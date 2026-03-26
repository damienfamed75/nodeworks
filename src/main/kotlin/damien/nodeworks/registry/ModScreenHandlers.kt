package damien.nodeworks.registry

import damien.nodeworks.screen.NodeSideOpenData
import damien.nodeworks.screen.NodeSideScreenHandler
import damien.nodeworks.screen.RecipeCardOpenData
import damien.nodeworks.screen.RecipeCardScreenHandler
import damien.nodeworks.screen.TerminalOpenData
import damien.nodeworks.screen.TerminalScreenHandler
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.inventory.MenuType

object ModScreenHandlers {

    val TERMINAL: MenuType<TerminalScreenHandler> = register(
        "terminal",
        ExtendedScreenHandlerType(
            { syncId, playerInventory, data ->
                TerminalScreenHandler.clientFactory(syncId, playerInventory, data)
            },
            TerminalOpenData.STREAM_CODEC
        )
    )

    val RECIPE_CARD: MenuType<RecipeCardScreenHandler> = register(
        "recipe_card",
        ExtendedScreenHandlerType(
            { syncId, playerInventory, data ->
                RecipeCardScreenHandler.clientFactory(syncId, playerInventory, data)
            },
            RecipeCardOpenData.STREAM_CODEC
        )
    )

    val NODE_SIDE: MenuType<NodeSideScreenHandler> = register(
        "node_side",
        ExtendedScreenHandlerType(
            { syncId, playerInventory, data ->
                NodeSideScreenHandler.clientFactory(syncId, playerInventory, data)
            },
            NodeSideOpenData.STREAM_CODEC
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
