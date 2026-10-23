package damien.nodeworks.screen

import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.item.ScriptItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
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
 * Menu for the Script Runner: eight Script slots over the player inventory, with a
 * running and an enabled flag per slot. Button ids 0 to 7 toggle a slot, which also
 * starts or stops its script.
 */
class ScriptRunnerMenu(
    syncId: Int,
    val devicePos: BlockPos,
    private val container: Container,
    playerInventory: Inventory,
    private val data: ContainerData,
    /** Each slot's recorded fault at open time, empty for none. */
    val initialErrors: List<String>,
) : AbstractContainerMenu(ModScreenHandlers.SCRIPT_RUNNER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val SLOT_COUNT = TerminalBlockEntity.SLOT_COUNT
        const val ROWS = 4
        const val COLUMNS = 2
        const val DATA_SLOTS = SLOT_COUNT * 2
        const val ROW_X = 8
        const val ROW_Y = 20
        const val ROW_H = 18
        const val COL_W = 140
        const val FRAME_W = 296
        const val INV_X = (FRAME_W - 162) / 2
        const val INV_PANEL_Y = 132
        const val INV_Y = INV_PANEL_Y + 14
        const val HOTBAR_GAP = 4
        const val HOTBAR_Y = INV_Y + 3 * 18 + HOTBAR_GAP
        const val SLOT_INSET = 1

        fun slotX(slot: Int): Int = ROW_X + (slot / ROWS) * COL_W
        fun slotY(slot: Int): Int = ROW_Y + (slot % ROWS) * ROW_H

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ScriptRunnerOpenData): ScriptRunnerMenu =
            ScriptRunnerMenu(syncId, openData.pos, SimpleContainer(SLOT_COUNT), playerInventory, SimpleContainerData(DATA_SLOTS), openData.errors)

        fun createServer(syncId: Int, playerInventory: Inventory, runner: TerminalBlockEntity): ScriptRunnerMenu {
            val level = runner.level as? ServerLevel
            val data = object : ContainerData {
                override fun get(index: Int): Int = when {
                    index < SLOT_COUNT -> if (level != null && PlatformServices.modState.isScriptSlotRunning(level, runner.blockPos, index)) 1 else 0
                    index < DATA_SLOTS -> if (runner.isEnabled(index - SLOT_COUNT)) 1 else 0
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            val errors = List(SLOT_COUNT) { runner.lastError(it) ?: "" }
            return ScriptRunnerMenu(syncId, runner.blockPos, runner, playerInventory, data, errors)
        }
    }

    init {
        for (i in 0 until SLOT_COUNT) {
            addSlot(object : Slot(container, i, slotX(i) + SLOT_INSET, slotY(i) + SLOT_INSET) {
                override fun mayPlace(stack: ItemStack): Boolean = stack.item is ScriptItem
                override fun getMaxStackSize(): Int = 1
            })
        }
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, INV_X + SLOT_INSET + col * 18, INV_Y + SLOT_INSET + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, INV_X + SLOT_INSET + col * 18, HOTBAR_Y + SLOT_INSET))
        }
        addDataSlots(data)
    }

    fun isRunning(slot: Int): Boolean = slot in 0 until SLOT_COUNT && data.get(slot) != 0
    fun isEnabled(slot: Int): Boolean = slot in 0 until SLOT_COUNT && data.get(SLOT_COUNT + slot) != 0

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id !in 0 until SLOT_COUNT) return false
        val level = player.level() as? ServerLevel ?: return true
        val runner = level.getBlockEntity(devicePos) as? TerminalBlockEntity ?: return true
        val on = !runner.isEnabled(id)
        runner.setEnabled(id, on)
        if (on) PlatformServices.modState.startScriptSlot(level, devicePos, id)
        else PlatformServices.modState.stopScriptSlot(level, devicePos, id)
        return true
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (slotIndex < SLOT_COUNT) {
            if (!moveItemStackTo(stack, SLOT_COUNT, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (stack.item !is ScriptItem) return ItemStack.EMPTY
            if (!moveItemStackTo(stack, 0, SLOT_COUNT, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = container.stillValid(player)
}
