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
        // Initialize client config
        damien.nodeworks.config.ClientConfig.init(net.fabricmc.loader.api.FabricLoader.getInstance().configDir.toFile())

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

        // Register custom shaders
        net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback.EVENT.register { ctx ->
            val location = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodeworks", "flat_color_item")
            ctx.register(location, com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) { shader ->
                damien.nodeworks.render.FlatColorItemRenderer.shaderInstance = shader
                damien.nodeworks.render.FlatColorItemRenderer.renderType = net.minecraft.client.renderer.RenderType.create(
                    "nodeworks_flat_color_item",
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                    1536, false, true,
                    net.minecraft.client.renderer.RenderType.CompositeState.builder()
                        .setShaderState(net.minecraft.client.renderer.RenderStateShard.ShaderStateShard { shader })
                        .setTextureState(net.minecraft.client.renderer.RenderStateShard.TextureStateShard(
                            net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS, false, false))
                        .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                        .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
                        .setOverlayState(net.minecraft.client.renderer.RenderStateShard.OVERLAY)
                        .createCompositeState(true)
                )
            }
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
        MenuScreens.register(ModScreenHandlers.DIAGNOSTIC) { menu, inventory, title ->
            damien.nodeworks.screen.DiagnosticScreen(menu, inventory, title)
        }

        // Receive log messages from the server
        ClientPlayNetworking.registerGlobalReceiver(TerminalLogPayload.TYPE) { payload, context ->
            TerminalLogBuffer.addLog(payload.terminalPos, payload.message, payload.isError)
            // Feed errors to the diagnostic menu if open
            if (payload.isError) {
                val player = Minecraft.getInstance().player
                val menu = player?.containerMenu
                if (menu is damien.nodeworks.screen.DiagnosticMenu) {
                    menu.addError(payload.terminalPos, payload.message)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(InventorySyncPayload.TYPE) { payload, context ->
            val screen = Minecraft.getInstance().screen
            if (screen is InventoryTerminalScreen) {
                screen.repo.handleUpdate(payload)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(damien.nodeworks.network.BufferSyncPayload.TYPE) { payload, context ->
            val player = Minecraft.getInstance().player ?: return@registerGlobalReceiver
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.CraftingCoreMenu && menu.containerId == payload.containerId) {
                menu.clientBufferContents = payload.entries
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(damien.nodeworks.network.CraftPreviewResponsePayload.TYPE) { payload, context ->
            val player = Minecraft.getInstance().player ?: return@registerGlobalReceiver
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.DiagnosticMenu && menu.containerId == payload.containerId) {
                menu.craftTree = payload.tree
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(damien.nodeworks.network.CraftingCpuTreePayload.TYPE) { payload, context ->
            val player = Minecraft.getInstance().player ?: return@registerGlobalReceiver
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.CraftingCoreMenu && menu.containerId == payload.containerId) {
                menu.craftTree = payload.tree
                menu.activeSteps = payload.activeSteps.toSet()
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(damien.nodeworks.network.CraftQueueSyncPayload.TYPE) { payload, context ->
            val screen = Minecraft.getInstance().screen
            if (screen is InventoryTerminalScreen) {
                screen.handleQueueSync(payload)
            }
        }
    }
}
