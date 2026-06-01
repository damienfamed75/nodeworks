package damien.nodeworks.screen

import damien.nodeworks.block.entity.StorageMeterBlockEntity
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
 * Settings menu for the Storage Meter. One ghost slot for the target item, plus
 * live-syncing data slots for channel, threshold, displayCount, and
 * isBelowThreshold so the screen can paint the current count + state without
 * a polling packet.
 */
class StorageMeterMenu(
    syncId: Int,
    val devicePos: BlockPos,
    private val targetContainer: Container,
    playerInventory: Inventory,
    private val data: ContainerData,
) : AbstractContainerMenu(ModScreenHandlers.STORAGE_METER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val DATA_SLOTS = 5
        private const val DATA_CHANNEL = 0
        private const val DATA_THRESHOLD = 1
        private const val DATA_DISPLAY_COUNT = 2
        private const val DATA_BELOW_THRESHOLD = 3
        private const val DATA_AUTOCRAFT = 4

        private const val GHOST_SLOT_INDEX = 0

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: StorageMeterOpenData): StorageMeterMenu {
            val container = SimpleContainer(1).also { it.setItem(0, openData.target) }
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(DATA_CHANNEL, openData.channelId)
            data.set(DATA_THRESHOLD, openData.threshold)
            data.set(DATA_DISPLAY_COUNT, 0)
            data.set(DATA_BELOW_THRESHOLD, 0)
            data.set(DATA_AUTOCRAFT, if (openData.autocraftEnabled) 1 else 0)
            return StorageMeterMenu(syncId, openData.pos, container, playerInventory, data)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: StorageMeterBlockEntity): StorageMeterMenu {
            // BE-backed container: writes go straight through to the BE so the
            // ghost slot stays in sync with the persisted target on click.
            val container = object : SimpleContainer(1) {
                override fun getItem(slot: Int): ItemStack = entity.target
                override fun setItem(slot: Int, stack: ItemStack) {
                    entity.target = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
                }
                override fun setChanged() { entity.setChanged() }
            }
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    DATA_CHANNEL -> entity.channel.toNbtInt()
                    DATA_THRESHOLD -> entity.threshold
                    DATA_DISPLAY_COUNT -> entity.displayCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    DATA_BELOW_THRESHOLD -> if (entity.isBelowThreshold) 1 else 0
                    DATA_AUTOCRAFT -> if (entity.autocraftEnabled) 1 else 0
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return StorageMeterMenu(syncId, entity.blockPos, container, playerInventory, data)
        }
    }

    val channelId: Int get() = data.get(DATA_CHANNEL)
    val threshold: Int get() = data.get(DATA_THRESHOLD)
    val displayCount: Int get() = data.get(DATA_DISPLAY_COUNT)
    val isBelowThreshold: Boolean get() = data.get(DATA_BELOW_THRESHOLD) != 0
    val autocraftEnabled: Boolean get() = data.get(DATA_AUTOCRAFT) != 0

    init {
        addDataSlots(data)
        addSlot(GhostSlot(targetContainer, 0, StorageMeterScreen.SLOT_X, StorageMeterScreen.SLOT_Y))

        // Player inventory main grid, slots 9..35.
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(
                    playerInventory,
                    col + row * 9 + 9,
                    StorageMeterScreen.INV_MAIN_SLOT_X + col * 18,
                    StorageMeterScreen.INV_MAIN_SLOT_Y + row * 18,
                ))
            }
        }
        // Hotbar, slots 0..8.
        for (col in 0 until 9) {
            addSlot(Slot(
                playerInventory,
                col,
                StorageMeterScreen.INV_MAIN_SLOT_X + col * 18,
                StorageMeterScreen.INV_HOTBAR_SLOT_Y,
            ))
        }
    }

    /** Single-stack-size ghost slot. Vanilla insertion is blocked, the
     *  custom click handler below sets the ghost item from the cursor stack
     *  without consuming it. */
    private class GhostSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun getMaxStackSize(): Int = 1
    }

    override fun clicked(slotId: Int, button: Int, clickType: ContainerInput, player: Player) {
        if (slotId == GHOST_SLOT_INDEX) {
            val cursor = carried
            if (cursor.isEmpty) {
                // Empty-handed click clears the ghost.
                targetContainer.setItem(0, ItemStack.EMPTY)
            } else {
                // Carry components (potion contents, custom name, enchantments)
                // so component-aware crafts resolve to the right variant.
                targetContainer.setItem(0, cursor.copyWithCount(1))
            }
            broadcastChanges()
            return
        }
        super.clicked(slotId, button, clickType, player)
    }

    /** Shift-click an inventory item to set it as the ghost target. The
     *  player's stack is not consumed, the ghost just learns its identity. */
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
