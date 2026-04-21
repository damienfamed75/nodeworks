package damien.nodeworks.script

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
 * `:` — either because the ident is a known global (see [moduleTypes] for the module →
 * type hop) or because the autocomplete symbol table has inferred the variable's type.
 *
 * ## Ground truth
 *
 * Every entry here was read off the actual Lua bindings in:
 *   * [damien.nodeworks.script.ScriptEngine.injectApi] — `network`, `scheduler`,
 *     `clock`, `print` registrations.
 *   * [damien.nodeworks.script.CardHandle.create] — card methods.
 *   * [damien.nodeworks.script.ItemsHandle.toLuaTable] — items-handle fields + methods.
 *   * [damien.nodeworks.script.SchedulerImpl.createLuaTable] — scheduler methods.
 *   * [damien.nodeworks.script.VariableHandle.create] — variable methods.
 *
 * If any of those files change shape, update the matching entry here so both the
 * editor tooltips and the guidebook stay accurate.
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
     *  in here — `card` isn't a global at all, and `clock` is a bare function. */
    private val moduleTypes: Map<String, String> = mapOf(
        "network" to "Network",
        "scheduler" to "Scheduler",
    )

    private val entries: Map<String, Doc> = buildMap {
        // ===== Lua keywords =====
        put("local", Doc(
            signature = "local <name> = <value>",
            description = "Declares a variable scoped to the enclosing block.",
            category = Category.KEYWORD,
        ))
        put("function", Doc(
            signature = "function <name>(<params>) … end",
            description = "Declares a function. Use `local function` to keep it block-scoped.",
            category = Category.KEYWORD,
        ))
        put("if", Doc(
            signature = "if <cond> then … [elseif …] [else …] end",
            description = "Conditional branch.",
            category = Category.KEYWORD,
        ))
        put("for", Doc(
            signature = "for i = start, stop[, step] do … end  |  for k, v in pairs(t) do … end",
            description = "Numeric or generic loop. `pairs` iterates tables; `ipairs` iterates integer arrays in order.",
            category = Category.KEYWORD,
        ))
        put("while", Doc(
            signature = "while <cond> do … end",
            description = "Loops while the condition is truthy.",
            category = Category.KEYWORD,
        ))
        put("return", Doc(
            signature = "return [values…]",
            description = "Returns from the current function, optionally yielding values.",
            category = Category.KEYWORD,
        ))
        put("break", Doc(
            description = "Exits the innermost enclosing loop.",
            category = Category.KEYWORD,
        ))

        // ===== Globals =====
        put("print", Doc(
            signature = "print(…)",
            description = "Prints its arguments to the terminal's log panel, space-separated.",
            category = Category.FUNCTION,
        ))
        put("require", Doc(
            signature = "require(modName) → module",
            description = "Loads and returns a Lua module stored on the network's instruction storage.",
            category = Category.FUNCTION,
        ))
        put("clock", Doc(
            signature = "clock() → number",
            description = "Seconds elapsed since this script started running, as a decimal. Not a module — `clock` is a plain function.",
            category = Category.FUNCTION,
        ))

        // ===== network / Network =====
        put("network", Doc(
            signature = "network: Network",
            description = "The network this script is running on. Look up cards by alias, query storage, register routes, craft.",
            category = Category.MODULE,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network", Doc(
            signature = "type Network",
            description = "The active network. Entry point for card lookup (`:get`), storage queries (`:find`), routing, and crafting.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:get", Doc(
            signature = "Network:get(alias: string) → CardHandle",
            description = "Returns the card with this alias. Errors if no card matches — use this for required hardware lookups. To query items, use `:find` instead.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#get",
        ))
        put("Network:getAll", Doc(
            signature = "Network:getAll(type: string) → { CardHandle… }",
            description = "Returns a table of every card on the network whose capability type matches (e.g. `\"redstone\"`, `\"storage\"`).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:find", Doc(
            signature = "Network:find(filter: string) → ItemsHandle | nil",
            description = "Scans all storage on the network for items/fluids matching the filter. Returns an aggregated handle (count summed across storage), or nil if nothing matches. Filters accept item ids, wildcards, `\$item:`/`\$fluid:` sigils.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#find",
        ))
        put("Network:findEach", Doc(
            signature = "Network:findEach(filter: string) → { ItemsHandle… }",
            description = "Like `:find`, but returns a separate handle for every distinct resource matching the filter (items then fluids unless kind-prefixed).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:insert", Doc(
            signature = "Network:insert(items: ItemsHandle[, count: number]) → boolean",
            description = "Atomic move of exactly `count` (or `items.count`) from the handle's source into network storage, honouring storage-card priority and any active routes. Returns true on success, false if the full amount wouldn't fit (nothing moves). Use `:tryInsert` for partial-move semantics.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#insert",
        ))
        put("Network:tryInsert", Doc(
            signature = "Network:tryInsert(items: ItemsHandle[, count: number]) → number",
            description = "Best-effort counterpart to `:insert`. Moves as much as fits into network storage and returns the count actually moved (0..count). Items left over stay in the source.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#insert",
        ))
        put("Network:craft", Doc(
            signature = "Network:craft(itemId: string[, count: number]) → CraftBuilder | nil",
            description = "Queues a craft for the given item. Returns a builder with `:connect(fn)` (callback on completion, receives the ItemsHandle) and `:store()` (send result to storage). Returns nil if the craft can't be planned.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:route", Doc(
            signature = "Network:route(alias: string, predicate: (ItemsHandle) → boolean)",
            description = "Declarative routing rule: any item matching `predicate` is directed to the card with this alias. Applied during `network:insert` / `network:tryInsert`.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#route",
        ))
        put("Network:shapeless", Doc(
            signature = "Network:shapeless(itemId: string, count: number, …) → ItemsHandle | nil",
            description = "Crafts via vanilla shapeless recipes using the given item/count pairs pulled from storage. Returns the result handle, or nil if no matching recipe or missing ingredients.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:handle", Doc(
            signature = "Network:handle(cardName: string, handler: function)",
            description = "Registers a processing handler for a named Processing Set. The handler is invoked with inputs and must return the output ItemsHandle.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:var", Doc(
            signature = "Network:var(name: string) → VariableHandle",
            description = "Returns a handle to the network variable with this name. Errors if the variable doesn't exist.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network:debug", Doc(
            signature = "Network:debug()",
            description = "Prints a summary of the network (controller, nodes, cards, variables) to the terminal log.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))

        // ===== scheduler / Scheduler =====
        put("scheduler", Doc(
            signature = "scheduler: Scheduler",
            description = "Registers periodic and delayed callbacks. All scheduled functions run on the server tick inside this script's engine.",
            category = Category.MODULE,
        ))
        put("Scheduler", Doc(
            signature = "type Scheduler",
            description = "Task scheduler. Tick / second / delay registrations return an id that can be passed to `:cancel`.",
            category = Category.TYPE,
        ))
        put("Scheduler:tick", Doc(
            signature = "Scheduler:tick(fn: function) → number",
            description = "Runs `fn` every server tick (20 tps). Returns a task id for later cancellation.",
            category = Category.METHOD,
        ))
        put("Scheduler:second", Doc(
            signature = "Scheduler:second(fn: function) → number",
            description = "Runs `fn` every 20 ticks (1 second). Returns a task id.",
            category = Category.METHOD,
        ))
        put("Scheduler:delay", Doc(
            signature = "Scheduler:delay(ticks: number, fn: function) → number",
            description = "Runs `fn` once, after the given number of ticks. Returns a task id.",
            category = Category.METHOD,
        ))
        put("Scheduler:cancel", Doc(
            signature = "Scheduler:cancel(id: number)",
            description = "Cancels a task previously returned by `:tick`, `:second`, or `:delay`.",
            category = Category.METHOD,
        ))

        // ===== CardHandle =====
        put("CardHandle", Doc(
            signature = "type CardHandle",
            description = "A card on the network accessed by alias. Exposes slot-level inventory operations (`:find`, `:insert`, `:count`) plus face-/slot-filtered variants.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:face", Doc(
            signature = "CardHandle:face(name: string) → CardHandle",
            description = "Returns a new handle with access pinned to a specific face of the adjacent block (`\"north\"`, `\"up\"`, etc.). Useful for machines with distinct input/output faces.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:slots", Doc(
            signature = "CardHandle:slots(…indices) → CardHandle",
            description = "Returns a new handle filtered to specific slot indices (1-based in Lua).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle.name", Doc(
            signature = "CardHandle.name: string",
            description = "The card's alias — same label shown in the terminal sidebar and Card Programmer. Falls back to the auto-assigned alias for un-renamed cards.",
            category = Category.PROPERTY,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:find", Doc(
            signature = "CardHandle:find(filter: string) → ItemsHandle | nil",
            description = "Like `Network:find`, but scoped to this card's inventory. Counts aggregate across this card's slots/tanks.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:findEach", Doc(
            signature = "CardHandle:findEach(filter: string) → { ItemsHandle… }",
            description = "Returns one handle per distinct resource in this card matching the filter.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:insert", Doc(
            signature = "CardHandle:insert(items: ItemsHandle[, count: number]) → boolean",
            description = "Atomic move of exactly `count` (or `items.count`) from the handle's source into this card. Returns true on success, false if the full amount wouldn't fit (nothing moves). Use `:tryInsert` for partial-move semantics.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:tryInsert", Doc(
            signature = "CardHandle:tryInsert(items: ItemsHandle[, count: number]) → number",
            description = "Best-effort move. Returns the actual count moved (may be less than requested).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("CardHandle:count", Doc(
            signature = "CardHandle:count(filter: string) → number",
            description = "Total quantity on this card matching the filter (items + fluids unless kind-prefixed).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))

        // ===== RedstoneCard =====
        put("RedstoneCard", Doc(
            signature = "type RedstoneCard",
            description = "A card attached to a Redstone Card capability. The usual inventory methods (`:find`, `:insert`, `:count`) are unavailable — use the redstone-specific methods below.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("RedstoneCard:powered", Doc(
            signature = "RedstoneCard:powered() → boolean",
            description = "True if the redstone signal reaching this card's face is > 0.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("RedstoneCard:strength", Doc(
            signature = "RedstoneCard:strength() → number",
            description = "Current redstone signal strength at this card's face (0–15).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("RedstoneCard:set", Doc(
            signature = "RedstoneCard:set(value: boolean | number)",
            description = "Emits a redstone signal from this card's face. Boolean maps to 15/0; number is clamped to 0–15.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("RedstoneCard:onChange", Doc(
            signature = "RedstoneCard:onChange(fn: (strength: number) → nil)",
            description = "Registers a callback fired whenever the incoming signal strength changes. Replaces any prior callback for this card.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))

        // ===== ItemsHandle =====
        put("ItemsHandle", Doc(
            signature = "type ItemsHandle",
            description = "A snapshot of items or fluids matching some filter. Carries fields (`.id`, `.name`, `.count`, `.kind`, …) and two helpers (`:hasTag`, `:matches`). Pass handles to `Network:insert` / `CardHandle:insert` to move the underlying resources.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/items-handle.md",
        ))
        put("ItemsHandle:hasTag", Doc(
            signature = "ItemsHandle:hasTag(tag: string) → boolean",
            description = "True if this resource is in the given tag (e.g. `\"#minecraft:logs\"` or `\"minecraft:logs\"`). Works for both items and fluids.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/items-handle.md",
        ))
        put("ItemsHandle:matches", Doc(
            signature = "ItemsHandle:matches(filter: string) → boolean",
            description = "True if this resource matches the filter, using the same rules `Network:find` uses (item ids, wildcards, `\$item:`/`\$fluid:` sigils).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/items-handle.md",
        ))

        // ===== VariableHandle =====
        put("VariableHandle", Doc(
            signature = "type VariableHandle",
            description = "A handle to a network variable returned by `Network:var`. Subtypes (Number/String/Bool) add type-specific methods.",
            category = Category.TYPE,
        ))
        put("VariableHandle:get", Doc(
            signature = "VariableHandle:get() → any",
            description = "Returns the current value of the variable.",
            category = Category.METHOD,
        ))
        put("VariableHandle:set", Doc(
            signature = "VariableHandle:set(value)",
            description = "Sets the variable's value. Must match the variable's declared type.",
            category = Category.METHOD,
        ))
        put("VariableHandle:cas", Doc(
            signature = "VariableHandle:cas(expected, new) → boolean",
            description = "Atomic compare-and-swap. Sets the variable to `new` only if its current value equals `expected`. Returns true on success.",
            category = Category.METHOD,
        ))
        put("VariableHandle:type", Doc(
            signature = "VariableHandle:type() → string",
            description = "Returns the variable's declared type (`\"number\"`, `\"string\"`, `\"boolean\"`).",
            category = Category.METHOD,
        ))
        put("VariableHandle:name", Doc(
            signature = "VariableHandle:name() → string",
            description = "Returns the variable's name as declared on the network.",
            category = Category.METHOD,
        ))
        put("VariableHandle:tryLock", Doc(
            signature = "VariableHandle:tryLock() → boolean",
            description = "Attempts to take the variable's lock. Returns true if acquired, false if another script holds it.",
            category = Category.METHOD,
        ))
        put("VariableHandle:unlock", Doc(
            signature = "VariableHandle:unlock()",
            description = "Releases a lock previously acquired via `:tryLock`.",
            category = Category.METHOD,
        ))

        // Number-typed variables:
        put("NumberVariableHandle", Doc(
            signature = "type NumberVariableHandle",
            description = "A `VariableHandle` with numeric-specific helpers in addition to the base methods.",
            category = Category.TYPE,
        ))
        put("NumberVariableHandle:increment", Doc(
            signature = "NumberVariableHandle:increment([by: number = 1])",
            description = "Adds `by` (defaults to 1) to the variable, atomically.",
            category = Category.METHOD,
        ))
        put("NumberVariableHandle:decrement", Doc(
            signature = "NumberVariableHandle:decrement([by: number = 1])",
            description = "Subtracts `by` (defaults to 1) from the variable, atomically.",
            category = Category.METHOD,
        ))
        put("NumberVariableHandle:min", Doc(
            signature = "NumberVariableHandle:min(other: number)",
            description = "Sets the variable to `min(current, other)`, atomically.",
            category = Category.METHOD,
        ))
        put("NumberVariableHandle:max", Doc(
            signature = "NumberVariableHandle:max(other: number)",
            description = "Sets the variable to `max(current, other)`, atomically.",
            category = Category.METHOD,
        ))

        // String-typed variables:
        put("StringVariableHandle", Doc(
            signature = "type StringVariableHandle",
            description = "A `VariableHandle` with string-specific helpers.",
            category = Category.TYPE,
        ))
        put("StringVariableHandle:append", Doc(
            signature = "StringVariableHandle:append(str: string)",
            description = "Appends `str` to the variable's current value.",
            category = Category.METHOD,
        ))
        put("StringVariableHandle:length", Doc(
            signature = "StringVariableHandle:length() → number",
            description = "Length of the current string value.",
            category = Category.METHOD,
        ))
        put("StringVariableHandle:clear", Doc(
            signature = "StringVariableHandle:clear()",
            description = "Sets the variable to the empty string.",
            category = Category.METHOD,
        ))

        // Bool-typed variables:
        put("BoolVariableHandle", Doc(
            signature = "type BoolVariableHandle",
            description = "A `VariableHandle` with a boolean-specific helper.",
            category = Category.TYPE,
        ))
        put("BoolVariableHandle:toggle", Doc(
            signature = "BoolVariableHandle:toggle()",
            description = "Flips the boolean variable's value.",
            category = Category.METHOD,
        ))
    }

    /** Direct key lookup. */
    fun get(symbol: String): Doc? = entries[symbol]

    /**
     * Context-aware lookup with optional variable-type inference.
     *
     * Resolution order:
     *   1. Literal `owner:member` (e.g. user registered a custom qualified key).
     *   2. Module → Type hop via [moduleTypes] — `network:find` → `Network:find`.
     *   3. Typed-local hop via [variableTypes] — `cards:setPowered` → `RedstoneCard:setPowered`
     *      when `cards` was declared as a local whose type the autocomplete inferred.
     *   4. Bare `member`.
     *   5. Variable-self — hovering the bare `cards` identifier returns the `RedstoneCard`
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

        // Array form: `{ Type… }` or `{ Type }`. Element may carry `?` for nullable —
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
     * element inference — no parallel hardcoded maps.
     *
     * When multiple entries share a method name (e.g. both `Network:find` and
     * `CardHandle:find` define `find`) the first match wins; that's fine because they
     * agree on return type in every case we ship today.
     */
    fun methodReturnType(methodName: String): ReturnType? {
        val suffix = ":$methodName"
        for ((key, doc) in entries) {
            if (key == methodName || key.endsWith(suffix)) {
                parseReturnType(doc.signature)?.let { return it }
            }
        }
        return null
    }

    fun resolveAt(
        tokens: List<LuaTokenizer.Token>,
        index: Int,
        variableTypes: Map<String, String> = emptyMap(),
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
                    val ownerEligible = owner.type == LuaTokenizer.TokenType.DEFAULT ||
                        owner.type == LuaTokenizer.TokenType.FUNCTION
                    if (ownerEligible) {
                        entries["${owner.text}${sep.text}${tok.text}"]?.let { return it }
                        moduleTypes[owner.text]?.let { ownerType ->
                            entries["$ownerType${sep.text}${tok.text}"]?.let { return it }
                        }
                        variableTypes[owner.text]?.let { ownerType ->
                            entries["$ownerType${sep.text}${tok.text}"]?.let { return it }
                        }
                    }
                }
            }
        }
        entries[tok.text]?.let { return it }
        // Variable hover: synthesize a `name: Type` signature and pull the type's
        // description from its own entry when one exists. This beats returning the raw
        // type doc (which would hide that the token is a *variable*) while still
        // surfacing the type's explanation so the user understands what operations apply.
        variableTypes[tok.text]?.let { selfType ->
            val typeDoc = entries[selfType]
            return Doc(
                signature = "${tok.text}: $selfType",
                description = typeDoc?.description ?: "",
                category = typeDoc?.category ?: Category.TYPE,
                guidebookRef = typeDoc?.guidebookRef,
            )
        }
        return null
    }
}
