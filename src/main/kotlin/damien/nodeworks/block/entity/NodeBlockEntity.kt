package damien.nodeworks.block.entity

import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
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
) : BlockEntity(ModBlockEntities.NODE, pos, state), WorldlyContainer {

    companion object {
        const val SLOTS_PER_SIDE = 9
        const val TOTAL_SLOTS = SLOTS_PER_SIDE * 6 // 54 total

        /** Client-side callback for tracking node load/unload. Set by client init. */
        var nodeTracker: NodeTracker? = null
    }

    /** Callback interface for client-side node position tracking. */
    fun interface NodeTracker {
        fun onNodeChanged(pos: BlockPos, loaded: Boolean)
    }

    private val items: NonNullList<ItemStack> = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY)
    private val connections: LinkedHashSet<BlockPos> = linkedSetOf()

    // --- Network connections ---

    fun getConnections(): List<BlockPos> = connections.toList()

    fun hasConnection(pos: BlockPos): Boolean = pos in connections

    fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    // --- Side-aware access ---

    private fun sideOffset(side: Direction): Int = side.ordinal * SLOTS_PER_SIDE

    fun getStack(side: Direction, slot: Int): ItemStack {
        require(slot in 0 until SLOTS_PER_SIDE) { "Slot $slot out of range for side" }
        return items[sideOffset(side) + slot]
    }

    fun setStack(side: Direction, slot: Int, stack: ItemStack) {
        require(slot in 0 until SLOTS_PER_SIDE) { "Slot $slot out of range for side" }
        items[sideOffset(side) + slot] = stack
        setChanged()
    }

    // --- Container implementation ---

    override fun getContainerSize(): Int = TOTAL_SLOTS

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) setChanged()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        return ContainerHelper.takeItem(items, slot)
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        setChanged()
    }

    override fun stillValid(player: Player): Boolean {
        return Container.stillValidBlockEntity(this, player)
    }

    override fun clearContent() {
        items.clear()
        setChanged()
    }

    // --- WorldlyContainer: controls which slots are accessible from each direction ---

    override fun getSlotsForFace(side: Direction): IntArray {
        val offset = sideOffset(side)
        return IntArray(SLOTS_PER_SIDE) { offset + it }
    }

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, side: Direction?): Boolean {
        if (side == null) return false
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
        output.store("connections", BlockPos.CODEC.listOf(), connections.toList())
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        connections.clear()
        input.read("connections", BlockPos.CODEC.listOf()).ifPresent { connections.addAll(it) }
        nodeTracker?.onNodeChanged(worldPosition, true)
        NodeConnectionHelper.trackNode(worldPosition)
    }

    override fun setRemoved() {
        nodeTracker?.onNodeChanged(worldPosition, false)
        NodeConnectionHelper.untrackNode(worldPosition)
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
