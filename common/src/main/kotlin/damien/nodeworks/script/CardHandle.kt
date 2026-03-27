package damien.nodeworks.script

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.platform.SlottedItemStorageHandle
import org.slf4j.LoggerFactory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Lua-side handle for a card on the network. Exposes :find(), :insert(), :count(), :face(), :slots().
 * Lua's `:` method call passes `self` as the first arg.
 */
class CardHandle private constructor(
    private val card: CardSnapshot,
    private val level: ServerLevel,
    private val accessFace: Direction?,
    private val slotFilter: Set<Int>?
) {
    companion object {
        private val logger = LoggerFactory.getLogger("nodeworks-cardhandle")

        const val MAX_REGEX_LENGTH = 200
        const val MAX_REGEX_CACHE_SIZE = 64

        private val regexCache = object : LinkedHashMap<String, java.util.regex.Pattern>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.util.regex.Pattern>?): Boolean {
                return size > MAX_REGEX_CACHE_SIZE
            }
        }

        fun matchesFilter(itemId: String, filter: String): Boolean {
            if (filter == "*") return true

            if (filter.startsWith("#")) {
                val tagId = filter.substring(1)
                val identifier = Identifier.tryParse(tagId) ?: return false
                val tagKey = TagKey.create(Registries.ITEM, identifier)
                val itemIdentifier = Identifier.tryParse(itemId) ?: return false
                val item = BuiltInRegistries.ITEM.getValue(itemIdentifier) ?: return false
                return item.builtInRegistryHolder().`is`(tagKey)
            }

            if (filter.startsWith("/") && filter.endsWith("/") && filter.length > 2) {
                val patternStr = filter.substring(1, filter.length - 1)
                if (patternStr.length > MAX_REGEX_LENGTH) return false
                val pattern = regexCache.getOrPut(patternStr) {
                    try { java.util.regex.Pattern.compile(patternStr) } catch (_: Exception) { return false }
                }
                return pattern.matcher(itemId).matches()
            }

            if (filter.endsWith(":*")) {
                val namespace = filter.removeSuffix(":*")
                return itemId.startsWith("$namespace:")
            }

            return itemId == filter
        }

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

    private fun getItemStorage(): ItemStorageHandle? {
        val cap = card.capability
        val targetPos = cap.adjacentPos
        val face = accessFace ?: (cap as? IOSideCapability)?.defaultFace ?: Direction.UP

        if (slotFilter != null) {
            val slotted = PlatformServices.storage.getSlottedStorage(level, targetPos, face) ?: return null
            return slotted.filteredBySlots(slotFilter)
        }
        return PlatformServices.storage.getItemStorage(level, targetPos, face)
    }

    fun toLuaTable(): LuaTable {
        val table = LuaTable()
        val self = this

        // :face(name) -> new CardHandle with specific access face
        table.set("face", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val dir = faceName(name) ?: throw LuaError("Unknown face: $name")
                return CardHandle(card, level, dir, slotFilter).toLuaTable()
            }
        })

        // :slots(...) -> new CardHandle filtered to specific slots
        table.set("slots", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val slots = mutableSetOf<Int>()
                for (i in 2..args.narg()) {
                    slots.add(args.checkint(i) - 1) // Lua 1-indexed → 0-indexed
                }
                return CardHandle(card, level, accessFace, slots).toLuaTable()
            }
        })

        // :find(filter) -> ItemsHandle or nil
        // Queries items in this card's storage matching the filter
        table.set("find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val storage = self.getItemStorage() ?: return LuaValue.NIL

                val info = PlatformServices.storage.findFirstItemInfo(storage) { matchesFilter(it, filter) }
                    ?: return LuaValue.NIL

                val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = { self.getItemStorage() }
                return ItemsHandle.toLuaTable(ItemsHandle.fromItemInfo(info, filter, sourceStorage, level))
            }
        })

        // :findAll(filter) -> table of ItemsHandles
        // Returns all unique item types matching the filter
        table.set("findAll", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val storage = self.getItemStorage() ?: return LuaTable()

                val items = PlatformServices.storage.findAllItemInfo(storage) { matchesFilter(it, filter) }
                val result = LuaTable()
                val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = { self.getItemStorage() }
                for ((i, info) in items.withIndex()) {
                    val handle = ItemsHandle.fromItemInfo(info, info.itemId, sourceStorage, level)
                    result.set(i + 1, ItemsHandle.toLuaTable(handle))
                }
                return result
            }
        })

        // :insert(itemsHandle, count?) -> number moved
        // Moves items from the ItemsHandle's source into this card's storage
        table.set("insert", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val itemsTable = args.checktable(2)
                val maxCount = if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    args.checklong(3)
                } else {
                    Long.MAX_VALUE
                }

                val ref = itemsTable.get("_itemsHandle")
                if (ref.isnil() || ref !is ItemsHandle.ItemsHandleRef) {
                    throw LuaError("Expected an ItemsHandle from :find() or network:craft()")
                }
                val itemsHandle = ref.handle

                val sourceStorage = itemsHandle.sourceStorage() ?: return LuaValue.valueOf(0)
                val destStorage = self.getItemStorage() ?: return LuaValue.valueOf(0)

                val moved = try {
                    PlatformServices.storage.moveItems(
                        sourceStorage, destStorage,
                        { matchesFilter(it, itemsHandle.filter) },
                        minOf(maxCount, itemsHandle.count.toLong())
                    )
                } catch (_: Exception) {
                    0L
                }
                return LuaValue.valueOf(moved.toInt())
            }
        })

        // :count(filter) -> number
        table.set("count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val storage = self.getItemStorage() ?: return LuaValue.valueOf(0)
                val total = PlatformServices.storage.countItems(storage) { matchesFilter(it, filter) }
                return LuaValue.valueOf(total.toInt())
            }
        })

        // Internal: allows insert() to access this handle's storage as a destination
        table.set("_getStorage", StorageGetter { self.getItemStorage() })

        return table
    }

    /** Internal LuaValue subclass to pass storage getter between CardHandles. */
    class StorageGetter(private val getter: () -> ItemStorageHandle?) : LuaValue() {
        fun getStorage(): ItemStorageHandle? = getter()
        override fun type(): Int = TUSERDATA
        override fun typename(): String = "StorageGetter"
    }
}
