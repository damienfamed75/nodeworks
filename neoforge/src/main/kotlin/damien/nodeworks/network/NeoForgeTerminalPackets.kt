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

    // --- Active script engines, one per Script Runner slot ---

    data class EngineKey(val pos: GlobalPos, val slot: Int)

    private val activeEngines = mutableMapOf<EngineKey, ScriptEngine>()

    private fun key(dimKey: ResourceKey<Level>, pos: BlockPos, slot: Int) = EngineKey(GlobalPos.of(dimKey, pos), slot)

    /** Every engine hosted by the Runner at [pos], running or not. */
    fun enginesAt(dimKey: ResourceKey<Level>, pos: BlockPos): List<ScriptEngine> {
        val gp = GlobalPos.of(dimKey, pos)
        return (0 until TerminalBlockEntity.SLOT_COUNT).mapNotNull { activeEngines[EngineKey(gp, it)] }
    }

    fun enginesAt(level: ServerLevel, pos: BlockPos): List<ScriptEngine> = enginesAt(level.dimension(), pos)

    /** The first running engine at [pos], for callers that want one representative. */
    fun getEngine(level: ServerLevel, pos: BlockPos): ScriptEngine? =
        enginesAt(level.dimension(), pos).firstOrNull { it.isRunning() }

    fun getEngine(dimKey: ResourceKey<Level>, pos: BlockPos): ScriptEngine? =
        enginesAt(dimKey, pos).firstOrNull { it.isRunning() }

    fun getSlotEngine(level: ServerLevel, pos: BlockPos, slot: Int): ScriptEngine? =
        activeEngines[key(level.dimension(), pos, slot)]

    fun isSlotRunning(level: ServerLevel, pos: BlockPos, slot: Int): Boolean =
        getSlotEngine(level, pos, slot)?.isRunning() == true

    fun stopSlot(level: ServerLevel, pos: BlockPos, slot: Int) {
        activeEngines.remove(key(level.dimension(), pos, slot))?.stop()
    }

    /** Stop every slot of the Runner at [pos]. */
    fun stopEngine(level: ServerLevel, pos: BlockPos) {
        for (slot in 0 until TerminalBlockEntity.SLOT_COUNT) stopSlot(level, pos, slot)
    }

    /**
     * Start every slot the Runner at [pos] would start on its own: enabled, holding a
     * program, without a recorded fault. Used by the redstone pulse and by auto-run on
     * chunk load. True when at least one slot started.
     */
    fun startEngine(level: ServerLevel, pos: BlockPos): Boolean {
        val runner = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return false
        var any = false
        for (slot in runner.autoStartSlots()) if (startSlot(level, pos, slot)) any = true
        return any
    }

    /**
     * Start (or restart) one slot. False when the Runner is missing, the slot holds no
     * program, the slot carries a timeout fault, the Runner has no network entry, or the
     * script failed to compile. Any engine already in the slot is stopped first.
     */
    fun startSlot(level: ServerLevel, pos: BlockPos, slot: Int): Boolean {
        val runner = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return false
        val program = runner.programAt(slot) ?: return false
        val label = program.name
        val fault = runner.lastError(slot)
        if (fault != null) {
            sendLog(level, pos, "[$label] Cannot run, this script previously timed out: $fault. Enable the slot again or swap the Script.", true)
            return false
        }
        val nodePos = runner.getNetworkStartPos()
        if (nodePos == null) {
            sendLog(level, pos, "[$label] The Runner is not connected to a network.", true)
            return false
        }
        val k = key(level.dimension(), pos, slot)
        activeEngines.remove(k)?.stop()

        val engine = ScriptEngine(level, nodePos, pos, runner.host(slot)) { message, isError ->
            val tagged = "[$label] $message"
            if (isError) damien.nodeworks.script.NetworkErrorBuffer.addError(pos, tagged, level.server.tickCount.toLong())
            sendLog(level, pos, tagged, isError)
        }
        activeEngines[k] = engine
        if (!engine.start(program.tabs)) {
            activeEngines.remove(k)
            return false
        }
        return true
    }

    /** A Runner log line goes to players near the block and to anyone watching it in a
     *  Diagnostic Tool. Print output is capped here so one absurd line cannot blow past
     *  the network string limit and disconnect the player. */
    private fun sendLog(level: ServerLevel, pos: BlockPos, message: String, isError: Boolean) {
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
    }

    fun findAnyEngine(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        overrideDimension: ResourceKey<Level>? = null,
    ): ScriptEngine? {
        val dimKey = overrideDimension ?: level.dimension()
        for (pos in terminalPositions) {
            for (engine in enginesAt(dimKey, pos)) if (engine.isRunning()) return engine
        }
        return null
    }

    /** Find the first active engine on the given network that has a processing handler for the given card name.
     *  [overrideDimension] lets callers search a different dimension than `level.dimension()`, required when
     *  the Runner positions come from a cross-dimensional Receiver Antenna. */
    fun findEngineWithHandler(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        cardName: String,
        overrideDimension: ResourceKey<Level>? = null,
        api: damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo? = null,
    ): ScriptEngine? {
        val dimKey = overrideDimension ?: level.dimension()
        for (pos in terminalPositions) {
            for (engine in enginesAt(dimKey, pos)) {
                if (engine.isRunning() && engine.processingHandlers.containsKey(cardName)) return engine
            }
        }
        // Rules only after every Runner had its say, an exact handler on any of them
        // beats a rule on another.
        if (api == null) return null
        for (pos in terminalPositions) {
            for (engine in enginesAt(dimKey, pos)) {
                if (engine.isRunning() && engine.handlerFor(cardName, api) != null) return engine
            }
        }
        return null
    }

    // --- Server handlers ---

    /** These packets act on a position the client names, so a modified client could
     *  target any loaded terminal in the dimension. The GUIs that send them close past
     *  8 blocks, the extra margin covers client and server disagreeing on position. */
    private const val MAX_PACKET_DISTANCE_SQ = 16.0 * 16.0

    private fun inReach(player: net.minecraft.world.entity.player.Player, pos: BlockPos): Boolean =
        player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= MAX_PACKET_DISTANCE_SQ

    fun handleOpenInstructionSet(payload: OpenInstructionSetPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            if (!inReach(player, payload.nodePos)) return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val serverPlayer = player as? net.minecraft.server.level.ServerPlayer ?: return@enqueueWork
            val nodeEntity = level.getBlockEntity(payload.nodePos) as? NodeBlockEntity ?: return@enqueueWork
            val side = Direction.entries[payload.sideOrdinal]
            val globalSlot = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE + payload.slotIndex
            val cardStack = nodeEntity.getItem(globalSlot)
            if (cardStack.item !is InstructionSet) return@enqueueWork
            val recipe = InstructionSet.getRecipeStacks(cardStack)
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
            if (!inReach(player, payload.terminalPos)) return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val entity = level.getBlockEntity(payload.terminalPos)
            when (entity) {
                is damien.nodeworks.block.entity.InventoryTerminalBlockEntity -> {
                    entity.layoutIndex = payload.layoutIndex
                    entity.setChanged()
                }
            }
        }
    }

    fun handleSetInstructionGrid(payload: SetInstructionGridPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val menu = player.containerMenu
            if (menu is InstructionSetScreenHandler && menu.containerId == payload.containerId) {
                menu.setRecipeFromStacks(payload.items)
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
            val runner = level.getBlockEntity(pos) as? TerminalBlockEntity ?: continue
            for (slot in runner.autoStartSlots()) {
                if (isSlotRunning(level, pos, slot)) continue
                if (startSlot(level, pos, slot)) logger.info("[Runner {}] Auto-run started slot {}", pos, slot + 1)
            }
        }
    }

    /** Ring buffer of total Lua wall-clock cost across all engines for the
     *  last 20 server ticks (one second at 20 TPS). Slot `tickCount % 20` is
     *  zeroed at the start of each [tickAll] before any engine writes to it,
     *  then accumulated as engines run. [totalLuaCostNsLastSecond] sums the
     *  ring for the `/nodeworks safety status` "% global tick budget" line. */
    private val totalLuaCostNsRing = LongArray(20)

    /** Sum of all-engine Lua wall-clock cost across the last second of ticks.
     *  Read by [damien.nodeworks.command.NodeworksCommand]. */
    fun totalLuaCostNsLastSecond(): Long {
        var sum = 0L
        for (v in totalLuaCostNsRing) sum += v
        return sum
    }

    /** Snapshot of the live engine table for the admin command suite. Returns a
     *  list copy so callers can iterate without ConcurrentModification when a
     *  reentrant tick edits [activeEngines]. */
    fun activeEnginesSnapshot(): List<Pair<EngineKey, ScriptEngine>> =
        activeEngines.entries.map { it.key to it.value }

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

        // Zero this tick's slot in the global + per-engine cost rings before
        // anyone writes to it. Engines deferred by global-budget exhaustion
        // contribute 0 for this tick (rather than re-counting the previous
        // tick's value), so % utilisation stays honest.
        val ringSlot = (tickCount % 20).toInt()
        totalLuaCostNsRing[ringSlot] = 0L
        for (e in activeEngines.values) e.resetTickCostSlot(tickCount)

        val toRemove = mutableListOf<EngineKey>()
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
            val cost = System.nanoTime() - before
            engine.vruntimeNs += cost
            engine.recordTickCost(tickCount, cost)
            totalLuaCostNsRing[ringSlot] += cost

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

    // --- Portable Scripting Terminal ---

    /** The portable menu behind [containerId] and the terminal stack it edits, or null. */
    private fun portableTerminal(
        player: net.minecraft.world.entity.player.Player,
        containerId: Int,
    ): Pair<damien.nodeworks.screen.TerminalScreenHandler, net.minecraft.world.item.ItemStack>? {
        val menu = player.containerMenu as? damien.nodeworks.screen.TerminalScreenHandler ?: return null
        if (menu.containerId != containerId || !menu.isPortable()) return null
        val stack = damien.nodeworks.item.PortableScriptingTerminalItem.stackIn(player, menu.getPortableSlot()) ?: return null
        return menu to stack
    }

    fun handlePortableDraftMeta(payload: PortableDraftMetaPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val (_, stack) = portableTerminal(player, payload.containerId) ?: return@enqueueWork
            val old = damien.nodeworks.item.PortableScriptingTerminalItem.draft(stack)
            val loadedName = if (payload.loadedSlot == old.loadedSlot) old.loadedName else {
                val slot = payload.loadedSlot
                val valid = slot in 0 until net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE ||
                    slot == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND
                if (valid) damien.nodeworks.item.ScriptItem.program(player.inventory.getItem(slot))?.name ?: "" else ""
            }
            val names = payload.tabNames
                .filter { damien.nodeworks.item.ScriptProgram.isValidTabName(it) }
                .distinct()
                .take(damien.nodeworks.item.ScriptProgram.MAX_TABS)
            val tabs = LinkedHashMap<String, String>()
            tabs[damien.nodeworks.item.ScriptProgram.MAIN_TAB] = old.tabs[damien.nodeworks.item.ScriptProgram.MAIN_TAB] ?: ""
            for (n in names) if (tabs.size < damien.nodeworks.item.ScriptProgram.MAX_TABS) tabs[n] = old.tabs[n] ?: ""
            stack.set(
                damien.nodeworks.registry.ModDataComponents.SCRIPT_DRAFT,
                damien.nodeworks.item.ScriptDraft(
                    payload.name.take(damien.nodeworks.item.ScriptProgram.MAX_NAME_LENGTH),
                    tabs,
                    payload.loadedSlot,
                    loadedName,
                    payload.layoutIndex.coerceIn(0, 3),
                ),
            )
        }
    }

    fun handlePortableDraftTab(payload: PortableDraftTabPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val (_, stack) = portableTerminal(player, payload.containerId) ?: return@enqueueWork
            if (!damien.nodeworks.item.ScriptProgram.isValidTabName(payload.tab)) return@enqueueWork
            val old = damien.nodeworks.item.PortableScriptingTerminalItem.draft(stack)
            if (payload.tab !in old.tabs && old.tabs.size >= damien.nodeworks.item.ScriptProgram.MAX_TABS) return@enqueueWork
            val others = old.tabs.entries.sumOf { if (it.key == payload.tab) 0 else it.value.length }
            // The editor caps typing at the program limit, so a larger payload is not a player action.
            if (others + payload.text.length > damien.nodeworks.item.ScriptProgram.MAX_TOTAL_CHARS) return@enqueueWork
            val tabs = LinkedHashMap(old.tabs)
            tabs[payload.tab] = payload.text
            stack.set(damien.nodeworks.registry.ModDataComponents.SCRIPT_DRAFT, old.copy(tabs = tabs))
        }
    }

    fun handlePortableScriptWrite(payload: PortableScriptWritePayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player()
            val (_, terminal) = portableTerminal(player, payload.containerId) ?: return@enqueueWork
            val slot = payload.targetSlot
            val inv = player.inventory
            if (slot !in 0 until net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE &&
                slot != net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND
            ) return@enqueueWork
            val target = inv.getItem(slot)
            if (target.item !is damien.nodeworks.item.ScriptItem) {
                player.sendOverlayMessage(Component.translatable("message.nodeworks.script_target_missing"))
                return@enqueueWork
            }
            val draft = damien.nodeworks.item.PortableScriptingTerminalItem.draft(terminal)
            val existing = damien.nodeworks.item.ScriptItem.program(target)
            if (existing != null && (slot != draft.loadedSlot || existing.name != draft.loadedName)) {
                player.sendOverlayMessage(Component.translatable("message.nodeworks.script_target_taken", existing.name))
                return@enqueueWork
            }
            val name = draft.name.trim()
            val problem = damien.nodeworks.item.ScriptProgram.problem(name, draft.tabs)
            if (problem != null) {
                player.sendOverlayMessage(Component.literal(problem))
                return@enqueueWork
            }
            if (target.count > 1) {
                // A stack of blanks: write one and leave the rest blank.
                val one = target.split(1)
                one.set(
                    damien.nodeworks.registry.ModDataComponents.SCRIPT_PROGRAM,
                    damien.nodeworks.item.ScriptProgram(name, LinkedHashMap(draft.tabs), player.uuid, player.name.string),
                )
                val written = one.copy()
                if (!inv.add(one)) player.drop(written, false)
                // Point the draft at wherever the written copy landed so Save updates it next time.
                val landed = ((0 until net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) + net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND)
                    .firstOrNull { net.minecraft.world.item.ItemStack.isSameItemSameComponents(inv.getItem(it), written) }
                if (landed != null) {
                    terminal.set(
                        damien.nodeworks.registry.ModDataComponents.SCRIPT_DRAFT,
                        draft.copy(loadedSlot = landed, loadedName = name),
                    )
                }
                player.sendOverlayMessage(Component.translatable("message.nodeworks.script_saved_split", name))
                return@enqueueWork
            }
            target.set(
                damien.nodeworks.registry.ModDataComponents.SCRIPT_PROGRAM,
                damien.nodeworks.item.ScriptProgram(name, LinkedHashMap(draft.tabs), player.uuid, player.name.string),
            )
            terminal.set(
                damien.nodeworks.registry.ModDataComponents.SCRIPT_DRAFT,
                draft.copy(loadedSlot = slot, loadedName = name),
            )
            player.sendOverlayMessage(Component.translatable("message.nodeworks.script_saved", name))
        }
    }
}
