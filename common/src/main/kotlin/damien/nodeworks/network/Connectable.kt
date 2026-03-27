package damien.nodeworks.network

import net.minecraft.core.BlockPos

/**
 * Interface for block entities that can participate in node network connections.
 * Implemented by NodeBlockEntity and InstructionCrafterBlockEntity.
 * Implementors must also be BlockEntity subclasses (which provide blockPos via getBlockPos()).
 */
interface Connectable {
    fun getBlockPos(): BlockPos

    fun getConnections(): Collection<BlockPos>
    fun addConnection(pos: BlockPos): Boolean
    fun removeConnection(pos: BlockPos): Boolean
    fun hasConnection(pos: BlockPos): Boolean

    var blockDestroyed: Boolean
}
