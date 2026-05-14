package damien.nodeworks.script

import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.FluidStorageHandle
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.platform.ResourceKind
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel

/**
 * Utility for querying items across all Storage Cards on a network.
 * Storage cards are sorted by priority (descending) before scanning.
 */
object NetworkStorageHelper {

    fun getStorageCards(snapshot: NetworkSnapshot): List<CardSnapshot> = snapshot.storageCards

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

    fun countItems(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        // When the filter narrows to a specific variant, delegate to the
        // component-aware path so we count only matching stacks. Plain
        // itemId / tag / regex filters route through the legacy path which
        // doesn't need a registry lookup per slot.
        val variantInfo = parsedVariantInfo(filter)
        if (variantInfo != null) {
            return countVariantAcrossNetwork(level, snapshot, variantInfo.first, variantInfo.second, channel)
        }
        var total = 0L
        val visited = HashSet<BlockPos>()
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
            val cap = card.capability as? StorageSideCapability ?: continue
            // Multiple cards on different faces of the same block view the
            // same physical inventory through different slot subsets, so
            // dedup by adjacentPos to avoid double-counting overlapping slots.
            if (!visited.add(cap.adjacentPos)) continue
            total += countItemsAt(level, cap.adjacentPos, card, filter)
        }
        return total
    }

    /** Count items across the network whose itemId AND DataComponents match
     *  the target variant. Used by the planner's component-aware
     *  feasibility check so a recipe asking for Strength Potion sees only
     *  the strength-potion count, not every variant under
     *  `minecraft:potion`. */
    fun countVariantAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        itemId: String,
        componentsPatch: net.minecraft.core.component.DataComponentPatch,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        val targetHash = damien.nodeworks.script.BufferKey.componentsHash(componentsPatch)
        val visited = HashSet<BlockPos>()
        var total = 0L
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
            val cap = card.capability as? StorageSideCapability ?: continue
            if (!visited.add(cap.adjacentPos)) continue
            val storage = getStorage(level, card) ?: continue
            total += PlatformServices.storage.countStacksByPredicate(storage) { stack ->
                val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == targetHash
            }
        }
        return total
    }

    /** Extract items matching variant across the network, returning real
     *  ItemStacks with their components intact. Honours per-card filters
     *  and the channel scope. Stops after [maxCount] items total. */
    fun extractVariantAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        itemId: String,
        componentsPatch: net.minecraft.core.component.DataComponentPatch,
        maxCount: Long,
        channel: ChannelFilter = ChannelFilter.All,
    ): List<net.minecraft.world.item.ItemStack> {
        if (maxCount <= 0L) return emptyList()
        val targetHash = damien.nodeworks.script.BufferKey.componentsHash(componentsPatch)
        val out = mutableListOf<net.minecraft.world.item.ItemStack>()
        var remaining = maxCount
        val visited = HashSet<BlockPos>()
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0L) break
            if (!channel.matches(card.channel)) continue
            val cap = card.capability as? StorageSideCapability ?: continue
            if (!visited.add(cap.adjacentPos)) continue
            val storage = getStorage(level, card) ?: continue
            val pulled = PlatformServices.storage.extractStacksByPredicate(storage, { stack ->
                val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == targetHash
            }, remaining)
            for (s in pulled) {
                if (s.isEmpty) continue
                out.add(s)
                remaining -= s.count
            }
        }
        return out
    }

    /** [List] of [CardSnapshot]s deduped by adjacentPos. Higher-priority
     *  cards win on a tie. Use this anywhere the same physical inventory
     *  shouldn't be counted/visited twice when the player has cards on
     *  multiple faces of one block. */
    fun getDedupedStorageCards(snapshot: NetworkSnapshot): List<CardSnapshot> {
        val seen = HashSet<BlockPos>()
        val out = mutableListOf<CardSnapshot>()
        for (card in getStorageCards(snapshot)) {
            val cap = card.capability as? StorageSideCapability ?: continue
            if (seen.add(cap.adjacentPos)) out.add(card)
        }
        return out
    }

    /** Find items at [pos] matching [filter] via [card]'s capability-resolved
     *  storage handle. Routes through `level.getCapability(Capabilities.Item.BLOCK,
     *  pos, face)` which returns the merged view for vanilla double chests
     *  (NeoForge's `CapabilityHooks` wraps `ChestBlock.combine` so the same
     *  combined handler is returned from every face) and the controller's
     *  aggregate inventory for Sophisticated Storage. The earlier BE-Container
     *  fast-path missed both cases because it walked only the BE at [pos],
     *  which is half a double chest. */
    fun findAllItemInfoAt(
        level: ServerLevel,
        pos: BlockPos,
        card: CardSnapshot,
        filter: (String) -> Boolean,
    ): List<ItemInfo> {
        val storage = getStorage(level, card) ?: return emptyList()
        return PlatformServices.storage.findAllItemInfo(storage, filter)
    }

    /** Count items at [pos] matching [filter] via [card]'s capability-resolved
     *  storage handle. See [findAllItemInfoAt] for the double-chest /
     *  Sophisticated Storage rationale. */
    fun countItemsAt(level: ServerLevel, pos: BlockPos, card: CardSnapshot, filter: String): Long {
        val storage = getStorage(level, card) ?: return 0L
        return PlatformServices.storage.countItems(storage) { CardHandle.matchesFilter(it, ResourceKind.ITEM, filter) }
    }

    fun countFluid(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        var total = 0L
        val visited = HashSet<BlockPos>()
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
            val cap = card.capability as? StorageSideCapability ?: continue
            // Same dedup reasoning as [countItems]. No vanilla equivalent of
            // [Container] for fluids, so we accept the face-restricted view.
            if (!visited.add(cap.adjacentPos)) continue
            val storage = getFluidStorage(level, card) ?: continue
            total += PlatformServices.storage.countFluid(storage) { CardHandle.matchesFilter(it, ResourceKind.FLUID, filter) }
        }
        return total
    }

    /**
     * Count items + fluids matching [filter] across the network.
     * A filter like `item:*` skips fluids, `fluid:*` skips items, bare filters sum both.
     */
    fun countResource(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        val (kindGate, _) = CardHandle.parseFilterKind(filter)
        var total = 0L
        if (kindGate == null || kindGate == ResourceKind.ITEM) total += countItems(level, snapshot, filter, channel)
        if (kindGate == null || kindGate == ResourceKind.FLUID) total += countFluid(level, snapshot, filter, channel)
        return total
    }

    fun findFirstFluidInfoAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): Pair<FluidInfo, CardSnapshot>? {
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
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
        cache: NetworkInventoryCache? = null,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        if (amount <= 0L) return 0L
        var remaining = amount
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0L) break
            if (!channel.matches(card.channel)) continue
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
        cache: NetworkInventoryCache? = null,
        channel: ChannelFilter = ChannelFilter.All,
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
        val storageCards = getStorageCards(snapshot).filter { channel.matches(it.channel) }
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
        val moved = insertItems(level, snapshot, source, filter, requested, routeTable, null, cache, channel)
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
                if (info != null) cache?.onExtracted(info.itemId, info.hasData, returned, info.componentsPatch)
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
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): List<Pair<FluidInfo, CardSnapshot>> {
        val aggregated = LinkedHashMap<String, Pair<FluidInfo, CardSnapshot>>()
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
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
    fun findFirstItemInfoAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): Pair<ItemInfo, CardSnapshot>? {
        val variantPatch = parsedVariantPatch(filter)
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
            val storage = getStorage(level, card) ?: continue
            val info = PlatformServices.storage.findFirstItemInfo(storage) {
                CardHandle.matchesFilter(it, filter)
            } ?: continue
            // When the filter narrows to a specific variant, require the
            // returned info's components hash to match. The itemId-only
            // predicate above lets through every variant of the item, this
            // second check narrows to the requested one.
            if (variantPatch != null) {
                val infoHash = damien.nodeworks.script.BufferKey.componentsHash(info.componentsPatch)
                val wantHash = damien.nodeworks.script.BufferKey.componentsHash(variantPatch)
                if (infoHash != wantHash) continue
            }
            return Pair(info, card)
        }
        return null
    }

    private fun parsedVariantPatch(filter: String): net.minecraft.core.component.DataComponentPatch? =
        damien.nodeworks.script.CardHandle.parsedVariantPatchForFind(filter)

    /** Like [parsedVariantPatch] but also extracts the itemId for the variant
     *  dispatch. Returns null when the filter isn't `Item(_, componentsPatch != null)`. */
    private fun parsedVariantInfo(filter: String): Pair<String, net.minecraft.core.component.DataComponentPatch>? {
        if (!filter.contains('[')) return null
        val inner = when {
            filter.startsWith("\$item:") -> filter.removePrefix("\$item:")
            filter.startsWith("\$fluid:") -> return null
            else -> filter
        }
        val registries = damien.nodeworks.script.CardHandle.parsedRuleRegistries() ?: return null
        val rule = FilterRule.parse(inner, registries) as? FilterRule.Item ?: return null
        val patch = rule.componentsPatch ?: return null
        return rule.itemId to patch
    }

    /** Find all unique item types across all Storage Cards matching the filter, with their source cards.
     *  Dedups by component-aware [BufferKey.Key] so component-bearing variants
     *  (different potions, dyed armor, enchanted books) each return as a
     *  distinct entry. The previous `"$itemId:$hasData"` boolean key collapsed
     *  every variant of a component-bearing item into one bucket which made
     *  `network:findEach("minecraft:potion")` return a single arbitrary potion. */
    fun findAllItemInfoAcrossNetwork(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        filter: String,
        channel: ChannelFilter = ChannelFilter.All,
    ): List<Pair<ItemInfo, CardSnapshot>> {
        val results = mutableListOf<Pair<ItemInfo, CardSnapshot>>()
        val seen = mutableSetOf<damien.nodeworks.script.BufferKey.Key>()
        val variantPatch = parsedVariantPatch(filter)
        val wantHash = variantPatch?.let { damien.nodeworks.script.BufferKey.componentsHash(it) }
        for (card in getStorageCards(snapshot)) {
            if (!channel.matches(card.channel)) continue
            val storage = getStorage(level, card) ?: continue
            val items = PlatformServices.storage.findAllItemInfo(storage) { CardHandle.matchesFilter(it, filter) }
            for (info in items) {
                if (wantHash != null && damien.nodeworks.script.BufferKey.componentsHash(info.componentsPatch) != wantHash) continue
                val key = damien.nodeworks.script.BufferKey.Key(
                    info.itemId,
                    damien.nodeworks.script.BufferKey.componentsHash(info.componentsPatch),
                )
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
    fun insertItemStack(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        stack: net.minecraft.world.item.ItemStack,
        cache: NetworkInventoryCache? = null,
        channel: ChannelFilter = ChannelFilter.All,
    ): Int {
        var remaining = stack.count
        val itemId = damien.nodeworks.platform.ItemIdCache.get(stack.item)
        // ItemStack carries its own NBT-presence state, so the filter check
        // can use the actual `hasData` rather than guessing.
        val hasData = !stack.componentsPatch.isEmpty
        val registries = level.registryAccess()
        for (card in getStorageCards(snapshot)) {
            if (remaining <= 0) break
            if (!channel.matches(card.channel)) continue
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
            if (cap != null && !cap.acceptsItem(stack, registries)) continue
            val storage = getStorage(level, card) ?: continue
            val inserted = PlatformServices.storage.insertItemStack(storage, stack.copyWithCount(remaining))
            remaining -= inserted
        }
        val totalInserted = stack.count - remaining
        if (totalInserted > 0 && cache != null) {
            if (itemId != null) {
                cache.onInserted(itemId, hasData, totalInserted.toLong(), stack.componentsPatch)
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
        cache: NetworkInventoryCache? = null,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        if (routeTable == null && onInsertCallback == null) {
            // No routing, fast path, use all storages
            return insertItemsDefault(level, snapshot, source, filter, maxCount, cache, channel)
        }
        return insertItemsRouted(level, snapshot, source, filter, maxCount, routeTable, onInsertCallback, cache, channel)
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
        cache: NetworkInventoryCache? = null,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount

        // Keyed by full variant identity so two potion variants of one itemId
        // are processed independently.
        val processedVariants = mutableSetOf<damien.nodeworks.script.BufferKey.Key>()

        while (remaining > 0) {
            val itemInfo = PlatformServices.storage.findFirstItemInfo(source) {
                CardHandle.matchesFilter(it, filter)
            }?.takeIf {
                damien.nodeworks.script.BufferKey.Key(
                    it.itemId, damien.nodeworks.script.BufferKey.componentsHash(it.componentsPatch),
                ) !in processedVariants
            } ?: run {
                // findFirstItemInfo only returns the first match; scan for the
                // next unprocessed variant once that one's done.
                PlatformServices.storage.findAllItemInfo(source) { CardHandle.matchesFilter(it, filter) }
                    .firstOrNull {
                        damien.nodeworks.script.BufferKey.Key(
                            it.itemId, damien.nodeworks.script.BufferKey.componentsHash(it.componentsPatch),
                        ) !in processedVariants
                    }
            } ?: break
            val itemId = itemInfo.itemId
            val hasData = itemInfo.hasData
            val componentsPatch = itemInfo.componentsPatch
            val wantHash = damien.nodeworks.script.BufferKey.componentsHash(componentsPatch)
            val variantKey = damien.nodeworks.script.BufferKey.Key(itemId, wantHash)

            processedVariants.add(variantKey)

            // Matches only this exact variant.
            val variantPred: (net.minecraft.world.item.ItemStack) -> Boolean = { stack ->
                val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == wantHash
            }
            val count = PlatformServices.storage.countStacksByPredicate(source, variantPred)
            val toMove = minOf(remaining, count)
            if (toMove <= 0L) continue

            // 1. Check routes first (precomputed, fast). A route may have multiple candidate
            //    cards (wildcard pattern like `cobblestone_*`), iterate them in order,
            //    moving what fits before overflowing to open storages.
            val routeTargets = routeTable?.findRouteTargets(itemInfo) ?: emptyList()
            if (routeTargets.isNotEmpty()) {
                var routeRemaining = toMove
                val registries = level.registryAccess()
                for ((targetCard, target) in routeTargets) {
                    if (routeRemaining <= 0L) break
                    // Per-card filter gate. A route may point at a card whose
                    // configured rules reject this item (misconfiguration, but
                    // valid). Skip those and let overflow fall through to
                    // open storages. Passes the item's components patch so
                    // `[component]` rules narrow to the specific variant.
                    val cap = targetCard.capability as? damien.nodeworks.card.StorageSideCapability
                    if (cap != null && !cap.acceptsItem(itemId, componentsPatch, registries)) continue
                    val moved = try {
                        PlatformServices.storage.moveItemsByStackPredicate(source, target, variantPred, routeRemaining)
                    } catch (_: Exception) { 0L }
                    if (moved > 0) cache?.onInserted(itemId, hasData, moved, componentsPatch)
                    totalMoved += moved
                    remaining -= moved
                    routeRemaining -= moved
                }
                if (routeRemaining > 0L && routeTable != null) {
                    val overflow = routeTable.insertDefault(source, itemId, routeRemaining)
                    if (overflow > 0) cache?.onInserted(itemId, hasData, overflow, componentsPatch)
                    totalMoved += overflow
                    remaining -= overflow
                }
                continue
            }

            // 2. Check onInsert callback
            val callbackTarget = onInsertCallback?.invoke(itemId, toMove)
            if (callbackTarget != null) {
                val moved = try {
                    PlatformServices.storage.moveItemsByStackPredicate(source, callbackTarget, variantPred, toMove)
                } catch (_: Exception) { 0L }
                if (moved > 0) cache?.onInserted(itemId, hasData, moved, componentsPatch)
                totalMoved += moved
                remaining -= moved
                if (moved < toMove) {
                    // Callback target full, fall to open storages or all storages
                    val fallbackMoved = if (routeTable != null) {
                        routeTable.insertDefault(source, itemId, toMove - moved)
                    } else {
                        insertItemsDefault(level, snapshot, source, itemId, toMove - moved, cache, channel)
                    }
                    if (fallbackMoved > 0) cache?.onInserted(itemId, hasData, fallbackMoved, componentsPatch)
                    totalMoved += fallbackMoved
                    remaining -= fallbackMoved
                }
                continue
            }

            // 3. No route or callback match, use open storages (or all if no routes)
            val defaultMoved = if (routeTable != null) {
                routeTable.insertDefault(source, itemId, toMove)
            } else {
                insertItemsDefault(level, snapshot, source, itemId, toMove, cache, channel)
            }
            if (defaultMoved > 0) cache?.onInserted(itemId, hasData, defaultMoved, componentsPatch)
            totalMoved += defaultMoved
            remaining -= defaultMoved
        }

        return totalMoved
    }

    /** Default priority-based routing across ALL storage cards (no route filtering).
     *  Enumerates source variants so each card's component-aware acceptance
     *  rule is applied per variant, not collapsed by itemId. */
    private fun insertItemsDefault(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        source: ItemStorageHandle,
        filter: String,
        maxCount: Long,
        cache: NetworkInventoryCache? = null,
        channel: ChannelFilter = ChannelFilter.All,
    ): Long {
        var totalMoved = 0L
        var remaining = maxCount
        val registries = level.registryAccess()
        val variants = PlatformServices.storage.findAllItemInfo(source) {
            CardHandle.matchesFilter(it, filter)
        }
        for (info in variants) {
            if (remaining <= 0L) break
            val itemId = info.itemId
            val componentsPatch = info.componentsPatch
            val wantHash = damien.nodeworks.script.BufferKey.componentsHash(componentsPatch)
            // Matches only this exact variant.
            val variantPred: (net.minecraft.world.item.ItemStack) -> Boolean = { stack ->
                val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == wantHash
            }
            for (card in getStorageCards(snapshot)) {
                if (remaining <= 0L) break
                if (!channel.matches(card.channel)) continue
                val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                if (cap != null && !cap.acceptsItem(itemId, componentsPatch, registries)) continue
                val destStorage = getStorage(level, card) ?: continue
                val moved = try {
                    PlatformServices.storage.moveItemsByStackPredicate(source, destStorage, variantPred, remaining)
                } catch (_: Exception) { 0L }
                totalMoved += moved
                remaining -= moved
            }
        }
        // Cache notification handled by callers in [insertItemsRouted] so we
        // don't double-count here. [insertItems]'s direct default path passes
        // cache=null since it isn't tracking per-variant inserts at that level.
        return totalMoved
    }
}
