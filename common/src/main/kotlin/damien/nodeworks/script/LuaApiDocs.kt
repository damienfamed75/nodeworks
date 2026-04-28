package damien.nodeworks.script

import damien.nodeworks.script.api.ApiCategory
import damien.nodeworks.script.api.ApiDoc
import damien.nodeworks.script.api.LuaApiBootstrap
import damien.nodeworks.script.api.LuaApiRegistry
import damien.nodeworks.script.api.LuaType

/**
 * Single source of truth for Lua symbol documentation, consumed by both the in-game
 * [damien.nodeworks.screen.widget.ScriptEditor] hover tooltips and the guidebook's
 * `<LuaCode>` tag. Add a new symbol here and it lights up in every surface immediately.
 *
 * ## Keys
 *
 * Entries are keyed either by plain identifier (`"network"`, `"print"`) or by a
 * type-qualified form (`"Network:find"`, `"CardHandle:insert"`). The qualified form is
 * what [resolveAt] looks up when the token under inspection is preceded by `ident` +
 * `:`, either because the ident is a known global (see [moduleTypes] for the module →
 * type hop) or because the autocomplete symbol table has inferred the variable's type.
 *
 * ## Ground truth
 *
 * Every entry here was read off the actual Lua bindings in:
 *   * [damien.nodeworks.script.ScriptEngine.injectApi], `network`, `scheduler`,
 *     `clock`, `print` registrations.
 *   * [damien.nodeworks.script.CardHandle.create], card methods.
 *   * [damien.nodeworks.script.ItemsHandle.toLuaTable], items-handle fields + methods.
 *   * [damien.nodeworks.script.SchedulerImpl.createLuaTable], scheduler methods.
 *   * [damien.nodeworks.script.VariableHandle.create], variable methods.
 *
 * If any of those files change shape, update the matching entry here so both the
 * editor tooltips and the guidebook stay accurate.
 *
 * ## Description style
 *
 * Descriptions are surfaced in two narrow-width tooltips (editor + guidebook), so keep
 * them short. One sentence per entry where possible, two at the absolute max. Avoid em
 * dashes and dashes as sentence separators, they read as clutter in small tooltips.
 * Favour periods. Avoid parenthetical asides, if a nuance needs explaining, it belongs
 * on the guidebook page, not in the tooltip.
 */
object LuaApiDocs {

    enum class Category { KEYWORD, MODULE, TYPE, FUNCTION, METHOD, PROPERTY }

    data class Doc(
        val signature: String? = null,
        val description: String,
        val category: Category = Category.MODULE,
        /** `namespace:path#anchor` or `namespace:path`. Optional. */
        val guidebookRef: String? = null,
    )

    /** Maps a top-level module name to its Lua type so method keys stored under the
     *  type resolve when the user writes the module literal. `card` and `clock` are NOT
     *  in here, `card` isn't a global at all, and `clock` is a bare function.
     *
     *  Modules registered via the new DSL ([damien.nodeworks.script.api.LuaApiRegistry])
     *  are merged in at first access, so adding a `LuaTypes.module(...)` declaration
     *  in a spec file lights up its entry here automatically. */
    private val moduleTypes: Map<String, String> by lazy {
        LuaApiBootstrap.ensureInitialized()
        buildMap {
            put("network", "Network")
            put("importer", "Importer")
            put("stocker", "Stocker")
            for (mod in LuaApiRegistry.knownModules()) {
                mod.moduleGlobal?.let { put(it, mod.name) }
            }
        }
    }

    private val entries: Map<String, Doc> by lazy {
        LuaApiBootstrap.ensureInitialized()
        val legacy = legacyEntries()
        val registry = LuaApiRegistry.allDocs().mapValues { it.value.toLegacyDoc() }
        legacy + registry
    }

    /** Convert a DSL-built [ApiDoc] into the legacy [Doc] shape expected by the rest
     *  of [LuaApiDocs]. Lossy by design, the new model carries richer fields
     *  ([ApiDoc.params], [ApiDoc.example], deprecation, etc.) that the legacy hover
     *  format doesn't surface yet, future work will route those through new
     *  consumer-side rendering. */
    private fun ApiDoc.toLegacyDoc(): Doc = Doc(
        signature = signature,
        description = description,
        category = when (category) {
            ApiCategory.KEYWORD -> Category.KEYWORD
            ApiCategory.MODULE -> Category.MODULE
            ApiCategory.TYPE -> Category.TYPE
            ApiCategory.FUNCTION -> Category.FUNCTION
            ApiCategory.METHOD -> Category.METHOD
            ApiCategory.PROPERTY -> Category.PROPERTY
        },
        guidebookRef = guidebookRef,
    )

