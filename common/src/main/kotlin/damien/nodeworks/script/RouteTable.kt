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
    data class Route(val alias: String, val predicate: LuaFunction, val card: CardSnapshot)

    private val routes = mutableListOf<Route>()
    private val routedAliases = mutableSetOf<String>()

    // Cache: "itemId:hasData" → winning route alias (or null for no match)
    private val routeCache = HashMap<String, String?>()

    // Precomputed: storage cards NOT claimed by any route
    private var openStorageCards: List<CardSnapshot> = NetworkStorageHelper.getStorageCards(snapshot)

    /** Add a route. Items matching the predicate go to the storage with the given alias. */
    fun addRoute(alias: String, predicate: LuaFunction) {
        val card = snapshot.findByAlias(alias) ?: return
        routes.add(Route(alias, predicate, card))
        routedAliases.add(alias)
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
     * Find the target storage for an item based on routes.
     * Returns the ItemStorageHandle if a route matches, null if no route matches.
     */
    fun findRouteTarget(itemInfo: ItemInfo): ItemStorageHandle? {
        val cacheKey = "${itemInfo.itemId}:${itemInfo.hasData}"

        // Check cache first
        if (routeCache.containsKey(cacheKey)) {
            val cachedAlias = routeCache[cacheKey] ?: return null
            val route = routes.find { it.alias == cachedAlias } ?: return null
            return NetworkStorageHelper.getStorage(level, route.card)
        }

        // Create an ItemsHandle to pass to predicates
        val itemsHandle = ItemsHandle.forCraftResult(
            itemId = itemInfo.itemId,
            itemName = itemInfo.name,
            count = itemInfo.count.toInt(),
            sourceStorage = { null },
            level = level
        )
        val itemsTable = ItemsHandle.toLuaTable(itemsHandle)

        // Evaluate each route's predicate
        for (route in routes) {
            val result = route.predicate.call(itemsTable)
            if (result.toboolean()) {
                routeCache[cacheKey] = route.alias
                return NetworkStorageHelper.getStorage(level, route.card)
            }
        }

        // No match
        routeCache[cacheKey] = null
        return null
    }

    /**
     * Insert items using default priority routing, but ONLY into open (unrouted) storages.
     */
    fun insertDefault(source: ItemStorageHandle, filter: String, maxCount: Long): Long {
        var totalMoved = 0L
        var remaining = maxCount
        for (card in openStorageCards) {
            if (remaining <= 0) break
            val destStorage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val moved = try {
                PlatformServices.storage.moveItems(
                    source, destStorage,
                    { CardHandle.matchesFilter(it, filter) },
                    remaining
                )
            } catch (_: Exception) { 0L }
            totalMoved += moved
            remaining -= moved
        }
        return totalMoved
    }

    /**
     * Insert an ItemStack using default routing into open storages only.
     */
    fun insertItemStackDefault(stack: net.minecraft.world.item.ItemStack): Int {
        var remaining = stack.count
        for (card in openStorageCards) {
            if (remaining <= 0) break
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val inserted = PlatformServices.storage.insertItemStack(storage, stack.copyWithCount(remaining))
            remaining -= inserted
        }
        return stack.count - remaining
    }
}
