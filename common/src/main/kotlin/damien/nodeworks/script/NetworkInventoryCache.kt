package damien.nodeworks.script

import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel

/**
 * Cached inventory index across all Storage Cards in a network.
 * Built by full scan on construction, updated by deltas during operations.
 *
 * Supports serial-based change tracking for efficient delta sync to clients
 * (used by the Inventory Terminal).
 */
class NetworkInventoryCache(
    private val level: ServerLevel,
    private val snapshot: NetworkSnapshot
) {
    /** Entry with a stable serial number for delta sync. */
    data class SerialEntry(
        val serial: Long,
        val info: ItemInfo
    )

    private val items = LinkedHashMap<String, SerialEntry>()
    private var nextSerial = 1L

    /** Serials that have changed since last `consumeChanges()`. */
    private val changedSerials = mutableSetOf<Long>()
    /** Serials that were removed since last `consumeChanges()`. */
    private val removedSerials = mutableSetOf<Long>()

    init {
        fullScan()
    }

    private fun fullScan() {
        items.clear()
        nextSerial = 1L
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val found = PlatformServices.storage.findAllItemInfo(storage) { true }
            for (info in found) {
                val key = cacheKey(info.itemId, info.hasData)
                val existing = items[key]
                if (existing != null) {
                    items[key] = existing.copy(info = existing.info.copy(count = existing.info.count + info.count))
                } else {
                    items[key] = SerialEntry(nextSerial++, info)
                }
            }
        }
    }

    fun onInserted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        val existing = items[key]
        if (existing != null) {
            items[key] = existing.copy(info = existing.info.copy(count = existing.info.count + amount))
            changedSerials.add(existing.serial)
        } else {
            val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
            val serial = nextSerial++
            items[key] = SerialEntry(serial, ItemInfo(
                itemId = itemId,
                name = item.getName(net.minecraft.world.item.ItemStack(item)).string,
                count = amount,
                maxStackSize = item.defaultMaxStackSize,
                hasData = hasData
            ))
            changedSerials.add(serial)
        }
    }

    fun onExtracted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        val existing = items[key] ?: return
        val newCount = existing.info.count - amount
        if (newCount <= 0) {
            items.remove(key)
            removedSerials.add(existing.serial)
            changedSerials.remove(existing.serial)
        } else {
            items[key] = existing.copy(info = existing.info.copy(count = newCount))
            changedSerials.add(existing.serial)
        }
    }

    // --- Queries (used by scripting API and monitors) ---

    fun count(filter: String): Long {
        var total = 0L
        for ((_, entry) in items) {
            if (CardHandle.matchesFilter(entry.info.itemId, filter)) {
                total += entry.info.count
            }
        }
        return total
    }

    fun find(filter: String): ItemInfo? {
        for ((_, entry) in items) {
            if (CardHandle.matchesFilter(entry.info.itemId, filter)) {
                return entry.info
            }
        }
        return null
    }

    fun findAll(filter: String): List<ItemInfo> {
        return items.values.map { it.info }.filter { CardHandle.matchesFilter(it.itemId, filter) }
    }

    fun getAllItems(): Collection<ItemInfo> = items.values.map { it.info }

    // --- Delta sync for Inventory Terminal ---

    /** Get all entries for a full sync (on terminal open). */
    fun getAllEntries(): Collection<SerialEntry> = items.values

    /** Check if there are pending changes. */
    fun hasChanges(): Boolean = changedSerials.isNotEmpty() || removedSerials.isNotEmpty()

    /** Get changed entries and removed serials, then clear the change tracking. */
    fun consumeChanges(): Pair<List<SerialEntry>, List<Long>> {
        val changed = changedSerials.mapNotNull { serial ->
            items.values.find { it.serial == serial }
        }
        val removed = removedSerials.toList()
        changedSerials.clear()
        removedSerials.clear()
        return Pair(changed, removed)
    }

    private fun cacheKey(itemId: String, hasData: Boolean): String = "$itemId:$hasData"
}
