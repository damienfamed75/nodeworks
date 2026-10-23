package damien.nodeworks.screen

import damien.nodeworks.script.BufferKey
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
        var dirty: Boolean = true,
        /** Root items the CPU has already moved to storage while the plan still runs.
         *  They are the player's to pick up before the craft is done. */
        var deliveredCount: Int = 0,
    ) {
        val isComplete: Boolean get() = completedOps > 0
        /** Items in storage with the player's name on them: the whole request once
         *  complete, what trickled in before that. */
        val readyCount: Int get() = if (isComplete) totalRequested else deliveredCount.coerceIn(0, totalRequested)
        val availableCount: Int get() = maxOf(0, readyCount - takenCount)
    }

    /**
     * Settle an entry when its plan ends. Success completes it. Failure or cancel removes
     * it, unless items already trickled into storage and were not all picked up: those
     * stay the player's, so the entry becomes a complete one sized to what was made.
     */
    fun finishEntry(playerUUID: UUID, entry: CraftQueueEntry, success: Boolean) {
        if (success) {
            entry.completedOps = 1
        } else if (entry.deliveredCount > entry.takenCount) {
            entry.totalRequested = entry.deliveredCount
            entry.completedOps = 1
        } else {
            getQueue(playerUUID).remove(entry)
        }
        entry.dirty = true
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
     * Available (ready but not taken) counts per variant, keyed like the inventory
     * cache, scoped to one network.
     * Reserved items live in that network's CPU buffer / storage, so deducting
     * them from a *different* network's inventory display would be wrong.
     */
    fun getReservedCounts(playerUUID: UUID, networkId: UUID?): Map<damien.nodeworks.script.BufferKey.Key, Int> {
        if (networkId == null) return emptyMap()
        val queue = queues[playerUUID] ?: return emptyMap()
        val result = HashMap<damien.nodeworks.script.BufferKey.Key, Int>()
        for (entry in queue) {
            if (entry.networkId != networkId) continue
            if (entry.availableCount > 0) {
                val key = damien.nodeworks.script.BufferKey.Key(entry.itemId, damien.nodeworks.script.BufferKey.componentsHash(entry.componentsPatch))
                result.merge(key, entry.availableCount, Int::plus)
            }
        }
        return result
    }

    /** Players whose pinned row must be resent although no entry of theirs is dirty,
     *  because [settle] removed one. A removed entry cannot carry the dirty flag. */
    private val syncPending = HashSet<UUID>()

    fun hasSyncPending(playerUUID: UUID): Boolean = playerUUID in syncPending

    /** Ask for the player's pinned row to be resent on their terminal's next tick. A
     *  pickup marks this first, so a request the server refuses still answers the
     *  client's predicted count with the truth. */
    fun markSyncPending(playerUUID: UUID) {
        syncPending.add(playerUUID)
    }

    fun clearSyncPending(playerUUID: UUID) {
        syncPending.remove(playerUUID)
    }

    /** Poll cycle at which a variant on a network was first seen short, see [settle]. */
    private val shortSince = HashMap<Pair<UUID, BufferKey.Key>, Long>()

    private class Holder(val player: UUID, val entry: CraftQueueEntry, val key: BufferKey.Key)

    private fun keyOf(entry: CraftQueueEntry): BufferKey.Key =
        BufferKey.Key(entry.itemId, BufferKey.componentsHash(entry.componentsPatch))

    /**
     * Bring the ready reservations on [networkId], every player's together, in line with
     * what the network holds. A reservation only marks crafted items as a player's, the
     * items sit in ordinary storage, so a JEI grid fill, a shift-click from the grid, an
     * export or another player can take them without touching the queue. Summing over
     * all players matters: two players each promised two ingots from a stock of two
     * would both be offered them, and the second click would find nothing.
     *
     * With an [actor], the player whose own terminal just took items, the clamp runs at
     * once and that player's entries give first, then the oldest entries of anyone.
     * Without one this is the per-tick check, and a shortfall is acted on only after it
     * has persisted while a whole poll cycle completed, read as [cycle] advancing by two
     * since it was first seen. A chest whose chunk just unloaded, items riding a hopper
     * between two chests, or a cycle that caught a move half way all read short for a
     * moment and must not cost anyone a pinned entry. Entries emptied here are removed,
     * as a pickup would remove them, and their players are flagged for a resend.
     */
    fun settle(networkId: UUID?, stockOf: (BufferKey.Key) -> Long, cycle: Long, actor: UUID? = null) {
        if (networkId == null) return
        val totals = HashMap<BufferKey.Key, Long>()
        val holders = ArrayList<Holder>()
        for ((player, queue) in queues) {
            for (entry in queue) {
                if (entry.networkId != networkId || entry.availableCount <= 0) continue
                val key = keyOf(entry)
                totals.merge(key, entry.availableCount.toLong(), Long::plus)
                holders.add(Holder(player, entry, key))
            }
        }
        if (shortSince.isNotEmpty()) shortSince.keys.removeAll { it.first == networkId && it.second !in totals }
        if (totals.isEmpty()) return
        holders.sortWith(compareBy<Holder> { if (it.player == actor) 0 else 1 }.thenBy { it.entry.id })
        val touched = HashSet<UUID>()
        for ((key, total) in totals) {
            val mark = networkId to key
            var shortfall = total - stockOf(key).coerceAtLeast(0L)
            if (shortfall <= 0L) {
                shortSince.remove(mark)
                continue
            }
            if (actor == null) {
                val since = shortSince[mark]
                if (since == null) {
                    shortSince[mark] = cycle
                    continue
                }
                if (cycle < since + 2) continue
            }
            shortSince.remove(mark)
            for (holder in holders) {
                if (shortfall <= 0L) break
                val entry = holder.entry
                if (holder.key != key || entry.availableCount <= 0) continue
                val give = minOf(shortfall, entry.availableCount.toLong()).toInt()
                entry.takenCount += give
                entry.dirty = true
                shortfall -= give
                touched.add(holder.player)
            }
        }
        for (player in touched) {
            queues[player]?.removeAll { it.networkId == networkId && it.isComplete && it.availableCount <= 0 }
            syncPending.add(player)
        }
    }

    /** Remove all queue data for a player (e.g., on disconnect). */
    fun clearPlayer(playerUUID: UUID) {
        queues.remove(playerUUID)
        syncPending.remove(playerUUID)
    }

    /** Clear all queues (e.g., on server restart / new world load). */
    fun clearAll() {
        queues.clear()
        syncPending.clear()
        shortSince.clear()
        nextId.set(0)
    }
}
