package damien.nodeworks.screen

import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.registry.ModItems
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
    val antennaPos: BlockPos
) : AbstractContainerMenu(ModScreenHandlers.BROADCAST_ANTENNA, syncId) {

    companion object {
        const val CHIP_SLOT_X = 62
        const val UPGRADE_SLOT_X = 98
        const val SLOT_Y = 37

        fun createServer(syncId: Int, playerInventory: Inventory, entity: BroadcastAntennaBlockEntity, pos: BlockPos): BroadcastAntennaMenu {
            return BroadcastAntennaMenu(syncId, playerInventory, entity, pos)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: BroadcastAntennaOpenData): BroadcastAntennaMenu {
            return BroadcastAntennaMenu(syncId, playerInventory, SimpleContainer(2), data.pos)
        }
    }

    init {
        addSlot(ChipSlot(antennaInventory, BroadcastAntennaBlockEntity.SLOT_CHIP, CHIP_SLOT_X, SLOT_Y))
        addSlot(UpgradeSlot(antennaInventory, BroadcastAntennaBlockEntity.SLOT_UPGRADE, UPGRADE_SLOT_X, SLOT_Y))

        // Player inventory (3 rows)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 9 + col * 18, 83 + row * 18))
            }
        }
        // Hotbar
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 9 + col * 18, 83 + 3 * 18 + 4))
        }
    }

    private class ChipSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is LinkCrystalItem
        override fun getMaxStackSize(): Int = 1
    }

    /** Accepts either of the two range upgrade items. */
    private class UpgradeSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean =
            stack.item == ModItems.DIMENSION_RANGE_UPGRADE || stack.item == ModItems.MULTI_DIMENSION_RANGE_UPGRADE
        override fun getMaxStackSize(): Int = 1
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()

        if (slotIndex <= 1) {
            // Container → player inv
            if (!moveItemStackTo(stack, 2, slots.size, true)) return ItemStack.EMPTY
        } else {
            // Player inv → container. Route to the right slot by item type.
            when {
                stack.item is LinkCrystalItem -> {
                    if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY
                }
                stack.item == ModItems.DIMENSION_RANGE_UPGRADE || stack.item == ModItems.MULTI_DIMENSION_RANGE_UPGRADE -> {
                    if (!moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY
                }
                else -> return ItemStack.EMPTY
            }
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = antennaInventory.stillValid(player)
}
