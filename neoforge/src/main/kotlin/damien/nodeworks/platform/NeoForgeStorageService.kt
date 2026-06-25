package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.transfer.ResourceHandler
import net.neoforged.neoforge.transfer.fluid.FluidResource
import net.neoforged.neoforge.transfer.item.ItemResource
import net.neoforged.neoforge.transfer.item.ItemUtil
import net.neoforged.neoforge.transfer.transaction.Transaction
import net.neoforged.neoforge.transfer.transaction.TransactionContext

fun ResourceHandler<ItemResource>.getStack(slot: Int): ItemStack = ItemUtil.getStack(this, slot)

class NeoForgeStorageService : StorageService {

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        // 26.1: Capabilities.ItemHandler.BLOCK (ResourceHandler<ItemResource>) was replaced by
        //  Capabilities.Item.BLOCK (ResourceHandler<ItemResource>). The ResourceHandler<ItemResource>.of(...)
        //  adapter is NeoForge's official migration ease path, keeps existing slot-based
        //  logic intact while consuming the new resource-handler capability.
        val resourceHandler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeItemStorageHandle(resourceHandler)
    }

    private fun itemIdOf(resource: ItemResource): String = BuiltInRegistries.ITEM.getKey(resource.item).toString()

    override fun moveItems(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long
    ): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount
        Transaction.openRoot().use { tx ->
            for (slot in 0 until src.size()) {
                if (remaining <= 0) break
                val resource = src.getResource(slot)
                if (resource.isEmpty) continue
                val itemId = itemIdOf(resource)
                if (!filter(itemId)) continue

                val toMove = minOf(remaining, src.getAmountAsLong(slot)).toInt()
                val extracted = src.extract(slot, resource, toMove, tx)
                if (extracted == 0) continue

                val inserted = dst.insert(resource, extracted, tx)
                total += inserted
                remaining -= inserted
            }
            tx.commit()
            return total
        }
    }

    override fun moveItemsVariant(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String, Boolean) -> Boolean,
        maxCount: Long
    ): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount

        Transaction.openRoot().use { tx ->
            for (slot in 0 until src.size()) {
                if (remaining <= 0) break
                val resource = src.getResource(slot)
                if (resource.isEmpty) continue
                val itemId = itemIdOf(resource)
                val hasData = resource.componentsPatch.size() > 0
                if (!filter(itemId, hasData)) continue

                val toMove = minOf(remaining, src.getAmountAsLong(slot)).toInt()
                val extracted = src.extract(slot, resource, toMove, tx)
                if (extracted == 0) continue

                val inserted = dst.insert(resource, extracted, tx)
                total += inserted
                remaining -= inserted
            }
            tx.commit()
            return total
        }
    }

    override fun moveItemsByStackPredicate(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
    ): Long {
        if (maxCount <= 0L) return 0L
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount

        Transaction.openRoot().use { tx ->
            for (slot in 0 until src.size()) {
                if (remaining <= 0) break
                // Predicate sees the full slot stack for component-aware matching.
                if (!filter(src.getStack(slot))) continue
                val resource = src.getResource(slot)
                if (resource.isEmpty) continue

                val toMove = minOf(remaining, src.getAmountAsLong(slot)).toInt()
                val extracted = src.extract(slot, resource, toMove, tx)
                if (extracted == 0) continue

                val inserted = dst.insert(resource, extracted, tx)
                total += inserted
                remaining -= inserted
            }
            tx.commit()
            return total
        }
    }

    override fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        for (slot in 0 until handler.size()) {
            val resource = handler.getResource(slot)
            if (resource.isEmpty) continue
            val itemId = itemIdOf(resource)
            if (filter(itemId)) {
                total += handler.getAmountAsLong(slot)
            }
        }
        return total
    }

    override fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount

        Transaction.openRoot().use { tx ->
            for (slot in 0 until handler.size()) {
                if (remaining <= 0) break
                val resource = handler.getResource(slot)
                if (resource.isEmpty) continue
                val itemId = itemIdOf(resource)
                if (!filter(itemId)) continue
                val toExtract = minOf(remaining, handler.getAmountAsLong(slot)).toInt()
                val extracted = handler.extract(slot, resource, toExtract, tx)
                total += extracted
                remaining -= extracted
            }
            tx.commit()
            return total
        }
    }

    override fun extractItemStacksMatching(
        storage: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
    ): List<ItemStack> {
        if (maxCount <= 0L) return emptyList()
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val out = ArrayList<ItemStack>()
        var remaining = maxCount

        Transaction.openRoot().use { tx ->
            for (slot in 0 until handler.size()) {
                if (remaining <= 0L) break
                val resource = handler.getResource(slot)
                if (resource.isEmpty) continue
                val itemId = itemIdOf(resource)
                if (!filter(itemId)) continue
                val toExtract = minOf(remaining, handler.getAmountAsLong(slot)).toInt()
                // extractItem returns a real stack with the slot's components intact,
                // unlike extractItems which only sums counts. Returning these directly
                // preserves durability, enchantments, custom names, dye colour, etc.
                val extracted = handler.extract(slot, resource, toExtract, tx)
                if (extracted == 0) continue
                out.add(resource.toStack(extracted))
                remaining -= extracted
            }
            tx.commit()
            return out
        }
    }

    override fun extractStacksByPredicate(
        storage: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
    ): List<ItemStack> {
        if (maxCount <= 0L) return emptyList()
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val out = ArrayList<ItemStack>()
        var remaining = maxCount

        Transaction.openRoot().use { tx ->
            for (slot in 0 until handler.size()) {
                if (remaining <= 0L) break
                val resource = handler.getResource(slot)
                if (resource.isEmpty) continue
                // Predicate sees the full slot stack so the caller can match on
                // component-bearing identity (e.g. only Strength Potions, not
                // every variant of `minecraft:potion`).
                if (!filter(resource.toStack(handler.getAmountAsInt(slot)))) continue
                val toExtract = minOf(remaining, handler.getAmountAsLong(slot)).toInt()
                val extracted = handler.extract(slot, resource, toExtract, tx)
                if (extracted == 0) continue
                out.add(resource.toStack(extracted))
                remaining -= extracted
            }
            tx.commit()
            return out
        }
    }

    override fun countStacksByPredicate(
        storage: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
    ): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        for (slot in 0 until handler.size()) {
            val stack = handler.getStack(slot)
            if (stack.isEmpty) continue
            if (filter(stack)) total += stack.count
        }
        return total
    }

    override fun insertItemStack(storage: ItemStorageHandle, stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        val handler = (storage as NeoForgeItemStorageHandle).handler
        Transaction.openRoot().use { tx ->
            val inserted = handler.insert(ItemResource.of(stack), stack.count, tx)
            tx.commit()
            return inserted
        }
    }

    override fun simulateInsertItem(
        dest: ItemStorageHandle,
        item: net.minecraft.world.item.Item,
        maxCount: Long
    ): Long {
        if (maxCount <= 0L) return 0L
        val handler = (dest as NeoForgeItemStorageHandle).handler
        val capped = minOf(maxCount, Int.MAX_VALUE.toLong()).toInt()
        // NeoForge's transactional simulate: `insertItemStacked(simulate=true)` opens a root
        // transaction, snapshots each touched slot, performs the insertion on a copy, and aborts
        // on close, net inventory state is guaranteed restored. Cosmetic slot reshuffling may
        // be observed (the snapshot mechanism swaps the slot's ItemStack reference with a copy
        // mid-transaction) but item counts are preserved by the transaction contract, so there
        // is no duplication or loss risk.
        Transaction.openRoot().use { tx ->
            val inserted = handler.insert(ItemResource.of(item), capped, tx)
            return inserted.toLong()
        }
    }

    override fun tryInsertAll(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, count: Long): Boolean {
        if (count <= 0L) return true
        if (count > Int.MAX_VALUE.toLong()) return false
        val handler = (dest as NeoForgeItemStorageHandle).handler
        // Simulate first, insertItemStacked with simulate=true returns leftover WITHOUT
        // mutating the handler. On a single-threaded server, the subsequent real insert
        // sees the same state the sim did, so a successful sim guarantees a successful commit.
        Transaction.openRoot().use { tx ->
            val inserted = handler.insert(ItemResource.of(item), count.toInt(), tx)
            return inserted == count.toInt()
        }
    }

    override fun tryMoveAll(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        count: Long
    ): Boolean {
        if (count <= 0L) return true
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler

        Transaction.openRoot().use { tx ->
            var remaining = count
            for (slot in 0 until src.size()) {
                if (remaining <= 0L) break
                val resource = src.getResource(slot)
                if (resource.isEmpty) continue
                val itemId = itemIdOf(resource)
                if (!filter(itemId)) continue
                val take = minOf(remaining, src.getAmountAsLong(slot)).toInt()
                val extracted = src.extract(slot, resource, take, tx)
                if (extracted <= 0) continue
                val inserted = dst.insert(resource, extracted, tx)
                if (inserted < extracted) return false
                remaining -= extracted
            }
            if (remaining > 0L) {
                return false
            }
            tx.commit()
            return true
        }
    }

    override fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        for (slot in 0 until handler.size()) {
            val resource = handler.getResource(slot)
            if (resource.isEmpty) continue
            val itemId = itemIdOf(resource)
            if (filter(itemId)) return itemId
        }
        return null
    }

    override fun findFirstItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): ItemInfo? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        for (slot in 0 until handler.size()) {
            val resource = handler.getResource(slot)
            if (resource.isEmpty) continue
            val itemId = itemIdOf(resource)
            if (filter(itemId)) {
                return ItemInfo(
                    itemId = itemId,
                    name = resource.hoverName.string,
                    count = handler.getAmountAsLong(slot),
                    maxStackSize = resource.item.defaultMaxStackSize,
                    hasData = resource.componentsPatch.size() > 0,
                    componentsPatch = resource.componentsPatch,
                )
            }
        }
        return null
    }

    override fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        // Aggregation key is the full BufferKey so stacks with distinct
        // DataComponents (different potions, dyed armor, enchanted books) stay
        // separate entries in the result instead of collapsing under a single
        // `hasData=true` bucket. The old key was `"$itemId:$hasData"` which
        // hashed all five potion variants together and the Inventory Terminal
        // / network:find / card:find all displayed one with the wrong count.
        val aggregated = LinkedHashMap<damien.nodeworks.script.BufferKey.Key, ItemInfo>()
        for (slot in 0 until handler.size()) {
            val resource = handler.getResource(slot)
            if (resource.isEmpty) continue
            val itemId = itemIdOf(resource)
            if (!filter(itemId)) continue
            val cacheKey = damien.nodeworks.script.BufferKey.of(handler.getStack(slot))
            val existing = aggregated[cacheKey]
            if (existing != null) {
                // Aggregate count, keep the first-sampled stack's components as the
                // representative for client-side display (durability bars, custom
                // names, enchantment glints). Distinct components already routed
                // to distinct cacheKey buckets, so this only merges truly
                // identical stacks split across slots.
                aggregated[cacheKey] = existing.copy(count = existing.count + handler.getAmountAsLong(slot))
            } else {
                aggregated[cacheKey] = ItemInfo(
                    itemId = itemId,
                    name = resource.hoverName.string,
                    count = handler.getAmountAsLong(slot),
                    maxStackSize = resource.item.defaultMaxStackSize,
                    hasData = !cacheKey.isPlain,
                    componentsPatch = resource.componentsPatch,
                )
            }
        }
        return aggregated.values.toList()
    }

    override fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle? {
        val resourceHandler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeSlottedStorageHandle(resourceHandler)
    }

    // --- Fluid side ---

    override fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? {
        val resourceHandler = level.getCapability(Capabilities.Fluid.BLOCK, pos, face) ?: return null
        return NeoForgeFluidStorageHandle(resourceHandler)
    }

    private fun fluidIdOf(resource: FluidResource): String = BuiltInRegistries.FLUID.getKey(resource.fluid).toString()

    override fun countFluid(storage: FluidStorageHandle, filter: (String) -> Boolean): Long {
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        var total = 0L
        for (tank in 0 until handler.size()) {
            val resource = handler.getResource(tank)
            if (resource.isEmpty) continue
            val id = fluidIdOf(resource)
            if (filter(id)) total += handler.getAmountAsLong(tank)
        }
        return total
    }

    override fun findFirstFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): FluidInfo? {
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        // Aggregate across tanks, first matching id wins, amount summed.
        var firstId: String? = null
        var firstName: String? = null
        var total = 0L
        for (tank in 0 until handler.size()) {
            val resource = handler.getResource(tank)
            if (resource.isEmpty) continue
            val id = fluidIdOf(resource)
            if (!filter(id)) continue
            if (firstId == null) {
                firstId = id
                firstName = resource.hoverName.string
            }
            if (id == firstId) total += handler.getAmountAsLong(tank)
        }
        return firstId?.let { FluidInfo(it, firstName ?: it, total) }
    }

    override fun findAllFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): List<FluidInfo> {
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        val aggregated = LinkedHashMap<String, FluidInfo>()
        for (tank in 0 until handler.size()) {
            val resource = handler.getResource(tank)
            if (resource.isEmpty) continue
            val id = fluidIdOf(resource)
            if (!filter(id)) continue
            val existing = aggregated[id]
            if (existing != null) {
                aggregated[id] = existing.copy(amount = existing.amount + handler.getAmountAsLong(tank))
            } else {
                aggregated[id] = FluidInfo(id, resource.hoverName.string, handler.getAmountAsLong(tank))
            }
        }
        return aggregated.values.toList()
    }

    override fun moveFluid(
        source: FluidStorageHandle,
        dest: FluidStorageHandle,
        filter: (String) -> Boolean,
        maxAmount: Long
    ): Long {
        if (maxAmount <= 0L) return 0L
        val src = (source as NeoForgeFluidStorageHandle).handler
        val dst = (dest as NeoForgeFluidStorageHandle).handler
        var moved = 0L
        var remaining = maxAmount

        Transaction.openRoot().use { tx ->
            for (tank in 0 until src.size()) {
                if (remaining <= 0L) break
                val resource = src.getResource(tank)
                if (resource.isEmpty) continue
                val id = fluidIdOf(resource)
                if (!filter(id)) continue
                val take = minOf(remaining, src.getAmountAsLong(tank)).toInt()
                val inserted = dst.insert(resource, take, tx)
                if (inserted <= 0) continue
                moved += inserted
                remaining -= inserted
            }
            tx.commit()
            return moved
        }
    }

    override fun tryMoveAllFluid(
        source: FluidStorageHandle,
        dest: FluidStorageHandle,
        filter: (String) -> Boolean,
        amount: Long
    ): Boolean {
        if (amount <= 0L) return true
        if (amount > Int.MAX_VALUE.toLong()) return false
        val src = (source as NeoForgeFluidStorageHandle).handler
        val dst = (dest as NeoForgeFluidStorageHandle).handler

        // Find the first matching fluid to move (fluids don't inter-mix across types in one call).
        var chosen: FluidResource? = null
        var available = 0L
        for (tank in 0 until src.size()) {
            val resource = src.getResource(tank)
            if (resource.isEmpty) continue
            val id = fluidIdOf(resource)
            if (!filter(id)) continue
            if (chosen == null) chosen = resource
            if (resource == chosen) available += src.getAmountAsLong(tank)
        }
        if (chosen == null || available < amount) return false

        // Drain simulate, fill simulate, then execute-execute.
        Transaction.openRoot().use { tx ->
            val extracted = src.extract(chosen, amount.toInt(), tx)
            if (extracted < amount) return false
            val inserted = dst.insert(chosen, amount.toInt(), tx)
            if (inserted < amount) return false
            tx.commit()
            return true
        }
    }

    override fun insertFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Long {
        if (amount <= 0L) return 0L
        val id = Identifier.tryParse(fluidId) ?: return 0L
        val fluid = BuiltInRegistries.FLUID.getValue(id)
        val handler = (dest as NeoForgeFluidStorageHandle).handler
        val toFill = minOf(amount, Int.MAX_VALUE.toLong()).toInt()
        Transaction.openRoot().use { tx ->
            val inserted = handler.insert(FluidResource.of(fluid), toFill, tx)
            tx.commit()
            return inserted.toLong()
        }
    }

    override fun simulateInsertFluid(dest: FluidStorageHandle, fluidId: String, maxAmount: Long): Long {
        if (maxAmount <= 0L) return 0L
        val id = Identifier.tryParse(fluidId) ?: return 0L
        val fluid = BuiltInRegistries.FLUID.getValue(id)
        val handler = (dest as NeoForgeFluidStorageHandle).handler
        val toFill = minOf(maxAmount, Int.MAX_VALUE.toLong()).toInt()
        Transaction.openRoot().use { tx ->
            return handler.insert(FluidResource.of(fluid), toFill, tx).toLong()
        }
    }

    override fun tryInsertAllFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Boolean {
        if (amount <= 0L) return true
        if (amount > Int.MAX_VALUE.toLong()) return false
        val id = Identifier.tryParse(fluidId) ?: return false
        val fluid = BuiltInRegistries.FLUID.getValue(id)
        val handler = (dest as NeoForgeFluidStorageHandle).handler
        Transaction.openRoot().use { tx ->
            val inserted = handler.insert(FluidResource.of(fluid), amount.toInt(), tx)
            if (inserted < amount) return false
            tx.commit()
            return true
        }
    }

    override fun extractFluid(storage: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long {
        if (maxAmount <= 0L) return 0L
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        var removed = 0L
        var remaining = maxAmount
        Transaction.openRoot().use { tx ->
            for (tank in 0 until handler.size()) {
                if (remaining <= 0L) break
                val resource = handler.getResource(tank)
                if (resource.isEmpty) continue
                val id = fluidIdOf(resource)
                if (!filter(id)) continue
                val take = minOf(remaining, handler.getAmountAsLong(tank)).toInt()
                val extracted = handler.extract(resource, take, tx)
                removed += extracted
                remaining -= extracted
            }
            tx.commit()
            return removed
        }
    }
}

