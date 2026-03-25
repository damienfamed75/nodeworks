package damien.nodeworks.registry

import damien.nodeworks.screen.NodeSideScreenHandler
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.inventory.MenuType

object ModScreenHandlers {

    val NODE_SIDE: MenuType<NodeSideScreenHandler> = register(
        "node_side",
        ExtendedScreenHandlerType(
            { syncId, playerInventory, sideOrdinal ->
                NodeSideScreenHandler.clientFactory(syncId, playerInventory, sideOrdinal)
            },
            ByteBufCodecs.INT
        )
    )

    private fun <T : net.minecraft.world.inventory.AbstractContainerMenu> register(
        id: String,
        type: MenuType<T>
    ): MenuType<T> {
        val key = ResourceKey.create(
            Registries.MENU,
            Identifier.fromNamespaceAndPath("nodeworks", id)
        )
        return Registry.register(BuiltInRegistries.MENU, key, type)
    }

    fun initialize() {
        // Triggers class loading
    }
}
