package damien.nodeworks.client

import damien.nodeworks.platform.ClientEventService
import damien.nodeworks.platform.ClientNetworkingService
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.ControllerRenderer
import damien.nodeworks.render.CoProcessorRenderer
import damien.nodeworks.render.CraftingCoreRenderer
import damien.nodeworks.render.CraftingStorageRenderer
import damien.nodeworks.render.InstructionStorageRenderer
import damien.nodeworks.render.InventoryTerminalRenderer
import damien.nodeworks.render.MonitorRenderer
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.render.NodeRenderer
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
import net.neoforged.neoforge.client.event.RenderFrameEvent
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
        modBus.addListener(::onRegisterItemTintSources)
        modBus.addListener(::onRegisterRenderPipelines)

        // Register the in-game guide synchronously during mod construction, NOT inside
        // FMLClientSetupEvent.enqueueWork. GuideME hooks the item-tooltip "Hold G" hint
        // during its own mod-event-bus construction, any guide registered after that point
        // has its ItemIndex wired but the tooltip binding never fires. Pattern mirrors
        // AE2's AppEngClient constructor call.
        damien.nodeworks.guide.NodeworksGuide.register()

        // Register user-rebindable keybinds on the mod bus. Vanilla picks these up and
        // displays them in the controls menu.
        NodeworksKeyBindings.register(modBus)

        // Client-side recipe-sync cache. Vanilla 26.1 stopped syncing the
        // full recipe set to clients, NeoForge keeps the old behavior alive
        // by firing RecipesReceivedEvent with the full RecipeMap on every
        // server update. We use it to keep the Soul Sand Infusion client
        // cache current (and clear it when the player disconnects). HIGHEST
        // priority so our cache is populated before JEI's own reload reads
        // from it during registerRecipes.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST) { event: net.neoforged.neoforge.client.event.RecipesReceivedEvent ->
            damien.nodeworks.recipe.SoulSandInfusionClientCache.refresh(event.recipeMap)
        }
        NeoForge.EVENT_BUS.addListener { _: net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut ->
            damien.nodeworks.recipe.SoulSandInfusionClientCache.clear()
            damien.nodeworks.item.NetworkWrenchItem.clientSelectedPos = null
        }

        // Block other mods (JEI) from stealing key events when our terminal editor is active.
        // JEI hooks into ScreenEvent.KeyPressed.Pre which fires before Screen.keyPressed().
        // We cancel the event to prevent JEI from seeing it, then manually forward to our screen.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST) { event: net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre ->
            val screen = event.screen
            if (screen is damien.nodeworks.screen.TerminalScreen && screen.isEditorFocused()) {
                // 26.1: Screen#keyPressed(KeyEvent), no longer the (keyCode, scanCode, modifiers) triple.
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
            PlatformServices.fluidRenderer = damien.nodeworks.platform.NeoForgeFluidSpriteRenderer()
            PlatformServices.guidebook = damien.nodeworks.guide.NodeworksGuidebookService
            PlatformServices.openDocsKeyHeld = NodeworksKeyBindings.openDocsKeyHeld()

            // 26.1: ItemProperties.register() is gone. Custom property codecs are
            //  registered on the mod event bus via
            //  RegisterConditionalItemModelPropertyEvent /
            //  RegisterSelectItemModelPropertyEvent (see the onRegister* methods
            //  below), and the item model JSON moved to assets/<ns>/items/<id>.json
            //  using `minecraft:condition` / `minecraft:select` dispatch types.

            NodeConnectionRenderer.register()
            damien.nodeworks.render.CardPlacementPreviewRenderer.init()

            // Reset the frame-scoped laser-beam dedup set at the start of each render
            // frame. ConnectionBeamRenderer.submit uses this set to ensure a beam between
            // two Connectables is drawn exactly once per frame regardless of which end's
            // BER submits first, no lex-order dedup, no silent drops if one BER isn't
            // wired or one endpoint is unloaded.
            NeoForge.EVENT_BUS.addListener { _: RenderFrameEvent.Pre ->
                damien.nodeworks.render.ConnectionBeamRenderer.startFrame()
            }
        }
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.NODE, ::NeoNodeRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.MONITOR, ::NeoMonitorRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.NETWORK_CONTROLLER, ::NeoControllerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.VARIABLE, ::NeoVariableRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.TERMINAL, ::NeoTerminalRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PROCESSING_STORAGE, ::NeoProcessingStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.INSTRUCTION_STORAGE, ::NeoInstructionStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.RECEIVER_ANTENNA, ::NeoReceiverAntennaRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CRAFTING_CORE, ::NeoCraftingCoreRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CRAFTING_STORAGE, ::CraftingStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CO_PROCESSOR, ::CoProcessorRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.INVENTORY_TERMINAL, ::NeoInventoryTerminalRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.BREAKER, ::NeoBreakerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PLACER, ::NeoPlacerRenderer)
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
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "portable_inventory_terminal_linked"),
            damien.nodeworks.client.item.PortableInventoryTerminalLinkedProperty.MAP_CODEC
        )
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "card_channel_set"),
            damien.nodeworks.client.item.CardChannelSetProperty.MAP_CODEC
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

    private fun onRegisterItemTintSources(
        event: net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.ItemTintSources
    ) {
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "portable_network_color"),
            damien.nodeworks.client.item.PortableNetworkColorTintSource.MAP_CODEC
        )
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "channel_color"),
            damien.nodeworks.client.item.ChannelColorTintSource.MAP_CODEC
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
        event.register(ModScreenHandlers.CARD_SETTINGS) { menu, inventory, title ->
            damien.nodeworks.screen.CardSettingsScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.BREAKER) { menu, inventory, title ->
            damien.nodeworks.screen.BreakerScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.PLACER) { menu, inventory, title ->
            damien.nodeworks.screen.PlacerScreen(menu, inventory, title)
        }
    }

    private fun onRegisterRenderPipelines(
        event: net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent
    ) {
        // 26.1 registers pipelines via this event so the shader program is compiled
        //  and the pipeline state locked in before any RenderType referencing it is
        //  used. Register our through-walls block-atlas pipeline here, the
        //  PinHighlightRenderType.THROUGH_WALLS RenderType holds the matching
        //  RenderSetup built around it.
        event.registerPipeline(damien.nodeworks.render.PinHighlightRenderType.THROUGH_WALLS_PIPELINE)
        event.registerPipeline(damien.nodeworks.render.CrystalCoreRenderType.CORE_PIPELINE)
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
        // 26.1: Camera.position field is private, public `position()` method is now the accessor.
        //  Kotlin can't auto-synthesise the property because the backing field is inaccessible.
        val cameraPos = mc.gameRenderer.mainCamera.position()
        val bufferSource = mc.renderBuffers().bufferSource()
        for (handler in handlers) {
            handler(event.poseStack, bufferSource, cameraPos)
        }
    }
}
