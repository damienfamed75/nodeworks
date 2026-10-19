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
 * Memory: budgets are kept in a [ConcurrentHashMap] keyed by UUID and swept by
 * [evictIdle] once nothing has asked for them in a while.
 */
object NetworkRateLimits {
    /** Sentinel UUID used when a network has no controller (e.g. mid-revalidation).
     *  Prevents the rate limiter from silently passing every op through during
     *  the brief window the controller is being re-resolved. */
    private val NO_NETWORK_UUID: UUID = UUID(0L, 0L)

    private val budgets: ConcurrentHashMap<UUID, NetworkBudget> = ConcurrentHashMap()

    /** Transfer Cards move under their own clock, see [forTransferCards]. */
    private val transferBudgets: ConcurrentHashMap<UUID, NetworkBudget> = ConcurrentHashMap()

    /** Share of the per-network move time Transfer Cards may use among themselves. */
    private const val TRANSFER_CARD_TIME_SHARE = 0.5

    /** A budget nobody has touched for this long is dropped. Node-anchored network ids
     *  come and go with the Nodes, so without this the maps would only ever grow. */
    private const val IDLE_TICKS = 20L * 60L * 5L
    private const val IDLE_SWEEP_TICKS = 20L * 60L

    fun forNetwork(networkId: UUID?): NetworkBudget =
        budgets.computeIfAbsent(networkId ?: NO_NETWORK_UUID) { NetworkBudget() }

    /** The budget a network's Transfer Cards share. Separate from [forNetwork] so a card
     *  stuck on a slow inventory slows other cards on that network and nothing else:
     *  scripts, chests and crafting keep their full move time. Items still count
     *  against the shared per-tick item cap through [forNetwork]. */
    fun forTransferCards(networkId: UUID?): NetworkBudget =
        transferBudgets.computeIfAbsent(networkId ?: NO_NETWORK_UUID) { NetworkBudget(TRANSFER_CARD_TIME_SHARE) }

    fun evictIdle(tick: Long) {
        if (tick % IDLE_SWEEP_TICKS != 0L) return
        budgets.values.removeIf { tick - it.lastSeenTick > IDLE_TICKS }
        transferBudgets.values.removeIf { tick - it.lastSeenTick > IDLE_TICKS }
    }

