package damien.nodeworks.script

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.platform.FluidStorageHandle
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.platform.ResourceKind
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

        /**
         * Parse a user-facing filter string into an optional kind gate and the inner pattern.
         *
         * A leading `${'$'}item:` or `${'$'}fluid:` locks the match to that resource kind; the remainder
         * is the pattern applied to the resource id. `*`, `<mod>:*`, `#tag`, `/regex/`, and exact id
         * are supported as patterns (kind-agnostic otherwise). The `${'$'}` sigil keeps the kind
         * prefix orthogonal to mod namespaces — no risk of clashing with a mod named "item".
         */
        fun parseFilterKind(filter: String): Pair<ResourceKind?, String> = when {
            filter.startsWith("\$item:") -> Pair(ResourceKind.ITEM, filter.removePrefix("\$item:"))
            filter.startsWith("\$fluid:") -> Pair(ResourceKind.FLUID, filter.removePrefix("\$fluid:"))
            else -> Pair(null, filter)
        }

        /** Backward-compat overload — assumes the tested id is an item id. */
        fun matchesFilter(itemId: String, filter: String): Boolean =
            matchesFilter(itemId, ResourceKind.ITEM, filter)

        fun matchesFilter(resourceId: String, kind: ResourceKind, filter: String): Boolean {
            val (kindGate, inner) = parseFilterKind(filter)
            if (kindGate != null && kindGate != kind) return false
            return matchesIdPattern(resourceId, kind, inner)
        }

        private fun matchesIdPattern(resourceId: String, kind: ResourceKind, filter: String): Boolean {
            if (filter == "*") return true

            if (filter.startsWith("#")) {
                val tagId = filter.substring(1)
                val identifier = Identifier.tryParse(tagId) ?: return false
                val resIdent = Identifier.tryParse(resourceId) ?: return false
                return when (kind) {
                    ResourceKind.ITEM -> {
                        val tagKey = TagKey.create(Registries.ITEM, identifier)
                        val item = BuiltInRegistries.ITEM.getValue(resIdent) ?: return false
                        item.builtInRegistryHolder().`is`(tagKey)
                    }
                    ResourceKind.FLUID -> {
                        val tagKey = TagKey.create(Registries.FLUID, identifier)
                        val fluid = BuiltInRegistries.FLUID.getValue(resIdent) ?: return false
                        fluid.builtInRegistryHolder().`is`(tagKey)
                    }
                }
            }

            if (filter.startsWith("/") && filter.endsWith("/") && filter.length > 2) {
                val patternStr = filter.substring(1, filter.length - 1)
                if (patternStr.length > MAX_REGEX_LENGTH) return false
                val pattern = regexCache.getOrPut(patternStr) {
                    try { java.util.regex.Pattern.compile(patternStr) } catch (_: Exception) { return false }
                }
                return pattern.matcher(resourceId).matches()
            }

            if (filter.endsWith(":*")) {
                val namespace = filter.removeSuffix(":*")
                return resourceId.startsWith("$namespace:")
            }

            return resourceId == filter
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

    /**
     * Build the Lua function that backs both `:insert` (atomic=true) and `:tryInsert` (atomic=false).
     *
     * Atomic mode (insert): moves `handle.count` exactly, or 0. Returns boolean.
     *   - Buffer-backed source: extract requested → attempt insert → rollback any shortfall.
     *   - Storage-backed source: move up to requested and accept whatever landed; moveItems
     *     already extracts-then-inserts under the hood, so partial shortfall means the source
     *     still holds the leftover. We then extract-back-to-source any already-moved items
     *     to keep atomicity. (Today's move API doesn't expose simulate, so this is the cost.)
     *
     * Best-effort mode (tryInsert): returns actual count moved (int, 0..handle.count).
     *
     * Both modes: insert of an empty handle (count ≤ 0) → false / 0.
     */
    private fun buildInsertFn(self: CardHandle, atomic: Boolean): VarArgFunction {
        return object : VarArgFunction() {
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
                val requested = minOf(maxCount, itemsHandle.count.toLong())
                if (requested <= 0L) {
                    return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                }

                // Dispatch by the handle's kind — an item handle targets the card's item cap,
                // a fluid handle targets the card's fluid cap. A block with neither matching
                // cap simply fails the op (returns 0/false).
                val moved: Long = if (itemsHandle.kind == ResourceKind.FLUID) {
                    val destFluid = self.getFluidStorage()
                        ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                    moveFluidToDest(itemsHandle, destFluid, requested, atomic)
                } else {
                    val destStorage = self.getItemStorage()
                        ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                    val bufSrc = itemsHandle.bufferSource
                    if (bufSrc != null) moveFromBuffer(bufSrc, destStorage, requested, atomic)
                    else moveFromStorage(itemsHandle, destStorage, requested, atomic)
                }

                return if (atomic) {
                    LuaValue.valueOf(moved == requested)
                } else {
                    LuaValue.valueOf(moved.toInt())
                }
            }
        }
    }

    private fun moveFluidToDest(
        itemsHandle: ItemsHandle,
        destStorage: FluidStorageHandle,
        requested: Long,
        atomic: Boolean
    ): Long {
        val sourceStorage = itemsHandle.fluidSourceStorage() ?: return 0L
        val fluidId = itemsHandle.itemId
        return if (atomic) {
            val ok = try {
                PlatformServices.storage.tryMoveAllFluid(
                    sourceStorage, destStorage,
                    { it == fluidId },
                    requested
                )
            } catch (_: Exception) { false }
            if (ok) requested else 0L
        } else {
            try {
                PlatformServices.storage.moveFluid(
                    sourceStorage, destStorage,
                    { it == fluidId },
                    requested
                )
            } catch (_: Exception) { 0L }
        }
    }

    /**
     * Buffer → destination.
     *
     * Atomic path uses [PlatformServices.StorageService.tryInsertAll] — checks destination
     * capacity via the platform's native transaction/simulation, moves only if all fits.
     * On failure, nothing is extracted from the buffer — no rollback needed.
     *
     * Best-effort path extracts in stack-sized batches, committing each real insert and
     * returning any shortfall to the buffer before stopping.
     */
    private fun moveFromBuffer(
        bufSrc: BufferSource,
        destStorage: ItemStorageHandle,
        requested: Long,
        atomic: Boolean
    ): Long {
        val id = Identifier.tryParse(bufSrc.itemId) ?: return 0L
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return 0L

        if (atomic) {
            // Pre-check atomically whether destination can accept everything. If so,
            // extract from buffer then insert exactly that amount via the atomic primitive.
            // If platform's real insert somehow can't match the sim (shouldn't happen on
            // single-threaded server), we return the extracted items to the buffer.
            val extracted = bufSrc.extract(requested)
            if (extracted < requested) {
                bufSrc.returnUnused(extracted)
                return 0L
            }
            val ok = PlatformServices.storage.tryInsertAll(destStorage, item, extracted)
            if (!ok) {
                bufSrc.returnUnused(extracted)
                return 0L
            }
            return extracted
        }

        // Best-effort: pull what fits, put unused back.
        val maxStack = item.getDefaultMaxStackSize().toLong()
        var totalMoved = 0L
        var remaining = requested
        while (remaining > 0L) {
            val batch = minOf(remaining, maxStack)
            val extracted = bufSrc.extract(batch)
            if (extracted == 0L) break
            val stack = net.minecraft.world.item.ItemStack(item, extracted.toInt())
            val inserted = PlatformServices.storage.insertItemStack(destStorage, stack).toLong()
            if (inserted < extracted) {
                bufSrc.returnUnused(extracted - inserted)
                totalMoved += inserted
                break
            }
            totalMoved += inserted
            remaining -= inserted
        }
        return totalMoved
    }

    /**
     * Storage → destination.
     *
     * Atomic path uses [PlatformServices.StorageService.tryMoveAll] — a platform-native
     * transaction encloses the extract+insert, so either the full [requested] count moves
     * or neither side is touched. Eliminates the duplication class of bugs that come with
     * extract-partial-then-rollback-by-filter.
     *
     * Best-effort path uses the existing `moveItems` which is inherently best-effort.
     */
    private fun moveFromStorage(
        itemsHandle: ItemsHandle,
        destStorage: ItemStorageHandle,
        requested: Long,
        atomic: Boolean
    ): Long {
        val sourceStorage = itemsHandle.sourceStorage() ?: return 0L
        if (atomic) {
            val ok = try {
                PlatformServices.storage.tryMoveAll(
                    sourceStorage, destStorage,
                    { matchesFilter(it, itemsHandle.filter) },
                    requested
                )
            } catch (_: Exception) {
                false
            }
            return if (ok) requested else 0L
        }
        return try {
            PlatformServices.storage.moveItems(
                sourceStorage, destStorage,
                { matchesFilter(it, itemsHandle.filter) },
                requested
            )
        } catch (_: Exception) {
            0L
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

    private fun getFluidStorage(): FluidStorageHandle? {
        val cap = card.capability
        val targetPos = cap.adjacentPos
        val face = accessFace ?: (cap as? IOSideCapability)?.defaultFace ?: Direction.UP
        return PlatformServices.storage.getFluidStorage(level, targetPos, face)
    }

    fun toLuaTable(): LuaTable {
        val table = LuaTable()
        val self = this

        // .name — readable alias for the card, matching how it's labelled in the terminal
        // sidebar and Card Programmer. Falls back through the same chain as everywhere else
        // (`alias ?? autoAlias ?? capabilityType`) so `print(card.name)` always produces
        // something meaningful even for un-renamed cards.
        table.set("name", LuaValue.valueOf(card.effectiveAlias))

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

        // :find(filter) -> ItemsHandle or nil (aggregated count across all slots/tanks)
        // Item side first, then fluid, unless the filter carries a kind prefix.
        table.set("find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = parseFilterKind(filter)

                if (kindGate == null || kindGate == ResourceKind.ITEM) {
                    val storage = self.getItemStorage()
                    if (storage != null) {
                        val info = PlatformServices.storage.findFirstItemInfo(storage) { matchesFilter(it, ResourceKind.ITEM, filter) }
                        if (info != null) {
                            val totalCount = PlatformServices.storage.countItems(storage) { matchesFilter(it, ResourceKind.ITEM, filter) }
                            val aggregatedInfo = damien.nodeworks.platform.ItemInfo(
                                itemId = info.itemId,
                                name = info.name,
                                count = totalCount,
                                maxStackSize = info.maxStackSize,
                                hasData = info.hasData
                            )
                            val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = { self.getItemStorage() }
                            return ItemsHandle.toLuaTable(ItemsHandle.fromItemInfo(aggregatedInfo, filter, sourceStorage, level))
                        }
                    }
                }
                if (kindGate == null || kindGate == ResourceKind.FLUID) {
                    val storage = self.getFluidStorage()
                    if (storage != null) {
                        val info = PlatformServices.storage.findFirstFluidInfo(storage) { matchesFilter(it, ResourceKind.FLUID, filter) }
                        if (info != null) {
                            val totalAmount = PlatformServices.storage.countFluid(storage) { matchesFilter(it, ResourceKind.FLUID, filter) }
                            val aggregated = damien.nodeworks.platform.FluidInfo(info.fluidId, info.name, totalAmount)
                            val fluidSource: () -> FluidStorageHandle? = { self.getFluidStorage() }
                            return ItemsHandle.toLuaTable(ItemsHandle.fromFluidInfo(aggregated, filter, fluidSource, level))
                        }
                    }
                }
                return LuaValue.NIL
            }
        })

        // :findEach(filter) -> table of ItemsHandles (items then fluids, filtered by kind prefix if any)
        table.set("findEach", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = parseFilterKind(filter)
                val result = LuaTable()
                var idx = 1
                if (kindGate == null || kindGate == ResourceKind.ITEM) {
                    val storage = self.getItemStorage()
                    if (storage != null) {
                        val items = PlatformServices.storage.findAllItemInfo(storage) { matchesFilter(it, ResourceKind.ITEM, filter) }
                        val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = { self.getItemStorage() }
                        for (info in items) {
                            val handle = ItemsHandle.fromItemInfo(info, info.itemId, sourceStorage, level)
                            result.set(idx++, ItemsHandle.toLuaTable(handle))
                        }
                    }
                }
                if (kindGate == null || kindGate == ResourceKind.FLUID) {
                    val storage = self.getFluidStorage()
                    if (storage != null) {
                        val fluids = PlatformServices.storage.findAllFluidInfo(storage) { matchesFilter(it, ResourceKind.FLUID, filter) }
                        val fluidSource: () -> FluidStorageHandle? = { self.getFluidStorage() }
                        for (info in fluids) {
                            val handle = ItemsHandle.fromFluidInfo(info, "\$fluid:${info.fluidId}", fluidSource, level)
                            result.set(idx++, ItemsHandle.toLuaTable(handle))
                        }
                    }
                }
                return result
            }
        })

        // :insert(itemsHandle, count?) -> boolean
        // Atomic move: moves `count` (or handle.count if omitted) exactly, or 0. Never partial.
        // Use :tryInsert for best-effort "move what fits" semantics.
        table.set("insert", buildInsertFn(self, atomic = true))

        // :tryInsert(itemsHandle, count?) -> number moved
        // Best-effort move: returns the actual count moved (0..requested).
        table.set("tryInsert", buildInsertFn(self, atomic = false))

        // :count(filter) -> number (items + fluids matching, or only one if kind-prefixed)
        table.set("count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = parseFilterKind(filter)
                var total = 0L
                if (kindGate == null || kindGate == ResourceKind.ITEM) {
                    val itemStorage = self.getItemStorage()
                    if (itemStorage != null) {
                        total += PlatformServices.storage.countItems(itemStorage) { matchesFilter(it, ResourceKind.ITEM, filter) }
                    }
                }
                if (kindGate == null || kindGate == ResourceKind.FLUID) {
                    val fluidStorage = self.getFluidStorage()
                    if (fluidStorage != null) {
                        total += PlatformServices.storage.countFluid(fluidStorage) { matchesFilter(it, ResourceKind.FLUID, filter) }
                    }
                }
                return LuaValue.valueOf(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        })

        // Internal: allows insert() to access this handle's storage as a destination
        table.set("_getStorage", StorageGetter { self.getItemStorage() })

        // Internal: target coordinates for job persistence (resume after restart)
        val cap = card.capability
        val resolvedFace = accessFace ?: (cap as? IOSideCapability)?.defaultFace ?: Direction.UP
        table.set("_targetPos", LuaValue.valueOf(cap.adjacentPos.asLong().toDouble()))
        table.set("_targetFace", LuaValue.valueOf(resolvedFace.ordinal))

        return table
    }

    /** Internal LuaValue subclass to pass storage getter between CardHandles. */
    class StorageGetter(private val getter: () -> ItemStorageHandle?) : LuaValue() {
        fun getStorage(): ItemStorageHandle? = getter()
        override fun type(): Int = TUSERDATA
        override fun typename(): String = "StorageGetter"
    }
}
