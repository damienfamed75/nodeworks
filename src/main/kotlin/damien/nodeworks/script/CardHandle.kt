package damien.nodeworks.script

import damien.nodeworks.card.InventorySideCapability
import damien.nodeworks.network.CardSnapshot
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Lua-side handle for a card on the network. Exposes :move(), :count(), :face() methods.
 * Lua's `:` method call passes `self` as the first arg, so we use VarArgFunction where needed.
 */
class CardHandle private constructor(
    private val card: CardSnapshot,
    private val level: ServerLevel,
    private val accessFace: Direction?
) {
    companion object {
        private val logger = LoggerFactory.getLogger("nodeworks-cardhandle")

        fun create(card: CardSnapshot, level: ServerLevel): LuaTable {
            return CardHandle(card, level, null).toLuaTable()
        }

        private fun faceName(name: String): Direction? = when (name.lowercase()) {
            "top", "up" -> Direction.UP
            "bottom", "down" -> Direction.DOWN
            "north" -> Direction.NORTH
            "south" -> Direction.SOUTH
            "east" -> Direction.EAST
            "west" -> Direction.WEST
            "side" -> Direction.NORTH
            else -> null
        }
    }

    private fun getItemStorage(): Storage<ItemVariant>? {
        val cap = card.capability
        val targetPos = cap.adjacentPos
        val face = accessFace ?: (cap as? InventorySideCapability)?.defaultFace ?: Direction.UP
        return ItemStorage.SIDED.find(level, targetPos, face)
    }

    fun toLuaTable(): LuaTable {
        val table = LuaTable()
        val self = this

        // :face(name) -> new CardHandle with specific access face
        // Lua `:` call: self is arg1, name is arg2
        table.set("face", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val dir = faceName(name) ?: throw LuaError("Unknown face: $name")
                return CardHandle(card, level, dir).toLuaTable()
            }
        })

        // :move(dest, itemFilter, count) -> number moved
        // Lua `:` call: self=arg1, dest=arg2, filter=arg3, count=arg4
        table.set("move", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val destTable = args.checktable(2)
                val filter = args.checkjstring(3)
                val maxCount = args.checklong(4)

                val sourceStorage = self.getItemStorage()
                if (sourceStorage == null) {
                    logger.warn("move: source storage is null for {} (pos={}, face={})",
                        card.alias, card.capability.adjacentPos, accessFace ?: (card.capability as? InventorySideCapability)?.defaultFace)
                    return LuaValue.valueOf(0)
                }

                val destGetStorage = destTable.get("_getStorage")
                if (destGetStorage.isnil() || destGetStorage !is StorageGetter) {
                    throw LuaError("Invalid destination — expected a card handle")
                }
                val destStorage = destGetStorage.getStorage()
                if (destStorage == null) {
                    logger.warn("move: dest storage is null")
                    return LuaValue.valueOf(0)
                }

                val moved = moveItems(sourceStorage, destStorage, filter, maxCount)
                if (moved == 0L) {
                    logger.debug("move: 0 items moved from {} to dest (filter={}, max={})", card.alias, filter, maxCount)
                }
                return LuaValue.valueOf(moved.toInt())
            }
        })

        // :count(itemFilter) -> number
        // Lua `:` call: self=arg1, filter=arg2
        table.set("count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val storage = self.getItemStorage() ?: return LuaValue.valueOf(0)
                var total = 0L
                for (view in storage) {
                    if (!view.isResourceBlank && matchesFilter(view.resource, filter)) {
                        total += view.amount
                    }
                }
                return LuaValue.valueOf(total.toInt())
            }
        })

        // Internal: allows dest CardHandle to expose its storage
        table.set("_getStorage", StorageGetter { self.getItemStorage() })

        return table
    }

    private fun moveItems(
        source: Storage<ItemVariant>,
        dest: Storage<ItemVariant>,
        filter: String,
        maxCount: Long
    ): Long {
        return try {
            StorageUtil.move(source, dest, { matchesFilter(it, filter) }, maxCount, null)
        } catch (_: Exception) {
            0L
        }
    }

    private fun matchesFilter(variant: ItemVariant, filter: String): Boolean {
        if (filter == "*") return true
        val itemId = BuiltInRegistries.ITEM.getKey(variant.item)?.toString() ?: return false
        return itemId == filter
    }

    /** Internal LuaValue subclass to pass storage getter between CardHandles. */
    class StorageGetter(private val getter: () -> Storage<ItemVariant>?) : LuaValue() {
        fun getStorage(): Storage<ItemVariant>? = getter()
        override fun type(): Int = TUSERDATA
        override fun typename(): String = "StorageGetter"
    }
}
