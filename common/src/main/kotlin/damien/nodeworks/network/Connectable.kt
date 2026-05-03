package damien.nodeworks.network

import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * Interface for block entities that can participate in node network connections.
 * Implemented by NodeBlockEntity and other network-connectable block entities.
 * Implementors must also be BlockEntity subclasses (which provide blockPos via getBlockPos()).
 */
interface Connectable {
    fun getBlockPos(): BlockPos

    fun getConnections(): Collection<BlockPos>
    fun addConnection(pos: BlockPos): Boolean
    fun removeConnection(pos: BlockPos): Boolean
    fun hasConnection(pos: BlockPos): Boolean

    /** The network UUID this block belongs to. Null if not yet connected to a controller. */
    var networkId: UUID?

    var blockDestroyed: Boolean

    /** Whether this block joins the network through face-adjacency. Full-block
     *  Connectables return true. Nodes opt out because they're a small fixture
     *  inside the block and the player can't see them touching a neighbour, so
     *  reaching the network through `Node next to Controller` would be invisible. */
    fun usesAdjacency(): Boolean = true

    /** Whether placing this block in the LOS path of an existing laser auto-rewires
     *  the connection through it (A↔B becomes A↔this↔B). Default false. Nodes opt
     *  in so a player can extend a network by dropping a Node onto an existing
     *  laser without having to wrench it manually. */
    fun autoSpliceOnPlace(): Boolean = false

    /** Render colour resolved from [networkId]. Null id (no controller, or a
     *  multi-controller conflict) renders grey. Trusts the cached BE state, no
     *  BFS fallback, propagate is the sole arbiter of membership. */
    fun networkColor(): Int {
        val id = networkId ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return NetworkSettingsRegistry.getColor(id)
    }
}
