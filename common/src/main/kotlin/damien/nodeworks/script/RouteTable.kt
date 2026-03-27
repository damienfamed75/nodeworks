package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel

/**
 * Precomputed routing table for network:route().
 * Maintains two lists:
 * - routedStorages: filter → (alias, CardSnapshot) pairs for directed routing
 * - openStorages: storage cards with no route restrictions (for default fallback)
 *
 * When a route is added, the target storage is moved from openStorages to routedStorages.
 * This avoids per-item exclusion checks during insertion.
 */
class RouteTable(
    private val level: ServerLevel,
    private val snapshot: NetworkSnapshot
) {
    data class Route(val filter: String, val alias: String, val card: CardSnapshot)

    private val routes = mutableListOf<Route>()
    private val routedAliases = mutableSetOf<String>()

    // Precomputed: storage cards NOT claimed by any route
    private var openStorageCards: List<CardSnapshot> = NetworkStorageHelper.getStorageCards(snapshot)

    /** Add a route. Items matching filter go to the storage with the given alias. */
    fun addRoute(filter: String, alias: String) {
        val card = snapshot.findByAlias(alias) ?: return
        routes.add(Route(filter, alias, card))
        routedAliases.add(alias)
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
    fun findRouteTarget(itemId: String): ItemStorageHandle? {
        for (route in routes) {
            if (CardHandle.matchesFilter(itemId, route.filter)) {
                return NetworkStorageHelper.getStorage(level, route.card)
            }
        }
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