    /** Reset all budgets. Used on server stop so the next server start begins
     *  with a clean slate and any leftover state from the prior run doesn't
     *  influence the first tick's accounting. */
    fun clearAll() {
        budgets.clear()
        transferBudgets.clear()
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
class NetworkBudget(
    /** Fraction of the configured per-network move time this budget may spend. */
    private val moveTimeShare: Double = 1.0,
) {
    private var lastTick: Long = -1L

    /** The last tick anything asked this budget, for idle eviction. */
    val lastSeenTick: Long get() = lastTick

    private var itemMoveCalls: Int = 0
    private var itemsMoved: Long = 0L
    private var moveNanos: Long = 0L
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
        moveNanos = 0L
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
     *  for this tick. A cap of 0 means the default, [DEFAULT_ITEMS_PER_TICK].
     *  Callers use this to clamp `tryInsert` requests or short-circuit atomic
     *  inserts that wouldn't fit. Doesn't deduct from the counter, pair with
     *  [noteItemsMoved] after the actual move to charge the budget for what was
     *  really moved. */
    fun availableItems(tick: Long): Long {
        ensureCurrentTick(tick)
        return (itemCap() - itemsMoved).coerceAtLeast(0L)
    }

    /** Charge [count] items against this network's per-tick item budget. Pair
     *  with [availableItems] for the pre-check. Splitting the API this way lets
     *  callers commit only the actually-moved count rather than the requested
     *  count, so a partial move (storage full mid-insert) doesn't waste budget. */
    fun noteItemsMoved(tick: Long, count: Long) {
        if (count <= 0L) return
        ensureCurrentTick(tick)
        itemsMoved += count
    }

    /** Move time left this tick under [ServerSafetySettings.maxItemMoveMsPerTickPerNetwork]. */
    fun moveTimeLeftNanos(tick: Long): Long {
        ensureCurrentTick(tick)
        val full = ServerPolicy.current.maxItemMoveMsPerTickPerNetwork.coerceAtLeast(1L) * 1_000_000L
        return (full * moveTimeShare).toLong().coerceAtLeast(1L) - moveNanos
    }

    /** Run a move, charging its wall-clock time to this tick. A best-effort one is also
     *  stopped once the time left runs out, keeping what it moved. An atomic one can't
     *  stop halfway, it is bounded by [MAX_ATOMIC_ITEMS] instead. */
    fun <T> timedMove(tick: Long, atomic: Boolean, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return if (atomic) damien.nodeworks.platform.MoveDeadline.suspended(block)
            else damien.nodeworks.platform.MoveDeadline.within(moveTimeLeftNanos(tick), block)
        } finally {
            ensureCurrentTick(tick)
            moveNanos += System.nanoTime() - start
        }
    }

    /** Returns true exactly once per tick per [op] for callers that want to log
     *  a single warning rather than spamming on every dropped call. */
    fun warnOnce(op: Int): Boolean {
        if ((warnedMask and op) != 0) return false
        warnedMask = warnedMask or op
        return true
    }

    companion object {
        /** Most items one atomic insert may move, whatever the config says. An atomic move
         *  runs in one go and can't be spread over ticks, so this bounds its cost. */
        const val MAX_ATOMIC_ITEMS: Long = 16_384L

        /** Distinct items a fullness dry run simulates before just trying the move. */
        const val FULLNESS_CHECK_STACKS: Int = 64

        /** Items per tick per network when the config is 0. */
        const val DEFAULT_ITEMS_PER_TICK: Long = 16_384L

        /** The configured items-per-tick cap, with 0 meaning [DEFAULT_ITEMS_PER_TICK]. */
        fun itemCap(): Long = ServerPolicy.current.maxItemsMovedPerTickPerNetwork.takeIf { it > 0L } ?: DEFAULT_ITEMS_PER_TICK

        /** Most items one importer or stocker run may move, whatever the config says, so one
         *  run's work, including a single huge drawer slot, stays bounded. */
        const val MAX_ITEMS_PER_PRESET_RUN: Long = 16_384L

        /** The configured per-run preset cap. 0, or anything above [MAX_ITEMS_PER_PRESET_RUN],
         *  means that ceiling. */
        fun presetRunCap(): Long =
            ServerPolicy.current.maxItemsPerPresetRun.takeIf { it in 1L..MAX_ITEMS_PER_PRESET_RUN } ?: MAX_ITEMS_PER_PRESET_RUN

        fun atomicCeilingMessage(op: String, requested: Long): String =
            "$op moved nothing: $requested items is over the $MAX_ATOMIC_ITEMS item limit for a single insert. " +
                "Use tryInsert in a loop to move them in batches."

        fun moveTimeMessage(op: String): String =
            "$op moved nothing: this network already used its item-moving time this tick. Try again next tick, or use tryInsert."

        /** Terminal text for an insert refused by the items-per-tick limit. [op] names the
         *  call, e.g. `card:insert into 'chest'`. tryInsert caps silently instead. */
        fun itemLimitMessage(op: String, requested: Long, available: Long): String {
            val cap = itemCap()
            return when {
                requested > cap -> "$op moved nothing: $requested items is over this network's limit of $cap items per tick. " +
                    "Use tryInsert in a loop to move them in batches."
                else -> "$op moved nothing: only $available of this network's $cap items per tick are left this tick."
            }
        }

        const val WARN_ITEM_MOVE: Int = 1 shl 0
        const val WARN_PLACEMENT: Int = 1 shl 1
        const val WARN_REDSTONE_WRITE: Int = 1 shl 2
        const val WARN_VARIABLE_WRITE: Int = 1 shl 3
        const val WARN_PRINT: Int = 1 shl 4
        const val WARN_ERROR_LOG: Int = 1 shl 5
    }
}
