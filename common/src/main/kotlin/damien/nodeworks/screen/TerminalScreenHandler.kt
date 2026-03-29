package damien.nodeworks.screen

import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

class TerminalScreenHandler(
    syncId: Int,
    private val terminalPos: BlockPos,
    private val scripts: Map<String, String>,
    private val running: Boolean,
    private val autoRun: Boolean,
    private val layoutIndex: Int
) : AbstractContainerMenu(ModScreenHandlers.TERMINAL, syncId) {

    companion object {
        fun createServer(syncId: Int, player: Player, terminal: TerminalBlockEntity): TerminalScreenHandler {
            val level = player.level() as? ServerLevel
            val isRunning = if (level != null) PlatformServices.modState.isScriptRunning(level, terminal.blockPos) else false
            return TerminalScreenHandler(syncId, terminal.blockPos, terminal.getScriptsCopy(), isRunning, terminal.autoRun, terminal.layoutIndex)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: TerminalOpenData): TerminalScreenHandler {
            return TerminalScreenHandler(syncId, data.terminalPos, data.scripts, data.running, data.autoRun, data.layoutIndex)
        }
    }

    fun getTerminalPos(): BlockPos = terminalPos
    fun getScripts(): Map<String, String> = scripts
    fun getScriptText(): String = scripts["main"] ?: ""
    fun isRunning(): Boolean = running
    fun isAutoRun(): Boolean = autoRun
    fun getLayoutIndex(): Int = layoutIndex

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(
            terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5
        ) <= 64.0
    }
}
