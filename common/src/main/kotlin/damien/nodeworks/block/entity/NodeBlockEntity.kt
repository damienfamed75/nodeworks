package damien.nodeworks.block.entity

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.card.ObserverSideCapability
import damien.nodeworks.card.RedstoneSideCapability
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.card.NodeCard
import damien.nodeworks.card.SideCapability
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import org.slf4j.LoggerFactory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Block entity for the Node block. Stores a separate inventory for each of the 6 faces.
 *
 * Optimized for large-scale systems:
 * - No ticking (passive storage, zero per-tick cost)
 * - Flat inventory array with O(1) side-to-slot mapping
 * - Only marks dirty on actual changes
 */
class NodeBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.NODE, pos, state), WorldlyContainer, damien.nodeworks.network.Connectable {

    companion object {
        private val logger = LoggerFactory.getLogger("nodeworks-node")
        const val SLOTS_PER_SIDE = 9
        const val TOTAL_SLOTS = SLOTS_PER_SIDE * 6 // 54 total

        /** Client-side callback for tracking node load/unload. Set by client init. */
        var nodeTracker: NodeTracker? = null

        /** Cached per-side slot index arrays to avoid allocation on every hopper interaction. */
        private val SLOTS_BY_FACE: Array<IntArray> = Array(6) { side ->
            val offset = side * SLOTS_PER_SIDE
            IntArray(SLOTS_PER_SIDE) { offset + it }
        }
    }

    /** Callback interface for client-side node position tracking. */
    fun interface NodeTracker {
        fun onNodeChanged(pos: BlockPos, loaded: Boolean)
    }

