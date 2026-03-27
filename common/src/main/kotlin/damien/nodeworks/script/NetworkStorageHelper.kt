package damien.nodeworks.script

import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel

/**
 * Utility for querying items across all Storage Cards on a network.
 * Storage cards are sorted by priority (descending) before scanning.
 */
object NetworkStorageHelper {

    fun getStorageCards(snapshot: NetworkSnapshot): List<CardSnapshot> {
        return snapshot.allCards()
            .filter { it.capability is StorageSideCapability }
            .sortedByDescending { (it.capability as StorageSideCapability).priority }
    }

    fun getStorage(level: ServerLevel, card: CardSnapshot): ItemStorageHandle? {
        val cap = card.capability as? StorageSideCapability ?: return null
        return PlatformServices.storage.getItemStorage(level, cap.adjacentPos, cap.defaultFace)
    }

    fun countItems(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Long {
        var total = 0L
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            total += PlatformServices.storage.countItems(storage) { CardHandle.matchesFilter(it, filter) }
        }
        return total
    }

    /** Find the first item ID across all Storage Cards matching the filter. */
    fun findFirstItemId(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): String? {
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            val itemId = PlatformServices.storage.findFirstItem(storage) { CardHandle.matchesFilter(it, filter) }
            if (itemId != null) return itemId
        }
        return null
    }

    fun findItem(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): CardSnapshot? {
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            val count = PlatformServices.storage.countItems(storage) { CardHandle.matchesFilter(it, filter) }
            if (count > 0) return card
        }
        return null
    }

    /** Insert an ItemStack into the network's Storage Cards (highest priority first). Returns count inserted. */
    fun insertItemStack(level: ServerLevel, snapshot: NetworkSnapshot, stack: net.minecraft.world.item.ItemStack): Int {
        var remaining = stack.count
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0) break
            val storage = getStorage(level, card) ?: continue
            val inserted = PlatformServices.storage.insertItemStack(storage, stack.copyWithCount(remaining))
            remaining -= inserted
        }
        return stack.count - remaining
    }

    /**
     * Move items from a source storage into the network's Storage Cards.
     * If a routingCallback is provided, each unique item type is routed individually.
     * Falls back to default priority-based routing if callback returns null or no callback is set.
     */
    fun insertItems(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        routingCallback: ((String, Long) -> ItemStorageHandle?)? = null
    ): Long {
        if (routingCallback != null) {
            return insertItemsWithRouting(level, snapshot, source, filter, maxCount, routingCallback)
        }
        return insertItemsDefault(level, snapshot, source, filter, maxCount)
    }

    /**
     * Routes each unique item type through the callback individually.
     * Items the callback returns null for fall through to default priority routing.
     */
    private fun insertItemsWithRouting(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        routingCallback: (String, Long) -> ItemStorageHandle?
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount
        val processedItems = mutableSetOf<String>()

        // Keep finding and routing items until source is empty or maxCount reached
        while (remaining > 0) {
            // Find the next unprocessed item type in the source
            val itemId = PlatformServices.storage.findFirstItem(source) {
                CardHandle.matchesFilter(it, filter) && it !in processedItems
            } ?: break

            processedItems.add(itemId)

            // Ask the callback where this item should go
            val count = PlatformServices.storage.countItems(source) { it == itemId }
            val toMove = minOf(remaining, count)
            val targetStorage = routingCallback(itemId, toMove)

            if (targetStorage != null) {
                // Route to the callback's target
                val moved = try {
                    PlatformServices.storage.moveItems(source, targetStorage, { it == itemId }, toMove)
                } catch (_: Exception) { 0L }
                totalMoved += moved
                remaining -= moved
                // If target was full, fall through to default for this item
                if (moved < toMove) {
                    val defaultMoved = insertItemsDefault(level, snapshot, source, itemId, toMove - moved)
                    totalMoved += defaultMoved
                    remaining -= defaultMoved
                }
            } else {
                // Callback returned nil — use default routing for this item
                val moved = insertItemsDefault(level, snapshot, source, itemId, toMove)
                totalMoved += moved
                remaining -= moved
            }
        }

        return totalMoved
    }

    /** Default priority-based routing across all storage cards. */
    private fun insertItemsDefault(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0) break
            val destStorage = getStorage(level, card) ?: continue
            val moved = try {
                PlatformServices.storage.moveItems(
                    source, destStorage,
                    { CardHandle.matchesFilter(it, filter) },
                    remaining
                )
            } catch (_: Exception) {
                0L
            }
            totalMoved += moved
            remaining -= moved
        }
        return totalMoved
    }
}
