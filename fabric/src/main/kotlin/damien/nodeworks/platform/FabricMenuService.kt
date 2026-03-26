package damien.nodeworks.platform

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

class FabricMenuService : MenuService {
    override fun <D : Any> openExtendedMenu(
        player: ServerPlayer,
        title: Component,
        data: D,
        codec: StreamCodec<in FriendlyByteBuf, D>,
        menuFactory: (Int, Inventory, Player) -> AbstractContainerMenu
    ) {
        player.openMenu(object : ExtendedScreenHandlerFactory<D> {
            override fun getScreenOpeningData(player: ServerPlayer): D = data

            override fun getDisplayName(): Component = title

            override fun createMenu(syncId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu {
                return menuFactory(syncId, playerInventory, player)
            }
        })
    }
}
