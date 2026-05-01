package damien.nodeworks.config

import damien.nodeworks.script.ServerSafetySettings
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * NeoForge [ModConfigSpec] for `serverconfig/nodeworks-server.toml`. Per-world
 * scope (each world gets its own copy seeded from the in-code defaults on first
 * load). Edit the file and run `/reload` (or restart the server) to push changes
 * through to in-flight script engines via [ServerPolicy].
 *
 * Two sections: `[scripting]` for the wall-clock budgets and per-tick budgets,
 * `[scripting.rateLimits]` for per-op call caps. Future module allow-list and
 * method deny-list will land in additional subsections as those features ship.
 */
object NodeworksServerConfig {

    val SOFT_ABORT_TOP_LEVEL_MS: ModConfigSpec.IntValue
    val SOFT_ABORT_CALLBACK_MS: ModConfigSpec.IntValue
    val TURN_OFF_AUTO_RUN_ON_TIMEOUT: ModConfigSpec.BooleanValue
    val INSTRUCTIONS_PER_WALL_CLOCK_CHECK: ModConfigSpec.IntValue
    val LOCAL_TICK_BUDGET_MS: ModConfigSpec.IntValue
    val GLOBAL_TICK_BUDGET_MS: ModConfigSpec.IntValue
    val MAX_PRINTS_PER_TICK: ModConfigSpec.IntValue
    val MAX_PLACEMENTS_PER_TICK: ModConfigSpec.IntValue
    val MAX_ITEM_MOVE_CALLS_PER_TICK: ModConfigSpec.IntValue
    val MAX_REDSTONE_WRITES_PER_TICK: ModConfigSpec.IntValue
    val MAX_VARIABLE_WRITES_PER_TICK: ModConfigSpec.IntValue
    val MAX_ERROR_LOGS_PER_TICK: ModConfigSpec.IntValue
    val MAX_ITEMS_MOVED_PER_TICK_PER_NETWORK: ModConfigSpec.IntValue
    val MAX_CALLBACKS_PER_KIND: ModConfigSpec.IntValue

    val SPEC: ModConfigSpec

