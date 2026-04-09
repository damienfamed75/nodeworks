package damien.nodeworks.screen

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global server-side craft queue storage, keyed by player UUID.
 * Survives menu close/reopen but not server restart.
 */
object CraftQueueManager {

    data class CraftQueueEntry(
        val id: Int,
        val itemId: String,
        val itemName: String,
        val totalRequested: Int,
        val outputPerOperation: Int,
        @Volatile var completedOps: Int = 0,
        var takenCount: Int = 0,
        var seenComplete: Boolean = false,
        var dirty: Boolean = true
    ) {
        val readyCount: Int get() = completedOps * outputPerOperation
        val availableCount: Int get() = maxOf(0, readyCount - takenCount)
        val isComplete: Boolean get() = readyCount >= totalRequested
    }

    private val queues = HashMap<UUID, MutableList<CraftQueueEntry>>()
    private val nextId = AtomicInteger(0)

    fun getQueue(playerUUID: UUID): MutableList<CraftQueueEntry> {
        return queues.getOrPut(playerUUID) { mutableListOf() }
    }

    fun addEntry(
        playerUUID: UUID,
        itemId: String,
        itemName: String,
        totalRequested: Int,
        outputPerOperation: Int
    ): CraftQueueEntry {
        val entry = CraftQueueEntry(
            id = nextId.incrementAndGet(),
            itemId = itemId,
            itemName = itemName,
            totalRequested = totalRequested,
            outputPerOperation = outputPerOperation
        )
        getQueue(playerUUID).add(entry)
        return entry
    }

    /**
     * Get total available (ready but not taken) counts per itemId for a player.
     * Used by the menu to deduct reserved counts from inventory sync.
     */
    fun getReservedCounts(playerUUID: UUID): Map<String, Int> {
        val queue = queues[playerUUID] ?: return emptyMap()
        val result = HashMap<String, Int>()
        for (entry in queue) {
            if (entry.availableCount > 0) {
                result[entry.itemId] = (result[entry.itemId] ?: 0) + entry.availableCount
            }
        }
        return result
    }

    /** Remove all queue data for a player (e.g., on disconnect). */
    fun clearPlayer(playerUUID: UUID) {
        queues.remove(playerUUID)
    }
}
