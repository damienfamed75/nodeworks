package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import damien.nodeworks.platform.PlatformServices
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * Manages a sandboxed Lua VM for one terminal. Provides the Nodeworks API
 * (card, scheduler, print) and enforces an instruction budget per tick.
 */
class ScriptEngine(
    internal val level: ServerLevel,
    private val networkEntryNode: BlockPos,
    private val logCallback: (String, Boolean) -> Unit // (message, isError)
) {
    private var globals: Globals? = null
    private var networkSnapshot: NetworkSnapshot? = null
    val scheduler = SchedulerImpl { errorMsg -> logCallback(errorMsg, true) }

    /** Preset builders (Importer, Stocker) registered by the `importer` / `stocker`
     *  factory globals. Each preset is stopped in [stop] before the scheduler is
     *  cleared so per-preset state can unwind cleanly. */
    internal val presets = mutableListOf<damien.nodeworks.script.preset.PresetBuilder<*>>()

    /** Register a preset builder so it's stopped on script teardown. Called by
     *  each factory method the instant a builder is created (not when the user
     *  calls `:run()`) so dangling builders that never start still get cleaned up. */
    internal fun registerPreset(p: damien.nodeworks.script.preset.PresetBuilder<*>) {
        p.registryIndex = presets.size
        presets.add(p)
    }

    /** Current network snapshot. Rebuilt by [start] and refreshed periodically.
     *  Presets compare identity (`!==`) to decide when to re-resolve card names. */
    internal fun currentSnapshot(): NetworkSnapshot? = networkSnapshot

    /** Log an error through the terminal's log callback. Presets use this when
     *  a tick throws so the player sees the error without the preset unscheduling. */
    internal fun logError(msg: String) = logCallback(msg, true)

    /** Precomputed route table set by network:route(). */
    var routeTable: RouteTable? = null
        private set

    /** Cached inventory index across all network storage. */
    var inventoryCache: NetworkInventoryCache? = null
        private set

    /** Processing handlers registered by network:handle(). Keyed by card name. */
    val processingHandlers = mutableMapOf<String, LuaFunction>()

    /** Redstone onChange callbacks. Keyed by card alias → (capability, lastStrength, callback). */
    private data class RedstoneCallback(
        val capability: damien.nodeworks.card.RedstoneSideCapability,
        var lastStrength: Int,
        val callback: LuaFunction
    )
    private val redstoneCallbacks = mutableMapOf<String, RedstoneCallback>()

    /** Observer onChange callbacks. Keyed by card alias → (capability, lastState, callback).
     *  [lastState] starts populated with the state observed when the script registers the
     *  callback so a fresh script run doesn't fire a spurious onChange for a block that's
     *  been sitting in its final form since before the script started. */
    private data class ObserverCallback(
        val capability: damien.nodeworks.card.ObserverSideCapability,
        var lastState: net.minecraft.world.level.block.state.BlockState,
        val callback: LuaFunction
    )
    private val observerCallbacks = mutableMapOf<String, ObserverCallback>()


    fun start(scripts: Map<String, String>): Boolean {
        stop()

        val mainScript = scripts["main"]
        if (mainScript.isNullOrBlank()) {
            logCallback("Error: no 'main' script found.", true)
            return false
        }

        // Discover network
        networkSnapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        inventoryCache = NetworkInventoryCache.getOrCreate(level, networkEntryNode)

        // Create sandboxed globals
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())

        // Install the Lua compiler
        LuaC.install(g)

        // Remove dangerous globals
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("os", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)

        // Custom require() that resolves modules from the scripts map
        val loaded = LuaTable()
        g.set("require", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val modName = arg.checkjstring()

                // Return cached module if already loaded
                val cached = loaded.get(modName)
                if (!cached.isnil()) return cached

                val source = scripts[modName]
                    ?: throw LuaError("module '$modName' not found")

                // Mark as loading (prevents circular require)
                loaded.set(modName, LuaValue.TRUE)

                val chunk = g.load(wrapForLoopIterators(stripTypeAnnotations(source)), modName)
                val result = chunk.call()

                // If the module returned a value, cache that, otherwise cache true
                val moduleValue = if (result.isnil()) LuaValue.TRUE else result
                loaded.set(modName, moduleValue)
                return moduleValue
            }
        })

        // Initialize scheduler with the current server tick
        scheduler.initialize(PlatformServices.modState.tickCount)

        // Inject Nodeworks API
        injectApi(g)

        globals = g

        // Compile and run the main script (top-level code: variable setup, scheduler registrations)
        return try {
            val chunk = g.load(wrapForLoopIterators(stripTypeAnnotations(mainScript)), "main")
            logCallback("Script started.", false)
            chunk.call()
            true
        } catch (e: LuaError) {
            // Script-level errors belong in the player-facing terminal log and the
            // Diagnostic Tool's error buffer, not the server console.
            logCallback("Error: ${e.message}", true)
            stop()
            false
        }
    }

    fun stop() {
        routeTable = null
        inventoryCache = null // clear local reference, cache lives in global registry
        processingHandlers.clear()
        redstoneCallbacks.clear()
        observerCallbacks.clear()
        // Stop every registered preset before wiping the scheduler so per-preset
        // cleanup (Stocker's pending craft callbacks, cached CardSnapshot lookups,
        // etc.) runs while the scheduler task ids are still valid. Exceptions in
        // a single preset's stop() never block the rest from unwinding.
        for (p in presets) { try { p.stop() } catch (_: Exception) {} }
        presets.clear()
        scheduler.clear()
        globals = null
        networkSnapshot = null
    }

    fun isRunning(): Boolean = globals != null

    /** Whether this engine should stay alive, has scheduler tasks, handlers, or routing. */
    fun hasWork(): Boolean = scheduler.hasActiveTasks()
        || processingHandlers.isNotEmpty()
        || redstoneCallbacks.isNotEmpty()
        || observerCallbacks.isNotEmpty()
        || routeTable?.hasRoutes() == true


    /** Called each server tick. Runs scheduler callbacks within the instruction budget. */
    fun tick(tickCount: Long) {
        if (globals == null) return

        // The server keeps every Connectable's `networkId` current, when an LOS break or
        // removed node severs the path, `propagateNetworkId` clears it on the orphaned
        // side. If our entry node no longer claims a network the terminal is effectively
        // disconnected, running further would silently operate against a stale snapshot
        // so stop with a clear error and let auto-run restart us once reconnected.
        val entry = level.getBlockEntity(networkEntryNode) as? damien.nodeworks.network.Connectable
        if (entry?.networkId == null) {
            logCallback("Network disconnected, no controller reachable.", true)
            stop()
            return
        }

        try {
            scheduler.tick(tickCount)
            pollRedstoneCallbacks()
            pollObserverCallbacks()
        } catch (e: LuaError) {
            logCallback("Runtime error: ${e.message}", true)
            stop()
        } catch (e: Exception) {
            logCallback("Runtime error: ${e.message}", true)
            stop()
        }
    }

    private fun pollRedstoneCallbacks() {
        if (redstoneCallbacks.isEmpty()) return
        for ((_, cb) in redstoneCallbacks) {
            val currentStrength = level.getSignal(cb.capability.adjacentPos, cb.capability.nodeSide)
            if (currentStrength != cb.lastStrength) {
                cb.lastStrength = currentStrength
                cb.callback.call(LuaValue.valueOf(currentStrength))
            }
        }
    }

    /** Polled once per server tick. Skips any observer whose target chunk isn't loaded
     *  so a far-away farm doesn't pay chunk-load cost from the polling loop alone, when
     *  the chunk reloads the next poll resyncs `lastState` silently and won't fire a
     *  spurious onChange for the load delta. Handler exceptions are caught and routed
     *  through the log so one bad observer can't kill the whole tick. */
    private fun pollObserverCallbacks() {
        if (observerCallbacks.isEmpty()) return
        for ((alias, cb) in observerCallbacks) {
            val pos = cb.capability.adjacentPos
            if (!level.isLoaded(pos)) continue
            val current = level.getBlockState(pos)
            if (current == cb.lastState) continue
            cb.lastState = current
            try {
                cb.callback.call(
                    LuaValue.valueOf(blockIdOf(current)),
                    blockStateToLua(current)
                )
            } catch (e: LuaError) {
                logCallback("[observer:$alias] ${e.message}", true)
            } catch (e: Exception) {
                logCallback("[observer:$alias] ${e.message ?: e.javaClass.simpleName}", true)
            }
        }
    }

    /** Block id at [pos] formatted as `"namespace:path"`. Used by observer reads
     *  and onChange dispatch so scripts can compare against literal id strings. */
    private fun blockIdOf(state: net.minecraft.world.level.block.state.BlockState): String =
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()

    /** Convert a [BlockState]'s property map into a Lua table. Numeric properties
     *  surface as Lua numbers, booleans as booleans, and enum-like properties (facing,
     *  half, axis, …) as lowercase strings to match Minecraft's command syntax. Block
     *  types with no properties produce an empty table. */
    private fun blockStateToLua(state: net.minecraft.world.level.block.state.BlockState): LuaTable {
        val t = LuaTable()
        for (prop in state.properties) {
            @Suppress("UNCHECKED_CAST")
            val typed = prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>
            val value = state.getValue(typed)
            val lua: LuaValue = when (value) {
                is Boolean -> LuaValue.valueOf(value)
                is Number -> LuaValue.valueOf(value.toInt())
                else -> LuaValue.valueOf(value.toString().lowercase())
            }
            t.set(prop.name, lua)
        }
        return t
    }

    companion object {
        /**
         * Strips Luau-style type annotations from script text before Lua compilation.
         * Handles: function params `(x: Type)`, return types `): Type`, local vars `local x: Type =`
         */
        private val typePattern = """(?:[A-Z]\w*|string|number|boolean|any)\??"""

        fun stripTypeAnnotations(source: String): String {
            var result = source

            // Container return-type annotations come BEFORE the scalar strip so the
            // brace-delimited form `: { CardHandle }` / `: { [string]: V }` gets caught
            // as a whole rather than leaving an unmatched brace behind.
            result = result.replace(Regex("""\)\s*:\s*\{[^}]*}""")) { ")" }

            // Function parameter types: (param:Type) or (param: Type) or (param :Type)
            // Matches uppercase types (CardHandle, ItemsHandle) and builtin types (string, number, boolean, any)
            result = result.replace(Regex("""\b(\w+)\s*:\s*($typePattern)""")) { match ->
                match.groupValues[1]
            }

            // Return type annotations: ): TypeName or ): TypeName?
            result = result.replace(Regex("""\)\s*:\s*($typePattern)""")) { ")" }

            // Container param types: (param: { CardHandle }), keep the param name,
            // drop the annotation. Matches the same brace-delimited form as returns.
            result = result.replace(Regex("""\b(\w+)\s*:\s*\{[^}]*}""")) { it.groupValues[1] }

            return result
        }

        /**
         * Rewrite `for ... in EXPR do` to `for ... in ipairs(EXPR) do` or `pairs(EXPR)`
         * when EXPR isn't already wrapped. Iterator choice comes from [LuaApiDocs],
         * array-returning calls (`{ X }`) wrap in `ipairs`, map-returning calls
         * (`{ [K]: V }`) wrap in `pairs`, and anything we can't resolve defaults to
         * `pairs` since it iterates any table safely.
         *
         * Intentionally conservative: if the expression already starts with `ipairs(` /
         * `pairs(`, or parses to something other than a function/method call, we leave
         * it alone, users writing custom iterators keep full control.
         */
        fun wrapForLoopIterators(source: String): String {
            // First pass: infer container kind for bare-variable for-loops. Covers two
            // sources, with the explicit annotation winning when both apply:
            //   * `local xs: { T }` / `local xs: { [K]: V }`, user-declared container
            //     type. Authoritative.
            //   * `local xs = fn()` where fn returns a container (per [LuaApiDocs]),
            //     inferred fallback.
            val containerVars = mutableMapOf<String, LuaApiDocs.Container>()
            val annotationPattern = Regex("""\blocal\s+(\w+)\s*:\s*(\{[^}]*})""")
            for (match in annotationPattern.findAll(source)) {
                val varName = match.groupValues[1]
                val rt = LuaApiDocs.parseReturnType("() → ${match.groupValues[2]}")
                if (rt != null && rt.container != LuaApiDocs.Container.NONE) {
                    containerVars[varName] = rt.container
                }
            }
            val localPattern = Regex("""\blocal\s+(\w+)\s*=\s*(.+)""")
            for (match in localPattern.findAll(source)) {
                val varName = match.groupValues[1]
                if (varName in containerVars) continue // explicit annotation wins
                val rhs = match.groupValues[2].trim()
                val methodName = Regex("""(\w+)\s*\(""").findAll(rhs).lastOrNull()?.groupValues?.get(1) ?: continue
                val rt = LuaApiDocs.methodReturnType(methodName) ?: continue
                if (rt.container != LuaApiDocs.Container.NONE) {
                    containerVars[varName] = rt.container
                }
            }

            val forPattern = Regex("""\bfor\s+(\w+(?:\s*,\s*\w+)?)\s+in\s+(.+?)\s+do\b""")
            return forPattern.replace(source) { match ->
                val binding = match.groupValues[1]
                val expr = match.groupValues[2].trim()
                if (expr.startsWith("ipairs(") || expr.startsWith("pairs(")) return@replace match.value

                // Bare variable referring to a tracked container: pick the right wrapper
                // based on what the original call returned. Falls through to the call
                // resolution below when the var isn't known to hold a container.
                val bareContainer = if (Regex("""^\w+$""").matches(expr)) containerVars[expr] else null

                val container = bareContainer ?: run {
                    val methodName = Regex("""(\w+)\s*\(""").findAll(expr).lastOrNull()?.groupValues?.get(1)
                    methodName?.let { LuaApiDocs.methodReturnType(it)?.container }
                }
                val iter = when (container) {
                    LuaApiDocs.Container.ARRAY -> "ipairs"
                    LuaApiDocs.Container.MAP -> "pairs"
                    // Unknown / scalar / nothing parsed, default to `pairs` because it
                    // iterates any table shape without needing contiguous integer keys.
                    else -> "pairs"
                }
                "for $binding in $iter($expr) do"
            }
        }
    }

    private fun createCardTable(card: damien.nodeworks.network.CardSnapshot, alias: String): LuaTable {
        val table = CardHandle.create(card, level)
        val cap = card.capability

        if (cap is damien.nodeworks.card.RedstoneSideCapability) {
            // Remove inventory methods that don't apply to redstone. `face` is also cleared
            // because redstone methods read from `cap.nodeSide` (the side the card is
            // installed on), they never consult the CardHandle's `accessFace`, so
            // `redstone:face("top"):powered()` does nothing useful. Worse, `:face` builds
            // a fresh CardHandle.toLuaTable which re-installs `find`/`insert`/etc. without
            // going through this NIL'ing branch, so calling it would resurrect inventory
            // methods on a block that can't host them and blow up at runtime.
            table.set("find", LuaValue.NIL)
            table.set("findEach", LuaValue.NIL)
            table.set("insert", LuaValue.NIL)
            table.set("tryInsert", LuaValue.NIL)
            table.set("count", LuaValue.NIL)
            table.set("slots", LuaValue.NIL)
            table.set("face", LuaValue.NIL)

            // powered() → boolean
            table.set("powered", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue {
                    val strength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    return LuaValue.valueOf(strength > 0)
                }
            })

            // strength() → number 0-15
            table.set("strength", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue {
                    val strength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    return LuaValue.valueOf(strength)
                }
            })

            // set(boolean | number), emit redstone signal
            table.set("set", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, valueArg: LuaValue): LuaValue {
                    val strength = when {
                        valueArg.isboolean() -> if (valueArg.toboolean()) 15 else 0
                        valueArg.isnumber() -> valueArg.checkint().coerceIn(0, 15)
                        else -> throw LuaError("set() expects boolean or number (0-15)")
                    }
                    val entity = level.getBlockEntity(cap.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity
                        ?: throw LuaError("Node block entity not found")
                    entity.setRedstoneOutput(cap.nodeSide, strength)
                    return LuaValue.NIL
                }
            })

            // onChange(function(strength: number)), register callback for signal changes
            table.set("onChange", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, fnArg: LuaValue): LuaValue {
                    val fn = fnArg.checkfunction()
                    val currentStrength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    redstoneCallbacks[alias] = RedstoneCallback(cap, currentStrength, fn)
                    return LuaValue.NIL
                }
            })
        }

        if (cap is damien.nodeworks.card.ObserverSideCapability) {
            // Observer cards have no inventory and no redirected face, `:face` would
            // produce a CardHandle table that hides `block`/`state`/`onChange` and would
            // also re-install inventory methods that crash on a non-storage block. Same
            // pattern as redstone: scrub the inventory surface, then bind the typed methods.
            table.set("find", LuaValue.NIL)
            table.set("findEach", LuaValue.NIL)
            table.set("insert", LuaValue.NIL)
            table.set("tryInsert", LuaValue.NIL)
            table.set("count", LuaValue.NIL)
            table.set("slots", LuaValue.NIL)
            table.set("face", LuaValue.NIL)

            // block() → string, current block id at the watched position.
            table.set("block", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue =
                    LuaValue.valueOf(blockIdOf(level.getBlockState(cap.adjacentPos)))
            })

            // state() → { [string]: any }, properties of the watched block.
            table.set("state", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue =
                    blockStateToLua(level.getBlockState(cap.adjacentPos))
            })

            // onChange(function(block: string, state: table))
            // Replaces any prior handler bound to the same alias. `lastState` seeds with the
            // current block so the very first poll after registration won't fire a phantom
            // change event for "transition from null to whatever's already there."
            table.set("onChange", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, fnArg: LuaValue): LuaValue {
                    val fn = fnArg.checkfunction()
                    val seed = level.getBlockState(cap.adjacentPos)
                    observerCallbacks[alias] = ObserverCallback(cap, seed, fn)
                    return LuaValue.NIL
                }
            })
        }

        return table
    }

    /**
     * Construct the Lua handle returned by `network:channel(color)`. Exposes:
     *   * `:first(type)`, first card or variable matching [type] AND [color], nil if none.
     *   * `:all(type?)`, array of every member matching [type] AND [color]. Omitting
     *     [type] returns every member of the channel regardless of capability type.
     *   * `:get(alias)`, alias lookup scoped to this channel, throws on no match.
     *
     * Variables count as channel members alongside cards: scripts ask for
     * `:first("variable")` to get a `VariableHandle`, or `:all()` to walk every
     * card AND variable on the channel in one pass. The per-member dispatch
     * routes through [createCardTable] / [VariableHandle.create] so channel-scoped
     * lookups return the same typed tables the global accessors do, no method
     * surface is lost by going through a channel.
     */
    private fun createChannelTable(
        snapshot: NetworkSnapshot,
        color: net.minecraft.world.item.DyeColor,
    ): LuaTable {
        val t = LuaTable()
        val selfRef = this

        // :getFirst(type), first card or variable matching [type] AND this channel,
        // or nil. Renamed from `:first` to keep every "fetch" method in the API on
        // the `:get*` prefix (`network:get`, `network:getAll`, `Channel:get`).
        t.set("getFirst", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, typeArg: LuaValue): LuaValue {
                val type = typeArg.checkjstring()
                if (type == "variable") {
                    val v = snapshot.variables.firstOrNull { it.channel == color } ?: return LuaValue.NIL
                    return VariableHandle.create(v, level)
                }
                if (type == "breaker") {
                    val b = snapshot.breakers.firstOrNull { it.channel == color } ?: return LuaValue.NIL
                    return BreakerHandle.create(b, snapshot, level)
                }
                if (type == "placer") {
                    val p = snapshot.placers.firstOrNull { it.channel == color } ?: return LuaValue.NIL
                    return PlacerHandle.create(p, snapshot, level)
                }
                val card = snapshot.allCards().firstOrNull {
                    it.channel == color && it.capability.type == type
                } ?: return LuaValue.NIL
                return selfRef.createCardTable(card, card.effectiveAlias)
            }
        })

        // :getAll(type?), `HandleList<T>` of every member matching [type] AND this
        // channel. Omitting [type] returns a HandleList over every member of the
        // channel, the broadcast method set is then empty (mixed types have no
        // single broadcast contract), so only `:list()` / `:count()` are useful.
        t.set("getAll", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val type: String? = if (args.narg() >= 2 && !args.arg(2).isnil()) args.checkjstring(2) else null
                val members = mutableListOf<LuaValue>()
                // Variables, breakers, placers, and cards are independent collections
                // on the snapshot, iterate each only when [type] selects it (or is
                // null = "all members"). Order in the resulting list is variables →
                // breakers → placers → cards so a full :getAll() walks devices first
                // then cards, which roughly matches sidebar ordering.
                if (type == null || type == "variable") {
                    for (v in snapshot.variables) {
                        if (v.channel != color) continue
                        members.add(VariableHandle.create(v, level))
                    }
                }
                if (type == null || type == "breaker") {
                    for (b in snapshot.breakers) {
                        if (b.channel != color) continue
                        members.add(BreakerHandle.create(b, snapshot, level))
                    }
                }
                if (type == null || type == "placer") {
                    for (p in snapshot.placers) {
                        if (p.channel != color) continue
                        members.add(PlacerHandle.create(p, snapshot, level))
                    }
                }
                if (type != "variable" && type != "breaker" && type != "placer") {
                    for (card in snapshot.allCards()) {
                        if (card.channel != color) continue
                        if (type != null && card.capability.type != type) continue
                        members.add(selfRef.createCardTable(card, card.effectiveAlias))
                    }
                }
                val broadcasts = when {
                    type == null -> emptySet()
                    type == "variable" -> HandleListMethods.methodsForHandleType("VariableHandle")
                    else -> HandleListMethods.methodsForCapabilityType(type)
                }
                return selfRef.createHandleListTable(members, broadcasts)
            }
        })

        t.set("get", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                val card = snapshot.allCards().firstOrNull {
                    it.channel == color && it.effectiveAlias == alias
                }
                if (card != null) return selfRef.createCardTable(card, card.effectiveAlias)
                val v = snapshot.variables.firstOrNull { it.channel == color && it.name == alias }
                if (v != null) return VariableHandle.create(v, level)
                val b = snapshot.breakers.firstOrNull { it.channel == color && it.effectiveAlias == alias }
                if (b != null) return BreakerHandle.create(b, snapshot, level)
                val p = snapshot.placers.firstOrNull { it.channel == color && it.effectiveAlias == alias }
                if (p != null) return PlacerHandle.create(p, snapshot, level)
                throw LuaError("No member named '$alias' on the ${color.name.lowercase()} channel")
            }
        })

        return t
    }

    /**
     * Build the Lua handle returned by `network:getAll(type)` and `Channel:getAll(type)`.
     *
     * A `HandleList<T>` exposes:
     *   * `:list()`, the underlying array of T (escape hatch for per-member work)
     *   * `:count()`, number of members
     *   * one fan-out method per entry in [HandleListMethods] for the element type,
     *     calling `list:set(true)` on a `HandleList<RedstoneCard>` invokes `:set(true)`
     *     on each member, return values discarded.
     *
     * [memberTables] is the already-built per-element Lua tables (output of
     * `createCardTable` / `VariableHandle.create`). [broadcastMethodNames] is read
     * from [HandleListMethods] and tells us which methods to fan out, the registry
     * is the single source of truth so adding a new card / device type only requires
     * updating that one file for HandleList participation.
     */
    private fun createHandleListTable(
        memberTables: List<LuaValue>,
        broadcastMethodNames: Set<String>,
    ): LuaTable {
        val list = LuaTable()

        // Marker so preset builders can detect a HandleList in their varargs and
        // expand its members inline (CardRefs.fromVarargs reads this). Distinct
        // from `_isNetworkPool`, those are the two table-shaped sentinel values
        // the preset builders recognise.
        list.set("_isHandleList", LuaValue.TRUE)

        // :list(), return a Lua array of every member. Built lazily on call so we
        // can hand back a fresh table each time (callers iterating the result with
        // ipairs shouldn't accidentally mutate the HandleList's underlying state).
        list.set("list", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                val arr = LuaTable()
                for ((i, m) in memberTables.withIndex()) arr.set(i + 1, m)
                return arr
            }
        })

        // :count(), number of members. Cheap, but worth a dedicated method so
        // scripts don't need to call :list() and `#` it just to count.
        list.set("count", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue =
                LuaValue.valueOf(memberTables.size)
        })

        // :face(name), return a NEW HandleList where every CardHandle member has
        // been re-built with the given access face. Useful for routing through
        // preset builders, `importer:from(network:cards("io_*"):face("bottom"))`
        // pulls from the bottom face of every matched card without the script
        // having to iterate manually. Members that aren't CardHandles (variables,
        // breakers, placers) pass through untouched, their handle types ignore
        // face. Same broadcast set as the source list since face-overriding a
        // card doesn't change its capability.
        list.set("face", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val faceFn = LuaValue.valueOf("face")
                val rebuilt = memberTables.map { m ->
                    val fn = m.get(faceFn)
                    if (fn.isfunction()) {
                        // member:face(name) → fresh CardHandle table with override.
                        fn.call(m, nameArg)
                    } else {
                        m
                    }
                }
                return createHandleListTable(rebuilt, broadcastMethodNames)
            }
        })

        // Broadcast wrappers, one per registered method name. Each wrapper looks
        // up the matching field on every member at call time and invokes it with
        // the same args. Return values are discarded, the HandleList model is
        // strictly write-only by design (see HandleListMethods doc comment).
        for (methodName in broadcastMethodNames) {
            list.set(methodName, object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    // args.arg(1) is `self` (the HandleList table), the user's
                    // argument list starts at index 2 and runs to args.narg().
                    val userArgs = if (args.narg() <= 1) {
                        LuaValue.NONE
                    } else {
                        val collected = arrayOfNulls<LuaValue>(args.narg() - 1)
                        for (i in 1 until args.narg()) collected[i - 1] = args.arg(i + 1)
                        @Suppress("UNCHECKED_CAST")
                        LuaValue.varargsOf(collected as Array<LuaValue>)
                    }
                    for (member in memberTables) {
                        val fn = member.get(methodName)
                        if (fn.isfunction()) {
                            // Invoke as method: pass member as first arg so the
                            // wrapped function sees `self` correctly.
                            fn.invoke(LuaValue.varargsOf(arrayOf(member), userArgs))
                        }
                    }
                    return LuaValue.NIL
                }
            })
        }

        return list
    }

    /**
     * Backs both `network:insert` (atomic=true → boolean) and `network:tryInsert`
     * (atomic=false → number). Structured identically to [CardHandle]'s insert pair so
     * scripts get consistent semantics whether they're targeting a specific card or the
     * network as a whole. `routeTable` / `inventoryCache` are read from `this` at call
     * time so routes registered mid-script via `network:route` take effect.
     */
    private fun buildNetworkInsertFn(snapshot: NetworkSnapshot, atomic: Boolean): VarArgFunction {
        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val itemsTable = args.checktable(2)
                val maxCount = if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    args.checklong(3)
                } else {
                    Long.MAX_VALUE
                }
                val ref = itemsTable.get("_itemsHandle")
                if (ref.isnil() || ref !is ItemsHandle.ItemsHandleRef) {
                    throw LuaError("Expected an ItemsHandle from :find() or network:craft()")
                }
                val itemsHandle = ref.handle
                val requested = minOf(maxCount, itemsHandle.count.toLong())
                if (requested <= 0L) {
                    return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                }

                return if (itemsHandle.kind == damien.nodeworks.platform.ResourceKind.FLUID) {
                    invokeFluid(snapshot, itemsHandle, requested, atomic)
                } else {
                    invokeItems(snapshot, itemsHandle, requested, atomic)
                }
            }
        }
    }

    /**
     * Fluid insert path.
     *
     * Atomic mode: first sim the network capacity via [NetworkStorageHelper.tryInsertFluidAcrossNetwork]
     *, which itself runs sim-first, so source is never drained unless the full amount is
     * known to fit. Draining from the handle's source is the last step, if the network
     * commit diverges from the sim, unwinds push fluid back.
     *
     * Best-effort: drain-then-place, push unused back to source. Fluids are never destroyed
     * on overflow.
     */
    private fun invokeFluid(
        snapshot: NetworkSnapshot,
        itemsHandle: ItemsHandle,
        requested: Long,
        atomic: Boolean
    ): LuaValue {
        val sourceFluid = itemsHandle.fluidSourceStorage()
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)

        if (atomic) {
            // Capacity probe BEFORE touching source: sum simulate across cards.
            val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
            var capacity = 0L
            for (card in storageCards) {
                if (capacity >= requested) break
                val dest = NetworkStorageHelper.getFluidStorage(level, card) ?: continue
                capacity += try {
                    damien.nodeworks.platform.PlatformServices.storage.simulateInsertFluid(
                        dest, itemsHandle.itemId, requested - capacity
                    )
                } catch (_: Exception) { 0L }
            }
            if (capacity < requested) return LuaValue.FALSE

            val drained = damien.nodeworks.platform.PlatformServices.storage.extractFluid(
                sourceFluid, { it == itemsHandle.itemId }, requested
            )
            if (drained < requested) {
                // Source turned out short after the probe passed, refund and bail.
                if (drained > 0L) {
                    damien.nodeworks.platform.PlatformServices.storage.insertFluid(
                        sourceFluid, itemsHandle.itemId, drained
                    )
                }
                return LuaValue.FALSE
            }
            val placed = NetworkStorageHelper.insertFluidAcrossNetwork(
                level, snapshot, itemsHandle.itemId, drained, inventoryCache
            )
            if (placed < drained) {
                // Commit diverged from sim, refund the shortfall to source.
                damien.nodeworks.platform.PlatformServices.storage.insertFluid(
                    sourceFluid, itemsHandle.itemId, drained - placed
                )
                return LuaValue.FALSE
            }
            return LuaValue.TRUE
        }

        // Best-effort: drain then place what fits, return unused to source.
        val drained = damien.nodeworks.platform.PlatformServices.storage.extractFluid(
            sourceFluid, { it == itemsHandle.itemId }, requested
        )
        if (drained <= 0L) return LuaValue.valueOf(0)
        val placed = NetworkStorageHelper.insertFluidAcrossNetwork(
            level, snapshot, itemsHandle.itemId, drained, inventoryCache
        )
        if (placed < drained) {
            damien.nodeworks.platform.PlatformServices.storage.insertFluid(
                sourceFluid, itemsHandle.itemId, drained - placed
            )
        }
        return LuaValue.valueOf(placed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * Item insert path. Delegates to [NetworkStorageHelper] for both modes. The atomic
     * variant rolls back by reverse-moving on shortfall, best-effort returns the count
     * that actually landed.
     */
    private fun invokeItems(
        snapshot: NetworkSnapshot,
        itemsHandle: ItemsHandle,
        requested: Long,
        atomic: Boolean
    ): LuaValue {
        val sourceStorage = itemsHandle.sourceStorage()
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)

        return if (atomic) {
            val ok = NetworkStorageHelper.tryInsertItemsAcrossNetwork(
                level, snapshot, sourceStorage, itemsHandle.filter,
                requested, routeTable, inventoryCache
            )
            LuaValue.valueOf(ok)
        } else {
            val moved = NetworkStorageHelper.insertItems(
                level, snapshot, sourceStorage, itemsHandle.filter,
                requested, routeTable, null, inventoryCache
            )
            LuaValue.valueOf(moved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun injectApi(g: Globals) {
        val snapshot = networkSnapshot!!

        // scheduler object
        g.set("scheduler", scheduler.createLuaTable())

        // network object
        val networkTable = LuaTable()

        // Preset builders (Importer / Stocker) recognise the `network` global as a
        // "pool" source/target via this sentinel. Kept under an internal key so a
        // user-shadowed method on the network table can't collide with it.
        networkTable.set("_isNetworkPool", LuaValue.TRUE)

        // network:get(name) → CardHandle | VariableHandle, or error.
        // Cards win on a name collision so existing scripts don't change behaviour
        // when a variable happens to share an alias with a card, a future "validate
        // unique names across cards + variables" pass on the network would catch
        // collisions at edit time, but for now the lookup order is the contract.
        networkTable.set("get", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                snapshot.findByAlias(alias)?.let { return createCardTable(it, alias) }
                snapshot.findVariable(alias)?.let { return VariableHandle.create(it, level) }
                snapshot.findBreaker(alias)?.let { return BreakerHandle.create(it, snapshot, level) }
                snapshot.findPlacer(alias)?.let { return PlacerHandle.create(it, snapshot, level) }
                throw LuaError("Not found on network: '$alias'")
            }
        })

        // network:getAll(type) → HandleList<T> for cards matching the capability type,
        // or HandleList<VariableHandle*> for `"variable"`. Variables share the type
        // string `"variable"` regardless of declared type (number/string/bool), the
        // HandleList's broadcast methods lock in to the shared `VariableHandle`
        // surface (`set`, `cas`). Callers wanting type-specific atomics on every
        // variable should iterate via `:list()`.
        networkTable.set("getAll", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, typeArg: LuaValue): LuaValue {
                val type = typeArg.checkjstring()
                if (type == "variable") {
                    val members = snapshot.variables.map { VariableHandle.create(it, level) as LuaValue }
                    return createHandleListTable(
                        members,
                        HandleListMethods.methodsForHandleType("VariableHandle"),
                    )
                }
                if (type == "breaker") {
                    val members = snapshot.breakers.map {
                        BreakerHandle.create(it, snapshot, level) as LuaValue
                    }
                    return createHandleListTable(
                        members,
                        HandleListMethods.methodsForCapabilityType("breaker"),
                    )
                }
                if (type == "placer") {
                    val members = snapshot.placers.map {
                        PlacerHandle.create(it, snapshot, level) as LuaValue
                    }
                    return createHandleListTable(
                        members,
                        HandleListMethods.methodsForCapabilityType("placer"),
                    )
                }
                val cards = snapshot.allCards().filter { it.capability.type == type }
                val members = cards.map { createCardTable(it, it.effectiveAlias) as LuaValue }
                return createHandleListTable(
                    members,
                    HandleListMethods.methodsForCapabilityType(type),
                )
            }
        })

        // network:cards(pattern) → HandleList<CardHandle> of every card whose alias
        // matches the glob-style pattern (`*` is the only wildcard char). Different
        // from `network:getAll(type)`: this matches by alias, that matches by
        // capability type. Common case: face-overriding a wildcard set,
        // `network:cards("io_*"):face("bottom")` returns a HandleList where every
        // member is a face-overridden CardHandle, ready to feed into the importer.
        //
        // The HandleList is a snapshot taken at call time. New cards added later
        // won't show up in it, re-call `network:cards` to refresh. For tick-time
        // re-resolution, use the bare-string wildcard form on importer/stocker
        // (`importer:from("io_*")`).
        networkTable.set("cards", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, patternArg: LuaValue): LuaValue {
                val pattern = patternArg.checkjstring()
                val regex = damien.nodeworks.script.preset.wildcardToRegex(pattern)
                val matched = snapshot.allCards()
                    .filter { regex.matchEntire(it.effectiveAlias) != null }
                    .distinctBy { it.effectiveAlias }
                val members = matched.map { createCardTable(it, it.effectiveAlias) as LuaValue }
                // Install broadcast methods only when every match shares one
                // capability type (common case: `io_*` returns all IO cards).
                // Mixed types fall back to no broadcasts so we don't dispatch a
                // method that some members don't support.
                val capTypes = matched.map { it.capability.type }.toSet()
                val broadcasts = if (capTypes.size == 1) {
                    HandleListMethods.methodsForCapabilityType(capTypes.first())
                } else {
                    emptySet()
                }
                return createHandleListTable(members, broadcasts)
            }
        })

        // network:channel(color) → Channel handle scoped to that dye color.
        // Errors on bad color names so a typo surfaces immediately rather than
        // silently iterating an empty group.
        networkTable.set("channel", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, colorArg: LuaValue): LuaValue {
                val name = colorArg.checkjstring()
                val color = net.minecraft.world.item.DyeColor.byName(name, null)
                    ?: throw LuaError("Unknown channel color: '$name'. Use one of the 16 dye color names (white, red, blue, ...).")
                return createChannelTable(snapshot, color)
            }
        })

        // network:channels() → { string } of every channel currently in use on the
        // network (cards + variables). Order is by DyeColor.id ascending so iteration
        // is stable across calls. White is included only when at least one card or
        // variable is actually set to it (which by default is most of them).
        networkTable.set("channels", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                val cardChannels = snapshot.allCards().map { it.channel }
                val varChannels = snapshot.variables.map { it.channel }
                val seen = (cardChannels + varChannels).toSortedSet(compareBy { it.id })
                val result = LuaTable()
                for ((i, color) in seen.withIndex()) {
                    result.set(i + 1, LuaValue.valueOf(color.name.lowercase()))
                }
                return result
            }
        })

        // network:find(filter) → ItemsHandle or nil (scans real storage, aggregated count)
        // Respects kind-qualified filters (`item:*`, `fluid:*`). Bare filters check items first.
        networkTable.set("find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = CardHandle.parseFilterKind(filter)

                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.ITEM) {
                    val itemResult = NetworkStorageHelper.findFirstItemInfoAcrossNetwork(level, snapshot, filter)
                    if (itemResult != null) {
                        val (info, _) = itemResult
                        val totalCount = NetworkStorageHelper.countItems(level, snapshot, filter)
                        val aggregatedInfo = info.copy(count = totalCount)
                        val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                                val storage = NetworkStorageHelper.getStorage(level, card)
                                if (storage != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                        CardHandle.matchesFilter(it, damien.nodeworks.platform.ResourceKind.ITEM, filter)
                                    }
                                    if (has > 0) storage else null
                                } else null
                            }
                        }
                        return ItemsHandle.toLuaTable(ItemsHandle.fromItemInfo(aggregatedInfo, filter, sourceStorage, level))
                    }
                }
                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.FLUID) {
                    val fluidResult = NetworkStorageHelper.findFirstFluidInfoAcrossNetwork(level, snapshot, filter)
                    if (fluidResult != null) {
                        val (info, _) = fluidResult
                        val totalAmount = NetworkStorageHelper.countFluid(level, snapshot, filter)
                        val aggregated = damien.nodeworks.platform.FluidInfo(info.fluidId, info.name, totalAmount)
                        val fluidSource: () -> damien.nodeworks.platform.FluidStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                                val storage = NetworkStorageHelper.getFluidStorage(level, card)
                                if (storage != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countFluid(storage) { it == info.fluidId }
                                    if (has > 0) storage else null
                                } else null
                            }
                        }
                        return ItemsHandle.toLuaTable(ItemsHandle.fromFluidInfo(aggregated, filter, fluidSource, level))
                    }
                }
                return LuaValue.NIL
            }
        })

        // network:findEach(filter) → table of ItemsHandles (scans real storage).
        // Bare filter lists items then fluids, kind-prefixed filter yields only that kind.
        networkTable.set("findEach", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = CardHandle.parseFilterKind(filter)
                val result = LuaTable()
                var idx = 1
                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.ITEM) {
                    val allItems = NetworkStorageHelper.findAllItemInfoAcrossNetwork(level, snapshot, filter)
                    for (pair in allItems) {
                        val (info, _) = pair
                        val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                                val storage = NetworkStorageHelper.getStorage(level, card)
                                if (storage != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) { it == info.itemId }
                                    if (has > 0) storage else null
                                } else null
                            }
                        }
                        val handle = ItemsHandle.fromItemInfo(info, info.itemId, sourceStorage, level)
                        result.set(idx++, ItemsHandle.toLuaTable(handle))
                    }
                }
                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.FLUID) {
                    // Single-pass aggregation, avoids O(N*M) rescans from calling countFluid
                    // per discovered fluid id.
                    val allFluids = NetworkStorageHelper.findAllFluidInfoAcrossNetwork(level, snapshot, filter)
                    for ((info, _) in allFluids) {
                        val fluidSource: () -> damien.nodeworks.platform.FluidStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { c ->
                                val s = NetworkStorageHelper.getFluidStorage(level, c)
                                if (s != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countFluid(s) { it == info.fluidId }
                                    if (has > 0) s else null
                                } else null
                            }
                        }
                        val handle = ItemsHandle.fromFluidInfo(info, "\$fluid:${info.fluidId}", fluidSource, level)
                        result.set(idx++, ItemsHandle.toLuaTable(handle))
                    }
                }
                return result
            }
        })

        // network:count(filter) → number (items + fluids, or kind-filtered)
        networkTable.set("count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val count = NetworkStorageHelper.countResource(level, snapshot, filter)
                return LuaValue.valueOf(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        })

        // network:insert(itemsHandle, count?) → boolean (atomic, either the full count lands
        // in network storage or nothing moves). Mirrors CardHandle:insert for consistency.
        // Use network:tryInsert for "move what fits, leave the rest" semantics.
        networkTable.set("insert", buildNetworkInsertFn(snapshot, atomic = true))

        // network:tryInsert(itemsHandle, count?) → number (best-effort count moved).
        networkTable.set("tryInsert", buildNetworkInsertFn(snapshot, atomic = false))

        // network:craft(identifier, count?) → CraftBuilder with :connect(fn) and :store()
        networkTable.set("craft", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val identifier = args.checkjstring(2)
                val count = if (args.narg() >= 3 && !args.arg(3).isnil()) args.checkint(3) else 1

                CraftingHelper.currentPendingJob = null
                val result = CraftingHelper.craft(identifier, count, level, snapshot, cache = inventoryCache, processingHandlers = processingHandlers.takeIf { it.isNotEmpty() }, callerScheduler = scheduler, traceLog = { msg -> logCallback(msg, false) })

                // Check for async pending job (processing handler or async assembly)
                val pending = CraftingHelper.currentPendingJob
                CraftingHelper.currentPendingJob = null

                if (result == null && pending == null) {
                    CraftingHelper.lastFailReason?.let { logCallback(it, true) }
                    return LuaValue.NIL
                }

                // For async: result is null but pending is set. Build a CraftResult placeholder.
                val craftResult = result ?: run {
                    // Async, we don't know the exact output yet. Use the identifier.
                    val id = net.minecraft.resources.Identifier.tryParse(identifier)
                    val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) else null
                    val name = if (item != null) net.minecraft.world.item.ItemStack(item).hoverName.string else identifier
                    CraftingHelper.CraftResult(identifier, name, count,
                        cpu = snapshot.cpus.firstOrNull()?.let { level.getBlockEntity(it.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity },
                        level = level, snapshot = snapshot, cache = inventoryCache)
                }

                // Helper: release CPU buffer → storage and create ItemsHandle
                fun releaseAndCreateHandle(): LuaTable {
                    CraftingHelper.releaseCraftResult(craftResult)
                    val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
                    val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                        storageCards.firstNotNullOfOrNull { card ->
                            val storage = NetworkStorageHelper.getStorage(level, card)
                            if (storage != null) {
                                val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                    CardHandle.matchesFilter(it, craftResult.outputItemId)
                                }
                                if (has > 0) storage else null
                            } else null
                        }
                    }
                    return ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                        itemId = craftResult.outputItemId,
                        itemName = craftResult.outputName,
                        count = craftResult.count,
                        sourceStorage = sourceStorage,
                        level = level
                    ))
                }

                // Build the CraftBuilder table
                val builder = LuaTable()

                // :connect(fn), release to storage, call fn with ItemsHandle
                builder.set("connect", object : TwoArgFunction() {
                    override fun call(selfArg: LuaValue, callbackArg: LuaValue): LuaValue {
                        val callback = callbackArg.checkfunction()

                        if (pending == null || pending.isComplete) {
                            // Instant, release and call now
                            val handle = releaseAndCreateHandle()
                            try { callback.call(handle) }
                            catch (e: LuaError) { logCallback("craft callback error: ${e.message}", true) }
                        } else {
                            // Async, wait for pending job, then release and call
                            pending.onCompleteCallback = { success ->
                                if (success) {
                                    val handle = releaseAndCreateHandle()
                                    try { callback.call(handle) }
                                    catch (e: LuaError) { logCallback("craft callback error: ${e.message}", true) }
                                } else {
                                    logCallback("Craft timed out for '$identifier'", true)
                                    // Clean up CPU buffer
                                    CraftingHelper.releaseCraftResult(craftResult)
                                }
                            }
                        }
                        return LuaValue.NIL
                    }
                })

                // :store(), release to storage, no callback
                builder.set("store", object : OneArgFunction() {
                    override fun call(selfArg: LuaValue): LuaValue {
                        if (pending == null || pending.isComplete) {
                            CraftingHelper.releaseCraftResult(craftResult)
                        } else {
                            pending.onCompleteCallback = { _ ->
                                CraftingHelper.releaseCraftResult(craftResult)
                            }
                        }
                        return LuaValue.NIL
                    }
                })

                return builder
            }
        })

        // network:route(alias, predicate), register a declarative storage route
        // Items where predicate(itemsHandle) returns true go to that storage
        routeTable = RouteTable(level, snapshot)
        networkTable.set("route", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue, predicateArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                val predicate = predicateArg.checkfunction()
                routeTable?.addRoute(alias, predicate)
                return LuaValue.NIL
            }
        })


        // network:shapeless(item1, count1, item2?, count2?, ...) → ItemsHandle or nil
        // Crafts using vanilla shapeless recipes. Inputs are item/count pairs.
        networkTable.set("shapeless", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                // Parse item/count pairs from varargs (self is arg1)
                val ingredients = mutableMapOf<String, Int>()
                var i = 2
                while (i <= args.narg()) {
                    val itemId = args.checkjstring(i)
                    val count = if (i + 1 <= args.narg() && !args.arg(i + 1).isnil() && args.arg(i + 1).isnumber()) {
                        i++
                        args.checkint(i)
                    } else {
                        1
                    }
                    ingredients[itemId] = (ingredients[itemId] ?: 0) + count
                    i++
                }

                if (ingredients.isEmpty()) throw LuaError("shapeless requires at least one item")

                val result = ShapelessCraftHelper.craft(ingredients, level, snapshot, cache = inventoryCache)
                    ?: return LuaValue.NIL

                val storageCards2 = NetworkStorageHelper.getStorageCards(snapshot)
                val sourceStorage2: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                    storageCards2.firstNotNullOfOrNull { card ->
                        val storage = NetworkStorageHelper.getStorage(level, card)
                        if (storage != null) {
                            val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                CardHandle.matchesFilter(it, result.outputItemId)
                            }
                            if (has > 0) storage else null
                        } else null
                    }
                }

                return ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                    itemId = result.outputItemId,
                    itemName = result.outputName,
                    count = result.count,
                    sourceStorage = sourceStorage2,
                    level = level
                ))
            }
        })

        // network:handle(cardName, handlerFn), register a processing handler
        // cardName matches the name set on a Processing Set in Processing Storage.
        // The handler function receives input items as arguments and should return
        // the result ItemsHandle from the processing machine's output.
        networkTable.set("handle", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue, handlerArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val handler = handlerArg.checkfunction()
                processingHandlers[name] = handler
                return LuaValue.NIL
            }
        })

        // (network:var was removed, variables are now first-class members of the
        // network and resolved through `network:get(name)` alongside cards.)

        // network:debug(), print full network summary
        networkTable.set("debug", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                val sb = StringBuilder()
                sb.appendLine("=== Network Debug ===")
                sb.appendLine("Controller: ${snapshot.controller?.pos ?: "none"}")
                sb.appendLine("Nodes: ${snapshot.nodes.size}")
                for (node in snapshot.nodes) {
                    val cardCount = node.sides.values.sumOf { it.size }
                    sb.appendLine("  Node ${node.pos}: $cardCount cards")
                    for ((dir, cards) in node.sides) {
                        for (card in cards) {
                            sb.appendLine("    ${dir.name}: ${card.effectiveAlias} (${card.capability.type})")
                        }
                    }
                }
                sb.appendLine("Terminals: ${snapshot.terminalPositions.size}")
                for (pos in snapshot.terminalPositions) sb.appendLine("  $pos")
                sb.appendLine("CPUs: ${snapshot.cpus.size}")
                for (cpu in snapshot.cpus) sb.appendLine("  ${cpu.pos}: ${cpu.bufferUsed}/${cpu.bufferCapacity} ${if (cpu.isBusy) "BUSY" else "idle"}")
                sb.appendLine("Crafters (Instruction Sets): ${snapshot.crafters.size}")
                for (crafter in snapshot.crafters) {
                    sb.appendLine("  ${crafter.pos}: ${crafter.instructionSets.size} recipes")
                    for (recipe in crafter.instructionSets) sb.appendLine("    ${recipe.alias ?: recipe.outputItemId}")
                }
                sb.appendLine("Processing APIs: ${snapshot.processingApis.size}")
                for (api in snapshot.processingApis) {
                    val remote = if (api.remoteTerminalPositions != null) " (remote)" else ""
                    sb.appendLine("  ${api.pos}$remote: ${api.apis.size} cards")
                    for (card in api.apis) {
                        val inputs = card.inputs.joinToString(", ") { "${it.first} x${it.second}" }
                        val outputs = card.outputs.joinToString(", ") { "${it.first} x${it.second}" }
                        sb.appendLine("    [${card.name}] $inputs -> $outputs")
                    }
                }
                sb.appendLine("Variables: ${snapshot.variables.size}")
                for (v in snapshot.variables) sb.appendLine("  ${v.name} (${v.type})")
                logCallback(sb.toString().trimEnd(), false)
                return LuaValue.NIL
            }
        })

        g.set("network", networkTable)

        // importer / stocker presets, declarative builders that compile down to
        // scheduler tasks. See damien/nodeworks/script/preset/*.kt.
        g.set("importer", damien.nodeworks.script.preset.Importer.createGlobal(this))
        g.set("stocker", damien.nodeworks.script.preset.Stocker.createGlobal(this))

        // clock() -> seconds since script started (as a decimal)
        val startTime = System.currentTimeMillis()
        g.set("clock", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                return LuaValue.valueOf(elapsed)
            }
        })

        // print(message)
        g.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val parts = mutableListOf<String>()
                for (i in 1..args.narg()) {
                    parts.add(formatValue(args.arg(i)))
                }
                logCallback(parts.joinToString("  "), false)
                return LuaValue.NONE
            }
        })
    }

    private fun formatValue(value: LuaValue, depth: Int = 0): String {
        if (depth > 3) return "{...}"
        return when {
            value.istable() -> {
                val table = value.checktable()
                val entries = mutableListOf<String>()
                var isArray = true
                var arrayIdx = 1

                // First pass: check if it's a pure array
                var key = LuaValue.NIL
                while (true) {
                    val n = table.next(key)
                    if (n.arg1().isnil()) break
                    key = n.arg1()
                    if (!key.isint() || key.toint() != arrayIdx) {
                        isArray = false
                        break
                    }
                    arrayIdx++
                }

                // Second pass: format entries
                key = LuaValue.NIL
                while (true) {
                    val n = table.next(key)
                    if (n.arg1().isnil()) break
                    key = n.arg1()
                    val v = n.arg(2)
                    if (entries.size >= 20) { entries.add("..."); break }
                    if (isArray) {
                        entries.add(formatValue(v, depth + 1))
                    } else {
                        val keyStr = if (key.isstring()) key.tojstring() else formatValue(key, depth + 1)
                        entries.add("$keyStr = ${formatValue(v, depth + 1)}")
                    }
                }

                if (entries.isEmpty()) "{}" else "{ ${entries.joinToString(", ")} }"
            }
            value.isstring() -> "\"${value.tojstring()}\""
            else -> value.tojstring()
        }
    }
}
