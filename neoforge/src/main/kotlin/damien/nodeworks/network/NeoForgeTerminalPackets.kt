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

    /**
     * Start (or restart) a terminal's script engine. Used by the packet handler when the
     * player clicks Run in the Terminal GUI, by the redstone-pulse toggle on the Terminal
     * block, and by the auto-run scheduler on chunk load. Returns true if the engine
     * actually started, false if the terminal is missing, has no network entry, or the
     * script failed to compile.
     *
     * Any previously running engine for this position is stopped first, so this is safe
     * to call regardless of current state.
     */
    fun startEngine(level: ServerLevel, pos: BlockPos): Boolean {
        val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return false
        // Refuse to start a script that previously hit a wall-clock soft-abort.
        // A redstone clock pointed at a misbehaving terminal would otherwise re-
        // trigger the bad script every cycle, eating per-tick budget repeatedly.
        // The player has to actually edit the script (which clears [lastError]
        // in [TerminalBlockEntity.setScript]) before it's eligible to run again.
        if (terminal.lastError != null) {
            val msg = TerminalLogPayload(
                pos,
                "Cannot run, this terminal's script previously timed out: ${terminal.lastError}. Edit the script to re-enable.",
                true,
            )
            for (p in level.players()) {
                if (p.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0 * 64.0) {
                    PacketDistributor.sendToPlayer(p, msg)
                }
            }
            return false
        }
        val nodePos = terminal.getNetworkStartPos() ?: return false
        val gp = GlobalPos.of(level.dimension(), pos)
        activeEngines.remove(gp)?.stop()

        val engine = ScriptEngine(level, nodePos, pos) { message, isError ->
            if (isError) damien.nodeworks.script.NetworkErrorBuffer.addError(pos, message, level.server.tickCount.toLong())
            // Cap script-originated print output so a single absurd log line can't blow past
            // the network string length and disconnect the player. Truncation happens here
            // (once, at the send site) rather than in the codec, so the original message is
            // still available to NetworkErrorBuffer above for in-world diagnostic display.
            val safeMessage = if (message.length > TerminalLogPayload.MAX_LOG_CHARS) {
                message.take(TerminalLogPayload.MAX_LOG_CHARS) +
                    "\n…(truncated, ${message.length - TerminalLogPayload.MAX_LOG_CHARS} more chars)"
            } else message
            val logPayload = TerminalLogPayload(pos, safeMessage, isError)
            for (p in level.players()) {
                if (p.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0 * 64.0) {
                    PacketDistributor.sendToPlayer(p, logPayload)
                } else if (isError && p.containerMenu is damien.nodeworks.screen.DiagnosticMenu) {
                    val diagMenu = p.containerMenu as damien.nodeworks.screen.DiagnosticMenu
                    if (diagMenu.topology.terminalInfos.any { it.pos == pos }) {
                        PacketDistributor.sendToPlayer(p, logPayload)
                    }
                }
            }
            // Terminal errors surface to the player via the TerminalLogPayload above and
            // to the Diagnostic Tool's Jobs tab via NetworkErrorBuffer, they don't
            // belong in the server console.
        }
        activeEngines[gp] = engine
        if (!engine.start(terminal.getScriptsCopy())) {
            activeEngines.remove(gp)
            return false
        }
        return true
    }

    fun findAnyEngine(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        overrideDimension: ResourceKey<Level>? = null,
    ): ScriptEngine? {
        val dimKey = overrideDimension ?: level.dimension()
        for (pos in terminalPositions) {
            val engine = activeEngines[GlobalPos.of(dimKey, pos)] ?: continue
            if (engine.isRunning()) return engine
        }
        return null
    }

    /** Find the first active engine on the given network that has a processing handler for the given card name.
     *  [overrideDimension] lets callers search a different dimension than `level.dimension()`, required when
     *  the terminal positions come from a cross-dimensional Receiver Antenna. */
    fun findEngineWithHandler(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        cardName: String,
        overrideDimension: ResourceKey<Level>? = null,
    ): ScriptEngine? {
        val dimKey = overrideDimension ?: level.dimension()
        for (pos in terminalPositions) {
            val engine = activeEngines[GlobalPos.of(dimKey, pos)] ?: continue
            if (engine.isRunning() && engine.processingHandlers.containsKey(cardName)) {
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
            startEngine(level, payload.terminalPos)
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
            val subs = InstructionSet.getSubstitutions(cardStack)

            PlatformServices.menu.openExtendedMenu(
                serverPlayer,
                Component.translatable("container.nodeworks.instruction_set"),
                InstructionSetOpenData(payload.nodePos, payload.sideOrdinal, payload.slotIndex, recipe, subs),
                InstructionSetOpenData.STREAM_CODEC
            ) { syncId, inv, p ->
                InstructionSetScreenHandler.createServer(syncId, inv, payload.nodePos, side, payload.slotIndex, cardStack)
            }
        }
    }

    fun handleSetLayout(payload: SetLayoutPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val entity = level.getBlockEntity(payload.terminalPos)
            when (entity) {
                is TerminalBlockEntity -> entity.setLayoutIndex(payload.layoutIndex)
                is damien.nodeworks.block.entity.InventoryTerminalBlockEntity -> {
                    entity.layoutIndex = payload.layoutIndex
                    entity.setChanged()
                }
            }
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
            if (activeEngines.containsKey(gp)) continue
            if (startEngine(level, pos)) logger.info("[Terminal {}] Auto-run started", pos)
        }
    }

    fun tickAll(server: MinecraftServer, tickCount: Long) {
        processPendingAutoRun(server, tickCount)
        // Snapshot before iterating: `engine.tick` may run Lua that reenters this class
        // (e.g. a RedstoneCard write triggers a neighbour-block update → Terminal's
        // neighborChanged → startEngine, which mutates activeEngines), and that would
        // ConcurrentModifyException the live iteration. Also re-resolve the engine from
        // the map each iteration so a snapshot'd entry that was replaced mid-tick isn't
        // double-ticked.
        //
        // Per-tick budget enforcement (CFS-inspired):
        //   - globalTickBudgetMs caps total Lua time across all engines this tick.
        //     When exhausted, remaining engines defer their work to the next tick.
        //   - localTickBudgetMs caps a single engine's slice. Bounds the worst case
        //     where one engine has many handlers and could otherwise consume the
        //     full global budget alone.
        //   - Engines are sorted by accumulated vruntime ascending, well-behaved
        //     scripts (low vruntime) run first, so a runaway engine eating its
        //     full local budget every tick gets deprioritised vs a neighbour that
        //     normally finishes in microseconds. Same idea Linux CFS uses.
        val settings = damien.nodeworks.script.ServerPolicy.current
        val tickStartNs = System.nanoTime()
        val globalDeadlineNs = tickStartNs + settings.globalTickBudgetMs * 1_000_000L
        val localBudgetNs = settings.localTickBudgetMs * 1_000_000L

        val toRemove = mutableListOf<GlobalPos>()
        val snapshot = activeEngines.entries
            .toList()
            .sortedBy { it.value.vruntimeNs }
        for ((gp, _) in snapshot) {
            // Re-resolve in case a reentrant startEngine replaced the entry.
            val engine = activeEngines[gp] ?: continue
            if (!engine.isRunning()) {
                toRemove.add(gp)
                continue
            }
            // Stop dispatching once the shared global budget is gone. Engines we
            // didn't reach this tick stay in `activeEngines` and pick up next tick
            // (sorted by vruntime so they get fair priority).
            val nowNs = System.nanoTime()
            if (nowNs >= globalDeadlineNs) break

            val perEngineDeadlineNs = minOf(nowNs + localBudgetNs, globalDeadlineNs)
            val before = nowNs
            engine.tick(tickCount, perEngineDeadlineNs)
            engine.vruntimeNs += System.nanoTime() - before

            if (!engine.hasWork()) {
                toRemove.add(gp)
            }
        }
        // Only remove if the entry is still the same engine we were about to retire,
        // a reentrant startEngine may have installed a fresh one at this key.
        for (gp in toRemove) {
            val engine = activeEngines[gp] ?: continue
            if (!engine.isRunning() || !engine.hasWork()) {
                activeEngines.remove(gp)?.stop()
            }
        }
    }
}
