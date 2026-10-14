package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PollCycle.tickWeighted], the cost-aware path the inventory cache polls with.
 * Items are (name, weight) pairs, and a fake clock charges [nanosPerUnit] per
 * unit of weight polled, so cost-driven cycle stretching is deterministic.
 */
class PollCycleWeightedTest {

    private data class Unit(val name: String, val weight: Int)

    private class Harness(
        var items: List<Unit>,
        minSlices: Int = 5,
        maxSlices: Int = 60,
        budgetNanos: Long = 2_000_000L,
        var nanosPerUnit: Long = 100L,
    ) {
        var now = 0L
        val cycle = PollCycle<Unit>(minSlices, maxSlices, budgetNanos) { now }
        val polled = mutableListOf<Unit>()
        /** Weight polled on each tick, in order. */
        val weightPerTick = mutableListOf<Long>()
        val cycleEndTicks = mutableListOf<Int>()
        var endCallChange: () -> Boolean = { false }
        var beginCalls = 0
        private var totalTicks = 0

        fun tick(): Boolean {
            totalTicks++
            var tickWeight = 0L
            val result = cycle.tickWeighted(
                beginCycle = { beginCalls++; items },
                weightOf = { it.weight },
                pollItem = {
                    polled.add(it)
                    tickWeight += it.weight
                    now += it.weight * nanosPerUnit
                    it.weight
                },
                endCycle = {
                    cycleEndTicks.add(totalTicks)
                    endCallChange()
                },
            )
            weightPerTick.add(tickWeight)
            return result
        }

        fun runTicks(n: Int) = repeat(n) { tick() }

        /** Run until the current cycle ends, returning how many ticks it took. */
        fun runOneCycle(): Int {
            val endsBefore = cycleEndTicks.size
            var ticks = 0
            while (cycleEndTicks.size == endsBefore) {
                tick()
                ticks++
                check(ticks <= PollCycle.MAX_CYCLE_TICKS) { "cycle never ended" }
            }
            return ticks
        }
    }

    private fun chests(n: Int, slots: Int = 27) = (0 until n).map { Unit("chest$it", slots) }

    // Cadence under budget, the case every ordinary network is in

    @Test
    fun underBudgetKeepsSliceCountCadence() {
        val h = Harness(chests(100))
        repeat(4) { assertEquals(h.cycle.sliceCount, h.runOneCycle()) }
    }

    @Test
    fun underBudgetCycleLengthMatchesUnweightedTick() {
        // Same items through both paths, both cycles end on the same tick.
        val items = chests(12)
        val weighted = Harness(items)
        weighted.runTicks(5)

        val plain = PollCycle<Unit>(5, 60)
        val plainEnds = mutableListOf<Int>()
        repeat(5) { i -> plain.tick({ items }, {}, { plainEnds.add(i + 1); false }) }

        assertEquals(plainEnds, weighted.cycleEndTicks)
    }

    @Test
    fun idleAndChangeAdaptationMatchesUnweightedTick() {
        val h = Harness(chests(3))
        repeat(3) { h.runOneCycle() }
        assertEquals(20, h.cycle.sliceCount)
        h.endCallChange = { true }
        h.runOneCycle()
        assertEquals(5, h.cycle.sliceCount)
    }

    // Coverage

    @Test
    fun everyItemPolledExactlyOncePerCycleInOrder() {
        val items = listOf(Unit("a", 1), Unit("b", 5000), Unit("c", 0), Unit("d", 27), Unit("e", 1024))
        val h = Harness(items)
        h.runOneCycle()
        assertEquals(items, h.polled)
        h.polled.clear()
        h.runOneCycle()
        assertEquals(items, h.polled)
    }

    @Test
    fun emptyItemListStillEndsCycleOnSchedule() {
        val h = Harness(emptyList())
        assertEquals(5, h.runOneCycle())
        assertTrue(h.polled.isEmpty())
    }

    @Test
    fun itemsSnapshottedAtCycleStartAreStableMidCycle() {
        val first = chests(10)
        val second = listOf(Unit("new", 27))
        val h = Harness(first)
        h.runTicks(2)
        h.items = second
        h.runOneCycle()
        assertEquals(first, h.polled)
        h.polled.clear()
        h.runOneCycle()
        assertEquals(second, h.polled)
    }

    // Balance, the point of the change

