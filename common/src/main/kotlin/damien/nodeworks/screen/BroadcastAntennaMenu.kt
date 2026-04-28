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
) : AbstractContainerMenu(ModScreenHandlers.BROADCAST_ANTENNA, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = antennaPos

    companion object {
        // Slot item positions. The slot frame is NOT drawn, the background image
        // (broadcast_antenna_bg.png) already depicts the slot visually.
        const val CHIP_SLOT_X = 80
        const val CHIP_SLOT_Y = 19
        const val UPGRADE_SLOT_X = 80
        const val UPGRADE_SLOT_Y = 77

        // Player inventory layout, lower panel sits below the 120px-tall BG image.
        const val INV_SLOT_ORIGIN_X = 9
        const val INV_SLOT_ORIGIN_Y = 137
        const val HOTBAR_SLOT_Y = 195

        fun createServer(syncId: Int, playerInventory: Inventory, entity: BroadcastAntennaBlockEntity, pos: BlockPos): BroadcastAntennaMenu {
            return BroadcastAntennaMenu(syncId, playerInventory, entity, pos)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: BroadcastAntennaOpenData): BroadcastAntennaMenu {
            return BroadcastAntennaMenu(syncId, playerInventory, SimpleContainer(2), data.pos)
        }
    }

    init {
        addSlot(ChipSlot(antennaInventory, BroadcastAntennaBlockEntity.SLOT_CHIP, CHIP_SLOT_X, CHIP_SLOT_Y))
        addSlot(UpgradeSlot(antennaInventory, BroadcastAntennaBlockEntity.SLOT_UPGRADE, UPGRADE_SLOT_X, UPGRADE_SLOT_Y))

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, INV_SLOT_ORIGIN_X + col * 18, INV_SLOT_ORIGIN_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, INV_SLOT_ORIGIN_X + col * 18, HOTBAR_SLOT_Y))
        }
    }

    private class ChipSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is LinkCrystalItem
        override fun getMaxStackSize(): Int = 1
    }

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
            if (!moveItemStackTo(stack, 2, slots.size, true)) return ItemStack.EMPTY
        } else {
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
