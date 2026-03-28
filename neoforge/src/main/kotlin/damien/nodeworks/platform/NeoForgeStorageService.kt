package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemHandlerHelper

class NeoForgeStorageService : StorageService {

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        val handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face) ?: return null
        return NeoForgeItemStorageHandle(handler)
    }

    override fun moveItems(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount

        for (slot in 0 until src.slots) {
            if (remaining <= 0) break
            val stack = src.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (!filter(itemId)) continue

            val toMove = minOf(remaining, stack.count.toLong()).toInt()
            val extracted = src.extractItem(slot, toMove, true) // simulate
            if (extracted.isEmpty) continue

            val leftover = ItemHandlerHelper.insertItemStacked(dst, extracted.copy(), false)
            val inserted = extracted.count - leftover.count
            if (inserted > 0) {
                src.extractItem(slot, inserted, false) // actually extract
                total += inserted
                remaining -= inserted
            }
        }
        return total
    }

    override fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount

        for (slot in 0 until src.slots) {
            if (remaining <= 0) break
            val stack = src.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            val hasData = stack.componentsPatch.size() > 0
            if (!filter(itemId, hasData)) continue

            val toMove = minOf(remaining, stack.count.toLong()).toInt()
            val extracted = src.extractItem(slot, toMove, true)
            if (extracted.isEmpty) continue

            val leftover = ItemHandlerHelper.insertItemStacked(dst, extracted.copy(), false)
            val inserted = extracted.count - leftover.count
            if (inserted > 0) {
                src.extractItem(slot, inserted, false)
                total += inserted
                remaining -= inserted
            }
        }
        return total
    }

    override fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (filter(itemId)) {
                total += stack.count
            }
        }
        return total
    }

    override fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount
        for (slot in 0 until handler.slots) {
            if (remaining <= 0) break
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (!filter(itemId)) continue
            val toExtract = minOf(remaining, stack.count.toLong()).toInt()
            val extracted = handler.extractItem(slot, toExtract, false)
            total += extracted.count
            remaining -= extracted.count
        }
        return total
    }

    override fun insertItemStack(storage: ItemStorageHandle, stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val leftover = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false)
        return stack.count - leftover.count
    }

    override fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (filter(itemId)) return itemId
        }
        return null
    }

    override fun findFirstItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): ItemInfo? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (filter(itemId)) {
                return ItemInfo(
                    itemId = itemId,
                    name = stack.hoverName.string,
                    count = stack.count.toLong(),
                    maxStackSize = stack.item.getDefaultMaxStackSize(),
                    hasData = stack.componentsPatch.size() > 0
                )
            }
        }
        return null
    }

    override fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val aggregated = LinkedHashMap<String, ItemInfo>()
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (!filter(itemId)) continue
            val hasData = stack.componentsPatch.size() > 0
            val cacheKey = "$itemId:$hasData"
            val existing = aggregated[cacheKey]
            if (existing != null) {
                aggregated[cacheKey] = existing.copy(count = existing.count + stack.count)
            } else {
                aggregated[cacheKey] = ItemInfo(
                    itemId = itemId,
                    name = stack.hoverName.string,
                    count = stack.count.toLong(),
                    maxStackSize = stack.item.getDefaultMaxStackSize(),
                    hasData = hasData
                )
            }
        }
        return aggregated.values.toList()
    }

    override fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle? {
        val handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face) ?: return null
        return NeoForgeSlottedStorageHandle(handler)
    }
}

class NeoForgeItemStorageHandle(val handler: IItemHandler) : ItemStorageHandle

class NeoForgeSlottedStorageHandle(
    val handler: IItemHandler
) : SlottedItemStorageHandle {
    override val slotCount: Int get() = handler.slots

    override fun filteredBySlots(slots: Set<Int>): ItemStorageHandle {
        return NeoForgeItemStorageHandle(SlotFilteredItemHandler(handler, slots))
    }
}

/**
 * Wraps an IItemHandler to only expose specific slot indices.
 */
private class SlotFilteredItemHandler(
    private val backing: IItemHandler,
    private val allowedSlots: Set<Int>
) : IItemHandler {
    private val slotList = allowedSlots.filter { it in 0 until backing.slots }.sorted()

    override fun getSlots(): Int = slotList.size

    override fun getStackInSlot(slot: Int): ItemStack {
        if (slot < 0 || slot >= slotList.size) return ItemStack.EMPTY
        return backing.getStackInSlot(slotList[slot])
    }

    override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (slot < 0 || slot >= slotList.size) return stack
        return backing.insertItem(slotList[slot], stack, simulate)
    }

    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if (slot < 0 || slot >= slotList.size) return ItemStack.EMPTY
        return backing.extractItem(slotList[slot], amount, simulate)
    }

    override fun getSlotLimit(slot: Int): Int {
        if (slot < 0 || slot >= slotList.size) return 0
        return backing.getSlotLimit(slotList[slot])
    }

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean {
        if (slot < 0 || slot >= slotList.size) return false
        return backing.isItemValid(slotList[slot], stack)
    }
}