    @Test
    fun splitControllerIsSpreadAcrossTicksNotFrontLoaded() {
        // 30 ranges of 1024 slots (one giant controller) after 100 chests.
        // Count-based slicing would put all 30 ranges on one or two ticks.
        val controller = (0 until 30).map { Unit("ctrl$it", 1024) }
        val items = chests(100) + controller
        val h = Harness(items)
        val ticks = h.runOneCycle()

        val total = items.sumOf { it.weight.toLong() }
        val share = total / ticks
        val maxItem = items.maxOf { it.weight }
        for (w in h.weightPerTick) {
            assertTrue(w <= share + maxItem, "tick polled $w, share $share + max item $maxItem")
        }
    }

    @Test
    fun noTickExceedsShareByMoreThanOneItem() {
        val items = listOf(1, 900, 3, 1024, 1024, 7, 1024, 512, 2, 1024, 1024, 60).mapIndexed { i, w -> Unit("u$i", w) }
        val h = Harness(items, minSlices = 4, maxSlices = 4)
        h.runOneCycle()
        val share = items.sumOf { it.weight.toLong() } / 4
        for (w in h.weightPerTick) assertTrue(w <= share + 1024, "tick polled $w over share $share")
    }

    // Budget-driven stretching

    @Test
    fun overBudgetStretchesCycleUntilTicksFitTheBudget() {
        // 200k slots at 1000ns each = 200ms per cycle. With a 2ms budget that
        // needs ~100 ticks. The estimate starts at 250ns and converges.
        val items = (0 until 196).map { Unit("r$it", 1024) }
        val h = Harness(items, nanosPerUnit = 1000L)
        repeat(6) { h.runOneCycle() }

        val ticks = h.runOneCycle()
        assertTrue(ticks in 95..110, "stretched cycle took $ticks ticks")
        val lastCycle = h.weightPerTick.takeLast(ticks)
        for (w in lastCycle) {
            val nanos = w * 1000L
            assertTrue(nanos <= 2_000_000L + 1024 * 1000L, "tick cost ${nanos}ns, over budget by more than one range")
        }
    }

    @Test
    fun cheapPollsNeverStretchTheCycleOnceSeeded() {
        // 200k slots but only 10ns each, 2ms total, fits in 5 ticks easily.
        // The cache seeds from its creation-time full scan, mirrored here.
        val items = (0 until 196).map { Unit("r$it", 1024) }
        val h = Harness(items, nanosPerUnit = 10L)
        val totalWeight = items.sumOf { it.weight.toLong() }
        h.cycle.seedCost(totalWeight * 10L, totalWeight)
        repeat(5) { assertEquals(h.cycle.sliceCount, h.runOneCycle()) }
    }

    @Test
    fun unseededEstimateConvergesWithinOneCycle() {
        // Without a seed the first cycle is sized from the starting guess, the
        // per-tick samples correct it before the second cycle is planned.
        val items = (0 until 196).map { Unit("r$it", 1024) }
        val h = Harness(items, nanosPerUnit = 10L)
        h.runOneCycle()
        repeat(4) { assertEquals(h.cycle.sliceCount, h.runOneCycle()) }
    }

    @Test
    fun seedReplacesTheEstimateOutright() {
        val h = Harness(chests(1))
        h.cycle.seedCost(elapsedNanos = 50_000L, work = 1000L)
        assertEquals(50.0, h.cycle.nanosPerWeight)
    }

    @Test
    fun seedIgnoresTooLittleWork() {
        val h = Harness(chests(1))
        h.cycle.seedCost(elapsedNanos = 1_000_000L, work = 10L)
        assertEquals(PollCycle.INITIAL_NANOS_PER_WEIGHT, h.cycle.nanosPerWeight)
    }

    @Test
    fun stretchedCycleShrinksBackWhenPollsGetCheaper() {
        val items = (0 until 196).map { Unit("r$it", 1024) }
        val h = Harness(items, nanosPerUnit = 1000L)
        repeat(6) { h.runOneCycle() }
        assertTrue(h.cycle.cycleLength > h.cycle.sliceCount)

        h.nanosPerUnit = 5L
        repeat(10) { h.runOneCycle() }
        assertEquals(h.cycle.sliceCount, h.cycle.cycleLength)
    }

    @Test
    fun stretchingIsCappedAtMaxCycleTicks() {
        val items = (0 until 2000).map { Unit("r$it", 1024) }
        val h = Harness(items, nanosPerUnit = 100_000L)
        repeat(4) { h.runOneCycle() }
        assertEquals(PollCycle.MAX_CYCLE_TICKS, h.cycle.cycleLength)
        assertEquals(items, h.polled.takeLast(items.size))
    }

