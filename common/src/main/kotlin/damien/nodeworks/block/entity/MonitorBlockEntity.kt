package damien.nodeworks.block.entity

import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.compat.getLongOrNull
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.putBlockPosList
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
import java.util.UUID

/**
 * Backs the full-block Monitor. Tracks one item id and the current count seen through
 * the network's storage cards, refreshed from [damien.nodeworks.script.MonitorUpdateHelper]
 * every 20 ticks. Connectable so it participates in the node-connection graph like the
 * Terminal does, which also lets [damien.nodeworks.script.NetworkInventoryCache] walk
 * from this position out to storage cards.
 */
class MonitorBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.MONITOR, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** Fully-qualified item id being displayed on the screen, or null when the Monitor
     *  hasn't been programmed yet. Set by right-clicking the block with an item. */
    var trackedItemId: String? = null
        private set

    /** Most recent network-wide count for [trackedItemId], pushed client-side via the
     *  BE update packet so the front-face renderer has it without a server round-trip. */
    var displayCount: Long = 0L

    fun setTrackedItem(itemId: String?) {
        if (trackedItemId == itemId) return
        trackedItemId = itemId
        displayCount = 0L
        markDirtyAndSync()
    }

    fun updateDisplayCount(count: Long) {
        if (count == displayCount) return
        displayCount = count
        setChanged()
        val lvl = level ?: return
        lvl.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
            damien.nodeworks.script.MonitorUpdateHelper.trackMonitor(worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val currentLevel = level
        if (currentLevel is ServerLevel) {
            damien.nodeworks.script.MonitorUpdateHelper.untrackMonitor(worldPosition)
            NodeConnectionHelper.removeAllConnections(currentLevel, this)
            NodeConnectionHelper.untrackNode(currentLevel, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putBlockPosList("connections", connections)
        networkId?.let { output.putString("networkId", it.toString()) }
        trackedItemId?.let { output.putString("trackedItem", it) }
        if (displayCount != 0L) output.putLong("count", displayCount)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        // Notify the client-side settings cache so the emissive tint refreshes the
        // same tick the BE loads, matching every other Connectable BE in the mod.
        // Without this the Monitor's glow could stay stale-grey until the next
        // network topology change pushed a new color.
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        trackedItemId = input.getStringOrNull("trackedItem")?.takeIf { it.isNotEmpty() }
        displayCount = input.getLongOrNull("count") ?: 0L
    }

    // --- Client sync ---
    //
    // `saveWithoutMetadata` delegates to `saveAdditional`, so the update packet
    // carries `networkId`, `connections`, `trackedItem`, and `count`. The client-side
    // BER and NodeConnectionRenderer both need networkId to tint the emissive face
    // with the current network colour, without the full save they'd see
    // networkId=null and the glow would stay grey.

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
