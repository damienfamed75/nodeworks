package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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

    /** Whether this block accepts a face-adjacency connection to [other]. Default
     *  true. Network leaves (e.g. import/export chests) override to reject other
     *  leaves so two chests placed face-to-face don't auto-bridge networks
     *  through each other, the player has to wire them with cable explicitly.
     *  The helper rejects the pair when *either* side returns false. */
    fun canConnectAdjacentTo(other: Connectable): Boolean = true

    /** Whether the player has wrench-blocked the connection on [side]. Default
     *  false (no per-face block). Pipe and Node BEs override with persistent
     *  bit-packed state, every other Connectable defers to the default since
     *  the user-facing wrench flow only touches Pipe/Node faces. The adjacency
     *  walk and the model `pipe_*` flag computation reject the pair when either
     *  side reports the touching face blocked. */
    fun forcedPipeBlocked(@Suppress("UNUSED_PARAMETER") side: Direction): Boolean = false

    /** Toggle the force-block on [side]. Default no-op. Pipe and Node override
     *  to flip the bit, mark the BE dirty, and emit a block update so the
     *  multipart blockstate re-evaluates. Wrench calls this on the BE the
     *  player clicked, the neighbour's blockstate is rebuilt separately so
     *  both sides drop their pipe stub. */
    fun toggleForcedPipeBlock(@Suppress("UNUSED_PARAMETER") side: Direction) {}

    /** Render colour resolved from [networkId]. Null id (no controller, or a
     *  multi-controller conflict) renders grey. Trusts the cached BE state, no
     *  BFS fallback, propagate is the sole arbiter of membership. */
    fun networkColor(): Int {
        val id = networkId ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return NetworkSettingsRegistry.getColor(id)
    }
}
