package damien.nodeworks.script

import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.FluidStorageHandle
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.platform.ResourceKind
import net.minecraft.server.level.ServerLevel

/**
 * Utility for querying items across all Storage Cards on a network.
 * Storage cards are sorted by priority (descending) before scanning.
 */
object NetworkStorageHelper {

    fun getStorageCards(snapshot: NetworkSnapshot): List<CardSnapshot> {
        return snapshot.allCards()
            .filter { it.capability is StorageSideCapability }
            .sortedByDescending { (it.capability as StorageSideCapability).priority }
    }

    /**
     * Storage cards are fluid-first: if the adjacent block exposes a fluid capability,
     * item I/O is disabled on that card. This keeps the Inventory Terminal from mixing
     * item and fluid totals on hybrid blocks that expose both caps.
     */
    fun getStorage(level: ServerLevel, card: CardSnapshot): ItemStorageHandle? {
        val cap = card.capability as? StorageSideCapability ?: return null
        if (PlatformServices.storage.getFluidStorage(level, cap.adjacentPos, cap.defaultFace) != null) return null
        return PlatformServices.storage.getItemStorage(level, cap.adjacentPos, cap.defaultFace)
    }

    fun getFluidStorage(level: ServerLevel, card: CardSnapshot): FluidStorageHandle? {
        val cap = card.capability as? StorageSideCapability ?: return null
        return PlatformServices.storage.getFluidStorage(level, cap.adjacentPos, cap.defaultFace)
    }

    fun countItems(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Long {
        var total = 0L
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            total += PlatformServices.storage.countItems(storage) { CardHandle.matchesFilter(it, ResourceKind.ITEM, filter) }
        }
        return total
    }

    fun countFluid(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Long {
        var total = 0L
        for (card in getStorageCards(snapshot)) {
            val storage = getFluidStorage(level, card) ?: continue
            total += PlatformServices.storage.countFluid(storage) { CardHandle.matchesFilter(it, ResourceKind.FLUID, filter) }
        }
        return total
    }

    /**
     * Count items + fluids matching [filter] across the network.
     * A filter like `item:*` skips fluids, `fluid:*` skips items, bare filters sum both.
     */
    fun countResource(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Long {
        val (kindGate, _) = CardHandle.parseFilterKind(filter)
        var total = 0L
        if (kindGate == null || kindGate == ResourceKind.ITEM) total += countItems(level, snapshot, filter)
        if (kindGate == null || kindGate == ResourceKind.FLUID) total += countFluid(level, snapshot, filter)
        return total
    }

    fun findFirstFluidInfoAcrossNetwork(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Pair<FluidInfo, CardSnapshot>? {
        for (card in getStorageCards(snapshot)) {
            val storage = getFluidStorage(level, card) ?: continue
            val info = PlatformServices.storage.findFirstFluidInfo(storage) { CardHandle.matchesFilter(it, ResourceKind.FLUID, filter) }
            if (info != null) return Pair(info, card)
        }
        return null
    }

    fun insertFluidAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        fluidId: String,
        amount: Long,
        cache: NetworkInventoryCache? = null
    ): Long {
        if (amount <= 0L) return 0L
        var remaining = amount
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0L) break
            val storage = getFluidStorage(level, card) ?: continue
            val inserted = PlatformServices.storage.insertFluid(storage, fluidId, remaining)
            remaining -= inserted
        }
        val placed = amount - remaining
        if (placed > 0) cache?.onFluidInserted(fluidId, placed)
        return placed
    }

