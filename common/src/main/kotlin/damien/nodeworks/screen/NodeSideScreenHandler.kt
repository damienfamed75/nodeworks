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
    private val side: Direction,
    private val nodePos: BlockPos,
    private val access: ContainerLevelAccess
) : AbstractContainerMenu(ModScreenHandlers.NODE_SIDE, syncId) {

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, data: NodeSideOpenData): NodeSideScreenHandler {
            val dummyInventory = net.minecraft.world.SimpleContainer(NodeBlockEntity.TOTAL_SLOTS)
            val side = Direction.entries[data.sideOrdinal]
            return NodeSideScreenHandler(syncId, playerInventory, dummyInventory, side, data.nodePos, ContainerLevelAccess.NULL)
        }
    }

    init {
        val offset = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE

        // 9 node slots — 3x3 grid
        for (row in 0..2) {
            for (col in 0..2) {
                val slotIndex = offset + row * 3 + col
                addSlot(CardOnlySlot(nodeInventory, slotIndex, 62 + col * 18, 17 + row * 18))
            }
        }

        // Player inventory
        // Player inventory (3 rows)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(net.minecraft.world.inventory.Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
            }
        }
        // Player hotbar
        for (col in 0 until 9) {
            addSlot(net.minecraft.world.inventory.Slot(playerInventory, col, 8 + col * 18, 84 + 58))
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()
        val nodeSlotCount = 9

        if (slotIndex < nodeSlotCount) {
            if (!moveItemStackTo(stack, nodeSlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, nodeSlotCount, false)) return ItemStack.EMPTY
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

    fun getSide(): Direction = side
    fun getNodePos(): BlockPos = nodePos
}