    /** Hand-rolled legacy entries that haven't migrated to the DSL yet. Every group
     *  in here is a candidate for [damien.nodeworks.script.api] migration, when an
     *  entry moves over, delete it from this builder. */
    private fun legacyEntries(): Map<String, Doc> = buildMap {
        // Keywords + globals migrated to damien.nodeworks.script.api.LuaGlobalsApi.

        // ===== network / Network / Channel / HandleList =====
        // Migrated to damien.nodeworks.script.api.NetworkApi (covers Network, Channel,
        // HandleList, and CraftBuilder).

        // ===== scheduler / Scheduler =====
        // Migrated to damien.nodeworks.script.api.SchedulerApi, served by LuaApiRegistry.

        // ===== Job / InputItems / CraftBuilder =====
        // These types don't come from a global, they arrive as arguments to user
        // callbacks (`network:handle`, `:connect`) or as a return value from a method.
        // Documenting them here lets hover tooltips resolve the bare type names and
        // the short method list under each of them.
        // Job + InputItems + CraftBuilder migrated to
        // damien.nodeworks.script.api.{JobApi, NetworkApi}.

        // Importer / ImporterBuilder migrated to damien.nodeworks.script.api.ImporterApi.

        // Stocker / StockerBuilder migrated to damien.nodeworks.script.api.StockerApi.

        // ===== CardHandle =====
        // Migrated to damien.nodeworks.script.api.CardHandleApi.

        // RedstoneCard, ObserverCard, BreakerHandle, BreakBuilder, PlacerHandle
        // migrated to damien.nodeworks.script.api.{RedstoneCardApi, ObserverCardApi,
        // BreakerApi, PlacerApi}.

        // ===== ItemsHandle =====
        // Migrated to damien.nodeworks.script.api.ItemsHandleApi.

        // VariableHandle and its Number/String/Bool variants migrated to
        // damien.nodeworks.script.api.VariableApi.
    }

    /** Direct key lookup. */
    fun get(symbol: String): Doc? = entries[symbol]

    /**
     * Context-aware lookup with optional variable-type inference.
     *
     * Resolution order:
     *   1. Literal `owner:member` (e.g. user registered a custom qualified key).
     *   2. Module → Type hop via [moduleTypes], `network:find` → `Network:find`.
     *   3. Typed-local hop via [variableTypes], `cards:setPowered` → `RedstoneCard:setPowered`
     *      when `cards` was declared as a local whose type the autocomplete inferred.
     *   4. Bare `member`.
     *   5. Variable-self, hovering the bare `cards` identifier returns the `RedstoneCard`
     *      type doc if `cards` is a known local of that type.
     */
    /**
     * What kind of container a signature's return type denotes. Drives both for-loop
     * element-type inference and the auto-pairs-vs-ipairs rewrite: `ARRAY` values iterate
     * with `ipairs` (integer keys), `MAP` values iterate with `pairs` (non-integer keys),
     * and `NONE` values aren't iterable.
     */
    enum class Container { NONE, ARRAY, MAP }

    /**
     * Extracted return type of an API signature. [type] is the element type for
     * containers (so `for _, v in fn() do v.<tab>` completes off [type]), or the scalar
     * return type when [container] is [Container.NONE].
     */
    data class ReturnType(val type: String, val container: Container)

    /**
     * Parse the return type from a signature string formatted like `"Foo:bar(args) → Type"`,
     * `"Foo:bar(args) → { Element… }"` / `"Foo:bar(args) → { Element }"` for arrays, or
     * `"Foo:bar(args) → { [KeyType]: ValueType }"` / `"Foo:bar(args) → { [KeyType] = ValueType }"`
     * for maps. Arrow can be `→` or `->`. Returns null if no return type is present
     * (e.g. void methods).
     */
    fun parseReturnType(signature: String?): ReturnType? {
        if (signature == null) return null
        val arrow = signature.lastIndexOf('→').takeIf { it >= 0 }
            ?: signature.lastIndexOf("->").takeIf { it >= 0 }
            ?: return null
        val rhs = signature.substring(arrow + 1).trim().trimStart('>').trim()

        // Map form: `{ [K]: V }` or `{ [K] = V }`. Element type is V.
        val mapMatch = Regex("""^\{\s*\[[\w?|\s]+?]\s*[:=]\s*([\w?|\s]+?)\s*}""").find(rhs)
        if (mapMatch != null) {
            val element = mapMatch.groupValues[1].trim().substringBefore('|').trim().trimEnd('?')
            return ReturnType(element, Container.MAP)
        }

        // Array form: `{ Type… }` or `{ Type }`. Element may carry `?` for nullable,
        // strip it since the for-loop element is always non-null per iteration.
        val arrayMatch = Regex("""^\{\s*([\w?|\s]+?)\s*(?:…|\.\.\.)?\s*}""").find(rhs)
        if (arrayMatch != null) {
            val element = arrayMatch.groupValues[1].trim().substringBefore('|').trim().trimEnd('?')
            return ReturnType(element, Container.ARRAY)
        }

        // Scalar form: first identifier after the arrow, strip any `|` union or `?` suffix.
        val scalarMatch = Regex("""^([\w?|\s]+?)(?:\s|$)""").find(rhs) ?: return null
        val scalar = scalarMatch.groupValues[1].trim().substringBefore('|').trim().trimEnd('?')
        if (scalar.isEmpty()) return null
        return ReturnType(scalar, Container.NONE)
    }

