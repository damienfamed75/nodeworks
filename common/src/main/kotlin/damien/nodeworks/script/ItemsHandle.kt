package damien.nodeworks.script

import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.FluidStorageHandle
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.ResourceKind
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Lua-side handle representing a reference to a single item type in a specific storage.
 * Created by CardHandle:find(), network:find(), network:craft(), network:shapeless().
 * Always represents one item type, use findEach() for multiple.
 */
/**
 * Opaque source for items held in a CPU buffer rather than real block storage.
 * Used by processing handlers, items are extracted from here and inserted into
 * destination.
 *
 * Carries a [BufferKey.Key] so component-bearing variants (potions, dyed armor,
 * enchanted books) don't collide with plain copies of the same itemId. The
 * convenience [itemId] accessor mirrors the legacy field for downstream
 * consumers that still index by name only.
 */
class BufferSource(
    private val cpu: damien.nodeworks.block.entity.CraftingCoreBlockEntity,
    val key: BufferKey.Key,
    private var remaining: Long
) {
    /** Legacy itemId accessor for consumers that haven't migrated to [key]. */
    val itemId: String get() = key.itemId

    /** Template stack captured at construction time so [returnUnused] can
     *  restore the original variant even when [extract] has fully drained
     *  the bucket and [cpu.getBufferTemplate] no longer returns one.
     *  Falls back to [net.minecraft.world.item.ItemStack.EMPTY] when the
     *  bucket was already empty at construction (legacy callers using
     *  [ofItemId] who never touched the buffer first). */
    private val capturedTemplate: net.minecraft.world.item.ItemStack? = cpu.getBufferTemplate(key)

    /** Template the buffer is using for this variant. Carries the components
     *  of the first instance routed in (e.g. the exact potion variant pulled
     *  from storage). Falls back to the captured snapshot when the bucket
     *  has been drained. */
    val template: net.minecraft.world.item.ItemStack
        get() = cpu.getBufferTemplate(key)
            ?: capturedTemplate
            ?: net.minecraft.world.item.ItemStack.EMPTY

    /** Extract up to [maxCount] items from the buffer. Returns actual count extracted. */
    fun extract(maxCount: Long): Long {
        val toExtract = minOf(maxCount, remaining)
        val removed = cpu.removeFromBuffer(key, toExtract)
        remaining -= removed
        return removed
    }

    /** Put previously-extracted items back into the buffer. Uses the bucket's
     *  template stack so components survive the round trip. Used by
     *  `card:insert`'s atomic rollback when the destination refused a partial
     *  amount.
     *
     *  Falls back to the [capturedTemplate] from construction time when the
     *  live bucket has been drained empty by a prior [extract], so
     *  component-bearing variants (potions, dyed gear) get re-added with
     *  their identity intact instead of degrading to a plain-itemId entry. */
    fun returnUnused(count: Long) {
        if (count <= 0L) return
        val template = cpu.getBufferTemplate(key) ?: capturedTemplate
        val ok = if (template != null && !template.isEmpty) cpu.addToBuffer(template, count)
            else cpu.addToBuffer(key.itemId, count)
        if (ok) remaining += count
    }

    companion object {
        /** Legacy itemId-only constructor for sites that don't yet thread
         *  components through. Builds an empty-components [BufferKey.Key]. */
        fun ofItemId(
            cpu: damien.nodeworks.block.entity.CraftingCoreBlockEntity,
            itemId: String,
            remaining: Long,
        ): BufferSource = BufferSource(cpu, BufferKey.Key(itemId, ""), remaining)
    }
}

