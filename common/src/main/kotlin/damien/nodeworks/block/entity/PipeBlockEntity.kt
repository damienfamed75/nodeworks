package damien.nodeworks.block.entity

import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getStringOrNull
import java.util.UUID

/**
 * Block entity for the [damien.nodeworks.block.PipeBlock]. The Pipe is the
 * connectivity backbone of the network, every face-adjacent Connectable BE
 * (Pipe / Node / Controller / Terminal / antennas / chests) joins the same
 * network. The BE itself is intentionally tiny, it just stores the network
 * id so [NodeConnectionHelper.propagateNetworkId] has somewhere to write
 * the propagated value.
 *
 * No `connections` set, no NBT for adjacency state, no card slots. Pipes
 * exist purely to extend the adjacency graph.
 */
class PipeBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.PIPE, pos, state), Connectable {

    override var networkId: UUID? = null
    override var blockDestroyed: Boolean = false

    /** Per-face wrench-block flags. Bit i = forced-blocked on Direction.entries[i].
     *  Persisted as a single byte in NBT. Read/written via [forcedPipeBlocked]
     *  and [toggleForcedPipeBlock]. */
    private var forcedPipeBlockedMask: Int = 0

    // No-op connection set, the interface contract requires the methods but
    // pipes connect purely via face-adjacency.
    override fun getConnections(): List<BlockPos> = emptyList()
    override fun hasConnection(pos: BlockPos): Boolean = false
    override fun addConnection(pos: BlockPos): Boolean = false
    override fun removeConnection(pos: BlockPos): Boolean = false

    override fun forcedPipeBlocked(side: Direction): Boolean =
        (forcedPipeBlockedMask shr side.ordinal) and 1 != 0

    override fun toggleForcedPipeBlock(side: Direction) {
        forcedPipeBlockedMask = forcedPipeBlockedMask xor (1 shl side.ordinal)
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
    }

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
        }
    }

    override fun setRemoved() {
        val lvl = level
        if (lvl is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
        }
        super.setRemoved()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        networkId?.let { output.putString("networkId", it.toString()) }
        if (forcedPipeBlockedMask != 0) output.putInt("forcedPipeBlocked", forcedPipeBlockedMask)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        forcedPipeBlockedMask = input.getIntOr("forcedPipeBlocked", 0) and 0x3F
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    /** Pipes are colourless on purpose, the network identity lives on named
     *  devices (Controller / Terminal / antennas). Returns the default grey
     *  so any code that reads [networkColor] gets a stable fallback. */
    override fun networkColor(): Int = damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
}
