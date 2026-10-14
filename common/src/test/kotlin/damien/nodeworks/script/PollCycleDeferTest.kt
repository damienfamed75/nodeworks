package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PollCycle.defer] plus an end-to-end simulation of the inventory cache's
 * range scanning: a storage of unknown size read [RANGE] slots at a time,
 * staged in a real [SplitScanStaging], and committed or abandoned. The storage
 * model is a fake, the cycle and staging are the real classes.
 */
class PollCycleDeferTest {

    private companion object {
        const val RANGE = 1024
    }

    /** Fake storage: a slot count, one item id per slot, and an attached flag. */
    private class Storage(var slots: Int, val itemOf: (Int) -> String = { "stone" }) {
        var attached = true
    }

    /** Mirrors a cache poll unit: [until] of MAX_VALUE is open-ended. */
    private data class Unit(val storage: String, val from: Int, val until: Int = Int.MAX_VALUE)

    private class FakeCache(val storages: Map<String, Storage>, budgetNanos: Long = 2_000_000L) {
        var now = 0L
        val cycle = PollCycle<Unit>(5, 60, budgetNanos) { now }
        val staging = SplitScanStaging<String, String, Long> { a, b -> a + b }
        val front = HashMap<String, Long>()
        /** What the last completed cycle saw, the "entries" a terminal displays. */
        var entries: Map<String, Long> = emptyMap()
        var cycleEnds = 0
        var incompleteEnds = 0
        val slotsReadPerTick = mutableListOf<Int>()
        var nanosPerSlot = 100L
        private var readThisTick = 0

        fun tick() {
            readThisTick = 0
            cycle.tickWeighted(
                beginCycle = {
                    front.clear()
                    staging.clear()
                    // Like network discovery, a detached card isn't in the next cycle.
                    storages.filterValues { it.attached }.keys.map { Unit(it, 0) }
                },
                weightOf = { 1 },
                pollItem = ::poll,
                endCycle = {
                    cycleEnds++
                    staging.clear()
                    if (!cycle.cycleComplete) {
                        incompleteEnds++
                    } else {
                        entries = HashMap(front)
                    }
                    false
                },
            )
            slotsReadPerTick.add(readThisTick)
        }

        private fun poll(unit: Unit): Int {
            val s = storages.getValue(unit.storage)
            if (staging.isAbandoned(unit.storage)) return 0
            if (unit.from > 0 && !s.attached) {
                staging.abandon(unit.storage)
                return 0
            }
            val end = if (unit.until == Int.MAX_VALUE) minOf(s.slots, unit.from + RANGE) else minOf(unit.until, s.slots)
            val work = (end - unit.from).coerceAtLeast(0)
            readThisTick += work
            now += work * nanosPerSlot
            val read = HashMap<String, Long>()
            for (slot in unit.from until end) read.merge(s.itemOf(slot), 1L, Long::plus)
            if (unit.from == 0 && end >= s.slots) {
                for ((k, v) in read) front.merge(k, v, Long::plus)
            } else {
                for ((k, v) in read) staging.stage(unit.storage, k, v)
                if (end >= s.slots) staging.commit(unit.storage, front)
                else if (unit.until == Int.MAX_VALUE) cycle.defer(Unit(unit.storage, end))
            }
            return work
        }

        fun runUntilCycleEnds(): Int {
            val before = cycleEnds
            var ticks = 0
            while (cycleEnds == before) {
                tick()
                ticks++
                check(ticks <= PollCycle.MAX_CYCLE_TICKS + 1) { "cycle never ended" }
            }
            return ticks
        }
    }

    // Deferral basics

    @Test
    fun deferredItemsRunBeforeTheCycleEnds() {
        val polled = mutableListOf<String>()
        val cycle = PollCycle<String>(minSlices = 1, maxSlices = 1)
        var ends = 0
        repeat(3) {
            cycle.tickWeighted(
                beginCycle = { listOf("first") },
                weightOf = { 1 },
                pollItem = {
                    polled.add(it)
                    if (it == "first") cycle.defer("second")
                    1
                },
                endCycle = { ends++; false },
            )
        }
        // The deferred item runs after the planned one, before the cycle ends.
        assertEquals(listOf("first", "second", "first", "second"), polled.take(4))
        assertTrue(ends >= 1)
    }

    @Test
    fun atLeastOneDeferredItemRunsPerTickEvenOverBudget() {
        val cycle = PollCycle<Int>(minSlices = 1, maxSlices = 1, tickBudgetNanos = 1L) { 0L }
        var deferredRuns = 0
        cycle.tickWeighted({ listOf(0) }, { 1 }, { if (it == 0) repeat(3) { i -> cycle.defer(i + 1) } else deferredRuns++; 1 }, { false })
        repeat(3) { cycle.tickWeighted({ listOf(0) }, { 1 }, { deferredRuns++; 1 }, { false }) }
        assertTrue(deferredRuns >= 3, "deferred items stalled: $deferredRuns ran")
    }

    @Test
    fun selfDeferringWithoutProgressCannotSpinATick() {
        val cycle = PollCycle<Int>(minSlices = 1, maxSlices = 1, tickBudgetNanos = Long.MAX_VALUE / 4) { 0L }
        var polls = 0
        cycle.tickWeighted({ listOf(0) }, { 1 }, { polls++; cycle.defer(0); 0 }, { false })
        assertTrue(polls <= PollCycle.MAX_DEFERRED_PER_TICK + 1, "one tick ran $polls polls")
    }

