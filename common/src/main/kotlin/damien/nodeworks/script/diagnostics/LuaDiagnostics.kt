package damien.nodeworks.script.diagnostics

import damien.nodeworks.script.LuaTokenizer
import damien.nodeworks.script.api.LuaApiRegistry
import damien.nodeworks.script.api.LuaType

/**
 * Editor diagnostics analyzer for Nodeworks' Lua dialect (vanilla Lua + type
 * annotations + pairs/ipairs-free for-loops). Runs as a pure function over the
 * script text and a precomputed symbol table; ScriptEditor calls it on every
 * text-change and renders the resulting diagnostics as squiggles.
 *
 * Each rule emits diagnostics with a [Diagnostic.code] that's keyed in
 * [severityForRule]. Tweak severity there to dial a rule up or down without
 * touching its body. The diagnostic surface and the editor renderer are both
 * already severity-aware, so the change is a one-line edit.
 *
 * Rules currently implemented:
 *   * `unknown-identifier`, bare identifier references that don't resolve to a
 *     keyword, registered global, Lua stdlib member, or a name declared earlier
 *     in the script.
 *   * `unknown-method`, `:method` after a typed receiver where the type
 *     doesn't declare that method.
 *   * `unknown-property`, `.field` after a typed receiver where the type
 *     doesn't declare that field.
 *   * `nullable-misuse`, using a `T?` value through `:method` / `.field`
 *     without first narrowing it via `if x [then]` / `if x ~= nil [then]` /
 *     `assert(x)`. Detects nullables from explicit `local x: T?` annotations,
 *     `function f(x: T?)` parameters, and locals inferred from registry method
 *     calls that return `Optional` (e.g. `local items = network:find(...)`).
 *   * `nullable-arg`, passing a `T?` value as an argument to a method whose
 *     corresponding parameter is declared non-nullable. Catches the bug shape
 *     `io_1:insert(items)` where `items: ItemsHandle?` but `:insert` expects
 *     a non-nullable `ItemsHandle`. Same nullable detection + narrowing logic
 *     as `nullable-misuse`.
 *   * `ambiguous-card-name`, `network:get("name")` where the active network
 *     has multiple cards / breakers / placers literally named `"name"`. The
 *     runtime returns the first match, but the player probably wants either a
 *     specific suffixed alias (`name_1`, `name_2`) or `:find("name_*")` for
 *     all of them. Soft hint, not an error.
 */
object LuaDiagnostics {

    /** Per-rule severity. Adjust here to flip a rule's underline colour and
     *  tone of voice; rules read this table at emit time. */
    val severityForRule: Map<String, Severity> = mapOf(
        "unknown-identifier" to Severity.ERROR,
        "unknown-method" to Severity.ERROR,
        "unknown-property" to Severity.ERROR,
        "nullable-misuse" to Severity.WARNING,
        "nullable-arg" to Severity.WARNING,
        "ambiguous-card-name" to Severity.HINT,
        "handler-no-pull" to Severity.HINT,
        "handler-unused-input" to Severity.HINT,
    )

