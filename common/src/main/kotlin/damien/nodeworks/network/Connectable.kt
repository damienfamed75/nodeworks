package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID

/**
 * Interface for block entities that can participate in network connections.
 * Implementors must also extend BlockEntity (for `getBlockPos`).
 */
interface Connectable {
    fun getBlockPos(): BlockPos

    fun getConnections(): Collection<BlockPos>
    fun addConnection(pos: BlockPos): Boolean
    fun removeConnection(pos: BlockPos): Boolean
    fun hasConnection(pos: BlockPos): Boolean

    /** Network UUID this block belongs to. Null until propagate finds a controller. */
    var networkId: UUID?

    var blockDestroyed: Boolean

    /** Whether this block joins the network through face-adjacency. */
    fun usesAdjacency(): Boolean = true

    /** Whether this block accepts a face-adjacency connection to [other].
     *  The helper rejects the pair when either side returns false. Network
     *  leaves (import/export chests) override to refuse other leaves so two
     *  chests placed face-to-face don't auto-bridge networks. */
    fun canConnectAdjacentTo(other: Connectable): Boolean = true

    /** Whether the player has wrench-blocked the connection on [side].
     *  Pipe and Node BEs override with persistent state, every other
     *  Connectable defers to the default since the wrench's force-block
     *  flow only touches Pipe/Node faces. */
    fun forcedPipeBlocked(@Suppress("UNUSED_PARAMETER") side: Direction): Boolean = false

    /** Flip the force-block on [side]. Default no-op. */
    fun toggleForcedPipeBlock(@Suppress("UNUSED_PARAMETER") side: Direction) {}

    /** Client-only. References [damien.nodeworks.render.NodeConnectionRenderer]
     *  for the default colour fallback. Server code should read [networkId]
     *  directly and look up the colour via [NetworkSettingsRegistry]. */
    fun networkColor(): Int {
        val id = networkId ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return NetworkSettingsRegistry.getColor(id)
    }
}
