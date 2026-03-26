package damien.nodeworks.script

import damien.nodeworks.card.InventorySideCapability
import damien.nodeworks.network.CardSnapshot
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext
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
    private val accessFace: Direction?,
    private val slotFilter: Set<Int>? // null = all slots, otherwise 0-indexed slot numbers
) {
    companion object {
        private val logger = LoggerFactory.getLogger("nodeworks-cardhandle")

        fun create(card: CardSnapshot, level: ServerLevel): LuaTable {
            return CardHandle(card, level, null, null).toLuaTable()
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
        val storage = ItemStorage.SIDED.find(level, targetPos, face) ?: return null

        // If slot filter is set, wrap to only expose those slots
        if (slotFilter != null && storage is SlottedStorage<*>) {
            @Suppress("UNCHECKED_CAST")
            val slotted = storage as SlottedStorage<ItemVariant>
            return SlotFilteredStorage(slotted, slotFilter)
        }
        return storage
    }

    /**
     * Wraps a SlottedStorage to only expose specific slots.
     * Iteration and StorageUtil.move only see the filtered slots.
     */
    private class SlotFilteredStorage(
        private val backing: SlottedStorage<ItemVariant>,
        private val allowedSlots: Set<Int>
    ) : Storage<ItemVariant> {
        override fun insert(resource: ItemVariant, maxAmount: Long, transaction: TransactionContext): Long {
            // Insert into first allowed slot that accepts
            for (idx in allowedSlots) {
                if (idx < 0 || idx >= backing.slotCount) continue
                val inserted = backing.getSlot(idx).insert(resource, maxAmount, transaction)
                if (inserted > 0) return inserted
            }
            return 0
        }

        override fun extract(resource: ItemVariant, maxAmount: Long, transaction: TransactionContext): Long {
            var total = 0L
            var remaining = maxAmount
            for (idx in allowedSlots) {
                if (remaining <= 0) break
                if (idx < 0 || idx >= backing.slotCount) continue
                val extracted = backing.getSlot(idx).extract(resource, remaining, transaction)
                total += extracted
                remaining -= extracted
            }
            return total
        }

        override fun iterator(): MutableIterator<net.fabricmc.fabric.api.transfer.v1.storage.StorageView<ItemVariant>> {
            return allowedSlots
                .filter { it >= 0 && it < backing.slotCount }
                .map { backing.getSlot(it) as net.fabricmc.fabric.api.transfer.v1.storage.StorageView<ItemVariant> }
                .toMutableList()
                .iterator()
        }
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
                return CardHandle(card, level, dir, slotFilter).toLuaTable()
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

        // :slots(...) -> new CardHandle filtered to specific slots
        // Lua `:` call: self=arg1, slot numbers as remaining args (1-indexed)
        table.set("slots", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val slots = mutableSetOf<Int>()
                for (i in 2..args.narg()) {
                    slots.add(args.checkint(i) - 1) // Lua 1-indexed → 0-indexed
                }
                return CardHandle(card, level, accessFace, slots).toLuaTable()
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
