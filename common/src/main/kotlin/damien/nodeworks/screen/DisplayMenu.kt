package damien.nodeworks.screen

import damien.nodeworks.block.entity.DisplayBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/**
 * Settings menu for the Display block. Slot-less, the only field is the
 * display name, which seeds from [DisplayOpenData] and commits via
 * [damien.nodeworks.network.DeviceSettingsPayload] (`key = "name"`).
 */
class DisplayMenu(
    syncId: Int,
    val devicePos: BlockPos,
    val initialName: String,
) : AbstractContainerMenu(ModScreenHandlers.DISPLAY, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: DisplayOpenData): DisplayMenu =
            DisplayMenu(syncId, openData.pos, openData.displayName)

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: DisplayBlockEntity,
        ): DisplayMenu = DisplayMenu(syncId, entity.blockPos, entity.clusterName())
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(devicePos, 8.0)
}
