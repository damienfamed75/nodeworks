package damien.nodeworks.screen

import damien.nodeworks.block.entity.ExportChestBlockEntity
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
 * Server-and-client menu for the Export Chest. Mirrors [ImportChestMenu] but
 * exposes the export-specific settings via [ContainerData] for the int
 * fields (pushFace ordinal, filter-mode ordinal, channel NBT int, redstone,
 * tickInterval) and seeds the rule-list string from [ExportChestOpenData] at
 * open. Filter rule edits flow back to the BE via
 * [damien.nodeworks.network.SetExportChestFilterRulesPayload].
 */
class ExportChestMenu(
    syncId: Int,
    val devicePos: BlockPos,
    private val chestInventory: Container,
    playerInventory: Inventory,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS),
    initialFilterRules: List<String> = emptyList(),
) : AbstractContainerMenu(ModScreenHandlers.EXPORT_CHEST, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    var filterRules: List<String> = initialFilterRules
        private set

    fun applyFilterRulesFromServer(rules: List<String>) {
        filterRules = rules.toList()
    }

    companion object {
        const val DATA_SLOTS = 4
        private const val DATA_PUSH_FACE = 0
        private const val DATA_CHANNEL = 1
        private const val DATA_REDSTONE = 2
        private const val DATA_TICK_INTERVAL = 3

        const val SLOT_COUNT = ExportChestBlockEntity.SLOT_COUNT
        const val GRID_X = 8
        const val GRID_Y = 22
        const val INV_X = 8
        // Inv panel sits below the rule list panel in the GUI (INV_PANEL_Y=144
        // in the screen with a 3-row rule panel), so the slot grid starts at
        // INV_PANEL_Y + 14 = 158. Hotbar = 3 inv rows + HOTBAR_GAP =
        // 158 + 54 + 4 = 216.
        const val INV_Y = 158
        const val HOTBAR_Y = 216
        // Slot rectangles offset inside the player-inventory backplate so they
        // land in the slot wells rather than flush with the backplate's outer
        // border. Split into X / Y for symmetry with the chest grid offset
        // even though they currently match.
        const val SLOT_INSET_X = 1
        const val SLOT_INSET_Y = 1

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ExportChestOpenData): ExportChestMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(DATA_PUSH_FACE, openData.pushFaceOrdinal)
            data.set(DATA_CHANNEL, openData.channelNbtInt)
            data.set(DATA_REDSTONE, openData.redstoneMode)
            data.set(DATA_TICK_INTERVAL, openData.tickInterval)
            val dummy = SimpleContainer(SLOT_COUNT)
            return ExportChestMenu(syncId, openData.pos, dummy, playerInventory, data, openData.filterRules)
        }

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: ExportChestBlockEntity,
        ): ExportChestMenu {
            entity.startOpen(playerInventory.player)
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    DATA_PUSH_FACE -> entity.pushFace?.ordinal ?: -1
                    DATA_CHANNEL -> entity.channel.toNbtInt()
                    DATA_REDSTONE -> entity.redstoneMode
                    DATA_TICK_INTERVAL -> entity.tickInterval
                    else -> 0
                }
                override fun set(index: Int, value: Int) {
                    // Server-only, mutations come via DeviceSettingsPayload (ints)
                    // and SetExportChestFilterRulesPayload (the rule list).
                }
                override fun getCount(): Int = DATA_SLOTS
            }
            return ExportChestMenu(syncId, entity.blockPos, entity, playerInventory, data, entity.filterRules)
        }
    }

    val pushFace: Direction?
        get() {
            val ord = data.get(DATA_PUSH_FACE)
            return if (ord < 0) null else Direction.entries.getOrNull(ord)
        }

    val channel: ChannelFilter
        get() = ChannelFilter.fromNbtInt(data.get(DATA_CHANNEL))

    val redstoneMode: Int get() = data.get(DATA_REDSTONE)
    val tickInterval: Int get() = data.get(DATA_TICK_INTERVAL)

    init {
        for (col in 0 until SLOT_COUNT) {
            addSlot(Slot(chestInventory, col, GRID_X + col * 18, GRID_Y))
        }
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
            if (!moveItemStackTo(stack, SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = chestInventory.stillValid(player)

    override fun removed(player: Player) {
        super.removed(player)
        chestInventory.stopOpen(player)
    }
}
