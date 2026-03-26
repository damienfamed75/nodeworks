package damien.nodeworks

import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.screen.NodeSideScreen
import damien.nodeworks.screen.RecipeCardScreen
import damien.nodeworks.screen.TerminalLogBuffer
import damien.nodeworks.screen.TerminalScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screens.MenuScreens

object NodeworksClient : ClientModInitializer {
    override fun onInitializeClient() {
        NodeConnectionRenderer.register()

        MenuScreens.register(ModScreenHandlers.NODE_SIDE) { menu, inventory, title ->
            NodeSideScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.RECIPE_CARD) { menu, inventory, title ->
            RecipeCardScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.TERMINAL) { menu, inventory, title ->
            TerminalScreen(menu, inventory, title)
        }

        // Receive log messages from the server
        ClientPlayNetworking.registerGlobalReceiver(TerminalPackets.TerminalLogPayload.TYPE) { payload, context ->
            TerminalLogBuffer.addLog(payload.terminalPos, payload.message, payload.isError)
        }
    }
}
