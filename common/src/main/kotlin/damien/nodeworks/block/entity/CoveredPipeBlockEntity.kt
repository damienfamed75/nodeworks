package damien.nodeworks.block.entity

import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * Block entity for the Covered Vacuum Pipe - the camouflaged full-block
 * variant of the Pipe. Behaves identically to a [PipeBlockEntity] for
 * network connectivity (adjacency BFS via [Connectable], no card slots, no
 * persistent connection set), but additionally stores the [BlockState] of
 * the wrapped "camo" block so the client renderer can mirror its
 * appearance via [net.minecraft.client.renderer.block.BlockModelRenderState].
 *
 * The camo state is set by [damien.nodeworks.item.CoveredPipeBlockItem]
 * on placement (reading the `CAMO_BLOCK_STATE` data component off the
 * item) and persists through NBT save/load + client sync.
 */
class CoveredPipeBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.COVERED_PIPE, pos, state), Connectable {

    override var networkId: UUID? = null
    override var blockDestroyed: Boolean = false

    /** Per-face wrench-block flags, bit-packed identically to
     *  [PipeBlockEntity.forcedPipeBlockedMask] so the wrench can split
     *  networks on Covered Pipe faces just like regular Pipes. */
    private var forcedPipeBlockedMask: Int = 0

    /** The disguised block's full state. Drives the renderer (which
     *  delegates to this state's baked model) and is the only piece of
     *  Covered-Pipe-specific data that needs to survive save/load and sync
     *  to the client. Defaults to [Blocks.STONE] so a freshly-created BE
     *  (e.g. /setblock without an item component) still renders something
     *  visible instead of leaving the cube blank. */
    var camoBlockState: BlockState = Blocks.STONE.defaultBlockState()
        set(value) {
            if (field == value) return
            field = value
            setChanged()
            level?.sendBlockUpdated(
                worldPosition, blockState, blockState,
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS,
            )
        }

    // No-op connection set, the interface contract requires the methods
    // but Covered Pipes connect purely via face-adjacency.
    override fun getConnections(): List<BlockPos> = emptyList()
    override fun hasConnection(pos: BlockPos): Boolean = false
    override fun addConnection(pos: BlockPos): Boolean = false
    override fun removeConnection(pos: BlockPos): Boolean = false

    override fun forcedPipeBlocked(side: Direction): Boolean =
        (forcedPipeBlockedMask shr side.ordinal) and 1 != 0

    override fun toggleForcedPipeBlock(side: Direction) {
        forcedPipeBlockedMask = forcedPipeBlockedMask xor (1 shl side.ordinal)
        setChanged()
        level?.sendBlockUpdated(
            worldPosition, blockState, blockState,
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS,
        )
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
        // Persist as the block's registry id - stable across save/load and
        // small in NBT. Full BlockState properties aren't worth round-
        // tripping since the camo is purely visual and most wall blocks
        // are stateless anyway. If a future feature needs full state
        // persistence, swap to `BlockState.CODEC.encodeStart(NbtOps.INSTANCE, ...)`.
        val itemId = BuiltInRegistries.BLOCK.getKey(camoBlockState.block)
        output.putString("camoBlock", itemId.toString())
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        forcedPipeBlockedMask = input.getIntOr("forcedPipeBlocked", 0) and 0x3F
        val camoId = input.getStringOrNull("camoBlock")
        if (camoId != null) {
            val parsed = Identifier.tryParse(camoId)
            val block = parsed?.let { BuiltInRegistries.BLOCK.getValue(it) }
            if (block != null) camoBlockState = block.defaultBlockState()
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    /** Covered Pipes are colourless on purpose, the network identity lives
     *  on named devices (Controller / Terminal / antennas). Mirrors
     *  [PipeBlockEntity.networkColor]. */
    override fun networkColor(): Int =
        damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
}
