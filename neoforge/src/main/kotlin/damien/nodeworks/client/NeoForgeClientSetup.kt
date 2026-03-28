package damien.nodeworks.client

import damien.nodeworks.platform.ClientEventService
import damien.nodeworks.platform.ClientNetworkingService
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.ControllerRenderer
import damien.nodeworks.render.MonitorRenderer
import damien.nodeworks.render.NodeConnectionRenderer
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
            // Initialize client platform services
            PlatformServices.clientNetworking = NeoForgeClientNetworkingService()
            PlatformServices.clientEvents = NeoForgeClientEventService()

            NodeConnectionRenderer.register()
        }
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.NODE, ::MonitorRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.NETWORK_CONTROLLER, ::ControllerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.VARIABLE, ::VariableRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.TERMINAL, ::TerminalRenderer)
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
            damien.nodeworks.registry.ModBlocks.TERMINAL
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
