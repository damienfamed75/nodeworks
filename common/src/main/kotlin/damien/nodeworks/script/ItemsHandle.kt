package damien.nodeworks.script

import damien.nodeworks.platform.ItemStorageHandle
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Lua-side handle representing a reference to items in a specific storage.
 * Created by CardHandle:find(filter), network:find(), network:craft(), network:shapeless().
 * Contains the item ID, available count, and a reference back to the source storage.
 */
class ItemsHandle(
    val itemId: String,
    val itemName: String,
    val count: Int,
    val filter: String,
    val sourceStorage: () -> ItemStorageHandle?,
    val level: ServerLevel
) {
    companion object {
        fun toLuaTable(handle: ItemsHandle): LuaTable {
            val table = LuaTable()

            table.set("id", LuaValue.valueOf(handle.itemId))
            table.set("name", LuaValue.valueOf(handle.itemName))
            table.set("count", LuaValue.valueOf(handle.count))

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
