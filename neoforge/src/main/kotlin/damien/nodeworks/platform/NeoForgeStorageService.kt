package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemHandlerHelper

class NeoForgeStorageService : StorageService {

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        // 26.1: Capabilities.ItemHandler.BLOCK (IItemHandler) was replaced by
        //  Capabilities.Item.BLOCK (ResourceHandler<ItemResource>). The IItemHandler.of(...)
        //  adapter is NeoForge's official migration ease path — keeps existing slot-based
        //  logic intact while consuming the new resource-handler capability.
        val resourceHandler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeItemStorageHandle(IItemHandler.of(resourceHandler))
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

    override fun tryInsertAll(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, count: Long): Boolean {
        if (count <= 0L) return true
        if (count > Int.MAX_VALUE.toLong()) return false
        val handler = (dest as NeoForgeItemStorageHandle).handler
        val stack = ItemStack(item, count.toInt())
        // Simulate first — insertItemStacked with simulate=true returns leftover WITHOUT
        // mutating the handler. On a single-threaded server, the subsequent real insert
        // sees the same state the sim did, so a successful sim guarantees a successful commit.
        val sim = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), true)
        if (!sim.isEmpty) return false
        val real = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false)
        if (!real.isEmpty) {
            // Unexpected divergence between sim and real. Extract back exactly what we placed
            // to keep the atomic contract; matches on item type which is unambiguous (the stack
            // we passed in has no NBT components, so matches are straightforward).
            val inserted = stack.count - real.count
            extractByItem(handler, item, inserted)
            return false
        }
        return true
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

        // Phase 1 — real extract from source, collecting exactly `count` matching items.
        // If source doesn't have enough, put what we took right back and return false.
        val extracted = mutableListOf<ItemStack>()
        var remaining = count
        for (slot in 0 until src.slots) {
            if (remaining <= 0L) break
            val s = src.getStackInSlot(slot)
            if (s.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(s.item)?.toString() ?: continue
            if (!filter(itemId)) continue
            val take = minOf(remaining, s.count.toLong()).toInt()
            val e = src.extractItem(slot, take, false)
            if (e.isEmpty) continue
            extracted.add(e)
            remaining -= e.count.toLong()
        }
        if (remaining > 0L) {
            restoreToSource(src, extracted)
            return false
        }

        // Phase 2 — atomic insert into dest, per item type. Sim-then-commit in sequence is
        // safe on a single-threaded server: each commit updates state so the next item type's
        // sim correctly reflects prior commits' effects.
        val byItem = extracted.groupBy { it.item }
            .mapValues { (_, stacks) -> stacks.sumOf { it.count } }
        val committedToDst = mutableListOf<Pair<net.minecraft.world.item.Item, Int>>()
        for ((item, amount) in byItem) {
            val stack = ItemStack(item, amount)
            val sim = ItemHandlerHelper.insertItemStacked(dst, stack.copy(), true)
            if (!sim.isEmpty) {
                // Won't fit. Unwind: extract what we already committed to dst, plus put the
                // extracted items back into source.
                for ((it2, amt2) in committedToDst) extractByItem(dst, it2, amt2)
                restoreToSource(src, extracted)
                return false
            }
            val real = ItemHandlerHelper.insertItemStacked(dst, stack.copy(), false)
            if (!real.isEmpty) {
                // Sim/real divergence — take back what did land and unwind everything.
                val landed = amount - real.count
                if (landed > 0) extractByItem(dst, item, landed)
                for ((it2, amt2) in committedToDst) extractByItem(dst, it2, amt2)
                restoreToSource(src, extracted)
                return false
            }
            committedToDst.add(item to amount)
        }
        return true
    }

    /** Put a list of stacks back into [src] in slot order. Should always succeed fully on a
     *  single-threaded server since the items came from this handler a moment ago. Any genuine
     *  overflow drops on the floor rather than looping forever — better than hanging. */
    private fun restoreToSource(src: IItemHandler, stacks: List<ItemStack>) {
        for (s in stacks) {
            val leftover = ItemHandlerHelper.insertItemStacked(src, s.copy(), false)
            if (!leftover.isEmpty) {
                // Defensive: the source somehow can't receive items it just emitted.
                // Log once and leak rather than corrupt more state.
                org.slf4j.LoggerFactory.getLogger("nodeworks-neoforge-storage").warn(
                    "tryMoveAll rollback: source refused returning {} x{}. State may diverge.",
                    s.item, leftover.count
                )
            }
        }
    }

    /** Remove up to [amount] of [item] from [handler] by iterating slots. Safe because
     *  we only extract items whose type matches what we explicitly placed. */
    private fun extractByItem(handler: IItemHandler, item: net.minecraft.world.item.Item, amount: Int) {
        var remaining = amount
        for (slot in 0 until handler.slots) {
            if (remaining <= 0) break
            val s = handler.getStackInSlot(slot)
            if (s.isEmpty || s.item != item) continue
            val take = minOf(remaining, s.count)
            val e = handler.extractItem(slot, take, false)
            remaining -= e.count
        }
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
        val resourceHandler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeSlottedStorageHandle(IItemHandler.of(resourceHandler))
    }

    // --- Fluid side ---

    override fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? {
        val resourceHandler = level.getCapability(Capabilities.Fluid.BLOCK, pos, face) ?: return null
        return NeoForgeFluidStorageHandle(IFluidHandler.of(resourceHandler))
    }

    private fun fluidIdOf(stack: FluidStack): String? =
        BuiltInRegistries.FLUID.getKey(stack.fluid)?.toString()

    override fun countFluid(storage: FluidStorageHandle, filter: (String) -> Boolean): Long {
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        var total = 0L
        for (tank in 0 until handler.tanks) {
            val s = handler.getFluidInTank(tank)
            if (s.isEmpty) continue
            val id = fluidIdOf(s) ?: continue
            if (filter(id)) total += s.amount.toLong()
        }
        return total
    }

    override fun findFirstFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): FluidInfo? {
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        // Aggregate across tanks — first matching id wins, amount summed.
        var firstId: String? = null
        var firstName: String? = null
        var total = 0L
        for (tank in 0 until handler.tanks) {
            val s = handler.getFluidInTank(tank)
            if (s.isEmpty) continue
            val id = fluidIdOf(s) ?: continue
            if (!filter(id)) continue
            if (firstId == null) {
                firstId = id
                firstName = s.hoverName.string
            }
            if (id == firstId) total += s.amount.toLong()
        }
        return firstId?.let { FluidInfo(it, firstName ?: it, total) }
    }

    override fun findAllFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): List<FluidInfo> {
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        val aggregated = LinkedHashMap<String, FluidInfo>()
        for (tank in 0 until handler.tanks) {
            val s = handler.getFluidInTank(tank)
            if (s.isEmpty) continue
            val id = fluidIdOf(s) ?: continue
            if (!filter(id)) continue
            val existing = aggregated[id]
            if (existing != null) {
                aggregated[id] = existing.copy(amount = existing.amount + s.amount.toLong())
            } else {
                aggregated[id] = FluidInfo(id, s.hoverName.string, s.amount.toLong())
            }
        }
        return aggregated.values.toList()
    }

    override fun moveFluid(source: FluidStorageHandle, dest: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long {
        if (maxAmount <= 0L) return 0L
        val src = (source as NeoForgeFluidStorageHandle).handler
        val dst = (dest as NeoForgeFluidStorageHandle).handler
        var moved = 0L
        var remaining = maxAmount
        for (tank in 0 until src.tanks) {
            if (remaining <= 0L) break
            val s = src.getFluidInTank(tank)
            if (s.isEmpty) continue
            val id = fluidIdOf(s) ?: continue
            if (!filter(id)) continue
            val take = minOf(remaining, s.amount.toLong()).toInt()
            val probe = s.copyWithAmount(take)
            val fillSim = dst.fill(probe, IFluidHandler.FluidAction.SIMULATE)
            if (fillSim <= 0) continue
            val drained = src.drain(s.copyWithAmount(fillSim), IFluidHandler.FluidAction.EXECUTE)
            if (drained.isEmpty) continue
            val filled = dst.fill(drained, IFluidHandler.FluidAction.EXECUTE)
            if (filled < drained.amount) {
                // Sim/real divergence — push the leftover back into source.
                val leftover = drained.copyWithAmount(drained.amount - filled)
                src.fill(leftover, IFluidHandler.FluidAction.EXECUTE)
            }
            moved += filled.toLong()
            remaining -= filled.toLong()
        }
        return moved
    }

    override fun tryMoveAllFluid(source: FluidStorageHandle, dest: FluidStorageHandle, filter: (String) -> Boolean, amount: Long): Boolean {
        if (amount <= 0L) return true
        if (amount > Int.MAX_VALUE.toLong()) return false
        val src = (source as NeoForgeFluidStorageHandle).handler
        val dst = (dest as NeoForgeFluidStorageHandle).handler

        // Find the first matching fluid to move (fluids don't inter-mix across types in one call).
        var chosenId: String? = null
        var available = 0L
        for (tank in 0 until src.tanks) {
            val s = src.getFluidInTank(tank)
            if (s.isEmpty) continue
            val id = fluidIdOf(s) ?: continue
            if (!filter(id)) continue
            if (chosenId == null) chosenId = id
            if (id == chosenId) available += s.amount.toLong()
        }
        if (chosenId == null || available < amount) return false

        // Drain simulate, fill simulate, then execute-execute.
        val drainProbe = FluidStack(BuiltInRegistries.FLUID.getValue(net.minecraft.resources.Identifier.parse(chosenId)), amount.toInt())
        val drainedSim = src.drain(drainProbe, IFluidHandler.FluidAction.SIMULATE)
        if (drainedSim.amount < amount.toInt()) return false
        val fillSim = dst.fill(drainedSim.copy(), IFluidHandler.FluidAction.SIMULATE)
        if (fillSim < amount.toInt()) return false

        val realDrain = src.drain(drainProbe.copy(), IFluidHandler.FluidAction.EXECUTE)
        if (realDrain.amount < amount.toInt()) {
            // Put back anything we accidentally drained.
            if (!realDrain.isEmpty) src.fill(realDrain, IFluidHandler.FluidAction.EXECUTE)
            return false
        }
        val realFill = dst.fill(realDrain.copy(), IFluidHandler.FluidAction.EXECUTE)
        if (realFill < amount.toInt()) {
            // Roll back: extract what went in, push drained back into source.
            if (realFill > 0) {
                val back = realDrain.copyWithAmount(realFill)
                dst.drain(back, IFluidHandler.FluidAction.EXECUTE)
            }
            src.fill(realDrain, IFluidHandler.FluidAction.EXECUTE)
            return false
        }
        return true
    }

    override fun insertFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Long {
        if (amount <= 0L) return 0L
        val id = net.minecraft.resources.Identifier.tryParse(fluidId) ?: return 0L
        val fluid = BuiltInRegistries.FLUID.getValue(id) ?: return 0L
        val handler = (dest as NeoForgeFluidStorageHandle).handler
        val toFill = minOf(amount, Int.MAX_VALUE.toLong()).toInt()
        val stack = FluidStack(fluid, toFill)
        return handler.fill(stack, IFluidHandler.FluidAction.EXECUTE).toLong()
    }

    override fun tryInsertAllFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Boolean {
        if (amount <= 0L) return true
        if (amount > Int.MAX_VALUE.toLong()) return false
        val id = net.minecraft.resources.Identifier.tryParse(fluidId) ?: return false
        val fluid = BuiltInRegistries.FLUID.getValue(id) ?: return false
        val handler = (dest as NeoForgeFluidStorageHandle).handler
        val stack = FluidStack(fluid, amount.toInt())
        val sim = handler.fill(stack.copy(), IFluidHandler.FluidAction.SIMULATE)
        if (sim < amount.toInt()) return false
        val real = handler.fill(stack.copy(), IFluidHandler.FluidAction.EXECUTE)
        if (real < amount.toInt()) {
            // Unexpected divergence — drain whatever landed.
            if (real > 0) handler.drain(FluidStack(fluid, real), IFluidHandler.FluidAction.EXECUTE)
            return false
        }
        return true
    }

    override fun extractFluid(storage: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long {
        if (maxAmount <= 0L) return 0L
        val handler = (storage as NeoForgeFluidStorageHandle).handler
        var removed = 0L
        var remaining = maxAmount
        for (tank in 0 until handler.tanks) {
            if (remaining <= 0L) break
            val s = handler.getFluidInTank(tank)
            if (s.isEmpty) continue
            val id = fluidIdOf(s) ?: continue
            if (!filter(id)) continue
            val take = minOf(remaining, s.amount.toLong()).toInt()
            val drained = handler.drain(s.copyWithAmount(take), IFluidHandler.FluidAction.EXECUTE)
            removed += drained.amount.toLong()
            remaining -= drained.amount.toLong()
        }
        return removed
    }
}

class NeoForgeItemStorageHandle(val handler: IItemHandler) : ItemStorageHandle

class NeoForgeFluidStorageHandle(val handler: IFluidHandler) : FluidStorageHandle

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