class NeoForgeItemStorageHandle(val handler: ResourceHandler<ItemResource>) : ItemStorageHandle

class NeoForgeFluidStorageHandle(val handler: ResourceHandler<FluidResource>) : FluidStorageHandle

class NeoForgeSlottedStorageHandle(
    val handler: ResourceHandler<ItemResource>
) : SlottedItemStorageHandle {
    override val slotCount: Int get() = handler.size()

    override fun filteredBySlots(slots: Set<Int>): ItemStorageHandle {
        return NeoForgeItemStorageHandle(SlotFilteredItemHandler(handler, slots))
    }
}

/**
 * Wraps an ResourceHandler<ItemResource> to only expose specific slot indices.
 */
private class SlotFilteredItemHandler(
    private val backing: ResourceHandler<ItemResource>,
    allowedSlots: Set<Int>
) : ResourceHandler<ItemResource> {
    private val slotList = allowedSlots.filter { it in 0 until backing.size() }.sorted()

    override fun size(): Int = slotList.size

    override fun getResource(index: Int): ItemResource {
        if (index < 0 || index >= slotList.size) return ItemResource.EMPTY
        return backing.getResource(slotList[index])
    }

    override fun getAmountAsLong(index: Int): Long {
        if (index < 0 || index >= slotList.size) return 0L
        return backing.getAmountAsLong(slotList[index])
    }

    override fun getCapacityAsLong(index: Int, resource: ItemResource): Long {
        if (index < 0 || index >= slotList.size) return 0L
        return backing.getCapacityAsLong(slotList[index], resource)
    }

    override fun isValid(index: Int, resource: ItemResource): Boolean {
        if (index < 0 || index >= slotList.size) return false
        return backing.isValid(slotList[index], resource)
    }

    override fun insert(index: Int, resource: ItemResource, amount: Int, transaction: TransactionContext): Int {
        if (index < 0 || index >= slotList.size) return 0
        return backing.insert(slotList[index], resource, amount, transaction)
    }

    override fun extract(index: Int, resource: ItemResource, amount: Int, transaction: TransactionContext): Int {
        if (index < 0 || index >= slotList.size) return 0
        return backing.extract(slotList[index], resource, amount, transaction)
    }
}
