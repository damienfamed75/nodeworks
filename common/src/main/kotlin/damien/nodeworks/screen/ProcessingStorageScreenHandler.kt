package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.card.ProcessingSet
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

class ProcessingStorageScreenHandler(
    syncId: Int,
    playerInventory: Inventory,
    private val storageInventory: Container,
    private val storagePos: BlockPos,
    private val data: ContainerData = SimpleContainerData(1)
) : AbstractContainerMenu(ModScreenHandlers.PROCESSING_STORAGE, syncId) {

    companion object {
        const val API_SLOT_COUNT = 36
        const val UPGRADE_SLOT_INDEX = 36
        const val STORAGE_SLOT_COUNT = 37

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ProcessingStorageOpenData): ProcessingStorageScreenHandler {
            val dummy = SimpleContainer(ProcessingStorageBlockEntity.TOTAL_SLOTS)
            return ProcessingStorageScreenHandler(syncId, playerInventory, dummy, openData.pos)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: ProcessingStorageBlockEntity, pos: BlockPos): ProcessingStorageScreenHandler {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.upgradeLevel
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = 1
            }
            return ProcessingStorageScreenHandler(syncId, playerInventory, entity, pos, data)
        }
    }

    val upgradeLevel: Int get() = data.get(0)

    init {
        // 36 API card slots — 4 rows of 9
        for (row in 0..3) {
            for (col in 0..8) {
                val slotIndex = row * 9 + col
                addSlot(ApiCardSlot(storageInventory, slotIndex, 8 + col * 18, 18 + row * 18, this))
            }
        }

        // Upgrade slot (slot 36) — only accepts MemoryUpgradeItem
        addSlot(UpgradeSlot(storageInventory, UPGRADE_SLOT_INDEX, 152, 90))

        // Player inventory (3 rows)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 114 + row * 18))
            }
        }
        // Player hotbar
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 114 + 58))
        }

        addDataSlots(data)
    }

    fun isSlotActive(slotIndex: Int): Boolean {
        if (slotIndex >= API_SLOT_COUNT) return true
        val activeCount = when (upgradeLevel) {
            0 -> ProcessingStorageBlockEntity.BASE_SLOTS
            1 -> ProcessingStorageBlockEntity.UPGRADE_1_SLOTS
            2 -> ProcessingStorageBlockEntity.UPGRADE_2_SLOTS
            3 -> ProcessingStorageBlockEntity.UPGRADE_3_SLOTS
            else -> ProcessingStorageBlockEntity.UPGRADE_4_SLOTS
        }
        return slotIndex < activeCount
    }

    private class ApiCardSlot(
        container: Container,
        index: Int,
        x: Int,
        y: Int,
        private val handler: ProcessingStorageScreenHandler
    ) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean {
            return stack.item is ProcessingSet && handler.isSlotActive(index)
        }
    }

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
            if (!moveItemStackTo(stack, STORAGE_SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (stack.item is ProcessingSet) {
                if (!moveItemStackTo(stack, 0, API_SLOT_COUNT, false)) return ItemStack.EMPTY
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
