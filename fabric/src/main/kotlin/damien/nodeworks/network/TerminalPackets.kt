package damien.nodeworks.network

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.card.InstructionSet
import damien.nodeworks.card.StorageCard
import damien.nodeworks.platform.MenuService
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.InstructionSetOpenData
import damien.nodeworks.screen.InstructionSetScreenHandler
import damien.nodeworks.script.ScriptEngine
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

object TerminalPackets {

    private val logger = LoggerFactory.getLogger("nodeworks-packets")

    // --- Active script engines per terminal (keyed by dimension + position) ---

    private val activeEngines = mutableMapOf<GlobalPos, ScriptEngine>()

    fun getEngine(level: ServerLevel, pos: BlockPos): ScriptEngine? =
        activeEngines[GlobalPos.of(level.dimension(), pos)]

    fun getEngine(dimKey: ResourceKey<Level>, pos: BlockPos): ScriptEngine? =
        activeEngines[GlobalPos.of(dimKey, pos)]

    /** Find any active engine at the given positions. */
    fun findAnyEngine(level: ServerLevel, terminalPositions: List<BlockPos>): ScriptEngine? {
        val dimKey = level.dimension()
        for (pos in terminalPositions) {
            val engine = activeEngines[GlobalPos.of(dimKey, pos)] ?: continue
            if (engine.isRunning()) return engine
        }
        return null
    }

    /** Find the first active engine on the given network that has a processing handler for the given card name. */
    fun findEngineWithHandler(level: ServerLevel, terminalPositions: List<BlockPos>, cardName: String): ScriptEngine? {
        val dimKey = level.dimension()
        for (pos in terminalPositions) {
            val engine = activeEngines[GlobalPos.of(dimKey, pos)] ?: continue
            if (engine.isRunning() && engine.processingHandlers.containsKey(cardName)) {
                return engine
            }
        }
        return null
    }

    /** Stop and remove the engine for a terminal. Called when the block entity is removed. */
    fun stopEngine(level: ServerLevel, pos: BlockPos) {
        val gp = GlobalPos.of(level.dimension(), pos)
        activeEngines.remove(gp)?.stop()
    }

    // --- Registration ---

