package damien.nodeworks.screen

import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.item.LinkChipItem
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class BroadcastAntennaMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val antennaInventory: Container,
    private val antennaPos: BlockPos
) : AbstractContainerMenu(ModScreenHandlers.BROADCAST_ANTENNA, syncId) {

    companion object {
        fun createServer(syncId: Int, playerInventory: Inventory, entity: BroadcastAntennaBlockEntity, pos: BlockPos): BroadcastAntennaMenu {
            return BroadcastAntennaMenu(syncId, playerInventory, entity, pos)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: BroadcastAntennaOpenData): BroadcastAntennaMenu {
            return BroadcastAntennaMenu(syncId, playerInventory, SimpleContainer(1), data.pos)
        }
    }

    init {
        // Chip slot — center of GUI
        addSlot(ChipSlot(antennaInventory, 0, 80, 35))

        // Player inventory
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 142))
        }
    }

    private class ChipSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is LinkChipItem
        override fun getMaxStackSize(): Int = 1
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()

        if (slotIndex == 0) {
            if (!moveItemStackTo(stack, 1, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (stack.item is LinkChipItem) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY
            } else return ItemStack.EMPTY
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = antennaInventory.stillValid(player)
}
