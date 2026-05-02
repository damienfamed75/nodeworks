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
 * (card, scheduler, print) and gates each Lua entry point with a wall-clock
 * soft-abort budget via [LuaExecGate].
 *
 * [terminalPos] is the position of the [damien.nodeworks.block.entity.TerminalBlockEntity]
 * that owns this engine, separate from [networkEntryNode] because a terminal can
 * connect to the network either directly (laser link, entry == terminal) or via
 * an adjacent NodeBlockEntity (entry != terminal). The engine needs both: entry
 * to discover the network, terminalPos to mark the BE on top-level timeout
 * (clearing autoRun so a `while true do end` script doesn't re-fire on world load).
 */
class ScriptEngine(
    internal val level: ServerLevel,
    private val networkEntryNode: BlockPos,
    private val terminalPos: BlockPos,
    rawLogCallback: (String, Boolean) -> Unit, // (message, isError), raw sink. See [logCallback].
) {

    /** Rate-limited wrapper around [rawLogCallback] for error messages. Routed
     *  through the per-network [NetworkBudget] so multiple terminals on one
     *  network share the error-log pool, the same way they share the print pool.
     *  Player-bound chat output is the actual shared resource (Netty's outbound
     *  buffer to nearby players), so binding the cap to the terminal-source
     *  granularity rather than the network granularity would let a player
     *  multiply the cap by spreading bad scripts across N terminals. */
    private val logCallback: (String, Boolean) -> Unit = { msg, isError ->
        if (isError) {
            val tick = PlatformServices.modState.tickCount
            val budget = NetworkRateLimits.forNetwork(currentSnapshot()?.controller?.networkId)
            if (budget.tryConsumeErrorLog(tick)) {
                rawLogCallback(msg, true)
            } else if (budget.warnOnce(NetworkBudget.WARN_ERROR_LOG)) {
                rawLogCallback("[error log rate-limited this tick on this network, further errors dropped]", true)
            }
        } else {
            rawLogCallback(msg, false)
        }
    }

    /** Per-engine execution gate. Created before [Globals] so [installOn] can wire
     *  the debug hook on engine startup. The gate reads from [ServerPolicy.current]
     *  on each gated entry, so a `/reload` of `serverconfig/nodeworks-server.toml`
     *  takes effect on the next tick into Lua, no engine restart required. */
    internal val gate: LuaExecGate = LuaExecGate()

    /** Accumulated wall-clock time (nanos) this engine has spent executing Lua
     *  across all ticks. The cross-engine scheduler in `NeoForgeTerminalPackets`
     *  uses this CFS-style: engines with lower vruntime get scheduled first each
     *  tick, so a heavy engine doesn't starve well-behaved neighbours when the
     *  global tick budget is tight. Reset to 0 on engine restart.
     *
     *  Public (rather than internal) because Kotlin `internal` is module-scoped
     *  and the cross-engine scheduler lives in the `:neoforge` module. */
    @Volatile
    var vruntimeNs: Long = 0L

    /** Wall-clock cost of the most recent tick this engine actually ran
     *  (nanos). Set by [NeoForgeTerminalPackets.tickAll] via [recordTickCost].
     *  Surfaces in `/nodeworks terminal info` so admins can spot a single
     *  heavy tick that the [vruntimeNs] running total would smear out. */
    @Volatile
    var lastTickCostNs: Long = 0L
        private set

    /** Ring buffer of per-tick wall-clock costs over the last second (20 slots
     *  at 20 TPS). Indexed by `tickCount % 20`, the per-tick scheduler zeros
     *  this engine's slot at the start of each tick via [resetTickCostSlot]
     *  before deciding whether to dispatch us, so engines deferred by global
     *  budget pressure contribute 0 to the sum rather than carrying the
     *  previous round trip's value. The sum across all 20 slots feeds the
     *  "% local tick budget" column shown by `/nodeworks terminal list`. */
    private val recentTickCostNs = LongArray(20)

    /** Zero this engine's ring-buffer slot for [tickCount] before the
     *  cross-engine scheduler decides whether to run us. Skipped engines stay
     *  at 0 for that slot, so the moving-average view stays honest about how
     *  much wall-clock this engine actually consumed. */
    fun resetTickCostSlot(tickCount: Long) {
        recentTickCostNs[(tickCount % 20).toInt()] = 0L
    }

    /** Record the wall-clock cost of this tick's [engine.tick] call. Called
     *  by the cross-engine scheduler after our slice runs. Updates both the
     *  ring buffer and the [lastTickCostNs] sentinel. */
    fun recordTickCost(tickCount: Long, costNs: Long) {
        recentTickCostNs[(tickCount % 20).toInt()] = costNs
        lastTickCostNs = costNs
    }

    /** Sum of wall-clock cost across the last 20 ticks (nanos). One-second
     *  rolling cost on default 20 TPS. Cheap, just a 20-element sum. */
    fun recentTickCostSumNs(): Long {
        var sum = 0L
        for (v in recentTickCostNs) sum += v
        return sum
    }
    private var globals: Globals? = null
    private var networkSnapshot: NetworkSnapshot? = null
    val scheduler = SchedulerImpl(
        onTaskError = { errorMsg -> logCallback(errorMsg, true) },
        runCallback = { label, body -> runGatedCallback(label, body) },
        assertCanRegister = { size, kind -> assertCallbackCap(size, kind) },
    )

    /** Preset builders (Importer, Stocker) registered by the `importer` / `stocker`
     *  factory globals. Each preset is stopped in [stop] before the scheduler is
     *  cleared so per-preset state can unwind cleanly. */
    internal val presets = mutableListOf<damien.nodeworks.script.preset.PresetBuilder<*>>()

    /** Register a preset builder so it's stopped on script teardown. Called by
     *  each factory method the instant a builder is created (not when the user
     *  calls `:run()`) so dangling builders that never start still get cleaned up.
     *
     *  Capped at [ServerSafetySettings.maxCallbacksPerKind] like the other
     *  registries. Without this, a `scheduler:tick(function() importer:...:start() end)`
     *  loop creates a new preset every tick and grows memory linearly; the
     *  cap stops that pattern at ~256 presets and locks the terminal. */
    internal fun registerPreset(p: damien.nodeworks.script.preset.PresetBuilder<*>) {
        assertCallbackCap(presets.size, "preset")
        p.registryIndex = presets.size
        presets.add(p)
    }

    /** Current network snapshot. Rebuilt by [start] and refreshed periodically.
     *  Presets compare identity (`!==`) to decide when to re-resolve card names. */
    internal fun currentSnapshot(): NetworkSnapshot? = networkSnapshot

    /** Discard the cached snapshot and rebuild it from a live walk of the
     *  network. Called by [damien.nodeworks.script.preset.PresetBuilder.safeTick]
     *  so long-running presets pick up Storage Card filter changes (and any
     *  other card-NBT mutations) the player makes mid-script. Cheap on small
     *  networks, ~O(n) on connectable count, fine to run once per preset
     *  tick (presets default to 20-tick intervals = once per second). */
    internal fun refreshSnapshot() {
        networkSnapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
    }

    /** Log an error through the terminal's log callback. Presets use this when
     *  a tick throws so the player sees the error without the preset unscheduling. */
    internal fun logError(msg: String) = logCallback(msg, true)

    /** Precomputed route table set by network:route(). */
    var routeTable: RouteTable? = null
        private set

    /** Cached inventory index across all network storage. */
    var inventoryCache: NetworkInventoryCache? = null
        private set

    // Rate-limited ops (print, error-log, place, redstone:set, var:set,
    // item-move calls, items-moved budget) all live on [NetworkRateLimits]
    // keyed by the network UUID. Multiple terminals on the same network share
    // one pool so a player can't multiply caps by spreading bad scripts across
    // terminals. The one per-engine cap left is [maxCallbacksPerKind] which
    // gates registry size at registration sites directly, no limiter needed.

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
        // Optional std libs gated by [ServerSafetySettings.enabledModules]. Admins
        // strip libs by removing entries from the config list; defaults load all
        // four. Base + package always load (above) since scripts depend on print /
        // pairs / require to function at all.
        val modules = ServerPolicy.current.enabledModules
        if ("bit32" in modules) g.load(Bit32Lib())
        if ("table" in modules) g.load(TableLib())
        if ("string" in modules) g.load(StringLib())
        if ("math" in modules) g.load(JseMathLib())

        // Install the Lua compiler
        LuaC.install(g)

        // Install the wall-clock soft-abort hook. Must come AFTER the libs are
        // loaded (so PackageLib's initialisation isn't itself gated, which would
        // otherwise burn the budget before the script even starts).
        gate.installOn(g)

        // Remove dangerous globals
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("os", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)
        // PackageLib leaves a `package` table reachable that exposes `loadlib`,
        // `searchpath`, and the `searchers` chain, a sandbox escape hatch. We
        // need PackageLib itself loaded so our custom `require` can register
        // modules in `package.loaded`, but the script-visible `package` global
        // gets nil'd here so scripts can't reach loadlib/searchers.
        g.set("package", LuaValue.NIL)

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

        // Compile and run the main script (top-level code: variable setup, scheduler registrations).
        // The top-level body runs under [LuaExecGate.runTopLevel] so a `while true do end`
        // is killed after [ServerSafetySettings.topLevelSoftAbortMs], and on timeout we
        // clear the BE's `autoRun` flag so the bad script doesn't re-fire on world load.
        return try {
            val chunk = g.load(wrapForLoopIterators(stripTypeAnnotations(mainScript)), "main")
            logCallback("Script started.", false)
            gate.runTopLevel("main") { chunk.call() }
            // A clean run wipes any persisted timeout error from a prior failed start.
            (level.getBlockEntity(terminalPos) as? damien.nodeworks.block.entity.TerminalBlockEntity)?.clearLastError()
            true
        } catch (e: LuaError) {
            when {
                e is LuaExecGate.FatalScriptError -> {
                    // Hard limit hit during top-level setup (e.g. registering more
                    // callbacks than maxCallbacksPerKind allows). Treat the same
                    // as a timeout: log + lock the terminal so the player has to
                    // edit before retrying. Use [cleanReason] so the user-facing
                    // log skips LuaJ's source:line prefix + stack traceback.
                    handleFatalScriptFault(e.cleanReason)
                    return false
                }
                gate.isTimeoutError(e) -> {
                    // Top-level wall-clock soft-abort: persist the reason and clear
                    // autoRun so a `while true do end` at chunk level doesn't pin
                    // the server tick on every world load.
                    logCallback("Script took too long to run and was stopped.", true)
                    val terminal = level.getBlockEntity(terminalPos) as? damien.nodeworks.block.entity.TerminalBlockEntity
                    terminal?.markTimedOut("Top-level script timed out.")
                    if (terminal?.autoRun == false) {
                        logCallback("Auto-run disabled until you edit the script.", true)
                    }
                }
                else -> {
                    // Regular script-level error. Surfaces through the player-facing
                    // terminal log and the Diagnostic Tool's error buffer. Strips the
                    // LuaJ stack traceback for a single-line readable error.
                    logCallback("Error: ${gate.stripLuaTraceback(e.message)}", true)
                }
            }
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

    /** Run [body] under the per-callback wall-clock budget AND mark the terminal's
     *  autoRun flag off if the callback times out. Used by the scheduler and the
     *  redstone/observer pollers so any timeout (not just top-level) clears
     *  autoRun, matching the user-facing rule "any timeout means the script is
     *  broken, don't auto-restart it." Eviction of the offending callback from
     *  its registry is still the caller's responsibility (it's registry-specific).
     */
    internal fun runGatedCallback(label: String, body: () -> Unit): LuaExecGate.Outcome {
        val outcome = gate.runCallback(label, body)
        when (outcome) {
            LuaExecGate.Outcome.TimedOut -> markCallbackTimedOut(label)
            is LuaExecGate.Outcome.Fatal -> handleFatalScriptFault(outcome.message)
            else -> { /* Ok / Errored already logged by gate via the engine's logCallback */ }
        }
        return outcome
    }

    /** Handle a fatal script-side fault: stop the engine and lock the terminal so
     *  the player must edit the script before it can run again. Used for errors
     *  that would otherwise re-fire every tick (e.g. callback registry full,
     *  evicting one offending callback doesn't help when peer callbacks driving
     *  the registration pressure also keep firing). Logged once via the engine's
     *  rate-limited error path. */
    private fun handleFatalScriptFault(reason: String) {
        logCallback(reason, true)
        val terminal = level.getBlockEntity(terminalPos) as? damien.nodeworks.block.entity.TerminalBlockEntity
        val wasAutoRun = terminal?.autoRun == true
        terminal?.markTimedOut(reason)
        if (wasAutoRun) logCallback("Auto-run disabled until you edit the script.", true)
        stop()
    }

    /** Throw a [LuaExecGate.FatalScriptError] if registering one more callback
     *  of [kind] would exceed the per-engine cap. Catches the recursive-self-
     *  registration pattern (a callback that adds another callback every time
     *  it fires) before the registry bloats. The [LuaExecGate.FatalScriptError]
     *  type is what classifies this as a fatal stop in [LuaExecGate.Outcome]:
     *  the engine halts and the terminal locks rather than retrying every
     *  tick (which would just re-throw the same error forever). A cap of 0
     *  in the config means "unlimited". */
    private fun assertCallbackCap(currentSize: Int, kind: String) {
        val cap = ServerPolicy.current.maxCallbacksPerKind
        if (cap > 0 && currentSize >= cap) {
            throw LuaExecGate.FatalScriptError(
                "Too many $kind callbacks registered ($cap). Edit the script to reduce registrations."
            )
        }
    }

    /** Wrap [fn] with a per-network, per-tick rate limiter via [NetworkRateLimits].
     *  All engines touching the same network share one pool, so a player running
     *  N terminals on the same network can't multiply the cap N times by spreading
     *  identical scripts. Used for `network:insert`/`tryInsert`, `card:insert`,
     *  `placer:place`, `redstone:set`, and `var:set`/`cas`.
     *
     *  [consume] picks the right per-op counter on the budget. [warnOp] is a
     *  bitmask key into [NetworkBudget.warnOnce] so the rate-limited warning
     *  fires once per op per network per tick rather than spamming.
     *
     *  Pass-through when [fn] is nil so disabled bindings stay disabled. */
    private fun networkRateLimited(
        label: String,
        consume: (NetworkBudget, Long) -> Boolean,
        warnOp: Int,
        fn: LuaValue,
        onLimit: Varargs = LuaValue.NIL,
    ): LuaValue {
        if (!fn.isfunction()) return fn
        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val networkId = currentSnapshot()?.controller?.networkId
                val budget = NetworkRateLimits.forNetwork(networkId)
                val tick = PlatformServices.modState.tickCount
                if (!consume(budget, tick)) {
                    if (budget.warnOnce(warnOp)) {
                        logCallback("[$label rate-limited this tick on this network, further calls dropped]", true)
                    }
                    return onLimit
                }
                return fn.invoke(args)
            }
        }
    }

    /** Persist a callback-timeout reason to the terminal BE and clear autoRun.
     *  Reads autoRun before clearing so we only emit the user-facing log line if
     *  there was actually anything to disable. */
    private fun markCallbackTimedOut(label: String) {
        val terminal = level.getBlockEntity(terminalPos) as? damien.nodeworks.block.entity.TerminalBlockEntity ?: return
        val wasAutoRun = terminal.autoRun
        terminal.markTimedOut("Callback '$label' timed out.")
        if (wasAutoRun) {
            logCallback("Auto-run disabled until you edit the script.", true)
        }
    }

    /** Whether this engine should stay alive, has scheduler tasks, handlers, or routing. */
    fun hasWork(): Boolean = scheduler.hasActiveTasks()
        || processingHandlers.isNotEmpty()
        || redstoneCallbacks.isNotEmpty()
        || observerCallbacks.isNotEmpty()
        || routeTable?.hasRoutes() == true


    /** Called each server tick. Runs scheduler callbacks and pollers, deferring
     *  remaining work to the next tick when [tickDeadlineNs] is reached.
     *
     *  [tickDeadlineNs] is an absolute [System.nanoTime] value computed by the
     *  cross-engine scheduler ([NeoForgeTerminalPackets.tickAll]). Between each
     *  callback we check if `now >= tickDeadlineNs` and if so, return early.
     *  The un-run callbacks stay in their registries and fire next tick. The
     *  budget only applies *between* callbacks, not within them; a single
     *  callback that runs longer than the local budget still completes
     *  (bounded by the per-callback soft-abort cumulatively). */
    fun tick(tickCount: Long, tickDeadlineNs: Long = Long.MAX_VALUE) {
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
            scheduler.tick(tickCount, tickDeadlineNs)
            if (System.nanoTime() < tickDeadlineNs) pollRedstoneCallbacks(tickDeadlineNs)
            if (System.nanoTime() < tickDeadlineNs) pollObserverCallbacks(tickDeadlineNs)
        } catch (e: LuaError) {
            logCallback("Runtime error: ${gate.stripLuaTraceback(e.message)}", true)
            stop()
        } catch (e: Exception) {
            logCallback("Runtime error: ${e.message}", true)
            stop()
        }
    }

    private fun pollRedstoneCallbacks(tickDeadlineNs: Long = Long.MAX_VALUE) {
        if (redstoneCallbacks.isEmpty()) return
        // Iterate over a snapshot of entries so eviction-on-timeout (mutating the
        // map mid-iteration) doesn't ConcurrentModificationException.
        val toEvict = mutableListOf<String>()
        for ((alias, cb) in redstoneCallbacks.toList()) {
            // Per-tick budget: if we've exhausted the engine's slice, leave any
            // remaining handlers unprocessed. They keep `lastStrength` at its
            // previous value so the change is re-detected next tick. No events
            // get silently swallowed.
            if (System.nanoTime() >= tickDeadlineNs) break
            val currentStrength = level.getSignal(cb.capability.adjacentPos, cb.capability.nodeSide)
            if (currentStrength == cb.lastStrength) continue
            cb.lastStrength = currentStrength
            val outcome = runGatedCallback("redstone-handler:$alias") {
                cb.callback.call(LuaValue.valueOf(currentStrength))
            }
            when (outcome) {
                LuaExecGate.Outcome.TimedOut -> {
                    logCallback("Redstone handler on '$alias' took too long to run, handler removed.", true)
                    toEvict += alias
                }
                is LuaExecGate.Outcome.Errored -> {
                    logCallback("Redstone handler on '$alias': ${outcome.message}", true)
                }
                is LuaExecGate.Outcome.Fatal -> return  // engine already stopped
                LuaExecGate.Outcome.Ok -> { /* no-op */ }
            }
        }
        for (alias in toEvict) redstoneCallbacks.remove(alias)
    }

    /** Polled once per server tick. Skips any observer whose target chunk isn't loaded
     *  so a far-away farm doesn't pay chunk-load cost from the polling loop alone, when
     *  the chunk reloads the next poll resyncs `lastState` silently and won't fire a
     *  spurious onChange for the load delta. Handler exceptions are caught and routed
     *  through the log so one bad observer can't kill the whole tick. */
    private fun pollObserverCallbacks(tickDeadlineNs: Long = Long.MAX_VALUE) {
        if (observerCallbacks.isEmpty()) return
        val toEvict = mutableListOf<String>()
        for ((alias, cb) in observerCallbacks.toList()) {
            if (System.nanoTime() >= tickDeadlineNs) break
            val pos = cb.capability.adjacentPos
            if (!level.isLoaded(pos)) continue
            val current = level.getBlockState(pos)
            if (current == cb.lastState) continue
            cb.lastState = current
            val outcome = runGatedCallback("observer-handler:$alias") {
                cb.callback.call(
                    LuaValue.valueOf(blockIdOf(current)),
                    blockStateToLua(current)
                )
            }
            when (outcome) {
                LuaExecGate.Outcome.TimedOut -> {
                    logCallback("Observer handler on '$alias' took too long to run, handler removed.", true)
                    toEvict += alias
                }
                is LuaExecGate.Outcome.Errored -> {
                    logCallback("Observer handler on '$alias': ${outcome.message}", true)
                }
                is LuaExecGate.Outcome.Fatal -> return  // engine already stopped
                LuaExecGate.Outcome.Ok -> { /* no-op */ }
            }
        }
        for (alias in toEvict) observerCallbacks.remove(alias)
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

    /** Build a Lua table for a placer device with `:place(...)` rate-limited via
     *  [placementLimiter]. Centralised here because [PlacerHandle.create] has
     *  five call sites and we want consistent rate-limiting across all of them. */
    private fun createPlacerTable(
        snapshot: damien.nodeworks.network.PlacerSnapshot,
        networkSnapshot: damien.nodeworks.network.NetworkSnapshot,
    ): LuaTable {
        val table = PlacerHandle.create(snapshot, networkSnapshot, level)
        val origPlace = table.get("place")
        if (origPlace.isfunction()) {
            table.set("place", networkRateLimited(
                "placer:place",
                consume = { b, tick -> b.tryConsumePlacement(tick) },
                warnOp = NetworkBudget.WARN_PLACEMENT,
                origPlace,
                onLimit = LuaValue.FALSE,
            ))
        }
        return table
    }

    /** Build a Lua table for a variable card with `:set` and `:cas` rate-limited via
     *  [variableWriteLimiter]. Each write fires `setChanged` + `sendBlockUpdated`,
     *  which generates client packets, bounded here so a tight loop can't flood. */
    private fun createVariableTable(
        snapshot: damien.nodeworks.network.VariableSnapshot,
    ): LuaTable {
        val table = VariableHandle.create(snapshot, level)
        val origSet = table.get("set")
        if (origSet.isfunction()) {
            table.set("set", networkRateLimited(
                "var:set",
                consume = { b, tick -> b.tryConsumeVariableWrite(tick) },
                warnOp = NetworkBudget.WARN_VARIABLE_WRITE,
                origSet,
            ))
        }
        val origCas = table.get("cas")
        if (origCas.isfunction()) {
            table.set("cas", networkRateLimited(
                "var:cas",
                consume = { b, tick -> b.tryConsumeVariableWrite(tick) },
                warnOp = NetworkBudget.WARN_VARIABLE_WRITE,
                origCas,
                onLimit = LuaValue.FALSE,
            ))
        }
        return table
    }

    private fun createCardTable(card: damien.nodeworks.network.CardSnapshot, alias: String): LuaTable {
        val table = CardHandle.create(card, level, currentSnapshot()?.controller?.networkId)
        // Per-tick call cap is enforced inside [CardHandle.buildInsertFn] so all
        // insert paths share the budget uniformly, including handles returned from
        // `:face(...)` / `:slots(...)` (which build a fresh table that wouldn't
        // pass through a post-hoc wrapper here).
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
            table.setGuarded("RedstoneCard", "powered", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue {
                    val strength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    return LuaValue.valueOf(strength > 0)
                }
            })

            // strength() → number 0-15
            table.setGuarded("RedstoneCard", "strength", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue {
                    val strength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    return LuaValue.valueOf(strength)
                }
            })

            // set(boolean | number), emit redstone signal
            table.setGuarded("RedstoneCard", "set", networkRateLimited(
                "redstone:set",
                consume = { b, tick -> b.tryConsumeRedstoneWrite(tick) },
                warnOp = NetworkBudget.WARN_REDSTONE_WRITE,
                object : TwoArgFunction() {
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
                }))

            // onChange(function(strength: number)), register callback for signal changes
            table.setGuarded("RedstoneCard", "onChange", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, fnArg: LuaValue): LuaValue {
                    val fn = fnArg.checkfunction()
                    // Only count when adding a new alias, replacing an existing
                    // handler doesn't grow the registry. Without this check a
                    // script could re-register the same alias forever and never
                    // hit the cap, but also: a script that replaces handlers in
                    // place is doing the right thing and shouldn't trip a cap.
                    if (alias !in redstoneCallbacks) assertCallbackCap(redstoneCallbacks.size, "redstone-handler")
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
            table.setGuarded("ObserverCard", "block", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue =
                    LuaValue.valueOf(blockIdOf(level.getBlockState(cap.adjacentPos)))
            })

            // state() → { [string]: any }, properties of the watched block.
            table.setGuarded("ObserverCard", "state", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue =
                    blockStateToLua(level.getBlockState(cap.adjacentPos))
            })

            // onChange(function(block: string, state: table))
            // Replaces any prior handler bound to the same alias. `lastState` seeds with the
            // current block so the very first poll after registration won't fire a phantom
            // change event for "transition from null to whatever's already there."
            table.setGuarded("ObserverCard", "onChange", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, fnArg: LuaValue): LuaValue {
                    val fn = fnArg.checkfunction()
                    if (alias !in observerCallbacks) assertCallbackCap(observerCallbacks.size, "observer-handler")
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
                    return createVariableTable(v)
                }
                if (type == "breaker") {
                    val b = snapshot.breakers.firstOrNull { it.channel == color } ?: return LuaValue.NIL
                    return BreakerHandle.create(b, snapshot, level)
                }
                if (type == "placer") {
                    val p = snapshot.placers.firstOrNull { it.channel == color } ?: return LuaValue.NIL
                    return createPlacerTable(p, snapshot)
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
                        members.add(createVariableTable(v))
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
                        members.add(createPlacerTable(p, snapshot))
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
                if (v != null) return createVariableTable(v)
                val b = snapshot.breakers.firstOrNull { it.channel == color && it.effectiveAlias == alias }
                if (b != null) return BreakerHandle.create(b, snapshot, level)
                val p = snapshot.placers.firstOrNull { it.channel == color && it.effectiveAlias == alias }
                if (p != null) return createPlacerTable(p, snapshot)
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
        // Buffer-backed handle (e.g. the one passed to a `:craft():connect(...)` callback):
        // drain from the CPU buffer into network storage stack-by-stack instead of going
        // through `sourceStorage()`, which is null for buffer-only handles.
        val bufSrc = itemsHandle.bufferSource
        if (bufSrc != null) {
            return invokeItemsFromBuffer(snapshot, bufSrc, requested, atomic)
        }
        val sourceStorage = itemsHandle.sourceStorage()
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)

        // Per-network items-moved budget: clamp the request to whatever's left
        // in the budget for this tick. Atomic short-circuits to false when the
        // budget can't satisfy the full requested count, best-effort moves only
        // the clamped amount and reports back the actual moved count. Charges
        // the budget after the move based on what really landed (so storage-full
        // shortfalls don't burn budget on items that didn't actually move).
        val tick = PlatformServices.modState.tickCount
        val budget = NetworkRateLimits.forNetwork(snapshot.controller?.networkId)
        val available = budget.availableItems(tick)
        if (available < requested) {
            if (budget.warnOnce(NetworkBudget.WARN_ITEMS_MOVED)) {
                logCallback("[items moved rate-limited this tick on this network]", true)
            }
            if (atomic) return LuaValue.FALSE
            if (available <= 0L) return LuaValue.valueOf(0)
        }
        val clamped = minOf(requested, available)

        return if (atomic) {
            val ok = NetworkStorageHelper.tryInsertItemsAcrossNetwork(
                level, snapshot, sourceStorage, itemsHandle.filter,
                clamped, routeTable, inventoryCache
            )
            if (ok) budget.noteItemsMoved(tick, clamped)
            LuaValue.valueOf(ok)
        } else {
            val moved = NetworkStorageHelper.insertItems(
                level, snapshot, sourceStorage, itemsHandle.filter,
                clamped, routeTable, null, inventoryCache
            )
            budget.noteItemsMoved(tick, moved)
            LuaValue.valueOf(moved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    /** Drain [requested] items from a CPU buffer into network storage. Atomic mode
     *  refuses partial moves, returning everything to the buffer if the network
     *  can't take the full amount. Best-effort moves what fits and pushes any
     *  shortfall back. Mirrors [CardHandle]'s `moveFromBuffer` but the destination
     *  is the network pool instead of a single card's adjacent storage. */
    private fun invokeItemsFromBuffer(
        snapshot: NetworkSnapshot,
        bufSrc: damien.nodeworks.script.BufferSource,
        requested: Long,
        atomic: Boolean
    ): LuaValue {
        val id = net.minecraft.resources.Identifier.tryParse(bufSrc.itemId)
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id)
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
        val maxStack = item.getDefaultMaxStackSize().toLong()

        // Same per-network budget enforcement as [invokeItems]: clamp the
        // request, atomic short-circuits when budget can't fit the full count,
        // best-effort clamps and charges only what actually moved.
        val tick = PlatformServices.modState.tickCount
        val budget = NetworkRateLimits.forNetwork(snapshot.controller?.networkId)
        val available = budget.availableItems(tick)
        if (available < requested) {
            if (budget.warnOnce(NetworkBudget.WARN_ITEMS_MOVED)) {
                logCallback("[items moved rate-limited this tick on this network]", true)
            }
            if (atomic) return LuaValue.FALSE
            if (available <= 0L) return LuaValue.valueOf(0)
        }
        val clamped = minOf(requested, available)

        if (atomic) {
            val extracted = bufSrc.extract(clamped)
            if (extracted < clamped) {
                bufSrc.returnUnused(extracted)
                return LuaValue.FALSE
            }
            var totalInserted = 0L
            var remaining = extracted
            while (remaining > 0L) {
                val batch = minOf(remaining, maxStack).toInt()
                val stack = net.minecraft.world.item.ItemStack(item, batch)
                val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, inventoryCache).toLong()
                totalInserted += inserted
                remaining -= inserted
                if (inserted == 0L) break
            }
            if (totalInserted < extracted) {
                bufSrc.returnUnused(extracted - totalInserted)
                return LuaValue.FALSE
            }
            budget.noteItemsMoved(tick, totalInserted)
            return LuaValue.TRUE
        }

        var totalMoved = 0L
        var remaining = clamped
        while (remaining > 0L) {
            val batch = minOf(remaining, maxStack)
            val extracted = bufSrc.extract(batch)
            if (extracted == 0L) break
            val stack = net.minecraft.world.item.ItemStack(item, extracted.toInt())
            val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, inventoryCache).toLong()
            totalMoved += inserted
            if (inserted < extracted) {
                bufSrc.returnUnused(extracted - inserted)
                break
            }
            remaining -= inserted
        }
        budget.noteItemsMoved(tick, totalMoved)
        return LuaValue.valueOf(totalMoved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
        networkTable.setGuarded("Network", "get", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                snapshot.findByAlias(alias)?.let { return createCardTable(it, alias) }
                snapshot.findVariable(alias)?.let { return createVariableTable(it) }
                snapshot.findBreaker(alias)?.let { return BreakerHandle.create(it, snapshot, level) }
                snapshot.findPlacer(alias)?.let { return createPlacerTable(it, snapshot) }
                throw LuaError("Not found on network: '$alias'")
            }
        })

        // network:getAll(type) → HandleList<T> for cards matching the capability type,
        // or HandleList<VariableHandle*> for `"variable"`. Variables share the type
        // string `"variable"` regardless of declared type (number/string/bool), the
        // HandleList's broadcast methods lock in to the shared `VariableHandle`
        // surface (`set`, `cas`). Callers wanting type-specific atomics on every
        // variable should iterate via `:list()`.
        networkTable.setGuarded("Network", "getAll", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, typeArg: LuaValue): LuaValue {
                val type = typeArg.checkjstring()
                if (type == "variable") {
                    val members = snapshot.variables.map { createVariableTable(it) as LuaValue }
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
                        createPlacerTable(it, snapshot) as LuaValue
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
        networkTable.setGuarded("Network", "cards", object : TwoArgFunction() {
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
        networkTable.setGuarded("Network", "channel", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, colorArg: LuaValue): LuaValue {
                val name = colorArg.checkjstring()
                val color = net.minecraft.world.item.DyeColor.byName(name, null)
                    ?: throw LuaError("Unknown channel color: '$name'. Use one of the 16 dye color names (white, red, blue, ...).")
                return createChannelTable(snapshot, color)
            }
        })

        // network:channels() → { string } of every channel currently in use on the
        // network. Walks cards, variables, breakers, and placers, all four kinds of
        // member carry a channel and `network:channel(color):getAll(...)` scopes
        // against any of them, so the in-use set has to mirror the same union.
        // Order is by DyeColor.id ascending so iteration is stable across calls.
        networkTable.setGuarded("Network", "channels", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                val seen = sortedSetOf<net.minecraft.world.item.DyeColor>(compareBy { it.id })
                snapshot.allCards().forEach { seen.add(it.channel) }
                snapshot.variables.forEach { seen.add(it.channel) }
                snapshot.breakers.forEach { seen.add(it.channel) }
                snapshot.placers.forEach { seen.add(it.channel) }
                val result = LuaTable()
                for ((i, color) in seen.withIndex()) {
                    result.set(i + 1, LuaValue.valueOf(color.name.lowercase()))
                }
                return result
            }
        })

        // network:find(filter) → ItemsHandle or nil (scans real storage, aggregated count)
        // Respects kind-qualified filters (`item:*`, `fluid:*`). Bare filters check items first.
        networkTable.setGuarded("Network", "find", object : TwoArgFunction() {
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
        networkTable.setGuarded("Network", "findEach", object : TwoArgFunction() {
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
        networkTable.setGuarded("Network", "count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val count = NetworkStorageHelper.countResource(level, snapshot, filter)
                return LuaValue.valueOf(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        })

        // network:insert(itemsHandle, count?) → boolean (atomic, either the full count lands
        // in network storage or nothing moves). Mirrors CardHandle:insert for consistency.
        // Use network:tryInsert for "move what fits, leave the rest" semantics.
        // Per-network call cap returns false on rate limit (matches "atomic move blocked"
        // semantics scripts already handle).
        networkTable.setGuarded("Network", "insert", networkRateLimited(
            "network:insert",
            consume = { b, tick -> b.tryConsumeItemMoveCall(tick) },
            warnOp = NetworkBudget.WARN_ITEM_MOVE,
            buildNetworkInsertFn(snapshot, atomic = true),
            onLimit = LuaValue.FALSE,
        ))

        // network:tryInsert(itemsHandle, count?) → number (best-effort count moved).
        // Per-network call cap returns 0 on rate limit (partial-success is the natural
        // shape for tryInsert, scripts already check the return value).
        networkTable.setGuarded("Network", "tryInsert", networkRateLimited(
            "network:tryInsert",
            consume = { b, tick -> b.tryConsumeItemMoveCall(tick) },
            warnOp = NetworkBudget.WARN_ITEM_MOVE,
            buildNetworkInsertFn(snapshot, atomic = false),
            onLimit = LuaValue.valueOf(0),
        ))

        // network:craft(identifier, count?) → CraftBuilder.
        //
        // The builder always returns non-nil so scripts don't have to nil-check the
        // call site. The default behavior is "auto-store the result into Network
        // Storage on completion." Calling `:connect(fn)` overrides that: the
        // callback fires with an ItemsHandle on success, or `nil` on any failure
        // (plan failed, async timed out, no Crafting CPU). Without `:connect`, plan
        // failures still log to the terminal so the player sees what went wrong.
        networkTable.setGuarded("Network", "craft", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val identifier = args.checkjstring(2)
                val count = if (args.narg() >= 3 && !args.arg(3).isnil()) args.checkint(3) else 1

                CraftingHelper.currentPendingJob = null
                // omitDeliver = true: the CPU plan stops at the root output, leaving
                // the freshly-produced items in the buffer. The runtime then either
                // hands them to a `:connect(fn)` callback or runs the auto-store path
                // (which uses [releaseCraftResult] and drops any overflow in-world).
                val result = CraftingHelper.craft(
                    identifier, count, level, snapshot,
                    cache = inventoryCache,
                    processingHandlers = processingHandlers.takeIf { it.isNotEmpty() },
                    callerScheduler = scheduler,
                    traceLog = { msg -> logCallback(msg, false) },
                    omitDeliver = true,
                )

                // Check for async pending job (processing handler or async assembly)
                val pending = CraftingHelper.currentPendingJob
                CraftingHelper.currentPendingJob = null

                val planFailed = result == null && pending == null
                if (planFailed) {
                    // Surface the reason now so the player sees it even if their script
                    // never registers a `:connect` callback. The builder still resolves
                    // to a "failed" outcome on the next tick so any registered callback
                    // is invoked uniformly.
                    CraftingHelper.lastFailReason?.let { logCallback(it, true) }
                }

                // For async: result is null but pending is set. Build a CraftResult placeholder
                // so the per-completion buffer release has somewhere to point. Plan failures
                // skip this entirely, there's nothing to release.
                val craftResult: CraftingHelper.CraftResult? = if (planFailed) null else result ?: run {
                    val id = net.minecraft.resources.Identifier.tryParse(identifier)
                    val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) else null
                    val name = if (item != null) net.minecraft.world.item.ItemStack(item).hoverName.string else identifier
                    CraftingHelper.CraftResult(identifier, name, count,
                        cpu = snapshot.cpus.firstOrNull()?.let { level.getBlockEntity(it.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity },
                        level = level, snapshot = snapshot, cache = inventoryCache)
                }

                // Auto-store path: release CPU buffer → storage. Used when no `:connect`
                // handler was registered. Items routed via the standard storage-card
                // priority + route table, with the existing in-world drop fallback when
                // storage is full (handled by `releaseCraftResult`'s downstream path).
                fun autoStoreToNetwork() {
                    val cr = craftResult ?: return
                    CraftingHelper.releaseCraftResult(cr)
                }

                /** Build the buffer-backed [ItemsHandle] passed to a `:connect` callback.
                 *  Items remain in the CPU buffer until the callback drains them via
                 *  `card:insert` / `network:insert`. After the callback returns, anything
                 *  still in the buffer is dropped in-world (see [dropRemainingBuffer]). */
                fun createBufferHandle(cr: CraftingHelper.CraftResult): LuaValue {
                    val cpu = cr.cpu ?: return LuaValue.NIL
                    val id = net.minecraft.resources.Identifier.tryParse(cr.outputItemId)
                    val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) else null
                    val maxStack = item?.getDefaultMaxStackSize() ?: 64
                    return ItemsHandle.toLuaTable(
                        ItemsHandle(
                            itemId = cr.outputItemId,
                            itemName = cr.outputName,
                            count = cr.count,
                            maxStackSize = maxStack,
                            hasData = false,
                            filter = cr.outputItemId,
                            sourceStorage = { null },
                            level = level,
                            bufferSource = damien.nodeworks.script.BufferSource(cpu, cr.outputItemId, cr.count.toLong()),
                        )
                    )
                }

                /** Post-callback cleanup: anything the user's handler didn't drain from
                 *  the CPU buffer is dropped at the CPU's position and an error is
                 *  surfaced through [CraftingCoreBlockEntity.lastFailureReason] (the same
                 *  channel storage-full Deliver-op failures already use, so the CPU's
                 *  GUI shows the red banner and the script terminal sees the message
                 *  via the standard CPU error path). The contract is "you took the
                 *  handle, you're responsible for moving the items." Failing to do so
                 *  isn't silent. */
                fun dropRemainingBuffer(cr: CraftingHelper.CraftResult) {
                    val cpu = cr.cpu ?: return
                    val remaining = cpu.getBufferCount(cr.outputItemId)
                    if (remaining <= 0L) {
                        if (cpu.isCrafting) {
                            cpu.clearAllCraftState()
                            cpu.setCrafting(false)
                        }
                        return
                    }
                    val id = net.minecraft.resources.Identifier.tryParse(cr.outputItemId)
                    val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) else null
                    if (item != null) {
                        val maxStack = item.getDefaultMaxStackSize().toLong()
                        var stillRemaining = remaining
                        while (stillRemaining > 0L) {
                            val batch = minOf(stillRemaining, maxStack).toInt()
                            val stack = net.minecraft.world.item.ItemStack(item, batch)
                            net.minecraft.world.Containers.dropItemStack(
                                level,
                                cpu.blockPos.x + 0.5,
                                cpu.blockPos.y + 1.0,
                                cpu.blockPos.z + 0.5,
                                stack
                            )
                            cpu.removeFromBuffer(cr.outputItemId, batch.toLong())
                            stillRemaining -= batch.toLong()
                        }
                    }
                    val displayName = cr.outputName.ifEmpty { cr.outputItemId }
                    cpu.lastFailureReason =
                        "Craft callback didn't move all items, dropped $remaining × $displayName at the CPU"
                    if (cpu.isCrafting) {
                        cpu.clearAllCraftState()
                        cpu.setCrafting(false)
                    }
                }

                // Mutable resolution state. The handler is registered (or not) by the
                // user's `:connect(fn)` call. The `resolved` guard prevents double-fire
                // when an async pending job's onCompleteCallback races against any
                // future cleanup paths.
                var handler: LuaFunction? = null
                var resolved = false

                fun fireHandler(handle: LuaValue) {
                    val fn = handler ?: return
                    try { fn.call(handle) }
                    catch (e: LuaError) { logCallback("craft callback error: ${gate.stripLuaTraceback(e.message)}", true) }
                }

                fun resolve(success: Boolean) {
                    if (resolved) return
                    resolved = true
                    val cr = craftResult
                    if (success && cr != null) {
                        if (handler != null) {
                            // Hand the handler a buffer-backed view of the result. Items
                            // stay in the CPU buffer until the handler drains them. Anything
                            // left over after the call returns is dropped in-world.
                            fireHandler(createBufferHandle(cr))
                            dropRemainingBuffer(cr)
                        } else {
                            // Default: route into network storage. Storage-full overflow
                            // is handled downstream of releaseCraftResult.
                            autoStoreToNetwork()
                        }
                    } else {
                        // Failure path: any partial buffer is released to storage so the
                        // items aren't stranded, then the handler (if any) is invoked
                        // with nil so scripts can branch on outcome.
                        cr?.let { CraftingHelper.releaseCraftResult(it) }
                        if (handler != null) fireHandler(LuaValue.NIL)
                    }
                }

                // Schedule the resolution. The `:connect(fn)` chain runs synchronously
                // immediately after `network:craft` returns, so we defer the resolve by
                // one tick to give it a chance to register before we fire.
                //
                //   * No pending (plan failure): runOnce(0), resolve with !planFailed.
                //   * Pending already complete (instant sync craft): runOnce(0), pick
                //     up the recorded success flag from the pending job.
                //   * Pending in-flight: hook the completion callback. Resolution
                //     happens whenever the scheduler finishes the plan.
                when {
                    pending == null -> scheduler.runOnce(0) { resolve(success = !planFailed) }
                    pending.isComplete -> {
                        val capturedSuccess = pending.success
                        scheduler.runOnce(0) { resolve(capturedSuccess) }
                    }
                    else -> pending.onCompleteCallback = { success -> resolve(success) }
                }

                // The builder. Only `:connect(fn)` is exposed, the previous `:store()`
                // method went away because auto-store is now the unconfigured default.
                val builder = LuaTable()
                builder.set("connect", object : TwoArgFunction() {
                    override fun call(selfArg: LuaValue, callbackArg: LuaValue): LuaValue {
                        handler = callbackArg.checkfunction()
                        return LuaValue.NIL
                    }
                })

                return builder
            }
        })

        // network:route(aliasPattern) → builder that configures matching
        // Storage Cards' filter NBT directly. Replaces the pre-1.1 runtime
        // routing predicate. Card filters are now the source of truth for
        // routing decisions, so this method just sugars "set the rule list
        // / mode / stack / nbt fields on every card whose alias matches."
        // [routeTable] stays null, the storage helper falls through to the
        // default priority-sorted insert.
        routeTable = null
        networkTable.setGuarded("Network", "route", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val pattern = aliasArg.checkjstring()
                return StorageCardConfigurator.createBuilder(level, snapshot, pattern)
            }
        })


        // network:shapeless(item1, count1, item2?, count2?, ...) → ItemsHandle or nil
        // Crafts using vanilla shapeless recipes. Inputs are item/count pairs.
        networkTable.setGuarded("Network", "shapeless", object : VarArgFunction() {
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
        networkTable.setGuarded("Network", "handle", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue, handlerArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val handler = handlerArg.checkfunction()
                if (name !in processingHandlers) assertCallbackCap(processingHandlers.size, "processing-handler")
                processingHandlers[name] = handler
                return LuaValue.NIL
            }
        })

        // (network:var was removed, variables are now first-class members of the
        // network and resolved through `network:get(name)` alongside cards.)

        // network:debug(), print full network summary
        networkTable.setGuarded("Network", "debug", object : OneArgFunction() {
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
                val tick = PlatformServices.modState.tickCount
                val budget = NetworkRateLimits.forNetwork(currentSnapshot()?.controller?.networkId)
                if (!budget.tryConsumePrint(tick)) {
                    if (budget.warnOnce(NetworkBudget.WARN_PRINT)) {
                        logCallback("[print rate-limited this tick on this network, further prints dropped]", true)
                    }
                    return LuaValue.NONE
                }
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
