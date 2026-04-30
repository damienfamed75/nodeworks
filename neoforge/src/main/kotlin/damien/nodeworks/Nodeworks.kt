package damien.nodeworks

import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.network.*
import damien.nodeworks.network.NeoForgeTerminalPackets
import damien.nodeworks.platform.*
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.registry.ModItems
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.screen.*
import damien.nodeworks.screen.ProcessingSetOpenData
import damien.nodeworks.screen.ProcessingSetScreenHandler
import damien.nodeworks.screen.ProcessingStorageOpenData
import damien.nodeworks.screen.ProcessingStorageScreenHandler
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.RegisterEvent
import org.slf4j.LoggerFactory

@Mod("nodeworks")
class Nodeworks(modBus: IEventBus) {

    companion object {
        const val MOD_ID = "nodeworks"
        private val logger = LoggerFactory.getLogger(MOD_ID)
        var tickCount = 0L
            private set
    }

    init {
        // Initialize platform services BEFORE any registration
        PlatformServices.blockEntity = NeoForgeBlockEntityService()
        PlatformServices.menu = NeoForgeMenuService()
        PlatformServices.storage = NeoForgeStorageService()
        PlatformServices.modState = NeoForgeModStateService()

        // Register during NeoForge's register event (registries are unfrozen at that point)
        modBus.addListener(::onRegister)

        // Register payloads on the mod event bus
        modBus.addListener(::registerPayloads)

        // Register game events on the NeoForge event bus
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onServerStopping)
        NeoForge.EVENT_BUS.addListener(::onPlayerDisconnect)
        NeoForge.EVENT_BUS.addListener(::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onDatapackSync)
        NeoForge.EVENT_BUS.addListener(::onBlockBreak)

