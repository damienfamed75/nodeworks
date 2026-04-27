package damien.nodeworks.script.diagnostics

import damien.nodeworks.script.LuaTokenizer
import damien.nodeworks.script.api.LuaApiRegistry

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
 *   * `unknown-identifier` — bare identifier references that don't resolve to a
 *     keyword, registered global, Lua stdlib member, or a name declared earlier
 *     in the script.
 *   * `unknown-method` — `:method` after a typed receiver where the type
 *     doesn't declare that method.
 *   * `unknown-property` — `.field` after a typed receiver where the type
 *     doesn't declare that field.
 *
 * Future:
 *   * `nullable-misuse` — using a `T?` value through `:method` / `.field`
 *     without first narrowing it via `if x` / `if x ~= nil` / `assert(x)`.
 */
object LuaDiagnostics {

    /** Per-rule severity. Adjust here to flip a rule's underline colour and
     *  tone of voice; rules read this table at emit time. */
    val severityForRule: Map<String, Severity> = mapOf(
        "unknown-identifier" to Severity.ERROR,
        "unknown-method" to Severity.ERROR,
        "unknown-property" to Severity.ERROR,
    )

    /**
     * Run every rule against [text] and return the union of diagnostics they
     * produce. [symbols] maps a local variable name to the type the autocomplete
     * inferred for it (e.g. `"card" to "CardHandle"`); used by the typed-receiver
     * rules to look up methods/properties. Pass an empty map to skip those.
     */
    fun analyze(
        text: String,
        symbols: Map<String, String> = emptyMap(),
    ): List<Diagnostic> {
        if (text.isBlank()) return emptyList()

        // Pre-pass: collect everything that COUNTS as declared. The unknown-id
        // rule needs this set to decide whether a bare reference is a typo or
        // just a name the user introduced earlier in the script.
        val declared = collectDeclaredNames(text)
        val typeAnnotationRanges = collectTypeAnnotationRanges(text)

        val out = mutableListOf<Diagnostic>()
        out += checkUnknownIdentifiers(text, declared, typeAnnotationRanges, symbols)
        return out
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

        // Walk the token stream with running global offsets.
        val lines = text.split('\n')
        val tokenLines = LuaTokenizer.tokenizeLines(text)
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
                // can't skip FUNCTION-coloured tokens — we still need to check whether
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
     *  runtime would fail those at execution time anyway. */
    private fun collectDeclaredNames(text: String): Set<String> {
        val names = mutableSetOf<String>()

        // local <name> [, <name>, ...] = ...
        // local <name>: <Type> = ...
        Regex("""\blocal\s+([\w_]+(?:\s*[,:]\s*(?:[\w_]+|\{[^}]*\}))*)""")
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
        Regex("""\b(?:local\s+)?function\s+([\w_]+)""")
            .findAll(text)
            .forEach { names.add(it.groupValues[1]) }

        // function <obj>.<name>(...) — the .name half is the declared method
        // name, but we want the obj name in declared too so a downstream
        // reference to <obj> doesn't squiggle.
        Regex("""\bfunction\s+([\w_]+)\s*\.""")
            .findAll(text)
            .forEach { names.add(it.groupValues[1]) }

        // Function parameters: `function name(a, b: T, c)` — extract the name list.
        // Also covers anonymous `function(a, b)` lambdas.
        Regex("""\bfunction\b\s*[\w_]*\s*\(([^)]*)\)""")
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
        Regex("""\bfor\s+([\w_]+(?:\s*,\s*[\w_]+)*)\s*=""")
            .findAll(text)
            .forEach { match ->
                for (chunk in match.groupValues[1].split(',')) {
                    val name = chunk.trim()
                    if (isIdentifierLike(name)) names.add(name)
                }
            }
        Regex("""\bfor\s+([\w_]+(?:\s*,\s*[\w_]+)*)\s+in\b""")
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
    private fun collectTypeAnnotationRanges(text: String): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        // Each pattern captures the type token in group 1. Type tokens can be
        // a bare name (`Type`), a nullable (`Type?`), or a brace-form container
        // (`{ T }`, `{ [K]: V }`).
        val patterns = listOf(
            // local <name>: Type
            Regex("""\blocal\s+\w+\s*:\s*(\w[\w_]*\??|\{[^}]*})"""),
            // function param: `(name: Type` or `, name: Type`
            Regex("""[(,]\s*\w+\s*:\s*(\w[\w_]*\??|\{[^}]*})"""),
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
}