    private val items: NonNullList<ItemStack> = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY)
    private val connections: LinkedHashSet<BlockPos> = linkedSetOf()

    /** Count of legacy per-face monitors read from an older save. The monitor-on-node
     *  system was removed in favour of the standalone [damien.nodeworks.block.MonitorBlock];
     *  [loadAdditional] can't act on legacy data directly (no level yet), so we stash
     *  the count and drop one Monitor item per entry from [setLevel] when the level
     *  is available. The drop loses the tracked-item setting — by design, since
     *  re-placing the Monitor is easy and we avoid trying to synthesize BlockItem NBT
     *  from a partial legacy record. */
    private var legacyMonitorDrops: Int = 0

    // --- Redstone output per side (0-15) ---
    private val redstoneOutputs = IntArray(6) // indexed by Direction.ordinal

    fun getRedstoneOutput(side: Direction): Int = redstoneOutputs[side.ordinal]

    fun setRedstoneOutput(side: Direction, strength: Int) {
        val clamped = strength.coerceIn(0, 15)
        if (redstoneOutputs[side.ordinal] != clamped) {
            redstoneOutputs[side.ordinal] = clamped
            markDirtyAndSync()
            level?.updateNeighborsAt(worldPosition, blockState.block)
        }
    }

    fun hasAnyRedstoneOutput(): Boolean = redstoneOutputs.any { it > 0 }

    // --- Network connections ---

    override fun getConnections(): List<BlockPos> = connections.toList()

    override fun hasConnection(pos: BlockPos): Boolean = pos in connections

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

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    // --- Card access ---

    /** Returns all cards found in this side's 9 slots, with their alias if named. */
    fun getCards(side: Direction): List<CardInfo> {
        val offset = sideOffset(side)
        val result = mutableListOf<CardInfo>()
        for (i in 0 until SLOTS_PER_SIDE) {
            val stack = items[offset + i]
            val card = stack.item as? NodeCard ?: continue
            val alias = if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME))
                stack.hoverName.string else null
            result.add(CardInfo(card, alias, i))
        }
        return result
    }

    /** Resolves all capabilities for this side based on inserted cards. */
    fun getSideCapabilities(side: Direction): List<SideCapabilityInfo> {
        val adjacentPos = worldPosition.relative(side)
        val accessFace = side.opposite // face of the target block that faces the node
        return getCards(side).map { info ->
            val stack = items[sideOffset(side) + info.slotIndex]
            val capability = when (info.card) {
                is damien.nodeworks.card.IOCard -> IOSideCapability(adjacentPos, accessFace)
                is damien.nodeworks.card.StorageCard -> {
                    val priority = damien.nodeworks.card.StorageCard.getPriority(stack)
                    StorageSideCapability(adjacentPos, accessFace, priority)
                }
                is damien.nodeworks.card.RedstoneCard -> RedstoneSideCapability(adjacentPos, worldPosition, side, accessFace)
                is damien.nodeworks.card.ObserverCard -> ObserverSideCapability(adjacentPos, accessFace)
                else -> null
            }
            // Pull the channel here so [NetworkDiscovery.snapshotNode] doesn't need a
            // back-reference to the BlockEntity to read it. White is the default for
            // pre-channel cards and any card the user hasn't dyed yet — the channel
            // helper reads directly from CUSTOM_DATA so untouched stacks return WHITE.
            val channel = damien.nodeworks.card.CardChannel.get(stack)
            SideCapabilityInfo(capability ?: return@map null, info.alias, info.slotIndex, channel)
        }.filterNotNull()
    }

    data class CardInfo(val card: NodeCard, val alias: String?, val slotIndex: Int)
    data class SideCapabilityInfo(
        val capability: SideCapability,
        val alias: String?,
        val slotIndex: Int,
        val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
    )

    // --- Side-aware access ---

    private fun sideOffset(side: Direction): Int = side.ordinal * SLOTS_PER_SIDE

    fun getStack(side: Direction, slot: Int): ItemStack {
        require(slot in 0 until SLOTS_PER_SIDE) { "Slot $slot out of range for side" }
        return items[sideOffset(side) + slot]
    }

    fun setStack(side: Direction, slot: Int, stack: ItemStack) {
        require(slot in 0 until SLOTS_PER_SIDE) { "Slot $slot out of range for side" }
        items[sideOffset(side) + slot] = stack
        markDirtyAndSync()
    }

    // --- Container implementation ---

    override fun getContainerSize(): Int = TOTAL_SLOTS

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) markDirtyAndSync()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        return ContainerHelper.takeItem(items, slot)
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        markDirtyAndSync()
    }

    override fun stillValid(player: Player): Boolean {
        return Container.stillValidBlockEntity(this, player)
    }

    override fun clearContent() {
        items.clear()
        markDirtyAndSync()
    }

    // --- WorldlyContainer: controls which slots are accessible from each direction ---

    override fun getSlotsForFace(side: Direction): IntArray {
        return SLOTS_BY_FACE[side.ordinal]
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean {
        return stack.item is NodeCard
    }

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, side: Direction?): Boolean {
        if (side == null) return false
        if (stack.item !is NodeCard) return false
        val offset = sideOffset(side)
        return slot in offset until (offset + SLOTS_PER_SIDE)
    }

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, side: Direction): Boolean {
        val offset = sideOffset(side)
        return slot in offset until (offset + SLOTS_PER_SIDE)
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putBlockPosList("connections", connections)
        if (hasAnyRedstoneOutput()) {
            output.putIntArray("redstoneOutputs", redstoneOutputs.copyOf())
        }
        networkId?.let { output.putString("networkId", it.toString()) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        redstoneOutputs.fill(0)
        input.getIntArray("redstoneOutputs").ifPresent { saved ->
            for (i in 0 until minOf(saved.size, 6)) {
                redstoneOutputs[i] = saved[i].coerceIn(0, 15)
            }
        }
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        nodeTracker?.onNodeChanged(worldPosition, true)

        // Legacy migration — prior builds stored monitors attached to node faces under
        // a "monitors" ListTag. That system is gone; read the count here so setLevel
        // can drop standalone Monitor items as a replacement. childrenListOrEmpty is
        // safe when the tag is absent, so new saves cost nothing.
        val legacy = input.childrenListOrEmpty("monitors")
        legacyMonitorDrops = legacy.count()
    }

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
            // Legacy migration: drop one Monitor item per legacy per-face monitor
            // recorded on this node. The drop happens once (`legacyMonitorDrops` is
            // zeroed afterward) and isn't re-written on save since saveAdditional
            // no longer emits the "monitors" key. Scheduled via enqueueTickTask so
            // Containers.dropItemStack runs after the chunk finishes loading —
            // spawning entities mid-load can upset the chunk tracker.
            if (legacyMonitorDrops > 0) {
                val count = legacyMonitorDrops
                legacyMonitorDrops = 0
                newLevel.server.execute {
                    if (!newLevel.isLoaded(worldPosition)) return@execute
                    val stack = ItemStack(damien.nodeworks.registry.ModBlocks.MONITOR, count)
                    net.minecraft.world.Containers.dropItemStack(
                        newLevel,
                        worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5,
                        stack
                    )
                    logger.info(
                        "Migrated $count legacy monitor(s) on node at {} to standalone Monitor item drops.",
                        worldPosition
                    )
                }
            }
        }
    }

    /** Set to true by NodeBlock when the block is actually being destroyed. */
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    override fun setRemoved() {
        nodeTracker?.onNodeChanged(worldPosition, false)
        val currentLevel = level
        if (currentLevel is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.removeAllConnections(currentLevel, this)
            NodeConnectionHelper.untrackNode(currentLevel, worldPosition)
        }
        super.setRemoved()
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