    @Test
    fun restartDropsDeferredWork() {
        val cycle = PollCycle<String>(minSlices = 5, maxSlices = 5)
        val polled = mutableListOf<String>()
        cycle.tickWeighted({ listOf("a") }, { 1 }, { polled.add(it); if (it == "a") cycle.defer("late"); 1 }, { false })
        cycle.restart()
        polled.clear()
        repeat(5) { cycle.tickWeighted({ listOf("b") }, { 1 }, { polled.add(it); 1 }, { false }) }
        assertEquals(listOf("b"), polled)
        assertTrue(cycle.cycleComplete)
    }

    // End-to-end range scanning

    @Test
    fun unknownGiantStorageStaysWithinTheTickBudget() {
        // 300k slots at 1000ns is 300ms of reading, far over a 2ms tick. A
        // whole read would put all of it on one tick.
        val c = FakeCache(mapOf("ctrl" to Storage(300_000), "chest" to Storage(27)))
        c.nanosPerSlot = 1000L
        c.cycle.seedCost(1_000_000L, 1000L)
        val ticks = c.runUntilCycleEnds()
        assertTrue(ticks > 100, "read in $ticks ticks, expected it spread out")
        for (slots in c.slotsReadPerTick) {
            val nanos = slots * 1000L
            assertTrue(nanos <= 2_000_000L + 2 * RANGE * 1000L, "a tick spent ${nanos}ns")
        }
        assertEquals(300_027L, c.entries["stone"])
    }

    @Test
    fun cheapStorageIsNotSlowedToOneRangePerTick() {
        // At 10ns a slot the whole 30k fits in one tick's budget, deferral
        // shouldn't stretch it to 30 ticks.
        val c = FakeCache(mapOf("ctrl" to Storage(30_000)))
        c.nanosPerSlot = 10L
        c.cycle.seedCost(10_000L, 1000L)
        assertEquals(5, c.runUntilCycleEnds())
        assertEquals(30_000L, c.entries["stone"])
    }

    @Test
    fun splitStorageCountsOnlyOnceFullyRead() {
        val c = FakeCache(mapOf("ctrl" to Storage(10_000)), budgetNanos = 1L)
        // Mid-scan, nothing from the controller is visible yet.
        repeat(3) { c.tick() }
        assertTrue(c.entries.isEmpty())
        c.runUntilCycleEnds()
        assertEquals(10_000L, c.entries["stone"])
    }

    @Test
    fun removingCardMidScanDropsItsItemsCleanly() {
        val ctrl = Storage(10_000) { "diamond" }
        // One range per tick so the removal lands mid-scan.
        val c = FakeCache(mapOf("ctrl" to ctrl, "chest" to Storage(27) { "stone" }), budgetNanos = 1L)
        c.runUntilCycleEnds()
        assertEquals(10_000L, c.entries["diamond"])

        // Pull the card a few ranges into the next cycle.
        c.tick(); c.tick()
        ctrl.attached = false
        c.runUntilCycleEnds()

        assertEquals(null, c.entries["diamond"], "removed card's items should be gone, not partial")
        assertEquals(27L, c.entries["stone"], "other storages are unaffected")
        assertEquals(0, c.incompleteEnds)
    }

    @Test
    fun abandonedStorageStopsBeingReadForTheRestOfTheCycle() {
        val ctrl = Storage(50_000)
        val c = FakeCache(mapOf("ctrl" to ctrl), budgetNanos = 1L)
        c.tick()
        ctrl.attached = false
        val readBefore = c.slotsReadPerTick.sum()
        c.runUntilCycleEnds()
        assertEquals(readBefore, c.slotsReadPerTick.sum(), "kept reading a detached storage")
    }

    @Test
    fun storageShrinkingMidScanEndsItsReadEarly() {
        val ctrl = Storage(10_000)
        val c = FakeCache(mapOf("ctrl" to ctrl), budgetNanos = 1L)
        c.tick(); c.tick()
        ctrl.slots = 1500
        c.runUntilCycleEnds()
        assertTrue(c.entries.getValue("stone") in 1500L..10_000L)
        c.runUntilCycleEnds()
        assertEquals(1500L, c.entries["stone"])
    }

    @Test
    fun storageGrowingMidScanIsReadToItsNewEnd() {
        val ctrl = Storage(3000)
        val c = FakeCache(mapOf("ctrl" to ctrl), budgetNanos = 1L)
        c.tick()
        ctrl.slots = 6000
        c.runUntilCycleEnds()
        assertEquals(6000L, c.entries["stone"])
    }

    @Test
    fun hardCapMarksCycleIncompleteAndKeepsLastGoodState() {
        // Over budget so only one range drains per tick, and more ranges than the cap allows.
        val huge = Storage(RANGE * (PollCycle.MAX_CYCLE_TICKS + 50))
        val c = FakeCache(mapOf("huge" to huge, "chest" to Storage(27) { "stone" }), budgetNanos = 1L)
        c.entries = mapOf("previous" to 1L)
        c.runUntilCycleEnds()
        assertEquals(1, c.incompleteEnds)
        assertEquals(mapOf("previous" to 1L), c.entries, "incomplete cycle must not replace the last good state")
    }
}
