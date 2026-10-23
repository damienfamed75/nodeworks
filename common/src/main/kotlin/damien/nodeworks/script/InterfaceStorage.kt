package damien.nodeworks.script

import damien.nodeworks.block.entity.InterfaceBlockEntity
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

/**
 * The storage handle a card gets when it faces an [InterfaceBlockEntity] of another
 * network. The storage service routes every call on it here, the way it routes an
 * ME network to its own backend, so the rest of the mod sees an ordinary storage.
 */
class InterfaceItemStorageHandle(val entity: InterfaceBlockEntity) : ItemStorageHandle

/**
 * Storage operations over an Interface. Reads come from the network's native index, so
 * they are map lookups and never slot walks. Writes and extractions go through the
 * entity, which keeps both of its network's caches current. There are no slots, so the
 * handle reads as whole-only, like an ME network.
 */
object InterfaceStorage {

    private fun idOf(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun entries(h: InterfaceItemStorageHandle): List<ItemInfo> = h.entity.exposedEntries()

    fun countItems(h: InterfaceItemStorageHandle, filter: (String) -> Boolean): Long =
        entries(h).sumOf { if (filter(it.itemId)) it.count else 0L }

    fun findFirstItem(h: InterfaceItemStorageHandle, filter: (String) -> Boolean): String? =
        entries(h).firstOrNull { filter(it.itemId) }?.itemId

    fun findFirstItemInfo(h: InterfaceItemStorageHandle, filter: (String) -> Boolean): ItemInfo? =
        entries(h).firstOrNull { filter(it.itemId) }

    fun findAllItemInfo(h: InterfaceItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> =
        entries(h).filter { filter(it.itemId) }

    fun countStacksByPredicate(h: InterfaceItemStorageHandle, filter: (ItemStack) -> Boolean): Long {
        var total = 0L
        for (info in entries(h)) {
            val stack = h.entity.representative(info) ?: continue
            if (filter(stack)) total += info.count
        }
        return total
    }

    /** The first entry a scan would take, sized to one stack. Cursors do not apply. */
    fun peekStack(h: InterfaceItemStorageHandle, filter: (ItemStack) -> Boolean): ItemStack? {
        for (info in entries(h)) {
            val stack = h.entity.representative(info) ?: continue
            if (!filter(stack)) continue
            stack.count = minOf(info.count, stack.maxStackSize.toLong()).toInt()
            return stack
        }
        return null
    }

    fun extractStacksByPredicate(h: InterfaceItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long): List<ItemStack> =
        h.entity.extractFromOutside(filter, maxCount)

    fun extractItemStacksMatching(h: InterfaceItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): List<ItemStack> =
        h.entity.extractFromOutside({ filter(idOf(it)) }, maxCount)

    fun extractItems(h: InterfaceItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long =
        extractItemStacksMatching(h, filter, maxCount).sumOf { it.count.toLong() }

    fun insertItemStack(h: InterfaceItemStorageHandle, stack: ItemStack): Int = h.entity.insertFromOutside(stack)

    fun simulateInsertStack(h: InterfaceItemStorageHandle, stack: ItemStack): Long = h.entity.simulateInsertFromOutside(stack)

    fun simulateInsertItem(h: InterfaceItemStorageHandle, item: net.minecraft.world.item.Item, maxCount: Long): Long {
        var room = 0L
        var left = maxCount
        // Probed a stack at a time, the way the storage behind it is filled.
        while (left > 0L) {
            val batch = minOf(left, item.getDefaultMaxStackSize().toLong()).toInt()
            val fits = h.entity.simulateInsertFromOutside(ItemStack(item, batch))
            room += fits
            left -= batch
            if (fits < batch) break
        }
        return room
    }

    /** All or nothing. The simulation is checked first, and a landing that still falls
     *  short is pulled back so the caller's source is never left half emptied. */
    fun tryInsertAll(h: InterfaceItemStorageHandle, item: net.minecraft.world.item.Item, count: Long): Boolean {
        if (count <= 0L) return true
        if (simulateInsertItem(h, item, count) < count) return false
        var inserted = 0L
        var left = count
        while (left > 0L) {
            val batch = minOf(left, item.getDefaultMaxStackSize().toLong()).toInt()
            val got = h.entity.insertFromOutside(ItemStack(item, batch))
            inserted += got
            left -= batch
            if (got < batch) break
        }
        if (inserted >= count) return true
        if (inserted > 0L) h.entity.extractFromOutside({ it.item === item }, inserted)
        return false
    }
}
