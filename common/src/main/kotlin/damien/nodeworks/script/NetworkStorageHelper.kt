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
}