    /**
     * Atomic counterpart to [insertItems]. Moves exactly [requested] items from [source] into
     * the network's storage cards (honouring routes + callback), or moves nothing and returns
     * false. Matches [damien.nodeworks.script.CardHandle]'s `insert` semantics: either the full
     * amount lands, or the source is left untouched.
     *
     * **Sim-first, then commit.** We never touch the source until we've verified, via
     * non-mutating [PlatformServices.StorageService.simulateInsertItem] calls, that every
     * matching item type the filter picks has enough network-wide capacity. If the probe
     * shows insufficient space for any single type, we return false with zero mutation,
     * no extract, no rollback, no chance to dupe or lose items on a partial commit.
     *
     * Once the probe passes, the commit runs through best-effort [insertItems] which honours
     * routes + callbacks. Routing may place items on different cards than the sim priority
     * walk picked, but capacity is fungible across cards for a given item type, so a clean
     * sim guarantees commit success under single-threaded server execution.
     */
    fun tryInsertItemsAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        requested: Long,
        routeTable: RouteTable? = null,
        cache: NetworkInventoryCache? = null
    ): Boolean {
        if (requested <= 0L) return true

        // Pin the per-type demand from source: scan item types matching the filter and
        // allocate `requested` across them in source-iteration order. If source doesn't
        // actually hold `requested` matching items, demand sums short and we fail fast.
        val demand = LinkedHashMap<String, Long>()
        var remainingDemand = requested
        for (info in PlatformServices.storage.findAllItemInfo(source) { CardHandle.matchesFilter(it, filter) }) {
            if (remainingDemand <= 0L) break
            val take = minOf(remainingDemand, info.count)
            if (take > 0L) {
                demand.merge(info.itemId, take, Long::plus)
                remainingDemand -= take
            }
        }
        if (remainingDemand > 0L) return false

        // Capacity probe, per item type. Each type checks independently against the full
        // network, a storage card's free slots are consumed by the first type we probe,
        // but subsequent probes aren't affected because probes don't mutate. In the rare
        // real-world case where capacity is tight enough that two types compete for the
        // same slot, routing at commit time handles the allocation, worst-case we trip the
        // shortfall guard below and return false without a bad partial state.
        val storageCards = getStorageCards(snapshot)
        for ((itemId, need) in demand) {
            val id = net.minecraft.resources.Identifier.tryParse(itemId) ?: return false
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: return false
            var capacity = 0L
            for (card in storageCards) {
                if (capacity >= need) break
                val dest = getStorage(level, card) ?: continue
                capacity += try {
                    PlatformServices.storage.simulateInsertItem(dest, item, need - capacity)
                } catch (_: Exception) { 0L }
            }
            if (capacity < need) return false
        }

        // Sim said it fits, commit. Under single-threaded server execution this should
        // place `requested` exactly, if routing + sim disagree (shouldn't happen with
        // vanilla IItemHandler but defensive for modded storages), we treat the shortfall
        // as a hard failure and unwind by reverse-moving the committed items back to
        // source. This keeps the atomic contract even on pathological edge cases.
        val moved = insertItems(level, snapshot, source, filter, requested, routeTable, null, cache)
        if (moved == requested) return true

        // Unexpected shortfall, unwind. Since source was just drained of at most `moved`
        // items of types we tracked in `demand`, it necessarily has slot capacity for them
        // back (fungibility within item type). Rollback runs per-type so cache.onExtracted
        // receives the correct (itemId, hasData) pairs.
        for ((itemId, need) in demand) {
            var toReturn = minOf(need, moved) // can't return more than actually moved
            if (toReturn <= 0L) continue
            val variantFilter: (String) -> Boolean = { it == itemId }
            var returned = 0L
            for (card in storageCards) {
                if (toReturn <= 0L) break
                val dest = getStorage(level, card) ?: continue
                val back = try {
                    PlatformServices.storage.moveItems(dest, source, variantFilter, toReturn)
                } catch (_: Exception) { 0L }
                toReturn -= back
                returned += back
            }
            if (returned > 0L) {
                val info = PlatformServices.storage.findFirstItemInfo(source) { it == itemId }
                if (info != null) cache?.onExtracted(info.itemId, info.hasData, returned)
            }
        }
        return false
    }

    /**
     * Atomic counterpart to [insertFluidAcrossNetwork]. Either places exactly [amount] mB of
     * [fluidId] into network fluid storages, or places nothing and returns false.
     *
     * Sim-first: sum `simulateInsertFluid` across storage cards to verify capacity before
     * any mutation. Only commits if the full amount is known to fit.
     */
    fun tryInsertFluidAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        fluidId: String,
        amount: Long,
        cache: NetworkInventoryCache? = null
    ): Boolean {
        if (amount <= 0L) return true

        val storageCards = getStorageCards(snapshot)
        var capacity = 0L
        for (card in storageCards) {
            if (capacity >= amount) break
            val storage = getFluidStorage(level, card) ?: continue
            capacity += try {
                PlatformServices.storage.simulateInsertFluid(storage, fluidId, amount - capacity)
            } catch (_: Exception) { 0L }
        }
        if (capacity < amount) return false

        // Commit. Since sim passed, this should place all, unwind drain on divergence.
        var remaining = amount
        val committed = mutableListOf<Pair<FluidStorageHandle, Long>>()
        for (card in storageCards) {
            if (remaining <= 0L) break
            val storage = getFluidStorage(level, card) ?: continue
            val inserted = try {
                PlatformServices.storage.insertFluid(storage, fluidId, remaining)
            } catch (_: Exception) { 0L }
            if (inserted > 0L) {
                committed.add(storage to inserted)
                remaining -= inserted
            }
        }
        if (remaining == 0L) {
            cache?.onFluidInserted(fluidId, amount)
            return true
        }
        for ((storage, placed) in committed) {
            try {
                PlatformServices.storage.extractFluid(storage, { it == fluidId }, placed)
            } catch (_: Exception) { /* best-effort rollback */ }
        }
        return false
    }

    /** Aggregate fluid totals across the whole network in a single pass, used in place
     *  of per-fluid `countFluid` calls when the caller already needs the full list.
     *  Returns `(FluidInfo, sourceCard)` pairs with amounts summed across all storage cards. */
    fun findAllFluidInfoAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String
    ): List<Pair<FluidInfo, CardSnapshot>> {
        val aggregated = LinkedHashMap<String, Pair<FluidInfo, CardSnapshot>>()
        for (card in getStorageCards(snapshot)) {
            val storage = getFluidStorage(level, card) ?: continue
            val fluids = PlatformServices.storage.findAllFluidInfo(storage) {
                CardHandle.matchesFilter(it, ResourceKind.FLUID, filter)
            }
            for (info in fluids) {
                val existing = aggregated[info.fluidId]
                if (existing != null) {
                    val merged = existing.first.copy(amount = existing.first.amount + info.amount)
                    aggregated[info.fluidId] = merged to existing.second
                } else {
                    aggregated[info.fluidId] = info to card
                }
            }
        }
        return aggregated.values.toList()
    }

    /** Find the first item ID across all Storage Cards matching the filter. */
    fun findFirstItemId(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): String? {
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            val itemId = PlatformServices.storage.findFirstItem(storage) { CardHandle.matchesFilter(it, filter) }
            if (itemId != null) return itemId
        }
        return null
    }

    /** Find the first item info across all Storage Cards matching the filter, with its source card. */
    fun findFirstItemInfoAcrossNetwork(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): Pair<ItemInfo, CardSnapshot>? {
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            val info = PlatformServices.storage.findFirstItemInfo(storage) { CardHandle.matchesFilter(it, filter) }
            if (info != null) return Pair(info, card)
        }
        return null
    }

    /** Find all unique item types across all Storage Cards matching the filter, with their source cards. */
    fun findAllItemInfoAcrossNetwork(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): List<Pair<ItemInfo, CardSnapshot>> {
        val results = mutableListOf<Pair<ItemInfo, CardSnapshot>>()
        val seen = mutableSetOf<String>()
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            val items = PlatformServices.storage.findAllItemInfo(storage) { CardHandle.matchesFilter(it, filter) }
            for (info in items) {
                val key = "${info.itemId}:${info.hasData}"
                if (seen.add(key)) {
                    results.add(Pair(info, card))
                }
            }
        }
        return results
    }

    fun findItem(level: ServerLevel, snapshot: NetworkSnapshot, filter: String): CardSnapshot? {
        for (card in getStorageCards(snapshot)) {
            val storage = getStorage(level, card) ?: continue
            val count = PlatformServices.storage.countItems(storage) { CardHandle.matchesFilter(it, filter) }
            if (count > 0) return card
        }
        return null
    }

    /** Insert an ItemStack into the network's Storage Cards (highest priority first). Returns count inserted.
     *
     *  Skips cards whose configured filter rejects the stack. The importer,
     *  inventory terminal manual inserts, crafting CPU completion, and the
     *  CPU op executor all funnel through here, so without the filter check
     *  any card with an ALLOW-mode whitelist would still receive every item
     *  the network produced (the filter would only gate the Lua-API
     *  `:insert` paths). */
    fun insertItemStack(level: ServerLevel, snapshot: NetworkSnapshot, stack: net.minecraft.world.item.ItemStack, cache: NetworkInventoryCache? = null): Int {
        var remaining = stack.count
        val itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
        // ItemStack carries its own NBT-presence state, so the filter check
        // can use the actual `hasData` rather than guessing.
        val hasData = !stack.componentsPatch.isEmpty
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0) break
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
            if (cap != null && itemId != null && !cap.acceptsItem(itemId, hasData)) continue
            val storage = getStorage(level, card) ?: continue
            val inserted = PlatformServices.storage.insertItemStack(storage, stack.copyWithCount(remaining))
            remaining -= inserted
        }
        val totalInserted = stack.count - remaining
        if (totalInserted > 0 && cache != null) {
            if (itemId != null) {
                cache.onInserted(itemId, hasData, totalInserted.toLong())
            }
        }
        return totalInserted
    }

    /**
     * Move items from a source storage into the network's Storage Cards.
     * Resolution order: routes → onInsert callback → default (open storages only if routes exist).
     */
    fun insertItems(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        routeTable: RouteTable? = null,
        onInsertCallback: ((String, Long) -> ItemStorageHandle?)? = null,
        cache: NetworkInventoryCache? = null
    ): Long {
        if (routeTable == null && onInsertCallback == null) {
            // No routing, fast path, use all storages
            return insertItemsDefault(level, snapshot, source, filter, maxCount, cache)
        }
        return insertItemsRouted(level, snapshot, source, filter, maxCount, routeTable, onInsertCallback, cache)
    }

    /**
     * Routes each unique item type through routes/callback individually.
     * Unmatched items go to open (unrouted) storages only.
     */
    private fun insertItemsRouted(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        routeTable: RouteTable?,
        onInsertCallback: ((String, Long) -> ItemStorageHandle?)?,
        cache: NetworkInventoryCache? = null
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount
        val processedItems = mutableSetOf<String>()

        while (remaining > 0) {
            val itemInfo = PlatformServices.storage.findFirstItemInfo(source) {
                CardHandle.matchesFilter(it, filter) && it !in processedItems
            } ?: break
            val itemId = itemInfo.itemId
            val hasData = itemInfo.hasData
            val variantKey = "$itemId:$hasData"

            processedItems.add(itemId)

            val variantFilter: (String, Boolean) -> Boolean = { id, data -> id == itemId && data == hasData }
            val count = PlatformServices.storage.countItems(source) { it == itemId }
            val toMove = minOf(remaining, count)

            // 1. Check routes first (precomputed, fast). A route may have multiple candidate
            //    cards (wildcard pattern like `cobblestone_*`), iterate them in order,
            //    moving what fits before overflowing to open storages.
            val routeTargets = routeTable?.findRouteTargets(itemInfo) ?: emptyList()
            if (routeTargets.isNotEmpty()) {
                var routeRemaining = toMove
                for ((targetCard, target) in routeTargets) {
                    if (routeRemaining <= 0L) break
                    // Per-card filter gate. A route may point at a card whose
                    // configured rules reject this item (misconfiguration, but
                    // valid). Skip those and let overflow fall through to
                    // open storages. Passes [hasData] so the NBT gate works.
                    val cap = targetCard.capability as? damien.nodeworks.card.StorageSideCapability
                    if (cap != null && !cap.acceptsItem(itemId, hasData)) continue
                    val moved = try {
                        PlatformServices.storage.moveItemsVariant(source, target, variantFilter, routeRemaining)
                    } catch (_: Exception) { 0L }
                    if (moved > 0) cache?.onInserted(itemId, hasData, moved)
                    totalMoved += moved
                    remaining -= moved
                    routeRemaining -= moved
                }
                if (routeRemaining > 0L && routeTable != null) {
                    val overflow = routeTable.insertDefault(source, itemId, routeRemaining)
                    if (overflow > 0) cache?.onInserted(itemId, hasData, overflow)
                    totalMoved += overflow
                    remaining -= overflow
                }
                continue
            }

            // 2. Check onInsert callback
            val callbackTarget = onInsertCallback?.invoke(itemId, toMove)
            if (callbackTarget != null) {
                val moved = try {
                    PlatformServices.storage.moveItemsVariant(source, callbackTarget, variantFilter, toMove)
                } catch (_: Exception) { 0L }
                if (moved > 0) cache?.onInserted(itemId, hasData, moved)
                totalMoved += moved
                remaining -= moved
                if (moved < toMove) {
                    // Callback target full, fall to open storages or all storages
                    val fallbackMoved = if (routeTable != null) {
                        routeTable.insertDefault(source, itemId, toMove - moved)
                    } else {
                        insertItemsDefault(level, snapshot, source, itemId, toMove - moved, cache)
                    }
                    if (fallbackMoved > 0) cache?.onInserted(itemId, hasData, fallbackMoved)
                    totalMoved += fallbackMoved
                    remaining -= fallbackMoved
                }
                continue
            }

            // 3. No route or callback match, use open storages (or all if no routes)
            val defaultMoved = if (routeTable != null) {
                routeTable.insertDefault(source, itemId, toMove)
            } else {
                insertItemsDefault(level, snapshot, source, itemId, toMove, cache)
            }
            if (defaultMoved > 0) cache?.onInserted(itemId, hasData, defaultMoved)
            totalMoved += defaultMoved
            remaining -= defaultMoved
        }

        return totalMoved
    }

    /** Default priority-based routing across ALL storage cards (no route filtering). */
    private fun insertItemsDefault(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        cache: NetworkInventoryCache? = null
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0) break
            val destStorage = getStorage(level, card) ?: continue
            // Per-card filter gate, see [RouteTable.insertDefault] for the rationale.
            // An ALLOW-mode card with empty rules + ANY/ANY gates accepts anything
            // (legacy behavior). Routes through `moveItemsVariant` so the filter
            // sees `hasData` for the NBT-presence gate.
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
            val moved = try {
                PlatformServices.storage.moveItemsVariant(
                    source, destStorage,
                    { id, hasData ->
                        CardHandle.matchesFilter(id, filter) && (cap == null || cap.acceptsItem(id, hasData))
                    },
                    remaining
                )
            } catch (_: Exception) { 0L }
            totalMoved += moved
            remaining -= moved
        }
        return totalMoved
    }
}
