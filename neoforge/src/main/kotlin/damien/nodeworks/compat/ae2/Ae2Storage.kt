package damien.nodeworks.compat.ae2

import appeng.api.AECapabilities
import appeng.api.config.Actionable
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.AEItemKey
import appeng.api.stacks.AEKey
import appeng.api.storage.MEStorage
import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.FluidStorageHandle
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.material.Fluids

/** Item view of an ME network reached through [AECapabilities.ME_STORAGE], which an
 *  unconfigured ME Interface exposes. AE2 keeps counts aggregated per key, so every
 *  operation here is O(distinct types) with no slot iteration at all. */
class Ae2ItemStorageHandle(val me: MEStorage) : ItemStorageHandle

/** Fluid view of the same network. One [MEStorage] holds both, keys are filtered by type. */
class Ae2FluidStorageHandle(val me: MEStorage) : FluidStorageHandle

object Ae2Storage {
    private val source: IActionSource = IActionSource.empty()

    /** Upper bound on ItemStacks one extraction call materializes. An ME network is
     *  not slot-bounded, so a huge request could otherwise allocate millions of
     *  stacks in one tick. Bulk moves loop in rounds instead, see
     *  [damien.nodeworks.platform.CompositeStorageService]. */
    private const val MAX_STACKS_PER_EXTRACT = 16_384

    fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle? =
        level.getCapability(AECapabilities.ME_STORAGE, pos, face)?.let { Ae2ItemStorageHandle(it) }

    fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? =
        level.getCapability(AECapabilities.ME_STORAGE, pos, face)?.let { Ae2FluidStorageHandle(it) }

    private fun idOf(key: AEKey): String = key.id.toString()

    // ---- items ----

