package damien.nodeworks.script

import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
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
 * Always represents one item type — use findEach() for multiple.
 */
/**
 * Opaque source for items held in a CPU buffer rather than real block storage.
 * Used by processing handlers — items are extracted from here and inserted into destination.
 */
class BufferSource(
    private val cpu: damien.nodeworks.block.entity.CraftingCoreBlockEntity,
    val itemId: String,
    private var remaining: Long
) {
    /** Extract up to [maxCount] items from the buffer. Returns actual count extracted. */
    fun extract(maxCount: Long): Long {
        val toExtract = minOf(maxCount, remaining)
        val removed = cpu.removeFromBuffer(itemId, toExtract)
        remaining -= removed
        return removed
    }

    /** Put previously-extracted items back into the buffer. Used by `card:insert`'s
     *  atomic rollback when the destination refused a partial amount. */
    fun returnUnused(count: Long) {
        if (count <= 0L) return
        if (cpu.addToBuffer(itemId, count)) {
            remaining += count
        }
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
    val bufferSource: BufferSource? = null
) {
    val stackable: Boolean get() = maxStackSize > 1

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

            // :hasTag(tag) → boolean
            table.set("hasTag", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, tagArg: LuaValue): LuaValue {
                    val tag = tagArg.checkjstring()
                    val tagId = if (tag.startsWith("#")) tag.substring(1) else tag
                    val identifier = Identifier.tryParse(tagId) ?: return LuaValue.FALSE
                    val tagKey = TagKey.create(Registries.ITEM, identifier)
                    val itemIdentifier = Identifier.tryParse(handle.itemId) ?: return LuaValue.FALSE
                    val item = BuiltInRegistries.ITEM.getValue(itemIdentifier) ?: return LuaValue.FALSE
                    return LuaValue.valueOf(item.builtInRegistryHolder().`is`(tagKey))
                }
            })

            // :matches(filter) → boolean
            table.set("matches", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                    val matchFilter = filterArg.checkjstring()
                    return LuaValue.valueOf(CardHandle.matchesFilter(handle.itemId, matchFilter))
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
