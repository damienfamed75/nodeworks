package damien.nodeworks.platform

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel

class FabricStorageService : StorageService {

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        val storage = ItemStorage.SIDED.find(level, pos, face) ?: return null
        return FabricItemStorageHandle(storage)
    }

    override fun moveItems(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val src = (source as FabricItemStorageHandle).storage
        val dst = (dest as FabricItemStorageHandle).storage
        return try {
            StorageUtil.move(src, dst, { variant ->
                val itemId = BuiltInRegistries.ITEM.getKey(variant.item)?.toString() ?: return@move false
                filter(itemId)
            }, maxCount, null)
        } catch (_: Exception) {
            0L
        }
    }

    override fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long {
        val src = (storage as FabricItemStorageHandle).storage
        var total = 0L
        for (view in src) {
            if (!view.isResourceBlank && view.amount > 0) {
                val itemId = BuiltInRegistries.ITEM.getKey(view.resource.item)?.toString() ?: continue
                if (filter(itemId)) {
                    total += view.amount
                }
            }
        }
        return total
    }

    override fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val src = (storage as FabricItemStorageHandle).storage
        var total = 0L
        var remaining = maxCount
        net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter().use { transaction ->
            for (view in src) {
                if (remaining <= 0) break
                if (view.isResourceBlank || view.amount <= 0) continue
                val itemId = BuiltInRegistries.ITEM.getKey(view.resource.item)?.toString() ?: continue
                if (!filter(itemId)) continue
                val extracted = view.extract(view.resource, minOf(remaining, view.amount), transaction)
                total += extracted
                remaining -= extracted
            }
            transaction.commit()
        }
        return total
    }

    override fun insertItemStack(storage: ItemStorageHandle, stack: net.minecraft.world.item.ItemStack): Int {
        if (stack.isEmpty) return 0
        val dst = (storage as FabricItemStorageHandle).storage
        val variant = ItemVariant.of(stack)
        net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter().use { transaction ->
            val inserted = dst.insert(variant, stack.count.toLong(), transaction)
            transaction.commit()
            return inserted.toInt()
        }
    }

    override fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String? {
        val src = (storage as FabricItemStorageHandle).storage
        for (view in src) {
            if (!view.isResourceBlank && view.amount > 0) {
                val itemId = BuiltInRegistries.ITEM.getKey(view.resource.item)?.toString() ?: continue
                if (filter(itemId)) return itemId
            }
        }
        return null
    }

    override fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle? {
        val storage = ItemStorage.SIDED.find(level, pos, face) ?: return null
        if (storage !is SlottedStorage<*>) return null
        @Suppress("UNCHECKED_CAST")
        return FabricSlottedStorageHandle(storage as SlottedStorage<ItemVariant>)
    }
}

class FabricItemStorageHandle(val storage: Storage<ItemVariant>) : ItemStorageHandle

class FabricSlottedStorageHandle(
    val storage: SlottedStorage<ItemVariant>
) : SlottedItemStorageHandle {
    override val slotCount: Int get() = storage.slotCount

    override fun filteredBySlots(slots: Set<Int>): ItemStorageHandle {
        return FabricItemStorageHandle(SlotFilteredStorage(storage, slots))
    }
}

/**
 * Wraps a SlottedStorage to only expose specific slots.
 */
private class SlotFilteredStorage(
    private val backing: SlottedStorage<ItemVariant>,
    private val allowedSlots: Set<Int>
) : Storage<ItemVariant> {
    override fun insert(resource: ItemVariant, maxAmount: Long, transaction: TransactionContext): Long {
        for (idx in allowedSlots) {
            if (idx < 0 || idx >= backing.slotCount) continue
            val inserted = backing.getSlot(idx).insert(resource, maxAmount, transaction)
            if (inserted > 0) return inserted
        }
        return 0
    }

    override fun extract(resource: ItemVariant, maxAmount: Long, transaction: TransactionContext): Long {
        var total = 0L
        var remaining = maxAmount
        for (idx in allowedSlots) {
            if (remaining <= 0) break
            if (idx < 0 || idx >= backing.slotCount) continue
            val extracted = backing.getSlot(idx).extract(resource, remaining, transaction)
            total += extracted
            remaining -= extracted
        }
        return total
    }

    override fun iterator(): MutableIterator<net.fabricmc.fabric.api.transfer.v1.storage.StorageView<ItemVariant>> {
        return allowedSlots
            .filter { it >= 0 && it < backing.slotCount }
            .map { backing.getSlot(it) as net.fabricmc.fabric.api.transfer.v1.storage.StorageView<ItemVariant> }
            .toMutableList()
            .iterator()
    }
}
