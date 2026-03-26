package damien.nodeworks.screen

import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.TerminalPackets
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
    private val scriptText: String,
    private val running: Boolean,
    private val autoRun: Boolean,
    private val layoutIndex: Int,
    private val cards: List<CardSnapshot>
) : AbstractContainerMenu(ModScreenHandlers.TERMINAL, syncId) {

    companion object {
        fun createServer(syncId: Int, player: Player, terminal: TerminalBlockEntity): TerminalScreenHandler {
            val level = player.level() as? ServerLevel
            val nodePos = terminal.getConnectedNodePos()

            val cards = if (level != null && nodePos != null) {
                val snapshot = NetworkDiscovery.discoverNetwork(level, nodePos)
                snapshot.allCards()
            } else emptyList()

            val isRunning = TerminalPackets.getEngine(terminal.blockPos)?.isRunning() == true

            return TerminalScreenHandler(syncId, terminal.blockPos, terminal.scriptText, isRunning, terminal.autoRun, terminal.layoutIndex, cards)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: TerminalOpenData): TerminalScreenHandler {
            return TerminalScreenHandler(syncId, data.terminalPos, data.scriptText, data.running, data.autoRun, data.layoutIndex, data.cards)
        }
    }

    fun getTerminalPos(): BlockPos = terminalPos
    fun getScriptText(): String = scriptText
    fun isRunning(): Boolean = running
    fun isAutoRun(): Boolean = autoRun
    fun getLayoutIndex(): Int = layoutIndex
    fun getCards(): List<CardSnapshot> = cards

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(
            terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5
        ) <= 64.0
    }
}
