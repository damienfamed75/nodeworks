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
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
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
            ModScreenHandlers.RECIPE_CARD = Registry.register(
                BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath("nodeworks", "recipe_card")),
                IMenuTypeExtension.create { syncId, inv, buf ->
                    val data = RecipeCardOpenData.STREAM_CODEC.decode(buf)
                    RecipeCardScreenHandler.clientFactory(syncId, inv, data)
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
            ModScreenHandlers.initialize()
        }
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("nodeworks")

        // C2S payloads
        registrar.playToServer(RunScriptPayload.TYPE, RunScriptPayload.CODEC, NeoForgeTerminalPackets::handleRunScript)
        registrar.playToServer(StopScriptPayload.TYPE, StopScriptPayload.CODEC, NeoForgeTerminalPackets::handleStopScript)
        registrar.playToServer(SaveScriptPayload.TYPE, SaveScriptPayload.CODEC, NeoForgeTerminalPackets::handleSaveScript)
        registrar.playToServer(ToggleAutoRunPayload.TYPE, ToggleAutoRunPayload.CODEC, NeoForgeTerminalPackets::handleToggleAutoRun)
        registrar.playToServer(SetLayoutPayload.TYPE, SetLayoutPayload.CODEC, NeoForgeTerminalPackets::handleSetLayout)
        registrar.playToServer(SetStoragePriorityPayload.TYPE, SetStoragePriorityPayload.CODEC, NeoForgeTerminalPackets::handleSetStoragePriority)
        registrar.playToServer(OpenRecipeCardPayload.TYPE, OpenRecipeCardPayload.CODEC, NeoForgeTerminalPackets::handleOpenRecipeCard)
        registrar.playToServer(SetRecipeGridPayload.TYPE, SetRecipeGridPayload.CODEC, NeoForgeTerminalPackets::handleSetRecipeGrid)

        // S2C payloads
        registrar.playToClient(TerminalLogPayload.TYPE, TerminalLogPayload.CODEC) { payload, context ->
            context.enqueueWork {
                TerminalLogBuffer.addLog(payload.terminalPos, payload.message, payload.isError)
            }
        }
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        tickCount++
        NeoForgeTerminalPackets.tickAll(event.server, tickCount)
    }

    private fun onPlayerDisconnect(event: PlayerEvent.PlayerLoggedOutEvent) {
        NetworkWrenchItem.clearSelection(event.entity.uuid)
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
}
