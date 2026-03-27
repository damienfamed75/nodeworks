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
     * Resolution order: routes → onInsert callback → default (open storages only if routes exist).
     */
    fun insertItems(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        routeTable: RouteTable? = null,
        onInsertCallback: ((String, Long) -> ItemStorageHandle?)? = null
    ): Long {
        if (routeTable == null && onInsertCallback == null) {
            // No routing — fast path, use all storages
            return insertItemsDefault(level, snapshot, source, filter, maxCount)
        }
        return insertItemsRouted(level, snapshot, source, filter, maxCount, routeTable, onInsertCallback)
    }

    /**
     * Routes each unique item type through routes/callback individually.
     * Unmatched items go to open (unrouted) storages only.
     */
    private fun insertItemsRouted(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        routeTable: RouteTable?,
        onInsertCallback: ((String, Long) -> ItemStorageHandle?)?
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount
        val processedItems = mutableSetOf<String>()

        while (remaining > 0) {
            val itemId = PlatformServices.storage.findFirstItem(source) {
                CardHandle.matchesFilter(it, filter) && it !in processedItems
            } ?: break

            processedItems.add(itemId)

            val count = PlatformServices.storage.countItems(source) { it == itemId }
            val toMove = minOf(remaining, count)

            // 1. Check routes first (precomputed, fast)
            val routeTarget = routeTable?.findRouteTarget(itemId)
            if (routeTarget != null) {
                val moved = try {
                    PlatformServices.storage.moveItems(source, routeTarget, { it == itemId }, toMove)
                } catch (_: Exception) { 0L }
                totalMoved += moved
                remaining -= moved
                if (moved < toMove && routeTable != null) {
                    // Route target full — fall to open storages
                    val overflow = routeTable.insertDefault(source, itemId, toMove - moved)
                    totalMoved += overflow
                    remaining -= overflow
                }
                continue
            }

            // 2. Check onInsert callback
            val callbackTarget = onInsertCallback?.invoke(itemId, toMove)
            if (callbackTarget != null) {
                val moved = try {
                    PlatformServices.storage.moveItems(source, callbackTarget, { it == itemId }, toMove)
                } catch (_: Exception) { 0L }
                totalMoved += moved
                remaining -= moved
                if (moved < toMove) {
                    // Callback target full — fall to open storages or all storages
                    val fallbackMoved = if (routeTable != null) {
                        routeTable.insertDefault(source, itemId, toMove - moved)
                    } else {
                        insertItemsDefault(level, snapshot, source, itemId, toMove - moved)
                    }
                    totalMoved += fallbackMoved
                    remaining -= fallbackMoved
                }
                continue
            }

            // 3. No route or callback match — use open storages (or all if no routes)
            val defaultMoved = if (routeTable != null) {
                routeTable.insertDefault(source, itemId, toMove)
            } else {
                insertItemsDefault(level, snapshot, source, itemId, toMove)
            }
            totalMoved += defaultMoved
            remaining -= defaultMoved
        }

        return totalMoved
    }

    /** Default priority-based routing across ALL storage cards (no route filtering). */
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
            } catch (_: Exception) { 0L }
            totalMoved += moved
            remaining -= moved
        }
        return totalMoved
    }
}
