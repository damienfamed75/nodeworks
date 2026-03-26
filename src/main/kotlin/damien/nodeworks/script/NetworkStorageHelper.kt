package damien.nodeworks.script

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkSnapshot
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel

/**
 * Utility for querying items across all Storage Cards on a network.
 * Storage cards are sorted by priority (descending) before scanning.
 */
object NetworkStorageHelper {

    /**
     * Returns all storage card snapshots from the network, sorted by priority (highest first).
     */
    fun getStorageCards(snapshot: NetworkSnapshot): List<CardSnapshot> {
        return snapshot.allCards()
            .filter { it.capability is StorageSideCapability }
            .sortedByDescending { (it.capability as StorageSideCapability).priority }
    }

    /**
     * Gets the item storage for a storage card snapshot.
     */
    fun getStorage(level: ServerLevel, card: CardSnapshot): Storage<ItemVariant>? {
        val cap = card.capability as? StorageSideCapability ?: return null
        return ItemStorage.SIDED.find(level, cap.adjacentPos, cap.defaultFace)
    }

    /**
     * Counts total items matching a filter across all Storage Cards on the network.
     */
    fun countItems(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Long {
        var total = 0L
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            for (view in storage) {
                if (!view.isResourceBlank && matchesFilter(view.resource, filter)) {
                    total += view.amount
                }
            }
        }
        return total
    }

    /**
     * Finds the first Storage Card that contains items matching the filter.
     * Returns the card snapshot or null if not found.
     */
    fun findItem(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): CardSnapshot? {
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            for (view in storage) {
                if (!view.isResourceBlank && matchesFilter(view.resource, filter)) {
                    return card
                }
            }
        }
        return null
    }

    private fun matchesFilter(variant: ItemVariant, filter: String): Boolean {
        if (filter == "*") return true
        val itemId = BuiltInRegistries.ITEM.getKey(variant.item)?.toString() ?: return false

        if (filter.startsWith("#")) {
            val tagId = filter.substring(1)
            val identifier = net.minecraft.resources.Identifier.tryParse(tagId) ?: return false
            val tagKey = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, identifier)
            return variant.item.builtInRegistryHolder().`is`(tagKey)
        }

        if (filter.startsWith("/") && filter.endsWith("/") && filter.length > 2) {
            val pattern = java.util.regex.Pattern.compile(filter.substring(1, filter.length - 1))
            return pattern.matcher(itemId).matches()
        }

        if (filter.endsWith(":*")) {
            val namespace = filter.removeSuffix(":*")
            return itemId.startsWith("$namespace:")
        }

        return itemId == filter
    }
}