    /**
     * Return type for the given method name, searching every documented entry whose key
     * ends with `:$methodName` or equals `$methodName`. Matches the same
     * "look up by short method name" shape autocomplete uses for chain resolution, so a
     * single definition in [entries] drives both hover docs, chain inference, and for-loop
     * element inference, no parallel hardcoded maps.
     *
     * When multiple entries share a method name, the first match wins. For receiver-aware
     * lookup (e.g. distinguishing `Importer:from` from `Stocker:from` when both return
     * different builder types), use the overload that takes a [receiverType] argument.
     */
    fun methodReturnType(methodName: String): ReturnType? = methodReturnType(methodName, receiverType = null)

    /**
     * Receiver-aware return-type lookup. When [receiverType] is non-null, the
     * `"$receiverType:$methodName"` key is checked first so sibling types that share
     * a short method name (Importer / Stocker both have `:from`, each returns its own
     * builder) resolve correctly. Falls through to the unqualified bare-name search if
     * the qualified key isn't found.
     */
    fun methodReturnType(methodName: String, receiverType: String?): ReturnType? {
        if (receiverType != null) {
            val qualified = "$receiverType:$methodName"
            entries[qualified]?.signature?.let { sig ->
                parseReturnType(sig)?.let { return it }
            }
        }
        val suffix = ":$methodName"
        for ((key, doc) in entries) {
            if (key == methodName || key.endsWith(suffix)) {
                parseReturnType(doc.signature)?.let { return it }
            }
        }
        return null
    }

    /** Map a bare module name (e.g. `"importer"`) to its canonical type name
     *  (e.g. `"Importer"`). Returns null for non-module identifiers. Callers use this
     *  to qualify [methodReturnType] lookups when the receiver is a bare module. */
    fun moduleTypeFor(moduleName: String): String? = moduleTypes[moduleName]

    /** Resolve the type of the chain ending at [closeParenIdx] (which must point to a
     *  `)` token). Walks back through the matching `(...)`, finds the method name,
     *  recurses on the method's own receiver chain, and returns the method's return
     *  type qualified by that receiver. Returns null when the chain isn't resolvable
     *  (e.g. non-call syntax, unknown method, balanced-paren scan failure).
     *
     *  Used by [resolveAt] to power hover tooltips on chained method calls like
     *  `importer:from(...):to` where the immediate owner of `to` is `)` and the
     *  receiver type is whatever `from` returned. */
    private fun resolveChainEndType(
        tokens: List<LuaTokenizer.Token>,
        closeParenIdx: Int,
        variableTypes: Map<String, String>,
    ): String? {
        if (closeParenIdx < 0 || tokens.getOrNull(closeParenIdx)?.text != ")") return null
        // Walk back to the matching `(`.
        var depth = 1
        var m = closeParenIdx - 1
        while (m >= 0) {
            when (tokens[m].text) {
                ")" -> depth++
                "(" -> {
                    depth--
                    if (depth == 0) break
                }
            }
            m--
        }
        if (m < 0) return null
        // Token before `(` is the method name.
        var n = m - 1
        while (n >= 0 && tokens[n].text.isBlank()) n--
        if (n < 0) return null
        val methodName = tokens[n].text

        // Walk back further to find the receiver of this method call.
        var p = n - 1
        while (p >= 0 && tokens[p].text.isBlank()) p--
        var receiverType: String? = null
        if (p >= 0 && (tokens[p].text == ":" || tokens[p].text == ".")) {
            var q = p - 1
            while (q >= 0 && tokens[q].text.isBlank()) q--
            if (q >= 0) {
                val recvTok = tokens[q]
                receiverType = when {
                    recvTok.text == ")" -> resolveChainEndType(tokens, q, variableTypes)
                    else -> moduleTypes[recvTok.text] ?: variableTypes[recvTok.text]
                }
            }
        }
        return methodReturnType(methodName, receiverType)?.type
    }

