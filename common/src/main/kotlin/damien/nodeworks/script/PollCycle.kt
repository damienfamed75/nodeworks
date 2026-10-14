package damien.nodeworks.script

/**
 * Generic round-robin polling state machine. Owns the "what to scan this tick"
 * decision so [NetworkInventoryCache] (and any future poller) can keep its body
 * focused on the actual reads. Tested in isolation in PollCycleTest, no
 * Minecraft world required.
 *
 * One cycle takes `sliceCount` ticks. The work is spread evenly across those
 * ticks via [RoundRobinSlicer]. After the last slice, [tick]'s `endCycle`
 * callback fires once and the slice count adapts:
 *
 *   - If the cycle reported changes, snap to [minSlices] (fast cycle, more
 *     responsive when the network is active).
 *   - If idle for two cycles in a row, grow the cycle by 5 ticks toward
 *     [maxSlices] (less work per tick when nothing is happening). The
 *     "two cycles in a row" hysteresis prevents one-tick blips from
 *     bouncing the slice count.
 *
 * [tickWeighted] is the cost-aware variant: items carry a weight (slots to
 * read), work is balanced by weight rather than item count, and the cycle only
 * stretches past [sliceCount] when the measured cost of a cycle would exceed
 * [tickBudgetNanos] per tick. Under budget it keeps exactly the cadence above.
 *
 * @param minSlices fastest cycle length, used during activity. Must be >= 1.
 * @param maxSlices slowest cycle length, used after sustained idle. Must be >= [minSlices].
 * @param tickBudgetNanos per-tick time [tickWeighted] aims to stay under.
 * @param clock nanosecond clock, injectable for tests.
 */
