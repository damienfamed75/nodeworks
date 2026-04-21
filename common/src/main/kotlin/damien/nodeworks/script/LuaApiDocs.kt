package damien.nodeworks.script

/**
 * Single source of truth for Lua symbol documentation, consumed by both the in-game
 * [damien.nodeworks.screen.widget.ScriptEditor] hover tooltips and the guidebook's
 * `<LuaCode>` tag. Add a new symbol here and it lights up in every surface immediately.
 *
 * Entries are keyed either by plain identifier (`"network"`) or by a qualified form
 * (`"network:get"`, `"string.format"`). The qualified form is what [resolveAt] looks up
 * when the token under inspection is preceded by `ident` + `:` or `ident` + `.`.
 *
 * When adding docs, keep them short — tooltips are one-liners with maybe 2–3 wrapped
 * lines of description. Full prose belongs in a guidebook page.
 */
object LuaApiDocs {

    enum class Category { KEYWORD, MODULE, TYPE, FUNCTION, METHOD }

    data class Doc(
        /** Optional type/signature hint shown as the tooltip's first bolded line. */
        val signature: String? = null,
        /** One-or-two-line description. Keep it tight. */
        val description: String,
        val category: Category = Category.MODULE,
        /** Guidebook anchor to open on Hold-G. Format: `namespace:path#anchor` or
         *  just `namespace:path`. Optional — omit for symbols without a dedicated
         *  guidebook section. */
        val guidebookRef: String? = null,
    )

    // ---------- Entries ----------
    //
    // Ordering is purely for readability in this file — lookup is by key, so arrangement
    // doesn't affect runtime behaviour. Keep related symbols clustered.

