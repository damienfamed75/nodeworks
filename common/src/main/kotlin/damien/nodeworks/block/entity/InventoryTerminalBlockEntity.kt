package damien.nodeworks.block.entity

import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Block entity for the Inventory Terminal. Connectable to the network via lasers.
 */
class InventoryTerminalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.INVENTORY_TERMINAL, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null
    var layoutIndex: Int = 0

    // --- Connectable ---

    override fun getBlockPos(): BlockPos = worldPosition
    override fun getConnections(): Set<BlockPos> = connections.toSet()

    override fun addConnection(pos: BlockPos): Boolean {
        val added = connections.add(pos)
        if (added) {
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
        }
        return added
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        val removed = connections.remove(pos)
        if (removed) {
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
        }
        return removed
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        networkId?.let { tag.putString("networkId", it.toString()) }
        tag.putInt("layoutIndex", layoutIndex)
        if (connections.isNotEmpty()) {
            tag.putLongArray("connections", connections.map { it.asLong() }.toLongArray())
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        layoutIndex = if (tag.contains("layoutIndex")) tag.getInt("layoutIndex") else 0
        networkId = tag.getString("networkId").takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        connections.clear()
        if (tag.contains("connections")) {
            tag.getLongArray("connections").forEach { connections.add(BlockPos.of(it)) }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
