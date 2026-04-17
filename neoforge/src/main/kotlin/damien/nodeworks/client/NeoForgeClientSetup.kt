package damien.nodeworks.client

import damien.nodeworks.platform.ClientEventService
import damien.nodeworks.platform.ClientNetworkingService
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.ControllerRenderer
import damien.nodeworks.render.InstructionStorageRenderer
import damien.nodeworks.render.MonitorRenderer
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.render.ProcessingStorageRenderer
import damien.nodeworks.render.ReceiverAntennaRenderer
import damien.nodeworks.render.TerminalRenderer
import damien.nodeworks.render.VariableRenderer
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import damien.nodeworks.screen.NodeSideScreen
import damien.nodeworks.screen.InstructionSetScreen
import damien.nodeworks.screen.InstructionStorageScreen
import damien.nodeworks.screen.InventoryTerminalScreen
import damien.nodeworks.screen.NetworkControllerScreen
import damien.nodeworks.screen.VariableScreen
import damien.nodeworks.screen.TerminalScreen
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.common.NeoForge

object NeoForgeClientSetup {

    fun register(modBus: IEventBus) {
        modBus.addListener(::onClientSetup)
        modBus.addListener(::onRegisterRenderers)
        modBus.addListener(::onRegisterMenuScreens)
        modBus.addListener(::onRegisterBlockColors)
        modBus.addListener(::onRegisterShaders)

        // Block other mods (JEI) from stealing key events when our terminal editor is active.
        // JEI hooks into ScreenEvent.KeyPressed.Pre which fires before Screen.keyPressed().
        // We cancel the event to prevent JEI from seeing it, then manually forward to our screen.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST) { event: net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre ->
            val screen = event.screen
            if (screen is damien.nodeworks.screen.TerminalScreen && screen.isEditorFocused()) {
                screen.keyPressed(event.keyCode, event.scanCode, event.modifiers)
                event.isCanceled = true
            }
        }
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST) { event: net.neoforged.neoforge.client.event.ScreenEvent.CharacterTyped.Pre ->
            val screen = event.screen
            if (screen is damien.nodeworks.screen.TerminalScreen && screen.isEditorFocused()) {
                screen.charTyped(event.codePoint, event.modifiers)
                event.isCanceled = true
            }
        }
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            // Initialize client config
            damien.nodeworks.config.ClientConfig.init(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().toFile())

            // Initialize client platform services
            PlatformServices.clientNetworking = NeoForgeClientNetworkingService()
            PlatformServices.clientEvents = NeoForgeClientEventService()

            // Register Link Crystal model predicate
            net.minecraft.client.renderer.item.ItemProperties.register(
                damien.nodeworks.registry.ModItems.LINK_CRYSTAL,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "linked")
            ) { stack, _, _, _ ->
                if (damien.nodeworks.item.LinkCrystalItem.isEncoded(stack)) 1.0f else 0.0f
            }

            // Register Card Programmer model predicate — changes texture based on template card type
            net.minecraft.client.renderer.item.ItemProperties.register(
                damien.nodeworks.registry.ModItems.CARD_PROGRAMMER,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "card_type")
            ) { stack, _, _, _ ->
                val template = damien.nodeworks.item.CardProgrammerItem.getTemplate(stack)
                if (template.isEmpty) 0.0f
                else when ((template.item as? damien.nodeworks.card.NodeCard)?.cardType) {
                    "storage" -> 1.0f
                    "io" -> 2.0f
                    "redstone" -> 3.0f
                    else -> 0.0f
                }
            }

            NodeConnectionRenderer.register()
        }
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.NODE, ::MonitorRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.NETWORK_CONTROLLER, ::ControllerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.VARIABLE, ::VariableRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.TERMINAL, ::TerminalRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PROCESSING_STORAGE, ::ProcessingStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.INSTRUCTION_STORAGE, ::InstructionStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.RECEIVER_ANTENNA, ::ReceiverAntennaRenderer)
        event.registerEntityRenderer(damien.nodeworks.registry.ModEntityTypes.MILKY_SOUL_BALL) { ctx ->
            net.minecraft.client.renderer.entity.ThrownItemRenderer(ctx)
        }
    }

    private fun onRegisterMenuScreens(event: RegisterMenuScreensEvent) {
        event.register(ModScreenHandlers.NODE_SIDE) { menu, inventory, title ->
            NodeSideScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.INSTRUCTION_SET) { menu, inventory, title ->
            InstructionSetScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.INSTRUCTION_STORAGE) { menu, inventory, title ->
            InstructionStorageScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.TERMINAL) { menu, inventory, title ->
            TerminalScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.INVENTORY_TERMINAL) { menu, inventory, title ->
            InventoryTerminalScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.NETWORK_CONTROLLER) { menu, inventory, title ->
            NetworkControllerScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.VARIABLE) { menu, inventory, title ->
            VariableScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.CRAFTING_CORE) { menu, inventory, title ->
            damien.nodeworks.screen.CraftingCoreScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.PROCESSING_SET) { menu, inventory, title ->
            damien.nodeworks.screen.ProcessingSetScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.PROCESSING_STORAGE) { menu, inventory, title ->
            damien.nodeworks.screen.ProcessingStorageScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.BROADCAST_ANTENNA) { menu, inventory, title ->
            damien.nodeworks.screen.BroadcastAntennaScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.RECEIVER_ANTENNA) { menu, inventory, title ->
            damien.nodeworks.screen.ReceiverAntennaScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.DIAGNOSTIC) { menu, inventory, title ->
            damien.nodeworks.screen.DiagnosticScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.CARD_PROGRAMMER) { menu, inventory, title ->
            damien.nodeworks.screen.CardProgrammerScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.STORAGE_CARD) { menu, inventory, title ->
            damien.nodeworks.screen.StorageCardScreen(menu, inventory, title)
        }
    }

    private fun onRegisterShaders(event: net.neoforged.neoforge.client.event.RegisterShadersEvent) {
        val location = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "flat_color_item")
        event.registerShader(
            net.minecraft.client.renderer.ShaderInstance(event.resourceProvider, location, com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY)
        ) { shader ->
            damien.nodeworks.render.FlatColorItemRenderer.shaderInstance = shader
            // Create the custom RenderType using the loaded shader
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

    private fun onRegisterBlockColors(event: net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block) {
        // Tint emissive overlays (tintindex 0) with network color
        val colorProvider = net.minecraft.client.color.block.BlockColor { _, blockGetter, pos, tintIndex ->
            if (tintIndex == 0 && pos != null) {
                // BlockAndTintGetter might be a Level — try to get the block entity
                val entity = blockGetter?.getBlockEntity(pos)
                when (entity) {
                    is damien.nodeworks.block.entity.NetworkControllerBlockEntity -> entity.networkColor
                    is damien.nodeworks.network.Connectable -> {
                        // For other connectable blocks, find the controller via BFS
                        val level = net.minecraft.client.Minecraft.getInstance().level
                        if (level != null) NodeConnectionRenderer.findNetworkColor(level, pos) else -1
                    }
                    else -> -1
                }
            } else -1
        }
        event.register(colorProvider,
            damien.nodeworks.registry.ModBlocks.NETWORK_CONTROLLER,
            damien.nodeworks.registry.ModBlocks.VARIABLE,
            damien.nodeworks.registry.ModBlocks.TERMINAL,
            damien.nodeworks.registry.ModBlocks.PROCESSING_STORAGE,
            damien.nodeworks.registry.ModBlocks.INSTRUCTION_STORAGE,
            damien.nodeworks.registry.ModBlocks.RECEIVER_ANTENNA
        )
    }
}

class NeoForgeClientNetworkingService : ClientNetworkingService {
    override fun sendToServer(payload: CustomPacketPayload) {
        PacketDistributor.sendToServer(payload)
    }
}

class NeoForgeClientEventService : ClientEventService {
    private val handlers = mutableListOf<(PoseStack?, MultiBufferSource?, Vec3) -> Unit>()

    override fun onWorldRender(handler: (PoseStack?, MultiBufferSource?, Vec3) -> Unit) {
        handlers.add(handler)
        if (handlers.size == 1) {
            NeoForge.EVENT_BUS.addListener(::onRenderAfterTranslucent)
        }
    }

    private fun onRenderAfterTranslucent(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc = Minecraft.getInstance()
        val cameraPos = mc.gameRenderer.mainCamera.getPosition()
        val bufferSource = mc.renderBuffers().bufferSource()
        for (handler in handlers) {
            handler(event.poseStack, bufferSource, cameraPos)
        }
    }
}
