package damien.nodeworks.screen

import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.script.CraftTreeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

class DiagnosticMenu(
    syncId: Int,
    val clickedPos: BlockPos,
    val topology: DiagnosticOpenData
) : AbstractContainerMenu(ModScreenHandlers.DIAGNOSTIC, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = clickedPos

    /** Craft tree received from server, updated via S2C packet. */
    var craftTree: CraftTreeBuilder.CraftTreeNode? = null

    /** Live errors from network terminals, populated by fanned-out TerminalLogPayloads. */
    data class DiagnosticError(val terminalPos: net.minecraft.core.BlockPos, val message: String, val timestamp: Long)
    val liveErrors = mutableListOf<DiagnosticError>()
    private val maxErrors = 50

    fun addError(terminalPos: net.minecraft.core.BlockPos, message: String) {
        liveErrors.add(0, DiagnosticError(terminalPos, message, System.currentTimeMillis()))
        if (liveErrors.size > maxErrors) liveErrors.removeLast()
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: DiagnosticOpenData): DiagnosticMenu {
            val menu = DiagnosticMenu(syncId, openData.networkPos, openData)
            // Pre-populate errors from server-side history
            val now = System.currentTimeMillis()
            for (err in openData.recentErrors) {
                val ageMs = err.tickAge * 50L // ~50ms per tick
                menu.liveErrors.add(DiagnosticError(err.terminalPos, err.message, now - ageMs))
            }
            return menu
        }

        fun createServer(syncId: Int, clickedPos: BlockPos): DiagnosticMenu {
            return DiagnosticMenu(syncId, clickedPos, DiagnosticOpenData(emptyList(), "", 0xFFFFFF, clickedPos))
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(clickedPos, 16.0)
    }
}
