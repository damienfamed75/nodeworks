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
import net.neoforged.neoforge.transfer.ResourceHandler
import net.neoforged.neoforge.transfer.ResourceHandlerUtil
import net.neoforged.neoforge.transfer.item.ItemResource
import net.neoforged.neoforge.transfer.resource.Resource
import net.neoforged.neoforge.transfer.transaction.Transaction
import net.neoforged.neoforge.transfer.transaction.TransactionContext

class NeoForgeStorageService : StorageService {

    override fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        // 26.1: Capabilities.ItemHandler.BLOCK (IItemHandler) was replaced by
        //  Capabilities.Item.BLOCK (ResourceHandler<ItemResource>). The IItemHandler.of(...)
        //  adapter is NeoForge's official migration ease path, keeps existing slot-based
        //  logic intact while consuming the new resource-handler capability.
        val resourceHandler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeItemStorageHandle(IItemHandler.of(resourceHandler), resourceHandler)
    }

    override fun moveItems(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor?,
        accept: ((ItemStack) -> Boolean)?,
        onMoved: ((ItemStack, Long) -> Unit)?,
    ): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount
        val refused = HashSet<ItemResource>()
        val slots = src.slots
        if (slots <= 0) return 0L
        // Each slot is visited at most once per call, starting where the last call stopped.
        val start = cursor?.slot?.takeIf { it in 0 until slots } ?: 0

        for (i in 0 until slots) {
            val slot = (start + i) % slots
            if (remaining <= 0 || MoveDeadline.expired()) {
                cursor?.slot = slot
                return total
            }
            // A source can shrink mid-scan, never read past its current end.
            if (slot >= src.slots) continue
            val stack = src.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (!filter(itemId)) continue
            if (accept != null && !accept(stack)) continue
            if (ItemResource.of(stack) in refused) continue

            val moved = moveFromSlot(src, slot, dest, remaining, refused, onMoved)
            total += moved
            remaining -= moved
            // The count is met on this slot, which may hold more, so the next call starts here.
            if (remaining <= 0L) {
                cursor?.slot = slot
                return total
            }
        }
        return total
    }

    override fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        val src = (source as NeoForgeItemStorageHandle).handler
        val dst = (dest as NeoForgeItemStorageHandle).handler
        var total = 0L
        var remaining = maxCount
        val refused = HashSet<ItemResource>()

        for (slot in 0 until src.slots) {
            if (remaining <= 0 || MoveDeadline.expired()) break
            val stack = src.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            val hasData = stack.componentsPatch.size() > 0
            if (!filter(itemId, hasData)) continue
            if (ItemResource.of(stack) in refused) continue

            val moved = moveFromSlot(src, slot, dest, remaining, refused)
            total += moved
            remaining -= moved
        }
        return total
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
        val refused = HashSet<ItemResource>()

        for (slot in 0 until src.slots) {
            if (remaining <= 0 || MoveDeadline.expired()) break
            val stack = src.getStackInSlot(slot)
            if (stack.isEmpty) continue
            // Predicate sees the full slot stack for component-aware matching.
            if (!filter(stack)) continue
            if (ItemResource.of(stack) in refused) continue

            val moved = moveFromSlot(src, slot, dest, remaining, refused)
            total += moved
            remaining -= moved
        }
        return total
    }

    /** Take up to [amount] from [slot], asking until it is met or the slot is empty. A
     *  handler returns at most one stack per call, so slots holding more (drawers) need
     *  repeated asks. Stops if the slot starts holding a different item. */
    private inline fun drainSlot(handler: IItemHandler, slot: Int, amount: Long, take: (ItemStack) -> Unit): Long {
        var got = 0L
        var first: ItemStack? = null
        while (got < amount) {
            val ask = minOf(amount - got, Int.MAX_VALUE.toLong()).toInt()
            if (first != null && !ItemStack.isSameItemSameComponents(handler.extractItem(slot, ask, true), first)) break
            val extracted = handler.extractItem(slot, ask, false)
            if (extracted.isEmpty) break
            if (first == null) first = extracted.copyWithCount(1)
            take(extracted)
            got += extracted.count
            // Less than asked and less than a stack means the slot ran out, skip asking again.
            if (extracted.count < minOf(ask, extracted.maxStackSize)) break
        }
        return got
    }

    /** Move up to [amount] from one source slot into [dest], one stack per round, until
     *  it is met, the slot runs dry, dest refuses, or the [MoveDeadline] passes. A type dest
     *  refuses goes in [refused] so the caller skips its other slots. Dest only fills up during
     *  one move, so a refused type can't fit later in it. */
    private fun moveFromSlot(
        src: IItemHandler,
        slot: Int,
        dest: NeoForgeItemStorageHandle,
        amount: Long,
        refused: MutableSet<ItemResource>,
        onMoved: ((ItemStack, Long) -> Unit)? = null,
    ): Long {
        var moved = 0L
        var first: ItemStack? = null
        while (moved < amount) {
            if (moved > 0L && MoveDeadline.expired()) break
            if (slot >= src.slots) break
            val offered = src.extractItem(slot, minOf(amount - moved, Int.MAX_VALUE.toLong()).toInt(), true)
            if (offered.isEmpty) break
            // Same guard as [drainSlot].
            if (first != null && !ItemStack.isSameItemSameComponents(offered, first)) break
            if (first == null) first = offered.copyWithCount(1)
            val inserted = insertInto(dest, offered, simulate = false)
            if (inserted <= 0) {
                refused += ItemResource.of(offered)
                break
            }
            val taken = src.extractItem(slot, inserted, false).count
            moved += inserted
            onMoved?.invoke(offered, inserted.toLong())
            // A source giving back less than it offered would duplicate, never repeat that.
            if (taken < inserted || inserted < offered.count) break
            // Less than a stack means the slot ran out or the request is met.
            if (offered.count < offered.maxStackSize) break
        }
        return moved
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
            val got = drainSlot(handler, slot, remaining) {}
            total += got
            remaining -= got
        }
        return total
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
        for (slot in 0 until handler.slots) {
            if (remaining <= 0L) break
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (!filter(itemId)) continue
            // extractItem returns a real stack with the slot's components intact,
            // unlike extractItems which only sums counts. Returning these directly
            // preserves durability, enchantments, custom names, dye colour, etc.
            remaining -= drainSlot(handler, slot, remaining) { out.add(it) }
        }
        return out
    }

    /** Take up to [maxCount] from one [slot] of [storage] as real stacks, components intact.
     *  Empty when the slot doesn't exist (any more). */
    fun extractFromSlot(storage: ItemStorageHandle, slot: Int, maxCount: Long): List<ItemStack> {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        if (maxCount <= 0L || slot !in 0 until handler.slots) return emptyList()
        val out = ArrayList<ItemStack>()
        drainSlot(handler, slot, maxCount) { out.add(it) }
        return out
    }

    override fun extractStacksByPredicate(
        storage: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor?,
    ): List<ItemStack> {
        if (maxCount <= 0L) return emptyList()
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        val slots = handler.slots
        // Each slot is visited at most once per call, starting where the last call
        // stopped, and the cursor lands after the last slot that gave something up so
        // the next call carries on from there instead of rescanning the start.
        val start = cursor?.slot?.takeIf { it in 0 until slots } ?: 0
        var lastTaken = -1
        for (i in 0 until slots) {
            val slot = (start + i) % slots
            if (remaining <= 0L) {
                cursor?.slot = slot
                return out
            }
            if (slot >= handler.slots) continue
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            // Predicate sees the full slot stack so the caller can match on
            // component-bearing identity (e.g. only Strength Potions, not
            // every variant of `minecraft:potion`).
            if (!filter(stack)) continue
            val got = drainSlot(handler, slot, remaining) { out.add(it) }
            if (got > 0L) lastTaken = slot
            remaining -= got
        }
        if (cursor != null && lastTaken >= 0 && slots > 0) cursor.slot = (lastTaken + 1) % slots
        return out
    }


    override fun peekStack(storage: ItemStorageHandle, filter: (ItemStack) -> Boolean, cursor: ScanCursor?): ItemStack? {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val slots = handler.slots
        if (slots <= 0) return null
        val start = cursor?.slot?.takeIf { it in 0 until slots } ?: 0
        for (i in 0 until slots) {
            val slot = (start + i) % slots
            // A huge source with nothing to give is covered over several calls.
            if (i > 0 && (i and 63) == 0 && MoveDeadline.expired()) {
                cursor?.slot = slot
                return null
            }
            if (slot >= handler.slots) continue
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty || !filter(stack)) continue
            // Only what the source would let go, so a slot that shows its contents but
            // refuses extraction, a furnace input, is never planned for.
            val takeable = handler.extractItem(slot, stack.count, true)
            if (takeable.isEmpty) continue
            cursor?.slot = slot
            return takeable
        }
        return null
    }

    override fun simulateInsertStack(dest: ItemStorageHandle, stack: ItemStack): Long =
        if (stack.isEmpty) 0L else insertInto(dest as NeoForgeItemStorageHandle, stack, simulate = true).toLong()

    override fun countStacksByPredicate(
        storage: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
    ): Long {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        var total = 0L
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            if (filter(stack)) total += stack.count
        }
        return total
    }

    /** Insert [stack] into [handle], returning how many went in. A storage that routes
     *  inserts itself (a storage controller, a drawer network) gets one whole-handler call
     *  and picks the slot. Walking every slot per stack instead froze the server for minutes
     *  on a controller with tens of thousands of slots. Plain inventories keep the stacking
     *  walk so chests still top up existing stacks before empty slots. */
    private fun insertInto(handle: NeoForgeItemStorageHandle, stack: ItemStack, simulate: Boolean): Int {
        if (stack.isEmpty) return 0
        val routed = handle.routed
        if (routed != null && routesInsert(routed)) {
            Transaction.openRoot().use { tx ->
                val inserted = routed.insert(ItemResource.of(stack), stack.count, tx)
                if (!simulate) tx.commit()
                if (!simulate && inserted > 0) MoveDeadline.progressed()
                return inserted
            }
        }
        val leftover = ItemHandlerHelper.insertItemStacked(handle.handler, stack.copy(), simulate)
        if (!simulate && leftover.count < stack.count) MoveDeadline.progressed()
        return stack.count - leftover.count
    }

    private val routesInsertByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()

    /** True when [handler] overrides the whole-handler insert. The interface default just
     *  loops slots, so only an override means the storage has its own routing. */
    private fun routesInsert(handler: ResourceHandler<*>): Boolean = routesInsertByClass.getOrPut(handler.javaClass) {
        try {
            handler.javaClass.getMethod(
                "insert", Resource::class.java, Int::class.javaPrimitiveType, TransactionContext::class.java,
            ).declaringClass != ResourceHandler::class.java
        } catch (e: NoSuchMethodException) {
            false
        }
    }

    override fun insertItemStack(storage: ItemStorageHandle, stack: ItemStack): Int =
        insertInto(storage as NeoForgeItemStorageHandle, stack, simulate = false)

    /** What an extract of [maxCount] matching items would return, merged to one stack per
     *  distinct item and components with the total as its count, at most [maxStacks] of
     *  them. Read-only. The flag is true when every slot was looked at. */
    fun previewStacks(storage: ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long, maxStacks: Int): Pair<List<ItemStack>, Boolean> {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        val merged = LinkedHashMap<ItemResource, ItemStack>()
        var remaining = maxCount
        for (slot in 0 until handler.slots) {
            if (remaining <= 0L) return ArrayList(merged.values) to false
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty || !filter(stack)) continue
            val take = minOf(remaining, stack.count.toLong()).toInt()
            val key = ItemResource.of(stack)
            val existing = merged[key]
            if (existing == null) {
                if (merged.size >= maxStacks) return ArrayList(merged.values) to false
                merged[key] = stack.copyWithCount(take)
            } else {
                existing.grow(take)
            }
            remaining -= take
        }
        return ArrayList(merged.values) to (remaining > 0L)
    }

    /** The item types [dest] can still take, as one-item templates, when it is a plain
     *  inventory with no empty slot: only what is already in it. Null when it might take
     *  anything, because it has an empty slot or routes inserts itself. */
    fun acceptedTypes(dest: ItemStorageHandle): List<ItemStack>? {
        val handle = dest as NeoForgeItemStorageHandle
        val routed = handle.routed
        if (routed != null && routesInsert(routed)) return null
        val handler = handle.handler
        val types = LinkedHashMap<ItemResource, ItemStack>()
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) return null
            types.putIfAbsent(ItemResource.of(stack), stack.copyWithCount(1))
        }
        return ArrayList(types.values)
    }

    /** How many of [stacks] [dest] would accept, all simulated in one transaction that is
     *  never committed, so each stack sees the space the earlier ones used. Null when
     *  [dest] is a slot view with no handler to simulate against. */
    fun simulateInsertStacks(dest: ItemStorageHandle, stacks: List<ItemStack>): Long? {
        val routed = (dest as NeoForgeItemStorageHandle).routed ?: return null
        val routes = routesInsert(routed)
        var total = 0L
        Transaction.openRoot().use { tx ->
            for (stack in stacks) {
                if (stack.isEmpty) continue
                val resource = ItemResource.of(stack)
                if (!routes) {
                    total += ResourceHandlerUtil.insertStacking(routed, resource, stack.count, tx)
                    continue
                }
                // Repeat until refused, a storage may fill only part of a merged stack per call.
                var left = stack.count
                while (left > 0) {
                    val put = routed.insert(resource, left, tx)
                    if (put <= 0) break
                    total += put
                    left -= put
                }
            }
        }
        return total
    }

    override fun simulateInsertItem(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, maxCount: Long): Long {
        if (maxCount <= 0L) return 0L
        val capped = minOf(maxCount, Int.MAX_VALUE.toLong()).toInt()
        // Transactional simulate, aborted on close, so the storage is left untouched.
        return insertInto(dest as NeoForgeItemStorageHandle, ItemStack(item, capped), simulate = true).toLong()
    }

    override fun tryInsertAll(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, count: Long): Boolean {
        if (count <= 0L) return true
        if (count > Int.MAX_VALUE.toLong()) return false
        val handle = dest as NeoForgeItemStorageHandle
        val stack = ItemStack(item, count.toInt())
        // Simulate first. On a single-threaded server the real insert sees the same state,
        // so a successful simulation guarantees the commit.
        if (insertInto(handle, stack, simulate = true) < stack.count) return false
        val inserted = insertInto(handle, stack, simulate = false)
        if (inserted < stack.count) {
            // Simulation and commit diverged. Take back what landed to keep the atomic contract.
            extractByItem(handle.handler, item, inserted)
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

        // Step 1: real extract from source, collecting exactly `count` matching items.
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

        // Step 2: atomic insert into dest, per item type. Sim-then-commit in sequence is
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
                // Sim/real divergence, take back what did land and unwind everything.
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
     *  overflow drops on the floor rather than looping forever, better than hanging. */
    private fun restoreToSource(src: IItemHandler, stacks: List<ItemStack>) {
        for (s in stacks) {
            val leftover = try {
                ItemHandlerHelper.insertItemStacked(src, s.copy(), false)
            } catch (e: Exception) {
                s.copy()
            }
            // The source can't take back items it just emitted. Spill rather than delete.
            if (!leftover.isEmpty) MoveSpill.spill(leftover, "tryMoveAll rollback: the source refused the items")
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
            remaining -= drainSlot(handler, slot, remaining.toLong()) {}.toInt()
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
                    hasData = stack.componentsPatch.size() > 0,
                    componentsPatch = stack.componentsPatch,
                )
            }
        }
        return null
    }

    override fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> =
        findAllItemInfoInRange(storage, 0, Int.MAX_VALUE, filter)

    override fun slotCount(storage: ItemStorageHandle): Int =
        (storage as? NeoForgeItemStorageHandle)?.handler?.slots ?: -1

    override fun findAllItemInfoInRange(
        storage: ItemStorageHandle,
        from: Int,
        until: Int,
        filter: (String) -> Boolean,
    ): List<ItemInfo> {
        val handler = (storage as NeoForgeItemStorageHandle).handler
        // Aggregation key is the full BufferKey so stacks with distinct
        // DataComponents (different potions, dyed armor, enchanted books) stay
        // separate entries in the result instead of collapsing under a single
        // `hasData=true` bucket. The old key was `"$itemId:$hasData"` which
        // hashed all five potion variants together and the Inventory Terminal
        // / network:find / card:find all displayed one with the wrong count.
        val aggregated = LinkedHashMap<damien.nodeworks.script.BufferKey.Key, ItemInfo>()
        val end = minOf(until, handler.slots)
        for (slot in from.coerceAtLeast(0) until end) {
            val stack = handler.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            if (!filter(itemId)) continue
            val cacheKey = damien.nodeworks.script.BufferKey.of(stack)
            val existing = aggregated[cacheKey]
            if (existing != null) {
                // Aggregate count, keep the first-sampled stack's components as the
                // representative for client-side display (durability bars, custom
                // names, enchantment glints). Distinct components already routed
                // to distinct cacheKey buckets, so this only merges truly
                // identical stacks split across slots.
                aggregated[cacheKey] = existing.copy(count = existing.count + stack.count)
            } else {
                aggregated[cacheKey] = ItemInfo(
                    itemId = itemId,
                    name = stack.hoverName.string,
                    count = stack.count.toLong(),
                    maxStackSize = stack.item.getDefaultMaxStackSize(),
                    hasData = !cacheKey.isPlain,
                    componentsPatch = stack.componentsPatch,
                )
            }
        }
        return aggregated.values.toList()
    }

    override fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle? {
        val resourceHandler = level.getCapability(Capabilities.Item.BLOCK, pos, face) ?: return null
        return NeoForgeSlottedStorageHandle(IItemHandler.of(resourceHandler))
    }

    // --- Energy side ---

    override fun getEnergy(level: ServerLevel, pos: BlockPos, face: Direction): EnergyInfo? {
        // Some blocks only answer a side-less query, so that is the fallback.
        val handler = level.getCapability(Capabilities.Energy.BLOCK, pos, face)
            ?: level.getCapability(Capabilities.Energy.BLOCK, pos, null)
            ?: return null
        return EnergyInfo(handler.getAmountAsLong(), handler.getCapacityAsLong())
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
        // Aggregate across tanks, first matching id wins, amount summed.
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
                // Sim/real divergence, push the leftover back into source.
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

    override fun simulateInsertFluid(dest: FluidStorageHandle, fluidId: String, maxAmount: Long): Long {
        if (maxAmount <= 0L) return 0L
        val id = net.minecraft.resources.Identifier.tryParse(fluidId) ?: return 0L
        val fluid = BuiltInRegistries.FLUID.getValue(id) ?: return 0L
        val handler = (dest as NeoForgeFluidStorageHandle).handler
        val capped = minOf(maxAmount, Int.MAX_VALUE.toLong()).toInt()
        val stack = FluidStack(fluid, capped)
        return handler.fill(stack, IFluidHandler.FluidAction.SIMULATE).toLong()
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
            // Unexpected divergence, drain whatever landed.
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

/** [routed] is the storage's own handler when it was resolved from a capability, used by
 *  [NeoForgeStorageService] for inserts the storage can route itself. Null for slot views. */
class NeoForgeItemStorageHandle(
    val handler: IItemHandler,
    val routed: ResourceHandler<ItemResource>? = null,
) : ItemStorageHandle

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