    /** Resolve the element type at the end of an indexed chain like
     *  `findEach(...)[0]` or `myList[i]`. Walks back from the closing `]` to the
     *  matching `[`, then looks at the token before `[`:
     *    * `)` → recurse via [resolveChainEndType] for the chain's own (already
     *      element-form) return type. The `[N]` is a no-op since
     *      [methodReturnType] already strips the container wrapper.
     *    * Identifier → look up its container annotation in [variableTypes] and
     *      strip the wrapper to get the element type.
     *
     *  Returns null when the bracket isn't balanced or the indexed value isn't a
     *  container, callers fall through to other resolution paths in that case. */
    private fun resolveIndexedEndType(
        tokens: List<LuaTokenizer.Token>,
        closeBracketIdx: Int,
        variableTypes: Map<String, String>,
    ): String? {
        if (closeBracketIdx < 0 || tokens.getOrNull(closeBracketIdx)?.text != "]") return null
        var depth = 1
        var m = closeBracketIdx - 1
        while (m >= 0) {
            when (tokens[m].text) {
                "]" -> depth++
                "[" -> {
                    depth--
                    if (depth == 0) break
                }
            }
            m--
        }
        if (m < 0) return null
        var n = m - 1
        while (n >= 0 && tokens[n].text.isBlank()) n--
        if (n < 0) return null
        val prev = tokens[n]
        return when (prev.text) {
            ")" -> resolveChainEndType(tokens, n, variableTypes)
            else -> {
                val varType = variableTypes[prev.text] ?: return null
                val rt = parseReturnType("() → $varType") ?: return null
                if (rt.container != Container.NONE) rt.type else null
            }
        }
    }

    /** Look up the doc for `ownerType:method` (or `.field`), with HandleList<T>
     *  awareness: a hover on a broadcast method falls back to the underlying T's
     *  doc so users see the real signature + description + guidebook link instead
     *  of a synthetic broadcast string. Universal `:list()` / `:count()` resolve
     *  through the bare `HandleList` type entry. */
    private fun lookupTypedMethod(ownerType: String, separator: String, methodName: String): Doc? {
        // Direct hit (e.g. `RedstoneCard:set` for an unwrapped type).
        entries["$ownerType$separator$methodName"]?.let { return it }
        // Parameterised HandleList<T>: try the universal HandleList entry first,
        // then fall back to T's per-method doc so broadcast methods like
        // `pistons:set` borrow `RedstoneCard:set`'s tooltip.
        val handleListMatch = Regex("""^HandleList<(\w+)>$""").matchEntire(ownerType)
        if (handleListMatch != null) {
            val element = handleListMatch.groupValues[1]
            entries["HandleList$separator$methodName"]?.let { return it }
            entries["$element$separator$methodName"]?.let { return it }
        }
        return null
    }

