package damien.nodeworks.script

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-network rate limit registry for script-driven ops that touch a shared
 * resource (storage moves, block placements, redstone writes, variable
 * writes, chat-bound prints, error logs). All engines touching the same
 * network share one pool so a player running N terminals on the same network
 * can't multiply the budget N times by spreading bad scripts across them.
 *
 * Lookup is by network UUID (the controller's networkId). Networks without
 * a controller (rare, transitional state during NodeConnection revalidation)
 * use a fallback "no-network" UUID so the limiter still gates the op.
 *
 * Lazy per-tick reset: each [NetworkBudget] tracks `lastTick` and rolls its
 * counters back to zero when a new tick is observed. No global tick listener
 * needed, no concurrent reset across networks.
 *
 * Memory: budgets are kept in a [ConcurrentHashMap] keyed by UUID. Stale
 * entries (networks that haven't been touched in a long time) just sit
 * there. Each is small (~80 bytes), so even thousands of dead networks
 * don't matter at server scale. If that becomes a concern we can add
 * periodic eviction tied to NetworkSettingsRegistry's existing cleanup.
 */
object NetworkRateLimits {
    /** Sentinel UUID used when a network has no controller (e.g. mid-revalidation).
     *  Prevents the rate limiter from silently passing every op through during
     *  the brief window the controller is being re-resolved. */
    private val NO_NETWORK_UUID: UUID = UUID(0L, 0L)

    private val budgets: ConcurrentHashMap<UUID, NetworkBudget> = ConcurrentHashMap()

    fun forNetwork(networkId: UUID?): NetworkBudget =
        budgets.computeIfAbsent(networkId ?: NO_NETWORK_UUID) { NetworkBudget() }

    /** Reset all budgets. Used on server stop so the next server start begins
     *  with a clean slate and any leftover state from the prior run doesn't
     *  influence the first tick's accounting. */
    fun clearAll() {
        budgets.clear()
    }
}

/**
 * Per-network counters for the script-driven ops shared across all engines
 * touching this network. Each counter tracks calls made this server tick;
 * the [ensureCurrentTick] guard at the start of every check rolls counters
 * back to zero when a new tick is observed.
 *
 * Single-threaded by design (called from the server tick thread). No locks.
 */
class NetworkBudget {
    private var lastTick: Long = -1L

    private var itemMoveCalls: Int = 0
    private var itemsMoved: Long = 0L
    private var placements: Int = 0
    private var redstoneWrites: Int = 0
    private var variableWrites: Int = 0
    private var prints: Int = 0
    private var errorLogs: Int = 0

    /** Track which counters have already emitted a "rate-limited this tick"
     *  warning, so callers can dedupe their log output. Bitmask, one bit per
     *  op: see the `WARN_*` constants. */
    private var warnedMask: Int = 0

    private fun ensureCurrentTick(tick: Long) {
        if (tick == lastTick) return
        lastTick = tick
        itemMoveCalls = 0
        itemsMoved = 0L
        placements = 0
        redstoneWrites = 0
        variableWrites = 0
        prints = 0
        errorLogs = 0
        warnedMask = 0
    }

    fun tryConsumeItemMoveCall(tick: Long): Boolean {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxItemMoveCallsPerTick
        if (cap <= 0) return true
        if (itemMoveCalls >= cap) return false
        itemMoveCalls++
        return true
    }

    fun tryConsumePlacement(tick: Long): Boolean {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxPlacementsPerTick
        if (cap <= 0) return true
        if (placements >= cap) return false
        placements++
        return true
    }

    fun tryConsumeRedstoneWrite(tick: Long): Boolean {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxRedstoneWritesPerTick
        if (cap <= 0) return true
        if (redstoneWrites >= cap) return false
        redstoneWrites++
        return true
    }

    fun tryConsumeVariableWrite(tick: Long): Boolean {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxVariableWritesPerTick
        if (cap <= 0) return true
        if (variableWrites >= cap) return false
        variableWrites++
        return true
    }

    fun tryConsumePrint(tick: Long): Boolean {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxPrintsPerTick
        if (cap <= 0) return true
        if (prints >= cap) return false
        prints++
        return true
    }

    fun tryConsumeErrorLog(tick: Long): Boolean {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxErrorLogsPerTick
        if (cap <= 0) return true
        if (errorLogs >= cap) return false
        errorLogs++
        return true
    }

    /** Available headroom under [ServerSafetySettings.maxItemsMovedPerTickPerNetwork]
     *  for this tick. Returns [Long.MAX_VALUE] when the cap is 0 (unlimited).
     *  Callers use this to clamp `tryInsert` requests or short-circuit atomic
     *  inserts that wouldn't fit. Doesn't deduct from the counter, pair with
     *  [noteItemsMoved] after the actual move to charge the budget for what was
     *  really moved. */
    fun availableItems(tick: Long): Long {
        ensureCurrentTick(tick)
        val cap = ServerPolicy.current.maxItemsMovedPerTickPerNetwork
        if (cap <= 0L) return Long.MAX_VALUE
        return (cap - itemsMoved).coerceAtLeast(0L)
    }

    /** Charge [count] items against this network's per-tick item budget. Pair
     *  with [availableItems] for the pre-check. Splitting the API this way lets
     *  callers commit only the actually-moved count rather than the requested
     *  count, so a partial move (storage full mid-insert) doesn't waste budget. */
    fun noteItemsMoved(tick: Long, count: Long) {
        if (count <= 0L) return
        ensureCurrentTick(tick)
        if (ServerPolicy.current.maxItemsMovedPerTickPerNetwork <= 0L) return
        itemsMoved += count
    }

    /** Returns true exactly once per tick per [op] for callers that want to log
     *  a single warning rather than spamming on every dropped call. */
    fun warnOnce(op: Int): Boolean {
        if ((warnedMask and op) != 0) return false
        warnedMask = warnedMask or op
        return true
    }

    companion object {
        const val WARN_ITEM_MOVE: Int = 1 shl 0
        const val WARN_PLACEMENT: Int = 1 shl 1
        const val WARN_REDSTONE_WRITE: Int = 1 shl 2
        const val WARN_VARIABLE_WRITE: Int = 1 shl 3
        const val WARN_PRINT: Int = 1 shl 4
        const val WARN_ERROR_LOG: Int = 1 shl 5
        const val WARN_ITEMS_MOVED: Int = 1 shl 6
    }
}
