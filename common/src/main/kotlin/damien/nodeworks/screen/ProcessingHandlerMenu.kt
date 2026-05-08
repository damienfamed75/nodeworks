package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/**
 * Settings menu for the Processing Handler. Slot-less; the GUI's state lives
 * on the BE and re-renders against the client-side BE on each frame, so the
 * menu only needs to exist for [PlatformServices.menu.openExtendedMenu] to
 * route the [ProcessingHandlerOpenData] payload to the screen.
 */
class ProcessingHandlerMenu(
    syncId: Int,
    val devicePos: BlockPos,
) : AbstractContainerMenu(ModScreenHandlers.PROCESSING_HANDLER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    /** Snapshot of unclaimed Processing Sets on the parent network at the
     *  moment the GUI was opened. */
    var availableSets: List<ProcessingHandlerOpenData.AvailableSet> = emptyList()

    /** The bound set's full recipe (inputs + outputs with counts) when the
     *  Handler is bound and the Set is on the network. Null otherwise. The
     *  screen renders the recipe-strip + Outputs scrollbox from this. */
    var boundSet: ProcessingHandlerOpenData.AvailableSet? = null

    /** Open-time flag: true when the BE's bound set wasn't found on the
     *  parent network at open time. The screen surfaces a warning. */
    var boundSetMissing: Boolean = false

    /** Replace the open-time snapshot with a fresh server-pushed payload.
     *  Called when a [ProcessingHandlerStateSyncPayload] arrives, e.g. after
     *  a bind/unbind. Lets the screen re-render the recipe panel + picker
     *  list against the latest parent-network state without a close/reopen. */
    fun applyStateSync(data: ProcessingHandlerOpenData) {
        availableSets = data.available
        boundSet = data.boundSet
        boundSetMissing = data.boundSetMissing
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ProcessingHandlerOpenData): ProcessingHandlerMenu {
            val menu = ProcessingHandlerMenu(syncId, openData.pos)
            menu.availableSets = openData.available
            menu.boundSet = openData.boundSet
            menu.boundSetMissing = openData.boundSetMissing
            return menu
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: ProcessingHandlerBlockEntity): ProcessingHandlerMenu {
            return ProcessingHandlerMenu(syncId, entity.blockPos)
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(devicePos, 8.0)
}
