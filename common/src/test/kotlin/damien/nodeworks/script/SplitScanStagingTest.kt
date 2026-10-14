package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitScanStagingTest {

    private fun staging() = SplitScanStaging<String, String, Int> { a, b -> a + b }

    @Test
    fun stagedEntriesOnlyLandOnCommit() {
        val s = staging()
        val front = mutableMapOf<String, Int>()
        s.stage("ctrl", "stone", 64)
        s.stage("ctrl", "dirt", 10)
        assertTrue(front.isEmpty())
        assertTrue(s.hasPending)

        s.commit("ctrl", front)
        assertEquals(mapOf("stone" to 64, "dirt" to 10), front)
        assertFalse(s.hasPending)
    }

    @Test
    fun sameKeyAcrossRangesIsSummed() {
        val s = staging()
        val front = mutableMapOf<String, Int>()
        s.stage("ctrl", "stone", 64)
        s.stage("ctrl", "stone", 30)
        s.commit("ctrl", front)
        assertEquals(94, front["stone"])
    }

    @Test
    fun commitMergesWithWhatTheFrontAlreadyHolds() {
        // Another card already contributed stone this cycle.
        val s = staging()
        val front = mutableMapOf("stone" to 5)
        s.stage("ctrl", "stone", 64)
        s.commit("ctrl", front)
        assertEquals(69, front["stone"])
    }

    @Test
    fun abandonDiscardsWhatWasReadAndIgnoresLaterRanges() {
        val s = staging()
        val front = mutableMapOf<String, Int>()
        s.stage("ctrl", "stone", 64)
        s.abandon("ctrl")
        s.stage("ctrl", "stone", 64)
        s.commit("ctrl", front)
        assertTrue(front.isEmpty())
        assertTrue(s.isAbandoned("ctrl"))
        assertFalse(s.hasPending)
    }

    @Test
    fun storagesAreIndependent() {
        val s = staging()
        val front = mutableMapOf<String, Int>()
        s.stage("a", "stone", 1)
        s.stage("b", "stone", 2)
        s.abandon("a")
        s.commit("b", front)
        assertEquals(mapOf("stone" to 2), front)
    }

    @Test
    fun commitWithNothingStagedIsANoOp() {
        val s = staging()
        val front = mutableMapOf("stone" to 1)
        s.commit("nothing", front)
        assertEquals(mapOf("stone" to 1), front)
    }

    @Test
    fun clearResetsAbandonedAndPending() {
        val s = staging()
        s.stage("a", "stone", 1)
        s.abandon("b")
        s.clear()
        assertFalse(s.hasPending)
        assertFalse(s.isAbandoned("b"))
    }
}
