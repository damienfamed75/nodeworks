package damien.nodeworks.screen

import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import damien.nodeworks.item.LinkCrystalItem
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

class ReceiverAntennaMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val antennaInventory: Container,
    val antennaPos: BlockPos,
    private val data: ContainerData = SimpleContainerData(1)
) : AbstractContainerMenu(ModScreenHandlers.RECEIVER_ANTENNA, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = antennaPos

    /** 0=not linked, 1=linked, 2=out of range, 3=broadcast not found, 4=freq mismatch, 5=not loaded */
    val statusCode: Int get() = data.get(0)

    companion object {
        fun createServer(syncId: Int, playerInventory: Inventory, entity: ReceiverAntennaBlockEntity, pos: BlockPos): ReceiverAntennaMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int {
                    val level = entity.level as? net.minecraft.server.level.ServerLevel ?: return 0
                    return entity.getConnectionStatus(level)
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = 1
            }
            return ReceiverAntennaMenu(syncId, playerInventory, entity, pos, data)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ReceiverAntennaOpenData): ReceiverAntennaMenu {
            val data = SimpleContainerData(1)
            data.set(0, openData.statusCode)
            return ReceiverAntennaMenu(syncId, playerInventory, SimpleContainer(1), openData.pos, data)
        }
    }

    init {
        // Chip slot, centered
        addSlot(ChipSlot(antennaInventory, 0, 81, 37))

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

        addDataSlots(data)
    }

    private class ChipSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean =
            stack.item is LinkCrystalItem && LinkCrystalItem.isEncoded(stack)
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
            if (stack.item is LinkCrystalItem && LinkCrystalItem.isEncoded(stack)) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY
            } else return ItemStack.EMPTY
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = antennaInventory.stillValid(player)
}
