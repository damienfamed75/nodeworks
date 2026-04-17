package damien.nodeworks.block.entity

import damien.nodeworks.card.IOSideCapability
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

    // --- Monitors ---

    /** Per-face monitor data. Null = no monitor on that face. */
    data class MonitorData(val trackedItemId: String?, var displayCount: Long = 0L)

    private val monitors = java.util.EnumMap<Direction, MonitorData>(Direction::class.java)

    fun hasMonitor(face: Direction): Boolean = monitors.containsKey(face)
    fun getMonitor(face: Direction): MonitorData? = monitors[face]
    fun getMonitorFaces(): Set<Direction> = monitors.keys.toSet()

    fun attachMonitor(face: Direction) {
        if (!monitors.containsKey(face)) {
            monitors[face] = MonitorData(null)
            markDirtyAndSync()
            val lvl = level
            if (lvl is net.minecraft.server.level.ServerLevel) {
                damien.nodeworks.script.MonitorUpdateHelper.trackNode(worldPosition)
            }
        }
    }

    fun removeMonitor(face: Direction): Boolean {
        if (monitors.remove(face) != null) {
            markDirtyAndSync()
            if (monitors.isEmpty()) {
                damien.nodeworks.script.MonitorUpdateHelper.untrackNode(worldPosition)
            }
            return true
        }
        return false
    }

    fun setMonitorItem(face: Direction, itemId: String?) {
        val monitor = monitors[face] ?: return
        monitors[face] = monitor.copy(trackedItemId = itemId)
        markDirtyAndSync()
    }

    fun updateMonitorCount(face: Direction, count: Long) {
        val monitor = monitors[face] ?: return
        monitor.displayCount = count
    }

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
            val capability = when (info.card) {
                is damien.nodeworks.card.IOCard -> IOSideCapability(adjacentPos, accessFace)
                is damien.nodeworks.card.StorageCard -> {
                    val stack = items[sideOffset(side) + info.slotIndex]
                    val priority = damien.nodeworks.card.StorageCard.getPriority(stack)
                    StorageSideCapability(adjacentPos, accessFace, priority)
                }
                is damien.nodeworks.card.RedstoneCard -> RedstoneSideCapability(adjacentPos, worldPosition, side, accessFace)
                else -> null
            }
            SideCapabilityInfo(capability ?: return@map null, info.alias, info.slotIndex)
        }.filterNotNull()
    }

    data class CardInfo(val card: NodeCard, val alias: String?, val slotIndex: Int)
    data class SideCapabilityInfo(val capability: SideCapability, val alias: String?, val slotIndex: Int)

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

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
        if (connections.isNotEmpty()) {
            tag.putLongArray("connections", connections.map { it.asLong() }.toLongArray())
        }
        // Save redstone outputs (only if any non-zero)
        if (hasAnyRedstoneOutput()) {
            tag.putIntArray("redstoneOutputs", redstoneOutputs.toList())
        }
        networkId?.let { tag.putString("networkId", it.toString()) }
        // Save monitors
        tag.putInt("monitorCount", monitors.size)
        var idx = 0
        for ((face, data) in monitors) {
            tag.putInt("monitorFace_$idx", face.ordinal)
            tag.putString("monitorItem_$idx", data.trackedItemId ?: "")
            tag.putLong("monitorCount_$idx", data.displayCount)
            idx++
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items.clear()
        ContainerHelper.loadAllItems(tag, items, registries)
        connections.clear()
        if (tag.contains("connections")) {
            tag.getLongArray("connections").forEach { connections.add(BlockPos.of(it)) }
        }
        // Load redstone outputs
        redstoneOutputs.fill(0)
        if (tag.contains("redstoneOutputs")) {
            val saved = tag.getIntArray("redstoneOutputs")
            for (i in 0 until minOf(saved.size, 6)) {
                redstoneOutputs[i] = saved[i].coerceIn(0, 15)
            }
        }
        networkId = tag.getString("networkId").takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        // Load monitors
        monitors.clear()
        val monitorCount = if (tag.contains("monitorCount")) tag.getInt("monitorCount") else 0
        for (i in 0 until monitorCount) {
            val faceOrdinal = if (tag.contains("monitorFace_$i")) tag.getInt("monitorFace_$i") else -1
            if (faceOrdinal < 0 || faceOrdinal >= Direction.entries.size) continue
            val face = Direction.entries[faceOrdinal]
            val itemId = tag.getString("monitorItem_$i").ifEmpty { null }
            val displayCount = if (tag.contains("monitorCount_$i")) tag.getLong("monitorCount_$i") else 0L
            monitors[face] = MonitorData(itemId, displayCount)
        }
        nodeTracker?.onNodeChanged(worldPosition, true)
    }

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            if (monitors.isNotEmpty()) {
                damien.nodeworks.script.MonitorUpdateHelper.trackNode(worldPosition)
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
