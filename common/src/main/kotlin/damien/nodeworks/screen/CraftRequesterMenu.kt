package damien.nodeworks.screen

import damien.nodeworks.block.entity.CraftRequesterBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Settings menu for the Craft Requester. One ghost slot for the target item,
 * live-syncing data slots for batchSize and channel, and standard player
 * inventory. Error lines are read off the client BE directly each frame.
 */
class CraftRequesterMenu(
    syncId: Int,
    val devicePos: BlockPos,
    private val targetContainer: Container,
    playerInventory: Inventory,
    private val data: ContainerData,
) : AbstractContainerMenu(ModScreenHandlers.CRAFT_REQUESTER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val DATA_SLOTS = 2
        private const val DATA_BATCH = 0
        private const val DATA_CHANNEL = 1

        private const val GHOST_SLOT_INDEX = 0

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: CraftRequesterOpenData): CraftRequesterMenu {
            val container = SimpleContainer(1).also { it.setItem(0, openData.target) }
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(DATA_BATCH, openData.batchSize)
            data.set(DATA_CHANNEL, openData.channelId)
            return CraftRequesterMenu(syncId, openData.pos, container, playerInventory, data)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: CraftRequesterBlockEntity): CraftRequesterMenu {
            val container = object : SimpleContainer(1) {
                override fun getItem(slot: Int): ItemStack = entity.target
                override fun setItem(slot: Int, stack: ItemStack) {
                    entity.target = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
                }
                override fun setChanged() { entity.setChanged() }
            }
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    DATA_BATCH -> entity.batchSize
                    DATA_CHANNEL -> entity.outputChannel.toNbtInt()
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return CraftRequesterMenu(syncId, entity.blockPos, container, playerInventory, data)
        }
    }

    val batchSize: Int get() = data.get(DATA_BATCH)
    val channelId: Int get() = data.get(DATA_CHANNEL)

    init {
        addDataSlots(data)
        addSlot(GhostSlot(targetContainer, 0, CraftRequesterScreen.SLOT_X, CraftRequesterScreen.SLOT_Y))

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(
                    playerInventory,
                    col + row * 9 + 9,
                    CraftRequesterScreen.INV_MAIN_SLOT_X + col * 18,
                    CraftRequesterScreen.INV_MAIN_SLOT_Y + row * 18,
                ))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(
                playerInventory,
                col,
                CraftRequesterScreen.INV_MAIN_SLOT_X + col * 18,
                CraftRequesterScreen.INV_HOTBAR_SLOT_Y,
            ))
        }
    }

    private class GhostSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun getMaxStackSize(): Int = 1
    }

    override fun clicked(slotId: Int, button: Int, clickType: ContainerInput, player: Player) {
        if (slotId == GHOST_SLOT_INDEX) {
            val cursor = carried
            if (cursor.isEmpty) {
                targetContainer.setItem(0, ItemStack.EMPTY)
            } else {
                targetContainer.setItem(0, cursor.copyWithCount(1))
            }
            broadcastChanges()
            return
        }
        super.clicked(slotId, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        if (index <= GHOST_SLOT_INDEX) return ItemStack.EMPTY
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        targetContainer.setItem(0, slot.item.copyWithCount(1))
        broadcastChanges()
        return ItemStack.EMPTY
    }

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(devicePos, 8.0)
}
