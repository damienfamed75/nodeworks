package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end-style tests for the PollCycle/cache integration.
 *
 * The real [NetworkInventoryCache] can't be unit-tested without spinning up
 * Minecraft. [FakeCache] mirrors its integration shape: init runs a synchronous
 * full scan, tick() delegates to PollCycle with the same three callbacks, and
 * endCycle applies the buffer diff while honoring the dirty-keys guard.
 */
class PollCycleIntegrationTest {

    private class Card(val name: String, var contents: Map<String, Int>)

    private class FakeCache(
        private var cards: () -> List<Card>,
        minSlices: Int = 5,
        maxSlices: Int = 60,
    ) {
        private val pollCycle = PollCycle<Card>(minSlices, maxSlices)
        private val frontBuffer = mutableMapOf<String, Int>()

        val entries = mutableMapOf<String, Int>()

        private val dirtyKeys = mutableSetOf<String>()

        init {
            val initialCards = beginCycle()
            for (card in initialCards) pollCard(card)
            endCycle()
        }

        fun tick(): Boolean = pollCycle.tick(
            beginCycle = ::beginCycle,
            pollItem = ::pollCard,
            endCycle = ::endCycle,
        )

        /** Stand-in for [NetworkInventoryCache.onInserted] / `onExtracted`: applies
         *  an immediate count delta and flags the key dirty so the cycle's diff
         *  doesn't revert it. */
        fun simulateHookDelta(itemId: String, delta: Int) {
            dirtyKeys.add(itemId)
            entries[itemId] = (entries[itemId] ?: 0) + delta
            if ((entries[itemId] ?: 0) <= 0) entries.remove(itemId)
        }

        private fun beginCycle(): List<Card> {
            frontBuffer.clear()
            return cards()
        }

        private fun pollCard(card: Card) {
            for ((id, count) in card.contents) {
                frontBuffer[id] = (frontBuffer[id] ?: 0) + count
            }
        }

        private fun endCycle(): Boolean {
            var changed = false
            // Mirrors NetworkInventoryCache.applyDiff: iterate entries, not a
            // back buffer, so orphans (entries added via simulateHookDelta but
            // never seen in any frontBuffer) get evicted.
            for (key in entries.keys.toSet()) {
                if (key in dirtyKeys) continue
                if (key !in frontBuffer) {
                    if (entries.remove(key) != null) changed = true
                }
            }
            for ((key, count) in frontBuffer) {
                if (key in dirtyKeys) continue
                val existing = entries[key]
                if (existing == null || existing != count) {
                    entries[key] = count
                    changed = true
                }
            }
            dirtyKeys.clear()
            return changed
        }
    }

    @Test
    fun entriesPopulatedAfterInit() {
        val cards = listOf(
            Card("io_1", mapOf("minecraft:cobblestone" to 64, "minecraft:dirt" to 32)),
            Card("io_2", mapOf("minecraft:cobblestone" to 16)),
        )
        val cache = FakeCache({ cards })
        assertEquals(2, cache.entries.size)
        assertEquals(80, cache.entries["minecraft:cobblestone"])
        assertEquals(32, cache.entries["minecraft:dirt"])
    }

    @Test
    fun entriesPersistThroughOneIdleCycle() {
        val cards = listOf(
            Card("io_1", mapOf("minecraft:cobblestone" to 64)),
            Card("io_2", mapOf("minecraft:dirt" to 32)),
        )
        val cache = FakeCache({ cards })
        repeat(5) { cache.tick() }
        assertEquals(2, cache.entries.size)
        assertEquals(64, cache.entries["minecraft:cobblestone"])
        assertEquals(32, cache.entries["minecraft:dirt"])
    }

    @Test
    fun entriesPersistAcrossManyIdleCycles() {
        val cards = listOf(
            Card("io_1", mapOf("minecraft:iron_ingot" to 100)),
        )
        val cache = FakeCache({ cards })
        repeat(50) { cache.tick() }
        assertEquals(1, cache.entries.size)
        assertEquals(100, cache.entries["minecraft:iron_ingot"])
    }

    @Test
    fun entriesUpdateWhenCardContentsChange() {
        val card = Card("io_1", mapOf("minecraft:cobblestone" to 64))
        val cache = FakeCache({ listOf(card) })
        assertEquals(64, cache.entries["minecraft:cobblestone"])

        card.contents = mapOf("minecraft:cobblestone" to 128)
        repeat(5) { cache.tick() }
        assertEquals(128, cache.entries["minecraft:cobblestone"])
    }

