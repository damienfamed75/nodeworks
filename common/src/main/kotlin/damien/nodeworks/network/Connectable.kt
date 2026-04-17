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
}
