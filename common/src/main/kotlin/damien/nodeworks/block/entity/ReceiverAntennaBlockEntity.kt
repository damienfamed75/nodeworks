package damien.nodeworks.block.entity

import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/** Fallback radius used only if the paired broadcast's own effective range can't be read.
 *  Real range is [BroadcastAntennaBlockEntity.effectiveRange] queried off the broadcast. */
private const val BASE_RANGE = 128.0

/**
 * Receiver Antenna — receives Processing Sets from a paired Broadcast Antenna.
 * Connectable via laser to the consumer network.
 * Has 1 slot for an encoded Link Crystal that defines the pairing.
 */
class ReceiverAntennaBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.RECEIVER_ANTENNA, pos, state), Container, Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    private val items = NonNullList.withSize(1, ItemStack.EMPTY)

    // Pairing data — read from the Link Crystal in the slot
    private var pairedPos: BlockPos? = null
    private var pairedDimension: ResourceKey<Level>? = null
    private var pairedFrequencyId: UUID? = null

    val isPaired: Boolean get() = pairedPos != null && pairedFrequencyId != null

    /** 0=not linked, 1=linked, 2=out of range, 3=broadcast not found, 4=freq mismatch,
     *  5=not loaded, 6=dimension mismatch (broadcast lacks Multi-Dimension upgrade). */
    fun getConnectionStatus(level: ServerLevel): Int {
        val pos = pairedPos ?: return 0
        val dim = pairedDimension ?: return 0
        val freq = pairedFrequencyId ?: return 0
        val targetLevel = level.server.getLevel(dim) ?: return 3
        if (!targetLevel.isLoaded(pos)) return 5
        val broadcast = targetLevel.getBlockEntity(pos) as? BroadcastAntennaBlockEntity ?: return 3
        if (broadcast.frequencyId != freq) return 4

        val sameDimension = dim == level.dimension()
        if (!sameDimension) {
            // Cross-dimensional pairing is gated behind the Multi-Dimension upgrade.
            return if (broadcast.allowsCrossDimension) 1 else 6
        }
        // Same dimension — distance must be within broadcast's effective range.
        val dx = pos.x - worldPosition.x.toDouble()
        val dy = pos.y - worldPosition.y.toDouble()
        val dz = pos.z - worldPosition.z.toDouble()
        val range = broadcast.effectiveRange
        if (dx * dx + dy * dy + dz * dz > range * range) return 2
        return 1
    }

    /** Load the paired Broadcast Antenna if in range, same/allowed dimension, matching frequency. */
    fun getBroadcastAntenna(level: ServerLevel): BroadcastAntennaBlockEntity? {
        val pos = pairedPos ?: return null
        val dim = pairedDimension ?: return null
        val freq = pairedFrequencyId ?: return null

        val targetLevel = level.server.getLevel(dim) ?: return null
        if (!targetLevel.isLoaded(pos)) return null

        val broadcast = targetLevel.getBlockEntity(pos) as? BroadcastAntennaBlockEntity ?: return null
        if (broadcast.frequencyId != freq) return null

        val sameDimension = dim == level.dimension()
        if (!sameDimension) {
            return if (broadcast.allowsCrossDimension) broadcast else null
        }
        val dx = pos.x - worldPosition.x.toDouble()
        val dy = pos.y - worldPosition.y.toDouble()
        val dz = pos.z - worldPosition.z.toDouble()
        val range = broadcast.effectiveRange
        if (dx * dx + dy * dy + dz * dz > range * range) return null
        return broadcast
    }

    /** Read pairing data from the chip in the slot. */
    private fun updatePairingFromChip() {
        val wasPaired = isPaired
        val stack = items[0]
        if (stack.isEmpty || stack.item !is LinkCrystalItem) {
            pairedPos = null
            pairedDimension = null
            pairedFrequencyId = null
            if (wasPaired != isPaired) updateSegmentConnectedState()
            return
        }
        val data = LinkCrystalItem.getPairingData(stack)
        if (data != null) {
            pairedPos = data.pos
            pairedDimension = data.dimension
            pairedFrequencyId = data.frequencyId
        } else {
            pairedPos = null
            pairedDimension = null
            pairedFrequencyId = null
        }
        if (wasPaired != isPaired) updateSegmentConnectedState()
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

    /** Flip the segment above's CONNECTED blockstate to match whether this receiver is
     *  currently fully linked to a Broadcast Antenna. Only status code 1 ("linked")
     *  counts — out-of-range / not-loaded / frequency-mismatch / broadcast-not-found
     *  all leave the horn dark. Drives the horn on/off multipart model. */
    fun updateSegmentConnectedState() {
        if (isRemoved) return
        val lvl = level as? ServerLevel ?: return
        val segmentPos = worldPosition.above()
        val segState = lvl.getBlockState(segmentPos)
        if (segState.block !is damien.nodeworks.block.AntennaSegmentBlock) return
        val desired = getConnectionStatus(lvl) == 1
        if (segState.getValue(damien.nodeworks.block.AntennaSegmentBlock.CONNECTED) == desired) return
        lvl.setBlock(
            segmentPos,
            segState.setValue(damien.nodeworks.block.AntennaSegmentBlock.CONNECTED, desired),
            Block.UPDATE_ALL
        )
    }

    /** Called every tick by [damien.nodeworks.block.ReceiverAntennaBlock.getTicker]. We
     *  only re-check every 20 ticks (1s) — the check walks to the paired broadcast and
     *  compares UUIDs, which is cheap but not free. setBlock only fires when the desired
     *  state actually changes, so the work per tick is minimal. */
    fun serverTick(lvl: ServerLevel) {
        tickCounter = (tickCounter + 1) % 20
        if (tickCounter == 0) updateSegmentConnectedState()
    }

    private var tickCounter: Int = 0

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        // Flip the BE's removed flag FIRST so updateSegmentConnectedState bails out.
        // Otherwise the chain removeAllConnections → removeConnection → setBlock
        // mutates blocks mid-chunk-unload and hangs world save.
        super.setRemoved()
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
    }

    // --- Container (1 chip slot) ---

    override fun getContainerSize(): Int = 1
    override fun isEmpty(): Boolean = items[0].isEmpty

    override fun getItem(slot: Int): ItemStack =
        if (slot == 0) items[0] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) {
            updatePairingFromChip()
            setChanged()
        }
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val result = ContainerHelper.takeItem(items, slot)
        updatePairingFromChip()
        return result
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot != 0) return
        items[0] = stack
        updatePairingFromChip()
        setChanged()
    }

    override fun stillValid(player: Player): Boolean =
        player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun clearContent() {
        items.clear()
        updatePairingFromChip()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putBlockPosList("connections", connections)
        networkId?.let { output.putString("networkId", it.toString()) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        // Keep updatePairingFromChip() post-load so the receiver re-syncs to its chip.
        updatePairingFromChip()
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
