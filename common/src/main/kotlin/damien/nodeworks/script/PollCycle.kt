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
 * @param minSlices fastest cycle length, used during activity. Must be >= 1.
 * @param maxSlices slowest cycle length, used after sustained idle. Must be >= [minSlices].
 */
class PollCycle<T>(
    private val minSlices: Int = 5,
    private val maxSlices: Int = 60,
) {
    init {
        require(minSlices >= 1) { "minSlices must be >= 1, got $minSlices" }
        require(maxSlices >= minSlices) { "maxSlices ($maxSlices) must be >= minSlices ($minSlices)" }
    }

    /** Current slice budget. Test/inspection access only, callers shouldn't mutate. */
    var sliceCount: Int = minSlices
        private set

    /** Index of the current tick within the cycle, 0..[sliceCount]. */
    var cycleTick: Int = 0
        private set

    private var lastChangeDetected = false
    private var cycleItems: List<T> = emptyList()

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
        return changed
    }
}
