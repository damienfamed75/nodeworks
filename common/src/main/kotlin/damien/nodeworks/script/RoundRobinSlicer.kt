package damien.nodeworks.script

/**
 * Pure helper for round-robin work spreading. Used by [NetworkInventoryCache] to
 * split a per-poll-cycle scan of every Storage Card across multiple ticks instead
 * of doing the whole burst on one tick.
 *
 * Given a list of items, a cycle index, and a slice count, returns the slice of
 * items that should be visited on this tick. Successive cycle indices walk
 * through the list in chunks of `ceil(items.size / sliceCount)`. After
 * `sliceCount` cycles, every item has been visited exactly once and the cycle
 * wraps back to the beginning.
 *
 * Kept as its own object (not an extension function on List) so the algorithm is
 * easy to unit-test in isolation, no Minecraft world required.
 */
object RoundRobinSlicer {

    /**
     * Return the slice of [items] that should be visited at the given [cycleIndex],
     * given a target [sliceCount] (how many ticks one full pass should take).
     *
     * - [items] empty → empty list.
     * - [sliceCount] ≤ 0 is treated as 1 (no slicing, return every item).
     * - [sliceCount] greater than `items.size` is capped to `items.size` so the
     *   chunk size never falls below 1 item per cycle.
     * - [cycleIndex] is taken modulo the effective slice count, so the caller can
     *   pass an unbounded counter and get the right wrap-around.
     */
    fun <T> slice(items: List<T>, cycleIndex: Int, sliceCount: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val slices = sliceCount.coerceAtLeast(1)
        val effectiveSlices = minOf(slices, items.size)
        // Ceiling division so when the list doesn't divide evenly, the trailing
        // slot gets the remainder rather than overflowing into a phantom slot.
        val chunkSize = (items.size + effectiveSlices - 1) / effectiveSlices
        val slot = Math.floorMod(cycleIndex, effectiveSlices)
        val start = slot * chunkSize
        val end = minOf(start + chunkSize, items.size)
        return items.subList(start, end)
    }
}
