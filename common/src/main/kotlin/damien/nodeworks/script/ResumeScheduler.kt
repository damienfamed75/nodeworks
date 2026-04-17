package damien.nodeworks.script

import org.slf4j.LoggerFactory

/**
 * Global server-side scheduler for resumed crafting jobs.
 * Independent of any script engine — CPUs register their own polls here
 * when they load from NBT with pending operations.
 *
 * Ticked from the server tick event in both Fabric and NeoForge.
 */
object ResumeScheduler {
    private val logger = LoggerFactory.getLogger("nodeworks-resume")
    val scheduler = SchedulerImpl()
    private var initialized = false

    fun tick(tickCount: Long) {
        if (!initialized) {
            scheduler.initialize(tickCount)
            initialized = true
            // Clear stale craft queue entries from previous session
            damien.nodeworks.screen.CraftQueueManager.clearAll()
        }
        scheduler.tick(tickCount)
    }

    /** Call on server stop to reset for next world load. */
    fun onServerStop() {
        scheduler.clear()
        initialized = false
        damien.nodeworks.screen.CraftQueueManager.clearAll()
    }

    fun reset() {
        scheduler.clear()
        initialized = false
    }
}
