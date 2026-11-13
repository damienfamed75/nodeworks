package damien.nodeworks.script.cpu

import damien.nodeworks.script.CraftingHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandlerActivityTest {

    private val key = "furnace" to 1

    @BeforeEach
    fun clear() = HandlerActivity.reset()

    @Test
    fun `a handler with a running job is busy until the job completes`() {
        val job = CraftingHelper.PendingHandlerJob()
        HandlerActivity.track(key, job, now = 100)
        assertTrue(HandlerActivity.busy(key, now = 150))
        job.complete(true)
        assertFalse(HandlerActivity.busy(key, now = 200))
    }

    @Test
    fun `a finished job records progress when it is next observed`() {
        val job = CraftingHelper.PendingHandlerJob()
        HandlerActivity.track(key, job, now = 100)
        assertEquals(Long.MIN_VALUE, HandlerActivity.lastProgressTick(key, now = 120))
        job.complete(true)
        assertEquals(300L, HandlerActivity.lastProgressTick(key, now = 300))
        assertEquals(300L, HandlerActivity.lastProgressTick(key, now = 400))
    }

    @Test
    fun `a failed job frees the handler without counting as progress`() {
        val job = CraftingHelper.PendingHandlerJob()
        HandlerActivity.track(key, job, now = 100)
        job.complete(false)
        assertFalse(HandlerActivity.busy(key, now = 200))
        assertEquals(Long.MIN_VALUE, HandlerActivity.lastProgressTick(key, now = 200))
    }

    @Test
    fun `an unknown handler is idle`() {
        assertFalse(HandlerActivity.busy("nowhere", now = 0))
        assertEquals(Long.MIN_VALUE, HandlerActivity.lastProgressTick("nowhere", now = 0))
    }

    @Test
    fun `tracking the same job twice keeps one entry`() {
        val job = CraftingHelper.PendingHandlerJob()
        HandlerActivity.track(key, job, now = 1)
        HandlerActivity.track(key, job, now = 2)
        job.complete(true)
        assertFalse(HandlerActivity.busy(key, now = 3))
    }
}
