package damien.nodeworks.network

import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.script.ScriptEngine
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

object TerminalPackets {

    private val logger = LoggerFactory.getLogger("nodeworks-packets")

    // --- Packet types ---

    data class RunScriptPayload(val terminalPos: BlockPos, val scriptText: String) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<RunScriptPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "run_script"))
            val CODEC: StreamCodec<FriendlyByteBuf, RunScriptPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.terminalPos)
                    buf.writeUtf(payload.scriptText, 32767)
                },
                { buf -> RunScriptPayload(buf.readBlockPos(), buf.readUtf(32767)) }
            )
        }
        override fun type() = TYPE
    }

    data class StopScriptPayload(val terminalPos: BlockPos) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<StopScriptPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "stop_script"))
            val CODEC: StreamCodec<FriendlyByteBuf, StopScriptPayload> = CustomPacketPayload.codec(
                { payload, buf -> buf.writeBlockPos(payload.terminalPos) },
                { buf -> StopScriptPayload(buf.readBlockPos()) }
            )
        }
        override fun type() = TYPE
    }

    data class SaveScriptPayload(val terminalPos: BlockPos, val scriptText: String) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<SaveScriptPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "save_script"))
            val CODEC: StreamCodec<FriendlyByteBuf, SaveScriptPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.terminalPos)
                    buf.writeUtf(payload.scriptText, 32767)
                },
                { buf -> SaveScriptPayload(buf.readBlockPos(), buf.readUtf(32767)) }
            )
        }
        override fun type() = TYPE
    }

    data class ToggleAutoRunPayload(val terminalPos: BlockPos, val enabled: Boolean) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<ToggleAutoRunPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "toggle_autorun"))
            val CODEC: StreamCodec<FriendlyByteBuf, ToggleAutoRunPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.terminalPos)
                    buf.writeBoolean(payload.enabled)
                },
                { buf -> ToggleAutoRunPayload(buf.readBlockPos(), buf.readBoolean()) }
            )
        }
        override fun type() = TYPE
    }

    // --- Server → Client: log messages ---

    data class TerminalLogPayload(val terminalPos: BlockPos, val message: String, val isError: Boolean) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<TerminalLogPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "terminal_log"))
            val CODEC: StreamCodec<FriendlyByteBuf, TerminalLogPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.terminalPos)
                    buf.writeUtf(payload.message, 1024)
                    buf.writeBoolean(payload.isError)
                },
                { buf -> TerminalLogPayload(buf.readBlockPos(), buf.readUtf(1024), buf.readBoolean()) }
            )
        }
        override fun type() = TYPE
    }

    // --- Active script engines per terminal ---

    private val activeEngines = mutableMapOf<BlockPos, ScriptEngine>()

    fun getEngine(pos: BlockPos): ScriptEngine? = activeEngines[pos]

    // --- Registration ---

    fun registerPayloads() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(RunScriptPayload.TYPE, RunScriptPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(StopScriptPayload.TYPE, StopScriptPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(SaveScriptPayload.TYPE, SaveScriptPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(ToggleAutoRunPayload.TYPE, ToggleAutoRunPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(TerminalLogPayload.TYPE, TerminalLogPayload.CODEC)
    }

    fun registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(RunScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver

            // Save the script text
            terminal.setScriptText(payload.scriptText)

            // Find the network entry node
            val nodePos = terminal.getConnectedNodePos() ?: return@registerGlobalReceiver

            // Stop any existing engine
            activeEngines.remove(payload.terminalPos)?.stop()

            // Create and start new engine
            val terminalPos = payload.terminalPos
            val engine = ScriptEngine(level, nodePos) { message, isError ->
                // Send log to all nearby players
                val logPayload = TerminalLogPayload(terminalPos, message, isError)
                for (p in level.players()) {
                    if (p.distanceToSqr(terminalPos.x + 0.5, terminalPos.y + 0.5, terminalPos.z + 0.5) <= 64.0 * 64.0) {
                        ServerPlayNetworking.send(p, logPayload)
                    }
                }
                if (isError) logger.warn("[Terminal {}] {}", terminalPos, message)
            }

            if (engine.start(payload.scriptText)) {
                activeEngines[payload.terminalPos] = engine
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(StopScriptPayload.TYPE) { payload, context ->
            activeEngines.remove(payload.terminalPos)?.stop()
            logger.info("[Terminal {}] Script stopped", payload.terminalPos)
        }

        ServerPlayNetworking.registerGlobalReceiver(SaveScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.setScriptText(payload.scriptText)
        }

        ServerPlayNetworking.registerGlobalReceiver(ToggleAutoRunPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.setAutoRun(payload.enabled)
            logger.info("[Terminal {}] Auto-run {}", payload.terminalPos, if (payload.enabled) "enabled" else "disabled")
        }
    }

    /** Terminals pending auto-start after world load. Populated by TerminalBlockEntity.setLevel. */
    private val pendingAutoRun = mutableSetOf<BlockPos>()

    fun registerPendingAutoRun(pos: BlockPos) {
        pendingAutoRun.add(pos)
    }

    /** Called each tick — starts any pending auto-run terminals once chunks are ready. */
    private fun processPendingAutoRun(server: net.minecraft.server.MinecraftServer, tickCount: Long) {
        if (pendingAutoRun.isEmpty()) return
        // Wait a few ticks after load to let chunks settle
        if (tickCount < 20) return

        val toStart = pendingAutoRun.toList()
        pendingAutoRun.clear()

        for (pos in toStart) {
            for (level in server.allLevels) {
                if (!level.isLoaded(pos)) continue
                val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: continue
                if (!terminal.autoRun || terminal.scriptText.isBlank()) continue
                val nodePos = terminal.getConnectedNodePos() ?: continue

                // Don't restart if already running
                if (activeEngines.containsKey(pos)) continue

                val autoRunPos = pos
                val autoRunLevel = level
                val engine = ScriptEngine(level, nodePos) { message, isError ->
                    val logPayload = TerminalLogPayload(autoRunPos, message, isError)
                    for (p in autoRunLevel.players()) {
                        if (p.distanceToSqr(autoRunPos.x + 0.5, autoRunPos.y + 0.5, autoRunPos.z + 0.5) <= 64.0 * 64.0) {
                            ServerPlayNetworking.send(p, logPayload)
                        }
                    }
                    if (isError) logger.warn("[Terminal {}] {}", autoRunPos, message)
                }
                if (engine.start(terminal.scriptText)) {
                    activeEngines[pos] = engine
                    logger.info("[Terminal {}] Auto-run started", pos)
                }
                break
            }
        }
    }

    /** Called from a server tick event to drive all active script schedulers. */
    fun tickAll(server: net.minecraft.server.MinecraftServer, tickCount: Long) {
        processPendingAutoRun(server, tickCount)
        val toRemove = mutableListOf<BlockPos>()
        for ((pos, engine) in activeEngines) {
            if (!engine.isRunning()) {
                toRemove.add(pos)
                continue
            }
            engine.tick(tickCount)
            if (!engine.scheduler.hasActiveTasks() && engine.isRunning()) {
                // Script ran but registered no recurring tasks — stop it
                // (keeps one-shot scripts from lingering)
            }
        }
        toRemove.forEach { activeEngines.remove(it)?.stop() }
    }
}
