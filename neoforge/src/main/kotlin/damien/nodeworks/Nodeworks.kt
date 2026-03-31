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
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.event.entity.player.PlayerEvent
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
        NeoForge.EVENT_BUS.addListener(::onPlayerDisconnect)
        NeoForge.EVENT_BUS.addListener(::onRightClickBlock)

        // Register client setup (bypasses KFF's AutoKotlinEventBusSubscriber)
        damien.nodeworks.client.NeoForgeClientSetup.register(modBus)

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
            ModItems.initialize()
            ModBlockEntities.initialize()
            damien.nodeworks.registry.ModEntityTypes.initialize()
        }

        // Register menu types
        event.register(Registries.MENU) {
            ModScreenHandlers.TERMINAL = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "terminal")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = TerminalOpenData.STREAM_CODEC.decode(buf)
                    TerminalScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.INSTRUCTION_SET = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "instruction_set")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = InstructionSetOpenData.STREAM_CODEC.decode(buf)
                    InstructionSetScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.INSTRUCTION_STORAGE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "instruction_storage")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = InstructionStorageOpenData.STREAM_CODEC.decode(buf)
                    InstructionStorageScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.NODE_SIDE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "node_side")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = NodeSideOpenData.STREAM_CODEC.decode(buf)
                    NodeSideScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.INVENTORY_TERMINAL = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "inventory_terminal")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = InventoryTerminalOpenData.STREAM_CODEC.decode(buf)
                    InventoryTerminalMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.NETWORK_CONTROLLER = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "network_controller")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = NetworkControllerOpenData.STREAM_CODEC.decode(buf)
                    NetworkControllerMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.VARIABLE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "variable")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = VariableOpenData.STREAM_CODEC.decode(buf)
                    VariableMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.CRAFTING_CORE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "crafting_core")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = CraftingCoreOpenData.STREAM_CODEC.decode(buf)
                    CraftingCoreMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.PROCESSING_SET = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "processing_set")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = ProcessingSetOpenData.STREAM_CODEC.decode(buf)
                    ProcessingSetScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.PROCESSING_STORAGE = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "processing_storage")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = ProcessingStorageOpenData.STREAM_CODEC.decode(buf)
                    ProcessingStorageScreenHandler.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.BROADCAST_ANTENNA = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "broadcast_antenna")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.BroadcastAntennaOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.BroadcastAntennaMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.RECEIVER_ANTENNA = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "receiver_antenna")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.ReceiverAntennaOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.ReceiverAntennaMenu.clientFactory(syncId, inv, data)
                }
            )
            ModScreenHandlers.DIAGNOSTIC = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "diagnostic")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = damien.nodeworks.screen.DiagnosticOpenData.STREAM_CODEC.decode(buf)
                    damien.nodeworks.screen.DiagnosticMenu.clientFactory(syncId, inv, data)
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
        registrar.playToServer(SetStoragePriorityPayload.TYPE, SetStoragePriorityPayload.CODEC, NeoForgeTerminalPackets::handleSetStoragePriority)
        registrar.playToServer(OpenInstructionSetPayload.TYPE, OpenInstructionSetPayload.CODEC, NeoForgeTerminalPackets::handleOpenInstructionSet)
        registrar.playToServer(SetInstructionGridPayload.TYPE, SetInstructionGridPayload.CODEC, NeoForgeTerminalPackets::handleSetInstructionGrid)
        registrar.playToServer(InvTerminalClickPayload.TYPE, InvTerminalClickPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                val menu = player.containerMenu
                if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                    menu.handleGridClick(player, payload.itemId, payload.action)
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
                    }
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
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        tickCount++
        NeoForgeTerminalPackets.tickAll(event.server, tickCount)
        for (cache in damien.nodeworks.script.NetworkInventoryCache.getAll()) {
            cache.tick()
        }
        for (level in event.server.allLevels) {
            damien.nodeworks.script.MonitorUpdateHelper.tick(level, tickCount)
        }
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
}

class NeoForgeModStateService : ModStateService {
    override val tickCount: Long get() = Nodeworks.tickCount

    override fun isScriptRunning(level: ServerLevel, pos: BlockPos): Boolean {
        return NeoForgeTerminalPackets.getEngine(level, pos)?.isRunning() == true
    }

    override fun stopScript(level: ServerLevel, pos: BlockPos) {
        NeoForgeTerminalPackets.stopEngine(level, pos)
    }

    override fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos) {
        NeoForgeTerminalPackets.registerPendingAutoRun(level, pos)
    }

    override fun findAnyEngine(level: ServerLevel, terminalPositions: List<BlockPos>): Any? {
        return NeoForgeTerminalPackets.findAnyEngine(level, terminalPositions)
    }

    override fun findProcessingEngine(level: ServerLevel, terminalPositions: List<BlockPos>, cardName: String): Any? {
        return NeoForgeTerminalPackets.findEngineWithHandler(level, terminalPositions, cardName)
    }
}
