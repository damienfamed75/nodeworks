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
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.common.NeoForge

object NeoForgeClientSetup {

    fun register(modBus: IEventBus) {
        modBus.addListener(::onClientSetup)
        modBus.addListener(::onRegisterRenderers)
        modBus.addListener(::onRegisterMenuScreens)
        modBus.addListener(::onRegisterConditionalItemModelProperties)
        modBus.addListener(::onRegisterSelectItemModelProperties)
        modBus.addListener(::onRegisterBlockTintSources)
        modBus.addListener(::onRegisterRenderPipelines)

        // Block other mods (JEI) from stealing key events when our terminal editor is active.
        // JEI hooks into ScreenEvent.KeyPressed.Pre which fires before Screen.keyPressed().
        // We cancel the event to prevent JEI from seeing it, then manually forward to our screen.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST) { event: net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre ->
            val screen = event.screen
            if (screen is damien.nodeworks.screen.TerminalScreen && screen.isEditorFocused()) {
                // 26.1: Screen#keyPressed(KeyEvent) — no longer the (keyCode, scanCode, modifiers) triple.
                //  The ScreenEvent still exposes getKeyEvent() for forwarding.
                screen.keyPressed(event.keyEvent)
                event.isCanceled = true
            }
        }
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST) { event: net.neoforged.neoforge.client.event.ScreenEvent.CharacterTyped.Pre ->
            val screen = event.screen
            if (screen is damien.nodeworks.screen.TerminalScreen && screen.isEditorFocused()) {
                screen.charTyped(event.characterEvent)
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

            // 26.1: ItemProperties.register() is gone. Custom property codecs are
            //  registered on the mod event bus via
            //  RegisterConditionalItemModelPropertyEvent /
            //  RegisterSelectItemModelPropertyEvent (see the onRegister* methods
            //  below), and the item model JSON moved to assets/<ns>/items/<id>.json
            //  using `minecraft:condition` / `minecraft:select` dispatch types.

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

    private fun onRegisterConditionalItemModelProperties(
        event: net.neoforged.neoforge.client.event.RegisterConditionalItemModelPropertyEvent
    ) {
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "link_crystal_linked"),
            damien.nodeworks.client.item.LinkCrystalLinkedProperty.MAP_CODEC
        )
    }

    private fun onRegisterSelectItemModelProperties(
        event: net.neoforged.neoforge.client.event.RegisterSelectItemModelPropertyEvent
    ) {
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "card_programmer_card_type"),
            damien.nodeworks.client.item.CardProgrammerTypeProperty.TYPE
        )
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

    // TODO MC 26.1.2 SHADER-PIPELINE MIGRATION (FlatColorItemRenderer only):
    //  The FlatColorItemRenderer silhouette-glow shader still needs a RenderPipeline
    //  port — see FlatColorItemRenderer for details. The pin-highlight path was
    //  ported in Phase 17 (PinHighlightRenderType).

    private fun onRegisterRenderPipelines(
        event: net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent
    ) {
        // 26.1 registers pipelines via this event so the shader program is compiled
        //  and the pipeline state locked in before any RenderType referencing it is
        //  used. Register our through-walls block-atlas pipeline here — the
        //  PinHighlightRenderType.THROUGH_WALLS RenderType holds the matching
        //  RenderSetup built around it.
        event.registerPipeline(damien.nodeworks.render.PinHighlightRenderType.THROUGH_WALLS_PIPELINE)
    }

    private fun onRegisterBlockTintSources(
        event: net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.BlockTintSources
    ) {
        // 26.1: the old `BlockColor` callback + `event.register(provider, Block...)` form was
        //  dropped. Tint is now resolved by a list of BlockTintSources per block, keyed by
        //  the `tintindex` on the face. Our emissive overlays use tintindex:0 so we register
        //  a single-source list with NetworkColorTintSource at index 0 for every block that
        //  wants its emissive layer tinted by the network colour.
        val source = listOf(damien.nodeworks.client.color.NetworkColorTintSource())
        event.register(
            source,
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
        // 26.1: PacketDistributor.sendToServer was split out into ClientPacketDistributor
        //  (client-only class) to prevent server-side code from accidentally referencing
        //  a client-only flow at compile time.
        ClientPacketDistributor.sendToServer(payload)
    }
}

class NeoForgeClientEventService : ClientEventService {
    private val handlers = mutableListOf<(PoseStack?, MultiBufferSource?, Vec3) -> Unit>()

    override fun onWorldRender(handler: (PoseStack?, MultiBufferSource?, Vec3) -> Unit) {
        handlers.add(handler)
        if (handlers.size == 1) {
            // 26.1: RenderLevelStageEvent gained subclasses (AfterSky, AfterOpaqueBlocks,
            //  AfterTranslucentBlocks, …) instead of a Stage enum. Listening on a subclass
            //  directly subscribes to that stage, no more `if (event.stage != ...) return`.
            NeoForge.EVENT_BUS.addListener(::onRenderAfterTranslucent)
        }
    }

    private fun onRenderAfterTranslucent(event: RenderLevelStageEvent.AfterTranslucentBlocks) {
        val mc = Minecraft.getInstance()
        // 26.1: Camera.position field is private; public `position()` method is now the accessor.
        //  Kotlin can't auto-synthesise the property because the backing field is inaccessible.
        val cameraPos = mc.gameRenderer.mainCamera.position()
        val bufferSource = mc.renderBuffers().bufferSource()
        for (handler in handlers) {
            handler(event.poseStack, bufferSource, cameraPos)
        }
    }
}