    fun registerPayloads() {
        PayloadTypeRegistry.playC2S().register(RunScriptPayload.TYPE, RunScriptPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(StopScriptPayload.TYPE, StopScriptPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SaveScriptPayload.TYPE, SaveScriptPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CreateScriptTabPayload.TYPE, CreateScriptTabPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DeleteScriptTabPayload.TYPE, DeleteScriptTabPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ToggleAutoRunPayload.TYPE, ToggleAutoRunPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SetLayoutPayload.TYPE, SetLayoutPayload.CODEC)
        // SetStoragePriorityPayload removed — priority is now per-card via StorageCard GUI
        PayloadTypeRegistry.playC2S().register(OpenInstructionSetPayload.TYPE, OpenInstructionSetPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SetInstructionGridPayload.TYPE, SetInstructionGridPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalClickPayload.TYPE, InvTerminalClickPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalSlotClickPayload.TYPE, InvTerminalSlotClickPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalCraftGridPayload.TYPE, InvTerminalCraftGridPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalDistributePayload.TYPE, InvTerminalDistributePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalCollectPayload.TYPE, InvTerminalCollectPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalCraftPayload.TYPE, InvTerminalCraftPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ControllerSettingsPayload.TYPE, ControllerSettingsPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(VariableSettingsPayload.TYPE, VariableSettingsPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SetProcessingApiDataPayload.TYPE, SetProcessingApiDataPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SetProcessingApiSlotPayload.TYPE, SetProcessingApiSlotPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(InvTerminalCraftGridActionPayload.TYPE, InvTerminalCraftGridActionPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CancelCraftPayload.TYPE, CancelCraftPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CraftPreviewRequestPayload.TYPE, CraftPreviewRequestPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(TerminalLogPayload.TYPE, TerminalLogPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(InventorySyncPayload.TYPE, InventorySyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BufferSyncPayload.TYPE, BufferSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CraftPreviewResponsePayload.TYPE, CraftPreviewResponsePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CraftQueueSyncPayload.TYPE, CraftQueueSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CraftingCpuTreePayload.TYPE, CraftingCpuTreePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(DebugCraftingCorePayload.TYPE, DebugCraftingCorePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(DebugInventoryTerminalPayload.TYPE, DebugInventoryTerminalPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CraftRequestErrorPayload.TYPE, CraftRequestErrorPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SwitchNodeSidePayload.TYPE, SwitchNodeSidePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CraftQueueExtractPayload.TYPE, CraftQueueExtractPayload.CODEC)
    }

    fun registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(RunScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver

            val nodePos = terminal.getNetworkStartPos() ?: return@registerGlobalReceiver

            val globalPos = GlobalPos.of(level.dimension(), payload.terminalPos)
            activeEngines.remove(globalPos)?.stop()

            val terminalPos = payload.terminalPos
            val engine = ScriptEngine(level, nodePos) { message, isError ->
                if (isError) damien.nodeworks.script.NetworkErrorBuffer.addError(terminalPos, message, level.server.tickCount.toLong())
                val logPayload = TerminalLogPayload(terminalPos, message, isError)
                for (p in level.players()) {
                    // Send to nearby players (existing behavior)
                    if (p.distanceToSqr(terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5) <= 64.0 * 64.0) {
                        ServerPlayNetworking.send(p, logPayload)
                    }
                    // Fan out to players with open diagnostic tool on this network
                    else if (isError && p.containerMenu is damien.nodeworks.screen.DiagnosticMenu) {
                        val diagMenu = p.containerMenu as damien.nodeworks.screen.DiagnosticMenu
                        if (diagMenu.topology.terminalInfos.any { it.pos == terminalPos }) {
                            ServerPlayNetworking.send(p, logPayload)
                        }
                    }
                }
                if (isError) logger.warn("[Terminal {}] {}", terminalPos, message)
            }

            if (engine.start(terminal.getScriptsCopy())) {
                activeEngines[globalPos] = engine
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(StopScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val gp = GlobalPos.of(level.dimension(), payload.terminalPos)
            activeEngines.remove(gp)?.stop()
            logger.info("[Terminal {}] Script stopped", payload.terminalPos)
        }

        ServerPlayNetworking.registerGlobalReceiver(SaveScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.setScript(payload.scriptName, payload.scriptText)
        }

        ServerPlayNetworking.registerGlobalReceiver(CreateScriptTabPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.createScript(payload.scriptName)
        }

        ServerPlayNetworking.registerGlobalReceiver(DeleteScriptTabPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.deleteScript(payload.scriptName)
        }

        ServerPlayNetworking.registerGlobalReceiver(OpenInstructionSetPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val nodeEntity = level.getBlockEntity(payload.nodePos) as? NodeBlockEntity ?: return@registerGlobalReceiver
            val side = Direction.entries[payload.sideOrdinal]
            val globalSlot = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE + payload.slotIndex
            val cardStack = nodeEntity.getItem(globalSlot)
            if (cardStack.item !is InstructionSet) return@registerGlobalReceiver
            val recipe = InstructionSet.getRecipe(cardStack)

            PlatformServices.menu.openExtendedMenu(
                player,
                Component.translatable("container.nodeworks.instruction_set"),
                InstructionSetOpenData(payload.nodePos, payload.sideOrdinal, payload.slotIndex, recipe),
                InstructionSetOpenData.STREAM_CODEC
            ) { syncId, inv, p ->
                InstructionSetScreenHandler.createServer(syncId, inv, payload.nodePos, side, payload.slotIndex, cardStack)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SwitchNodeSidePayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.NodeSideScreenHandler) {
                val side = Direction.entries.getOrNull(payload.sideOrdinal) ?: return@registerGlobalReceiver
                menu.switchSide(side)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SetLayoutPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val entity = level.getBlockEntity(payload.terminalPos)
            when (entity) {
                is TerminalBlockEntity -> entity.setLayoutIndex(payload.layoutIndex)
                is damien.nodeworks.block.entity.InventoryTerminalBlockEntity -> {
                    entity.layoutIndex = payload.layoutIndex
                    entity.setChanged()
                }
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(ToggleAutoRunPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.setAutoRun(payload.enabled)
            logger.info("[Terminal {}] Auto-run {}", payload.terminalPos, if (payload.enabled) "enabled" else "disabled")
        }

        ServerPlayNetworking.registerGlobalReceiver(SetInstructionGridPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is InstructionSetScreenHandler && menu.containerId == payload.containerId) {
                menu.setRecipeFromIds(payload.items)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalClickPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleGridClick(player, payload.itemId, payload.action)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalSlotClickPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handlePlayerSlotClick(player, payload.slotIndex, payload.action)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalCraftPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleCraftRequest(player, payload.itemId, payload.count)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalCollectPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleCollect(player, payload.itemId)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CraftQueueExtractPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleQueueExtract(player, payload.entryId, payload.action)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalCraftGridActionPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleCraftGridAction(player, payload.action)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalDistributePayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleDistribute(player, payload.slotType, payload.slotIndices)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(InvTerminalCraftGridPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.InventoryTerminalMenu && menu.containerId == payload.containerId) {
                menu.handleCraftGridFill(player, payload.grid)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(ControllerSettingsPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.NetworkControllerBlockEntity ?: return@registerGlobalReceiver
            if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@registerGlobalReceiver
            when (payload.key) {
                "color" -> entity.networkColor = payload.intValue
                "redstone" -> entity.redstoneMode = payload.intValue
                "glow" -> entity.nodeGlowStyle = payload.intValue
                "name" -> entity.networkName = payload.strValue
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(VariableSettingsPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.VariableBlockEntity ?: return@registerGlobalReceiver
            if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@registerGlobalReceiver
            when (payload.key) {
                "name" -> entity.variableName = payload.strValue
                "type" -> entity.setType(damien.nodeworks.block.entity.VariableType.fromOrdinal(payload.intValue))
                "value" -> entity.setValue(payload.strValue)
                "toggle" -> entity.toggleValue()
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SetProcessingApiDataPayload.TYPE) { payload, context ->
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

        ServerPlayNetworking.registerGlobalReceiver(SetProcessingApiSlotPayload.TYPE) { payload, context ->
            val player = context.player()
            val menu = player.containerMenu
            if (menu is damien.nodeworks.screen.ProcessingSetScreenHandler && menu.containerId == payload.containerId) {
                menu.setSlotFromId(payload.slotIndex, payload.itemId)
            }
        }


        ServerPlayNetworking.registerGlobalReceiver(CancelCraftPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            if (!player.blockPosition().closerThan(payload.pos, 8.0)) return@registerGlobalReceiver
            val entity = level.getBlockEntity(payload.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity ?: return@registerGlobalReceiver
            entity.cancelJob()
        }

        ServerPlayNetworking.registerGlobalReceiver(CraftPreviewRequestPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, payload.networkPos)
            val tree = damien.nodeworks.script.CraftTreeBuilder.buildCraftTree(payload.itemId, 1, level, snapshot)
            ServerPlayNetworking.send(player, CraftPreviewResponsePayload(payload.containerId, tree))
        }
    }

    /** Terminals pending auto-start after world load. Populated by TerminalBlockEntity.setLevel. */
    private val pendingAutoRun = mutableSetOf<GlobalPos>()

    fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos) {
        pendingAutoRun.add(GlobalPos.of(level.dimension(), pos))
    }

    /** Called each tick -- starts any pending auto-run terminals once chunks are ready. */
    private fun processPendingAutoRun(server: MinecraftServer, tickCount: Long) {
        if (pendingAutoRun.isEmpty()) return
        if (tickCount < 20) return

        val toStart = pendingAutoRun.toList()
        pendingAutoRun.clear()

        for (gp in toStart) {
            val level = server.getLevel(gp.dimension()) ?: continue
            val pos = gp.pos()
            if (!level.isLoaded(pos)) continue
            val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: continue
            if (!terminal.autoRun || terminal.scriptText.isBlank()) continue
            val nodePos = terminal.getNetworkStartPos() ?: continue

            if (activeEngines.containsKey(gp)) continue

            val engine = ScriptEngine(level, nodePos) { message, isError ->
                if (isError) damien.nodeworks.script.NetworkErrorBuffer.addError(pos, message, level.server.tickCount.toLong())
                val logPayload = TerminalLogPayload(pos, message, isError)
                for (p in level.players()) {
                    if (p.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0 * 64.0) {
                        ServerPlayNetworking.send(p, logPayload)
                    } else if (isError && p.containerMenu is damien.nodeworks.screen.DiagnosticMenu) {
                        val diagMenu = p.containerMenu as damien.nodeworks.screen.DiagnosticMenu
                        if (diagMenu.topology.terminalInfos.any { it.pos == pos }) {
                            ServerPlayNetworking.send(p, logPayload)
                        }
                    }
                }
                if (isError) logger.warn("[Terminal {}] {}", pos, message)
            }
            if (engine.start(terminal.getScriptsCopy())) {
                activeEngines[gp] = engine
                logger.info("[Terminal {}] Auto-run started", pos)
            }
        }
    }

    /** Called from a server tick event to drive all active script schedulers. */
    fun tickAll(server: MinecraftServer, tickCount: Long) {
        processPendingAutoRun(server, tickCount)
        val toRemove = mutableListOf<GlobalPos>()
        for ((gp, engine) in activeEngines) {
            if (!engine.isRunning()) {
                toRemove.add(gp)
                continue
            }
            engine.tick(tickCount)
            if (!engine.hasWork()) {
                toRemove.add(gp)
            }
        }
        toRemove.forEach { activeEngines.remove(it)?.stop() }
    }
}
