package damien.nodeworks.screen

import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.card.InstructionSet
import damien.nodeworks.item.MemoryUpgradeItem
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class InstructionStorageScreenHandler(
    syncId: Int,
    playerInventory: Inventory,
    private val storageInventory: Container,
    private val storagePos: BlockPos,
    private val data: ContainerData = SimpleContainerData(1)
) : AbstractContainerMenu(ModScreenHandlers.INSTRUCTION_STORAGE, syncId) {

    companion object {
        const val INSTRUCTION_SLOT_COUNT = 36
        const val UPGRADE_SLOT_INDEX = 36
        const val STORAGE_SLOT_COUNT = 37

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: InstructionStorageOpenData): InstructionStorageScreenHandler {
            val dummy = SimpleContainer(InstructionStorageBlockEntity.TOTAL_SLOTS)
            return InstructionStorageScreenHandler(syncId, playerInventory, dummy, openData.pos)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: InstructionStorageBlockEntity, pos: BlockPos): InstructionStorageScreenHandler {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.upgradeLevel
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = 1
            }
            return InstructionStorageScreenHandler(syncId, playerInventory, entity, pos, data)
        }
    }

    val upgradeLevel: Int get() = data.get(0)

    init {
        // 36 instruction set slots — 4 rows of 9
        for (row in 0..3) {
            for (col in 0..8) {
                val slotIndex = row * 9 + col
                addSlot(InstructionSlot(storageInventory, slotIndex, 8 + col * 18, 18 + row * 18, this))
            }
        }

        // Upgrade slot (slot 36) — only accepts MemoryUpgradeItem
        addSlot(UpgradeSlot(storageInventory, UPGRADE_SLOT_INDEX, 152, 90))

        // Player inventory (starts at y=114 to leave room for 4 rows + upgrade area)
        // Player inventory (3 rows)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(net.minecraft.world.inventory.Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 114 + row * 18))
            }
        }
        // Player hotbar
        for (col in 0 until 9) {
            addSlot(net.minecraft.world.inventory.Slot(playerInventory, col, 8 + col * 18, 114 + 58))
        }

        addDataSlots(data)
    }

    /** Whether a given instruction slot index is currently active (unlocked). */
    fun isSlotActive(slotIndex: Int): Boolean {
        if (slotIndex >= INSTRUCTION_SLOT_COUNT) return true
        val activeCount = when (upgradeLevel) {
            0 -> InstructionStorageBlockEntity.BASE_SLOTS
            1 -> InstructionStorageBlockEntity.UPGRADE_1_SLOTS
            2 -> InstructionStorageBlockEntity.UPGRADE_2_SLOTS
            3 -> InstructionStorageBlockEntity.UPGRADE_3_SLOTS
            else -> InstructionStorageBlockEntity.UPGRADE_4_SLOTS
        }
        return slotIndex < activeCount
    }

    /** Instruction slot — only accepts InstructionSet items, and only when the slot is active. */
    private class InstructionSlot(
        container: Container,
        index: Int,
        x: Int,
        y: Int,
        private val handler: InstructionStorageScreenHandler
    ) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean {
            return stack.item is InstructionSet
                && InstructionSet.getOutput(stack).isNotEmpty()
                && handler.isSlotActive(index)
        }
    }

    /** Upgrade slot — only accepts MemoryUpgradeItem. */
    private class UpgradeSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is MemoryUpgradeItem
        override fun getMaxStackSize(): Int = 4
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()

        if (slotIndex < STORAGE_SLOT_COUNT) {
            // From storage to player inventory
            if (!moveItemStackTo(stack, STORAGE_SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            // From player inventory to storage
            if (stack.item is InstructionSet) {
                if (!moveItemStackTo(stack, 0, INSTRUCTION_SLOT_COUNT, false)) return ItemStack.EMPTY
            } else if (stack.item is MemoryUpgradeItem) {
                if (!moveItemStackTo(stack, UPGRADE_SLOT_INDEX, UPGRADE_SLOT_INDEX + 1, false)) return ItemStack.EMPTY
            } else {
                return ItemStack.EMPTY
            }
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = storageInventory.stillValid(player)
}
