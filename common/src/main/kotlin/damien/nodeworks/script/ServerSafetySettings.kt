package damien.nodeworks.script

/**
 * Tunables for the script-execution safety layer. Read from
 * `serverconfig/nodeworks-server.toml` at server start and live-reloaded on
 * `/reload`. [ServerPolicy] holds the active instance, [LuaExecGate] reads
 * from it on every gated entry so config edits propagate without engine
 * restarts.
 *
 * Times are in **milliseconds** (rather than NeoForge `Duration` strings)
 * because `ModConfigSpec` doesn't natively parse duration strings and an
 * integer ms config value is the lowest-friction path for both code and
 * admins.
 */
data class ServerSafetySettings(
    /** Wall-clock budget for the top-level chunk (`main`). Slightly longer than
     *  the callback budget because legit setup work (building large route
     *  tables, seeding presets, registering many handlers) can plausibly take
     *  a couple seconds. A runaway top-level script can pin the server tick
     *  for the full duration of this budget, the per-tick budget defers
     *  *between* callbacks, not within them, so keep it short. */
    val topLevelSoftAbortMs: Long = 3_000,

    /** Wall-clock budget for each callback invocation: scheduler tick callbacks,
     *  redstone/observer onChange, processing handlers, route predicates, breaker
     *  drop handlers. Each invocation gets a fresh budget. A script idle on
     *  registered handlers consumes no budget. */
    val callbackSoftAbortMs: Long = 1_000,

    /** When the top-level chunk soft-aborts, automatically clear `autoRun` on the
     *  terminal so the next world load doesn't re-trigger the bad script. The
     *  per-chunk-load grief vector (a `while true do end` saved with autoRun on
     *  pinning the server tick on every restart) closes when this is true. */
    val turnOffAutoRunOnTimeout: Boolean = true,

    /** How many bytecode instructions between wall-clock checks inside the
     *  guarded debug hook. Lower = tighter timeout enforcement when the script is
     *  in a tight Lua loop calling slow Kotlin functions (e.g. `while true do
     *  print() end`, the hook is starved while Lua is inside the Kotlin call,
     *  so the check frequency bounds how many slow calls slip past the budget).
     *  32 keeps overhead negligible (~50ns × 32 bytecodes worth of script work)
     *  while bounding the per-loop-iteration overrun tightly. Heavy compute-bound
     *  scripts pay slightly more but the absolute cost is microseconds per tick. */
    val instructionsPerWallClockCheck: Int = 32,

    /** Per-engine wall-clock budget within a single server tick. When an engine
     *  exhausts this, remaining callbacks (scheduler tasks, redstone/observer
     *  pollers) defer to the next tick. Doesn't yield mid-callback. A single
     *  callback that runs longer than this still completes (bounded by
     *  [callbackSoftAbortMs] cumulatively), but the engine's other work is
     *  pushed off so the server tick stays responsive. */
    val localTickBudgetMs: Long = 5,

    /** Total wall-clock budget for ALL Lua execution across every running engine
     *  per server tick. When this is exhausted, remaining engines are skipped
     *  entirely for this tick. Combined with [localTickBudgetMs], this bounds
     *  the worst-case Lua time one tick can consume regardless of how many
     *  engines are active. */
    val globalTickBudgetMs: Long = 10,

    // -- Per-tick op rate limits ---------------------------------------------
    //
    // Each call to a script-callable op that generates outbound packets or
    // `setChanged` cascades is counted against its limiter. When the cap is
    // hit, further calls in the same tick return early without doing the work
    // (and without sending packets). Bounds the worst-case packet flood from
    // a tight loop calling these ops, defending against the kind of accidental
    // server DDoS that the wall-clock hook alone can't catch (Netty's outbound
    // buffer can saturate before the hook fires, hanging the tick).
    //
    // 0 on any of these means "unlimited", singleplayer or trusted servers
    // can opt out of all rate limiting by setting them to 0 in the config.

    /** Max script-driven `print(...)` calls per tick per network. Each print
     *  dispatches a chat packet to every player in range, so the actual shared
     *  resource is the player's outbound bandwidth. Per-network bounds the
     *  flood across multi-terminal setups. */
    val maxPrintsPerTick: Int = 20,

    /** Max script-driven block placements per tick per network (placer:place).
     *  Each placement fires a BlockUpdatePacket to nearby clients + neighbour
     *  updates. Placement is naturally heavy, lower than the others. */
    val maxPlacementsPerTick: Int = 5,

    /** Max script-driven network/card insert/tryInsert/route calls per tick
     *  per network. Each call can fire `setChanged` on storage containers,
     *  triggering menu sync packets to anyone with a GUI open. Per-call cap,
     *  separate from [maxItemsMovedPerTickPerNetwork] which counts items
     *  rather than calls. */
    val maxItemMoveCallsPerTick: Int = 100,

    /** Max script-driven redstone signal writes per tick per network
     *  (redstone:set). Each write triggers neighbour-change updates. */
    val maxRedstoneWritesPerTick: Int = 20,

    /** Max script-driven variable card writes per tick per network
     *  (var:set, var:cas). Each write fires `setChanged` + `sendBlockUpdated`. */
    val maxVariableWritesPerTick: Int = 100,

    /** Max error-log dispatches per tick per network. Same packet path as
     *  [maxPrintsPerTick] but for `isError = true` log calls. Typically
     *  low-volume but a `pcall(function() error("x") end)` loop could abuse
     *  it. Counted separately from prints so legit error reporting isn't
     *  starved by a noisy script. */
    val maxErrorLogsPerTick: Int = 20,

    /** Max items moved per tick per network across all `network:insert` and
     *  `network:tryInsert` calls (and any future bulk-move paths that route
     *  through the same helper). Independent of [maxItemMoveCallsPerTick]
     *  (a single call can move 64 items, so call count != item count).
     *  When this is exhausted, `tryInsert` returns partial counts and atomic
     *  `insert` short-circuits to false. 0 means unlimited. */
    val maxItemsMovedPerTickPerNetwork: Long = 0,

    /** Max simultaneously-registered callbacks per kind, per engine. Each kind
     *  (scheduler tasks, redstone handlers, observer handlers, processing
     *  handlers, presets) has its own counter. The 257th `scheduler:tick(...)`
     *  or `redstone:onChange(...)` etc. throws a fatal [LuaError] so the
     *  script crashes loud rather than slowly leaking memory.
     *
     *  Catches the recursive-self-registration pattern (a callback that adds
     *  *another* callback every time it fires) before the registry bloats.
     *  Without this cap, such a script registers thousands of handlers, each
     *  of which fires on its own schedule, eventually saturating the per-tick
     *  budget AND building up unbounded memory. */
    val maxCallbacksPerKind: Int = 256,
) {
    companion object {
        /** Compiled-in defaults, used as the seed values when generating the
         *  config TOML for the first time and as the fallback when running in a
         *  context that hasn't loaded the config yet (unit tests, pre-init). */
        val Defaults: ServerSafetySettings = ServerSafetySettings()
    }
}
