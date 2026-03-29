package damien.nodeworks

import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.platform.*
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.registry.ModItems
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.screen.*
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

object Nodeworks : ModInitializer {
    const val MOD_ID = "nodeworks"
    private val logger = LoggerFactory.getLogger(MOD_ID)
    var tickCount = 0L
        private set

    override fun onInitialize() {
        // Initialize platform services BEFORE any registration
        PlatformServices.blockEntity = FabricBlockEntityService()
        PlatformServices.menu = FabricMenuService()
        PlatformServices.storage = FabricStorageService()
        PlatformServices.modState = FabricModStateService()

        // Register screen handler types (Fabric-specific ExtendedScreenHandlerType)
        ModScreenHandlers.TERMINAL = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "terminal")),
            ExtendedScreenHandlerType({ syncId, inv, data -> TerminalScreenHandler.clientFactory(syncId, inv, data) }, TerminalOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.INSTRUCTION_SET = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "instruction_set")),
            ExtendedScreenHandlerType({ syncId, inv, data -> InstructionSetScreenHandler.clientFactory(syncId, inv, data) }, InstructionSetOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.INSTRUCTION_STORAGE = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "instruction_storage")),
            ExtendedScreenHandlerType({ syncId, inv, data -> InstructionStorageScreenHandler.clientFactory(syncId, inv, data) }, InstructionStorageOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.NODE_SIDE = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "node_side")),
            ExtendedScreenHandlerType({ syncId, inv, data -> NodeSideScreenHandler.clientFactory(syncId, inv, data) }, NodeSideOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.INVENTORY_TERMINAL = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "inventory_terminal")),
            ExtendedScreenHandlerType({ syncId, inv, data -> InventoryTerminalMenu.clientFactory(syncId, inv, data) }, InventoryTerminalOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.NETWORK_CONTROLLER = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "network_controller")),
            ExtendedScreenHandlerType({ syncId, inv, data -> NetworkControllerMenu.clientFactory(syncId, inv, data) }, NetworkControllerOpenData.STREAM_CODEC)
        )

        ModScreenHandlers.VARIABLE = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "variable")),
            ExtendedScreenHandlerType({ syncId, inv, data -> VariableMenu.clientFactory(syncId, inv, data) }, VariableOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.CRAFTING_CORE = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "crafting_core")),
            ExtendedScreenHandlerType({ syncId, inv, data -> CraftingCoreMenu.clientFactory(syncId, inv, data) }, CraftingCoreOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.PROCESSING_API_CARD = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "processing_api_card")),
            ExtendedScreenHandlerType({ syncId, inv, data -> damien.nodeworks.screen.ProcessingApiCardScreenHandler.clientFactory(syncId, inv, data) }, damien.nodeworks.screen.ProcessingApiCardOpenData.STREAM_CODEC)
        )
        ModScreenHandlers.API_STORAGE = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, ResourceLocation.fromNamespaceAndPath("nodeworks", "api_storage")),
            ExtendedScreenHandlerType({ syncId, inv, data -> damien.nodeworks.screen.ApiStorageScreenHandler.clientFactory(syncId, inv, data) }, damien.nodeworks.screen.ApiStorageOpenData.STREAM_CODEC)
        )

        ModBlocks.initialize()
        ModBlockEntities.initialize()
        ModItems.initialize()
        ModScreenHandlers.initialize()

        TerminalPackets.registerPayloads()
        TerminalPackets.registerServerHandlers()

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            NetworkWrenchItem.clearSelection(handler.player.uuid)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickCount++
            TerminalPackets.tickAll(server, tickCount)
            for (cache in damien.nodeworks.script.NetworkInventoryCache.getAll()) {
                cache.tick()
            }
            for (level in server.allLevels) {
                damien.nodeworks.script.MonitorUpdateHelper.tick(level, tickCount)
            }
        }

        logger.info("Nodeworks initialized")
    }
}

class FabricModStateService : ModStateService {
    override val tickCount: Long get() = Nodeworks.tickCount

    override fun isScriptRunning(level: ServerLevel, pos: BlockPos): Boolean {
        return TerminalPackets.getEngine(level, pos)?.isRunning() == true
    }

    override fun stopScript(level: ServerLevel, pos: BlockPos) {
        TerminalPackets.stopEngine(level, pos)
    }

    override fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos) {
        TerminalPackets.registerPendingAutoRun(level, pos)
    }

    override fun findProcessingEngine(level: ServerLevel, terminalPositions: List<BlockPos>, outputItemId: String): Any? {
        return TerminalPackets.findEngineWithHandler(level, terminalPositions, outputItemId)
    }
}
