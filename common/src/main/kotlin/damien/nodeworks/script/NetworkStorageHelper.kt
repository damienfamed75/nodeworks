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

    /** Move items from a source storage into the network's Storage Cards (highest priority first). */
    fun insertItems(
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
