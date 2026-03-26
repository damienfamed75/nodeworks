package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
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
        val targetPos = card.capability.adjacentPos
        val face = accessFace ?: Direction.UP
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
                    ?: throw LuaError("Source inventory not accessible")

                val destGetStorage = destTable.get("_getStorage")
                if (destGetStorage.isnil() || destGetStorage !is StorageGetter) {
                    throw LuaError("Invalid destination — expected a card handle")
                }
                val destStorage = destGetStorage.getStorage()
                    ?: throw LuaError("Destination inventory not accessible")

                val moved = moveItems(sourceStorage, destStorage, filter, maxCount)
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
        var totalMoved = 0L
        var remaining = maxCount

        Transaction.openOuter().use { transaction ->
            for (view in source) {
                if (remaining <= 0) break
                if (view.isResourceBlank) continue
                if (!matchesFilter(view.resource, filter)) continue

                val toExtract = minOf(view.amount, remaining)
                val extracted = view.extract(view.resource, toExtract, transaction)
                if (extracted > 0) {
                    val inserted = dest.insert(view.resource, extracted, transaction)
                    if (inserted < extracted) {
                        // Couldn't insert all — abort entire transaction and return what we've moved so far
                        transaction.abort()
                        return totalMoved
                    }
                    totalMoved += inserted
                    remaining -= inserted
                }
            }
            transaction.commit()
        }
        return totalMoved
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
