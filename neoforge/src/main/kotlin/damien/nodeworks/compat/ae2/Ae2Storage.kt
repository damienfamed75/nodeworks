package damien.nodeworks.compat.ae2

import appeng.api.AECapabilities
import appeng.api.config.Actionable
import appeng.api.networking.security.IActionSource
import appeng.api.networking.storage.IStorageService
import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.AEItemKey
import appeng.api.stacks.AEKey
import appeng.api.stacks.KeyCounter
import appeng.api.storage.MEStorage
import damien.nodeworks.platform.EntrySnapshot
import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.FluidStorageHandle
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.script.BufferKey
import damien.nodeworks.script.EntryReuse
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.material.Fluids
import java.util.Collections
import java.util.IdentityHashMap

/** Item view of an ME network reached through [AECapabilities.ME_STORAGE], which an
 *  unconfigured ME Interface exposes. AE2 keeps counts aggregated per key, so every
 *  operation here is O(distinct types) with no slot iteration at all. [grid] is the
 *  owning network when the block is a grid node, used to read its inventory cache. */
class Ae2ItemStorageHandle(val me: MEStorage, val grid: IStorageService?) : ItemStorageHandle

/** Fluid view of the same network. One [MEStorage] holds both, keys are filtered by type. */
class Ae2FluidStorageHandle(val me: MEStorage, val grid: IStorageService?) : FluidStorageHandle

object Ae2Storage {
    private val source: IActionSource = IActionSource.empty()

    /** Upper bound on ItemStacks one extraction call materializes. An ME network is
     *  not slot-bounded, so a huge request could otherwise allocate millions of
     *  stacks in one tick. Bulk moves loop in rounds instead, see
     *  [damien.nodeworks.platform.CompositeStorageService]. */
    private const val MAX_STACKS_PER_EXTRACT = 16_384

    fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? {
        val me = level.getCapability(AECapabilities.ME_STORAGE, pos, face) ?: return null
        return Ae2ItemStorageHandle(me, gridStorage(level, pos, face))
    }

    fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? {
        val me = level.getCapability(AECapabilities.ME_STORAGE, pos, face) ?: return null
        return Ae2FluidStorageHandle(me, gridStorage(level, pos, face))
    }

