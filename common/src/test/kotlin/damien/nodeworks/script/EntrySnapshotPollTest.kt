package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end simulation of how the inventory cache polls an ME network: the
 * first range freezes the network's types into a snapshot, later ranges read
 * that same snapshot across ticks, entries are reused through [EntryReuse],
 * and the result is staged in [SplitScanStaging] until the last range. The
 * network is a fake, [PollCycle], [EntryReuse] and [SplitScanStaging] are real.
 */
class EntrySnapshotPollTest {

    private companion object {
        const val RANGE = 1024
        const val NANOS_PER_ENTRY = 200L
    }

    private class Network(types: Int) {
        val counts = LinkedHashMap<String, Long>().apply { for (i in 0 until types) put("item$i", 1000L + i) }
        var attached = true
    }

    private class Entry(val key: String, var count: Long)

    private class Snapshot(val keys: Array<String>, val counts: LongArray, val pass: EntryReuse<String, Entry>.Pass)

    private data class Unit(val from: Int)

    private class FakeCache(val net: Network, budgetNanos: Long = 2_000_000L) {
        var now = 0L
        val cycle = PollCycle<Unit>(5, 60, budgetNanos) { now }
        val reuse = EntryReuse<String, Entry>()
        val staging = SplitScanStaging<String, String, Long> { a, b -> a + b }
        val front = HashMap<String, Long>()
        var entries: Map<String, Long> = emptyMap()
        var builds = 0
        var pass: Snapshot? = null
        val nanosPerTick = mutableListOf<Long>()
        var cycleEnds = 0

        init {
            // Same as the cache's creation-time scan seeding the estimate.
            cycle.seedCost(NANOS_PER_ENTRY * 1000L, 1000L)
        }

        fun tick() {
            val before = now
            cycle.tickWeighted(
                beginCycle = { front.clear(); staging.clear(); pass = null; if (net.attached) listOf(Unit(0)) else emptyList() },
                weightOf = { 1 },
                pollItem = ::poll,
                endCycle = { cycleEnds++; staging.clear(); if (cycle.cycleComplete) entries = HashMap(front); false },
            )
            nanosPerTick.add(now - before)
        }

        private fun poll(unit: Unit): Int {
            if (staging.isAbandoned("me")) return 0
            if (unit.from > 0 && !net.attached) {
                staging.abandon("me"); pass = null
                return 0
            }
            val snap = if (unit.from == 0) {
                Snapshot(net.counts.keys.toTypedArray(), net.counts.values.toLongArray(), reuse.startPass(net.counts.size)).also { pass = it }
            } else pass ?: return 0
            val end = minOf(snap.keys.size, unit.from + RANGE)
            for (i in unit.from until end) {
                val e = snap.pass.get(snap.keys[i]) { builds++; Entry(snap.keys[i], snap.counts[i]) }
                if (e.count != snap.counts[i]) e.count = snap.counts[i]
                staging.stage("me", e.key, e.count)
            }
            now += (end - unit.from) * NANOS_PER_ENTRY
            if (end >= snap.keys.size) {
                snap.pass.complete()
                staging.commit("me", front)
                pass = null
            } else {
                cycle.defer(Unit(end))
            }
            return end - unit.from
        }

        fun runUntilCycleEnds(): Int {
            val before = cycleEnds
            var ticks = 0
            while (cycleEnds == before) {
                tick(); ticks++
                check(ticks <= PollCycle.MAX_CYCLE_TICKS + 1)
            }
            return ticks
        }
    }

    @Test
    fun hugeNetworkIsSpreadWithinTheTickBudget() {
        // 100k types at 200ns is 20ms of reading, ten ticks of budget.
        val c = FakeCache(Network(100_000))
        val ticks = c.runUntilCycleEnds()
        assertTrue(ticks >= 10, "read in $ticks ticks")
        for (n in c.nanosPerTick) assertTrue(n <= 2_000_000L + 2 * RANGE * NANOS_PER_ENTRY, "a tick spent ${n}ns")
        assertEquals(100_000, c.entries.size)
    }

    @Test
    fun steadyNetworkBuildsNothingAfterTheFirstPass() {
        val c = FakeCache(Network(20_000))
        c.runUntilCycleEnds()
        assertEquals(20_000, c.builds)
        repeat(3) { c.runUntilCycleEnds() }
        assertEquals(20_000, c.builds, "unchanged types were rebuilt")
    }

    @Test
    fun countChangesShowUpWithoutRebuilding() {
        val net = Network(5_000)
        val c = FakeCache(net)
        c.runUntilCycleEnds()
        net.counts["item7"] = 1L
        c.runUntilCycleEnds()
        assertEquals(1L, c.entries["item7"])
        assertEquals(5_000, c.builds)
    }

    @Test
    fun passIsFrozenSoMidPassChangesWaitForTheNextCycle() {
        val net = Network(20_000)
        val c = FakeCache(net, budgetNanos = 1L)
        c.runUntilCycleEnds()
        c.tick()
        net.counts["item19999"] = 5L
        c.runUntilCycleEnds()
        assertEquals(1000L + 19_999, c.entries["item19999"], "a pass should see one moment")
        c.runUntilCycleEnds()
        assertEquals(5L, c.entries["item19999"])
    }

    @Test
    fun removedTypesLeaveTheCacheAndTheReuseSet() {
        val net = Network(3_000)
        val c = FakeCache(net)
        c.runUntilCycleEnds()
        net.counts.remove("item42")
        c.runUntilCycleEnds()
        assertEquals(null, c.entries["item42"])
        assertEquals(2_999, c.reuse.size)
    }

    @Test
    fun detachingMidPassDropsTheNetworkCleanlyAndKeepsReuse() {
        val net = Network(20_000)
        val c = FakeCache(net, budgetNanos = 1L)
        c.runUntilCycleEnds()
        c.tick(); c.tick()
        net.attached = false
        c.runUntilCycleEnds()
        assertTrue(c.entries.isEmpty(), "detached network still showed ${c.entries.size} types")
        assertEquals(20_000, c.reuse.size, "an abandoned pass must not wipe the reuse set")

        net.attached = true
        val buildsBefore = c.builds
        c.runUntilCycleEnds()
        assertEquals(20_000, c.entries.size)
        assertEquals(buildsBefore, c.builds, "reattaching rebuilt entries it could have reused")
    }
}
