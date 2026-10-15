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

    private companion object {
        const val ROUND_ITEMS = 4096L
    }

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

    // An ME network has no slots, so it reports -1 and is always scanned whole.
    override fun slotCount(storage: ItemStorageHandle): Int =
        if (ae2(storage) != null) -1 else base.slotCount(storage)

    override fun findAllItemInfoInRange(
        storage: ItemStorageHandle,
        from: Int,
        until: Int,
        filter: (String) -> Boolean,
    ): List<ItemInfo> {
        val a = ae2(storage)
        return if (a != null) { if (from == 0) Ae2Storage.findAllItemInfo(a, filter) else emptyList() }
        else base.findAllItemInfoInRange(storage, from, until, filter)
    }

    override fun snapshotEntries(storage: ItemStorageHandle, memo: Any?): EntrySnapshot? =
        ae2(storage)?.let { Ae2Storage.snapshotEntries(it, memo) }

    override fun readEntries(
        snapshot: EntrySnapshot,
        from: Int,
        until: Int,
        visit: (damien.nodeworks.script.BufferKey.Key, ItemInfo) -> Unit,
    ) {
        // Guarded like [ae2] so a server without AE2 never loads its classes.
        if (Ae2Compat.isLoaded && snapshot is Ae2Storage.Ae2EntrySnapshot) {
            Ae2Storage.readEntries(snapshot, from, until, visit)
        }
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
        // First stack not yet handed to dest, everything from here on is still ours.
        var next = 0
        try {
            while (next < taken.size) {
                val stack = taken[next]
                if (stack.isEmpty) { next++; continue }
                val inserted = insertItemStack(dest, stack)
                next++
                moved += inserted
                if (inserted < stack.count) returnToSource(source, dest, stack.copyWithCount(stack.count - inserted))
            }
        } catch (e: Throwable) {
            returnRemaining(source, dest, taken, next)
            throw e
        }
        return moved
    }

    /** A storage threw mid-move. The stack being inserted may have partly landed, so
     *  returning it whole risks a duplicate, which beats deleting it. */
    private fun returnRemaining(source: ItemStorageHandle, dest: ItemStorageHandle, taken: List<ItemStack>, from: Int) {
        logger.error("A storage threw during a move, returning {} held stacks to the source", taken.size - from)
        for (i in from until taken.size) returnToSource(source, dest, taken[i])
    }

    /** Moves in rounds of at most [ROUND_ITEMS] so a huge request against an unbounded
     *  source (an ME network) holds little in flight and can stop at a deadline between
     *  rounds. Stops when the request is met, the source runs dry, dest starts rejecting,
     *  or the [MoveDeadline] passes. */
    private fun moveInRounds(source: ItemStorageHandle, dest: ItemStorageHandle, maxCount: Long, extract: (Long) -> List<ItemStack>): Long {
        var remaining = maxCount
        var moved = 0L
        while (remaining > 0L && !MoveDeadline.expired()) {
            val taken = extract(minOf(remaining, ROUND_ITEMS))
            if (taken.isEmpty()) break
            val movedNow = moveStacks(source, dest, taken)
            moved += movedNow
            remaining -= movedNow
            if (movedNow < taken.sumOf { it.count.toLong() }) break
        }
        return moved
    }

    /** ME into a slotted storage, one item type at a time, taking only what fits so a
     *  destination that accepts some items (drawers, a full chest) still gets them. A
     *  full plain inventory can only take what it holds, so those types are looked up
     *  directly instead of walking the network. */
    private fun moveFromMe(
        s: Ae2ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor? = null,
    ): Long {
        var remaining = maxCount
        var moved = 0L
        val step: (ItemStack, Long) -> Boolean = step@{ template, available ->
            val want = minOf(remaining, available, Int.MAX_VALUE.toLong())
            if (want <= 0L) return@step remaining > 0L
            val fits = base.simulateInsertStacks(dest, listOf(template.copyWithCount(want.toInt()))) ?: want
            if (fits > 0L) {
                val movedNow = moveStacks(s, dest, Ae2Storage.extractOf(s, template, fits))
                moved += movedNow
                remaining -= movedNow
            }
            remaining > 0L && !MoveDeadline.expired()
        }
        val accepted = base.acceptedTypes(dest)
        if (accepted == null) {
            // Resuming where the last call stopped means every type gets its turn, even when
            // dest is slow to ask and a call only gets through part of a big network.
            if (cursor != null) {
                cursor.key = Ae2Storage.forEachStackFrom(s, filter, cursor.key, cursor.keyDone, step)
                // Out of items, the type it stopped on likely has more, so start on it again.
                // Out of time, that type had its turn, so move past it.
                cursor.keyDone = remaining > 0L
            } else {
                Ae2Storage.forEachStack(s, filter, step)
            }
        } else {
            for (template in accepted) {
                if (!filter(template)) continue
                if (!step(template, Ae2Storage.countOf(s, template))) break
            }
        }
        return moved
    }

    override fun moveItems(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor?,
    ): Long {
        if (!crossesBackend(source, dest)) return base.moveItems(source, dest, filter, maxCount, cursor)
        if (maxCount <= 0L) return 0L
        val s = ae2(source)
        val d = ae2(dest)
        if (s != null && d != null) return Ae2Storage.moveKeys(s, d, { filter(idOf(it)) }, maxCount)
        if (s != null) return moveFromMe(s, dest, { filter(idOf(it)) }, maxCount, cursor)
        val handler = (source as? NeoForgeItemStorageHandle)?.handler
        if (cursor != null && d != null && handler != null) return moveSlotsToMe(source, handler, d, filter, maxCount, cursor)
        return moveInRounds(source, dest, maxCount) { extractItemStacksMatching(source, filter, it) }
    }

    /** Slotted source into ME, scanned from [cursor] and wrapping, so a source bigger than one
     *  call can get through is covered over several calls. ME is asked first, so a type it
     *  won't take (a full type limit) is skipped without being pulled out and put back, and
     *  later slots still get their turn. Items are taken before they are inserted, and
     *  whatever ME then refuses goes back, as in [moveInRounds]. */
    private fun moveSlotsToMe(
        source: ItemStorageHandle,
        handler: net.neoforged.neoforge.items.IItemHandler,
        d: Ae2ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor,
    ): Long {
        val slots = handler.slots
        if (slots <= 0) return 0L
        val start = cursor.slot.takeIf { it in 0 until slots } ?: 0
        val refused = HashSet<net.neoforged.neoforge.transfer.item.ItemResource>()
        var remaining = maxCount
        var moved = 0L
        for (i in 0 until slots) {
            val slot = (start + i) % slots
            if (remaining <= 0L || MoveDeadline.expired()) {
                cursor.slot = slot
                return moved
            }
            // A source can shrink mid-scan, never read past its current end.
            if (slot >= handler.slots) continue
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty || !filter(idOf(stack))) continue
            val resource = net.neoforged.neoforge.transfer.item.ItemResource.of(stack)
            if (resource in refused) continue
            val want = minOf(remaining, stack.count.toLong())
            val fits = Ae2Storage.simulateInsertStacks(d, listOf(stack.copyWithCount(want.toInt())))
            if (fits <= 0L) {
                refused += resource
                continue
            }
            val taken = base.extractFromSlot(source, slot, fits)
            if (taken.isEmpty()) continue
            val movedNow = moveStacks(source, d, taken)
            moved += movedNow
            remaining -= movedNow
            // ME took less than it said it would, it is full for this type now.
            if (movedNow < taken.sumOf { it.count.toLong() }) refused += resource
        }
        return moved
    }

    override fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        if (!crossesBackend(source, dest)) return base.moveItemsVariant(source, dest, filter, maxCount)
        if (maxCount <= 0L) return 0L
        val pred: (ItemStack) -> Boolean = { filter(idOf(it), it.componentsPatch.size() > 0) }
        val s = ae2(source)
        val d = ae2(dest)
        if (s != null && d != null) return Ae2Storage.moveKeys(s, d, pred, maxCount)
        if (s != null) return moveFromMe(s, dest, pred, maxCount)
        return moveInRounds(source, dest, maxCount) { extractStacksByPredicate(source, pred, it) }
    }

    override fun moveItemsByStackPredicate(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long): Long {
        if (!crossesBackend(source, dest)) return base.moveItemsByStackPredicate(source, dest, filter, maxCount)
        if (maxCount <= 0L) return 0L
        val s = ae2(source)
        val d = ae2(dest)
        if (s != null && d != null) return Ae2Storage.moveKeys(s, d, filter, maxCount)
        if (s != null) return moveFromMe(s, dest, filter, maxCount)
        return moveInRounds(source, dest, maxCount) { extractStacksByPredicate(source, filter, it) }
    }

    /** Reads the source and simulates the destination, so the cost is a source scan plus
     *  one simulated insert per distinct item, with nothing extracted. */
    override fun previewMove(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long, maxStacks: Int): MovePreview {
        val s = ae2(source)
        val (stacks, sawAll) = if (s != null) Ae2Storage.previewStacks(s, filter, maxCount, maxStacks)
            else base.previewStacks(source, filter, maxCount, maxStacks)
        val available = stacks.sumOf { it.count.toLong() }
        // A capped run that stopped early can't rule the move out, so don't pay for asking dest.
        if (maxStacks != Int.MAX_VALUE && !sawAll) return MovePreview(available, null, false)
        val d = ae2(dest)
        val fits = if (d != null) Ae2Storage.simulateInsertStacks(d, stacks) else base.simulateInsertStacks(dest, stacks)
        return MovePreview(available, fits, sawAll)
    }

    /** Atomic. A request larger than one extraction can materialize fails cleanly
     *  (everything goes back) rather than allocating without bound. Used for every
     *  backend, the base version regroups stacks by item and strips their components. */
    override fun tryMoveAll(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, count: Long): Boolean {
        if (count <= 0L) return true
        if (countItems(source, filter) < count) return false

        val taken = extractItemStacksMatching(source, filter, count)
        if (taken.sumOf { it.count.toLong() } < count) {
            for (s in taken) returnToSource(source, dest, s)
            return false
        }

        val landed = ArrayList<ItemStack>()
        val leftovers = ArrayList<ItemStack>()
        // First stack not yet handed to dest, everything from here on is still ours.
        var next = 0
        try {
            while (next < taken.size) {
                val stack = taken[next]
                val inserted = insertItemStack(dest, stack)
                next++
                if (inserted > 0) landed += stack.copyWithCount(inserted)
                if (inserted < stack.count) {
                    leftovers += stack.copyWithCount(stack.count - inserted)
                    break
                }
            }
        } catch (e: Throwable) {
            returnRemaining(source, dest, taken, next)
            throw e
        }
        if (next < taken.size) leftovers += taken.subList(next, taken.size)
        if (leftovers.isEmpty()) return true

        // Unwind: pull back what landed in dest, then return everything to source.
        // Whatever can't be pulled back stays in dest, delivered rather than lost.
        for (l in landed) {
            leftovers += try {
                extractStacksByPredicate(dest, { ItemStack.isSameItemSameComponents(it, l) }, l.count.toLong())
            } catch (e: Exception) {
                logger.warn("tryMoveAll unwind: could not pull {} back from the destination", l.item, e)
                emptyList()
            }
        }
        for (s in leftovers) returnToSource(source, dest, s)
        return false
    }

    /** Put [stack] back where it came from. Never throws and never deletes: if the
     *  source refuses it, the destination it was headed for gets it, and if neither
     *  takes it, it is spilled into the world. */
    private fun returnToSource(source: ItemStorageHandle, dest: ItemStorageHandle, stack: ItemStack) {
        var left = stack
        for (target in listOf(source, dest)) {
            if (left.isEmpty) return
            try {
                left = left.copyWithCount(left.count - insertItemStack(target, left))
            } catch (e: Exception) {
                logger.warn("Move rollback: a storage threw taking {} back", left.item, e)
            }
        }
        if (!left.isEmpty) MoveSpill.spill(left, "Move rollback: neither storage would take the items back")
    }

    // ---- fluids ----

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
        val filled = fillOrReturn(source, dest, info.fluidId, drained)
        if (filled < drained) returnFluid(source, info.fluidId, drained - filled)
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
            returnFluid(source, info.fluidId, drained)
            return false
        }
        val filled = fillOrReturn(source, dest, info.fluidId, drained)
        if (filled < drained) {
            extractFluid(dest, { it == info.fluidId }, filled)
            returnFluid(source, info.fluidId, drained)
            return false
        }
        return true
    }

    /** Fill [dest] with fluid already drained from [source]. A throw returns it all first. */
    private fun fillOrReturn(source: FluidStorageHandle, dest: FluidStorageHandle, fluidId: String, drained: Long): Long =
        try {
            insertFluid(dest, fluidId, drained)
        } catch (e: Throwable) {
            returnFluid(source, fluidId, drained)
            throw e
        }

    /** Fluid has no world form to spill, so a refused return is logged as an error. */
    private fun returnFluid(source: FluidStorageHandle, fluidId: String, amount: Long) {
        val back = try { insertFluid(source, fluidId, amount) } catch (e: Exception) { 0L }
        if (back < amount) logger.error("Fluid move rollback: source refused {} mB of {}", amount - back, fluidId)
    }
}
