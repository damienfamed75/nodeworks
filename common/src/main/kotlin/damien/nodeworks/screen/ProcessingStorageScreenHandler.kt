package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.card.ProcessingSet
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
 * Processing Storage screen handler — fixed 2 columns × 4 rows = 8 API card slots.
 * No upgrades. Slot positions are synced with [ProcessingStorageScreen]'s layout.
 */
class ProcessingStorageScreenHandler(
    syncId: Int,
    playerInventory: Inventory,
    private val storageInventory: Container,
    val storagePos: BlockPos
) : AbstractContainerMenu(ModScreenHandlers.PROCESSING_STORAGE, syncId) {

    companion object {
        const val API_SLOT_COUNT = 8
        const val COLS = 2
        const val ROWS = 4

        // Card grid — 2 cols × 4 rows, horizontally centered in a 176-wide frame.
        // Slot origin: (70, 28). Each slot is 18×18.
        const val GRID_X = 70
        const val GRID_Y = 28

        // Player inventory — INV_X / INV_Y are the FRAME origin passed to drawPlayerInventory.
        // Slot positions sit 1px inside (matches the 1px border of NineSlice.SLOT, same
        // pattern as CardProgrammer's inventory).
        const val INV_X = 8
        const val INV_Y = 114
        const val HOTBAR_Y = 172
        const val SLOT_INSET = 1

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ProcessingStorageOpenData): ProcessingStorageScreenHandler {
            val dummy = SimpleContainer(ProcessingStorageBlockEntity.TOTAL_SLOTS)
            return ProcessingStorageScreenHandler(syncId, playerInventory, dummy, openData.pos)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: ProcessingStorageBlockEntity, pos: BlockPos): ProcessingStorageScreenHandler {
            return ProcessingStorageScreenHandler(syncId, playerInventory, entity, pos)
        }
    }

    init {
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val slotIndex = row * COLS + col
                addSlot(ApiCardSlot(storageInventory, slotIndex, GRID_X + col * 18, GRID_Y + row * 18))
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

    private class ApiCardSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean {
            return stack.item is ProcessingSet && ProcessingSet.getOutputs(stack).isNotEmpty()
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()

        if (slotIndex < API_SLOT_COUNT) {
            // Card grid → player inventory
            if (!moveItemStackTo(stack, API_SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            // Player inventory → card grid (only if the item is a valid ProcessingSet)
            if (stack.item is ProcessingSet) {
                if (!moveItemStackTo(stack, 0, API_SLOT_COUNT, false)) return ItemStack.EMPTY
            } else {
                return ItemStack.EMPTY
            }
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = storageInventory.stillValid(player)
}
