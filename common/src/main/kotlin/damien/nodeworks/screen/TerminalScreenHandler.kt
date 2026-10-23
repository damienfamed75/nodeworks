package damien.nodeworks.screen

import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
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
    private val knownTags: List<String> = emptyList(),
    /** Inventory slot of the Portable Scripting Terminal being edited, -1 when this menu is the block. */
    private val portableSlot: Int = -1,
    private val scriptName: String = "",
    private val loadedSlot: Int = -1,
    private val loadedName: String = "",
) : AbstractContainerMenu(ModScreenHandlers.TERMINAL, syncId), BlockBackedMenu {

    /** Null for the portable form so the block-break listener leaves it alone. */
    override val blockBackingPos: BlockPos? get() = if (portableSlot >= 0) null else terminalPos

    companion object {
        const val DATA_SLOTS = 1 // slot 0 = running (0 or 1)

        /** Server side of a Portable Scripting Terminal: no block, no runtime, the draft comes off the item. */
        fun createPortable(syncId: Int, slot: Int, draft: damien.nodeworks.item.ScriptDraft): TerminalScreenHandler =
            TerminalScreenHandler(
                syncId, BlockPos.ZERO, draft.tabs, SimpleContainerData(DATA_SLOTS), false, draft.layoutIndex,
                portableSlot = slot, scriptName = draft.name, loadedSlot = draft.loadedSlot, loadedName = draft.loadedName,
            )

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: TerminalOpenData): TerminalScreenHandler {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, if (openData.running) 1 else 0)
            return TerminalScreenHandler(
                syncId, openData.terminalPos, openData.scripts, data,
                openData.autoRun, openData.layoutIndex, openData.remoteApis, openData.knownTags,
                openData.portableSlot, openData.scriptName, openData.loadedSlot, openData.loadedName,
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
    fun getKnownTags(): List<String> = knownTags
    fun isPortable(): Boolean = portableSlot >= 0
    fun getPortableSlot(): Int = portableSlot
    fun getScriptName(): String = scriptName
    fun getLoadedSlot(): Int = loadedSlot
    fun getLoadedName(): String = loadedName

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        if (portableSlot >= 0) return damien.nodeworks.item.PortableScriptingTerminalItem.stackIn(player, portableSlot) != null
        return player.distanceToSqr(
            terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5
        ) <= 64.0
    }
}
