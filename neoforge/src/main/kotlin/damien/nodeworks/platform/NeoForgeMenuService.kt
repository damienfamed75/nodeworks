package damien.nodeworks.platform

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

class NeoForgeMenuService : MenuService {
    override fun <D : Any> openExtendedMenu(
        player: ServerPlayer,
        title: Component,
        data: D,
        codec: StreamCodec<in FriendlyByteBuf, D>,
        menuFactory: (Int, Inventory, Player) -> AbstractContainerMenu
    ) {
        player.openMenu(object : MenuProvider {
            override fun getDisplayName(): Component = title
            override fun createMenu(syncId: Int, inv: Inventory, p: Player): AbstractContainerMenu {
                return menuFactory(syncId, inv, p)
            }
        }) { buf -> codec.encode(buf, data) }
    }
}
