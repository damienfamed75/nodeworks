package damien.nodeworks.platform

import damien.nodeworks.compat.ae2.Ae2Compat
import damien.nodeworks.compat.ae2.Ae2FluidStorageHandle
import damien.nodeworks.compat.ae2.Ae2ItemStorageHandle
import damien.nodeworks.compat.ae2.Ae2Storage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

/**
 * Routes each call by handle type. AE2-backed handles go to [Ae2Storage], everything
 * else to [base], which stays exactly the pre-AE2 service. Two-handle operations that
 * cross backends are composed from the single-handle primitives below, which already
 * route, so a move from an ME network into a chest needs no backend-specific code.
 */
class CompositeStorageService(private val base: NeoForgeStorageService) : StorageService {

    private val logger = LoggerFactory.getLogger("nodeworks-storage")

    private fun ae2(h: ItemStorageHandle): Ae2ItemStorageHandle? =
        if (Ae2Compat.isLoaded) h as? Ae2ItemStorageHandle else null

    private fun ae2(h: FluidStorageHandle): Ae2FluidStorageHandle? =
        if (Ae2Compat.isLoaded) h as? Ae2FluidStorageHandle else null

    private fun idOf(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    // ---- lookup ----

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        if (Ae2Compat.isLoaded) Ae2Storage.getItemStorage(level, pos, face)?.let { return it }
        return base.getItemStorage(level, pos, face)
    }

    override fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? {
        if (Ae2Compat.isLoaded) Ae2Storage.getFluidStorage(level, pos, face)?.let { return it }
        return base.getFluidStorage(level, pos, face)
    }

    // ME storage has no slots. A slot filter on an ME Interface sees its 9 local slots.
    override fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle? =
        base.getSlottedStorage(level, pos, face)

    // ---- items, single handle ----

