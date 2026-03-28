package damien.nodeworks.screen

import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
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
    private val layoutIndex: Int,
    private val cards: List<CardSnapshot>,
    private val itemTags: List<String>,
    private val variables: List<Pair<String, Int>> = emptyList()
) : AbstractContainerMenu(ModScreenHandlers.TERMINAL, syncId) {

    companion object {
        fun createServer(syncId: Int, player: Player, terminal: TerminalBlockEntity): TerminalScreenHandler {
            val level = player.level() as? ServerLevel
            val nodePos = terminal.getConnectedNodePos()

            val snapshot = if (level != null && nodePos != null) NetworkDiscovery.discoverNetwork(level, nodePos) else null
            val cards = snapshot?.allCards() ?: emptyList()
            val varNames = snapshot?.variables?.map { it.name to it.type.ordinal } ?: emptyList()

            val isRunning = if (level != null) PlatformServices.modState.isScriptRunning(level, terminal.blockPos) else false

            val tags = if (level != null) {
                net.minecraft.core.registries.BuiltInRegistries.ITEM.tags
                    .map { it.key().location().toString() }
                    .sorted()
                    .toList()
            } else emptyList()

            return TerminalScreenHandler(syncId, terminal.blockPos, terminal.getScripts(), isRunning, terminal.autoRun, terminal.layoutIndex, cards, tags, varNames)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: TerminalOpenData): TerminalScreenHandler {
            return TerminalScreenHandler(syncId, data.terminalPos, data.scripts, data.running, data.autoRun, data.layoutIndex, data.cards, data.itemTags, data.variables)
        }
    }

    fun getTerminalPos(): BlockPos = terminalPos
    fun getScripts(): Map<String, String> = scripts
    fun getScriptText(): String = scripts["main"] ?: ""
    fun isRunning(): Boolean = running
    fun isAutoRun(): Boolean = autoRun
    fun getLayoutIndex(): Int = layoutIndex
    fun getCards(): List<CardSnapshot> = cards
    fun getItemTags(): List<String> = itemTags
    fun getVariables(): List<Pair<String, Int>> = variables

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(
            terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5
        ) <= 64.0
    }
}
