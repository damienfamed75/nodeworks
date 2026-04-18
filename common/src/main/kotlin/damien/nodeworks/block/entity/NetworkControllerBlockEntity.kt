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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Block entity for the Network Controller — the heart of every network.
 * Stores a UUID that defines the network's identity.
 * Generated on first placement, persists through world save/load.
 */
class NetworkControllerBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.NETWORK_CONTROLLER, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false

    /** The network's unique identity. Generated on first placement. */
    override var networkId: UUID? = UUID.randomUUID()

    // --- Network Settings ---

    /** Network color as RGB (no alpha). Default green. */
    var networkColor: Int = 0x83E086
        set(value) {
            field = value and 0xFFFFFF
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Custom network name. Empty = unnamed. */
    var networkName: String = ""
        set(value) {
            field = value.take(32) // cap length
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Redstone mode: 0 = Ignored, 1 = Active on Low, 2 = Active on High */
    var redstoneMode: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Node glow style: 0=square, 1=circle, 2=dot, 3=creeper, 4=spiral, 5=none */
    var nodeGlowStyle: Int = GLOW_SQUARE
        set(value) {
            field = value.coerceIn(0, 5)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** How many times the Crafting CPU retries a Processing Set handler whose inputs
     *  went unmoved before giving up. 0 = never retry (fail fast). Default 50. */
    var handlerRetryLimit: Int = 50
        set(value) {
            field = value.coerceIn(0, 500)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    companion object {
        const val REDSTONE_IGNORED = 0
        const val REDSTONE_LOW = 1
        const val REDSTONE_HIGH = 2

        const val GLOW_SQUARE = 0
        const val GLOW_CIRCLE = 1
        const val GLOW_DOT = 2
        const val GLOW_CREEPER = 3
        const val GLOW_SPIRAL = 4
        const val GLOW_NONE = 5
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
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
            networkId?.let {
                damien.nodeworks.script.NetworkInventoryCache.removeByUUID(it)
                damien.nodeworks.network.NetworkSettingsRegistry.remove(it)
            }
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putInt("networkColor", networkColor)
        output.putString("networkName", networkName)
        output.putInt("redstoneMode", redstoneMode)
        output.putInt("nodeGlowStyle", nodeGlowStyle)
        output.putInt("handlerRetryLimit", handlerRetryLimit)
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val idStr = input.getStringOr("networkId", "")
        if (idStr.isNotEmpty()) {
            try {
                networkId = UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                networkId = UUID.randomUUID()
            }
        }
        networkColor = input.getIntOr("networkColor", 0x83E086)
        networkName = input.getStringOr("networkName", "")
        redstoneMode = input.getIntOr("redstoneMode", 0)
        nodeGlowStyle = input.getIntOr("nodeGlowStyle", GLOW_SQUARE)
        handlerRetryLimit = input.getIntOr("handlerRetryLimit", 50)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        // Push loaded settings into client-visible registry so color/glow survive reloads.
        networkId?.let {
            damien.nodeworks.network.NetworkSettingsRegistry.update(
                it,
                damien.nodeworks.network.NetworkSettingsRegistry.NetworkSettings(networkColor, nodeGlowStyle)
            )
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
