package damien.nodeworks.script

/**
 * Per-tick rate limiter for script-callable operations. Each instance tracks how
 * many times its associated op fired this server tick, resetting the counter when
 * the tick number changes. Once the per-tick cap is reached, [tryConsume] returns
 * false until the next tick.
 *
 * Used to bound script-driven packet floods: a `while true do print(...) end`
 * loop produces packets faster than clients can drain them, hanging the server
 * tick on outbound buffer pressure long before the wall-clock soft-abort hook
 * can react. The same shape applies to `placer:place`, `redstone:set`,
 * `var:set`, and the `network:insert/tryInsert/route` family. Any op whose
 * single call generates one or more outbound packets or `setChanged` cascades
 * is bounded by an instance of this limiter.
 *
 * The cap is read live from a closure each tick, so a `/reload` of the server
 * config takes effect immediately. A cap of 0 means "unlimited", single-player
 * and trusted-server scenarios opt out by setting their knobs to 0.
 *
 * Single-threaded by design (called from the server tick thread), no locks.
 */
class TickRateLimit(
    private val maxProvider: () -> Int,
) {
    private var lastTick: Long = -1L
    private var count: Int = 0
    private var warnedThisTick: Boolean = false

    /** Try to consume one slot for the current tick. Returns true on success,
     *  false when the per-tick cap has already been hit. The caller can use
     *  [warnOnce] to log a single message per tick when the cap kicks in. */
    fun tryConsume(currentTick: Long): Boolean {
        if (currentTick != lastTick) {
            lastTick = currentTick
            count = 0
            warnedThisTick = false
        }
        val max = maxProvider()
        if (max <= 0) return true                       // 0 = unlimited
        if (count >= max) return false
        count++
        return true
    }

    /** Returns true exactly once per tick after the cap is hit. Use this to log
     *  a single warning rather than spamming the terminal log on every dropped
     *  call. The caller is expected to invoke this only when [tryConsume]
     *  returned false. */
    fun warnOnce(): Boolean {
        if (warnedThisTick) return false
        warnedThisTick = true
        return true
    }

    /** Reset internal state. Used when the engine restarts so a new run starts
     *  with a clean counter, no carry-over from the prior run's tick. */
    fun reset() {
        lastTick = -1L
        count = 0
        warnedThisTick = false
    }
}
