package damien.nodeworks.platform

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoundRobinSpreadTest {

    private fun run(count: Long, caps: LongArray): Pair<LongArray, Int> {
        val plan = LongArray(caps.size)
        val last = RoundRobinSpread.plan(count, caps, plan)
        return plan to last
    }

    @Test
    fun evenSplitAcrossEqualTargets() {
        val (plan, last) = run(32, longArrayOf(64, 64, 64, 64))
        assertArrayEquals(longArrayOf(8, 8, 8, 8), plan)
        assertEquals(3, last)
    }

    @Test
    fun extrasGoToTheFirstTargetsInWindowOrder() {
        val (plan, last) = run(30, longArrayOf(64, 64, 64, 64))
        assertArrayEquals(longArrayOf(8, 8, 7, 7), plan)
        assertEquals(1, last)
    }

    @Test
    fun aSmallTargetIsToppedUpAndTheRestShareWhatIsLeft() {
        val (plan, _) = run(64, longArrayOf(5, 64, 64, 64))
        assertArrayEquals(longArrayOf(5, 20, 20, 19), plan)
        assertEquals(64, plan.sum())
    }

    @Test
    fun neverPlansMoreThanTheTargetsCanTake() {
        val (plan, _) = run(100, longArrayOf(10, 20))
        assertArrayEquals(longArrayOf(10, 20), plan)
    }

    @Test
    fun fewerItemsThanTargets() {
        val (plan, last) = run(2, longArrayOf(64, 64, 64, 64))
        assertArrayEquals(longArrayOf(1, 1, 0, 0), plan)
        assertEquals(1, last)
    }

    @Test
    fun aTargetWithNoRoomIsSkipped() {
        val (plan, last) = run(16, longArrayOf(0, 64, 0, 64))
        assertArrayEquals(longArrayOf(0, 8, 0, 8), plan)
        assertEquals(3, last)
    }

    @Test
    fun nothingFitsNowhere() {
        val (plan, last) = run(16, longArrayOf(0, 0))
        assertArrayEquals(longArrayOf(0, 0), plan)
        assertEquals(-1, last)
    }
}
