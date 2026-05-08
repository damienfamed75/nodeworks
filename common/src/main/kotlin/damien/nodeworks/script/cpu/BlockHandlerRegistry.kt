package damien.nodeworks.script.cpu

import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * Server-side index of Processing Handler blocks bound to recipes, keyed by
 * `(parent networkId, processing api name)`. Acts as the block-side mirror of
 * [damien.nodeworks.script.ScriptEngine.processingHandlers]: when the CPU
 * dispatches a recipe and the Lua side has no handler registered, the executor
 * falls back to this registry.
 *
 * Entries are in-memory only - rebuilt on chunk load via the BE's lifecycle
 * hooks. No NBT persistence: a Handler's binding state lives on the BE itself
 * (`processingApiName`), the registry is just a fast lookup index.
 *
 * **Lua wins on collision.** [damien.nodeworks.screen.ProcessingHandlerServerLogic.isSetClaimedByOther]
 * already refuses block binds when a Lua handler is registered for the same
 * api on the parent network. The executor independently prefers Lua too, so
 * even if the registries disagree the player-facing behavior is consistent.
 */
object BlockHandlerRegistry {

    /** networkId -> (apiName -> handler position). The outer map is sparse;
     *  only networks that actually host a bound Handler get an entry. */
    private val byNetwork: MutableMap<UUID, MutableMap<String, BlockPos>> = HashMap()

    /** Register or update a Handler at [handlerPos] on [networkId] bound to
     *  [apiName]. If another entry already exists for the same key it is
     *  replaced - the BE's bind logic + claim coordination is the gate that
     *  decides whether a duplicate bind should have been allowed at all. */
    @Synchronized
    fun register(networkId: UUID, apiName: String, handlerPos: BlockPos) {
        if (apiName.isEmpty()) return
        byNetwork.getOrPut(networkId) { HashMap() }[apiName] = handlerPos
    }

    /** Drop the entry for [networkId] / [apiName]. Cleans up the inner map
     *  when it goes empty so an unloaded network's UUID doesn't sit there
     *  forever. */
    @Synchronized
    fun unregister(networkId: UUID, apiName: String) {
        val inner = byNetwork[networkId] ?: return
        inner.remove(apiName)
        if (inner.isEmpty()) byNetwork.remove(networkId)
    }

    /** Drop every entry pointing at [handlerPos], regardless of network. Used
     *  when a Handler is destroyed (we can't always look up its old networkId
     *  if the BE is mid-removal). O(n) over registered handlers but n is the
     *  count of bound block handlers across all networks - tiny in practice. */
    @Synchronized
    fun unregisterByPos(handlerPos: BlockPos) {
        val emptied = mutableListOf<UUID>()
        for ((netId, inner) in byNetwork) {
            inner.entries.removeIf { it.value == handlerPos }
            if (inner.isEmpty()) emptied += netId
        }
        for (netId in emptied) byNetwork.remove(netId)
    }

    /** Resolve the Handler bound to [apiName] on [networkId], or null when
     *  no Handler claims that recipe on that network. */
    @Synchronized
    fun find(networkId: UUID?, apiName: String): BlockPos? {
        if (networkId == null) return null
        return byNetwork[networkId]?.get(apiName)
    }

    /** Snapshot of every (apiName -> pos) entry on [networkId]. Returns an
     *  empty map when the network has no block handlers. Copy is intentional;
     *  callers can iterate without holding the registry lock. */
    @Synchronized
    fun handlersOnNetwork(networkId: UUID?): Map<String, BlockPos> {
        if (networkId == null) return emptyMap()
        return byNetwork[networkId]?.toMap() ?: emptyMap()
    }

    /** Wipe the whole registry. Called on server stop so a re-login starts
     *  fresh and stale entries from a previous session don't linger. */
    @Synchronized
    fun reset() {
        byNetwork.clear()
    }

    /**
     * Reconcile the registry with [handler]'s current `(networkId, processingApiName)`.
     * Idempotent: drops any stale entry pointing at this Handler's position, then
     * re-registers if the BE is currently bound and on a network.
     *
     * Single source of truth for "is this handler registered" - called from every
     * lifecycle hook (setLevel, bind, unbind, propagateNetworkId for PHandlers)
     * so the registry can't drift from BE state regardless of which hook fires
     * in what order on world load.
     */
    @Synchronized
    fun syncFromBE(handler: damien.nodeworks.block.entity.ProcessingHandlerBlockEntity) {
        val pos = handler.blockPos
        unregisterByPos(pos)
        val name = handler.processingApiName
        if (name.isEmpty()) return
        val netId = handler.networkId ?: return
        register(netId, name, pos)
    }
}
