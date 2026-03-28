package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.transfer.ResourceHandler
import net.neoforged.neoforge.transfer.ResourceHandlerUtil
import net.neoforged.neoforge.transfer.item.ItemResource
import net.neoforged.neoforge.transfer.transaction.Transaction

class NeoForgeStorageService : StorageService {

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        val handler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeItemStorageHandle(handler)
    }

    override fun moveItems(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        return try {
            ResourceHandlerUtil.moveStacking(src, dst, { resource ->
                if (resource.isEmpty) return@moveStacking false
                val itemId = BuiltInRegistries.ITEM.getKey(resource.item)?.toString() ?: return@moveStacking false
                filter(itemId)
            }, minOf(maxCount, Int.MAX_VALUE.toLong()).toInt(), null).toLong()
        } catch (_: Exception) {
            0L
        }
    }

    override fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        return try {
            ResourceHandlerUtil.moveStacking(src, dst, { resource ->
                if (resource.isEmpty) return@moveStacking false
                val itemId = BuiltInRegistries.ITEM.getKey(resource.item)?.toString() ?: return@moveStacking false
                val hasData = resource.toStack().componentsPatch.size() > 0
                filter(itemId, hasData)
            }, minOf(maxCount, Int.MAX_VALUE.toLong()).toInt(), null).toLong()
        } catch (_: Exception) {
            0L
        }
    }

    override fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        for (index in 0 until handler.size()) {
            val resource = handler.getResource(index)
            val amount = handler.getAmountAsLong(index)
            if (!resource.isEmpty && amount > 0) {
                val itemId = BuiltInRegistries.ITEM.getKey(resource.item)?.toString() ?: continue
                if (filter(itemId)) {
                    total += amount
                }
            }
        }
        return total
    }

    override fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount
        net.neoforged.neoforge.transfer.transaction.Transaction.open(null).use { transaction ->
            for (index in 0 until handler.size()) {
                if (remaining <= 0) break
                val resource = handler.getResource(index)
                val amount = handler.getAmountAsLong(index)
                if (resource.isEmpty || amount <= 0) continue
                val itemId = BuiltInRegistries.ITEM.getKey(resource.item)?.toString() ?: continue
                if (!filter(itemId)) continue
                val toExtract = minOf(remaining, amount).toInt()
                val extracted = handler.extract(index, resource, toExtract, transaction)
                total += extracted
                remaining -= extracted
            }
            transaction.commit()
        }
        return total
    }

    override fun insertItemStack(storage: ItemStorageHandle, stack: net.minecraft.world.item.ItemStack): Int {
        if (stack.isEmpty) return 0
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val resource = net.neoforged.neoforge.transfer.item.ItemResource.of(stack)
        net.neoforged.neoforge.transfer.transaction.Transaction.open(null).use { transaction ->
            val inserted = handler.insert(resource, stack.count, transaction)
            transaction.commit()
            return inserted
        }
    }

    override fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        for (index in 0 until handler.size()) {
            val resource = handler.getResource(index)
            val amount = handler.getAmountAsLong(index)
            if (!resource.isEmpty && amount > 0) {
                val itemId = BuiltInRegistries.ITEM.getKey(resource.item)?.toString() ?: continue
                if (filter(itemId)) return itemId
            }
        }
        return null
    }

    override fun findFirstItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): ItemInfo? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        for (index in 0 until handler.size()) {
            val resource = handler.getResource(index)
            val amount = handler.getAmountAsLong(index)
            if (!resource.isEmpty && amount > 0) {
                val item = resource.item
                val itemId = BuiltInRegistries.ITEM.getKey(item)?.toString() ?: continue
                if (filter(itemId)) {
                    val stack = resource.toStack()
                    return ItemInfo(
                        itemId = itemId,
                        name = item.getName(stack).string,
                        count = amount,
                        maxStackSize = item.defaultMaxStackSize,
                        hasData = stack.componentsPatch.size() > 0
                    )
                }
            }
        }
        return null
    }

    override fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val aggregated = LinkedHashMap<String, ItemInfo>()
        for (index in 0 until handler.size()) {
            val resource = handler.getResource(index)
            val amount = handler.getAmountAsLong(index)
            if (!resource.isEmpty && amount > 0) {
                val item = resource.item
                val itemId = BuiltInRegistries.ITEM.getKey(item)?.toString() ?: continue
                if (!filter(itemId)) continue
                val stack = resource.toStack()
                val hasData = stack.componentsPatch.size() > 0
                val cacheKey = "$itemId:$hasData"
                val existing = aggregated[cacheKey]
                if (existing != null) {
                    aggregated[cacheKey] = existing.copy(count = existing.count + amount)
                } else {
                    aggregated[cacheKey] = ItemInfo(
                        itemId = itemId,
                        name = item.getName(stack).string,
                        count = amount,
                        maxStackSize = item.defaultMaxStackSize,
                        hasData = hasData
                    )
                }
            }
        }
        return aggregated.values.toList()
    }

    override fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle? {
        val handler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeSlottedStorageHandle(handler)
    }
}

class NeoForgeItemStorageHandle(val handler: ResourceHandler<ItemResource>) : ItemStorageHandle

class NeoForgeSlottedStorageHandle(
    val handler: ResourceHandler<ItemResource>
) : SlottedItemStorageHandle {
    override val slotCount: Int get() = handler.size()

    override fun filteredBySlots(slots: Set<Int>): ItemStorageHandle {
        return NeoForgeItemStorageHandle(SlotFilteredResourceHandler(handler, slots))
    }
}

/**
 * Wraps a ResourceHandler to only expose specific indices.
 */
private class SlotFilteredResourceHandler(
    private val backing: ResourceHandler<ItemResource>,
    private val allowedSlots: Set<Int>
) : ResourceHandler<ItemResource> {
    private val slotList = allowedSlots.filter { it in 0 until backing.size() }.sorted()

    override fun size(): Int = slotList.size

    override fun getResource(index: Int): ItemResource {
        if (index < 0 || index >= slotList.size) return ItemResource.EMPTY
        return backing.getResource(slotList[index])
    }

    override fun getAmountAsLong(index: Int): Long {
        if (index < 0 || index >= slotList.size) return 0
        return backing.getAmountAsLong(slotList[index])
    }

    override fun getCapacityAsLong(index: Int, resource: ItemResource): Long {
        if (index < 0 || index >= slotList.size) return 0
        return backing.getCapacityAsLong(slotList[index], resource)
    }

    override fun isValid(index: Int, resource: ItemResource): Boolean {
        if (index < 0 || index >= slotList.size) return false
        return backing.isValid(slotList[index], resource)
    }

    override fun insert(index: Int, resource: ItemResource, amount: Int, transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext): Int {
        if (index < 0 || index >= slotList.size) return 0
        return backing.insert(slotList[index], resource, amount, transaction)
    }

    override fun extract(index: Int, resource: ItemResource, amount: Int, transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext): Int {
        if (index < 0 || index >= slotList.size) return 0
        return backing.extract(slotList[index], resource, amount, transaction)
    }
}
