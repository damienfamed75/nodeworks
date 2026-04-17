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
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
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

    // TODO MC 26.1.2 NBT MIGRATION: rewrite against ValueOutput. See git history for pre-migration body.
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
    }

    // TODO MC 26.1.2 NBT MIGRATION: rewrite against ValueInput. See git history for pre-migration body.
    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
