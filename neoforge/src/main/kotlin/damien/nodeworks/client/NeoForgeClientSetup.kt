package damien.nodeworks.client

import damien.nodeworks.platform.ClientEventService
import damien.nodeworks.platform.ClientNetworkingService
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.BreakerRenderer
import damien.nodeworks.render.ControllerRenderer
import damien.nodeworks.render.CoProcessorRenderer
import damien.nodeworks.render.CraftingCoreRenderer
import damien.nodeworks.render.CraftingStorageRenderer
import damien.nodeworks.render.ExportChestRenderer
import damien.nodeworks.render.ImportChestRenderer
import damien.nodeworks.render.InstructionStorageRenderer
import damien.nodeworks.render.InventoryTerminalRenderer
import damien.nodeworks.render.MonitorRenderer
import damien.nodeworks.render.CraftRequesterRenderer
import damien.nodeworks.render.StorageMeterRenderer
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.render.NodeRenderer
import damien.nodeworks.render.PipeRenderer
import damien.nodeworks.render.PlacerRenderer
import damien.nodeworks.render.ProcessingHandlerRenderer
import damien.nodeworks.render.ProcessingStorageRenderer
import damien.nodeworks.render.ReceiverAntennaRenderer
import damien.nodeworks.render.TerminalRenderer
import damien.nodeworks.render.UserRenderer
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
        modBus.addListener(::onRegisterItemTintSources)
        modBus.addListener(::onRegisterRenderPipelines)
        modBus.addListener(::onRegisterStandaloneModels)
        modBus.addListener(::onModifyBakingResult)
        modBus.addListener(::onRegisterTooltipComponents)
        modBus.addListener(::onRegisterGuiLayers)
        modBus.addListener(::onRegisterClientExtensions)

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

        // Polls keyUse each client tick so we can fire a release packet on
        // the falling edge when the player lets go of right-click while a
        // Grapple Beam hook is attached. The item bypasses Player.startUsingItem
        // to avoid the bow-draw movement slow, so this is the only client-side
        // signal the server gets that the grapple should retract.
        NeoForge.EVENT_BUS.addListener { _: net.neoforged.neoforge.client.event.ClientTickEvent.Post ->
            damien.nodeworks.client.GrappleBeamInput.tick()
        }

        // Intercepts scroll-wheel input while the player has an active
        // Grapple Beam session: sends a rope-length adjustment to the server
        // and cancels the event so the default hotbar-swap doesn't also fire.
        // When NOT grappled, scroll behaves normally (toolbar swap).
        NeoForge.EVENT_BUS.addListener { event: net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent ->
            if (damien.nodeworks.client.GrappleBeamInput.onScroll(event.scrollDeltaY)) {
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
            damien.nodeworks.render.UserPreviewRenderer.init()
            damien.nodeworks.render.GrappleBeamRenderer.register()
        }
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        // Direct registration without the laser-bounding-box wrappers, all neighbours
        // are now visible via adjacency, no off-screen-laser-visibility concern.
        event.registerBlockEntityRenderer(ModBlockEntities.NODE, ::NodeRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.FOCUS_NODE, ::FocusNodeRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PIPE, ::PipeRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PROCESSING_HANDLER, ::ProcessingHandlerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.MONITOR, ::MonitorRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.STORAGE_METER, ::StorageMeterRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CRAFT_REQUESTER, ::CraftRequesterRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.NETWORK_CONTROLLER, ::ControllerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.VARIABLE, ::VariableRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.TERMINAL, ::TerminalRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PROCESSING_STORAGE, ::ProcessingStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.INSTRUCTION_STORAGE, ::InstructionStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.RECEIVER_ANTENNA, ::ReceiverAntennaRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CRAFTING_CORE, ::CraftingCoreRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CRAFTING_STORAGE, ::CraftingStorageRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.CO_PROCESSOR, ::CoProcessorRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.INVENTORY_TERMINAL, ::InventoryTerminalRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.BREAKER, ::BreakerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.PLACER, ::PlacerRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.USER, ::UserRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.IMPORT_CHEST, ::ImportChestRenderer)
        event.registerBlockEntityRenderer(ModBlockEntities.EXPORT_CHEST, ::ExportChestRenderer)
        event.registerEntityRenderer(damien.nodeworks.registry.ModEntityTypes.MILKY_SOUL_BALL) { ctx ->
            net.minecraft.client.renderer.entity.ThrownItemRenderer(ctx)
        }
        event.registerEntityRenderer(damien.nodeworks.registry.ModEntityTypes.GRAPPLE_BEAM_HOOK) { ctx ->
            // Hook entity itself draws nothing. The beam line drawn by
            // GrappleBeamRenderer is the only visible representation of
            // the anchor point.
            net.minecraft.client.renderer.entity.NoopRenderer<damien.nodeworks.entity.GrappleBeamHookEntity>(ctx)
        }
    }

    /** Standalone-model registration. The User device's arm geometry comes
     *  from `models/block/user_arm.json` so it can be edited in Blockbench
     *  without touching code. NeoForge's standalone-model system handles
     *  loading + baking; the renderer fetches the result via [UserArmModel]'s
     *  fetcher lambda which we wire up to the live ModelManager here. */
    private val USER_ARM_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:user_arm" }

    /** Emissive overlay model. Inherits geometry from `nodeworks:block/user`
     *  via JSON parent and only swaps `#0` to user_emissive.png, so any
     *  face the artist paints in user_emissive.png lights up at the
     *  network colour with the SAME per-face UV layout user.json uses. */
    private val USER_EMISSIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:user_emissive" }

    /** Emissive overlay model for the Placer. Same parent-model pattern the
     *  User uses - inherits `nodeworks:block/placer`'s geometry and only
     *  swaps `#0` to placer_emissive.png. */
    private val PLACER_EMISSIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:placer_emissive" }

    /** Emissive overlay model for the Breaker. Same parent-model pattern -
     *  inherits `nodeworks:block/breaker`'s geometry and only swaps `#0` to
     *  breaker_emissive.png. */
    private val BREAKER_EMISSIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:breaker_emissive" }

    /** Storage Meter network-colour emissive overlay. Inherits the geometry of
     *  `nodeworks:block/storage_meter` and only swaps `#0` to
     *  storage_meter_emissive.png. */
    private val STORAGE_METER_EMISSIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:storage_meter_emissive" }

    /** Storage Meter redstone-active overlay. Same geometry inheritance, swaps
     *  `#0` to storage_meter_redstone_active.png. Drawn alongside the network-
     *  colour overlay when the meter is below threshold. */
    private val STORAGE_METER_REDSTONE_ACTIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:storage_meter_redstone_active" }

    /** Craft Requester network-colour emissive overlay. Inherits the geometry
     *  of `nodeworks:block/craft_requester` and only swaps `#0` to
     *  craft_requester_emissive.png. */
    private val CRAFT_REQUESTER_EMISSIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:craft_requester_emissive" }

    /** Craft Requester redstone-active overlay. Same geometry inheritance,
     *  swaps `#0` to craft_requester_redstone_active.png. Drawn alongside the
     *  network-colour overlay while the input signal is high. */
    private val CRAFT_REQUESTER_REDSTONE_ACTIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:craft_requester_redstone_active" }

    /** Emissive overlay model for the Variable block. Same parent-model
     *  pattern - inherits `nodeworks:block/variable`'s geometry and only
     *  swaps `#particle` to variable_emissive.png. */
    private val VARIABLE_EMISSIVE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:variable_emissive" }

    /** Indicator overlay model for the Covered Pipe. Loaded once at bake
     *  time and held by the dynamic baked model so every Covered Pipe in
     *  the chunk can stamp the same baked quads on its faces without
     *  reloading them per-block. */
    private val COVERED_PIPE_INDICATOR_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:covered_pipe_indicator" }

    /** Floating cube that the Grapple Beam emits from. Baked separately
     *  from the staff so its quads can be re-emitted on every frame with
     *  a per-frame transform (passive spin + grapple-time anchor tracking
     *  + pulse). The model file lives at `nodeworks:item/grapple_beam_cube`. */
    private val GRAPPLE_BEAM_CUBE_MODEL_KEY: net.neoforged.neoforge.client.model.standalone.StandaloneModelKey<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> =
        net.neoforged.neoforge.client.model.standalone.StandaloneModelKey { "nodeworks:grapple_beam_cube" }

    private fun onRegisterStandaloneModels(
        event: net.neoforged.neoforge.client.event.ModelEvent.RegisterStandalone
    ) {
        val armId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/user_arm")
        event.register(
            USER_ARM_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(armId),
        )
        damien.nodeworks.client.UserArmModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(USER_ARM_MODEL_KEY)
        }

        val emissiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/user_emissive")
        event.register(
            USER_EMISSIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(emissiveId),
        )
        damien.nodeworks.client.UserEmissiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(USER_EMISSIVE_MODEL_KEY)
        }

        val placerEmissiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/placer_emissive")
        event.register(
            PLACER_EMISSIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(placerEmissiveId),
        )
        damien.nodeworks.client.PlacerEmissiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(PLACER_EMISSIVE_MODEL_KEY)
        }

        val breakerEmissiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/breaker_emissive")
        event.register(
            BREAKER_EMISSIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(breakerEmissiveId),
        )
        damien.nodeworks.client.BreakerEmissiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(BREAKER_EMISSIVE_MODEL_KEY)
        }

        val storageMeterEmissiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/storage_meter_emissive")
        event.register(
            STORAGE_METER_EMISSIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(storageMeterEmissiveId),
        )
        damien.nodeworks.client.StorageMeterEmissiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(STORAGE_METER_EMISSIVE_MODEL_KEY)
        }

        val storageMeterRedstoneActiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/storage_meter_redstone_active")
        event.register(
            STORAGE_METER_REDSTONE_ACTIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(storageMeterRedstoneActiveId),
        )
        damien.nodeworks.client.StorageMeterRedstoneActiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(STORAGE_METER_REDSTONE_ACTIVE_MODEL_KEY)
        }

        val craftRequesterEmissiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/craft_requester_emissive")
        event.register(
            CRAFT_REQUESTER_EMISSIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(craftRequesterEmissiveId),
        )
        damien.nodeworks.client.CraftRequesterEmissiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(CRAFT_REQUESTER_EMISSIVE_MODEL_KEY)
        }

        val craftRequesterRedstoneActiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/craft_requester_redstone_active")
        event.register(
            CRAFT_REQUESTER_REDSTONE_ACTIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(craftRequesterRedstoneActiveId),
        )
        damien.nodeworks.client.CraftRequesterRedstoneActiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(CRAFT_REQUESTER_REDSTONE_ACTIVE_MODEL_KEY)
        }

        val variableEmissiveId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/variable_emissive")
        event.register(
            VARIABLE_EMISSIVE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(variableEmissiveId),
        )
        damien.nodeworks.client.VariableEmissiveModel.fetcher = {
            net.minecraft.client.Minecraft.getInstance()
                .modelManager
                .getStandaloneModel(VARIABLE_EMISSIVE_MODEL_KEY)
        }

        val coveredPipeIndicatorId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "block/covered_pipe_indicator")
        event.register(
            COVERED_PIPE_INDICATOR_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(coveredPipeIndicatorId),
        )

        val grappleBeamCubeId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "item/grapple_beam_cube")
        event.register(
            GRAPPLE_BEAM_CUBE_MODEL_KEY,
            net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel.simpleModelWrapper(grappleBeamCubeId),
        )
    }

    /**
     * Wraps the default baked model for every Covered Vacuum Pipe blockstate
     * with [damien.nodeworks.client.model.CoveredPipeBakedModel], which
     * delegates to the wrapped camo block's quads at chunk-mesh time + adds
     * the indicator overlay. Doing the swap here (rather than via a
     * [net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel] +
     * JSON loader) keeps the rest of the model pipeline stock - the
     * baseline `covered_pipe.json` still loads and provides the indicator-
     * only fallback the dynamic model uses when there's no level/BE
     * context (item-frame display, break particles).
     */
    private fun onModifyBakingResult(
        event: net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult
    ) {
        val bakingResult = event.bakingResult
        val indicatorPart: net.minecraft.client.renderer.block.dispatch.BlockStateModelPart =
            bakingResult.standaloneModels().get(COVERED_PIPE_INDICATOR_MODEL_KEY)
                ?: return  // Indicator failed to bake - leave the baseline in place

        // Block-side swap: every Covered Pipe blockstate's baked model is
        // replaced with a dynamic delegate that reads the wrapped block
        // from the BE and routes its quads through chunk meshing.
        val blockModels = bakingResult.blockStateModels()
        val coveredPipeStates = damien.nodeworks.registry.ModBlocks.COVERED_PIPE.stateDefinition.possibleStates
        for (state in coveredPipeStates) {
            val baselineModel = blockModels[state] ?: continue
            blockModels[state] = damien.nodeworks.client.model.CoveredPipeBakedModel(
                indicatorPart = indicatorPart,
                defaultCamo = baselineModel,
            )
        }

        // Item-side swap: the baseline `nodeworks:item/covered_pipe` cube_all
        // gets replaced with a custom ItemModel that emits the camo's quads
        // at update-time. We pull ModelRenderProperties off the baseline
        // (display transforms, particle, etc.) via reflection on
        // CuboidItemModelWrapper's private `properties` field - cleanest
        // way to inherit the vanilla block-item perspective/gui transforms
        // without re-deriving the matrices. If the baseline isn't a
        // CuboidItemModelWrapper (modded transformation, etc.), bail and
        // leave the static model in place rather than crash.
        val itemModels = bakingResult.itemStackModels()
        val itemId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "covered_pipe")
        val baselineItem = itemModels[itemId] ?: return
        val baselineProps = extractItemModelProperties(baselineItem) ?: return
        itemModels[itemId] = damien.nodeworks.client.model.CoveredPipeItemModel(
            baselineProperties = baselineProps,
            indicatorPart = indicatorPart,
        )

        // Grapple Beam: wrap the baseline staff with a composite that
        // also emits the standalone-baked cube quads on a second layer
        // with a per-frame animated transform. The cube's visible size
        // can be tuned via GrappleBeamItemModel.CUBE_VISUAL_SCALE.
        val grappleCubePart = bakingResult.standaloneModels().get(GRAPPLE_BEAM_CUBE_MODEL_KEY)
        if (grappleCubePart != null) {
            val grappleId = net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "grapple_beam")
            val grappleBaseline = itemModels[grappleId]
            val grappleProps = grappleBaseline?.let { extractItemModelProperties(it) }
            if (grappleBaseline != null && grappleProps != null) {
                itemModels[grappleId] = damien.nodeworks.client.model.GrappleBeamItemModel(
                    baselineStaff = grappleBaseline,
                    cubeProperties = grappleProps,
                    cubePart = grappleCubePart,
                )
            }
        }
    }

    /** Pull [net.minecraft.client.renderer.item.ModelRenderProperties] off
     *  a baked [net.minecraft.client.renderer.item.ItemModel] when it's a
     *  [net.minecraft.client.renderer.item.CuboidItemModelWrapper] (the
     *  shape vanilla bakes `cube_all`-style models into). Returns null
     *  for other ItemModel types so the caller can fall back gracefully. */
    private fun extractItemModelProperties(
        model: net.minecraft.client.renderer.item.ItemModel,
    ): net.minecraft.client.renderer.item.ModelRenderProperties? {
        if (model !is net.minecraft.client.renderer.item.CuboidItemModelWrapper) return null
        return try {
            val field = net.minecraft.client.renderer.item.CuboidItemModelWrapper::class.java
                .getDeclaredField("properties")
            field.isAccessible = true
            field.get(model) as? net.minecraft.client.renderer.item.ModelRenderProperties
        } catch (e: Exception) {
            null
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
        event.register(ModScreenHandlers.IMPORT_CHEST) { menu, inventory, title ->
            damien.nodeworks.screen.ImportChestScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.EXPORT_CHEST) { menu, inventory, title ->
            damien.nodeworks.screen.ExportChestScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.USER) { menu, inventory, title ->
            damien.nodeworks.screen.UserScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.PROCESSING_HANDLER) { menu, inventory, title ->
            damien.nodeworks.screen.ProcessingHandlerScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.STORAGE_METER) { menu, inventory, title ->
            damien.nodeworks.screen.StorageMeterScreen(menu, inventory, title)
        }
        event.register(ModScreenHandlers.CRAFT_REQUESTER) { menu, inventory, title ->
            damien.nodeworks.screen.CraftRequesterScreen(menu, inventory, title)
        }
    }

    /** Wire [damien.nodeworks.screen.tooltip.RecipeIconTooltip] (server-safe
     *  data carrier returned by Instruction / Processing Set items'
     *  [net.minecraft.world.item.Item.getTooltipImage]) to its client-side
     *  renderer. Without this the data class flows through the tooltip
     *  pipeline but vanilla doesn't know how to draw it and the icons
     *  silently no-op. */
    private fun onRegisterTooltipComponents(
        event: net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent
    ) {
        event.register(damien.nodeworks.screen.tooltip.RecipeIconTooltip::class.java) { data ->
            damien.nodeworks.screen.tooltip.RecipeIconTooltipRenderer(data)
        }
    }

    /** Wires the Grapple Beam item's first-person animation. The
     *  extension handler reads lerped state from [GrappleBeamAnimState]
     *  each frame and tilts/extends/scales the held staff. */
    private fun onRegisterClientExtensions(
        event: net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent
    ) {
        event.registerItem(GrappleBeamClientExtensions, damien.nodeworks.registry.ModItems.GRAPPLE_BEAM)
    }

    private fun onRegisterGuiLayers(event: net.neoforged.neoforge.client.event.RegisterGuiLayersEvent) {
        event.registerAbove(
            net.neoforged.neoforge.client.gui.VanillaGuiLayers.CROSSHAIR,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "grapple_beam_hud"),
            net.neoforged.neoforge.client.gui.GuiLayer { graphics, delta ->
                damien.nodeworks.client.GrappleBeamHud.render(graphics, delta)
            },
        )
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
        event.registerPipeline(damien.nodeworks.render.PipeLaserCoreRenderType.PIPELINE)
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
