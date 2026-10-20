package damien.nodeworks.screen

import damien.nodeworks.block.entity.MultiProcessingHandlerBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/**
 * Settings menu for the Multi-Processing Handler. Slot-less like
 * [ProcessingHandlerMenu]: the bound list and channels are read live from the client
 * block entity, the menu only carries the set list the server built at open time and
 * refreshes it when a state sync arrives.
 */
class MultiProcessingHandlerMenu(
    syncId: Int,
    val devicePos: BlockPos,
) : AbstractContainerMenu(ModScreenHandlers.MULTI_PROCESSING_HANDLER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    var sets: List<MultiProcessingHandlerOpenData.SetEntry> = emptyList()
        private set
    var maxSets: Int = 0
        private set
    /** The server's bound list at the last sync, for a client whose block entity
     *  has not caught up yet. */
    var bound: List<String> = emptyList()
        private set

    /** Bumped on every sync so the screen rebuilds its rows only then. */
    var version: Int = 0
        private set

    fun applyStateSync(data: MultiProcessingHandlerOpenData) {
        sets = data.sets
        maxSets = data.maxSets
        bound = data.bound
        version++
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: MultiProcessingHandlerOpenData): MultiProcessingHandlerMenu {
            val menu = MultiProcessingHandlerMenu(syncId, openData.pos)
            menu.applyStateSync(openData)
            return menu
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: MultiProcessingHandlerBlockEntity): MultiProcessingHandlerMenu =
            MultiProcessingHandlerMenu(syncId, entity.blockPos)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(devicePos, 8.0)
}