    @Test
    fun entriesShrinkWhenCardRemovedFromList() {
        val cardA = Card("io_1", mapOf("minecraft:cobblestone" to 64))
        val cardB = Card("io_2", mapOf("minecraft:dirt" to 32))
        var current = listOf(cardA, cardB)
        val cache = FakeCache({ current })
        assertEquals(2, cache.entries.size)

        current = listOf(cardA)
        repeat(5) { cache.tick() }
        assertEquals(1, cache.entries.size)
        assertEquals(64, cache.entries["minecraft:cobblestone"])
        assertTrue("minecraft:dirt" !in cache.entries)
    }

    @Test
    fun emptyCardListAfterInitDoesNotEraseEntries() {
        val cards = mutableListOf(
            Card("io_1", mapOf("minecraft:cobblestone" to 64)),
        )
        var current: List<Card> = cards
        val cache = FakeCache({ current })
        assertEquals(1, cache.entries.size)

        current = emptyList()
        repeat(5) { cache.tick() }
        assertTrue(cache.entries.isEmpty())
    }

    @Test
    fun deltaHookProtectsAgainstStaleSlicedPoll() {
        // Regression for the Inventory Terminal flicker: a tick polls pre-extract
        // count, the user clicks extract mid-cycle (hook updates entries), the
        // cycle ends with stale front data. Without the dirty-keys guard,
        // applyDiff would revert entries back to the pre-extract count.
        val card = Card("io_1", mapOf("minecraft:cobblestone" to 200))
        val cache = FakeCache({ listOf(card) }, minSlices = 5, maxSlices = 60)
        assertEquals(200, cache.entries["minecraft:cobblestone"])

        cache.tick()

        card.contents = mapOf("minecraft:cobblestone" to 136)
        cache.simulateHookDelta("minecraft:cobblestone", -64)
        assertEquals(136, cache.entries["minecraft:cobblestone"])

        repeat(4) { cache.tick() }
        assertEquals(136, cache.entries["minecraft:cobblestone"])

        repeat(25) { cache.tick() }
        assertEquals(136, cache.entries["minecraft:cobblestone"])
    }

    @Test
    fun deltaHookSurvivesAnInsertMidCycle() {
        val card = Card("io_1", mapOf("minecraft:cobblestone" to 100))
        val cache = FakeCache({ listOf(card) }, minSlices = 5)

        cache.tick()

        card.contents = mapOf("minecraft:cobblestone" to 164)
        cache.simulateHookDelta("minecraft:cobblestone", 64)

        repeat(4) { cache.tick() }
        assertEquals(164, cache.entries["minecraft:cobblestone"])
    }

    @Test
    fun deltaHookProtectsAgainstStalePollOnNewItemEntry() {
        // Edge: extract-all removes the entry, but a stale poll still has it.
        // Without the guard, applyDiff would re-add it as a phantom new entry.
        val card = Card("io_1", mapOf("minecraft:diamond" to 5))
        val cache = FakeCache({ listOf(card) }, minSlices = 5)
        assertEquals(5, cache.entries["minecraft:diamond"])

        cache.tick()

        card.contents = emptyMap()
        cache.simulateHookDelta("minecraft:diamond", -5)
        assertTrue("minecraft:diamond" !in cache.entries)

        repeat(4) { cache.tick() }
        assertTrue("minecraft:diamond" !in cache.entries)
    }

    @Test
    fun moreCardsThanSliceCountStillVisitsAllInOneCycle() {
        val cards = (0 until 12).map { i ->
            Card("io_$i", mapOf("test:item_$i" to (i + 1) * 10))
        }
        val cache = FakeCache({ cards })
        assertEquals(12, cache.entries.size)
        for (i in 0 until 12) {
            assertEquals((i + 1) * 10, cache.entries["test:item_$i"])
        }
    }

    @Test
    fun orphanedHookEntriesGetEvicted() {
        // Regression: an item piped through the pool faster than the poll runs
        // (importer:from(net):to("chest") on a script that also imports into
        // the pool) used to leak. The hook fired onInserted every tick, the
        // item left before any poll caught it, and the back-vs-front diff
        // never had it in either buffer so the orphan stayed in entries
        // forever. The fix iterates entries.keys directly and evicts anything
        // the latest poll didn't see (and isn't dirty-protected this cycle).
        val card = Card("io_pool", emptyMap())
        val cache = FakeCache({ listOf(card) })
        // Simulate one onInserted (item briefly in pool) without ever appearing
        // in frontBuffer, this is the orphan we want to evict.
        cache.simulateHookDelta("nodeworks:raw_iron", 5)
        assertEquals(5, cache.entries["nodeworks:raw_iron"])

        // PollCycle grows the slice budget on idle cycles, so 50 ticks easily
        // covers two cycle boundaries. First cycle's diff sees the dirty mark
        // and skips, second cycle's diff finds the entry orphaned (not dirty,
        // not in frontBuffer) and evicts.
        repeat(50) { cache.tick() }
        assertTrue("nodeworks:raw_iron" !in cache.entries)
    }
}