    private fun gridStorage(level: ServerLevel, pos: BlockPos, face: Direction): IStorageService? =
        level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, pos, null)
            ?.getGridNode(face)?.grid?.storageService

    // ---- per-tick snapshot ----
    // One snapshot per ME storage per tick, shared by every device that asks, so AE2
    // walks its cells at most once per tick per grid. The grid inventory itself has a
    // cache AE2 already maintains, read for free. Our own writes are applied to the
    // snapshot as deltas, so a script making many moves in one tick never rewalks.

    private var snapshotTick = Long.MIN_VALUE
    private val snapshots = IdentityHashMap<MEStorage, KeyCounter>()
    private val mutated: MutableSet<MEStorage> = Collections.newSetFromMap(IdentityHashMap())

    /** Snapshots that are our private copies, safe to apply deltas to. */
    private val owned: MutableSet<MEStorage> = Collections.newSetFromMap(IdentityHashMap())

    private fun rollTick() {
        val tick = PlatformServices.modState.tickCount
        if (tick == snapshotTick) return
        snapshots.clear()
        mutated.clear()
        owned.clear()
        snapshotTick = tick
    }

    private fun snapshot(me: MEStorage, grid: IStorageService?): KeyCounter {
        rollTick()
        snapshots[me]?.let { return it }
        // AE2's cache lags our own writes until its next tick, so after one, walk the cells.
        val shared = grid != null && grid.inventory === me && me !in mutated
        val fresh = if (shared) grid!!.cachedInventory else me.availableStacks
        if (!shared) owned.add(me)
        snapshots[me] = fresh
        return fresh
    }

    /** Record that we moved [delta] of [key] in or out of [me] this tick. AE2's shared
     *  cache is never written, it is copied on our first change. A key not already in
     *  the copy gets a fresh copy rather than an insert, so a loop iterating the old
     *  counter is never disturbed by a rehash. */
    private fun noteChange(me: MEStorage, key: AEKey, delta: Long) {
        if (delta == 0L) return
        rollTick()
        mutated.add(me)
        val snap = snapshots[me] ?: return
        if (me in owned && snap.get(key) != 0L) {
            snap.add(key, delta)
            return
        }
        val copy = KeyCounter()
        copy.addAll(snap)
        copy.add(key, delta)
        snapshots[me] = copy
        owned.add(me)
    }

    private fun idOf(key: AEKey): String = key.id.toString()

    // ---- items ----

    /** AE2's cache is never written, and our deltas only change existing keys in place
     *  or swap in a new copy, so mutating while iterating is safe. */
    private inline fun forEachItem(h: Ae2ItemStorageHandle, filter: (String) -> Boolean, visit: (AEItemKey, Long) -> Unit) {
        for (entry in snapshot(h.me, h.grid)) {
            val key = entry.key as? AEItemKey ?: continue
            if (entry.longValue <= 0L) continue
            if (filter(idOf(key))) visit(key, entry.longValue)
        }
    }

    // Read-only stacks are AE2's own cached view, never handed out, only inspected.
    private fun info(key: AEItemKey, count: Long) = ItemInfo(
        itemId = idOf(key),
        name = key.displayName.string,
        count = count,
        maxStackSize = key.maxStackSize,
        hasData = key.hasComponents(),
        componentsPatch = key.readOnlyStack.componentsPatch,
    )

    fun countItems(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): Long {
        var total = 0L
        forEachItem(h, filter) { _, n -> total += n }
        return total
    }

    fun findFirstItem(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): String? {
        forEachItem(h, filter) { key, _ -> return idOf(key) }
        return null
    }

    fun findFirstItemInfo(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): ItemInfo? {
        forEachItem(h, filter) { key, n -> return info(key, n) }
        return null
    }

    fun findAllItemInfo(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> {
        val out = ArrayList<ItemInfo>()
        forEachItem(h, filter) { key, n -> out += info(key, n) }
        return out
    }

    // ---- ranged polling for the inventory cache ----
    // Building an ItemInfo (id string, display name, components hash) is the
    // expensive part of reading a network, not walking AE2's cache. Entries are
    // built once per type and reused while the type stays in the network, so a
    // steady network costs a map lookup and a count compare per type per poll.

    internal class PolledEntry(val key: BufferKey.Key, var info: ItemInfo)

    class Ae2EntrySnapshot internal constructor(
        internal val keys: Array<AEItemKey>,
        internal val counts: LongArray,
        internal val reuse: EntryReuse<AEItemKey, PolledEntry>,
        internal val pass: EntryReuse<AEItemKey, PolledEntry>.Pass,
    ) : EntrySnapshot {
        override val size: Int get() = keys.size
        override val memo: Any get() = reuse
    }

    /** Copy the network's item types and counts in one pass, frozen so the cache
     *  can read them in ranges over later ticks and get one consistent picture.
     *  Reads the grid's cache even right after our own extract, where [snapshot]
     *  would walk every cell instead: at most a tick stale, and the inventory
     *  cache's own insert/extract hooks already carry our changes. */
    fun snapshotEntries(h: Ae2ItemStorageHandle, memo: Any?): EntrySnapshot {
        val grid = h.grid
        val counter = if (grid != null && grid.inventory === h.me) grid.cachedInventory else snapshot(h.me, grid)
        val keys = ArrayList<AEItemKey>()
        val counts = it.unimi.dsi.fastutil.longs.LongArrayList()
        for (entry in counter) {
            val key = entry.key as? AEItemKey ?: continue
            val n = entry.longValue
            if (n <= 0L) continue
            keys.add(key)
            counts.add(n)
        }
        @Suppress("UNCHECKED_CAST")
        val reuse = memo as? EntryReuse<AEItemKey, PolledEntry> ?: EntryReuse()
        return Ae2EntrySnapshot(keys.toTypedArray(), counts.toLongArray(), reuse, reuse.startPass(keys.size))
    }

    fun readEntries(s: Ae2EntrySnapshot, from: Int, until: Int, visit: (BufferKey.Key, ItemInfo) -> Unit) {
        val end = minOf(until, s.keys.size)
        for (i in from.coerceAtLeast(0) until end) {
            val key = s.keys[i]
            val count = s.counts[i]
            val entry = s.pass.get(key) {
                PolledEntry(
                    BufferKey.Key(idOf(key), BufferKey.componentsHash(key.readOnlyStack.componentsPatch)),
                    info(key, count),
                )
            }
            if (entry.info.count != count) entry.info = entry.info.copy(count = count)
            visit(entry.key, entry.info)
        }
        if (end >= s.keys.size) s.pass.complete()
    }

    /** Count-only extraction, no stacks allocated, so any amount is safe. */
    fun extractItems(h: Ae2ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        var remaining = maxCount
        var total = 0L
        forEachItem(h, filter) { key, available ->
            if (remaining <= 0L) return total
            val got = h.me.extract(key, minOf(remaining, available), Actionable.MODULATE, source)
            noteChange(h.me, key, -got)
            total += got
            remaining -= got
        }
        return total
    }

    /** Extracts up to [maxCount] of [key] within [stackBudget] stacks and appends them
     *  to [out]. The cap is applied before extracting so nothing is pulled that
     *  cannot be returned. */
    private fun extractStacks(me: MEStorage, key: AEItemKey, maxCount: Long, stackBudget: Int, out: MutableList<ItemStack>): Long {
        if (stackBudget <= 0) return 0L
        val max = key.maxStackSize.coerceAtLeast(1).toLong()
        val cap = minOf(maxCount, Int.MAX_VALUE.toLong(), stackBudget * max)
        if (cap <= 0L) return 0L
        val got = me.extract(key, cap, Actionable.MODULATE, source)
        noteChange(me, key, -got)
        var left = got
        while (left > 0L) {
            val n = minOf(left, max).toInt()
            out += key.toStack(n)
            left -= n
        }
        return got
    }

    fun extractItemStacksMatching(h: Ae2ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        forEachItem(h, filter) { key, available ->
            if (remaining <= 0L || out.size >= MAX_STACKS_PER_EXTRACT) return out
            remaining -= extractStacks(h.me, key, minOf(remaining, available), MAX_STACKS_PER_EXTRACT - out.size, out)
        }
        return out
    }

    fun extractStacksByPredicate(
        h: Ae2ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        cursor: damien.nodeworks.platform.ScanCursor? = null,
    ): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        if (cursor == null) {
            forEachItem(h, { true }) { key, available ->
                if (remaining <= 0L || out.size >= MAX_STACKS_PER_EXTRACT) return out
                if (filter(key.readOnlyStack)) {
                    remaining -= extractStacks(h.me, key, minOf(remaining, available), MAX_STACKS_PER_EXTRACT - out.size, out)
                }
            }
            return out
        }
        // Resumes from the type the last call stopped on, as [CompositeStorageService]'s
        // ME source move does, so every type gets its turn across calls.
        val step: (ItemStack, Long) -> Boolean = step@{ template, available ->
            if (remaining <= 0L || out.size >= MAX_STACKS_PER_EXTRACT) return@step false
            val key = AEItemKey.of(template) ?: return@step true
            remaining -= extractStacks(h.me, key, minOf(remaining, available), MAX_STACKS_PER_EXTRACT - out.size, out)
            remaining > 0L && out.size < MAX_STACKS_PER_EXTRACT
        }
        cursor.key = forEachStackFrom(h, filter, cursor.key, cursor.keyDone, step)
        cursor.keyDone = remaining > 0L
        return out
    }

    fun countStacksByPredicate(h: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean): Long {
        var total = 0L
        forEachItem(h, { true }) { key, n -> if (filter(key.readOnlyStack)) total += n }
        return total
    }

    /** What an extract of [maxCount] matching items would return, one stack per key with
     *  the total as its count, at most [maxStacks] of them. Read from the snapshot without
     *  touching the network. The flag is true when every key was looked at. */
    fun previewStacks(h: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long, maxStacks: Int): Pair<List<ItemStack>, Boolean> {
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        forEachItem(h, { true }) { key, available ->
            if (remaining <= 0L) return out to false
            if (!filter(key.readOnlyStack)) return@forEachItem
            if (out.size >= maxStacks) return out to false
            val take = minOf(remaining, available, Int.MAX_VALUE.toLong())
            out += key.toStack(take.toInt())
            remaining -= take
        }
        return out to (remaining > 0L)
    }

    /** Visit matching item types as one-item templates with their counts, in snapshot
     *  order, until [visit] returns false. */
    fun forEachStack(h: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean, visit: (ItemStack, Long) -> Boolean) {
        forEachItem(h, { true }) { key, n ->
            if (filter(key.readOnlyStack) && !visit(key.toStack(1), n)) return
        }
    }

    /** Like [forEachStack], but resumes at type [from] and wraps, visiting each type at most
     *  once. Returns the type [visit] stopped on, or null when every type was visited.
     *
     *  Two passes over one snapshot, so nothing is collected up front and the walk ends the
     *  moment [visit] does: [from] to the end, then the start up to [from]. With [skipFrom],
     *  [from] goes last instead of first, so the other types get their turn before it but it
     *  still gets one. A null [from] walks from the start, and a [from] that is gone costs one
     *  walk of key comparisons to find out before also walking from the start. */
    /** The first type past the cursor that passes [filter], as a stack of up to one
     *  stack size, with the cursor left on it. See [damien.nodeworks.platform.StorageService.peekStack]. */
    fun peekStack(h: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean, cursor: damien.nodeworks.platform.ScanCursor?): ItemStack? {
        var found: ItemStack? = null
        val key = forEachStackFrom(h, filter, cursor?.key, cursor?.keyDone ?: false) { template, available ->
            found = template.copyWithCount(minOf(available, template.maxStackSize.toLong()).toInt().coerceAtLeast(1))
            false
        }
        if (found != null && cursor != null) {
            cursor.key = key
            cursor.keyDone = false
        }
        return found
    }

    fun forEachStackFrom(
        h: Ae2ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        from: Any?,
        skipFrom: Boolean,
        visit: (ItemStack, Long) -> Boolean,
    ): Any? {
        // Our deltas only change existing keys in place or swap in a new copy, so this one
        // snapshot stays safe to walk twice while visit moves items.
        val snap = snapshot(h.me, h.grid)
        if (from != null) {
            var started = false
            for (entry in snap) {
                val key = entry.key as? AEItemKey ?: continue
                if (!started) {
                    if (key != from) continue
                    started = true
                    if (skipFrom) continue
                }
                if (entry.longValue <= 0L || !filter(key.readOnlyStack)) continue
                if (!visit(key.toStack(1), entry.longValue)) return key
            }
        }
        for (entry in snap) {
            val key = entry.key as? AEItemKey ?: continue
            if (from != null && key == from) {
                // Skipped at the start, so it goes last rather than not at all.
                if (skipFrom && entry.longValue > 0L && filter(key.readOnlyStack) &&
                    !visit(key.toStack(1), entry.longValue)) return key
                break
            }
            if (entry.longValue <= 0L || !filter(key.readOnlyStack)) continue
            if (!visit(key.toStack(1), entry.longValue)) return key
        }
        return null
    }

    /** How many of [template]'s exact type the network holds. */
    fun countOf(h: Ae2ItemStorageHandle, template: ItemStack): Long {
        val key = AEItemKey.of(template) ?: return 0L
        return snapshot(h.me, h.grid).get(key)
    }

    /** Extract up to [maxCount] of [template]'s exact type, a direct key lookup. */
    fun extractOf(h: Ae2ItemStorageHandle, template: ItemStack, maxCount: Long): List<ItemStack> {
        val key = AEItemKey.of(template) ?: return emptyList()
        val out = ArrayList<ItemStack>()
        extractStacks(h.me, key, maxCount, MAX_STACKS_PER_EXTRACT, out)
        return out
    }

    /** How many of [stacks] the network would accept. Each key is simulated on its own,
     *  so tight byte space shared between keys can read slightly high. */
    fun simulateInsertStacks(h: Ae2ItemStorageHandle, stacks: List<ItemStack>): Long {
        var total = 0L
        for (stack in stacks) {
            val key = AEItemKey.of(stack) ?: continue
            total += h.me.insert(key, stack.count.toLong(), Actionable.SIMULATE, source)
        }
        return total
    }

    fun insertItemStack(h: Ae2ItemStorageHandle, stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        val key = AEItemKey.of(stack) ?: return 0
        val put = h.me.insert(key, stack.count.toLong(), Actionable.MODULATE, source)
        noteChange(h.me, key, put)
        if (put > 0L) damien.nodeworks.platform.MoveDeadline.progressed()
        return put.toInt()
    }

    fun simulateInsertItem(h: Ae2ItemStorageHandle, item: Item, maxCount: Long): Long =
        if (maxCount <= 0L) 0L else h.me.insert(AEItemKey.of(item), maxCount, Actionable.SIMULATE, source)

    fun tryInsertAll(h: Ae2ItemStorageHandle, item: Item, count: Long): Boolean {
        if (count <= 0L) return true
        val key = AEItemKey.of(item)
        if (h.me.insert(key, count, Actionable.SIMULATE, source) < count) return false
        val inserted = h.me.insert(key, count, Actionable.MODULATE, source)
        noteChange(h.me, key, inserted)
        if (inserted < count) {
            // Network changed between the two calls, keep the all-or-nothing contract.
            noteChange(h.me, key, -h.me.extract(key, inserted, Actionable.MODULATE, source))
            return false
        }
        return true
    }

    /** ME to ME move at the key level: no stacks exist at any point, so moving a
     *  billion items costs O(distinct types). Whatever dest rejects goes back. */
    fun moveKeys(
        src: Ae2ItemStorageHandle,
        dst: Ae2ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        onMoved: ((ItemStack, Long) -> Unit)? = null,
    ): Long {
        var remaining = maxCount
        var moved = 0L
        forEachItem(src, { true }) { key, available ->
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) return moved
            if (!filter(key.readOnlyStack)) return@forEachItem
            val got = src.me.extract(key, minOf(remaining, available), Actionable.MODULATE, source)
            if (got <= 0L) return@forEachItem
            noteChange(src.me, key, -got)
            val put = try {
                dst.me.insert(key, got, Actionable.MODULATE, source)
            } catch (e: Throwable) {
                giveBack(src, key, got)
                throw e
            }
            noteChange(dst.me, key, put)
            if (put > 0L) damien.nodeworks.platform.MoveDeadline.progressed()
            if (put < got) giveBack(src, key, got - put)
            if (put > 0L) onMoved?.invoke(key.toStack(1), put)
            moved += put
            remaining -= put
        }
        return moved
    }

    /** Return [amount] of [key] taken from [src]. Whatever it refuses is spilled as
     *  stacks, so a key move can never delete items. */
    private fun giveBack(src: Ae2ItemStorageHandle, key: AEItemKey, amount: Long) {
        val back = try { src.me.insert(key, amount, Actionable.MODULATE, source) } catch (e: Exception) { 0L }
        noteChange(src.me, key, back)
        var left = amount - back
        val max = key.maxStackSize.coerceAtLeast(1).toLong()
        while (left > 0L) {
            val n = minOf(left, max).toInt()
            damien.nodeworks.platform.MoveSpill.spill(key.toStack(n), "ME move rollback: the source refused the items")
            left -= n
        }
    }

    // ---- fluids ----

    // Nodeworks measures fluids in mB. AE2 uses AMOUNT_BUCKET per bucket, 1000 on
    // NeoForge, so these are the identity today but stay right if that ever changes.
    private fun toAe(mb: Long): Long = if (AEFluidKey.AMOUNT_BUCKET == 1000) mb else mb * AEFluidKey.AMOUNT_BUCKET / 1000L
    private fun toMb(ae: Long): Long = if (AEFluidKey.AMOUNT_BUCKET == 1000) ae else ae * 1000L / AEFluidKey.AMOUNT_BUCKET

    private inline fun forEachFluid(h: Ae2FluidStorageHandle, filter: (String) -> Boolean, visit: (AEFluidKey, Long) -> Unit) {
        for (entry in snapshot(h.me, h.grid)) {
            val key = entry.key as? AEFluidKey ?: continue
            if (entry.longValue <= 0L) continue
            if (filter(idOf(key))) visit(key, toMb(entry.longValue))
        }
    }

    private fun fluidKey(fluidId: String): AEFluidKey? {
        val id = Identifier.tryParse(fluidId) ?: return null
        val fluid = BuiltInRegistries.FLUID.getValue(id)
        return if (fluid == Fluids.EMPTY) null else AEFluidKey.of(fluid)
    }

    fun countFluid(h: Ae2FluidStorageHandle, filter: (String) -> Boolean): Long {
        var total = 0L
        forEachFluid(h, filter) { _, mb -> total += mb }
        return total
    }

    fun findFirstFluidInfo(h: Ae2FluidStorageHandle, filter: (String) -> Boolean): FluidInfo? {
        forEachFluid(h, filter) { key, mb -> return FluidInfo(idOf(key), key.displayName.string, mb) }
        return null
    }

    fun findAllFluidInfo(h: Ae2FluidStorageHandle, filter: (String) -> Boolean): List<FluidInfo> {
        val out = ArrayList<FluidInfo>()
        forEachFluid(h, filter) { key, mb -> out += FluidInfo(idOf(key), key.displayName.string, mb) }
        return out
    }

    fun insertFluid(h: Ae2FluidStorageHandle, fluidId: String, amountMb: Long): Long {
        if (amountMb <= 0L) return 0L
        val key = fluidKey(fluidId) ?: return 0L
        val put = h.me.insert(key, toAe(amountMb), Actionable.MODULATE, source)
        noteChange(h.me, key, put)
        return toMb(put)
    }

    fun simulateInsertFluid(h: Ae2FluidStorageHandle, fluidId: String, maxMb: Long): Long {
        if (maxMb <= 0L) return 0L
        val key = fluidKey(fluidId) ?: return 0L
        return toMb(h.me.insert(key, toAe(maxMb), Actionable.SIMULATE, source))
    }

    fun tryInsertAllFluid(h: Ae2FluidStorageHandle, fluidId: String, amountMb: Long): Boolean {
        if (amountMb <= 0L) return true
        val key = fluidKey(fluidId) ?: return false
        val want = toAe(amountMb)
        if (h.me.insert(key, want, Actionable.SIMULATE, source) < want) return false
        val inserted = h.me.insert(key, want, Actionable.MODULATE, source)
        noteChange(h.me, key, inserted)
        if (inserted < want) {
            noteChange(h.me, key, -h.me.extract(key, inserted, Actionable.MODULATE, source))
            return false
        }
        return true
    }

    fun extractFluid(h: Ae2FluidStorageHandle, filter: (String) -> Boolean, maxMb: Long): Long {
        var remaining = maxMb
        var total = 0L
        forEachFluid(h, filter) { key, availableMb ->
            if (remaining <= 0L) return total
            val gotAe = h.me.extract(key, toAe(minOf(remaining, availableMb)), Actionable.MODULATE, source)
            noteChange(h.me, key, -gotAe)
            val got = toMb(gotAe)
            total += got
            remaining -= got
        }
        return total
    }
}
