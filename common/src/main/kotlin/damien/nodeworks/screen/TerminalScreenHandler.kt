package damien.nodeworks.screen

import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

class TerminalScreenHandler(
    syncId: Int,
    private val terminalPos: BlockPos,
    private val scripts: Map<String, String>,
    private val data: ContainerData,
    private val autoRun: Boolean,
    private val layoutIndex: Int,
    /** Cross-dim / remote Processing APIs the server pre-resolved at open-time. Client-
     *  side autocomplete folds these into its scanned list so `network:craft("...")`
     *  can suggest remote recipes the client otherwise couldn't see. */
    private val remoteApis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo> = emptyList(),
) : AbstractContainerMenu(ModScreenHandlers.TERMINAL, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = terminalPos

    companion object {
        const val DATA_SLOTS = 1 // slot 0 = running (0 or 1)

        fun createServer(syncId: Int, player: Player, terminal: TerminalBlockEntity): TerminalScreenHandler {
            val level = player.level() as? ServerLevel
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> if (level != null && PlatformServices.modState.isScriptRunning(level, terminal.blockPos)) 1 else 0
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return TerminalScreenHandler(syncId, terminal.blockPos, terminal.getScriptsCopy(), data, terminal.autoRun, terminal.layoutIndex)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: TerminalOpenData): TerminalScreenHandler {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, if (openData.running) 1 else 0)
            return TerminalScreenHandler(
                syncId, openData.terminalPos, openData.scripts, data,
                openData.autoRun, openData.layoutIndex, openData.remoteApis,
            )
        }
    }

    init {
        addDataSlots(data)
    }

    fun getTerminalPos(): BlockPos = terminalPos
    fun getScripts(): Map<String, String> = scripts
    fun getScriptText(): String = scripts["main"] ?: ""
    fun isRunning(): Boolean = data.get(0) != 0
    fun isAutoRun(): Boolean = autoRun
    fun getLayoutIndex(): Int = layoutIndex
    fun getRemoteApis(): List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo> = remoteApis

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(
            terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5
        ) <= 64.0
    }
}