    @Test
    fun oneSlowTickMovesTheEstimateBoundedly() {
        val h = Harness(chests(200), nanosPerUnit = 100L)
        repeat(3) { h.runOneCycle() }
        val before = h.cycle.nanosPerWeight

        // A GC-pause-sized stall on a single tick.
        h.nanosPerUnit = 10_000_000L
        h.tick()
        h.nanosPerUnit = 100L
        val after = h.cycle.nanosPerWeight
        assertTrue(after <= before * 8.0, "estimate jumped from $before to $after")
    }

    @Test
    fun tinySamplesDoNotMoveTheEstimate() {
        val h = Harness(listOf(Unit("tiny", 3)), nanosPerUnit = 1_000_000L)
        repeat(3) { h.runOneCycle() }
        assertEquals(PollCycle.INITIAL_NANOS_PER_WEIGHT, h.cycle.nanosPerWeight)
    }

    // Unsplittable whole reads (an ME network behind one card)

    @Test
    fun oneHeavyUnsplittableItemDoesNotStretchTheCycle() {
        // An AE2 network with 50k item types next to ordinary chests. It can't
        // be split, so stretching the cycle would only delay its updates.
        var now = 0L
        val cycle = PollCycle<Unit>(5, 60) { now }
        val items = listOf(Unit("me", 50_000)) + chests(100)
        var ends = 0
        var ticks = 0
        while (ends < 3) {
            ticks++
            cycle.tickWeighted({ items }, { it.weight }, {
                now += if (it.name == "me") 20_000_000L else 2_000L
                if (it.name == "me") PollCycle.UNMEASURED else it.weight
            }, { ends++; false })
            // Never stretched past the normal cadence, which itself grows while idle.
            assertTrue(cycle.cycleLength <= cycle.sliceCount, "stretched to ${cycle.cycleLength}")
        }
    }

    @Test
    fun unmeasuredItemsDoNotMoveTheEstimate() {
        var now = 0L
        val cycle = PollCycle<Unit>(5, 5) { now }
        repeat(20) {
            cycle.tickWeighted({ listOf(Unit("me", 50_000)) }, { it.weight }, {
                now += 50_000_000L
                PollCycle.UNMEASURED
            }, { false })
        }
        assertEquals(PollCycle.INITIAL_NANOS_PER_WEIGHT, cycle.nanosPerWeight)
    }

    @Test
    fun deferredWorkYieldsWhenAnUnmeasuredReadUsedTheTick() {
        var now = 0L
        val cycle = PollCycle<String>(1, 1) { now }
        val polled = mutableListOf<String>()
        cycle.tickWeighted({ listOf("me") }, { 1 }, {
            polled.add(it)
            if (it == "me") {
                repeat(5) { i -> cycle.defer("range$i") }
                now += 10_000_000L // the ME read alone blows the 2ms tick
                PollCycle.UNMEASURED
            } else {
                now += 1_000L
                1
            }
        }, { false })
        // Only the guaranteed one deferred item ran on that tick.
        assertEquals(listOf("me", "range0"), polled)
    }

    // Restart

    @Test
    fun restartAbandonsCycleWithoutEndingIt() {
        val h = Harness(chests(10))
        h.runTicks(3)
        h.cycle.restart()
        assertEquals(0, h.cycle.cycleTick)
        assertTrue(h.cycleEndTicks.isEmpty())

        h.polled.clear()
        h.runOneCycle()
        assertEquals(chests(10), h.polled)
        assertEquals(2, h.beginCalls)
    }

    @Test
    fun restartKeepsCadenceAndEstimate() {
        val h = Harness(chests(3))
        repeat(2) { h.runOneCycle() }
        val slices = h.cycle.sliceCount
        val estimate = h.cycle.nanosPerWeight
        h.runTicks(1)
        h.cycle.restart()
        assertEquals(slices, h.cycle.sliceCount)
        assertEquals(estimate, h.cycle.nanosPerWeight)
    }

    @Test
    fun returnsTrueOnlyOnCycleEndWithChanges() {
        val h = Harness(chests(4), minSlices = 5, maxSlices = 5)
        h.endCallChange = { true }
        val results = (0 until 10).map { h.tick() }
        assertEquals(List(10) { it == 4 || it == 9 }, results)
        assertFalse(results.take(4).any { it })
    }
}