class PollCycle<T>(
    private val minSlices: Int = 5,
    private val maxSlices: Int = 60,
    private val tickBudgetNanos: Long = DEFAULT_TICK_BUDGET_NANOS,
    private val clock: () -> Long = System::nanoTime,
) {
    init {
        require(minSlices >= 1) { "minSlices must be >= 1, got $minSlices" }
        require(maxSlices >= minSlices) { "maxSlices ($maxSlices) must be >= minSlices ($minSlices)" }
        require(tickBudgetNanos > 0) { "tickBudgetNanos must be > 0, got $tickBudgetNanos" }
    }

    companion object {
        /** [tickWeighted] pollItem result for work that shouldn't feed the cost estimate. */
        const val UNMEASURED = -1
        const val DEFAULT_TICK_BUDGET_NANOS = 2_000_000L
        /** Starting cost guess, refined from real polls within a few ticks. */
        const val INITIAL_NANOS_PER_WEIGHT = 250.0
        /** Upper bound on a stretched cycle (60s), so a broken estimate can't stall
         *  polling indefinitely. Above this the budget is exceeded instead. */
        const val MAX_CYCLE_TICKS = 1200
        // Backstop against a caller that re-defers without making progress, which
        // would otherwise spin one tick forever.
        const val MAX_DEFERRED_PER_TICK = 256
        // Samples below this much work are mostly fixed overhead, they'd skew the estimate.
        private const val MIN_SAMPLE_WEIGHT = 64L
        private const val EWMA_ALPHA = 0.25
        // One sample can move the estimate by at most this factor, damping GC pauses.
        private const val MAX_SAMPLE_JUMP = 8.0
        private const val MIN_NANOS_PER_WEIGHT = 1.0
        private const val MAX_NANOS_PER_WEIGHT = 1_000_000.0
    }

    /** Current slice budget. Test/inspection access only, callers shouldn't mutate. */
    var sliceCount: Int = minSlices
        private set

    /** Index of the current tick within the cycle, 0..[sliceCount]. */
    var cycleTick: Int = 0
        private set

    /** Ticks the current [tickWeighted] cycle spans: [sliceCount], or more when
     *  the budget needs it. Inspection only. */
    var cycleLength: Int = minSlices
        private set

    /** Running cost estimate for [tickWeighted]. Inspection only. */
    var nanosPerWeight: Double = INITIAL_NANOS_PER_WEIGHT
        private set

    /** False when the cycle hit [MAX_CYCLE_TICKS] with deferred work still queued
     *  and that work was dropped. Read it inside endCycle: an incomplete cycle
     *  didn't see everything, so it shouldn't be treated as the full picture. */
    var cycleComplete: Boolean = true
        private set

    private var lastChangeDetected = false
    private var cycleItems: List<T> = emptyList()
    // Items for tick t of a weighted cycle are cycleItems[tickBounds[t] until tickBounds[t + 1]].
    private var tickBounds = IntArray(0)
    // Work [defer]red by pollItem, drained on following ticks before the cycle may end.
    private val carry = ArrayDeque<T>()

    /**
     * Drive one tick of the cycle. On the first tick of a cycle ([cycleTick] == 0)
     * the [beginCycle] callback is invoked to snapshot the items to poll. The
     * snapshot is stable for the whole cycle, so item-list churn mid-cycle (a
     * card breaks, a new one is added) doesn't shift slice indices, the change
     * is picked up next cycle.
     *
     * For each tick where there's work to do, [pollItem] is called for every
     * item in the current slice. When the cycle ends, [endCycle] is called
     * once and is expected to apply the diff and return whether changes were
     * detected (true → snap to [minSlices], false → grow toward [maxSlices]).
     *
     * Returns whether [endCycle] reported changes. Intermediate (non-cycle-end)
     * ticks always return false.
     */
    fun tick(
        beginCycle: () -> List<T>,
        pollItem: (T) -> Unit,
        endCycle: () -> Boolean,
    ): Boolean {
        if (cycleTick == 0) cycleItems = beginCycle()

        // Slice budget caps at the item count: with fewer items than slices we
        // don't subdivide further, the trailing ticks of the cycle are idle so
        // overall cadence stays bounded by sliceCount.
        val effective = minOf(sliceCount, cycleItems.size)
        if (cycleTick < effective && effective > 0) {
            for (item in RoundRobinSlicer.slice(cycleItems, cycleTick, effective)) {
                pollItem(item)
            }
        }

        cycleTick++
        if (cycleTick < sliceCount) return false
        return finishCycle(endCycle)
    }

    /**
     * Cost-aware [tick]. [weightOf] is the planned work for an item, [pollItem]
     * polls it and returns the work it actually did (both in the same unit, e.g.
     * slots read), or [UNMEASURED] when its time isn't proportional to that unit
     * and shouldn't feed the cost estimate. Each tick's slice holds an even share
     * of the cycle's weight, so one item is the most a tick can exceed its share
     * by. Every item is polled exactly once per cycle, in list order.
     */
    fun tickWeighted(
        beginCycle: () -> List<T>,
        weightOf: (T) -> Int,
        pollItem: (T) -> Int,
        endCycle: () -> Boolean,
    ): Boolean {
        if (cycleTick == 0) {
            cycleItems = beginCycle()
            carry.clear()
            cycleComplete = true
            planWeightedCycle(weightOf)
        }

        val tickStart = clock()
        var sampleWork = 0L
        var sampleNanos = 0L
        // Timed per item so UNMEASURED items stay out of the estimate entirely.
        fun poll(item: T) {
            val t0 = clock()
            val work = pollItem(item)
            if (work >= 0) {
                sampleWork += work
                sampleNanos += clock() - t0
            }
        }
        // Past cycleLength only deferred work remains, the planned slices are done.
        if (cycleTick < cycleLength) {
            for (i in tickBounds[cycleTick] until tickBounds[cycleTick + 1]) poll(cycleItems[i])
        }
        // Deferred work: at least one item per tick so it always drains, more
        // while the tick is under budget by the real clock, so time spent on
        // anything this tick counts. Items deferred here join the queue.
        var carried = 0
        while (carry.isNotEmpty() && carried < MAX_DEFERRED_PER_TICK &&
            (carried == 0 || clock() - tickStart < tickBudgetNanos)
        ) {
            poll(carry.removeFirst())
            carried++
        }
        recordCost(sampleNanos, sampleWork)

        cycleTick++
        if (cycleTick < cycleLength) return false
        if (carry.isNotEmpty()) {
            if (cycleTick < MAX_CYCLE_TICKS) return false
            // A handler too large to finish in the hard cap. Drop the rest and
            // flag the cycle so endCycle can keep its last good state.
            carry.clear()
            cycleComplete = false
        }
        return finishCycle(endCycle)
    }

    /** Queue [item] to be polled on a later tick of the current [tickWeighted]
     *  cycle. Called from pollItem when an item couldn't finish its work within
     *  one go. The cycle won't end until the queue drains, up to [MAX_CYCLE_TICKS]. */
    fun defer(item: T) {
        carry.addLast(item)
    }

    private fun planWeightedCycle(weightOf: (T) -> Int) {
        val n = cycleItems.size
        // An item can't be spread across ticks, so one costing more than a whole
        // tick counts as exactly one tick. Uncapped, a single unsplittable read
        // (an ME network) would stretch the cycle while spreading nothing.
        val tickWeight = tickBudgetNanos / nanosPerWeight
        val weights = DoubleArray(n) { minOf(weightOf(cycleItems[it]).coerceAtLeast(1).toDouble(), tickWeight) }
        val total = weights.sum()

        val neededTicks = Math.ceil(total / tickWeight)
        cycleLength = if (neededTicks <= sliceCount) sliceCount
            else minOf(neededTicks, MAX_CYCLE_TICKS.toDouble()).toInt().coerceAtLeast(sliceCount)

        // Item i lands on the tick its starting weight falls in, so ticks get
        // equal weight shares and the order is preserved.
        val len = cycleLength
        val bounds = IntArray(len + 1)
        var t = 0
        var prefix = 0.0
        for (i in 0 until n) {
            val itemTick = if (total <= 0.0) 0
                else ((prefix * len) / total).toInt().coerceIn(0, len - 1)
            while (t <= itemTick) bounds[t++] = i
            prefix += weights[i]
        }
        while (t <= len) bounds[t++] = n
        tickBounds = bounds
    }

    /** Replace the cost estimate with a measured full scan, so the first planned
     *  cycle is sized from real data rather than the starting guess. Too little
     *  work to measure leaves the estimate alone. */
    fun seedCost(elapsedNanos: Long, work: Long) {
        if (work < MIN_SAMPLE_WEIGHT || elapsedNanos <= 0L) return
        nanosPerWeight = (elapsedNanos.toDouble() / work).coerceIn(MIN_NANOS_PER_WEIGHT, MAX_NANOS_PER_WEIGHT)
    }

    private fun recordCost(elapsedNanos: Long, work: Long) {
        if (work < MIN_SAMPLE_WEIGHT || elapsedNanos <= 0L) return
        val sample = (elapsedNanos.toDouble() / work)
            .coerceIn(nanosPerWeight / MAX_SAMPLE_JUMP, nanosPerWeight * MAX_SAMPLE_JUMP)
        nanosPerWeight = (nanosPerWeight + EWMA_ALPHA * (sample - nanosPerWeight))
            .coerceIn(MIN_NANOS_PER_WEIGHT, MAX_NANOS_PER_WEIGHT)
    }

    /** Abandon the cycle in progress without calling endCycle, so the next tick
     *  begins a fresh one. For callers that just did their own full scan.
     *  Cadence and the cost estimate are kept. */
    fun restart() {
        cycleTick = 0
        cycleItems = emptyList()
        tickBounds = IntArray(0)
        carry.clear()
        cycleComplete = true
    }

    private fun finishCycle(endCycle: () -> Boolean): Boolean {
        // Cycle complete, finalise and adapt.
        val changed = endCycle()
        if (changed) {
            sliceCount = minSlices
            lastChangeDetected = true
        } else if (lastChangeDetected) {
            // First idle cycle after activity: stay fast for one more pass before
            // starting to back off, smooths over single-tick blips.
            lastChangeDetected = false
        } else {
            sliceCount = minOf(sliceCount + 5, maxSlices)
        }
        cycleTick = 0
        cycleItems = emptyList()
        tickBounds = IntArray(0)
        carry.clear()
        return changed
    }
}
