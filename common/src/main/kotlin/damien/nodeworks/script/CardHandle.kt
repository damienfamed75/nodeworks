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

        /** Max characters allowed in a regex item filter pattern. */
        const val MAX_REGEX_LENGTH = 200

        /** Max cached compiled regex patterns (LRU eviction). */
        const val MAX_REGEX_CACHE_SIZE = 64

        /** Compiled regex cache — bounded LRU to prevent unbounded memory growth. */
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
                        card.alias, card.capability.adjacentPos, accessFace ?: (card.capability as? IOSideCapability)?.defaultFace)
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
                val total = PlatformServices.storage.countItems(storage) { matchesFilter(it, filter) }
                return LuaValue.valueOf(total.toInt())
            }
        })

        // Internal: allows dest CardHandle to expose its storage
        table.set("_getStorage", StorageGetter { self.getItemStorage() })

        return table
    }

    private fun moveItems(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: String,
        maxCount: Long
    ): Long {
        return try {
            PlatformServices.storage.moveItems(source, dest, { matchesFilter(it, filter) }, maxCount)
        } catch (_: Exception) {
            0L
        }
    }

    /** Internal LuaValue subclass to pass storage getter between CardHandles. */
    class StorageGetter(private val getter: () -> ItemStorageHandle?) : LuaValue() {
        fun getStorage(): ItemStorageHandle? = getter()
        override fun type(): Int = TUSERDATA
        override fun typename(): String = "StorageGetter"
    }
}
