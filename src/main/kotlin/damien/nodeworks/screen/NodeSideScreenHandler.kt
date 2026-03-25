package damien.nodeworks.screen

import damien.nodeworks.card.NodeCard
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
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
    private val side: Direction,
    private val access: ContainerLevelAccess
) : AbstractContainerMenu(ModScreenHandlers.NODE_SIDE, syncId) {

    companion object {
        /** Client-side factory — receives direction ordinal from server. */
        fun clientFactory(syncId: Int, playerInventory: Inventory, sideOrdinal: Int): NodeSideScreenHandler {
            val dummyInventory = net.minecraft.world.SimpleContainer(NodeBlockEntity.TOTAL_SLOTS)
            val side = Direction.entries[sideOrdinal]
            return NodeSideScreenHandler(syncId, playerInventory, dummyInventory, side, ContainerLevelAccess.NULL)
        }
    }

    init {
        val offset = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE

        // Card slot (slot 0 of this side) — top center
        addSlot(CardSlot(nodeInventory, offset, 80, 18))

        // Buffer slots (slots 1–8) — 2 rows of 4
        for (row in 0..1) {
            for (col in 0..3) {
                val slotIndex = offset + 1 + row * 4 + col
                addSlot(BufferSlot(nodeInventory, slotIndex, 53 + col * 18, 42 + row * 18))
            }
        }

        // Player inventory (3 rows)
        addStandardInventorySlots(playerInventory, 8, 90)
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()
        val nodeSlotCount = 1 + 8 // card + buffer

        if (slotIndex < nodeSlotCount) {
            // Moving from node → player inventory
            if (!moveItemStackTo(stack, nodeSlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            // Moving from player → node: try card slot first, then buffer
            if (stack.item is NodeCard) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY
            } else {
                if (!moveItemStackTo(stack, 1, nodeSlotCount, false)) return ItemStack.EMPTY
            }
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean {
        return nodeInventory.stillValid(player)
    }

    fun getSide(): Direction = side

    /** Slot that only accepts NodeCard items. */
    private class CardSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is NodeCard
        override fun getMaxStackSize(): Int = 1
    }

    /** Slot that rejects NodeCard items. */
    private class BufferSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item !is NodeCard
    }
}
