package damien.nodeworks

import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.screen.NodeSideScreen
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.gui.screens.MenuScreens

object NodeworksClient : ClientModInitializer {
    override fun onInitializeClient() {
        NodeConnectionRenderer.register()

        MenuScreens.register(ModScreenHandlers.NODE_SIDE) { menu, inventory, title ->
            NodeSideScreen(menu, inventory, title)
        }
    }
}
