package damien.nodeworks.script

import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Lua-side handle representing a reference to items in a specific storage.
 * Created by CardHandle:find(filter). Contains the item ID, available count,
 * and a reference back to the source storage for extraction during insert().
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
