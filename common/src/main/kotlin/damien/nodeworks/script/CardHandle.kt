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
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import java.util.UUID

/**
 * Lua-side handle for a card on the network. Exposes :find(), :insert(), :count(), :face(), :slots().
 * Lua's `:` method call passes `self` as the first arg.
 */
class CardHandle private constructor(
    private val card: CardSnapshot,
    private val level: ServerLevel,
    private val accessFace: Direction?,
    private val slotFilter: Set<Int>?,
    /** Network UUID this card belongs to. Used by [buildInsertFn] to charge
     *  the per-network items-moved budget for card-level inserts. Null means
     *  the card is on a network without a controller (rare/transient), the
     *  rate limiter falls back to its NO_NETWORK_UUID bucket. */
    private val networkId: UUID?,
    /** Live network snapshot accessor. Each storage-resolving call walks the
     *  current snapshot to confirm the card still occupies the same slot it
     *  did at handle creation, so removing or moving the card surfaces as a
     *  clear LuaError instead of silently writing to the original chest.
     *  Null skips the check (preconstructed handles in GuideME scenes). */
    private val snapshotFn: (() -> damien.nodeworks.network.NetworkSnapshot)?,
) {
    companion object {
        private val logger = LoggerFactory.getLogger("nodeworks-cardhandle")

        /**
         * Parse a user-facing filter string into an optional kind gate and the inner pattern.
         *
         * A leading `${'$'}item:` or `${'$'}fluid:` locks the match to that resource kind, the remainder
         * is the pattern applied to the resource id. `*`, `<mod>:*`, `#tag`, `/regex/`, and exact id
         * are supported as patterns (kind-agnostic otherwise). The `${'$'}` sigil keeps the kind
         * prefix orthogonal to mod namespaces, no risk of clashing with a mod named "item".
         */
        fun parseFilterKind(filter: String): Pair<ResourceKind?, String> = when {
            filter.startsWith("\$item:") -> Pair(ResourceKind.ITEM, filter.removePrefix("\$item:"))
            filter.startsWith("\$fluid:") -> Pair(ResourceKind.FLUID, filter.removePrefix("\$fluid:"))
            else -> Pair(null, filter)
        }

        /** Backward-compat overload, assumes the tested id is an item id. */
        fun matchesFilter(itemId: String, filter: String): Boolean =
            matchesFilter(itemId, ResourceKind.ITEM, filter)

        /** Match by id only. Doesn't know about [DataComponentPatch], so a
         *  filter like `minecraft:potion[...]` degrades to plain itemId matching
         *  here. Prefer the [ItemStack] overload whenever the stack is in
         *  hand. Used by fluid checks and a few block-id sites that never
         *  carry component data. */
        fun matchesFilter(resourceId: String, kind: ResourceKind, filter: String): Boolean {
            val rule = cachedRule(filter, fallbackRegistries)
            return rule.matches(resourceId, kind)
        }

        /** Component-aware match for an [net.minecraft.world.item.ItemStack].
         *  Reads the per-stack components patch so a rule like
         *  `minecraft:potion[minecraft:potion_contents={potion:"minecraft:strength"}]`
         *  matches only the specific potion variant. */
        fun matchesFilter(
            stack: net.minecraft.world.item.ItemStack,
            filter: String,
            registries: net.minecraft.core.HolderLookup.Provider,
        ): Boolean {
            val rule = cachedRule(filter, registries)
            return rule.matches(stack)
        }

        /** Per-string parsed-rule cache. Filter strings repeat across many
         *  match calls (Storage Card rule list evaluated per item per tick),
         *  caching the parsed structure avoids re-invoking [ItemParser] for
         *  component-bearing rules on every match. */
        private val ruleCache = object : LinkedHashMap<String, FilterRule>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FilterRule>?): Boolean {
                return size > 256
            }
        }

        @Synchronized
        private fun cachedRule(
            filter: String,
            registries: net.minecraft.core.HolderLookup.Provider?,
        ): FilterRule {
            ruleCache[filter]?.let { return it }
            val parsed = if (registries != null) FilterRule.parse(filter, registries)
                else parseWithoutRegistries(filter)
            ruleCache[filter] = parsed
            return parsed
        }

        /** Best-effort parse when no registries are available. Drops the
         *  `[components]` portion so the surviving shape (id / tag / regex /
         *  namespace / sigil) still works for legacy id-only callers. */
        private fun parseWithoutRegistries(filter: String): FilterRule {
            val bracket = filter.indexOf('[')
            val trimmed = if (bracket >= 0) filter.substring(0, bracket) else filter
            return FilterRule.parse(trimmed, net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty()))
        }

        /** Captured at server start so the legacy id-only overload can still
         *  parse `[components]` filters. Null until the first server starts,
         *  which is fine because no script can call [matchesFilter] before
         *  that. */
        @Volatile
        private var fallbackRegistries: net.minecraft.core.HolderLookup.Provider? = null

        fun setFallbackRegistries(registries: net.minecraft.core.HolderLookup.Provider) {
            fallbackRegistries = registries
            synchronized(this) { ruleCache.clear() }
        }

        /** Public read for callers that need to parse a [FilterRule]
         *  themselves (e.g. to extract the variant patch before dispatching
         *  to a variant-specific storage helper). Returns null until the
         *  server starts and sets registries. */
        fun parsedRuleRegistries(): net.minecraft.core.HolderLookup.Provider? = fallbackRegistries

        /** If [filter] parses as `Item(_, componentsPatch != null)`, returns
         *  that patch. Used by find/count call sites to narrow results to a
         *  specific variant. Returns null for plain filters (any variant
         *  matches) and fluid-side filters (fluids carry no components). */
        fun parsedVariantPatchForFind(filter: String): net.minecraft.core.component.DataComponentPatch? {
            if (!filter.contains('[')) return null
            val inner = when {
                filter.startsWith("\$item:") -> filter.removePrefix("\$item:")
                filter.startsWith("\$fluid:") -> return null
                else -> filter
            }
            val registries = fallbackRegistries ?: return null
            val rule = cachedRule(inner, registries)
            return (rule as? FilterRule.Item)?.componentsPatch
        }

        fun create(
            card: CardSnapshot,
            level: ServerLevel,
            networkId: UUID? = null,
            snapshotFn: (() -> damien.nodeworks.network.NetworkSnapshot)? = null,
        ): LuaTable {
            return CardHandle(card, level, null, null, networkId, snapshotFn).toLuaTable()
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
     *   - Storage-backed source: move up to requested and accept whatever landed, moveItems
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

                // Per-network call cap. Charged here (not in a wrapper) so all
                // CardHandle insert paths share the same budget, including handles
                // returned from `:face(...)` / `:slots(...)` whose freshly-constructed
                // tables would otherwise route around an outer wrapper.
                val tick = PlatformServices.modState.tickCount
                val callBudget = NetworkRateLimits.forNetwork(self.networkId)
                if (!callBudget.tryConsumeItemMoveCall(tick)) {
                    if (callBudget.warnOnce(NetworkBudget.WARN_ITEM_MOVE)) {
                        logger.info("[card:insert calls rate-limited this tick on network {}]", self.networkId)
                    }
                    return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                }

                // Per-network items-moved budget (items only; fluids dispatch through
                // a separate path below). Mirrors [ScriptEngine.invokeItems]: clamp
                // the request, atomic short-circuits when budget can't fit the full
                // count, best-effort clamps and charges only what actually moved.
                val isItem = itemsHandle.kind != ResourceKind.FLUID
                val budget = if (isItem) callBudget else null
                val available = if (isItem) budget!!.availableItems(tick) else Long.MAX_VALUE
                if (isItem && available < requested) {
                    if (budget!!.warnOnce(NetworkBudget.WARN_ITEMS_MOVED)) {
                        logger.info("[card:insert items moved rate-limited this tick on network {}]", self.networkId)
                    }
                    if (atomic) return LuaValue.FALSE
                    if (available <= 0L) return LuaValue.valueOf(0)
                }
                val clamped = minOf(requested, available)

                // Dispatch by the handle's kind, an item handle targets the card's item cap,
                // a fluid handle targets the card's fluid cap. A block with neither matching
                // cap simply fails the op (returns 0/false).
                val moved: Long = if (itemsHandle.kind == ResourceKind.FLUID) {
                    val destFluid = self.getFluidStorage()
                        ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                    moveFluidToDest(itemsHandle, destFluid, clamped, atomic)
                } else {
                    val destStorage = self.getItemStorage()
                        ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                    val bufSrc = itemsHandle.bufferSource
                    if (bufSrc != null) moveFromBuffer(bufSrc, destStorage, clamped, atomic)
                    else moveFromStorage(itemsHandle, destStorage, clamped, atomic)
                }
                if (isItem) budget!!.noteItemsMoved(tick, moved)

                return if (atomic) {
                    LuaValue.valueOf(moved == clamped)
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
     * Atomic path uses [PlatformServices.StorageService.tryInsertAll], checks destination
     * capacity via the platform's native transaction/simulation, moves only if all fits.
     * On failure, nothing is extracted from the buffer, no rollback needed.
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
        // Snapshot the buffer template ONCE before any extract. The bucket
        // gets removed when the last item is drained, so a later read of
        // bufSrc.template would return EMPTY and we'd write bare items to
        // the destination, losing potion variants, dye colors, enchants.
        val capturedTemplate = bufSrc.template.let { tmpl ->
            if (tmpl.isEmpty) net.minecraft.world.item.ItemStack(item) else tmpl.copy()
        }
        val templateFn: (Int) -> net.minecraft.world.item.ItemStack = { count ->
            capturedTemplate.copyWithCount(count)
        }

        if (atomic) {
            // Simulate the FULL requested amount before touching the buffer,
            // so an under-capacity destination leaves both sides untouched.
            val maxStack = item.getDefaultMaxStackSize().toLong()
            val capacity = try {
                PlatformServices.storage.simulateInsertItem(destStorage, item, requested)
            } catch (_: Exception) { 0L }
            if (capacity < requested) return 0L

            val extracted = bufSrc.extract(requested)
            if (extracted < requested) {
                bufSrc.returnUnused(extracted)
                return 0L
            }

            var inserted = 0L
            var remaining = extracted
            while (remaining > 0L) {
                val batch = minOf(remaining, maxStack)
                val stack = templateFn(batch.toInt())
                val put = PlatformServices.storage.insertItemStack(destStorage, stack).toLong()
                inserted += put
                remaining -= put
                if (put < batch) break  // sim/commit divergence, bail to rollback
            }

            if (inserted < extracted) {
                // Sim/commit divergence (modded storage, concurrent mutation).
                // Pull the inserted variant back out so the move stays atomic.
                val pulledBack = try {
                    PlatformServices.storage.extractStacksByPredicate(
                        destStorage,
                        { st -> net.minecraft.world.item.ItemStack.isSameItemSameComponents(st, capturedTemplate) },
                        inserted,
                    )
                } catch (_: Exception) { emptyList() }
                val recovered = pulledBack.sumOf { it.count.toLong() }
                bufSrc.returnUnused(extracted - inserted + recovered)
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
            val stack = templateFn(extracted.toInt())
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
     * Atomic path uses [PlatformServices.StorageService.tryMoveAll], a platform-native
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
        // When the destination is a Storage Card with configured filter rules,
        // gate the insert on its [acceptsItem] check too. Direct `card:insert`
        // calls respect the card's configuration so a script dumping a mixed
        // bag into a logs-only card correctly leaves everything except logs
        // in the source. Atomic path uses `tryMoveAll` whose predicate is
        // `(String) -> Boolean`, so the per-stack `hasData` isn't visible
        // there. We approximate by checking the rules + stackability gates
        // and skipping the NBT gate for atomic moves; non-atomic uses
        // `moveItemsVariant` which has full info.
        val destCap = card.capability as? damien.nodeworks.card.StorageSideCapability
        if (atomic) {
            val predicate: (String) -> Boolean = if (destCap != null) {
                { matchesFilter(it, itemsHandle.filter) && destCap.acceptsItem(it) }
            } else {
                { matchesFilter(it, itemsHandle.filter) }
            }
            val ok = try {
                PlatformServices.storage.tryMoveAll(sourceStorage, destStorage, predicate, requested)
            } catch (_: Exception) {
                false
            }
            return if (ok) requested else 0L
        }
        val variantPredicate: (String, Boolean) -> Boolean = if (destCap != null) {
            { id, hasData -> matchesFilter(id, itemsHandle.filter) && destCap.acceptsItem(id, hasData) }
        } else {
            { id, _ -> matchesFilter(id, itemsHandle.filter) }
        }
        return try {
            PlatformServices.storage.moveItemsVariant(sourceStorage, destStorage, variantPredicate, requested)
        } catch (_: Exception) {
            0L
        }
    }

    /** Confirm this handle's underlying card still occupies the same physical
     *  slot it did at creation. Identity is `(adjacentPos, slotIndex,
     *  capability.type)`, so renaming or filter edits are tolerated but
     *  removal or moving the card to a different node throws. No-op when
     *  [snapshotFn] is null (legacy / GuideME callers). */
    private fun verifyCardOnNetwork() {
        val fn = snapshotFn ?: return
        val ourPos = card.capability.adjacentPos
        val ourType = card.capability.type
        val ourSlot = card.slotIndex
        val stillThere = fn().allCards().any { c ->
            c.capability.adjacentPos == ourPos &&
                c.capability.type == ourType &&
                c.slotIndex == ourSlot
        }
        if (!stillThere) {
            throw LuaError("Card '${card.effectiveAlias}' is no longer on the network")
        }
    }

    private fun getItemStorage(): ItemStorageHandle? {
        verifyCardOnNetwork()
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
        verifyCardOnNetwork()
        val cap = card.capability
        val targetPos = cap.adjacentPos
        val face = accessFace ?: (cap as? IOSideCapability)?.defaultFace ?: Direction.UP
        return PlatformServices.storage.getFluidStorage(level, targetPos, face)
    }

    fun toLuaTable(): LuaTable {
        val table = LuaTable()
        val self = this

        // .name, readable alias for the card, matching how it's labelled in the terminal
        // sidebar and Card Programmer. Falls back through the same chain as everywhere else
        // (`alias ?? autoAlias ?? capabilityType`) so `print(card.name)` always produces
        // something meaningful even for un-renamed cards.
        table.set("name", LuaValue.valueOf(card.effectiveAlias))

        // .kind, the capability-type string. Same set as `network:getAll(kind)`'s
        // argument so scripts can filter a `network:cards("name_*")` result, e.g.
        // `for _, c in cards do if c.kind == "io" then ... end end`.
        table.set("kind", LuaValue.valueOf(card.capability.type))

        // :face(name) -> new CardHandle with specific access face
        table.setGuarded("CardHandle", "face", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val dir = faceName(name) ?: throw LuaError("Unknown face: $name")
                return CardHandle(card, level, dir, slotFilter, networkId, snapshotFn).toLuaTable()
            }
        })

        // :slots(...) -> new CardHandle filtered to specific slots
        table.setGuarded("CardHandle", "slots", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val slots = mutableSetOf<Int>()
                for (i in 2..args.narg()) {
                    slots.add(args.checkint(i) - 1) // Lua 1-indexed → 0-indexed
                }
                return CardHandle(card, level, accessFace, slots, networkId, snapshotFn).toLuaTable()
            }
        })

        // :find(filter) -> ItemsHandle or nil (aggregated count across all slots/tanks)
        // Item side first, then fluid, unless the filter carries a kind prefix.
        table.setGuarded("CardHandle", "find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = parseFilterKind(filter)

                if (kindGate == null || kindGate == ResourceKind.ITEM) {
                    val storage = self.getItemStorage()
                    if (storage != null) {
                        val variantPatch = parsedVariantPatchForFind(filter)
                        val wantHash = variantPatch?.let { damien.nodeworks.script.BufferKey.componentsHash(it) }
                        val candidates = PlatformServices.storage.findAllItemInfo(storage) {
                            matchesFilter(it, ResourceKind.ITEM, filter)
                        }
                        val narrowed = if (wantHash != null) {
                            candidates.filter { damien.nodeworks.script.BufferKey.componentsHash(it.componentsPatch) == wantHash }
                        } else candidates
                        val info = narrowed.firstOrNull()
                        if (info != null) {
                            val totalCount = narrowed.sumOf { it.count }
                            val aggregatedInfo = info.copy(count = totalCount)
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
        table.setGuarded("CardHandle", "findEach", object : TwoArgFunction() {
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
        table.setGuarded("CardHandle", "insert", buildInsertFn(self, atomic = true))

        // :tryInsert(itemsHandle, count?) -> number moved
        // Best-effort move: returns the actual count moved (0..requested).
        table.setGuarded("CardHandle", "tryInsert", buildInsertFn(self, atomic = false))

        // :count(filter) -> number (items + fluids matching, or only one if kind-prefixed)
        table.setGuarded("CardHandle", "count", object : TwoArgFunction() {
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

        // Internal: the card's resolved alias, used by preset builders (Importer /
        // Stocker) to recognise CardHandle tables in :from / :to args. Stored under
        // an internal key so presets keep working even if the user has shadowed
        // the user-facing `name` field.
        table.set("_cardRefName", LuaValue.valueOf(card.effectiveAlias))

        // Internal: when this handle was produced by `:face(name)`, expose the
        // override Direction.ordinal so preset builders carry the override into
        // their tick-time storage lookups. Absent when the user kept the card's
        // stored access face.
        if (accessFace != null) {
            table.set("_cardRefFace", LuaValue.valueOf(accessFace.ordinal))
        }

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
