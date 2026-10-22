package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SchedulerImpl.hasActiveJobOnTargets] — the contention check behind the
 * greedy processing paths (surplus vacuuming, the post-completion sweep).
 * The invariants that keep a stocker loop's next craft from being starved:
 * active pulls on a shared machine register as contention, passive sweeps
 * never do, and a job never contends with itself.
 */
class SchedulerTargetsTest {

    private fun scheduler() = SchedulerImpl()

    private fun job(targets: Set<Long>, passive: Boolean = false) = SchedulerImpl.PendingJob(
        pollFn = { false },
        targets = targets,
        passive = passive,
    )

    @Test
    fun `empty targets never count as owned`() {
        val s = scheduler()
        s.addPendingJob(job(setOf(1L)))
        assertFalse(s.hasActiveJobOnTargets(emptySet()))
    }

    @Test
    fun `active pull on a shared machine is contention`() {
        val s = scheduler()
        s.addPendingJob(job(setOf(1L, 2L)))
        assertTrue(s.hasActiveJobOnTargets(setOf(2L)))
        assertFalse(s.hasActiveJobOnTargets(setOf(3L)))
    }

    @Test
    fun `a job does not contend with itself`() {
        val s = scheduler()
        val mine = job(setOf(1L))
        s.addPendingJob(mine)
        assertFalse(s.hasActiveJobOnTargets(setOf(1L), listOf(mine)))
    }

    @Test
    fun `passive sweeps are never contention`() {
        val s = scheduler()
        s.addPendingJob(job(setOf(1L), passive = true))
        assertFalse(s.hasActiveJobOnTargets(setOf(1L)))
    }

    @Test
    fun `exclusion is by identity so equal-looking jobs still contend`() {
        val s = scheduler()
        val mine = job(setOf(1L))
        val other = job(setOf(1L))
        s.addPendingJob(mine)
        s.addPendingJob(other)
        assertTrue(s.hasActiveJobOnTargets(setOf(1L), listOf(mine)))
    }
}
