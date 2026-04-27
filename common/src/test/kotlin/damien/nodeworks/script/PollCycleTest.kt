package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PollCycleTest {

    private class Harness<T>(
        items: List<T>,
        minSlices: Int = 5,
        maxSlices: Int = 60,
    ) {
        val cycle = PollCycle<T>(minSlices, maxSlices)
        val polled = mutableListOf<T>()
        val cycleEndTicks = mutableListOf<Int>()
        var beginCalls = 0
        var endCallChange: () -> Boolean = { false }
        var itemsProvider: () -> List<T> = { items }
        private var totalTicks = 0

        fun runTicks(n: Int) {
            repeat(n) {
                totalTicks++
                cycle.tick(
                    beginCycle = {
                        val items = itemsProvider()
                        // Increment after the call so the provider can read beginCalls
                        // as "0 during the first cycle's begin" without an off-by-one.
                        beginCalls++
                        items
                    },
                    pollItem = { polled.add(it) },
                    endCycle = {
                        cycleEndTicks.add(totalTicks)
                        endCallChange()
                    },
                )
            }
        }

        /** Drive one full cycle at the current sliceCount. Use this when the test
         *  cares about cycle boundaries, since sliceCount grows between cycles. */
        fun runOneCycle() = runTicks(cycle.sliceCount)
    }

    // Polling correctness

    @Test
    fun fullCycleVisitsEveryItemInOrder() {
        val items = (0 until 10).toList()
        val h = Harness(items)
        h.runTicks(5)
        assertEquals(items, h.polled)
    }

    @Test
    fun cycleSpansSliceCountTicksRegardlessOfItemCount() {
        val h = Harness(items = listOf(0, 1, 2), minSlices = 10)
        h.runTicks(10)
        assertEquals(listOf(0, 1, 2), h.polled)
        assertEquals(listOf(10), h.cycleEndTicks)
    }

    @Test
    fun emptyItemsListStillEndsCycleAfterSliceCountTicks() {
        val h = Harness(items = emptyList<Int>(), minSlices = 5)
        h.runTicks(5)
        assertEquals(emptyList<Int>(), h.polled)
        assertEquals(listOf(5), h.cycleEndTicks)
    }

    @Test
    fun multipleCyclesEachVisitEveryItem() {
        val items = (0 until 5).toList()
        val h = Harness(items, minSlices = 5)
        repeat(3) { h.runOneCycle() }
        assertEquals(items + items + items, h.polled)
        assertEquals(3, h.cycleEndTicks.size)
    }

    @Test
    fun moreItemsThanSliceCountStillVisitsAllInOneCycle() {
        // 12 items, sliceCount=5: chunk size = ceil(12/5) = 3.
        val items = (0 until 12).toList()
        val h = Harness(items, minSlices = 5)
        h.runTicks(5)
        assertEquals(items, h.polled)
    }

    // Snapshot stability

    @Test
    fun itemsSnapshottedAtCycleStartAreStableMidCycle() {
        val firstList = listOf(0, 1, 2, 3, 4)
        val secondList = listOf(100, 200, 300)
        val h = Harness(items = firstList, minSlices = 5)
        h.itemsProvider = { if (h.beginCalls == 0) firstList else secondList }
        repeat(2) { h.runOneCycle() }
        assertEquals(firstList + secondList, h.polled)
    }

    @Test
    fun beginCycleFiresOnceAtTheStartOfEachCycle() {
        val h = Harness(items = listOf(0, 1, 2), minSlices = 5)
        repeat(3) { h.runOneCycle() }
        assertEquals(3, h.beginCalls)
    }

    // Adaptive slice count

    @Test
    fun changeDetectedSnapsSliceCountToMin() {
        // Three idle cycles grow sliceCount: 5 -> 10 -> 15 -> 20.
        val h = Harness(items = listOf(0, 1, 2), minSlices = 5, maxSlices = 60)
        repeat(3) { h.runOneCycle() }
        assertEquals(20, h.cycle.sliceCount)

        h.endCallChange = { true }
        h.runOneCycle()
        assertEquals(5, h.cycle.sliceCount)
    }

    @Test
    fun idleCyclesGrowSliceCountByFiveTowardMax() {
        val h = Harness(items = listOf(0), minSlices = 5, maxSlices = 60)
        repeat(20) { h.runOneCycle() }
        assertEquals(60, h.cycle.sliceCount)
    }

    @Test
    fun hysteresisStaysFastForOneCycleAfterChange() {
        val h = Harness(items = listOf(0), minSlices = 5, maxSlices = 60)

        // First cycle is idle: hysteresis flag starts false, so we grow.
        h.runOneCycle()
        assertEquals(10, h.cycle.sliceCount)

        // Change snaps to MIN and arms the hysteresis flag.
        h.endCallChange = { true }
        h.runOneCycle()
        assertEquals(5, h.cycle.sliceCount)

        // First idle cycle after a change stays fast (hysteresis).
        h.endCallChange = { false }
        h.runOneCycle()
        assertEquals(5, h.cycle.sliceCount)

        // Second idle cycle: flag is clear, growth resumes.
        h.runOneCycle()
        assertEquals(10, h.cycle.sliceCount)
    }

    @Test
    fun adaptationUsesEndCycleResultNotIntermediateTicks() {
        var endCalls = 0
        val cycle = PollCycle<Int>(minSlices = 5, maxSlices = 5)
        repeat(15) {
            cycle.tick(
                beginCycle = { listOf(0, 1, 2) },
                pollItem = {},
                endCycle = { endCalls++; false },
            )
        }
        assertEquals(3, endCalls)
    }

    @Test
    fun tickReturnsTrueOnlyOnCycleEndTickWithChanges() {
        var changeReturn = false
        val cycle = PollCycle<Int>(minSlices = 5, maxSlices = 5)
        val results = mutableListOf<Boolean>()
        repeat(10) { i ->
            changeReturn = i == 4
            results.add(
                cycle.tick(
                    beginCycle = { listOf(0) },
                    pollItem = {},
                    endCycle = { changeReturn },
                )
            )
        }
        val expected = List(10) { i -> i == 4 }
        assertEquals(expected, results)
    }

    // Edge cases

    @Test
    fun cycleTickResetsToZeroAfterEndCycle() {
        val h = Harness(items = listOf(0, 1, 2), minSlices = 5)
        h.runTicks(5)
        assertEquals(0, h.cycle.cycleTick)
        h.runTicks(1)
        assertEquals(1, h.cycle.cycleTick)
    }

    @Test
    fun minSlicesEqualsMaxSlicesIsValid() {
        val h = Harness(items = listOf(0, 1), minSlices = 10, maxSlices = 10)
        repeat(10) { h.runOneCycle() }
        assertEquals(10, h.cycle.sliceCount)
    }

    @Test
    fun rejectsInvalidConstructorArgs() {
        try {
            PollCycle<Int>(minSlices = 0)
            assertTrue(false, "should have thrown for minSlices=0")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            PollCycle<Int>(minSlices = 10, maxSlices = 5)
            assertTrue(false, "should have thrown for maxSlices < minSlices")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun pollItemNotCalledOnCycleEndIdleTicks() {
        val h = Harness(items = listOf(0, 1, 2), minSlices = 10)
        h.runTicks(10)
        assertEquals(listOf(0, 1, 2), h.polled)
    }

    @Test
    fun adaptationCorrectlyHandlesChangeOnFirstCycle() {
        val h = Harness(items = listOf(0), minSlices = 5)
        h.endCallChange = { true }
        h.runTicks(5)
        assertEquals(5, h.cycle.sliceCount)
    }
}
