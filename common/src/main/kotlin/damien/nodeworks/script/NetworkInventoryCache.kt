package damien.nodeworks.script

import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel

/**
 * Cached inventory index across all Storage Cards in a network.
 * Built by full scan on construction, updated by deltas during operations.
 *
 * Key: "itemId:hasData" → aggregated ItemInfo with total count across all storage.
 * Queries are O(1) for exact lookups, O(n) for filter scans where n = unique item types.
 */
class NetworkInventoryCache(
    private val level: ServerLevel,
    private val snapshot: NetworkSnapshot
) {
    private val items = LinkedHashMap<String, ItemInfo>()

    init {
        fullScan()
    }

    private fun fullScan() {
        items.clear()
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val found = PlatformServices.storage.findAllItemInfo(storage) { true }
            for (info in found) {
                val key = cacheKey(info.itemId, info.hasData)
                val existing = items[key]
                if (existing != null) {
                    items[key] = existing.copy(count = existing.count + info.count)
                } else {
                    items[key] = info
                }
            }
        }
    }

    fun onInserted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        val existing = items[key]
        if (existing != null) {
            items[key] = existing.copy(count = existing.count + amount)
        } else {
            val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
            items[key] = ItemInfo(
                itemId = itemId,
                name = item.getName(net.minecraft.world.item.ItemStack(item)).string,
                count = amount,
                maxStackSize = item.defaultMaxStackSize,
                hasData = hasData
            )
        }
    }

    fun onExtracted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        val existing = items[key] ?: return
        val newCount = existing.count - amount
        if (newCount <= 0) {
            items.remove(key)
        } else {
            items[key] = existing.copy(count = newCount)
        }
    }

    fun count(filter: String): Long {
        var total = 0L
        for ((_, info) in items) {
            if (CardHandle.matchesFilter(info.itemId, filter)) {
                total += info.count
            }
        }
        return total
    }

    fun find(filter: String): ItemInfo? {
        for ((_, info) in items) {
            if (CardHandle.matchesFilter(info.itemId, filter)) {
                return info
            }
        }
        return null
    }

    fun findAll(filter: String): List<ItemInfo> {
        return items.values.filter { CardHandle.matchesFilter(it.itemId, filter) }
    }

    fun getAllItems(): Collection<ItemInfo> = items.values

    private fun cacheKey(itemId: String, hasData: Boolean): String = "$itemId:$hasData"
}