    override fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.countItems(a, filter) else base.countItems(storage, filter)
    }

    override fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String? {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.findFirstItem(a, filter) else base.findFirstItem(storage, filter)
    }

    override fun findFirstItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): ItemInfo? {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.findFirstItemInfo(a, filter) else base.findFirstItemInfo(storage, filter)
    }

    override fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.findAllItemInfo(a, filter) else base.findAllItemInfo(storage, filter)
    }

    override fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.extractItems(a, filter, maxCount) else base.extractItems(storage, filter, maxCount)
    }

    override fun extractItemStacksMatching(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): List<ItemStack> {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.extractItemStacksMatching(a, filter, maxCount)
        else base.extractItemStacksMatching(storage, filter, maxCount)
    }

    override fun extractStacksByPredicate(storage: ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long): List<ItemStack> {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.extractStacksByPredicate(a, filter, maxCount)
        else base.extractStacksByPredicate(storage, filter, maxCount)
    }

    override fun countStacksByPredicate(storage: ItemStorageHandle, filter: (ItemStack) -> Boolean): Long {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.countStacksByPredicate(a, filter) else base.countStacksByPredicate(storage, filter)
    }

    override fun insertItemStack(storage: ItemStorageHandle, stack: ItemStack): Int {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.insertItemStack(a, stack) else base.insertItemStack(storage, stack)
    }

    override fun simulateInsertItem(dest: ItemStorageHandle, item: Item, maxCount: Long): Long {
        val a = ae2(dest)
        return if (a != null) Ae2Storage.simulateInsertItem(a, item, maxCount) else base.simulateInsertItem(dest, item, maxCount)
    }

    override fun tryInsertAll(dest: ItemStorageHandle, item: Item, count: Long): Boolean {
        val a = ae2(dest)
        return if (a != null) Ae2Storage.tryInsertAll(a, item, count) else base.tryInsertAll(dest, item, count)
    }

    // ---- items, two handles ----

    private fun crossesBackend(source: ItemStorageHandle, dest: ItemStorageHandle): Boolean =
        ae2(source) != null || ae2(dest) != null

    /** Loss-free move on the routed primitives: whatever dest rejects goes back to source. */
    private fun moveStacks(source: ItemStorageHandle, dest: ItemStorageHandle, taken: List<ItemStack>): Long {
        var moved = 0L
        for (stack in taken) {
            if (stack.isEmpty) continue
            val inserted = insertItemStack(dest, stack)
            moved += inserted
            if (inserted < stack.count) insertItemStack(source, stack.copyWithCount(stack.count - inserted))
        }
        return moved
    }

    /** Moves in rounds so a huge request against an unbounded source (an ME network)
     *  never holds more than one extraction's worth of stacks at a time. Stops when the
     *  request is met, the source runs dry, or dest starts rejecting. */
    private fun moveInRounds(source: ItemStorageHandle, dest: ItemStorageHandle, maxCount: Long, extract: (Long) -> List<ItemStack>): Long {
        var remaining = maxCount
        var moved = 0L
        while (remaining > 0L) {
            val taken = extract(remaining)
            if (taken.isEmpty()) break
            val movedNow = moveStacks(source, dest, taken)
            moved += movedNow
            remaining -= movedNow
            if (movedNow < taken.sumOf { it.count.toLong() }) break
        }
        return moved
    }

    override fun moveItems(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        if (!crossesBackend(source, dest)) return base.moveItems(source, dest, filter, maxCount)
        if (maxCount <= 0L) return 0L
        val s = ae2(source)
        val d = ae2(dest)
        if (s != null && d != null) return Ae2Storage.moveKeys(s, d, { filter(idOf(it)) }, maxCount)
        return moveInRounds(source, dest, maxCount) { extractItemStacksMatching(source, filter, it) }
    }

    override fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        if (!crossesBackend(source, dest)) return base.moveItemsVariant(source, dest, filter, maxCount)
        if (maxCount <= 0L) return 0L
        val pred: (ItemStack) -> Boolean = { filter(idOf(it), it.componentsPatch.size() > 0) }
        val s = ae2(source)
        val d = ae2(dest)
        if (s != null && d != null) return Ae2Storage.moveKeys(s, d, pred, maxCount)
        return moveInRounds(source, dest, maxCount) { extractStacksByPredicate(source, pred, it) }
    }

    override fun moveItemsByStackPredicate(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long): Long {
        if (!crossesBackend(source, dest)) return base.moveItemsByStackPredicate(source, dest, filter, maxCount)
        if (maxCount <= 0L) return 0L
        val s = ae2(source)
        val d = ae2(dest)
        if (s != null && d != null) return Ae2Storage.moveKeys(s, d, filter, maxCount)
        return moveInRounds(source, dest, maxCount) { extractStacksByPredicate(source, filter, it) }
    }

    /** Atomic. A request larger than one extraction can materialize fails cleanly
     *  (everything goes back) rather than allocating without bound. */
    override fun tryMoveAll(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, count: Long): Boolean {
        if (!crossesBackend(source, dest)) return base.tryMoveAll(source, dest, filter, count)
        if (count <= 0L) return true
        if (countItems(source, filter) < count) return false

        val taken = extractItemStacksMatching(source, filter, count)
        if (taken.sumOf { it.count.toLong() } < count) {
            for (s in taken) returnToSource(source, s)
            return false
        }

        val landed = ArrayList<ItemStack>()
        val leftovers = ArrayList<ItemStack>()
        for ((i, stack) in taken.withIndex()) {
            val inserted = insertItemStack(dest, stack)
            if (inserted > 0) landed += stack.copyWithCount(inserted)
            if (inserted < stack.count) {
                leftovers += stack.copyWithCount(stack.count - inserted)
                leftovers += taken.subList(i + 1, taken.size)
                break
            }
        }
        if (leftovers.isEmpty()) return true

        // Unwind: pull back what landed in dest, then return everything to source.
        for (l in landed) {
            leftovers += extractStacksByPredicate(dest, { ItemStack.isSameItemSameComponents(it, l) }, l.count.toLong())
        }
        for (s in leftovers) returnToSource(source, s)
        return false
    }

    private fun returnToSource(source: ItemStorageHandle, stack: ItemStack) {
        val back = insertItemStack(source, stack)
        if (back < stack.count) {
            logger.warn("tryMoveAll rollback: source refused returning {} x{}. State may diverge.", stack.item, stack.count - back)
        }
    }

    // ---- fluids ----

    // An ME network is unified item and fluid storage, so an AE2-backed card is never
    // treated as fluid-only. Both views stay visible through the same interface.
    override fun hasFluidCapacity(storage: FluidStorageHandle): Boolean =
        if (ae2(storage) != null) false else base.hasFluidCapacity(storage)

    override fun countFluid(storage: FluidStorageHandle, filter: (String) -> Boolean): Long {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.countFluid(a, filter) else base.countFluid(storage, filter)
    }

    override fun findFirstFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): FluidInfo? {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.findFirstFluidInfo(a, filter) else base.findFirstFluidInfo(storage, filter)
    }

    override fun findAllFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): List<FluidInfo> {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.findAllFluidInfo(a, filter) else base.findAllFluidInfo(storage, filter)
    }

    override fun insertFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Long {
        val a = ae2(dest)
        return if (a != null) Ae2Storage.insertFluid(a, fluidId, amount) else base.insertFluid(dest, fluidId, amount)
    }

    override fun simulateInsertFluid(dest: FluidStorageHandle, fluidId: String, maxAmount: Long): Long {
        val a = ae2(dest)
        return if (a != null) Ae2Storage.simulateInsertFluid(a, fluidId, maxAmount) else base.simulateInsertFluid(dest, fluidId, maxAmount)
    }

    override fun tryInsertAllFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Boolean {
        val a = ae2(dest)
        return if (a != null) Ae2Storage.tryInsertAllFluid(a, fluidId, amount) else base.tryInsertAllFluid(dest, fluidId, amount)
    }

    override fun extractFluid(storage: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long {
        val a = ae2(storage)
        return if (a != null) Ae2Storage.extractFluid(a, filter, maxAmount) else base.extractFluid(storage, filter, maxAmount)
    }

    private fun crossesBackend(source: FluidStorageHandle, dest: FluidStorageHandle): Boolean =
        ae2(source) != null || ae2(dest) != null

    // Fluids are count-based end to end, no stacks exist, so no round limits are needed.
    override fun moveFluid(source: FluidStorageHandle, dest: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long {
        if (!crossesBackend(source, dest)) return base.moveFluid(source, dest, filter, maxAmount)
        if (maxAmount <= 0L) return 0L
        val info = findFirstFluidInfo(source, filter) ?: return 0L
        val room = simulateInsertFluid(dest, info.fluidId, minOf(maxAmount, info.amount))
        if (room <= 0L) return 0L
        val drained = extractFluid(source, { it == info.fluidId }, room)
        val filled = insertFluid(dest, info.fluidId, drained)
        if (filled < drained) insertFluid(source, info.fluidId, drained - filled)
        return filled
    }

    override fun tryMoveAllFluid(source: FluidStorageHandle, dest: FluidStorageHandle, filter: (String) -> Boolean, amount: Long): Boolean {
        if (!crossesBackend(source, dest)) return base.tryMoveAllFluid(source, dest, filter, amount)
        if (amount <= 0L) return true
        val info = findFirstFluidInfo(source, filter) ?: return false
        if (info.amount < amount) return false
        if (simulateInsertFluid(dest, info.fluidId, amount) < amount) return false
        val drained = extractFluid(source, { it == info.fluidId }, amount)
        if (drained < amount) {
            insertFluid(source, info.fluidId, drained)
            return false
        }
        val filled = insertFluid(dest, info.fluidId, drained)
        if (filled < drained) {
            extractFluid(dest, { it == info.fluidId }, filled)
            insertFluid(source, info.fluidId, drained)
            return false
        }
        return true
    }
}
