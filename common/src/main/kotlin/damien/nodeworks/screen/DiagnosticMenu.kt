package damien.nodeworks.screen

import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

class DiagnosticMenu(
    syncId: Int,
    val clickedPos: BlockPos,
    val topology: DiagnosticOpenData
) : AbstractContainerMenu(ModScreenHandlers.DIAGNOSTIC, syncId) {

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: DiagnosticOpenData): DiagnosticMenu {
            return DiagnosticMenu(syncId, BlockPos.ZERO, openData)
        }

        fun createServer(syncId: Int, clickedPos: BlockPos): DiagnosticMenu {
            return DiagnosticMenu(syncId, clickedPos, DiagnosticOpenData(emptyList(), "", 0xFFFFFF))
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(clickedPos, 16.0)
    }
}
