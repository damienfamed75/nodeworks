package damien.nodeworks

import damien.nodeworks.network.TerminalLogPayload
import damien.nodeworks.platform.FabricClientEventService
import damien.nodeworks.platform.FabricClientNetworkingService
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.MonitorRenderer
import damien.nodeworks.render.ControllerRenderer
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.render.TerminalRenderer
import damien.nodeworks.render.VariableRenderer
import damien.nodeworks.screen.NodeSideScreen
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry
import damien.nodeworks.screen.InstructionSetScreen
import damien.nodeworks.screen.InstructionStorageScreen
import damien.nodeworks.screen.InventoryTerminalScreen
import damien.nodeworks.screen.NetworkControllerScreen
import damien.nodeworks.screen.VariableScreen
import damien.nodeworks.screen.TerminalLogBuffer
import damien.nodeworks.screen.TerminalScreen
import damien.nodeworks.network.InventorySyncPayload
import net.minecraft.client.Minecraft
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screens.MenuScreens

object NodeworksClient : ClientModInitializer {
    override fun onInitializeClient() {
        // Initialize client platform services
        PlatformServices.clientNetworking = FabricClientNetworkingService()
        PlatformServices.clientEvents = FabricClientEventService()

        // Register Link Crystal model predicate
        net.minecraft.client.renderer.item.ItemProperties.register(
            damien.nodeworks.registry.ModItems.LINK_CRYSTAL,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodeworks", "linked")
        ) { stack, _, _, _ ->
            if (damien.nodeworks.item.LinkCrystalItem.isEncoded(stack)) 1.0f else 0.0f
        }

        NodeConnectionRenderer.register()
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
            damien.nodeworks.registry.ModEntityTypes.MILKY_SOUL_BALL
        ) { ctx -> net.minecraft.client.renderer.entity.ThrownItemRenderer(ctx) }
        BlockEntityRendererRegistry.register(ModBlockEntities.NODE, ::MonitorRenderer)
        BlockEntityRendererRegistry.register(ModBlockEntities.NETWORK_CONTROLLER, ::ControllerRenderer)
        BlockEntityRendererRegistry.register(ModBlockEntities.VARIABLE, ::VariableRenderer)
        BlockEntityRendererRegistry.register(ModBlockEntities.TERMINAL, ::TerminalRenderer)

        MenuScreens.register(ModScreenHandlers.NODE_SIDE) { menu, inventory, title ->
            NodeSideScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.INSTRUCTION_SET) { menu, inventory, title ->
            InstructionSetScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.INSTRUCTION_STORAGE) { menu, inventory, title ->
            InstructionStorageScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.TERMINAL) { menu, inventory, title ->
            TerminalScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.INVENTORY_TERMINAL) { menu, inventory, title ->
            InventoryTerminalScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.NETWORK_CONTROLLER) { menu, inventory, title ->
            NetworkControllerScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.VARIABLE) { menu, inventory, title ->
            VariableScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.CRAFTING_CORE) { menu, inventory, title ->
            damien.nodeworks.screen.CraftingCoreScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.PROCESSING_SET) { menu, inventory, title ->
            damien.nodeworks.screen.ProcessingSetScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.PROCESSING_STORAGE) { menu, inventory, title ->
            damien.nodeworks.screen.ProcessingStorageScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.BROADCAST_ANTENNA) { menu, inventory, title ->
            damien.nodeworks.screen.BroadcastAntennaScreen(menu, inventory, title)
        }
        MenuScreens.register(ModScreenHandlers.RECEIVER_ANTENNA) { menu, inventory, title ->
            damien.nodeworks.screen.ReceiverAntennaScreen(menu, inventory, title)
        }

        // Receive log messages from the server
        ClientPlayNetworking.registerGlobalReceiver(TerminalLogPayload.TYPE) { payload, context ->
            TerminalLogBuffer.addLog(payload.terminalPos, payload.message, payload.isError)
        }

        ClientPlayNetworking.registerGlobalReceiver(InventorySyncPayload.TYPE) { payload, context ->
            val screen = Minecraft.getInstance().screen
            if (screen is InventoryTerminalScreen) {
                screen.repo.handleUpdate(payload)
            }
        }
    }
}