    // -----------------------------------------------------------------------
    // Lifted regex constants. analyze() runs per keystroke. Every inline
    // `Regex("""...""")` was recompiling per call, hoisting the static ones
    // here compiles each pattern once for the lifetime of the JVM. Patterns
    // that interpolate a runtime string (variable name in narrowing checks,
    // module export prefix) stay inline since there isn't a stable cache key.
    // -----------------------------------------------------------------------
    private val GET_CALL: Regex = Regex(""":get\s*\(\s*(['"])([^'"]+)\1""")
    private val ROUTE_FROM_TO_CALL: Regex =
        Regex(""":(route|from|to)\s*\(\s*(['"])([^'"]+)\2""")
    private val FUNCTION_DEF: Regex =
        Regex("""\b(?:local\s+)?function\s+(\w+(?:\.\w+)?)\s*\(([^)]*)\)""")
    private val REQUIRE_LOCAL: Regex =
        Regex("""\blocal\s+(\w+)\s*=\s*require\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    private val MODULE_RETURN: Regex =
        Regex("""^\s*return\s+(\w+)\s*$""", RegexOption.MULTILINE)

    // findNullableVars passes
    private val LOCAL_TYPE_DECL: Regex = Regex("""\blocal\s+(\w+)\s*:""")
    private val PARAM_TYPE_DECL: Regex = Regex("""[(,]\s*(\w+)\s*:""")
    private val LOCAL_TYPE_NULLABLE: Regex = Regex("""\blocal\s+(\w+)\s*:\s*\w+\?""")
    private val PARAM_TYPE_NULLABLE: Regex = Regex("""[(,]\s*(\w+)\s*:\s*\w+\?""")
    private val LOCAL_BIND_LHS: Regex = Regex("""\blocal\s+(\w+)\s*=""")

    // collectDeclaredNames patterns (run across the whole script).
    private val DECL_LOCAL_NAMES: Regex =
        Regex("""\blocal\s+([\w_]+(?:\s*[,:]\s*(?:[\w_]+|\{[^}]*\}))*)""")
    private val DECL_FUNCTION_BARE: Regex =
        Regex("""\b(?:local\s+)?function\s+([\w_]+)""")
    private val DECL_FUNCTION_QUALIFIED: Regex =
        Regex("""\bfunction\s+([\w_]+)\s*\.""")
    private val DECL_FUNCTION_PARAMS: Regex =
        Regex("""\bfunction\b\s*[\w_.:]*\s*\(([^)]*)\)""")
    private val DECL_FOR_NUMERIC: Regex =
        Regex("""\bfor\s+([\w_]+(?:\s*,\s*[\w_]+)*)\s*=""")
    private val DECL_FOR_IN: Regex =
        Regex("""\bfor\s+([\w_]+(?:\s*,\s*[\w_]+)*)\s+in\b""")

    // Type-annotation extraction.
    private val ANN_LOCAL_TYPE_BODY: Regex =
        Regex("""\blocal\s+\w+\s*:\s*(\w[\w_]*\??|\{[^}]*})""")
    private val ANN_PARAM_TYPE_BODY: Regex =
        Regex("""[(,]\s*\w+\s*:\s*(\w[\w_]*\??|\{[^}]*})""")

    /** Returns the names that are nullable AT [offset] in [text]. A name counts as
     *  nullable when (1) the analyzer would put it in `nullableVars` (explicit
     *  `T?` annotation, function-param `T?`, or RHS that resolves to `Optional`),
     *  AND (2) the offset isn't inside a narrowing region for that name (`if name
     *  then`, `if name ~= nil then`, `assert(name)`, `if not name then return end`).
     *
     *  Lets the editor's hover tooltip and autocomplete suggestion rendering
     *  surface nullability the same way the diagnostic analyzer sees it, so
     *  hovering `all` after `local all = io_1:find(...)` shows `all: ItemsHandle?`,
     *  but inside `if all then ...` it shows `all: ItemsHandle`.
     */
    fun nullablesAtOffset(
        text: String,
        offset: Int,
        symbols: Map<String, String> = emptyMap(),
    ): Set<String> {
        if (text.isBlank()) return emptySet()
        val nullableVars = findNullableVars(text, symbols)
        if (nullableVars.isEmpty()) return emptySet()
        return nullableVars.filterTo(mutableSetOf()) { name ->
            val regions = findNarrowingRegions(text, name)
            regions.none { offset in it }
        }
    }

    /**
     * Run every rule against [text] and return the union of diagnostics they
     * produce. [symbols] maps a local variable name to the type the autocomplete
     * inferred for it (e.g. `"card" to "CardHandle"`); used by the typed-receiver
     * rules to look up methods/properties. Pass an empty map to skip those.
     *
     * [otherScripts] is the rest of the workspace's scripts keyed by name, used
     * to resolve `local foo = require("foo")` cross-script lookups so callers like
     * `foo.bar(items)` can flag against the imported module's declared param
     * types. Pass an empty map (the default) to disable cross-script analysis.
     *
     * [ambiguousNetworkNames] is the set of literal names on the active network
     * that are shared by ≥2 cards / breakers / placers. The `ambiguous-card-name`
     * rule flags `network:get("<name>")` calls whose argument is in this set.
     * Pass an empty set (the default) to disable the check, e.g. when running
     * the analyzer outside a terminal where there's no live network attached.
     */
    fun analyze(
        text: String,
        symbols: Map<String, String> = emptyMap(),
        otherScripts: Map<String, String> = emptyMap(),
        ambiguousNetworkNames: Set<String> = emptySet(),
        processingApis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo> = emptyList(),
    ): List<Diagnostic> {
        if (text.isBlank()) return emptyList()

        // Pre-pass: collect everything that COUNTS as declared. The unknown-id
        // rule needs this set to decide whether a bare reference is a typo or
        // just a name the user introduced earlier in the script.
        val declared = collectDeclaredNames(text)
        val typeAnnotationRanges = collectTypeAnnotationRanges(text)

        val out = mutableListOf<Diagnostic>()
        out += checkUnknownIdentifiers(text, declared, typeAnnotationRanges, symbols)

        // Nullable rules share the same per-script detection cost (finding nullable
        // names, building narrowing regions, locating declaration sites). Compute
        // once, feed all the nullable rules.
        val nullableVars = findNullableVars(text, symbols)
        val declarationNameRanges = collectDeclarationNameRanges(text)
        if (nullableVars.isNotEmpty()) {
            val narrowingByVar = nullableVars.associateWith { findNarrowingRegions(text, it) }
            out += checkNullableMisuse(
                text, typeAnnotationRanges, nullableVars, narrowingByVar, declarationNameRanges,
            )
            out += checkNullableArgs(
                text, symbols, nullableVars, narrowingByVar, declarationNameRanges,
                otherScripts,
            )
        }
        // The chain-on-nullable rule is independent of the script's declared nullables,
        // it fires whenever a chain step's return type is `T?`. Always run it.
        out += checkNullableChainAccess(text, symbols, nullableVars, declarationNameRanges)

        if (ambiguousNetworkNames.isNotEmpty()) {
            out += checkAmbiguousNetworkGet(text, ambiguousNetworkNames)
        }

        // Handler-shape hints. Both rules share the same handler-span scan, so
        // build it once. Skip the work entirely when the script has no
        // `network:handle(` call to begin with, the substring check is O(n) and
        // common-case scripts (non-handler logic) avoid the regex pass.
        if (text.contains("network:handle")) {
            val handlerSpans = findHandlerSpans(text)
            if (handlerSpans.isNotEmpty()) {
                out += checkHandlersWithoutPull(text, handlerSpans)
                if (processingApis.isNotEmpty()) {
                    out += checkHandlersWithUnusedInputs(text, handlerSpans, processingApis)
                }
            }
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rule: ambiguous-card-name
    // ──────────────────────────────────────────────────────────────────────

    /** Flag bare-name calls into duplicated entity names on the active network.
     *  Two flavours:
     *
     *  * `:get("name")`, singular lookup. Runtime returns first match. Hint
     *    suggests an explicit suffixed alias or a `:find("name_*")` glob.
     *  * `:route("name", ...)` / `:from("name")` / `:to("name")`, collection
     *    operation that almost certainly meant "every duplicate". Hint
     *    suggests the glob form `name_*` so all matching cards participate.
     *
     *  The runtime path still works either way; this rule is purely a UX nudge
     *  toward an unambiguous spelling. */
    private fun checkAmbiguousNetworkGet(
        text: String,
        ambiguousNames: Set<String>,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val stripped = stripComments(text)

        // `<receiver>:get("<name>")`, singular lookup. Both `network:get` and
        // `Channel:get` flow through the same bare-name resolution.
        val getPattern = GET_CALL
        for (m in getPattern.findAll(stripped)) {
            val name = m.groupValues[2]
            if (name !in ambiguousNames) continue
            val nameRange = m.groups[2]!!.range
            diagnostics.add(
                Diagnostic(
                    severity = severityFor("ambiguous-card-name"),
                    range = TextRange(nameRange.first, nameRange.last + 1),
                    code = "ambiguous-card-name",
                    message = "Multiple entities on this network are named '$name'. " +
                            "Use a suffixed alias like `${name}_1` for a specific card, or " +
                            "`network:cards(\"${name}_*\")` for all of them.",
                ),
            )
        }

        // Collection-receiver methods: `:route(...)`, `:from(...)`, `:to(...)`.
        // First string arg is a card alias that's expected to glob to multiple
        // cards in the duplicate-name case. Hint suggests the glob.
        val collectionPattern = ROUTE_FROM_TO_CALL
        for (m in collectionPattern.findAll(stripped)) {
            val method = m.groupValues[1]
            val name = m.groupValues[3]
            if (name !in ambiguousNames) continue
            val nameRange = m.groups[3]!!.range
            diagnostics.add(
                Diagnostic(
                    severity = severityFor("ambiguous-card-name"),
                    range = TextRange(nameRange.first, nameRange.last + 1),
                    code = "ambiguous-card-name",
                    message = "Multiple cards on this network are named '$name'. " +
                            ":$method on a bare name only targets the first match. Use " +
                            "`${name}_*` to apply to all of them, or a suffixed alias like " +
                            "`${name}_1` for a specific one.",
                ),
            )
        }
        return diagnostics
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rule: unknown-identifier (+ unknown-method, unknown-property)
    // ──────────────────────────────────────────────────────────────────────

    private fun checkUnknownIdentifiers(
        text: String,
        declared: Set<String>,
        typeAnnotationRanges: List<TextRange>,
        symbols: Map<String, String>,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val knownGlobals = collectKnownGlobals()
        val knownStdlibMembers = STDLIB_MEMBERS

        // Walk the token stream with running global offsets. One split feeds
        // both the line iteration and the tokenizer, halving the per-pass
        // tokenisation cost.
        val lines = text.split('\n')
        val tokenLines = LuaTokenizer.tokenizeLines(lines)
        var lineStart = 0
        for ((lineIdx, line) in lines.withIndex()) {
            val tokens = tokenLines[lineIdx]
            var localOffset = 0
            for ((tokIdx, token) in tokens.withIndex()) {
                val tokenStart = lineStart + localOffset
                val tokenEnd = tokenStart + token.text.length
                localOffset += token.text.length

                // DEFAULT and FUNCTION tokens are candidate identifier references.
                // The tokenizer classifies any identifier followed by `(` as FUNCTION,
                // including user-defined functions that don't exist (typos), so we
                // can't skip FUNCTION-coloured tokens, we still need to check whether
                // the name is actually declared. KEYWORDs, STRINGs, COMMENTs, NUMBERs
                // are always non-references and are always skipped.
                if (token.type != LuaTokenizer.TokenType.DEFAULT &&
                    token.type != LuaTokenizer.TokenType.FUNCTION
                ) continue
                if (!isIdentifierLike(token.text)) continue

                val tokenRange = TextRange(tokenStart, tokenEnd)
                if (typeAnnotationRanges.any { it.encloses(tokenRange) }) continue

                // Member access: preceded by `:` or `.`. Look back through the
                // current line's tokens for the separator.
                val separator = precedingSeparator(tokens, tokIdx)
                if (separator != null) {
                    val receiverName = receiverBeforeSeparator(tokens, tokIdx, separator.first)
                    if (receiverName != null) {
                        diagnoseMember(
                            receiverName, separator.second, token.text, tokenRange, symbols,
                        )?.let { diagnostics.add(it) }
                    }
                    continue
                }

                // Bare identifier: must be a keyword, registered global, stdlib
                // module name, or a name declared in the script.
                if (token.text in declared) continue
                if (token.text in knownGlobals) continue
                if (token.text in knownStdlibMembers) continue
                if (token.text in LuaTokenizer.KEYWORDS) continue

                diagnostics.add(
                    Diagnostic(
                        severity = severityFor("unknown-identifier"),
                        range = tokenRange,
                        code = "unknown-identifier",
                        message = "Unknown identifier '${token.text}'",
                    )
                )
            }
            // +1 for the newline that split() consumed.
            lineStart += line.length + 1
        }
        return diagnostics
    }

    /** Build a method/property diagnostic when [member] doesn't resolve on
     *  [receiverName]'s type. Returns null when we can't determine the receiver
     *  type, which means we don't have enough information to flag, not that the
     *  member is necessarily known. */
    private fun diagnoseMember(
        receiverName: String,
        separator: Char,
        member: String,
        memberRange: TextRange,
        symbols: Map<String, String>,
    ): Diagnostic? {
        // Lua stdlib modules (string, math, table) aren't in the registry, but
        // they have hand-rolled member lists below. Validate them first so a
        // typo on `math.maxx` flags instead of falling through to the registry
        // path which would early-return on the missing module type.
        if (receiverName in LUA_STDLIB_MODULES) {
            if (member in stdlibMembersFor(receiverName)) return null
            return Diagnostic(
                severity = severityFor("unknown-method"),
                range = memberRange,
                code = "unknown-method",
                message = "'$receiverName' has no member '$member'",
            )
        }

        // Receiver type lookup priority:
        //   1. Module global (network, scheduler, importer, stocker, ...)
        //   2. Symbol table entry (typed local, function param)
        // Otherwise we don't know the type, skip the check (could be a require'd
        // module or a user-table field, both of which we don't validate yet).
        val type = LuaApiRegistry.moduleType(receiverName)?.name
            ?: symbols[receiverName]?.trimEnd('?')
            ?: return null

        // Registry path: methods + properties for the resolved type.
        val methods = LuaApiRegistry.methodsOf(type).map { it.displayName }
        val properties = LuaApiRegistry.propertiesOf(type).map { it.displayName }
        if (member in methods) return null
        if (member in properties) return null
        if (methods.isEmpty() && properties.isEmpty()) {
            // No spec at all for this type, can't validate. Skip rather than flag
            // every member access on, say, an InputItems table whose fields are
            // recipe-derived and not statically declared.
            return null
        }

        val code = if (separator == ':') "unknown-method" else "unknown-property"
        return Diagnostic(
            severity = severityFor(code),
            range = memberRange,
            code = code,
            message = "$type has no ${if (separator == ':') "method" else "property"} '$member'",
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rule: nullable-misuse
    // ──────────────────────────────────────────────────────────────────────

    private fun checkNullableMisuse(
        text: String,
        typeAnnotationRanges: List<TextRange>,
        nullableVars: Set<String>,
        narrowingByVar: Map<String, List<TextRange>>,
        declarationNameRanges: List<TextRange>,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val lines = text.split('\n')
        val tokenLines = LuaTokenizer.tokenizeLines(lines)
        var lineStart = 0
        for ((lineIdx, line) in lines.withIndex()) {
            val tokens = tokenLines[lineIdx]
            var localOffset = 0
            for ((tokIdx, token) in tokens.withIndex()) {
                val tokenStart = lineStart + localOffset
                val tokenEnd = tokenStart + token.text.length
                localOffset += token.text.length

                if (token.type != LuaTokenizer.TokenType.DEFAULT &&
                    token.type != LuaTokenizer.TokenType.FUNCTION
                ) continue
                if (token.text !in nullableVars) continue

                val tokenRange = TextRange(tokenStart, tokenEnd)
                if (typeAnnotationRanges.any { it.encloses(tokenRange) }) continue
                if (declarationNameRanges.any { it.encloses(tokenRange) }) continue

                // We only flag MEMBER ACCESS, the `name` token must be followed
                // (after blank tokens) by `:` or `.`. A bare reference of a nullable
                // is fine, it's only the dereference that crashes on nil.
                var nextIdx = tokIdx + 1
                while (nextIdx < tokens.size && tokens[nextIdx].text.isBlank()) nextIdx++
                val next = tokens.getOrNull(nextIdx) ?: continue
                if (next.text != ":" && next.text != ".") continue

                val regions = narrowingByVar[token.text] ?: emptyList()
                if (regions.any { tokenStart in it }) continue

                diagnostics.add(
                    Diagnostic(
                        severity = severityFor("nullable-misuse"),
                        range = tokenRange,
                        code = "nullable-misuse",
                        message = "'${token.text}' may be nil here. Narrow it first with " +
                                "`if ${token.text} then ... end` or `assert(${token.text})`.",
                    ),
                )
            }
            lineStart += line.length + 1
        }
        return diagnostics
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rule: nullable-arg
    // ──────────────────────────────────────────────────────────────────────

    /** Flag `<call>(NAME, ...)` sites where NAME is a known nullable, the call site
     *  isn't inside a narrowing region, and the corresponding parameter is declared
     *  non-nullable. Three call shapes are supported:
     *
     *  * `obj:method(NAME)` where `obj` is a bare ident (module global or a symbol-
     *    table local). The receiver type comes from
     *    [LuaApiRegistry.moduleType] (for module globals like `network`, `scheduler`,
     *    `importer`, `stocker`) or from [symbols] (for typed locals the autocomplete
     *    inferred). Anything we can't resolve (untyped local, typo) is skipped
     *    silently rather than guessed.
     *
     *  * Chained `obj:m1():m2(NAME)` and longer chains. The receiver of the final
     *    call is resolved by walking back through [resolveExprTypeBefore] which
     *    recursively peels `expr(:m | .f)*` shapes off, looking up each step's
     *    return / property type in the registry.
     *
     *  * Bare `userFunc(NAME)` calls into user-defined functions declared earlier
     *    in the same script with `local function f(x: T)` / `function f(x: T)`.
     *    Param annotations are pulled from those declarations by
     *    [collectUserFunctions].
     *
     *  Limitations (intentional, fine for v1):
     *    * Only bare-name arguments. `io_1:insert(items or fallback)` doesn't flag,
     *      complex arg expressions aren't worth parsing here.
     *    * `function obj.m(...)` / `function obj:m(...)` user-defined methods aren't
     *      tracked. Callers of those would attempt the typed-receiver path and skip
     *      because the method isn't in the registry.
     */
    private fun checkNullableArgs(
        text: String,
        symbols: Map<String, String>,
        nullableVars: Set<String>,
        narrowingByVar: Map<String, List<TextRange>>,
        declarationNameRanges: List<TextRange>,
        otherScripts: Map<String, String>,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        // Two sources of user-function specs: this script (named + table-method
        // declarations) and any modules pulled in via `local x = require("y")`.
        // Merged into one map keyed by either bare name (`doThing`) or qualified
        // name (`foo.bar`) so the call-site walk can look up either shape.
        val userFunctions = collectUserFunctions(text) + collectImportedFunctions(text, otherScripts)
        val flat = flattenTokens(text)

        for ((idx, t) in flat.withIndex()) {
            // A call site is `<callName> ( ... )`. Anchor on the `(` so we can find
            // the call name (token just before, after blanks) and the receiver
            // (whatever's before the name's separator).
            if (t.text != "(") continue
            val nameIdx = prevNonBlankIdx(flat, idx) ?: continue
            val nameTok = flat[nameIdx]
            if (!isIdentifierLike(nameTok.text)) continue
            if (nameTok.text in LuaTokenizer.KEYWORDS) continue
            if (nameTok.type == LuaTokenizer.TokenType.STRING ||
                nameTok.type == LuaTokenizer.TokenType.COMMENT
            ) continue

            // Skip declaration sites: `function NAME(`, `local function NAME(`.
            // The token before the name (if any) is `function` in declarations.
            val prevIdx = prevNonBlankIdx(flat, nameIdx)
            val prevText = prevIdx?.let { flat[it].text }
            if (prevText == "function") continue

            val callName = nameTok.text

            // Determine call shape from the separator before the name.
            data class ResolvedCall(val params: List<LuaType.Param>, val description: String)

            val resolved: ResolvedCall = when (prevText) {
                ":" -> {
                    // Method call. Receiver expression ends at the `:` token.
                    val recvType = resolveExprTypeBefore(text, flat[prevIdx!!].start, symbols)
                        ?: continue
                    val doc = LuaApiRegistry.methodsOf(recvType)
                        .firstOrNull { it.displayName == callName } ?: continue
                    ResolvedCall(doc.params, "$recvType:$callName")
                }

                "." -> {
                    // `expr.method(...)`. We use this path for table-method calls
                    // declared as `function foo.bar(...)` (either in this script or
                    // imported via `require`). The receiver must be a bare ident so
                    // we can build the qualified key `foo.bar` to look up the spec.
                    val recvIdx = prevNonBlankIdx(flat, prevIdx!!) ?: continue
                    val recvTok = flat[recvIdx]
                    if (!isIdentifierLike(recvTok.text)) continue
                    if (recvTok.text in LuaTokenizer.KEYWORDS) continue
                    val key = "${recvTok.text}.$callName"
                    val func = userFunctions[key] ?: continue
                    ResolvedCall(func, key)
                }

                else -> {
                    // Bare function call. Look up user-defined functions; built-in
                    // globals are skipped (they take `Any` so wouldn't flag anyway).
                    val func = userFunctions[callName] ?: continue
                    ResolvedCall(func, callName)
                }
            }

            val openParenIdx = t.start
            val closeParenIdx = findMatchingClose(text, openParenIdx) ?: continue
            val argRanges = splitTopLevelArgs(text, openParenIdx + 1, closeParenIdx)

            for ((argIdx, argRange) in argRanges.withIndex()) {
                val rawArg = text.substring(argRange.start, argRange.end)
                val leadingWs = rawArg.length - rawArg.trimStart().length
                val argName = rawArg.trim()
                if (argName !in nullableVars) continue

                val nameStart = argRange.start + leadingWs
                val nameEnd = nameStart + argName.length

                val regions = narrowingByVar[argName] ?: emptyList()
                if (regions.any { nameStart in it }) continue

                val param = resolved.params.getOrNull(argIdx) ?: continue
                // Skip when the param's declared type tolerates nil. Optional<T>
                // explicitly does, and primitives like `any` accept anything (they
                // are the "we don't care" type). Only registered Named types
                // (CardHandle, ItemsHandle, ...) are taken as a strict non-null
                // contract worth warning about.
                if (param.type is LuaType.Optional) continue
                if (LuaType.unwrap(param.type) !is LuaType.Named) continue

                diagnostics.add(
                    Diagnostic(
                        severity = severityFor("nullable-arg"),
                        range = TextRange(nameStart, nameEnd),
                        code = "nullable-arg",
                        message = "'$argName' may be nil here. " +
                                "${resolved.description} expects ${param.type.display} for '${param.name}'. " +
                                "Narrow with `if $argName then ... end` or `assert($argName)` first.",
                    ),
                )
            }
        }
        return diagnostics
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rule: nullable chain access (reuses the `nullable-misuse` code so the
    // squiggle colour and dial-up/down knob match)
    // ──────────────────────────────────────────────────────────────────────

    /** Flag `expr:method` / `expr.field` access where `expr`'s declared type is
     *  [LuaType.Optional]. Catches the bug shape:
     *
     *      network:channel("c"):getFirst("io"):face("top")
     *      --                                  ^^^^ may be nil
     *
     *  `getFirst` returns `CardHandle?`, so chaining `:face` on it can hit nil.
     *  This rule covers chained call results (the case where `expr` is itself a
     *  call) and chained property accesses; the bare-name case (`x:method()`
     *  where `x` is a known nullable variable) stays the responsibility of
     *  [checkNullableMisuse] to avoid double-flagging.
     *
     *  Narrowing isn't applied here: a chain step's nullable result has no name
     *  to check against. The fix is to capture the result into a local and
     *  narrow that, which switches us back to the bare-name path.
     */
    private fun checkNullableChainAccess(
        text: String,
        symbols: Map<String, String>,
        nullableVars: Set<String>,
        declarationNameRanges: List<TextRange>,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val flat = flattenTokens(text)

        for ((idx, sepTok) in flat.withIndex()) {
            if (sepTok.text != ":" && sepTok.text != ".") continue

            // Skip type-annotation positions: `local x: T?`'s `:` looks like a
            // separator but is part of a declaration, not a member access.
            val sepRange = TextRange(sepTok.start, sepTok.start + 1)
            if (declarationNameRanges.any { it.start <= sepRange.start && sepRange.end <= it.end + 1 }) continue

            // Skip when the receiver is a bare identifier already in nullableVars,
            // [checkNullableMisuse] handles those (and includes narrowing).
            val prevIdx = prevNonBlankIdx(flat, idx) ?: continue
            val prevTok = flat[prevIdx]
            if (isIdentifierLike(prevTok.text) && prevTok.text in nullableVars) continue
            // Likewise skip when the receiver is just a bare ident with no prior
            // chain ([checkNullableMisuse] either covers it or the type isn't
            // nullable, no work for us either way).
            if (isIdentifierLike(prevTok.text)) {
                val beforeBareIdx = prevNonBlankIdx(flat, prevIdx)
                if (beforeBareIdx == null || (flat[beforeBareIdx].text != ")" &&
                            flat[beforeBareIdx].text != "." && flat[beforeBareIdx].text != "]")
                ) continue
            }

            val recvType = resolveExprReturnType(text, sepTok.start, symbols) ?: continue
            if (recvType !is LuaType.Optional) continue

            // Underline the member name (token after the separator). That's where
            // the dereference happens, so it's the most actionable location.
            val memberIdx = nextNonBlankIdx(flat, idx) ?: continue
            val memberTok = flat[memberIdx]
            if (!isIdentifierLike(memberTok.text)) continue
            if (memberTok.text in LuaTokenizer.KEYWORDS) continue

            val accessKind = if (sepTok.text == ":") "method ':${memberTok.text}'" else "field '.${memberTok.text}'"
            diagnostics.add(
                Diagnostic(
                    severity = severityFor("nullable-misuse"),
                    range = TextRange(memberTok.start, memberTok.start + memberTok.text.length),
                    code = "nullable-misuse",
                    message = "Receiver is ${recvType.display}, may be nil. " +
                            "Capture the result into a local and narrow before calling $accessKind.",
                ),
            )
        }
        return diagnostics
    }

    private fun nextNonBlankIdx(tokens: List<FlatToken>, fromIdx: Int): Int? {
        var i = fromIdx + 1
        while (i < tokens.size) {
            if (!tokens[i].text.isBlank()) return i
            i++
        }
        return null
    }

    /** Replace every comment character in [text] with a space, preserving offsets
     *  and newlines so positions computed against the masked text remain valid in
     *  the original. Lets the regex-based collectors below ignore declarations
     *  that the player has commented out. Detection is by [LuaTokenizer.COMMENT_COLOR]
     *  rather than `TokenType.COMMENT`, the tokenizer assigns the colour but
     *  leaves the type as DEFAULT for line comments and the body of block
     *  comments, only the explicit BLOCK_COMMENT_START/END markers carry a
     *  comment-specific TokenType. */
    private fun stripComments(text: String): String {
        if (text.isEmpty()) return text
        val out = CharArray(text.length)
        text.toCharArray(out, 0, 0, text.length)
        val lines = text.split('\n')
        val tokenLines = LuaTokenizer.tokenizeLines(lines)
        var lineStart = 0
        for ((lineIdx, line) in lines.withIndex()) {
            val toks = tokenLines.getOrNull(lineIdx) ?: emptyList()
            var localOff = 0
            for (t in toks) {
                val isComment = t.color == LuaTokenizer.COMMENT_COLOR ||
                        t.type == LuaTokenizer.TokenType.BLOCK_COMMENT_START ||
                        t.type == LuaTokenizer.TokenType.BLOCK_COMMENT_END
                if (isComment) {
                    for (k in 0 until t.text.length) {
                        val pos = lineStart + localOff + k
                        if (pos < out.size && out[pos] != '\n') out[pos] = ' '
                    }
                }
                localOff += t.text.length
            }
            lineStart += line.length + 1
        }
        return String(out)
    }

    /** A single token with its absolute character offset in the source. Flat tokens
     *  let the call-site walk look forward and backward in a uniform stream rather
     *  than juggling per-line indices and offsets. */
    private data class FlatToken(
        val text: String,
        val type: LuaTokenizer.TokenType,
        val start: Int,
    )

    private fun flattenTokens(text: String): List<FlatToken> {
        val out = mutableListOf<FlatToken>()
        val lines = text.split('\n')
        val tokenLines = LuaTokenizer.tokenizeLines(lines)
        var lineStart = 0
        for ((lineIdx, line) in lines.withIndex()) {
            val toks = tokenLines.getOrNull(lineIdx) ?: emptyList()
            var localOff = 0
            for (tok in toks) {
                out.add(FlatToken(tok.text, tok.type, lineStart + localOff))
                localOff += tok.text.length
            }
            lineStart += line.length + 1
        }
        return out
    }

    private fun prevNonBlankIdx(tokens: List<FlatToken>, fromIdx: Int): Int? {
        var i = fromIdx - 1
        while (i >= 0) {
            if (!tokens[i].text.isBlank()) return i
            i--
        }
        return null
    }

    /** Resolve the FULL type produced by the Lua expression ending just before
     *  [endOffset] in [text]. Returns the LuaType including [LuaType.Optional]
     *  wrappers, so callers that care about nullability can detect it.
     *  Walks back through `expr(:method | .field)*` shapes recursively, and
     *  returns null if the type can't be determined.
     *
     *  Limitations: indexed access (`expr[i]`) and user-defined function returns
     *  aren't resolved. Both bail to null so the rest of the chain skips silently.
     */
    private fun resolveExprReturnType(
        text: String,
        endOffset: Int,
        symbols: Map<String, String>,
    ): LuaType? {
        var i = endOffset
        while (i > 0 && text[i - 1].isWhitespace()) i--
        if (i == 0) return null

        val ch = text[i - 1]

        // Call `expr(args)`, peel the `(args)` and recurse on the call name.
        if (ch == ')') {
            val openIdx = findMatchingOpenBefore(text, i - 1) ?: return null
            var nameEnd = openIdx
            while (nameEnd > 0 && text[nameEnd - 1].isWhitespace()) nameEnd--
            var nameStart = nameEnd
            while (nameStart > 0 &&
                (text[nameStart - 1].isLetterOrDigit() || text[nameStart - 1] == '_')
            ) nameStart--
            if (nameStart == nameEnd) return null
            val callName = text.substring(nameStart, nameEnd)

            var sepEnd = nameStart
            while (sepEnd > 0 && text[sepEnd - 1].isWhitespace()) sepEnd--
            if (sepEnd > 0) {
                val sepCh = text[sepEnd - 1]
                if (sepCh == ':') {
                    // Receiver lookup unwraps to the Named base, so `T?:m()` resolves
                    // to T's m(), same as Lua's runtime "use through nil-check"
                    // would expose. The Optional wrapping is preserved on the return
                    // type itself when the registry declares `m` as `T?`.
                    val recvName = resolveReceiverTypeName(text, sepEnd - 1, symbols)
                        ?: return null
                    return LuaApiRegistry.methodReturnType(recvName, callName)
                }
                if (sepCh == '.') {
                    val recvName = resolveReceiverTypeName(text, sepEnd - 1, symbols)
                        ?: return null
                    return LuaApiRegistry.propertiesOf(recvName)
                        .firstOrNull { it.displayName == callName }?.returnType
                }
            }
            // Bare function call: user-function returns aren't tracked here yet.
            return null
        }

        // Indexed access. v1 doesn't resolve element types from chains.
        if (ch == ']') return null

        // Identifier, bare ref or `expr.field`.
        if (!ch.isLetterOrDigit() && ch != '_') return null
        var nameStart = i
        while (nameStart > 0 &&
            (text[nameStart - 1].isLetterOrDigit() || text[nameStart - 1] == '_')
        ) nameStart--
        val name = text.substring(nameStart, i)

        var sepEnd = nameStart
        while (sepEnd > 0 && text[sepEnd - 1].isWhitespace()) sepEnd--
        if (sepEnd > 0 && text[sepEnd - 1] == '.') {
            val recvName = resolveReceiverTypeName(text, sepEnd - 1, symbols)
                ?: return null
            return LuaApiRegistry.propertiesOf(recvName)
                .firstOrNull { it.displayName == name }?.returnType
        }

        // Bare ident: module global or symbol-table local.
        val moduleType = LuaApiRegistry.moduleType(name)
        if (moduleType != null) return moduleType
        val symType = symbols[name] ?: return null
        val nullable = symType.endsWith("?")
        val baseName = symType.trimEnd('?')
        val baseType: LuaType = LuaApiRegistry.knownTypes()
            .firstOrNull { it.name == baseName }
            ?: LuaApiRegistry.knownModules().firstOrNull { it.name == baseName }
            ?: return null
        return if (nullable) baseType.optional() else baseType
    }

    /** Convenience wrapper around [resolveExprReturnType] that returns the
     *  unwrapped [LuaType.Named]'s name (so `T?` → `T`, `{ T }` → `T`). Used
     *  whenever a caller wants to look up methods/properties on the receiver
     *  type, where the Optional wrapper is irrelevant. */
    private fun resolveExprTypeBefore(
        text: String,
        endOffset: Int,
        symbols: Map<String, String>,
    ): String? = resolveReceiverTypeName(text, endOffset, symbols)

    /** Same as [resolveExprTypeBefore] but explicit: walk back, get the LuaType,
     *  unwrap to the Named base. Both methods exist so call sites can pick the
     *  one that documents intent ("I want a receiver type to look methods on"
     *  vs "I want the unwrapped name"). */
    private fun resolveReceiverTypeName(
        text: String,
        endOffset: Int,
        symbols: Map<String, String>,
    ): String? {
        val rt = resolveExprReturnType(text, endOffset, symbols) ?: return null
        return (LuaType.unwrap(rt) as? LuaType.Named)?.name
    }

    /** Find the `(` that matches a `)` at [closeIdx], walking backward and
     *  counting nested parens. Returns null if the paren is unbalanced (which
     *  means the receiver expression is malformed and we can't resolve it). */
    private fun findMatchingOpenBefore(text: String, closeIdx: Int): Int? {
        if (closeIdx >= text.length || text[closeIdx] != ')') return null
        var depth = 0
        var i = closeIdx
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i--
        }
        return null
    }

    /** Collect user-defined function signatures so the bare-call path and the
     *  `obj.method` path of [checkNullableArgs] can look up param types.
     *
     *  Recognises:
     *    * `local function NAME(params)` and `function NAME(params)`, keyed by NAME.
     *    * `function obj.NAME(params)` table-method declarations, keyed by `obj.NAME`.
     *
     *  Skips `function obj:NAME(...)` colon-method declarations. Their first param
     *  is an implicit `self` and matching arg-positions to params would need extra
     *  bookkeeping that v1 doesn't justify. Anonymous functions don't get an entry.
     */
    private fun collectUserFunctions(rawText: String): Map<String, List<LuaType.Param>> {
        val text = stripComments(rawText)
        val out = mutableMapOf<String, List<LuaType.Param>>()
        val pattern = FUNCTION_DEF
        for (match in pattern.findAll(text)) {
            val name = match.groupValues[1]
            val raw = match.groupValues[2]
            out[name] = parseUserParams(raw)
        }
        return out
    }

    /** Cross-script equivalent of [collectUserFunctions]. For each
     *  `local NAME = require("MODULE")` in [text], find MODULE's text in
     *  [otherScripts] and harvest its `function <export>.METHOD(...)` declarations,
     *  re-keying them as `NAME.METHOD` so the calling script's `NAME.METHOD(args)`
     *  call sites look the spec up cleanly.
     *
     *  The module's "export prefix" is taken from the LAST `return IDENT` line in
     *  the module's text. Modules following the standard idiom
     *  (`local M = {}; ...; return M`) match cleanly; modules that return a
     *  literal table or compute the export differently fall through silently.
     */
    private fun collectImportedFunctions(
        rawText: String,
        otherScripts: Map<String, String>,
    ): Map<String, List<LuaType.Param>> {
        if (otherScripts.isEmpty()) return emptyMap()
        val text = stripComments(rawText)
        val out = mutableMapOf<String, List<LuaType.Param>>()
        for (m in REQUIRE_LOCAL.findAll(text)) {
            val localName = m.groupValues[1]
            val moduleName = m.groupValues[2]
            val rawModuleText = otherScripts[moduleName] ?: continue
            // Strip the imported module's comments too, a fully-commented-out
            // module body should produce no exports, even if the comments contain
            // `function foo.bar(...)` shaped text.
            val moduleText = stripComments(rawModuleText)
            val exportPrefix = findModuleExportPrefix(moduleText) ?: continue
            val funcPattern = Regex(
                """\bfunction\s+${Regex.escape(exportPrefix)}\.(\w+)\s*\(([^)]*)\)"""
            )
            for (fm in funcPattern.findAll(moduleText)) {
                val methodName = fm.groupValues[1]
                val rawParams = fm.groupValues[2]
                out["$localName.$methodName"] = parseUserParams(rawParams)
            }
        }
        return out
    }

    /** Find the identifier in the LAST `return IDENT` line of [moduleText].
     *  Heuristic: any `return IDENT` line at the start (after leading whitespace)
     *  of a line counts; we take the textually last one as the module's export.
     *  Returns null when the module has no such return, in which case the
     *  cross-script lookup falls through to no harvested functions. Caller is
     *  expected to have stripped comments already so a commented-out
     *  `-- return foo` doesn't get mistaken for the real export. */
    private fun findModuleExportPrefix(moduleText: String): String? {
        return MODULE_RETURN.findAll(moduleText).lastOrNull()?.groupValues?.get(1)
    }

    /** Parse a comma-separated parameter list with optional `: Type` / `: Type?`
     *  annotations into [LuaType.Param]s. Untyped params get [LuaType.Primitive.Any]
     *  so they don't trigger the nullable-arg check (any-typed params accept
     *  anything, including nil, which is the safe default for un-annotated user
     *  code). Nullable annotations unwrap to [LuaType.Optional] so the caller's
     *  `is LuaType.Optional` check works the same as for registry-declared params.
     */
    private fun parseUserParams(raw: String): List<LuaType.Param> {
        val out = mutableListOf<LuaType.Param>()
        for (chunk in raw.split(',')) {
            val piece = chunk.trim()
            if (piece.isEmpty()) continue
            val colonIdx = piece.indexOf(':')
            if (colonIdx == -1) {
                out.add(LuaType.Param(piece, LuaType.Primitive.Any))
                continue
            }
            val pname = piece.substring(0, colonIdx).trim()
            val ptypeRaw = piece.substring(colonIdx + 1).trim()
            val nullable = ptypeRaw.endsWith("?")
            val ptypeName = ptypeRaw.trimEnd('?')
            // Look up the named type in the registry. If it's not registered (could
            // be a future / private type), fall back to Any so we don't false-flag.
            val baseType: LuaType = LuaApiRegistry.knownTypes()
                .firstOrNull { it.name == ptypeName }
                ?: LuaApiRegistry.knownModules().firstOrNull { it.name == ptypeName }
                ?: when (ptypeName) {
                    "number" -> LuaType.Primitive.Number
                    "string" -> LuaType.Primitive.String
                    "boolean" -> LuaType.Primitive.Boolean
                    else -> LuaType.Primitive.Any
                }
            val finalType = if (nullable) baseType.optional() else baseType
            out.add(LuaType.Param(pname, finalType))
        }
        return out
    }

    /** Walk forward from [openParenIdx] (which points at a `(`) and return the
     *  offset of the matching `)`. Counts nested `(`/`)` so a method call with
     *  parenthesised args resolves correctly. Returns null when the file ends
     *  before the close. Quick string/comment skips are not implemented here, the
     *  callers all match `:method(` patterns whose contents are real Lua code in
     *  practice. */
    private fun findMatchingClose(text: String, openParenIdx: Int): Int? {
        var depth = 0
        var i = openParenIdx
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** Split the half-open range `[start, end)` of [text] into top-level
     *  comma-separated argument ranges. Tracks nesting of `()`, `[]`, and `{}`
     *  so commas inside nested expressions don't split the arg list. Returns
     *  [TextRange]s in the original [text]'s offset space, for the diagnostic to
     *  point at the right characters. */
    private fun splitTopLevelArgs(text: String, start: Int, end: Int): List<TextRange> {
        if (start >= end) return emptyList()
        val out = mutableListOf<TextRange>()
        var depth = 0
        var argStart = start
        var i = start
        while (i < end) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out.add(TextRange(argStart, i))
                    argStart = i + 1
                }
            }
            i++
        }
        out.add(TextRange(argStart, end))
        return out
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Ranges covering the NAME side of a type-annotated declaration: `local NAME:`,
     *  `(NAME:`, `, NAME:`. Skipped by [checkNullableMisuse] so the analyzer doesn't
     *  treat the colon in a type annotation as a method-access separator. */
    private fun collectDeclarationNameRanges(rawText: String): List<TextRange> {
        val text = stripComments(rawText)
        val ranges = mutableListOf<TextRange>()
        LOCAL_TYPE_DECL.findAll(text).forEach {
            val r = it.groups[1]!!.range
            ranges.add(TextRange(r.first, r.last + 1))
        }
        PARAM_TYPE_DECL.findAll(text).forEach {
            val r = it.groups[1]!!.range
            ranges.add(TextRange(r.first, r.last + 1))
        }
        return ranges
    }

    /** Names of variables this script declares as nullable. Three sources:
     *
     *  1. Explicit annotations: `local x: T?` and `function f(x: T?)`.
     *  2. Inferred from a `local x = expr` whose RHS resolves to `T?`. The resolver
     *     walks `expr(:method | .field)*` chains so receivers can be module globals
     *     (`network:find(...)`), typed locals (`io_1:find(...)`), or longer chains
     *     (`network:channel("c"):getFirst("io")` → `CardHandle?`).
     *  3. Receiver type for typed locals comes from [symbols], the same map the
     *     autocomplete builds; nothing is inferred here that the autocomplete didn't
     *     already infer at hover time.
     */
    private fun findNullableVars(rawText: String, symbols: Map<String, String>): Set<String> {
        val text = stripComments(rawText)
        val out = mutableSetOf<String>()

        // Explicit `local x: T?` (and the rare comma-separated `local x: T?, y: U?`).
        LOCAL_TYPE_NULLABLE.findAll(text).forEach {
            out.add(it.groupValues[1])
        }
        // Function param annotations: `function f(x: T?, ...)`.
        PARAM_TYPE_NULLABLE.findAll(text).forEach {
            out.add(it.groupValues[1])
        }

        // Inferred from RHS expression. Walk every `local NAME =` and resolve the
        // RHS via [resolveExprReturnType]. If it returns Optional, the local is
        // nullable. Skip when an explicit annotation already covers the name, so a
        // user override (`local items: ItemsHandle = ...`) silences the warning
        // intentionally even when the runtime call would actually return `T?`.
        for (m in LOCAL_BIND_LHS.findAll(text)) {
            val name = m.groupValues[1]
            if (name in out) continue
            val rhsStart = m.range.last + 1
            val rhsEnd = findStatementEnd(text, rhsStart)
            val rt = resolveExprReturnType(text, rhsEnd, symbols) ?: continue
            if (rt is LuaType.Optional) out.add(name)
        }

        return out
    }

    /** Find the end of the statement starting at [fromOffset]. Walks forward to the
     *  first newline at depth 0 (paren/bracket/brace count) that isn't a chain or
     *  operator continuation. Lets multi-line chains like:
     *
     *      local x = network
     *          :channel("c")
     *          :getFirst("io")
     *
     *  resolve as a single expression even when each chain step lives on its own
     *  line and the `\n` between them is at depth 0. The continuation is recognised
     *  by either a leading `:` / `.` on the next line, or a trailing operator /
     *  comma / open-paren on the current line. */
    private fun findStatementEnd(text: String, fromOffset: Int): Int {
        var depth = 0
        var i = fromOffset
        while (i < text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '\n' -> if (depth == 0 && !isLineContinuation(text, i)) return i
            }
            i++
        }
        return text.length
    }

    /** True when the newline at [newlineIdx] is part of a multi-line statement, i.e.
     *  the next non-whitespace char is a chain-continuation `:` / `.` OR the previous
     *  non-whitespace char is a binary-operator / comma / open paren waiting on
     *  another operand. Reads tokens character-by-character, strings and comments
     *  aren't pre-stripped, but line-comment-on-prior-line is rare enough in chain
     *  RHSs that we don't bother special-casing it. */
    private fun isLineContinuation(text: String, newlineIdx: Int): Boolean {
        var j = newlineIdx + 1
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        if (j < text.length && (text[j] == ':' || text[j] == '.')) return true

        var k = newlineIdx - 1
        while (k >= 0 && (text[k] == ' ' || text[k] == '\t')) k--
        if (k >= 0 && text[k] in "+-*/%,(") return true

        return false
    }

    /** Regions of [text] where [name] is proven non-nil. The check fires a diagnostic
     *  when an access offset is NOT inside any of these regions.
     *
     *  Patterns recognised:
     *    * `if NAME then ... end`, body narrows NAME
     *    * `if NAME ~= nil then ... end`, body narrows NAME
     *    * `if nil ~= NAME then ... end`, same, reversed
     *    * `if not NAME then <terminate> end`, narrows NAME from after the `end`
     *      onward. Terminating statements: `return`, `error(...)`, `break`.
     *    * `if NAME == nil then <terminate> end` (and the reversed `nil == NAME`)
     *    * `assert(NAME [, msg])`, narrows NAME from the call site to end-of-script
     *
     *  These are all approximations, the "narrows to end-of-script" cases should
     *  really stop at the enclosing function boundary, and conditional `return`s
     *  inside the guard body would weaken the narrowing. For v1 we accept those as
     *  false negatives (under-flagging) since false positives annoy users more than
     *  missed warnings.
     *
     *  Other narrowing forms (`x = x or default`, ternary-style
     *  `local y = x and x:m() or nil`) are accepted by Lua but not yet decoded here.
     */
    private fun findNarrowingRegions(rawText: String, name: String): List<TextRange> {
        val text = stripComments(rawText)
        val regions = mutableListOf<TextRange>()
        val n = Regex.escape(name)

        // Truthy / explicit nil-check branches: body is narrowed up to the next
        // branch boundary (`elseif`, `else`, or matching `end`). Both `if NAME
        // then` and `elseif NAME then` start a narrowing branch, the elseif form
        // appears in chained checks like:
        //
        //     if a then ...
        //     elseif b then b:m()  -- `b` narrowed in this branch
        //     else ...
        //     end
        val truthyBranchHeads = listOf(
            Regex("""\bif\s+$n\s+then\b"""),
            Regex("""\bif\s+$n\s*~=\s*nil\s+then\b"""),
            Regex("""\bif\s+nil\s*~=\s*$n\s+then\b"""),
            Regex("""\belseif\s+$n\s+then\b"""),
            Regex("""\belseif\s+$n\s*~=\s*nil\s+then\b"""),
            Regex("""\belseif\s+nil\s*~=\s*$n\s+then\b"""),
        )
        for (pat in truthyBranchHeads) {
            for (m in pat.findAll(text)) {
                val thenEnd = m.range.last + 1
                val endOffset = findBranchBoundary(text, thenEnd) ?: continue
                regions.add(TextRange(thenEnd, endOffset))
            }
        }

        // Early-return guards: `if not NAME then return end` and friends. The body must
        // terminate control flow (return / break / error), in which case everything
        // AFTER the closing `end` is narrowed.
        val nilGuardPatterns = listOf(
            Regex("""\bif\s+not\s+$n\s+then\b"""),
            Regex("""\bif\s+$n\s*==\s*nil\s+then\b"""),
            Regex("""\bif\s+nil\s*==\s*$n\s+then\b"""),
        )
        val terminatorPattern = Regex("""\b(return|break)\b|\berror\s*\(""")
        for (pat in nilGuardPatterns) {
            for (m in pat.findAll(text)) {
                val thenEnd = m.range.last + 1
                val endOffset = findMatchingEnd(text, thenEnd) ?: continue
                val body = text.substring(thenEnd, endOffset)
                // Require the body to contain SOMETHING that terminates control flow.
                // Misses partial-flow cases (`if cond then return end` nested inside)
                // but catches the canonical idiom and avoids over-narrowing on
                // bodies that just print a warning.
                if (!terminatorPattern.containsMatchIn(body)) continue
                regions.add(TextRange(endOffset, text.length))
            }
        }

        for (m in Regex("""\bassert\s*\(\s*$n\s*[,)]""").findAll(text)) {
            regions.add(TextRange(m.range.last + 1, text.length))
        }

        return regions
    }

    /** Find the offset of the next branch boundary inside the current `if`/`elseif`
     *  body, that's the next `elseif`, `else`, or matching `end` at the same
     *  block depth. Used by [findNarrowingRegions] so a branch's narrowing doesn't
     *  spill into a sibling `else`/`elseif` where the variable could be the
     *  opposite truthiness. Skips deeper nested `if`/`for`/`while`/`repeat`/
     *  `function` blocks to keep the depth count honest.
     */
    private fun findBranchBoundary(text: String, fromOffset: Int): Int? {
        if (fromOffset >= text.length) return null
        val slice = text.substring(fromOffset)
        val tokenLines = LuaTokenizer.tokenizeLines(slice)
        val sliceLines = slice.split('\n')
        var depth = 1
        var lineStart = 0
        for ((lineIdx, line) in sliceLines.withIndex()) {
            val toks = tokenLines.getOrNull(lineIdx) ?: emptyList()
            var localOff = 0
            for (t in toks) {
                val tokenAbs = fromOffset + lineStart + localOff
                localOff += t.text.length
                if (t.type != LuaTokenizer.TokenType.KEYWORD) continue
                when (t.text) {
                    "if", "for", "while", "repeat", "function" -> depth++
                    "end", "until" -> {
                        depth--
                        if (depth == 0) return tokenAbs
                    }
                    // `elseif` / `else` close the current branch when we're
                    // directly inside the `if` they belong to (depth == 1, since
                    // `if` itself bumped the depth at construction time).
                    "elseif", "else" -> if (depth == 1) return tokenAbs
                }
            }
            lineStart += line.length + 1
        }
        return null
    }

    /** Find the offset of the keyword that closes the block opened just before
     *  [fromOffset]. Tracks nested `if/for/while/repeat/function` openers so we don't
     *  mis-pair when the body contains nested blocks. Returns null if no matching
     *  closer is found, in which case the caller treats the narrowing as
     *  inconclusive and skips it. */
    private fun findMatchingEnd(text: String, fromOffset: Int): Int? {
        if (fromOffset >= text.length) return null
        val slice = text.substring(fromOffset)
        val tokenLines = LuaTokenizer.tokenizeLines(slice)
        val sliceLines = slice.split('\n')
        var depth = 1
        var lineStart = 0
        for ((lineIdx, line) in sliceLines.withIndex()) {
            val toks = tokenLines.getOrNull(lineIdx) ?: emptyList()
            var localOff = 0
            for (t in toks) {
                val tokenAbs = fromOffset + lineStart + localOff
                localOff += t.text.length
                if (t.type != LuaTokenizer.TokenType.KEYWORD) continue
                when (t.text) {
                    "if", "for", "while", "repeat", "function" -> depth++
                    "end", "until" -> {
                        depth--
                        if (depth == 0) return tokenAbs
                    }
                }
            }
            lineStart += line.length + 1
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun severityFor(code: String): Severity =
        severityForRule[code] ?: Severity.WARNING

    /** Walk [tokens] backwards from [fromIdx] (exclusive) to find the most
     *  recent `:` or `.` separator. Returns the (tokenIndex, separator char)
     *  pair, or null if none was found before hitting a non-whitespace,
     *  non-separator token. */
    private fun precedingSeparator(
        tokens: List<LuaTokenizer.Token>,
        fromIdx: Int,
    ): Pair<Int, Char>? {
        var i = fromIdx - 1
        while (i >= 0) {
            val t = tokens[i].text
            if (t.isBlank()) {
                i--
                continue
            }
            return when (t) {
                ":" -> i to ':'
                "." -> i to '.'
                else -> null
            }
        }
        return null
    }

    /** Return the receiver identifier name that precedes [separatorIdx] on the
     *  same line. Skips blanks. Null when the previous non-blank token isn't an
     *  identifier (e.g. a `)` from a chain, which we don't try to resolve here). */
    private fun receiverBeforeSeparator(
        tokens: List<LuaTokenizer.Token>,
        @Suppress("UNUSED_PARAMETER") tokIdx: Int,
        separatorIdx: Int,
    ): String? {
        var i = separatorIdx - 1
        while (i >= 0) {
            val t = tokens[i].text
            if (t.isBlank()) {
                i--
                continue
            }
            return if (isIdentifierLike(t)) t else null
        }
        return null
    }

    private fun isIdentifierLike(s: String): Boolean {
        if (s.isEmpty()) return false
        if (!(s[0].isLetter() || s[0] == '_')) return false
        return s.all { it.isLetterOrDigit() || it == '_' }
    }

    /** All names declared in [text] regardless of scope. False positives (using
     *  a local outside its block) are accepted to keep the analyzer simple, the
     *  runtime would fail those at execution time anyway.
     *
     *  Comments are stripped before the regex passes so a commented-out
     *  declaration (`-- local foo = 5`) doesn't sneak `foo` into the declared set
     *  and silence a real unknown-identifier diagnostic on a downstream `print(foo)`. */
    private fun collectDeclaredNames(rawText: String): Set<String> {
        val text = stripComments(rawText)
        val names = mutableSetOf<String>()

        // local <name> [, <name>, ...] = ...
        // local <name>: <Type> = ...
        DECL_LOCAL_NAMES
            .findAll(text)
            .forEach { match ->
                val raw = match.groupValues[1]
                // Pull only the name half: split on `=` first if present, then
                // walk comma-separated entries and strip the `:Type` suffix.
                val nameSection = raw.substringBefore('=')
                for (chunk in nameSection.split(',')) {
                    val name = chunk.trim().substringBefore(':').trim()
                    if (isIdentifierLike(name)) names.add(name)
                }
            }

        // function <name>(...) and local function <name>(...)
        DECL_FUNCTION_BARE
            .findAll(text)
            .forEach { names.add(it.groupValues[1]) }

        // function <obj>.<name>(...), the .name half is the declared method
        // name, but we want the obj name in declared too so a downstream
        // reference to <obj> doesn't squiggle.
        DECL_FUNCTION_QUALIFIED
            .findAll(text)
            .forEach { names.add(it.groupValues[1]) }

        // Function parameters: `function name(a, b: T, c)`, extract the name list.
        // Also covers anonymous `function(a, b)` lambdas, table-method declarations
        // (`function foo.bar(a)`), and colon-method declarations (`function foo:bar(a)`).
        // The `[\w_.:]*` between `function` and `(` allows the qualified name to slip
        // through without re-entering the params regex.
        DECL_FUNCTION_PARAMS
            .findAll(text)
            .forEach { match ->
                val params = match.groupValues[1]
                for (chunk in params.split(',')) {
                    val name = chunk.trim().substringBefore(':').trim()
                    if (isIdentifierLike(name)) names.add(name)
                }
            }

        // For-loop bindings: `for x in ...` / `for k, v in ...` / `for i = a, b`.
        // The numeric form (`for i=1, 5 do`) has no required whitespace before `=`,
        // so we use `\s*` there. The generic form (`for x in xs`) needs at least one
        // space before `in` to keep us from chopping `for inner = 1, 5 do` at "in".
        DECL_FOR_NUMERIC
            .findAll(text)
            .forEach { match ->
                for (chunk in match.groupValues[1].split(',')) {
                    val name = chunk.trim()
                    if (isIdentifierLike(name)) names.add(name)
                }
            }
        DECL_FOR_IN
            .findAll(text)
            .forEach { match ->
                for (chunk in match.groupValues[1].split(',')) {
                    val name = chunk.trim()
                    if (isIdentifierLike(name)) names.add(name)
                }
            }

        return names
    }

    /** Collect ranges of type-annotation positions so the analyzer doesn't
     *  flag the type names there as unknown identifiers. The patterns are
     *  position-specific (each only matches in places type annotations can
     *  appear in this dialect) so they don't false-match `obj:method(` style
     *  member accesses, which look colon-separated but aren't annotations. */
    private fun collectTypeAnnotationRanges(rawText: String): List<TextRange> {
        val text = stripComments(rawText)
        val ranges = mutableListOf<TextRange>()
        // Each pattern captures the type token in group 1. Type tokens can be
        // a bare name (`Type`), a nullable (`Type?`), or a brace-form container
        // (`{ T }`, `{ [K]: V }`).
        val patterns = listOf(
            // local <name>: Type
            ANN_LOCAL_TYPE_BODY,
            // function param: `(name: Type` or `, name: Type`
            ANN_PARAM_TYPE_BODY,
            // return type annotation: `): Type`
            Regex("""\)\s*:\s*(\w[\w_]*\??|\{[^}]*})"""),
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val typeRange = match.groups[1]!!.range
                ranges.add(TextRange(typeRange.first, typeRange.last + 1))
            }
        }
        return ranges
    }

    private fun collectKnownGlobals(): Set<String> {
        val globals = mutableSetOf<String>()
        // Module aliases (network, scheduler, importer, stocker, ...).
        for (mod in LuaApiRegistry.knownModules()) {
            mod.moduleGlobal?.let { globals.add(it) }
        }
        // Top-level functions + Lua keywords + stdlib module names registered
        // through the same surface (print, clock, require, error, assert,
        // tostring, tonumber, pairs, ipairs, type, select, unpack, ...).
        for (doc in LuaApiRegistry.globals().values) {
            globals.add(doc.displayName)
        }
        return globals
    }

    /** Lua stdlib module names that the registry exposes as globals. Their
     *  member methods are tracked separately in [STDLIB_MEMBERS] keyed by
     *  module name. */
    private val LUA_STDLIB_MODULES = setOf("string", "math", "table")

    private val STDLIB_MEMBERS: Set<String> = LUA_STDLIB_MODULES + setOf(
        // Stdlib bare functions registered as top-level globals. Already in
        // [collectKnownGlobals]'s output, but listing them here makes the
        // bare-identifier check robust if the registry ever changes.
        "tostring", "tonumber", "type", "pairs", "ipairs", "select", "unpack",
        "print", "clock", "require", "error", "assert",
    )

    /** Hand-rolled member lists for the Lua stdlib modules. The registry
     *  doesn't carry these (they aren't part of the Nodeworks API surface),
     *  so we replicate the autocomplete's coverage here. */
    private fun stdlibMembersFor(module: String): Set<String> = when (module) {
        "string" -> setOf(
            "byte", "char", "find", "format", "gmatch", "gsub", "len",
            "lower", "match", "rep", "reverse", "sub", "upper",
        )

        "math" -> setOf(
            "abs", "acos", "asin", "atan", "atan2", "ceil", "cos", "cosh",
            "deg", "exp", "floor", "fmod", "frexp", "huge", "ldexp", "log",
            "log10", "max", "maxinteger", "min", "mininteger", "modf", "pi",
            "pow", "rad", "random", "randomseed", "sin", "sinh", "sqrt",
            "tan", "tanh", "tointeger", "type", "ult",
        )

        "table" -> setOf(
            "concat", "insert", "move", "pack", "remove", "sort", "unpack",
        )

        else -> emptySet()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rules: handler-no-pull, handler-unused-input
    // ──────────────────────────────────────────────────────────────────────

    /** One `network:handle("name", function(...) … end)` block found in the
     *  script. [bodyStart] / [bodyEnd] bracket the function body (between the
     *  `function(...)` closing paren and the matching `end`). Both rules read
     *  the same span data so we compute it once per analyze pass. */
    private data class HandlerSpan(
        val name: String,
        /** Range covering the whole `network:handle("...", function(...))`
         *  signature (everything up to but not including the body), used to
         *  anchor handler-shape diagnostics. Underlining the full header
         *  reads more clearly than highlighting just the name string when the
         *  hint is "this whole handler is missing something". */
        val headerRange: TextRange,
        /** Inclusive start of the body (first char after `function(...)`'s `)`). */
        val bodyStart: Int,
        /** Exclusive end of the body (position of the matching `end` keyword). */
        val bodyEnd: Int,
    )

    private val HANDLER_OPEN: Regex =
        Regex("""network:handle\s*\(\s*"([^"]+)"\s*,\s*function\s*\(([^)]*)\)""")

    /** Find every `network:handle("name", function(...) … end)` block and the
     *  body span of each. Body extents are computed by walking comment-stripped
     *  text and balancing `function`/`end` keywords starting at depth=1, the
     *  first `end` that brings depth back to 0 closes the handler. Nested
     *  user-defined `function … end` blocks inside the body don't confuse the
     *  walk because they push/pop together. */
    private fun findHandlerSpans(text: String): List<HandlerSpan> {
        // Strip comments first so a commented-out `network:handle(...)` doesn't
        // produce a phantom span and a commented `end` doesn't close the body
        // early. stripComments preserves offsets so the matched ranges remain
        // valid against the original text.
        val stripped = stripComments(text)
        val out = mutableListOf<HandlerSpan>()
        for (open in HANDLER_OPEN.findAll(stripped)) {
            val name = open.groupValues[1]
            // bodyStart is one past the `)` that closes the function signature.
            val bodyStart = open.range.last + 1
            val bodyEnd = findMatchingHandlerEnd(stripped, bodyStart) ?: continue
            out += HandlerSpan(
                name = name,
                headerRange = TextRange(open.range.first, bodyStart),
                bodyStart = bodyStart,
                bodyEnd = bodyEnd,
            )
        }
        return out
    }

    private val FUNCTION_OR_END_KW: Regex = Regex("""\b(function|end)\b""")

    /** Walk forward from [from] balancing `function`/`end` keywords starting at
     *  depth=1, return the offset of the `end` that returns depth to 0, or null
     *  when the body never closes (file ends mid-handler, common while typing). */
    private fun findMatchingHandlerEnd(text: String, from: Int): Int? {
        var depth = 1
        for (m in FUNCTION_OR_END_KW.findAll(text, startIndex = from)) {
            if (m.value == "function") {
                depth++
            } else {
                depth--
                if (depth == 0) return m.range.first
            }
        }
        return null
    }

    /** HINT when a handler body never calls `:pull(`. Without a `job:pull(...)`
     *  the executor's async wait never resolves, the craft hangs until the
     *  Processing-Set timeout fires. The check is a substring on the body of
     *  the comment-stripped text, `pull` is a Job-only method so the literal
     *  `:pull(` is unambiguous. */
    private fun checkHandlersWithoutPull(
        rawText: String,
        spans: List<HandlerSpan>,
    ): List<Diagnostic> {
        if (spans.isEmpty()) return emptyList()
        val stripped = stripComments(rawText)
        val out = mutableListOf<Diagnostic>()
        for (span in spans) {
            val body = stripped.substring(span.bodyStart, span.bodyEnd)
            if (body.contains(":pull(")) continue
            out += Diagnostic(
                severity = severityFor("handler-no-pull"),
                range = span.headerRange,
                code = "handler-no-pull",
                message = "Handler '${span.name}' never calls job:pull. The crafting CPU " +
                        "will wait for outputs that never arrive and time out the craft.",
            )
        }
        return out
    }

    /** HINT when a handler body never references one of its declared input
     *  fields. Looks at each handler's matching [ProcessingApiInfo] (by name),
     *  derives the per-slot identifier names via [HandlerParamNames.build],
     *  and emits one hint per input that the body doesn't mention. The literal
     *  `\bname\b` substring is what we check, dynamic lookups via `items[name]`
     *  or `for k,v in items` will trigger false positives, accepting that as a
     *  hint-tier risk. */
    private fun checkHandlersWithUnusedInputs(
        rawText: String,
        spans: List<HandlerSpan>,
        apis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo>,
    ): List<Diagnostic> {
        if (spans.isEmpty() || apis.isEmpty()) return emptyList()
        val stripped = stripComments(rawText)
        val byName = apis.associateBy { it.name }
        val out = mutableListOf<Diagnostic>()
        for (span in spans) {
            val api = byName[span.name] ?: continue
            if (api.inputs.isEmpty()) continue
            val expected =
                damien.nodeworks.card.HandlerParamNames.build(api.inputs)
            if (expected.isEmpty()) continue
            val body = stripped.substring(span.bodyStart, span.bodyEnd)
            // Bail when the body references `items` opaquely (table / loop /
            // index access). Once any of those shapes is in scope we can't tell
            // statically whether each slot is used.
            if (HANDLER_INDIRECT_ITEMS_USE.containsMatchIn(body)) continue
            val missing = expected.filterNot { name ->
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(body)
            }
            if (missing.isEmpty()) continue
            out += Diagnostic(
                severity = severityFor("handler-unused-input"),
                range = span.headerRange,
                code = "handler-unused-input",
                message = "Handler '${span.name}' never references " +
                        "${if (missing.size == 1) "input" else "inputs"} " +
                        missing.joinToString(", ") { "'$it'" } +
                        ". Pass them through items.<name> in the body or the craft " +
                        "will leave them stuck in the CPU buffer.",
            )
        }
        return out
    }

    /** Patterns that imply the handler accesses `items` opaquely, in which
     *  case the literal-name substring check would produce false positives.
     *  Bracket access, iteration, and full-reassignment all suppress the
     *  unused-input hint. The reassignment branch uses a negative lookahead
     *  so `local x = items.copperIngot` (a field access, not an alias) does
     *  *not* trigger suppression. */
    private val HANDLER_INDIRECT_ITEMS_USE: Regex = Regex(
        """\bitems\s*\[""" +
            """|\bfor\s+\w+(?:\s*,\s*\w+)?\s+in\s+items\b""" +
            """|\blocal\s+\w+\s*=\s*items(?![.\[\w])"""
    )
}
