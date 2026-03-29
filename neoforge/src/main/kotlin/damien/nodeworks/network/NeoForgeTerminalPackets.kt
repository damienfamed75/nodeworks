package damien.nodeworks.network

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.card.InstructionSet
import damien.nodeworks.card.StorageCard
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.InstructionSetOpenData
import damien.nodeworks.screen.InstructionSetScreenHandler
import damien.nodeworks.script.ScriptEngine
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.LoggerFactory

object NeoForgeTerminalPackets {

    private val logger = LoggerFactory.getLogger("nodeworks-packets")

    // --- Active script engines per terminal (keyed by dimension + position) ---

    private val activeEngines = mutableMapOf<GlobalPos, ScriptEngine>()

    fun getEngine(level: ServerLevel, pos: BlockPos): ScriptEngine? =
        activeEngines[GlobalPos.of(level.dimension(), pos)]

    fun getEngine(dimKey: ResourceKey<Level>, pos: BlockPos): ScriptEngine? =
        activeEngines[GlobalPos.of(dimKey, pos)]

    fun stopEngine(level: ServerLevel, pos: BlockPos) {
        val gp = GlobalPos.of(level.dimension(), pos)
        activeEngines.remove(gp)?.stop()
    }

    /** Find the first active engine on the given network that has a processing handler for the output item ID. */
    fun findEngineWithHandler(level: ServerLevel, terminalPositions: List<BlockPos>, outputItemId: String): ScriptEngine? {
        val dimKey = level.dimension()
        for (pos in terminalPositions) {
            val engine = activeEngines[GlobalPos.of(dimKey, pos)] ?: continue
            if (engine.isRunning() && engine.processingHandlers.containsKey(outputItemId)) {
                return engine
            }
        }
        return null
    }

    // --- Server handlers ---

    fun handleRunScript(payload: RunScriptPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@enqueueWork

            val nodePos = terminal.getNetworkStartPos() ?: return@enqueueWork

            val globalPos = GlobalPos.of(level.dimension(), payload.terminalPos)
            activeEngines.remove(globalPos)?.stop()

            val terminalPos = payload.terminalPos
            val engine = ScriptEngine(level, nodePos) { message, isError ->
                val logPayload = TerminalLogPayload(terminalPos, message, isError)
                for (p in level.players()) {
                    if (p.distanceToSqr(terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5) <= 64.0 * 64.0) {
                        PacketDistributor.sendToPlayer(p, logPayload)
                    }
                }
                if (isError) logger.warn("[Terminal {}] {}", terminalPos, message)
            }

            if (engine.start(terminal.getScriptsCopy())) {
                activeEngines[globalPos] = engine
            }
        }
    }

    fun handleStopScript(payload: StopScriptPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val gp = GlobalPos.of(level.dimension(), payload.terminalPos)
            activeEngines.remove(gp)?.stop()
            logger.info("[Terminal {}] Script stopped", payload.terminalPos)
        }
    }

    fun handleSaveScript(payload: SaveScriptPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@enqueueWork
            terminal.setScript(payload.scriptName, payload.scriptText)
        }
    }

    fun handleCreateScriptTab(payload: CreateScriptTabPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@enqueueWork
            terminal.createScript(payload.scriptName)
        }
    }

    fun handleDeleteScriptTab(payload: DeleteScriptTabPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@enqueueWork
            terminal.deleteScript(payload.scriptName)
        }
    }

    fun handleOpenInstructionSet(payload: OpenInstructionSetPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val serverPlayer = player as? net.minecraft.server.level.ServerPlayer ?: return@enqueueWork
            val nodeEntity = level.getBlockEntity(payload.nodePos) as? NodeBlockEntity ?: return@enqueueWork
            val side = Direction.entries[payload.sideOrdinal]
            val globalSlot = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE + payload.slotIndex
            val cardStack = nodeEntity.getItem(globalSlot)
            if (cardStack.item !is InstructionSet) return@enqueueWork
            val recipe = InstructionSet.getRecipe(cardStack)

            PlatformServices.menu.openExtendedMenu(
                serverPlayer,
                Component.translatable("container.nodeworks.instruction_set"),
                InstructionSetOpenData(payload.nodePos, payload.sideOrdinal, payload.slotIndex, recipe),
                InstructionSetOpenData.STREAM_CODEC
            ) { syncId, inv, p ->
                InstructionSetScreenHandler.createServer(syncId, inv, payload.nodePos, side, payload.slotIndex, cardStack)
            }
        }
    }

    fun handleSetStoragePriority(payload: SetStoragePriorityPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val nodeEntity = level.getBlockEntity(payload.nodePos) as? NodeBlockEntity ?: return@enqueueWork
            val side = Direction.entries[payload.sideOrdinal]
            val globalSlot = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE + payload.slotIndex
            val stack = nodeEntity.getItem(globalSlot)
            if (stack.item is StorageCard) {
                StorageCard.setPriority(stack, payload.priority)
                nodeEntity.setChanged()
            }
        }
    }

    fun handleSetLayout(payload: SetLayoutPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@enqueueWork
            terminal.setLayoutIndex(payload.layoutIndex)
        }
    }

    fun handleToggleAutoRun(payload: ToggleAutoRunPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@enqueueWork
            terminal.setAutoRun(payload.enabled)
            logger.info("[Terminal {}] Auto-run {}", payload.terminalPos, if (payload.enabled) "enabled" else "disabled")
        }
    }

    fun handleSetInstructionGrid(payload: SetInstructionGridPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val menu = player.containerMenu
            if (menu is InstructionSetScreenHandler && menu.containerId == payload.containerId) {
                menu.setRecipeFromIds(payload.items)
            }
        }
    }

    // --- Auto-run ---

    private val pendingAutoRun = mutableSetOf<GlobalPos>()

    fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos) {
        pendingAutoRun.add(GlobalPos.of(level.dimension(), pos))
    }

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
                val logPayload = TerminalLogPayload(pos, message, isError)
                for (p in level.players()) {
                    if (p.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0 * 64.0) {
                        PacketDistributor.sendToPlayer(p, logPayload)
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