        // Register client setup (bypasses KFF's AutoKotlinEventBusSubscriber). Gated
        // on `Dist.CLIENT` because [NeoForgeClientSetup.register] eagerly calls
        // [NodeworksGuide.register], which loads `guideme.Guide` types. GuideME is
        // declared `side = "CLIENT"` in neoforge.mods.toml so it's absent on a
        // dedicated server, calling this unconditionally would NoClassDefFoundError
        // during mod construction. JVM class loading is lazy enough that the
        // reference to NeoForgeClientSetup itself doesn't trigger loading until the
        // call resolves at runtime, so the gate is sufficient.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            damien.nodeworks.client.NeoForgeClientSetup.register(modBus)
        }

        logger.info("Nodeworks initialized")
    }

    private fun onRegister(event: RegisterEvent) {
        // NeoForge fires RegisterEvent once per registry type.
        // Common code uses Registry.register() directly, which works during the event window.
        // ModBlocks registers both blocks AND block items, and ModItems registers standalone items.
        // ModBlockEntities depends on ModBlocks being loaded first.
        // We trigger all common registration on the BLOCK event (first one that fires)
        // since NeoForge's RegisterEvent actually allows cross-registry registration.
        event.register(Registries.BLOCK) {
            ModBlocks.initialize()
            // ModDataComponents MUST come before ModItems, items may reference
            // component types as defaults, and referencing an unregistered component
            // crashes the item constructor with an Unregistered value.
            damien.nodeworks.registry.ModDataComponents.initialize()
            ModItems.initialize()
            ModBlockEntities.initialize()
            damien.nodeworks.registry.ModEntityTypes.initialize()
            // Recipe types + serializers + display types. Ordering:
            //   1. ModRecipeTypes, the RecipeType marker the recipe class references.
            //   2. ModRecipeDisplayTypes, the display Type referenced by Recipe.display()
            //      implementations. Must be registered before the serializer (below)
            //      because constructing a Recipe eagerly may touch display Type.
            //   3. ModRecipeSerializers, the MapCodec/StreamCodec pair that loads
            //      our JSON and syncs over the network.
            damien.nodeworks.registry.ModRecipeTypes.initialize()
            damien.nodeworks.registry.ModRecipeDisplayTypes.initialize()
            damien.nodeworks.registry.ModRecipeSerializers.initialize()
            damien.nodeworks.registry.ModCreativeTab.initialize()
        }

        // Worldgen features. Registered on the FEATURE event so the configured-
        // feature JSON can resolve its `type` reference. Single-feature registry
        // for now, swap to a dedicated init object if more land here.
        event.register(Registries.FEATURE) {
            Registry.register(
                BuiltInRegistries.FEATURE,
                ResourceKey.create(
                    Registries.FEATURE,
                    Identifier.fromNamespaceAndPath("nodeworks", "celestine_ore"),
                ),
                damien.nodeworks.worldgen.CelestineOreFeature(
                    net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.CODEC
                ),
            )
        }

        // Register menu types
        event.register(Registries.MENU) {
            ModScreenHandlers.TERMINAL = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "terminal")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = TerminalOpenData.STREAM_CODEC.decode(buf)
                    TerminalScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.INSTRUCTION_SET = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "instruction_set")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = InstructionSetOpenData.STREAM_CODEC.decode(buf)
                    InstructionSetScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.INSTRUCTION_STORAGE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "instruction_storage")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = InstructionStorageOpenData.STREAM_CODEC.decode(buf)
                    InstructionStorageScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.NODE_SIDE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "node_side")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = NodeSideOpenData.STREAM_CODEC.decode(buf)
                    NodeSideScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.INVENTORY_TERMINAL = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "inventory_terminal")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = InventoryTerminalOpenData.STREAM_CODEC.decode(buf)
                    InventoryTerminalMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.NETWORK_CONTROLLER = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "network_controller")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = NetworkControllerOpenData.STREAM_CODEC.decode(buf)
                    NetworkControllerMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.VARIABLE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "variable")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = VariableOpenData.STREAM_CODEC.decode(buf)
                    VariableMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.CRAFTING_CORE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "crafting_core")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = CraftingCoreOpenData.STREAM_CODEC.decode(buf)
                    CraftingCoreMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.PROCESSING_SET = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "processing_set")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = ProcessingSetOpenData.STREAM_CODEC.decode(buf)
                    ProcessingSetScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.PROCESSING_STORAGE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "processing_storage")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = ProcessingStorageOpenData.STREAM_CODEC.decode(buf)
                    ProcessingStorageScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.BROADCAST_ANTENNA = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "broadcast_antenna")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.BroadcastAntennaOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.BroadcastAntennaMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.RECEIVER_ANTENNA = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "receiver_antenna")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.ReceiverAntennaOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.ReceiverAntennaMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.DIAGNOSTIC = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "diagnostic")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.DiagnosticOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.DiagnosticMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.CARD_PROGRAMMER = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "card_programmer")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.CardProgrammerOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.CardProgrammerMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.STORAGE_CARD = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "storage_card")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.StorageCardOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.StorageCardMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.CARD_SETTINGS = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "card_settings")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.CardSettingsOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.CardSettingsMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.BREAKER = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "breaker")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.BreakerOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.BreakerMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.PLACER = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "placer")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.PlacerOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.PlacerMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.initialize()
        }
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("nodeworks")

        // C2S payloads
        registrar.playToServer(RunScriptPayload.TYPE, RunScriptPayload.CODEC, NeoForgeTerminalPackets::handleRunScript)
        registrar.playToServer(StopScriptPayload.TYPE, StopScriptPayload.CODEC, NeoForgeTerminalPackets::handleStopScript)
        registrar.playToServer(SaveScriptPayload.TYPE, SaveScriptPayload.CODEC, NeoForgeTerminalPackets::handleSaveScript)
        registrar.playToServer(CreateScriptTabPayload.TYPE, CreateScriptTabPayload.CODEC, NeoForgeTerminalPackets::handleCreateScriptTab)
        registrar.playToServer(DeleteScriptTabPayload.TYPE, DeleteScriptTabPayload.CODEC, NeoForgeTerminalPackets::handleDeleteScriptTab)
        registrar.playToServer(ToggleAutoRunPayload.TYPE, ToggleAutoRunPayload.CODEC, NeoForgeTerminalPackets::handleToggleAutoRun)
        registrar.playToServer(SetLayoutPayload.TYPE, SetLayoutPayload.CODEC, NeoForgeTerminalPackets::handleSetLayout)
        registrar.playToServer(SwitchNodeSidePayload.TYPE, SwitchNodeSidePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is NodeSideScreenHandler) {
                    val side = net.minecraft.core.Direction.entries.getOrNull(payload.sideOrdinal) ?: return@enqueueWork
                    menu.switchSide(side)
                }
            }
        }
        // SetStoragePriorityPayload removed, priority is now per-card via StorageCard GUI
        registrar.playToServer(OpenInstructionSetPayload.TYPE, OpenInstructionSetPayload.CODEC, NeoForgeTerminalPackets::handleOpenInstructionSet)
        registrar.playToServer(SetInstructionGridPayload.TYPE, SetInstructionGridPayload.CODEC, NeoForgeTerminalPackets::handleSetInstructionGrid)
        registrar.playToServer(InvTerminalClickPayload.TYPE, InvTerminalClickPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    if (payload.kind == 1.toByte()) {
                        menu.handleFluidGridClick(player, payload.itemId, payload.action)
                    } else {
                        menu.handleGridClick(player, payload.itemId, payload.action)
                    }
                }
            }
        }
        registrar.playToServer(InvTerminalSlotClickPayload.TYPE, InvTerminalSlotClickPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handlePlayerSlotClick(player, payload.slotIndex, payload.action)
                }
            }
        }
        registrar.playToServer(InvTerminalCraftPayload.TYPE, InvTerminalCraftPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleCraftRequest(player, payload.itemId, payload.count)
                }
            }
        }
        registrar.playToServer(CraftQueueExtractPayload.TYPE, CraftQueueExtractPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleQueueExtract(player, payload.entryId, payload.action)
                }
            }
        }
        registrar.playToServer(InvTerminalCraftGridActionPayload.TYPE, InvTerminalCraftGridActionPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleCraftGridAction(player, payload.action)
                }
            }
        }
        registrar.playToServer(InvTerminalCollectPayload.TYPE, InvTerminalCollectPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleCollect(player, payload.itemId)
                }
            }
        }
        registrar.playToServer(InvTerminalDistributePayload.TYPE, InvTerminalDistributePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleDistribute(player, payload.slotType, payload.slotIndices)
                }
            }
        }
        registrar.playToServer(InvTerminalCraftGridPayload.TYPE, InvTerminalCraftGridPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleCraftGridFill(player, payload.grid)
                }
            }
        }

        registrar.playToServer(ControllerSettingsPayload.TYPE, ControllerSettingsPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val level = player.level() as? ServerLevel ?: return@enqueueWork
                val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.NetworkControllerBlockEntity ?: return@enqueueWork
                if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@enqueueWork
                when (payload.key) {
                    "color" -> entity.networkColor = payload.intValue
                    "redstone" -> entity.redstoneMode = payload.intValue
                    "glow" -> entity.nodeGlowStyle = payload.intValue
                    "name" -> entity.networkName = payload.strValue
                    "retry" -> entity.handlerRetryLimit = payload.intValue
                    "chunkload" -> entity.setChunkLoadingEnabled(payload.intValue != 0)
                }
            }
        }

        registrar.playToServer(VariableSettingsPayload.TYPE, VariableSettingsPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val level = player.level() as? ServerLevel ?: return@enqueueWork
                val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.VariableBlockEntity ?: return@enqueueWork
                if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@enqueueWork
                when (payload.key) {
                    "name" -> entity.variableName = payload.strValue
                    "type" -> entity.setType(damien.nodeworks.block.entity.VariableType.fromOrdinal(payload.intValue))
                    "value" -> entity.setValue(payload.strValue)
                    "toggle" -> entity.toggleValue()
                    "channel" -> entity.channel = runCatching {
                        net.minecraft.world.item.DyeColor.byId(payload.intValue)
                    }.getOrDefault(net.minecraft.world.item.DyeColor.WHITE)
                }
            }
        }

        // DeviceSettingsPayload, shared (Breaker, Placer, future devices). Dispatch
        // by reading the BlockEntity at [pos] and matching its concrete type. Same
        // proximity check as VariableSettingsPayload so a remote client can't tweak
        // settings on a device they're not standing near.
        registrar.playToServer(damien.nodeworks.network.DeviceSettingsPayload.TYPE, damien.nodeworks.network.DeviceSettingsPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val level = player.level() as? ServerLevel ?: return@enqueueWork
                if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@enqueueWork
                val entity = level.getBlockEntity(payload.pos) ?: return@enqueueWork
                val newColor: net.minecraft.world.item.DyeColor? = if (payload.key == "channel") {
                    runCatching { net.minecraft.world.item.DyeColor.byId(payload.intValue) }.getOrNull()
                } else null
                when (entity) {
                    is damien.nodeworks.block.entity.BreakerBlockEntity -> {
                        when (payload.key) {
                            "name" -> entity.deviceName = payload.strValue
                            "channel" -> if (newColor != null) entity.channel = newColor
                        }
                    }
                    is damien.nodeworks.block.entity.PlacerBlockEntity -> {
                        when (payload.key) {
                            "name" -> entity.deviceName = payload.strValue
                            "channel" -> if (newColor != null) entity.channel = newColor
                        }
                    }
                }
            }
        }

        registrar.playToServer(SetProcessingApiDataPayload.TYPE, SetProcessingApiDataPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.ProcessingSetScreenHandler && menu.containerId == payload.containerId) {
                    when (payload.key) {
                        "input" -> menu.setInputCount(payload.slotIndex, payload.value)
                        "output" -> menu.setOutputCount(payload.slotIndex, payload.value)
                        "timeout" -> menu.setTimeout(payload.value)
                        "serial" -> {
                            menu.serial = payload.value != 0
                            menu.markDirty()
                        }
                    }
                }
            }
        }

        registrar.playToServer(SetProcessingApiNamePayload.TYPE, SetProcessingApiNamePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.ProcessingSetScreenHandler && menu.containerId == payload.containerId) {
                    menu.cardName = payload.name.take(32)
                    menu.markDirty()
                }
            }
        }

        registrar.playToServer(SetStorageCardFilterRulesPayload.TYPE, SetStorageCardFilterRulesPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.StorageCardMenu && menu.containerId == payload.containerId) {
                    menu.replaceFilterRules(payload.rules)
                }
            }
        }


        registrar.playToServer(CancelCraftPayload.TYPE, CancelCraftPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val level = player.level() as? ServerLevel ?: return@enqueueWork
                if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@enqueueWork
                val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity ?: return@enqueueWork
                entity.cancelJob()
            }
        }

        registrar.playToServer(DismissCpuFailurePayload.TYPE, DismissCpuFailurePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val level = player.level() as? ServerLevel ?: return@enqueueWork
                if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@enqueueWork
                val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity ?: return@enqueueWork
                entity.lastFailureReason = ""
            }
        }

        registrar.playToServer(CraftPreviewRequestPayload.TYPE, CraftPreviewRequestPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val level = player.level() as? ServerLevel ?: return@enqueueWork
                val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, payload.networkPos)
                val tree = damien.nodeworks.script.CraftTreeBuilder.buildCraftTree(payload.itemId, 1, level, snapshot)
                val serverPlayer = player as? net.minecraft.server.level.ServerPlayer ?: return@enqueueWork
                val packet = net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(CraftPreviewResponsePayload(payload.containerId, tree))
                serverPlayer.connection.send(packet)
            }
        }

        registrar.playToServer(SetProcessingApiSlotPayload.TYPE, SetProcessingApiSlotPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.ProcessingSetScreenHandler && menu.containerId == payload.containerId) {
                    menu.setSlotFromId(payload.slotIndex, payload.itemId)
                }
            }
        }

        // S2C payloads
        registrar.playToClient(TerminalLogPayload.TYPE, TerminalLogPayload.CODEC) { payload, context ->
            context.enqueueWork {
                TerminalLogBuffer.addLog(payload.terminalPos, payload.message, payload.isError)
                if (payload.isError) {
                    val player = net.minecraft.client.Minecraft.getInstance().player
                    val menu = player?.containerMenu
                    if (menu is damien.nodeworks.screen.DiagnosticMenu) {
                        menu.addError(payload.terminalPos, payload.message)
                    }
                }
            }
        }
        registrar.playToClient(InventorySyncPayload.TYPE, InventorySyncPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val screen = net.minecraft.client.Minecraft.getInstance().screen
                if (screen is damien.nodeworks.screen.InventoryTerminalScreen) {
                    screen.repo.handleUpdate(payload)
                }
            }
        }
        registrar.playToClient(BufferSyncPayload.TYPE, BufferSyncPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = net.minecraft.client.Minecraft.getInstance().player ?: return@enqueueWork
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.CraftingCoreMenu && menu.containerId == payload.containerId) {
                    menu.clientBufferContents = payload.entries
                }
            }
        }

        registrar.playToClient(CpuFailurePayload.TYPE, CpuFailurePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = net.minecraft.client.Minecraft.getInstance().player ?: return@enqueueWork
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.CraftingCoreMenu && menu.containerId == payload.containerId) {
                    menu.lastFailureReason = payload.reason
                }
            }
        }

        registrar.playToClient(CraftRequestErrorPayload.TYPE, CraftRequestErrorPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val screen = net.minecraft.client.Minecraft.getInstance().screen
                if (screen is damien.nodeworks.screen.InventoryTerminalScreen) {
                    screen.setCraftError(payload.message)
                }
            }
        }

        registrar.playToClient(
            damien.nodeworks.network.PortableConnectionStatusPayload.TYPE,
            damien.nodeworks.network.PortableConnectionStatusPayload.CODEC,
        ) { payload, context ->
            context.enqueueWork {
                val player = net.minecraft.client.Minecraft.getInstance().player ?: return@enqueueWork
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.connectionStatus = damien.nodeworks.screen.PortableConnectionStatus.fromOrdinal(payload.statusOrdinal)
                }
            }
        }

        registrar.playToClient(DebugCraftingCorePayload.TYPE, DebugCraftingCorePayload.CODEC) { _, context ->
            context.enqueueWork {
                damien.nodeworks.command.DebugScreens.openCraftingCore()
            }
        }

        registrar.playToClient(DebugInventoryTerminalPayload.TYPE, DebugInventoryTerminalPayload.CODEC) { _, context ->
            context.enqueueWork {
                damien.nodeworks.command.DebugScreens.openInventoryTerminal()
            }
        }

        registrar.playToClient(CraftingCpuTreePayload.TYPE, CraftingCpuTreePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = net.minecraft.client.Minecraft.getInstance().player ?: return@enqueueWork
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.CraftingCoreMenu && menu.containerId == payload.containerId) {
                    menu.craftTree = payload.tree
                    menu.activeNodeIds = payload.activeNodeIds.toSet()
                    menu.completedNodeIds = payload.completedNodeIds.toSet()
                }
            }
        }

        registrar.playToClient(CraftPreviewResponsePayload.TYPE, CraftPreviewResponsePayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = net.minecraft.client.Minecraft.getInstance().player ?: return@enqueueWork
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.DiagnosticMenu && menu.containerId == payload.containerId) {
                    menu.craftTree = payload.tree
                }
            }
        }

        registrar.playToClient(CraftQueueSyncPayload.TYPE, CraftQueueSyncPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val screen = net.minecraft.client.Minecraft.getInstance().screen
                if (screen is damien.nodeworks.screen.InventoryTerminalScreen) {
                    screen.handleQueueSync(payload)
                }
            }
        }
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        tickCount++
        NeoForgeTerminalPackets.tickAll(event.server, tickCount)
        damien.nodeworks.script.ResumeScheduler.tick(tickCount)
        for (cache in damien.nodeworks.script.NetworkInventoryCache.getAll()) {
            cache.tick()
        }
        for (level in event.server.allLevels) {
            damien.nodeworks.script.MonitorUpdateHelper.tick(level, tickCount)
        }
        // Drain any connectables whose setLevel queued a LOS revalidation this past tick.
        // Deferred because in-line revalidation from setLevel would recurse into
        // level.getBlockEntity for the still-being-registered BE → StackOverflow.
        damien.nodeworks.network.NodeConnectionHelper.drainPendingRevalidations(event.server)
        // Reset NodeConnectionHelper's per-tick propagate-dedup set so the next tick's
        // propagate calls can traverse fresh. Kept here rather than per-level because
        // the dedup is indexed by dimension and clearing once covers all levels.
        damien.nodeworks.network.NodeConnectionHelper.clearTickDedup()
    }

    private fun onRegisterCommands(event: net.neoforged.neoforge.event.RegisterCommandsEvent) {
        damien.nodeworks.command.NwDebugCommand.register(event.dispatcher)
    }

    private fun onServerStopping(event: net.neoforged.neoforge.event.server.ServerStoppingEvent) {
        damien.nodeworks.script.ResumeScheduler.onServerStop()
        // Drop cached SavedData handles, a restart in the same JVM (integrated server quit+rejoin)
        // must re-resolve them against the freshly loaded level.dataStorage.
        damien.nodeworks.network.NodeConnectionHelper.clearServerCaches()
        // Wipe chunk-load refcounts, each controller's setLevel on the next run will
        // re-claim, rebuilding the map from scratch against a fresh level.
        damien.nodeworks.network.ChunkForceLoadManager.clearAll()
    }

    private fun onPlayerDisconnect(event: PlayerEvent.PlayerLoggedOutEvent) {
        NetworkWrenchItem.clearSelection(event.entity.uuid)
    }

    private fun onRightClickBlock(event: net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock) {
        val result = damien.nodeworks.item.SoulSandInteraction.onUseItemOnBlock(
            event.entity, event.level, event.pos, event.itemStack
        )
        if (result != net.minecraft.world.InteractionResult.PASS) {
            event.cancellationResult = result
            event.isCanceled = true
        }
    }

    // Opts our recipe types into the server → client sync. Vanilla 26.1 only
    // syncs a narrow set (smelting, stonecutter, etc.) for display purposes.
    // Without this call the client's RecipeMap has our type key but no
    // entries, so JEI + GuideME can't find the recipe even though gameplay
    // (which goes through the server) works fine.
    //
    // Fires on every player join AND on `/reload`, so the client cache stays
    // current across datapack reloads.
    private fun onDatapackSync(event: net.neoforged.neoforge.event.OnDatapackSyncEvent) {
        event.sendRecipes(damien.nodeworks.registry.ModRecipeTypes.SOUL_SAND_INFUSION)
    }

    /**
     * Force-close any open menu that's bound to a block being broken so a second
     * player mining the block while the first has its GUI open kicks the first
     * out instead of leaving them interacting with a ghost menu. Mirrors how
     * vanilla Chest does it (its `stillValid` checks the block survives), but
     * we need it event-driven because most of our menus implement only a
     * range-based `stillValid`, and `stillValid` isn't called every tick anyway.
     *
     * Iterates [ServerLevel.players()] each break, but the body is a cheap
     * `containerMenu is BlockBackedMenu && pos == backingPos` check so the cost
     * stays proportional to number of players, not menus.
     */
    private fun onBlockBreak(event: BreakBlockEvent) {
        // BreakBlockEvent fires on both sides in 26.1.2.30+, so guard on
        // ServerLevel to avoid running the close once per tick on each client
        // alongside the server. Closing on the server is sufficient, the menu's
        // server-side close packet drives the client's exit.
        val level = event.level as? ServerLevel ?: return
        val pos = event.pos
        for (player in level.players()) {
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.BlockBackedMenu && menu.blockBackingPos == pos) {
                player.closeContainer()
            }
        }
    }
}

class NeoForgeModStateService : ModStateService {
    override val tickCount: Long get() = Nodeworks.tickCount

    override fun isScriptRunning(level: ServerLevel, pos: BlockPos): Boolean {
        return NeoForgeTerminalPackets.getEngine(level, pos)?.isRunning() == true
    }

    override fun stopScript(level: ServerLevel, pos: BlockPos) {
        NeoForgeTerminalPackets.stopEngine(level, pos)
    }

    override fun startScript(level: ServerLevel, pos: BlockPos) {
        NeoForgeTerminalPackets.startEngine(level, pos)
    }

    override fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos) {
        NeoForgeTerminalPackets.registerPendingAutoRun(level, pos)
    }

    override fun findAnyEngine(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        overrideDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>?,
    ): Any? {
        return NeoForgeTerminalPackets.findAnyEngine(level, terminalPositions, overrideDimension)
    }

    override fun findProcessingEngine(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        cardName: String,
        overrideDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>?,
    ): Any? {
        return NeoForgeTerminalPackets.findEngineWithHandler(level, terminalPositions, cardName, overrideDimension)
    }

    override fun getScriptEngine(level: ServerLevel, pos: BlockPos): Any? {
        return NeoForgeTerminalPackets.getEngine(level, pos)
    }
}
