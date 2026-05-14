package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaFunction

/**
 * Precomputed routing table for network:route().
 * Maintains two lists:
 * - routes: predicate-based routes with alias → CardSnapshot pairs for directed routing
 * - openStorages: storage cards with no route restrictions (for default fallback)
 *
 * When a route is added, the target storage is moved from openStorages to routedStorages.
 * Results are cached by item key to avoid re-evaluating predicates.
 */
class RouteTable(
    private val level: ServerLevel,
    private val snapshot: NetworkSnapshot
) {
    /**
     * A single route. [cards] is a list because a pattern like `"cobblestone_*"` expands to
     * every alias matching the wildcard, items that match [predicate] spread across that set
     * of cards (highest-priority first, then insertion order).
     */
    data class Route(val pattern: String, val predicate: LuaFunction, val cards: List<CardSnapshot>)

    private val routes = mutableListOf<Route>()
    private val routedAliases = mutableSetOf<String>()

    // Cache: "itemId:hasData" → winning route pattern (or null for no match). We cache by
    // pattern rather than by card because wildcard routes fan out to multiple cards and
    // we need to resolve the live card list at commit time (in case membership shifted).
    private val routeCache = HashMap<String, String?>()

    // Precomputed: storage cards NOT claimed by any route.
    private var openStorageCards: List<CardSnapshot> = NetworkStorageHelper.getStorageCards(snapshot)

    /**
     * Add a route. [pattern] may contain `*` wildcards (e.g. `"cobblestone_*"`) to match any
     * sequence of characters in an alias, the resulting route claims every currently-known
     * storage card whose alias matches the pattern. Non-wildcard patterns resolve to a single
     * card by exact alias.
     */
    fun addRoute(pattern: String, predicate: LuaFunction) {
        val cards = if (pattern.contains('*')) {
            val regex = wildcardToRegex(pattern)
            snapshot.allCards()
                .filter { it.capability.type == "storage" && regex.matches(it.effectiveAlias) }
        } else {
            listOfNotNull(snapshot.findByAlias(pattern))
        }
        if (cards.isEmpty()) return
        routes.add(Route(pattern, predicate, cards))
        routedAliases.addAll(cards.map { it.effectiveAlias })
        routeCache.clear()
        recomputeOpenStorages()
    }

    /** Recompute the open storages list (storages not claimed by any route). */
    private fun recomputeOpenStorages() {
        openStorageCards = NetworkStorageHelper.getStorageCards(snapshot)
            .filter { it.effectiveAlias !in routedAliases }
    }

    fun hasRoutes(): Boolean = routes.isNotEmpty()

    /**
     * Find the candidate target storages for an item based on routes. Returns an in-priority
     * ordered list of handles for the first route whose predicate matches, or an empty list
     * when no route applies. Callers insert into the list in order, overflowing to open
     * storages only after every candidate is full.
     */
    fun findRouteTargets(itemInfo: ItemInfo): List<Pair<CardSnapshot, ItemStorageHandle>> {
        val cacheKey = "${itemInfo.itemId}:${itemInfo.hasData}"

        val matchingRoute: Route? = if (routeCache.containsKey(cacheKey)) {
            val cachedPattern = routeCache[cacheKey] ?: return emptyList()
            routes.find { it.pattern == cachedPattern }
        } else {
            val itemsHandle = ItemsHandle.fromItemInfo(
                info = itemInfo,
                filter = itemInfo.itemId,
                sourceStorage = { null },
                level = level
            )
            val itemsTable = ItemsHandle.toLuaTable(itemsHandle)
            val hit = routes.firstOrNull { it.predicate.call(itemsTable).toboolean() }
            routeCache[cacheKey] = hit?.pattern
            hit
        }

        if (matchingRoute == null) return emptyList()
        // Pairs so the caller can gate on the card's per-card filter
        // ([damien.nodeworks.card.StorageSideCapability.acceptsItem]) before
        // moving items into the underlying storage. Without the CardSnapshot
        // carried alongside the handle, the caller would have no way to consult
        // the route target's filter rules.
        return matchingRoute.cards.mapNotNull { card ->
            NetworkStorageHelper.getStorage(level, card)?.let { storage -> card to storage }
        }
    }

    /**
     * Convert a glob-style pattern (with `*` meaning "any sequence") into a regex anchored
     * to the full alias. Every other character is escaped literally, so an alias that
     * genuinely contains special regex metachars (e.g. a parenthesis) still matches only
     * its literal form.
     */
    private fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            if (ch == '*') sb.append(".*") else sb.append(Regex.escape(ch.toString()))
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    /**
     * Insert items using default priority routing, but ONLY into open (unrouted) storages.
     * Enumerates source variants and moves each per-variant so a card's
     * filter rules apply to the right variant.
     */
    fun insertDefault(source: ItemStorageHandle, filter: String, maxCount: Long): Long {
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
            for (card in openStorageCards) {
                if (remaining <= 0L) break
                val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                if (cap != null && !cap.acceptsItem(itemId, componentsPatch, registries)) continue
                val destStorage = NetworkStorageHelper.getStorage(level, card) ?: continue
                val moved = try {
                    PlatformServices.storage.moveItemsByStackPredicate(source, destStorage, variantPred, remaining)
                } catch (_: Exception) { 0L }
                totalMoved += moved
                remaining -= moved
            }
        }
        return totalMoved
    }

    /**
     * Insert an ItemStack using default routing into open storages only.
     */
    fun insertItemStackDefault(stack: net.minecraft.world.item.ItemStack): Int {
        var remaining = stack.count
        val registries = level.registryAccess()
        for (card in openStorageCards) {
            if (remaining <= 0) break
            // Same per-card filter gate as [insertDefault], the source here is
            // a single concrete ItemStack so the full component-aware check
            // runs and `[component]` rules narrow to the variant.
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
            if (cap != null && !cap.acceptsItem(stack, registries)) continue
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val inserted = PlatformServices.storage.insertItemStack(storage, stack.copyWithCount(remaining))
            remaining -= inserted
        }
        return stack.count - remaining
    }
}
