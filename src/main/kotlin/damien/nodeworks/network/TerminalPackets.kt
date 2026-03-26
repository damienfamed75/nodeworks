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

    data class OpenRecipeCardPayload(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<OpenRecipeCardPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "open_recipe_card"))
            val CODEC: StreamCodec<FriendlyByteBuf, OpenRecipeCardPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.nodePos)
                    buf.writeVarInt(payload.sideOrdinal)
                    buf.writeVarInt(payload.slotIndex)
                },
                { buf -> OpenRecipeCardPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()) }
            )
        }
        override fun type() = TYPE
    }

    data class SetStoragePriorityPayload(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int, val priority: Int) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<SetStoragePriorityPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_storage_priority"))
            val CODEC: StreamCodec<FriendlyByteBuf, SetStoragePriorityPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.nodePos)
                    buf.writeVarInt(payload.sideOrdinal)
                    buf.writeVarInt(payload.slotIndex)
                    buf.writeVarInt(payload.priority)
                },
                { buf -> SetStoragePriorityPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
            )
        }
        override fun type() = TYPE
    }

    data class SetLayoutPayload(val terminalPos: BlockPos, val layoutIndex: Int) : CustomPacketPayload {
        companion object {
            val TYPE: CustomPacketPayload.Type<SetLayoutPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_layout"))
            val CODEC: StreamCodec<FriendlyByteBuf, SetLayoutPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeBlockPos(payload.terminalPos)
                    buf.writeVarInt(payload.layoutIndex)
                },
                { buf -> SetLayoutPayload(buf.readBlockPos(), buf.readVarInt()) }
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

    // --- Active script engines per terminal (keyed by dimension + position) ---

    private val activeEngines = mutableMapOf<net.minecraft.core.GlobalPos, ScriptEngine>()

    fun getEngine(level: net.minecraft.server.level.ServerLevel, pos: BlockPos): ScriptEngine? =
        activeEngines[net.minecraft.core.GlobalPos.of(level.dimension(), pos)]

    fun getEngine(dimKey: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, pos: BlockPos): ScriptEngine? =
        activeEngines[net.minecraft.core.GlobalPos.of(dimKey, pos)]

    /** Stop and remove the engine for a terminal. Called when the block entity is removed. */
    fun stopEngine(level: ServerLevel, pos: BlockPos) {
        val gp = net.minecraft.core.GlobalPos.of(level.dimension(), pos)
        activeEngines.remove(gp)?.stop()
    }

    // --- Registration ---

    fun registerPayloads() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(RunScriptPayload.TYPE, RunScriptPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(StopScriptPayload.TYPE, StopScriptPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(SaveScriptPayload.TYPE, SaveScriptPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(ToggleAutoRunPayload.TYPE, ToggleAutoRunPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(SetLayoutPayload.TYPE, SetLayoutPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(SetStoragePriorityPayload.TYPE, SetStoragePriorityPayload.CODEC)
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(OpenRecipeCardPayload.TYPE, OpenRecipeCardPayload.CODEC)
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
            val globalPos = net.minecraft.core.GlobalPos.of(level.dimension(), payload.terminalPos)
            activeEngines.remove(globalPos)?.stop()

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
                activeEngines[globalPos] = engine
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(StopScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val gp = net.minecraft.core.GlobalPos.of(level.dimension(), payload.terminalPos)
            activeEngines.remove(gp)?.stop()
            logger.info("[Terminal {}] Script stopped", payload.terminalPos)
        }

        ServerPlayNetworking.registerGlobalReceiver(SaveScriptPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.setScriptText(payload.scriptText)
        }

        ServerPlayNetworking.registerGlobalReceiver(OpenRecipeCardPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val nodeEntity = level.getBlockEntity(payload.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity ?: return@registerGlobalReceiver
            val side = net.minecraft.core.Direction.entries[payload.sideOrdinal]
            val globalSlot = side.ordinal * damien.nodeworks.block.entity.NodeBlockEntity.SLOTS_PER_SIDE + payload.slotIndex
            val cardStack = nodeEntity.getItem(globalSlot)
            if (cardStack.item !is damien.nodeworks.card.RecipeCard) return@registerGlobalReceiver
            val recipe = damien.nodeworks.card.RecipeCard.getRecipe(cardStack)

            player.openMenu(object : net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory<damien.nodeworks.screen.RecipeCardOpenData> {
                override fun getScreenOpeningData(p: net.minecraft.server.level.ServerPlayer) =
                    damien.nodeworks.screen.RecipeCardOpenData(payload.nodePos, payload.sideOrdinal, payload.slotIndex, recipe)
                override fun getDisplayName() = net.minecraft.network.chat.Component.translatable("container.nodeworks.recipe_card")
                override fun createMenu(syncId: Int, inv: net.minecraft.world.entity.player.Inventory, p: net.minecraft.world.entity.player.Player) =
                    damien.nodeworks.screen.RecipeCardScreenHandler.createServer(syncId, inv, payload.nodePos, side, payload.slotIndex, cardStack)
            })
        }

        ServerPlayNetworking.registerGlobalReceiver(SetStoragePriorityPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val node = level.getBlockEntity(payload.nodePos) as? TerminalBlockEntity
            // Actually this is a NodeBlockEntity, not TerminalBlockEntity
            val nodeEntity = level.getBlockEntity(payload.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity ?: return@registerGlobalReceiver
            val side = net.minecraft.core.Direction.entries[payload.sideOrdinal]
            val slotIndex = payload.slotIndex
            val globalSlot = side.ordinal * damien.nodeworks.block.entity.NodeBlockEntity.SLOTS_PER_SIDE + slotIndex
            val stack = nodeEntity.getItem(globalSlot)
            if (stack.item is damien.nodeworks.card.StorageCard) {
                damien.nodeworks.card.StorageCard.setPriority(stack, payload.priority)
                nodeEntity.setChanged()
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SetLayoutPayload.TYPE) { payload, context ->
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            val terminal = level.getBlockEntity(payload.terminalPos) as? TerminalBlockEntity ?: return@registerGlobalReceiver
            terminal.setLayoutIndex(payload.layoutIndex)
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
    private val pendingAutoRun = mutableSetOf<net.minecraft.core.GlobalPos>()

    fun registerPendingAutoRun(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
        pendingAutoRun.add(net.minecraft.core.GlobalPos.of(level.dimension(), pos))
    }

    /** Called each tick — starts any pending auto-run terminals once chunks are ready. */
    private fun processPendingAutoRun(server: net.minecraft.server.MinecraftServer, tickCount: Long) {
        if (pendingAutoRun.isEmpty()) return
        // Wait a few ticks after load to let chunks settle
        if (tickCount < 20) return

        val toStart = pendingAutoRun.toList()
        pendingAutoRun.clear()

        for (gp in toStart) {
            val level = server.getLevel(gp.dimension()) ?: continue
            val pos = gp.pos()
            if (!level.isLoaded(pos)) continue
            val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: continue
            if (!terminal.autoRun || terminal.scriptText.isBlank()) continue
            val nodePos = terminal.getConnectedNodePos() ?: continue

            // Don't restart if already running
            if (activeEngines.containsKey(gp)) continue

            val engine = ScriptEngine(level, nodePos) { message, isError ->
                val logPayload = TerminalLogPayload(pos, message, isError)
                for (p in level.players()) {
                    if (p.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0 * 64.0) {
                        ServerPlayNetworking.send(p, logPayload)
                    }
                }
                if (isError) logger.warn("[Terminal {}] {}", pos, message)
            }
            if (engine.start(terminal.scriptText)) {
                activeEngines[gp] = engine
                logger.info("[Terminal {}] Auto-run started", pos)
            }
        }
    }

    /** Called from a server tick event to drive all active script schedulers. */
    fun tickAll(server: net.minecraft.server.MinecraftServer, tickCount: Long) {
        processPendingAutoRun(server, tickCount)
        val toRemove = mutableListOf<net.minecraft.core.GlobalPos>()
        for ((gp, engine) in activeEngines) {
            if (!engine.isRunning()) {
                toRemove.add(gp)
                continue
            }
            engine.tick(tickCount)
            if (!engine.scheduler.hasActiveTasks()) {
                // No recurring tasks left — script finished, clean up
                toRemove.add(gp)
            }
        }
        toRemove.forEach { activeEngines.remove(it)?.stop() }
    }
}
