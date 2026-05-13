package damien.nodeworks.screen

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global server-side craft queue storage, keyed by player UUID.
 * Survives menu close/reopen but not server restart.
 *
 * Each entry represents one craft request. The job is either pending (completedOps=0)
 * or complete (completedOps=1). Items go through the CPU buffer and are flushed to
 * network storage atomically when the whole job finishes, same as network:craft():store().
 */
object CraftQueueManager {

    data class CraftQueueEntry(
        val id: Int,
        /** Network the craft was submitted to. Used to scope queue display so a
         *  portable terminal opened on Network A doesn't show jobs queued from
         *  Network B. Null only for legacy entries from before the field existed. */
        val networkId: UUID?,
        val itemId: String,
        val itemName: String,
        var totalRequested: Int,
        /** Component patch of the requested variant. Empty for plain items
         *  (the common case). Preserved so the client's craft-queue row
         *  renders the right potion / dyed armor / enchantment variant
         *  instead of a generic placeholder. */
        val componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
        @Volatile var completedOps: Int = 0,
        var takenCount: Int = 0,
        var seenComplete: Boolean = false,
        var dirty: Boolean = true
    ) {
        val isComplete: Boolean get() = completedOps > 0
        val availableCount: Int get() = if (isComplete) maxOf(0, totalRequested - takenCount) else 0
    }

    private val queues = HashMap<UUID, MutableList<CraftQueueEntry>>()
    private val nextId = AtomicInteger(0)

    /** Full per-player queue across every network. Used by craft-completion
     *  callbacks (which know an entry's id, not its network) to update state. */
    fun getQueue(playerUUID: UUID): MutableList<CraftQueueEntry> {
        return queues.getOrPut(playerUUID) { mutableListOf() }
    }

    /** Per-player queue scoped to one network. The portable inventory terminal's
     *  pinned row uses this so players don't see jobs from networks they aren't
     *  currently looking at. */
    fun getQueueForNetwork(playerUUID: UUID, networkId: UUID?): List<CraftQueueEntry> {
        if (networkId == null) return emptyList()
        val queue = queues[playerUUID] ?: return emptyList()
        return queue.filter { it.networkId == networkId }
    }

    fun addEntry(
        playerUUID: UUID,
        networkId: UUID?,
        itemId: String,
        itemName: String,
        totalRequested: Int,
        componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ): CraftQueueEntry {
        val entry = CraftQueueEntry(
            id = nextId.incrementAndGet(),
            networkId = networkId,
            itemId = itemId,
            itemName = itemName,
            totalRequested = totalRequested,
            componentsPatch = componentsPatch,
        )
        getQueue(playerUUID).add(entry)
        return entry
    }

    /**
     * Available (ready but not taken) counts per itemId, scoped to one network.
     * Reserved items live in that network's CPU buffer / storage, so deducting
     * them from a *different* network's inventory display would be wrong.
     */
    fun getReservedCounts(playerUUID: UUID, networkId: UUID?): Map<String, Int> {
        if (networkId == null) return emptyMap()
        val queue = queues[playerUUID] ?: return emptyMap()
        val result = HashMap<String, Int>()
        for (entry in queue) {
            if (entry.networkId != networkId) continue
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

    /** Clear all queues (e.g., on server restart / new world load). */
    fun clearAll() {
        queues.clear()
        nextId.set(0)
    }
}