    init {
        val builder = ModConfigSpec.Builder()

        builder
            .comment(
                " Script execution safety.",
                " Wall-clock soft-abort budgets for the Nodeworks scripting terminal,",
                " bounds runaway scripts so a `while true do end` cannot hang the server tick."
            )
            .push("scripting")

        SOFT_ABORT_TOP_LEVEL_MS = builder
            .comment(
                " Wall-clock budget for the top-level chunk (`main`) before the script is",
                " soft-aborted. Longer than the callback budget because legit setup work",
                " (building large route tables, seeding presets) can plausibly take several",
                " seconds. On timeout the terminal's `autoRun` flag is cleared and a",
                " `lastError` is persisted so the bad script doesn't re-fire on world load."
            )
            .defineInRange(
                "topLevelSoftAbortMs",
                ServerSafetySettings.Defaults.topLevelSoftAbortMs.toInt(),
                100,
                600_000,
            )

        SOFT_ABORT_CALLBACK_MS = builder
            .comment(
                " Wall-clock budget for each callback invocation (scheduler tick callbacks,",
                " redstone/observer onChange, processing handlers). Each invocation gets a",
                " fresh budget. A script that just registers handlers and exits consumes no",
                " budget while idle. On timeout the offending callback is evicted from its",
                " registry so it cannot re-fire next tick."
            )
            .defineInRange(
                "callbackSoftAbortMs",
                ServerSafetySettings.Defaults.callbackSoftAbortMs.toInt(),
                100,
                600_000,
            )

        TURN_OFF_AUTO_RUN_ON_TIMEOUT = builder
            .comment(
                " When the top-level chunk soft-aborts, automatically clear `autoRun` on the",
                " terminal so the next world load doesn't re-trigger the bad script. Closes",
                " the per-chunk-load grief vector (a `while true do end` saved with autoRun",
                " on pinning the server tick on every restart). Recommended: true."
            )
            .define(
                "turnOffAutoRunOnTimeout",
                ServerSafetySettings.Defaults.turnOffAutoRunOnTimeout,
            )

        INSTRUCTIONS_PER_WALL_CLOCK_CHECK = builder
            .comment(
                " How many Lua bytecode instructions between wall-clock checks inside the",
                " guarded debug hook. Lower = tighter timeout bounding when the script is in",
                " a tight loop calling slow Kotlin functions (the hook is starved while Lua",
                " is inside Kotlin code, so the check frequency caps how many slow calls",
                " slip past the budget). 32 keeps overhead negligible while bounding the",
                " per-iteration overrun tightly. Heavy compute scripts pay slightly more",
                " but absolute cost is microseconds per tick."
            )
            .defineInRange(
                "instructionsPerWallClockCheck",
                ServerSafetySettings.Defaults.instructionsPerWallClockCheck,
                1,
                65_536,
            )

        LOCAL_TICK_BUDGET_MS = builder
            .comment(
                " Per-engine wall-clock budget within a single server tick. When an engine",
                " exhausts this, remaining callbacks (scheduler tasks, redstone/observer",
                " pollers) defer to the next tick so the server stays responsive even if",
                " an engine has heavy work to do. A single callback that runs longer than",
                " this still completes (bounded by callbackSoftAbortMs cumulatively); the",
                " budget only applies between callbacks, not within them."
            )
            .defineInRange(
                "localTickBudgetMs",
                ServerSafetySettings.Defaults.localTickBudgetMs.toInt(),
                1,
                10_000,
            )

        GLOBAL_TICK_BUDGET_MS = builder
            .comment(
                " Total wall-clock budget for all Lua execution across every running engine",
                " per server tick. When this is exhausted, remaining engines are skipped",
                " entirely for this tick. Combined with localTickBudgetMs, this bounds the",
                " worst-case Lua time one server tick can spend on scripts regardless of",
                " how many engines are active."
            )
            .defineInRange(
                "globalTickBudgetMs",
                ServerSafetySettings.Defaults.globalTickBudgetMs.toInt(),
                1,
                10_000,
            )

        builder.pop()
        builder
            .comment(
                " Per-tick op rate limits.",
                " Each call to a script-callable op that generates packets or container",
                " sync events is counted per tick; calls past the cap return early without",
                " sending packets, defending against the kind of accidental DDoS that the",
                " wall-clock hook alone can't catch (a tight loop fires faster than clients",
                " drain, hanging the server tick on Netty buffer pressure).",
                " Set any of these to 0 for 'unlimited' on trusted / singleplayer servers.",
                "",
                " Split into two pools:",
                "   network: shared across all terminals on the same network. Multiple",
                "            terminals running the same offending script can't multiply",
                "            the cap by spreading themselves across terminals.",
                "   engine:  per-terminal, for state that's genuinely script-internal",
                "            (callback registry size). Each engine has its own pool",
                "            because these aren't shared network resources."
            )
            .push("scripting.rateLimits")
            .push("network")

        MAX_PLACEMENTS_PER_TICK = builder
            .comment(
                " Max script-driven block placements per tick per network (placer:place).",
                " Each placement fires a BlockUpdatePacket and neighbour updates; placement",
                " is heavy, so this cap is the lowest by default."
            )
            .defineInRange(
                "maxPlacementsPerTick",
                ServerSafetySettings.Defaults.maxPlacementsPerTick,
                0,
                100_000,
            )

        MAX_ITEM_MOVE_CALLS_PER_TICK = builder
            .comment(
                " Max network/card insert/tryInsert/route calls per tick per network. Per-call",
                " cap, separate from maxItemsMovedPerTickPerNetwork which limits item count",
                " rather than call count. A single call can move a stack of 64; the call cap",
                " bounds packet flood from menu syncs, the item cap bounds bulk throughput."
            )
            .defineInRange(
                "maxItemMoveCallsPerTick",
                ServerSafetySettings.Defaults.maxItemMoveCallsPerTick,
                0,
                100_000,
            )

        MAX_REDSTONE_WRITES_PER_TICK = builder
            .comment(
                " Max script-driven redstone signal writes per tick per network (redstone:set).",
                " Each write triggers neighbour-change updates."
            )
            .defineInRange(
                "maxRedstoneWritesPerTick",
                ServerSafetySettings.Defaults.maxRedstoneWritesPerTick,
                0,
                100_000,
            )

        MAX_VARIABLE_WRITES_PER_TICK = builder
            .comment(
                " Max script-driven variable card writes per tick per network (var:set, var:cas).",
                " Each write fires setChanged + sendBlockUpdated."
            )
            .defineInRange(
                "maxVariableWritesPerTick",
                ServerSafetySettings.Defaults.maxVariableWritesPerTick,
                0,
                100_000,
            )

        MAX_PRINTS_PER_TICK = builder
            .comment(
                " Max script `print(...)` calls per tick per network. Each print sends a chat",
                " packet to nearby players, so the actual shared resource is the player's",
                " network bandwidth, not any per-terminal channel. Per-network so a player",
                " can't multiply the cap by spreading bad scripts across N terminals."
            )
            .defineInRange(
                "maxPrintsPerTick",
                ServerSafetySettings.Defaults.maxPrintsPerTick,
                0,
                100_000,
            )

        MAX_ERROR_LOGS_PER_TICK = builder
            .comment(
                " Max error-log dispatches per tick per network. Same packet path as",
                " maxPrintsPerTick but for error-log calls (bug spam from a loop).",
                " Counted separately so legit error reporting isn't starved by a noisy script."
            )
            .defineInRange(
                "maxErrorLogsPerTick",
                ServerSafetySettings.Defaults.maxErrorLogsPerTick,
                0,
                100_000,
            )

        MAX_ITEMS_MOVED_PER_TICK_PER_NETWORK = builder
            .comment(
                " Max items moved per tick per network across all network:insert and",
                " network:tryInsert calls. Independent of maxItemMoveCallsPerTick (a",
                " single call can move 64 items, so call count != item count). 0 means",
                " unlimited. When exhausted, tryInsert returns partial counts and atomic",
                " insert short-circuits to false."
            )
            .defineInRange(
                "maxItemsMovedPerTickPerNetwork",
                ServerSafetySettings.Defaults.maxItemsMovedPerTickPerNetwork.toInt(),
                0,
                Int.MAX_VALUE,
            )

        builder.pop()
        builder.push("engine")

        MAX_CALLBACKS_PER_KIND = builder
            .comment(
                " Max simultaneously-registered callbacks per kind per engine (scheduler",
                " tasks, redstone handlers, observer handlers, processing handlers, presets).",
                " The 257th registration throws a fatal Lua error and locks the terminal,",
                " so a recursive self-register pattern (a callback that registers another",
                " callback each time it fires) crashes loud rather than slowly bloating the",
                " registry. Per-engine because the registry is script-internal state."
            )
            .defineInRange(
                "maxCallbacksPerKind",
                ServerSafetySettings.Defaults.maxCallbacksPerKind,
                0,
                100_000,
            )

        builder.pop()
        SPEC = builder.build()
    }