class ItemsHandle(
    val itemId: String,
    val itemName: String,
    val count: Int,
    val maxStackSize: Int,
    val hasData: Boolean,
    val filter: String,
    val sourceStorage: () -> ItemStorageHandle?,
    val level: ServerLevel,
    val bufferSource: BufferSource? = null,
    val kind: ResourceKind = ResourceKind.ITEM,
    val fluidSourceStorage: () -> FluidStorageHandle? = { null }
) {
    val stackable: Boolean get() = kind == ResourceKind.ITEM && maxStackSize > 1

    companion object {
        /** Create an ItemsHandle from an ItemInfo and source storage reference. */
        fun fromItemInfo(info: ItemInfo, filter: String, sourceStorage: () -> ItemStorageHandle?, level: ServerLevel): ItemsHandle {
            return ItemsHandle(
                itemId = info.itemId,
                itemName = info.name,
                count = info.count.toInt(),
                maxStackSize = info.maxStackSize,
                hasData = info.hasData,
                filter = filter,
                sourceStorage = sourceStorage,
                level = level
            )
        }

        /** Create a fluid-backed ItemsHandle from a FluidInfo. Counts are mB. */
        fun fromFluidInfo(info: FluidInfo, filter: String, fluidSourceStorage: () -> FluidStorageHandle?, level: ServerLevel): ItemsHandle {
            return ItemsHandle(
                itemId = info.fluidId,
                itemName = info.name,
                count = info.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                maxStackSize = 1,
                hasData = false,
                filter = filter,
                sourceStorage = { null },
                level = level,
                kind = ResourceKind.FLUID,
                fluidSourceStorage = fluidSourceStorage
            )
        }

        /** Create an ItemsHandle for crafting results (no stack in storage yet). */
        fun forCraftResult(itemId: String, itemName: String, count: Int, sourceStorage: () -> ItemStorageHandle?, level: ServerLevel): ItemsHandle {
            val identifier = Identifier.tryParse(itemId)
            val item = if (identifier != null) BuiltInRegistries.ITEM.getValue(identifier) else null
            return ItemsHandle(
                itemId = itemId,
                itemName = itemName,
                count = count,
                maxStackSize = item?.getDefaultMaxStackSize() ?: 64,
                hasData = false,
                filter = itemId,
                sourceStorage = sourceStorage,
                level = level
            )
        }

        fun toLuaTable(handle: ItemsHandle): LuaTable {
            val table = LuaTable()

            table.set("id", LuaValue.valueOf(handle.itemId))
            table.set("name", LuaValue.valueOf(handle.itemName))
            table.set("count", LuaValue.valueOf(handle.count))
            table.set("stackable", LuaValue.valueOf(handle.stackable))
            table.set("maxStackSize", LuaValue.valueOf(handle.maxStackSize))
            table.set("hasData", LuaValue.valueOf(handle.hasData))
            table.set("kind", LuaValue.valueOf(if (handle.kind == ResourceKind.FLUID) "fluid" else "item"))

            // :hasTag(tag) → boolean
            table.set("hasTag", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, tagArg: LuaValue): LuaValue {
                    val tag = tagArg.checkjstring()
                    val tagId = if (tag.startsWith("#")) tag.substring(1) else tag
                    val identifier = Identifier.tryParse(tagId) ?: return LuaValue.FALSE
                    val resIdent = Identifier.tryParse(handle.itemId) ?: return LuaValue.FALSE
                    return when (handle.kind) {
                        ResourceKind.ITEM -> {
                            val tagKey = TagKey.create(Registries.ITEM, identifier)
                            val item = BuiltInRegistries.ITEM.getValue(resIdent) ?: return LuaValue.FALSE
                            LuaValue.valueOf(item.builtInRegistryHolder().`is`(tagKey))
                        }
                        ResourceKind.FLUID -> {
                            val tagKey = TagKey.create(Registries.FLUID, identifier)
                            val fluid = BuiltInRegistries.FLUID.getValue(resIdent) ?: return LuaValue.FALSE
                            LuaValue.valueOf(fluid.builtInRegistryHolder().`is`(tagKey))
                        }
                    }
                }
            })

            // :matches(filter) → boolean
            table.set("matches", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                    val matchFilter = filterArg.checkjstring()
                    return LuaValue.valueOf(CardHandle.matchesFilter(handle.itemId, handle.kind, matchFilter))
                }
            })

            // Internal: used by insert() to extract from source
            table.set("_itemsHandle", ItemsHandleRef(handle))

            return table
        }
    }

    /** Internal LuaValue wrapper to pass ItemsHandle between Lua tables. */
    class ItemsHandleRef(val handle: ItemsHandle) : LuaValue() {
        override fun type(): Int = TUSERDATA
        override fun typename(): String = "ItemsHandle"
    }
}
