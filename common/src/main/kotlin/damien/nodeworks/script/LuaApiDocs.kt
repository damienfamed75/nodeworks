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

    enum class Category { KEYWORD, MODULE, FUNCTION, METHOD }

    data class Doc(
        /** Optional type/signature hint shown as the tooltip's first bolded line. */
        val signature: String? = null,
        /** One-or-two-line description. Keep it tight. */
        val description: String,
        val category: Category = Category.MODULE,
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
        put("network", Doc(
            signature = "network: Network",
            description = "The network this script is running on. Entry point for inventory queries, routing, and crafting.",
            category = Category.MODULE,
        ))
        put("card", Doc(
            signature = "card: Card",
            description = "The card this script was loaded from. Holds per-card state and hardware references.",
            category = Category.MODULE,
        ))
        put("scheduler", Doc(
            signature = "scheduler: Scheduler",
            description = "Async primitive — sleep, wait on conditions, yield from coroutine handlers.",
            category = Category.MODULE,
        ))
        put("clock", Doc(
            signature = "clock: Clock",
            description = "World-time queries. Useful for redstone-card triggers and daily schedules.",
            category = Category.MODULE,
        ))

        // ===== network: methods =====
        put("network:get", Doc(
            signature = "network:get(filter: string) → ItemsHandle",
            description = "Returns a handle representing all matching items on the network. Filter uses the resource-filter grammar (`\$item:modid:item`, wildcards, etc.).",
            category = Category.METHOD,
        ))
        put("network:find", Doc(
            signature = "network:find(filter: string) → ItemsHandle",
            description = "Alias of `get` with the same semantics. Use whichever reads more naturally at the call site.",
            category = Category.METHOD,
        ))
        put("network:insert", Doc(
            signature = "network:insert(stack: ItemsHandle) → ItemsHandle",
            description = "Inserts a stack into the network's storage, spreading across storage cards by the storage-first rule. Returns the remainder that couldn't fit.",
            category = Category.METHOD,
        ))
        put("network:route", Doc(
            signature = "network:route(stack: ItemsHandle, target: string) → ItemsHandle",
            description = "Moves items from the network to a target (another filter-addressable location). Returns the portion that actually moved.",
            category = Category.METHOD,
        ))
        put("network:onInsert", Doc(
            signature = "network:onInsert(fn: (stack) → nil)",
            description = "Registers a callback that fires when any item enters the network. Callback runs inside the script's coroutine, so blocking calls are safe.",
            category = Category.METHOD,
        ))

        // ===== scheduler: methods =====
        put("scheduler:sleep", Doc(
            signature = "scheduler:sleep(ticks: number)",
            description = "Pauses the current coroutine for N game ticks (20 ticks = 1 second).",
            category = Category.METHOD,
        ))
        put("scheduler:waitUntil", Doc(
            signature = "scheduler:waitUntil(predicate: () → boolean)",
            description = "Polls the predicate each tick and resumes the coroutine when it returns truthy.",
            category = Category.METHOD,
        ))

        // ===== clock: methods =====
        put("clock:time", Doc(
            signature = "clock:time() → number",
            description = "Returns the world's current game time in ticks.",
            category = Category.METHOD,
        ))
        put("clock:day", Doc(
            signature = "clock:day() → number",
            description = "Returns the current day number (ticks / 24000).",
            category = Category.METHOD,
        ))
    }

    /** Direct key lookup. Returns the doc entry for an exact symbol, or null if absent. */
    fun get(symbol: String): Doc? = entries[symbol]

    /**
     * Context-aware lookup: if the token at [index] is an identifier preceded by
     * `owner ( : | . ) member` (whitespace-collapsed), tries the qualified symbol
     * `owner:member` / `owner.member` first, then falls back to the bare member name.
     *
     * Skips over whitespace-only tokens so `foo :bar` and `foo.bar` both resolve
     * correctly even though our tokenizer emits the punctuation as its own single-char
     * token.
     */
    fun resolveAt(tokens: List<LuaTokenizer.Token>, index: Int): Doc? {
        val tok = tokens.getOrNull(index) ?: return null
        // Only identifier-like tokens are eligible for docs. Skip strings / comments /
        // numbers / punctuation even if they happen to match an entry key.
        val eligible = tok.type == LuaTokenizer.TokenType.DEFAULT ||
            tok.type == LuaTokenizer.TokenType.FUNCTION ||
            tok.type == LuaTokenizer.TokenType.KEYWORD
        if (!eligible) return null

        // Walk back past whitespace to find the punctuation + owner.
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
                        val qualified = "${owner.text}${sep.text}${tok.text}"
                        entries[qualified]?.let { return it }
                    }
                }
            }
        }
        return entries[tok.text]
    }
}