    fun resolveAt(
        tokens: List<LuaTokenizer.Token>,
        index: Int,
        variableTypes: Map<String, String> = emptyMap(),
        inputItemsFields: List<String>? = null,
    ): Doc? {
        val tok = tokens.getOrNull(index) ?: return null
        val eligible = tok.type == LuaTokenizer.TokenType.DEFAULT ||
                tok.type == LuaTokenizer.TokenType.FUNCTION ||
                tok.type == LuaTokenizer.TokenType.KEYWORD
        if (!eligible) return null

        var j = index - 1
        while (j >= 0 && tokens[j].text.isBlank()) j--
        if (j >= 0) {
            val sep = tokens[j]
            if (sep.text == ":" || sep.text == ".") {
                var k = j - 1
                while (k >= 0 && tokens[k].text.isBlank()) k--
                if (k >= 0) {
                    val owner = tokens[k]
                    // Punctuation tokens like `)` carry [TokenType.DEFAULT] from the
                    // tokenizer (it's the catch-all class), so we can't disambiguate
                    // them from identifiers via [TokenType] alone. Check the closing
                    // paren by literal text BEFORE the ownerEligible identifier path
                    // so chained calls like `importer:from(...):to` route through the
                    // chain walker instead of accidentally hitting the bare-owner
                    // lookup that would search for `entries["):to"]`.
                    if (owner.text == ")") {
                        val chainType = resolveChainEndType(tokens, k, variableTypes)
                        if (chainType != null) {
                            lookupTypedMethod(chainType, sep.text, tok.text)?.let { return it }
                        }
                    } else if (owner.text == "]") {
                        val elementType = resolveIndexedEndType(tokens, k, variableTypes)
                        if (elementType != null) {
                            lookupTypedMethod(elementType, sep.text, tok.text)?.let { return it }
                        }
                    } else {
                        val ownerEligible = owner.type == LuaTokenizer.TokenType.DEFAULT ||
                                owner.type == LuaTokenizer.TokenType.FUNCTION
                        if (ownerEligible) {
                            entries["${owner.text}${sep.text}${tok.text}"]?.let { return it }
                            moduleTypes[owner.text]?.let { ownerType ->
                                lookupTypedMethod(ownerType, sep.text, tok.text)?.let { return it }
                            }
                            variableTypes[owner.text]?.let { ownerType ->
                                lookupTypedMethod(ownerType, sep.text, tok.text)?.let { return it }
                                // Dynamic InputItems fields, the per-recipe slot names
                                // can't be statically registered so they fall through
                                // [lookupTypedMethod]. Synthesise an ItemsHandle Doc
                                // when the caller supplied the live field set.
                                if (ownerType.trimEnd('?') == "InputItems" &&
                                    sep.text == "." &&
                                    inputItemsFields != null &&
                                    tok.text in inputItemsFields
                                ) {
                                    return synthesizeInputItemsFieldDoc(tok.text)
                                }
                            }
                        }
                    }
                }
            }
        }
        entries[tok.text]?.let { return it }
        // Type-name fall-through: when the bare token IS a registered type
        // (`Job`, `InputItems`, …), surface its registered Doc directly. The
        // [entries] lookup above SHOULD already cover this, but
        // [LuaApiRegistry] is the source of truth and we'd rather hit it
        // here than fall through to a null-return that masks the type's
        // description and guidebookRef.
        registryTypeDoc(tok.text)?.let { return it }
        // Variable hover: synthesize a `name: Type` signature and pull the type's
        // description from its own entry when one exists. This beats returning the raw
        // type doc (which would hide that the token is a *variable*) while still
        // surfacing the type's explanation so the user understands what operations apply.
        variableTypes[tok.text]?.let { selfType ->
            val unwrapped = selfType.trimEnd('?')
            val typeDoc = entries[unwrapped] ?: registryTypeDoc(unwrapped)
            return Doc(
                signature = "${tok.text}: $selfType",
                description = typeDoc?.description ?: "",
                category = typeDoc?.category ?: Category.TYPE,
                guidebookRef = typeDoc?.guidebookRef,
            )
        }
        return null
    }

    /** Fetch a registered type's Doc straight from [LuaApiRegistry]. Used by
     *  the hover fall-through paths so a registry hit on `Job` / `InputItems`
     *  surfaces description + guidebookRef even if the [entries] lazy somehow
     *  misses (stale class-load, partial bootstrap, etc.). Returns null when
     *  the name isn't a registered type. */
    private fun registryTypeDoc(name: String): Doc? {
        val apiDoc = damien.nodeworks.script.api.LuaApiRegistry.allDocs()[name] ?: return null
        if (apiDoc.category != damien.nodeworks.script.api.ApiCategory.TYPE &&
            apiDoc.category != damien.nodeworks.script.api.ApiCategory.MODULE
        ) return null
        return Doc(
            signature = apiDoc.signature,
            description = apiDoc.description,
            category = when (apiDoc.category) {
                damien.nodeworks.script.api.ApiCategory.TYPE -> Category.TYPE
                damien.nodeworks.script.api.ApiCategory.MODULE -> Category.MODULE
                else -> Category.TYPE
            },
            guidebookRef = apiDoc.guidebookRef,
        )
    }

    /** Build a hover Doc for a per-slot `items.<field>` access inside a
     *  handler. Each slot is statically typed `ItemsHandle`, so the doc just
     *  carries that signature plus the ItemsHandle entry's description /
     *  guidebookRef so hovering teaches the player what they can call on it. */
    private fun synthesizeInputItemsFieldDoc(fieldName: String): Doc {
        val itemsHandleDoc = entries["ItemsHandle"]
        return Doc(
            signature = "$fieldName: ItemsHandle",
            description = itemsHandleDoc?.description ?: "",
            category = Category.PROPERTY,
            guidebookRef = itemsHandleDoc?.guidebookRef,
        )
    }
}
