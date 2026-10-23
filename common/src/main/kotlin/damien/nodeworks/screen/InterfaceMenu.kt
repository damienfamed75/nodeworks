package damien.nodeworks.screen

import damien.nodeworks.block.entity.InterfaceBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu for the Interface: its nine buffer slots over the player inventory. The
 * Interface has no settings, what it shows is decided by the network behind it.
 */
class InterfaceMenu(
    syncId: Int,
    val devicePos: BlockPos,
    private val buffer: Container,
    playerInventory: Inventory,
) : AbstractContainerMenu(ModScreenHandlers.INTERFACE, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val SLOT_COUNT = InterfaceBlockEntity.SLOT_COUNT
        const val GRID_X = 8
        const val GRID_Y = 22
        const val INV_X = 8
        const val INV_Y = 102
        const val HOTBAR_Y = 160
        const val SLOT_INSET_X = 1
        const val SLOT_INSET_Y = 1

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: InterfaceOpenData): InterfaceMenu =
            InterfaceMenu(syncId, openData.pos, SimpleContainer(SLOT_COUNT), playerInventory)

        fun createServer(syncId: Int, playerInventory: Inventory, entity: InterfaceBlockEntity): InterfaceMenu =
            InterfaceMenu(syncId, entity.blockPos, entity, playerInventory)
    }

    init {
        for (col in 0 until SLOT_COUNT) {
            addSlot(Slot(buffer, col, GRID_X + col * 18, GRID_Y))
        }
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, INV_X + SLOT_INSET_X + col * 18, INV_Y + SLOT_INSET_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, INV_X + SLOT_INSET_X + col * 18, HOTBAR_Y + SLOT_INSET_Y))
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (slotIndex < SLOT_COUNT) {
            if (!moveItemStackTo(stack, SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = buffer.stillValid(player)
}
