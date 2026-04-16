package damien.nodeworks.screen

import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.card.InstructionSet
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
 * Instruction Storage screen handler — fixed 2 columns × 6 rows = 12 instruction slots.
 * No upgrades. Slot positions match [InstructionStorageScreen]'s layout.
 */
class InstructionStorageScreenHandler(
    syncId: Int,
    playerInventory: Inventory,
    private val storageInventory: Container,
    val storagePos: BlockPos
) : AbstractContainerMenu(ModScreenHandlers.INSTRUCTION_STORAGE, syncId) {

    companion object {
        const val INSTRUCTION_SLOT_COUNT = 12
        const val COLS = 2
        const val ROWS = 6

        // Card grid — 2 cols × 6 rows, horizontally centered in a 176-wide frame.
        // Slot origin: (70, 28). Each slot is 18×18.
        const val GRID_X = 70
        const val GRID_Y = 28

        // Player inventory — INV_X / INV_Y are the FRAME origin passed to drawPlayerInventory.
        // Slot positions sit 1px inside to match the 1px border of NineSlice.SLOT.
        const val INV_X = 8
        const val INV_Y = 150
        const val HOTBAR_Y = 208
        const val SLOT_INSET = 1

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: InstructionStorageOpenData): InstructionStorageScreenHandler {
            val dummy = SimpleContainer(InstructionStorageBlockEntity.TOTAL_SLOTS)
            return InstructionStorageScreenHandler(syncId, playerInventory, dummy, openData.pos)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: InstructionStorageBlockEntity, pos: BlockPos): InstructionStorageScreenHandler {
            return InstructionStorageScreenHandler(syncId, playerInventory, entity, pos)
        }
    }

    init {
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val slotIndex = row * COLS + col
                addSlot(InstructionSlot(storageInventory, slotIndex, GRID_X + col * 18, GRID_Y + row * 18))
            }
        }

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, INV_X + SLOT_INSET + col * 18, INV_Y + SLOT_INSET + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, INV_X + SLOT_INSET + col * 18, HOTBAR_Y + SLOT_INSET))
        }
    }

    private class InstructionSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean {
            return stack.item is InstructionSet && InstructionSet.getOutput(stack).isNotEmpty()
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()

        if (slotIndex < INSTRUCTION_SLOT_COUNT) {
            if (!moveItemStackTo(stack, INSTRUCTION_SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (stack.item is InstructionSet) {
                if (!moveItemStackTo(stack, 0, INSTRUCTION_SLOT_COUNT, false)) return ItemStack.EMPTY
            } else {
                return ItemStack.EMPTY
            }
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = storageInventory.stillValid(player)
}
