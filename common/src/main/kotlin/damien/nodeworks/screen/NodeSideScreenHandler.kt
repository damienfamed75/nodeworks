package damien.nodeworks.screen

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.card.NodeCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class NodeSideScreenHandler(
    syncId: Int,
    playerInventory: Inventory,
    private val nodeInventory: Container,
    initialSide: Direction,
    private val nodePos: BlockPos,
    private val access: ContainerLevelAccess
) : AbstractContainerMenu(ModScreenHandlers.NODE_SIDE, syncId) {

    companion object {
        /** Extra vertical offset for the tab row between title bar and card grid. */
        const val TAB_SHIFT = 18

        /** Visible card slot positions (3x3 grid). */
        private fun cardSlotX(col: Int) = 63 + col * 18
        private fun cardSlotY(row: Int) = 25 + TAB_SHIFT + row * 18

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: NodeSideOpenData): NodeSideScreenHandler {
            val dummyInventory = net.minecraft.world.SimpleContainer(NodeBlockEntity.TOTAL_SLOTS)
            val side = Direction.entries[data.sideOrdinal]
            return NodeSideScreenHandler(syncId, playerInventory, dummyInventory, side, data.nodePos, ContainerLevelAccess.NULL)
        }
    }

    /** Currently displayed side — updated by tab clicks. */
    var activeSide: Direction = initialSide
        private set

    init {
        // Add all 54 node slots (6 sides × 9 slots).
        // Only the initial side's slots are at visible positions; others are off-screen.
        for (side in Direction.entries) {
            val offset = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE
            val visible = side == initialSide
            for (row in 0..2) {
                for (col in 0..2) {
                    val slotIndex = offset + row * 3 + col
                    addSlot(CardOnlySlot(
                        nodeInventory, slotIndex,
                        if (visible) cardSlotX(col) else -9999,
                        if (visible) cardSlotY(row) else -9999
                    ))
                }
            }
        }

        // Player inventory (3 rows)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 9 + col * 18, 93 + TAB_SHIFT + row * 18))
            }
        }
        // Player hotbar
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 9 + col * 18, 93 + TAB_SHIFT + 3 * 18 + 4))
        }
    }

    /** Switch the active side — repositions slots so only the new side is visible. */
    fun switchSide(newSide: Direction) {
        activeSide = newSide
        for (sideOrd in 0..5) {
            val visible = sideOrd == newSide.ordinal
            for (row in 0..2) {
                for (col in 0..2) {
                    val slot = slots[sideOrd * 9 + row * 3 + col]
                    slot.x = if (visible) cardSlotX(col) else -9999
                    slot.y = if (visible) cardSlotY(row) else -9999
                }
            }
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()
        val nodeSlotCount = 54  // 6 sides × 9 slots
        val activeStart = activeSide.ordinal * 9
        val activeEnd = activeStart + 9

        if (slotIndex < nodeSlotCount) {
            // From node slot → player inventory
            if (!moveItemStackTo(stack, nodeSlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            // From player → active side's node slots only
            if (!moveItemStackTo(stack, activeStart, activeEnd, false)) return ItemStack.EMPTY
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean {
        return nodeInventory.stillValid(player)
    }

    private class CardOnlySlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is NodeCard
    }

    fun getSide(): Direction = activeSide
    fun getNodePos(): BlockPos = nodePos
}
