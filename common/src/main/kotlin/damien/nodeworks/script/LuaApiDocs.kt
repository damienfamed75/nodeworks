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
     *  in here, `card` isn't a global at all, and `clock` is a bare function. */
    private val moduleTypes: Map<String, String> = mapOf(
        "network" to "Network",
        "scheduler" to "Scheduler",
        "importer" to "Importer",
        "stocker" to "Stocker",
    )

    private val entries: Map<String, Doc> = buildMap {
        // ===== Lua keywords =====
        put(
            "local", Doc(
                signature = "local <name> = <value>",
                description = "Declares a variable scoped to the enclosing block.",
                category = Category.KEYWORD,
            )
        )
        put(
            "function", Doc(
                signature = "function <name>(<params>) … end",
                description = "Declares a function. Use `local function` to keep it block-scoped.",
                category = Category.KEYWORD,
            )
        )
        put(
            "if", Doc(
                signature = "if <cond> then … [elseif …] [else …] end",
                description = "Conditional branch.",
                category = Category.KEYWORD,
            )
        )
        put(
            "for", Doc(
                signature = "for i = start, stop[, step] do … end  |  for k, v in pairs(t) do … end",
                description = "Numeric or generic loop. `pairs` iterates tables; `ipairs` iterates integer arrays in order.",
                category = Category.KEYWORD,
            )
        )
        put(
            "while", Doc(
                signature = "while <cond> do … end",
                description = "Loops while the condition is truthy.",
                category = Category.KEYWORD,
            )
        )
        put(
            "return", Doc(
                signature = "return [values…]",
                description = "Returns from the current function, optionally yielding values.",
                category = Category.KEYWORD,
            )
        )
        put(
            "break", Doc(
                description = "Exits the innermost enclosing loop.",
                category = Category.KEYWORD,
            )
        )

        // ===== Globals =====
        put(
            "print", Doc(
                signature = "print(…)",
                description = "Writes its arguments to the terminal log, space separated.",
                category = Category.FUNCTION,
            )
        )
        put(
            "require", Doc(
                signature = "require(modName) → module",
                description = "Loads and returns a Lua module from another script tab in this terminal.",
                category = Category.FUNCTION,
            )
        )
        put(
            "clock", Doc(
                signature = "clock() → number",
                description = "Fractional seconds since this script started running.",
                category = Category.FUNCTION,
            )
        )

        // ===== network / Network =====
        put(
            "network", Doc(
                signature = "network: Network",
                description = "Entry point into the network this script runs on.",
                category = Category.MODULE,
                guidebookRef = "nodeworks:lua-api/network.md",
            )
        )
        put(
            "Network", Doc(
                signature = "type Network",
                description = "The active network. Covers card lookup, storage queries, routing, and crafting.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/network.md",
            )
        )

        // ===== Channel =====
        // ===== HandleList =====
        // Returned by `network:getAll(type)` and `Channel:getAll(type)`. Broadcasts
        // *write* methods across every member, calling `:set(true)` on a
        // HandleList<RedstoneCard> invokes set on each card. Read methods don't
        // broadcast (their return value is the whole point), use `:list()` and a
        // for-loop when you need per-member reads.
        put(
            "HandleList", Doc(
                signature = "type HandleList",
                description = "A list of cards or devices that broadcasts write methods across every member. Use :list() for per-member access.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/handle-list.md",
            )
        )
        put(
            "HandleList:list", Doc(
                signature = "HandleList:list() → { T… }",
                description = "Returns the underlying array so scripts can iterate per-member or read individual values.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/handle-list.md#list",
            )
        )
        put(
            "HandleList:count", Doc(
                signature = "HandleList:count() → number",
                description = "Number of members in the list.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/handle-list.md#count",
            )
        )

        // Returned by `network:channel(color)`, scopes lookups to one dye-color group
        // covering both cards and devices. The bundled `:getFirst` / `:getAll` / `:get`
        // mirror the global accessors so once you've narrowed by channel everything
        // reads the same way as the un-scoped network API.
        put(
            "Channel", Doc(
                signature = "type Channel",
                description = "A dye-color-scoped view of the network's cards and devices.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/network.md#channel",
            )
        )
        put(
            "Channel:getFirst", Doc(
                signature = "Channel:getFirst(type: string) → CardHandle | nil",
                description = "First card or device of [type] on this channel, or nil if none match.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#channel-getfirst",
            )
        )
        put(
            "Channel:getAll", Doc(
                signature = "Channel:getAll(type: string?) → HandleList",
                description = "HandleList of every card or device on this channel matching [type]. Omit [type] for every member.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#channel-getall",
            )
        )
        put(
            "Channel:get", Doc(
                signature = "Channel:get(alias: string) → CardHandle",
                description = "Alias lookup scoped to this channel. Errors if no match exists.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#channel-get",
            )
        )
        put(
            "Network:get", Doc(
                signature = "Network:get(name: string) → CardHandle | VariableHandle",
                description = "Returns the card or variable with this name. Errors if neither matches. Cards win on collision.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#get",
            )
        )
        put(
            "Network:getAll", Doc(
                signature = "Network:getAll(type: string) → HandleList",
                description = "HandleList of every card or variable matching this type. Broadcasts write methods across all members.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#getAll",
            )
        )
        put(
            "Network:channel", Doc(
                signature = "Network:channel(color: string) → Channel",
                description = "Scopes lookups to a single dye-color channel. Errors on unknown color names.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#channel",
            )
        )
        put(
            "Network:channels", Doc(
                signature = "Network:channels() → { string }",
                description = "Color names of every channel currently in use on the network.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#channels",
            )
        )
        put(
            "Network:find", Doc(
                signature = "Network:find(filter: string) → ItemsHandle | nil",
                description = "Scans network storage for matching items or fluids. Returns an aggregated handle, or nil if nothing matches.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#find",
            )
        )
        put(
            "Network:findEach", Doc(
                signature = "Network:findEach(filter: string) → { ItemsHandle… }",
                description = "Returns a separate handle for every distinct resource matching the filter.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#findEach",
            )
        )
        put(
            "Network:count", Doc(
                signature = "Network:count(filter: string) → number",
                description = "Total quantity in network storage matching the filter. Fluids count in mB.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#count",
            )
        )
        put(
            "Network:insert", Doc(
                signature = "Network:insert(items: ItemsHandle[, count: number]) → boolean",
                description = "Moves the full count from the handle's source into storage, or moves nothing. Returns true on success.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#insert",
            )
        )
        put(
            "Network:tryInsert", Doc(
                signature = "Network:tryInsert(items: ItemsHandle[, count: number]) → number",
                description = "Best-effort move into storage. Returns the count actually moved.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#tryInsert",
            )
        )
        put(
            "Network:craft", Doc(
                signature = "Network:craft(itemId: string[, count: number]) → CraftBuilder | nil",
                description = "Queues a craft for the given item. Returns a CraftBuilder, or nil if the craft can't be planned.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#craft",
            )
        )
        put(
            "Network:route", Doc(
                signature = "Network:route(alias: string, predicate: (ItemsHandle) → boolean)",
                description = "Routing rule. Items matching `predicate` are directed to the card with this alias during `network:insert`.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#route",
            )
        )
        put(
            "Network:shapeless", Doc(
                signature = "Network:shapeless(itemId: string, count: number, …) → ItemsHandle | nil",
                description = "Crafts via a vanilla shapeless recipe, pulling the given ingredients from storage.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#shapeless",
            )
        )
        put(
            "Network:handle", Doc(
                signature = "Network:handle(cardName: string, handler: function(job, inputs))",
                description = "Registers a processing handler for a Processing Set. The handler uses `job:pull` to emit each output.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#handle",
            )
        )
        // (Network:var was removed, variables resolve through `network:get(name)`
        // alongside cards. The legacy entry isn't kept around as a deprecated alias
        // because the runtime no longer registers `var` on the network table.)
        put(
            "Network:debug", Doc(
                signature = "Network:debug()",
                description = "Prints a summary of the network to the terminal log.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#debug",
            )
        )

        // ===== scheduler / Scheduler =====
        put(
            "scheduler", Doc(
                signature = "scheduler: Scheduler",
                description = "Registers periodic and delayed callbacks that run on the server tick.",
                category = Category.MODULE,
            )
        )
        put(
            "Scheduler", Doc(
                signature = "type Scheduler",
                description = "Task scheduler. Every registration returns an id that can be passed to `:cancel`.",
                category = Category.TYPE,
            )
        )
        put(
            "Scheduler:tick", Doc(
                signature = "Scheduler:tick(fn: function) → number",
                description = "Runs `fn` every server tick. Returns a task id.",
                category = Category.METHOD,
            )
        )
        put(
            "Scheduler:second", Doc(
                signature = "Scheduler:second(fn: function) → number",
                description = "Runs `fn` every 20 ticks. Returns a task id.",
                category = Category.METHOD,
            )
        )
        put(
            "Scheduler:delay", Doc(
                signature = "Scheduler:delay(ticks: number, fn: function) → number",
                description = "Runs `fn` once after the given number of ticks. Returns a task id.",
                category = Category.METHOD,
            )
        )
        put(
            "Scheduler:cancel", Doc(
                signature = "Scheduler:cancel(id: number)",
                description = "Cancels a task returned by a scheduler method.",
                category = Category.METHOD,
            )
        )

        // ===== Job / InputItems / CraftBuilder =====
        // These types don't come from a global, they arrive as arguments to user
        // callbacks (`network:handle`, `:connect`) or as a return value from a method.
        // Documenting them here lets hover tooltips resolve the bare type names and
        // the short method list under each of them.
        put(
            "Job", Doc(
                signature = "type Job",
                description = "The first argument to a `network:handle` callback. Represents the in-flight processing job.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/network.md#handle",
            )
        )
        put(
            "Job:pull", Doc(
                signature = "Job:pull(card: CardHandle, …)",
                description = "Pulls an output from the given card so the Crafting CPU can collect it.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#handle",
            )
        )
        put(
            "InputItems", Doc(
                signature = "type InputItems",
                description = "The second argument to a `network:handle` callback. A per-recipe bag of `ItemsHandle` fields keyed by the recipe's input slot names.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/network.md#handle",
            )
        )
        put(
            "CraftBuilder", Doc(
                signature = "type CraftBuilder",
                description = "Returned by `network:craft`. Configures how the craft result is delivered once it completes.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/network.md#craft",
            )
        )
        put(
            "CraftBuilder:connect", Doc(
                signature = "CraftBuilder:connect(fn: function(items: ItemsHandle))",
                description = "Callback fired when the craft completes. Receives the output `ItemsHandle`.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#craft",
            )
        )
        put(
            "CraftBuilder:store", Doc(
                signature = "CraftBuilder:store()",
                description = "Sends the craft result into network storage using the normal routing rules.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/network.md#craft",
            )
        )

        // ===== Presets: importer / Importer / ImporterBuilder =====
        put(
            "importer", Doc(
                signature = "importer: Importer",
                description = "Builds item movers from a chained expression.",
                category = Category.MODULE,
                guidebookRef = "nodeworks:lua-api/importer.md",
            )
        )
        put(
            "Importer", Doc(
                signature = "type Importer",
                description = "Factory for `ImporterBuilder`.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/importer.md",
            )
        )
        put(
            "Importer:from", Doc(
                signature = "Importer:from(...sources: string | CardHandle | network) → ImporterBuilder",
                description = "Starts an importer chain from one or more sources.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/importer.md#from",
            )
        )
        put(
            "ImporterBuilder", Doc(
                signature = "type ImporterBuilder",
                description = "A configured or running item mover.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/importer.md",
            )
        )
        put(
            "ImporterBuilder:to", Doc(
                signature = "ImporterBuilder:to(...targets: string | CardHandle | network) → ImporterBuilder",
                description = "Sets the destinations.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/importer.md#to",
            )
        )
        put(
            "ImporterBuilder:roundrobin", Doc(
                signature = "ImporterBuilder:roundrobin(step: number?) → ImporterBuilder",
                description = "Switch to round robin distribution. `step` is items per target per tick (default 1).",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/importer.md#roundrobin",
            )
        )
        put(
            "ImporterBuilder:filter", Doc(
                signature = "ImporterBuilder:filter(pattern: string) → ImporterBuilder",
                description = "Narrows the mover to items matching the pattern. Defaults to `*`.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/importer.md#filter",
            )
        )
        put(
            "ImporterBuilder:every", Doc(
                signature = "ImporterBuilder:every(ticks: number) → ImporterBuilder",
                description = "How often the importer runs. Default 20 ticks.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#every",
            )
        )
        put(
            "ImporterBuilder:start", Doc(
                signature = "ImporterBuilder:start()",
                description = "Validates the chain and begins ticking.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#start",
            )
        )
        put(
            "ImporterBuilder:stop", Doc(
                signature = "ImporterBuilder:stop()",
                description = "Stops ticking. Restart with `:start()`.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#stop",
            )
        )
        put(
            "ImporterBuilder:isRunning", Doc(
                signature = "ImporterBuilder:isRunning() → boolean",
                description = "True if the preset is currently scheduled.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#isrunning",
            )
        )

        // ===== Presets: stocker / Stocker / StockerBuilder =====
        put(
            "stocker", Doc(
                signature = "stocker: Stocker",
                description = "Builds level maintainers from a chained expression.",
                category = Category.MODULE,
                guidebookRef = "nodeworks:lua-api/stocker.md",
            )
        )
        put(
            "Stocker", Doc(
                signature = "type Stocker",
                description = "Factory for `StockerBuilder`.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/stocker.md",
            )
        )
        put(
            "Stocker:from", Doc(
                signature = "Stocker:from(...sources: string | CardHandle | network) → StockerBuilder",
                description = "Pull from cards or the pool to maintain a level. Never crafts.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#from",
            )
        )
        put(
            "Stocker:ensure", Doc(
                signature = "Stocker:ensure(itemId: string) → StockerBuilder",
                description = "Pull from the pool first, craft the rest if short.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#ensure",
            )
        )
        put(
            "Stocker:craft", Doc(
                signature = "Stocker:craft(itemId: string) → StockerBuilder",
                description = "Always craft to maintain the level. Never pulls.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#craft",
            )
        )
        put(
            "StockerBuilder", Doc(
                signature = "type StockerBuilder",
                description = "A configured or running level maintainer.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/stocker.md",
            )
        )
        put(
            "StockerBuilder:to", Doc(
                signature = "StockerBuilder:to(target: string | CardHandle | network) → StockerBuilder",
                description = "Sets the destination.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#to",
            )
        )
        put(
            "StockerBuilder:keep", Doc(
                signature = "StockerBuilder:keep(amount: number) → StockerBuilder",
                description = "Target stock level. Never extracts above this.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#keep",
            )
        )
        put(
            "StockerBuilder:batch", Doc(
                signature = "StockerBuilder:batch(size: number) → StockerBuilder",
                description = "Coalesce craft requests into this batch size. Default 0.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#batch",
            )
        )
        put(
            "StockerBuilder:filter", Doc(
                signature = "StockerBuilder:filter(pattern: string) → StockerBuilder",
                description = "Pattern counted toward `:keep(n)` in the target.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/stocker.md#filter",
            )
        )
        put(
            "StockerBuilder:every", Doc(
                signature = "StockerBuilder:every(ticks: number) → StockerBuilder",
                description = "How often the stocker checks. Default 20 ticks.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#every",
            )
        )
        put(
            "StockerBuilder:start", Doc(
                signature = "StockerBuilder:start()",
                description = "Validates the chain and begins ticking.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#start",
            )
        )
        put(
            "StockerBuilder:stop", Doc(
                signature = "StockerBuilder:stop()",
                description = "Stops ticking. Restart with `:start()`.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#stop",
            )
        )
        put(
            "StockerBuilder:isRunning", Doc(
                signature = "StockerBuilder:isRunning() → boolean",
                description = "True if the preset is currently scheduled.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/presets.md#isrunning",
            )
        )

        // ===== CardHandle =====
        put(
            "CardHandle", Doc(
                signature = "type CardHandle",
                description = "A card on the network accessed by alias.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/card-handle.md",
            )
        )
        put(
            "CardHandle:face", Doc(
                signature = "CardHandle:face(name: string) → CardHandle",
                description = "Returns a new handle pinned to a specific face of the adjacent block.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#face",
            )
        )
        put(
            "CardHandle:slots", Doc(
                signature = "CardHandle:slots(…indices) → CardHandle",
                description = "Returns a new handle filtered to specific slot indices. Indices are 1-based.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#slots",
            )
        )
        put(
            "CardHandle:find", Doc(
                signature = "CardHandle:find(filter: string) → ItemsHandle | nil",
                description = "Like `Network:find`, scoped to this card's inventory.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#find",
            )
        )
        put(
            "CardHandle:findEach", Doc(
                signature = "CardHandle:findEach(filter: string) → { ItemsHandle… }",
                description = "Returns one handle per distinct resource in this card matching the filter.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#findEach",
            )
        )
        put(
            "CardHandle:insert", Doc(
                signature = "CardHandle:insert(items: ItemsHandle[, count: number]) → boolean",
                description = "Moves the full count from the handle's source into this card, or moves nothing. Returns true on success.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#insert",
            )
        )
        put(
            "CardHandle:tryInsert", Doc(
                signature = "CardHandle:tryInsert(items: ItemsHandle[, count: number]) → number",
                description = "Best-effort move into this card. Returns the count actually moved.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#tryInsert",
            )
        )
        put(
            "CardHandle:count", Doc(
                signature = "CardHandle:count(filter: string) → number",
                description = "Total quantity on this card matching the filter.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#count",
            )
        )
        put(
            "CardHandle.name", Doc(
                signature = "CardHandle.name: string",
                description = "The card's alias.",
                category = Category.PROPERTY,
                guidebookRef = "nodeworks:lua-api/card-handle.md#properties",
            )
        )

        // RedstoneCard is the same underlying Lua table as CardHandle, `.name` is set
        // by CardHandle.create before any redstone-specific rebinding. Mirror the doc
        // entry under the RedstoneCard key so hover tooltips resolve for variables
        // typed as RedstoneCard.
        put(
            "RedstoneCard.name", Doc(
                signature = "RedstoneCard.name: string",
                description = "The card's alias.",
                category = Category.PROPERTY,
                guidebookRef = "nodeworks:lua-api/card-handle.md#properties",
            )
        )
        put(
            "RedstoneCard", Doc(
                signature = "type RedstoneCard",
                description = "A card attached to a Redstone Card capability. Exposes redstone methods instead of the inventory methods.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/card-handle.md",
            )
        )
        put(
            "RedstoneCard:powered", Doc(
                signature = "RedstoneCard:powered() → boolean",
                description = "True if the incoming redstone signal is greater than 0.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#powered",
            )
        )
        put(
            "RedstoneCard:strength", Doc(
                signature = "RedstoneCard:strength() → number",
                description = "Current incoming redstone signal strength from 0 to 15.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#strength",
            )
        )
        put(
            "RedstoneCard:set", Doc(
                signature = "RedstoneCard:set(value: boolean | number)",
                description = "Emits a redstone signal. Boolean maps to 15 or 0. Number is clamped to 0 to 15.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#set",
            )
        )
        put(
            "RedstoneCard:onChange", Doc(
                signature = "RedstoneCard:onChange(fn: (strength: number) → nil)",
                description = "Registers a callback fired whenever the incoming signal strength changes.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#onchange",
            )
        )

        // ===== ObserverCard =====
        // Reads block id and state at the watched position. Drives stage-aware harvesting,
        // fluid level checks, and any other "do X when this block becomes Y" automation
        // without scripts having to poll on a scheduler.
        put(
            "ObserverCard", Doc(
                signature = "type ObserverCard",
                description = "A card that reads the block at its facing position. Exposes block(), state(), and onChange() instead of inventory methods.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/card-handle.md#observer-card",
            )
        )
        put(
            "ObserverCard:block", Doc(
                signature = "ObserverCard:block() → string",
                description = "Block id at the watched position, e.g. `\"minecraft:diamond_ore\"` or `\"nodeworks:celestine_cluster\"`.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#block",
            )
        )
        put(
            "ObserverCard:state", Doc(
                signature = "ObserverCard:state() → { [string]: any }",
                description = "Property table for the watched block. Keys are property names; values are numbers, booleans, or lowercase strings.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#state",
            )
        )
        put(
            "ObserverCard:onChange", Doc(
                signature = "ObserverCard:onChange(fn: (block: string, state: { [string]: any }) → nil)",
                description = "Fires whenever the watched block id or any state property changes. Replaces any prior handler on the same card.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/card-handle.md#observer-onchange",
            )
        )

        // ===== BreakerHandle =====
        // Device that breaks the block at its facing position over time. Drops route
        // to network storage by default, chain `:mine():connect(fn)` to redirect.
        put(
            "BreakerHandle", Doc(
                signature = "type BreakerHandle",
                description = "A Breaker device. Diamond-pickaxe tier; break duration uses the wooden-pickaxe formula.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md",
            )
        )
        put(
            "BreakerHandle:mine", Doc(
                signature = "BreakerHandle:mine() → BreakBuilder",
                description = "Starts a multi-tick break of the block in front. Returns a builder; chain :connect(fn) to redirect drops, or leave unchained to route drops to network storage.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#mine",
            )
        )
        put(
            "BreakerHandle:cancel", Doc(
                signature = "BreakerHandle:cancel()",
                description = "Aborts the in-flight break, if any. Safe to call when idle.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#cancel",
            )
        )
        put(
            "BreakerHandle:block", Doc(
                signature = "BreakerHandle:block() → string",
                description = "Block id at the breaker's facing position.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#block",
            )
        )
        put(
            "BreakerHandle:state", Doc(
                signature = "BreakerHandle:state() → { [string]: any }",
                description = "Property table for the block at the breaker's facing position.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#state",
            )
        )
        put(
            "BreakerHandle:isMining", Doc(
                signature = "BreakerHandle:isMining() → boolean",
                description = "True when a break is in progress.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#isMining",
            )
        )
        put(
            "BreakerHandle:progress", Doc(
                signature = "BreakerHandle:progress() → number",
                description = "0..1 fraction of the current break's progress. 0 when idle.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#progress",
            )
        )

        // ===== BreakBuilder =====
        put(
            "BreakBuilder", Doc(
                signature = "type BreakBuilder",
                description = "Returned by Breaker:mine(). Configures how the drops route once the break completes.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#breakbuilder",
            )
        )
        put(
            "BreakBuilder:connect", Doc(
                signature = "BreakBuilder:connect(fn: function(items: ItemsHandle))",
                description = "Redirects drops to a script handler instead of network storage. The handler receives one ItemsHandle per drop stack.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/breaker-handle.md#connect",
            )
        )

        // ===== PlacerHandle =====
        put(
            "PlacerHandle", Doc(
                signature = "type PlacerHandle",
                description = "A Placer device. Pulls one item from network storage and places it as a block in front.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/placer-handle.md",
            )
        )
        put(
            "PlacerHandle:place", Doc(
                signature = "PlacerHandle:place(item: string | ItemsHandle) → boolean",
                description = "Pulls one of [item] from network storage and places it. Returns true on success, false if the source is empty, the target isn't replaceable, or the item isn't a block.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/placer-handle.md#place",
            )
        )
        put(
            "PlacerHandle:block", Doc(
                signature = "PlacerHandle:block() → string",
                description = "Block id at the placer's facing position.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/placer-handle.md#block",
            )
        )
        put(
            "PlacerHandle:isBlocked", Doc(
                signature = "PlacerHandle:isBlocked() → boolean",
                description = "True if the target position is non-replaceable (a place would fail).",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/placer-handle.md#isBlocked",
            )
        )

        // ===== ItemsHandle =====
        put(
            "ItemsHandle", Doc(
                signature = "type ItemsHandle",
                description = "A snapshot of items or fluids matching some filter.",
                category = Category.TYPE,
                guidebookRef = "nodeworks:lua-api/items-handle.md",
            )
        )
        put(
            "ItemsHandle:hasTag", Doc(
                signature = "ItemsHandle:hasTag(tag: string) → boolean",
                description = "True if this resource is in the given tag.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/items-handle.md",
            )
        )
        put(
            "ItemsHandle:matches", Doc(
                signature = "ItemsHandle:matches(filter: string) → boolean",
                description = "True if this resource matches the filter, using `Network:find` syntax.",
                category = Category.METHOD,
                guidebookRef = "nodeworks:lua-api/items-handle.md",
            )
        )

        // ===== VariableHandle =====
        put(
            "VariableHandle", Doc(
                signature = "type VariableHandle",
                description = "A handle to a network variable returned by `Network:get`.",
                category = Category.TYPE,
            )
        )
        put(
            "VariableHandle:get", Doc(
                signature = "VariableHandle:get() → any",
                description = "Returns the variable's current value.",
                category = Category.METHOD,
            )
        )
        put(
            "VariableHandle:set", Doc(
                signature = "VariableHandle:set(value)",
                description = "Sets the variable's value. Must match the variable's declared type.",
                category = Category.METHOD,
            )
        )
        put(
            "VariableHandle:cas", Doc(
                signature = "VariableHandle:cas(expected, new) → boolean",
                description = "Compare and swap. Sets the variable to `new` only if its current value equals `expected`. Returns true on success.",
                category = Category.METHOD,
            )
        )
        put(
            "VariableHandle:type", Doc(
                signature = "VariableHandle:type() → string",
                description = "Returns the variable's declared type as a string.",
                category = Category.METHOD,
            )
        )
        put(
            "VariableHandle.name", Doc(
                signature = "VariableHandle.name: string",
                description = "The variable's declared name.",
                category = Category.PROPERTY,
                guidebookRef = "nodeworks:lua-api/variable-handle.md",
            )
        )
        put(
            "VariableHandle:tryLock", Doc(
                signature = "VariableHandle:tryLock() → boolean",
                description = "Attempts to acquire the variable's lock. Returns true on success, false if another script holds it.",
                category = Category.METHOD,
            )
        )
        put(
            "VariableHandle:unlock", Doc(
                signature = "VariableHandle:unlock()",
                description = "Releases a lock acquired via `:tryLock`.",
                category = Category.METHOD,
            )
        )

        // Number-typed variables:
        put(
            "NumberVariableHandle", Doc(
                signature = "type NumberVariableHandle",
                description = "A `VariableHandle` with numeric-specific helpers.",
                category = Category.TYPE,
            )
        )
        put(
            "NumberVariableHandle:increment", Doc(
                signature = "NumberVariableHandle:increment([by: number = 1])",
                description = "Adds `by` to the variable atomically. Defaults to 1.",
                category = Category.METHOD,
            )
        )
        put(
            "NumberVariableHandle:decrement", Doc(
                signature = "NumberVariableHandle:decrement([by: number = 1])",
                description = "Subtracts `by` from the variable atomically. Defaults to 1.",
                category = Category.METHOD,
            )
        )
        put(
            "NumberVariableHandle:min", Doc(
                signature = "NumberVariableHandle:min(other: number)",
                description = "Sets the variable to `min(current, other)` atomically.",
                category = Category.METHOD,
            )
        )
        put(
            "NumberVariableHandle:max", Doc(
                signature = "NumberVariableHandle:max(other: number)",
                description = "Sets the variable to `max(current, other)` atomically.",
                category = Category.METHOD,
            )
        )

        // String-typed variables:
        put(
            "StringVariableHandle", Doc(
                signature = "type StringVariableHandle",
                description = "A `VariableHandle` with string-specific helpers.",
                category = Category.TYPE,
            )
        )
        put(
            "StringVariableHandle:append", Doc(
                signature = "StringVariableHandle:append(str: string)",
                description = "Appends `str` to the current value.",
                category = Category.METHOD,
            )
        )
        put(
            "StringVariableHandle:length", Doc(
                signature = "StringVariableHandle:length() → number",
                description = "Length of the current string value.",
                category = Category.METHOD,
            )
        )
        put(
            "StringVariableHandle:clear", Doc(
                signature = "StringVariableHandle:clear()",
                description = "Sets the variable to the empty string.",
                category = Category.METHOD,
            )
        )

        // Bool-typed variables:
        put(
            "BoolVariableHandle", Doc(
                signature = "type BoolVariableHandle",
                description = "A `VariableHandle` with a boolean-specific helper.",
                category = Category.TYPE,
            )
        )
        put(
            "BoolVariableHandle:toggle", Doc(
                signature = "BoolVariableHandle:toggle()",
                description = "Flips the boolean value.",
                category = Category.METHOD,
            )
        )
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
                            }
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