    private val entries: Map<String, Doc> = buildMap {
        // ===== Lua keywords =====
        put("local", Doc(
            signature = "local <name> = <value>",
            description = "Declares a variable scoped to the enclosing block.",
            category = Category.KEYWORD,
        ))
        put("function", Doc(
            signature = "function <name>(<params>) … end",
            description = "Declares a function. Use `local function` to scope it.",
            category = Category.KEYWORD,
        ))
        put("if", Doc(
            signature = "if <cond> then … [elseif …] [else …] end",
            description = "Conditional branch. `elseif` / `else` are optional.",
            category = Category.KEYWORD,
        ))
        put("for", Doc(
            signature = "for i = start, stop[, step] do … end  |  for k, v in pairs(t) do … end",
            description = "Numeric or generic loop. `pairs` iterates tables; `ipairs` iterates integer-keyed arrays in order.",
            category = Category.KEYWORD,
        ))
        put("while", Doc(
            signature = "while <cond> do … end",
            description = "Loop that runs while the condition is truthy.",
            category = Category.KEYWORD,
        ))
        put("return", Doc(
            signature = "return [values…]",
            description = "Returns from the current function, optionally yielding one or more values.",
            category = Category.KEYWORD,
        ))
        put("break", Doc(
            description = "Exits the innermost enclosing loop immediately.",
            category = Category.KEYWORD,
        ))

        // ===== Global builtins =====
        put("print", Doc(
            signature = "print(…)",
            description = "Writes arguments to the terminal's output log, space-separated.",
            category = Category.FUNCTION,
        ))
        put("require", Doc(
            signature = "require(modName) → module",
            description = "Loads and returns a Lua module. Modules live on the network's instruction storage.",
            category = Category.FUNCTION,
        ))

        // ===== Top-level API modules =====
        //
        // Globals get BOTH a module-value entry (e.g. `"network"`) and a type-level entry
        // (e.g. `"Network"`) so:
        //   * Hovering the literal `network` identifier finds the module entry.
        //   * Hovering a user-declared `local x = network` (→ variableTypes["x"] = "Network")
        //     resolves via type fallback.
        // Method keys use the type name (`"Network:get"`) so typed locals
        // (`local items = card`; `items:frob()`) resolve against the same entries.

        put("network", Doc(
            signature = "network: Network",
            description = "The network this script is running on. Entry point for inventory queries, routing, and crafting.",
            category = Category.MODULE,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("Network", Doc(
            signature = "type Network",
            description = "The active network. Queries storage, routes items, registers callbacks.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/network.md",
        ))
        put("card", Doc(
            signature = "card: Card",
            description = "The card this script was loaded from. Holds per-card state and hardware references.",
            category = Category.MODULE,
            guidebookRef = "nodeworks:lua-api/card.md",
        ))
        put("Card", Doc(
            signature = "type Card",
            description = "A per-card handle: state, aliases, capability references.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/card.md",
        ))
        put("scheduler", Doc(
            signature = "scheduler: Scheduler",
            description = "Async primitive — sleep, wait on conditions, yield from coroutine handlers.",
            category = Category.MODULE,
            guidebookRef = "nodeworks:lua-api/scheduler.md",
        ))
        put("Scheduler", Doc(
            signature = "type Scheduler",
            description = "Async control: sleep / wait / yield inside a script coroutine.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/scheduler.md",
        ))
        put("clock", Doc(
            signature = "clock: Clock",
            description = "World-time queries. Useful for redstone-card triggers and daily schedules.",
            category = Category.MODULE,
            guidebookRef = "nodeworks:lua-api/clock.md",
        ))
        put("Clock", Doc(
            signature = "type Clock",
            description = "World-time queries (game tick, day number).",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/clock.md",
        ))

        // ===== Handle types (returned from various API calls) =====
        put("ItemsHandle", Doc(
            signature = "type ItemsHandle",
            description = "A stack of items matching some filter. Chain into `:insert`, `:route`, `:count`, etc.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/items-handle.md",
        ))
        put("CardHandle", Doc(
            signature = "type CardHandle",
            description = "A hardware card on the network, accessed by alias.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/card-handle.md",
        ))
        put("RedstoneCard", Doc(
            signature = "type RedstoneCard : CardHandle",
            description = "A Redstone Card — read and write redstone signals on the card's associated face.",
            category = Category.TYPE,
            guidebookRef = "nodeworks:lua-api/card-handle.md#redstone-card",
        ))

        // ===== Network methods (keyed against the TYPE name) =====
        put("Network:get", Doc(
            signature = "Network:get(filter: string) → ItemsHandle",
            description = "Returns a handle representing all matching items on the network. Filter uses the resource-filter grammar (`\$item:modid:item`, wildcards, etc.).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#get",
        ))
        put("Network:find", Doc(
            signature = "Network:find(filter: string) → ItemsHandle",
            description = "Alias of `get` with the same semantics. Use whichever reads more naturally at the call site.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#find",
        ))
        put("Network:insert", Doc(
            signature = "Network:insert(stack: ItemsHandle) → ItemsHandle",
            description = "Inserts a stack into the network's storage, spreading across storage cards by the storage-first rule. Returns the remainder that couldn't fit.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#insert",
        ))
        put("Network:route", Doc(
            signature = "Network:route(stack: ItemsHandle, target: string) → ItemsHandle",
            description = "Moves items from the network to a target (another filter-addressable location). Returns the portion that actually moved.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#route",
        ))
        put("Network:onInsert", Doc(
            signature = "Network:onInsert(fn: (stack) → nil)",
            description = "Registers a callback that fires when any item enters the network. Callback runs inside the script's coroutine, so blocking calls are safe.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/network.md#on-insert",
        ))

        // ===== ItemsHandle methods =====
        put("ItemsHandle:insert", Doc(
            signature = "ItemsHandle:insert(items: ItemsHandle) → ItemsHandle",
            description = "Inserts items into this handle's target (a card, storage, etc.). Returns the remainder that didn't fit.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/items-handle.md#insert",
        ))
        put("ItemsHandle:find", Doc(
            signature = "ItemsHandle:find(filter: string) → ItemsHandle",
            description = "Narrows the handle down to items matching the filter. Useful for scanning a container's contents by mod / tag.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/items-handle.md#find",
        ))
        put("ItemsHandle:count", Doc(
            signature = "ItemsHandle:count() → number",
            description = "Total item count this handle represents right now.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/items-handle.md#count",
        ))

        // ===== Scheduler methods =====
        put("Scheduler:sleep", Doc(
            signature = "Scheduler:sleep(ticks: number)",
            description = "Pauses the current coroutine for N game ticks (20 ticks = 1 second).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/scheduler.md#sleep",
        ))
        put("Scheduler:waitUntil", Doc(
            signature = "Scheduler:waitUntil(predicate: () → boolean)",
            description = "Polls the predicate each tick and resumes the coroutine when it returns truthy.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/scheduler.md#wait-until",
        ))

        // ===== Clock methods =====
        put("Clock:time", Doc(
            signature = "Clock:time() → number",
            description = "Returns the world's current game time in ticks.",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/clock.md#time",
        ))
        put("Clock:day", Doc(
            signature = "Clock:day() → number",
            description = "Returns the current day number (ticks / 24000).",
            category = Category.METHOD,
            guidebookRef = "nodeworks:lua-api/clock.md#day",
        ))
    }

    /** Maps a module-global name to its type name. Method keys are stored under the
     *  TYPE (e.g. `"Network:get"`), so hovering `network:get` needs a way to hop from
     *  the literal module value `"network"` to the type `"Network"` before lookup.
     *  Users see the same tooltip whether they write `network:get(…)` at the top level
     *  or `local n = network; n:get(…)` via a typed local. */
    private val moduleTypes: Map<String, String> = mapOf(
        "network" to "Network",
        "card" to "Card",
        "scheduler" to "Scheduler",
        "clock" to "Clock",
    )

    /** Direct key lookup. Returns the doc entry for an exact symbol, or null if absent. */
    fun get(symbol: String): Doc? = entries[symbol]

    /**
     * Context-aware lookup with optional variable-type inference.
     *
     * Resolution order:
     *   1. `owner:member` / `owner.member` literal — covers module-level calls like
     *      `network:get`.
     *   2. If the owner is a local variable whose type is known, try
     *      `OwnerType:member` — covers typed locals like `local cards = card;
     *      cards:setPowered(true)` which resolves to `Card:setPowered`.
     *   3. Bare `member` literal.
     *   4. If the token IS a local variable with a known type, try the type name alone
     *      — covers hovering the variable name itself (`local items = …; items`).
     *
     * [variableTypes] comes from the editor's existing symbol-table inference pass
     * (see `AutocompletePopup.getSymbolTable`). Pass an empty map for contexts that
     * don't have live inference (e.g. the guidebook's `<LuaCode>` tag).
     */
    fun resolveAt(
        tokens: List<LuaTokenizer.Token>,
        index: Int,
        variableTypes: Map<String, String> = emptyMap(),
    ): Doc? {
        val tok = tokens.getOrNull(index) ?: return null
        // Only identifier-like tokens are eligible for docs. Skip strings / comments /
        // numbers / punctuation even if they happen to match an entry key.
        val eligible = tok.type == LuaTokenizer.TokenType.DEFAULT ||
            tok.type == LuaTokenizer.TokenType.FUNCTION ||
            tok.type == LuaTokenizer.TokenType.KEYWORD
        if (!eligible) return null

        // Walk back past whitespace to find punctuation + owner.
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
                        // 1) Literal owner:member (covers any entry stored under the
                        //    module-value name, e.g. a user-added `mymod:foo`).
                        entries["${owner.text}${sep.text}${tok.text}"]?.let { return it }
                        // 2) Module → Type hop: `network` is a value of type `Network`,
                        //    and method entries are stored under the type (`Network:get`).
                        //    This is the common path for top-level globals.
                        moduleTypes[owner.text]?.let { ownerType ->
                            entries["$ownerType${sep.text}${tok.text}"]?.let { return it }
                        }
                        // 3) Typed-variable fallback — owner is a local with an inferred
                        //    type (from the autocomplete symbol table).
                        variableTypes[owner.text]?.let { ownerType ->
                            entries["$ownerType${sep.text}${tok.text}"]?.let { return it }
                        }
                    }
                }
            }
        }
        // 3) Bare identifier lookup (keywords, globals).
        entries[tok.text]?.let { return it }
        // 4) Typed-variable self-hover — e.g. hovering `items` where
        //    `local items = network:get(…)` shows the `ItemsHandle` type doc.
        variableTypes[tok.text]?.let { selfType ->
            entries[selfType]?.let { return it }
        }
        return null
    }
}