    /** getAvailableStacks returns a fresh snapshot, so extracting while iterating is safe. */
    private inline fun forEachItem(me: MEStorage, filter: (String) -> Boolean, visit: (AEItemKey, Long) -> Unit) {
        for (entry in me.availableStacks) {
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
        forEachItem(h.me, filter) { _, n -> total += n }
        return total
    }

    fun findFirstItem(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): String? {
        forEachItem(h.me, filter) { key, _ -> return idOf(key) }
        return null
    }

    fun findFirstItemInfo(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): ItemInfo? {
        forEachItem(h.me, filter) { key, n -> return info(key, n) }
        return null
    }

    fun findAllItemInfo(h: Ae2ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo> {
        val out = ArrayList<ItemInfo>()
        forEachItem(h.me, filter) { key, n -> out += info(key, n) }
        return out
    }

    /** Count-only extraction, no stacks allocated, so any amount is safe. */
    fun extractItems(h: Ae2ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long {
        var remaining = maxCount
        var total = 0L
        forEachItem(h.me, filter) { key, available ->
            if (remaining <= 0L) return total
            val got = h.me.extract(key, minOf(remaining, available), Actionable.MODULATE, source)
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
        forEachItem(h.me, filter) { key, available ->
            if (remaining <= 0L || out.size >= MAX_STACKS_PER_EXTRACT) return out
            remaining -= extractStacks(h.me, key, minOf(remaining, available), MAX_STACKS_PER_EXTRACT - out.size, out)
        }
        return out
    }

    fun extractStacksByPredicate(h: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        forEachItem(h.me, { true }) { key, available ->
            if (remaining <= 0L || out.size >= MAX_STACKS_PER_EXTRACT) return out
            if (filter(key.readOnlyStack)) {
                remaining -= extractStacks(h.me, key, minOf(remaining, available), MAX_STACKS_PER_EXTRACT - out.size, out)
            }
        }
        return out
    }

    fun countStacksByPredicate(h: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean): Long {
        var total = 0L
        forEachItem(h.me, { true }) { key, n -> if (filter(key.readOnlyStack)) total += n }
        return total
    }

    fun insertItemStack(h: Ae2ItemStorageHandle, stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        val key = AEItemKey.of(stack) ?: return 0
        return h.me.insert(key, stack.count.toLong(), Actionable.MODULATE, source).toInt()
    }

    fun simulateInsertItem(h: Ae2ItemStorageHandle, item: Item, maxCount: Long): Long =
        if (maxCount <= 0L) 0L else h.me.insert(AEItemKey.of(item), maxCount, Actionable.SIMULATE, source)

    fun tryInsertAll(h: Ae2ItemStorageHandle, item: Item, count: Long): Boolean {
        if (count <= 0L) return true
        val key = AEItemKey.of(item)
        if (h.me.insert(key, count, Actionable.SIMULATE, source) < count) return false
        val inserted = h.me.insert(key, count, Actionable.MODULATE, source)
        if (inserted < count) {
            // Network changed between the two calls, keep the all-or-nothing contract.
            h.me.extract(key, inserted, Actionable.MODULATE, source)
            return false
        }
        return true
    }

    /** ME to ME move at the key level: no stacks exist at any point, so moving a
     *  billion items costs O(distinct types). Whatever dest rejects goes back. */
    fun moveKeys(src: Ae2ItemStorageHandle, dst: Ae2ItemStorageHandle, filter: (ItemStack) -> Boolean, maxCount: Long): Long {
        var remaining = maxCount
        var moved = 0L
        forEachItem(src.me, { true }) { key, available ->
            if (remaining <= 0L) return moved
            if (!filter(key.readOnlyStack)) return@forEachItem
            val got = src.me.extract(key, minOf(remaining, available), Actionable.MODULATE, source)
            if (got <= 0L) return@forEachItem
            val put = dst.me.insert(key, got, Actionable.MODULATE, source)
            if (put < got) src.me.insert(key, got - put, Actionable.MODULATE, source)
            moved += put
            remaining -= put
        }
        return moved
    }

    // ---- fluids ----

    // Nodeworks measures fluids in mB. AE2 uses AMOUNT_BUCKET per bucket, 1000 on
    // NeoForge, so these are the identity today but stay right if that ever changes.
    private fun toAe(mb: Long): Long = if (AEFluidKey.AMOUNT_BUCKET == 1000) mb else mb * AEFluidKey.AMOUNT_BUCKET / 1000L
    private fun toMb(ae: Long): Long = if (AEFluidKey.AMOUNT_BUCKET == 1000) ae else ae * 1000L / AEFluidKey.AMOUNT_BUCKET

    private inline fun forEachFluid(me: MEStorage, filter: (String) -> Boolean, visit: (AEFluidKey, Long) -> Unit) {
        for (entry in me.availableStacks) {
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
        forEachFluid(h.me, filter) { _, mb -> total += mb }
        return total
    }

    fun findFirstFluidInfo(h: Ae2FluidStorageHandle, filter: (String) -> Boolean): FluidInfo? {
        forEachFluid(h.me, filter) { key, mb -> return FluidInfo(idOf(key), key.displayName.string, mb) }
        return null
    }

    fun findAllFluidInfo(h: Ae2FluidStorageHandle, filter: (String) -> Boolean): List<FluidInfo> {
        val out = ArrayList<FluidInfo>()
        forEachFluid(h.me, filter) { key, mb -> out += FluidInfo(idOf(key), key.displayName.string, mb) }
        return out
    }

    fun insertFluid(h: Ae2FluidStorageHandle, fluidId: String, amountMb: Long): Long {
        if (amountMb <= 0L) return 0L
        val key = fluidKey(fluidId) ?: return 0L
        return toMb(h.me.insert(key, toAe(amountMb), Actionable.MODULATE, source))
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
        if (inserted < want) {
            h.me.extract(key, inserted, Actionable.MODULATE, source)
            return false
        }
        return true
    }

    fun extractFluid(h: Ae2FluidStorageHandle, filter: (String) -> Boolean, maxMb: Long): Long {
        var remaining = maxMb
        var total = 0L
        forEachFluid(h.me, filter) { key, availableMb ->
            if (remaining <= 0L) return total
            val got = toMb(h.me.extract(key, toAe(minOf(remaining, availableMb)), Actionable.MODULATE, source))
            total += got
            remaining -= got
        }
        return total
    }
}
