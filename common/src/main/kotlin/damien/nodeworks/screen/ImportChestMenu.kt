package damien.nodeworks.screen

import damien.nodeworks.block.entity.ImportChestBlockEntity
import damien.nodeworks.network.ChannelFilter
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

/**
 * Server-and-client menu for the Import Chest. Backs nine slots from the BE's
 * own inventory, exposes settings (channel / redstone / roundRobin / tickInterval)
 * via [ContainerData] so the screen can drive setters through the standard
 * menu data-sync path. Setter writes flow back to the BE through
 * [serverWriter] (set in [createServer]); the client menu's writer is a no-op
 * since the screen sends its own update packet for non-data-slot mutations.
 */
class ImportChestMenu(
    syncId: Int,
    val devicePos: BlockPos,
    private val chestInventory: Container,
    playerInventory: Inventory,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS),
) : AbstractContainerMenu(ModScreenHandlers.IMPORT_CHEST, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val DATA_SLOTS = 4
        private const val DATA_CHANNEL = 0
        private const val DATA_REDSTONE = 1
        private const val DATA_ROUND_ROBIN = 2
        private const val DATA_TICK_INTERVAL = 3

        const val SLOT_COUNT = ImportChestBlockEntity.SLOT_COUNT
        // Slot offsets within the screen frame. Top-panel chest grid sits under the
        // title bar, player inventory is in a *separate* lower panel (matching
        // [ProcessingSetScreen]'s split). Mirrored in [ImportChestScreen]'s
        // backplate draw, keep in sync if either side moves.
        const val GRID_X = 8
        const val GRID_Y = 22
        const val INV_X = 8
        const val INV_Y = 102
        const val HOTBAR_Y = 160
        // Slot rectangles offset inside the player-inventory backplate so they
        // land in the slot wells rather than flush with the backplate's outer
        // border. Split into X / Y for symmetry with the chest grid offset
        // even though they currently match.
        const val SLOT_INSET_X = 1
        const val SLOT_INSET_Y = 1

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ImportChestOpenData): ImportChestMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(DATA_CHANNEL, openData.channelNbtInt)
            data.set(DATA_REDSTONE, openData.redstoneMode)
            data.set(DATA_ROUND_ROBIN, if (openData.roundRobin) 1 else 0)
            data.set(DATA_TICK_INTERVAL, openData.tickInterval)
            val dummy = SimpleContainer(SLOT_COUNT)
            return ImportChestMenu(syncId, openData.pos, dummy, playerInventory, data)
        }

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: ImportChestBlockEntity,
        ): ImportChestMenu {
            // Drives the open sound + lid-open block-event broadcast.
            // Paired with chestInventory.stopOpen() in removed() below.
            entity.startOpen(playerInventory.player)
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    DATA_CHANNEL -> entity.channel.toNbtInt()
                    DATA_REDSTONE -> entity.redstoneMode
                    DATA_ROUND_ROBIN -> if (entity.roundRobin) 1 else 0
                    DATA_TICK_INTERVAL -> entity.tickInterval
                    else -> 0
                }
                override fun set(index: Int, value: Int) {
                    // Setter is server-side only, the client never writes this map.
                    // Client sends mutation requests via dedicated payloads.
                }
                override fun getCount(): Int = DATA_SLOTS
            }
            return ImportChestMenu(syncId, entity.blockPos, entity, playerInventory, data)
        }
    }

    val channelFilter: ChannelFilter get() = ChannelFilter.fromNbtInt(data.get(DATA_CHANNEL))
    val redstoneMode: Int get() = data.get(DATA_REDSTONE)
    val roundRobin: Boolean get() = data.get(DATA_ROUND_ROBIN) != 0
    val tickInterval: Int get() = data.get(DATA_TICK_INTERVAL)

    init {
        // Nine chest slots in a single row.
        for (col in 0 until SLOT_COUNT) {
            addSlot(Slot(chestInventory, col, GRID_X + col * 18, GRID_Y))
        }
        // Player inventory (3 rows of 9 + 1 hotbar row of 9).
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, INV_X + SLOT_INSET_X + col * 18, INV_Y + SLOT_INSET_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, INV_X + SLOT_INSET_X + col * 18, HOTBAR_Y + SLOT_INSET_Y))
        }
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (slotIndex < SLOT_COUNT) {
            // Chest grid → player inventory.
            if (!moveItemStackTo(stack, SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            // Player inventory → chest grid.
            if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = chestInventory.stillValid(player)

    // Client side, chestInventory is a SimpleContainer dummy whose default
    // stopOpen is a no-op. Only the server BE actually decrements.
    override fun removed(player: Player) {
        super.removed(player)
        chestInventory.stopOpen(player)
    }
}