    /** Snapshot the current spec values into a [ServerSafetySettings] instance.
     *  Called by the config-event listener on load and reload. */
    fun snapshot(): ServerSafetySettings = ServerSafetySettings(
        topLevelSoftAbortMs = SOFT_ABORT_TOP_LEVEL_MS.get().toLong(),
        callbackSoftAbortMs = SOFT_ABORT_CALLBACK_MS.get().toLong(),
        turnOffAutoRunOnTimeout = TURN_OFF_AUTO_RUN_ON_TIMEOUT.get(),
        instructionsPerWallClockCheck = INSTRUCTIONS_PER_WALL_CLOCK_CHECK.get(),
        localTickBudgetMs = LOCAL_TICK_BUDGET_MS.get().toLong(),
        globalTickBudgetMs = GLOBAL_TICK_BUDGET_MS.get().toLong(),
        maxPrintsPerTick = MAX_PRINTS_PER_TICK.get(),
        maxPlacementsPerTick = MAX_PLACEMENTS_PER_TICK.get(),
        maxItemMoveCallsPerTick = MAX_ITEM_MOVE_CALLS_PER_TICK.get(),
        maxRedstoneWritesPerTick = MAX_REDSTONE_WRITES_PER_TICK.get(),
        maxVariableWritesPerTick = MAX_VARIABLE_WRITES_PER_TICK.get(),
        maxErrorLogsPerTick = MAX_ERROR_LOGS_PER_TICK.get(),
        maxItemsMovedPerTickPerNetwork = MAX_ITEMS_MOVED_PER_TICK_PER_NETWORK.get().toLong(),
        maxCallbacksPerKind = MAX_CALLBACKS_PER_KIND.get(),
    )
}
