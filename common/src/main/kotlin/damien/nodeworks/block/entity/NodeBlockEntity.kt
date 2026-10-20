package damien.nodeworks.block.entity

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.card.ObserverSideCapability
import damien.nodeworks.card.RedstoneSideCapability
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.card.NodeCard
import damien.nodeworks.card.SideCapability
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import org.slf4j.LoggerFactory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Block entity for the Node block. Stores a separate inventory for each of the 6 faces.
 *
 * Optimized for large-scale systems:
 * - No ticking (passive storage, zero per-tick cost)
 * - Flat inventory array with O(1) side-to-slot mapping
 * - Only marks dirty on actual changes
 */
open class NodeBlockEntity(
    type: net.minecraft.world.level.block.entity.BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(type, pos, state), WorldlyContainer, damien.nodeworks.network.Connectable {

    /** Convenience constructor used by the regular Node block entity registration.
     *  Subclasses (e.g. Advanced Node) call the [BlockEntityType]-taking primary
     *  constructor so they can pass their own type. */
    constructor(pos: BlockPos, state: BlockState) : this(ModBlockEntities.NODE, pos, state)

    companion object {
        /** How often a Transfer Card drops scan cursors for Storage Cards that are gone. */
        private const val CURSOR_PRUNE_TICKS = 1200L

        /** An idle Transfer Card waits up to four times its interval between runs. */
        private const val MAX_IDLE_BACKOFF_SHIFT = 2

        /** A Node whose network id is gone looks again after this long. */
        private const val NO_NETWORK_RECHECK_TICKS = 20

        private val logger = LoggerFactory.getLogger("nodeworks-node")
        const val SLOTS_PER_SIDE = 9
        const val TOTAL_SLOTS = SLOTS_PER_SIDE * 6 // 54 total

        /** Client-side callback for tracking node load/unload. Set by client init. */
        var nodeTracker: NodeTracker? = null

        /** Cached per-side slot index arrays to avoid allocation on every hopper interaction. */
        private val SLOTS_BY_FACE: Array<IntArray> = Array(6) { side ->
            val offset = side * SLOTS_PER_SIDE
            IntArray(SLOTS_PER_SIDE) { offset + it }
        }
    }

    /** Callback interface for client-side node position tracking. */
    fun interface NodeTracker {
        fun onNodeChanged(pos: BlockPos, loaded: Boolean)
    }

    private val items: NonNullList<ItemStack> = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY)

    override fun isFallbackAnchor(): Boolean = true

    /** The id this Node lends to a network with no controller, derived from where it
     *  stands so nothing is saved and a cloned Node cannot carry it along. */
    override val fallbackAnchorId: UUID?
        get() = level?.let { damien.nodeworks.network.NetworkIds.nodeAnchor(it, worldPosition) }

    /** Client only. The Node renderer keeps its extracted card beams here and rebuilds
     *  them only when [renderCacheVersion] moves, instead of rescanning a dozen block
     *  entities every frame. Bumped when the cards sync and when a neighbour changes. */
    @Transient var renderCache: Any? = null
    @Transient var renderCacheVersion: Int = 0

    fun invalidateRenderCache() {
        renderCacheVersion++
    }
    // Regular Nodes connect via face-adjacency only and never populate this
    // set, but the [Focus Node][damien.nodeworks.block.entity.FocusNodeBlockEntity]
    // subclass uses it for laser links. `protected` so the subclass can write
    // and persist it.
    protected val connections: LinkedHashSet<BlockPos> = linkedSetOf()

    /** Per-face wrench-block flags. Bit i = forced-blocked on
     *  Direction.entries[i]. Persisted as a single int in NBT. */
    private var forcedPipeBlockedMask: Int = 0

    /** Count of legacy per-face monitors read from an older save. The monitor-on-node
     *  system was removed in favour of the standalone [damien.nodeworks.block.MonitorBlock],
     *  [loadAdditional] can't act on legacy data directly (no level yet), so we stash
     *  the count and drop one Monitor item per entry from [setLevel] when the level
     *  is available. The drop loses the tracked-item setting, by design, since
     *  re-placing the Monitor is easy and we avoid trying to synthesize BlockItem NBT
     *  from a partial legacy record. */
    private var legacyMonitorDrops: Int = 0

    // --- Redstone output per side (0-15) ---
    private val redstoneOutputs = IntArray(6) // indexed by Direction.ordinal

    fun getRedstoneOutput(side: Direction): Int = redstoneOutputs[side.ordinal]

    fun setRedstoneOutput(side: Direction, strength: Int) {
        val clamped = strength.coerceIn(0, 15)
        if (redstoneOutputs[side.ordinal] != clamped) {
            redstoneOutputs[side.ordinal] = clamped
            markDirtyAndSync()
            level?.updateNeighborsAt(worldPosition, blockState.block)
        }
    }

    fun hasAnyRedstoneOutput(): Boolean = redstoneOutputs.any { it > 0 }

    // --- Network connections ---

    override fun getConnections(): Collection<BlockPos> = connections

    override fun hasConnection(pos: BlockPos): Boolean = pos in connections

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun forcedPipeBlocked(side: Direction): Boolean =
        (forcedPipeBlockedMask shr side.ordinal) and 1 != 0

    override fun toggleForcedPipeBlock(side: Direction) {
        forcedPipeBlockedMask = forcedPipeBlockedMask xor (1 shl side.ordinal)
        markDirtyAndSync()
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    // --- Card access ---

    /** Returns all cards found in this side's 9 slots, with their alias if named. */
    fun getCards(side: Direction): List<CardInfo> {
        val offset = sideOffset(side)
        val result = mutableListOf<CardInfo>()
        for (i in 0 until SLOTS_PER_SIDE) {
            val stack = items[offset + i]
            val card = stack.item as? NodeCard ?: continue
            val alias = if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME))
                stack.hoverName.string else null
            result.add(CardInfo(card, alias, i))
        }
        return result
    }

    /** Channel dye-colors for every card on this face, one per card present (no
     *  deduplication, callers that want the distinct set can call .toSet()).
     *  Used by [damien.nodeworks.render.NodeRenderer] to tint the per-face glow
     *  overlay: same-channel faces show that channel's hue, mixed-channel faces
     *  show the rainbow indicator. Returns empty when no cards are installed,
     *  which the renderer treats as "use the network color." */
    fun getFaceChannels(side: Direction): List<net.minecraft.world.item.DyeColor> {
        val offset = sideOffset(side)
        val out = mutableListOf<net.minecraft.world.item.DyeColor>()
        for (i in 0 until SLOTS_PER_SIDE) {
            val stack = items[offset + i]
            if (stack.item !is NodeCard) continue
            out.add(damien.nodeworks.card.CardChannel.get(stack))
        }
        return out
    }

    /** Role of one face of this Node, derived from what's adjacent.
     *
     *  - [PIPE]: another Connectable BE is adjacent. The face is consumed by
     *    the connection, no cards are valid here.
     *  - [DEVICE]: a non-Connectable block is adjacent (vanilla chest, furnace,
     *    stone, ...). Cards on this face manage that adjacent block, current
     *    behaviour.
     *  - [FREE]: air is adjacent. Cards are allowed but most have no effect
     *    without a block to target. Observer Cards into air are a legitimate
     *    use case so we don't gate placement here. */
    enum class FaceRole { PIPE, DEVICE, FREE }

    fun faceRole(side: Direction): FaceRole {
        val lvl = level ?: return FaceRole.FREE
        val neighborPos = worldPosition.relative(side)
        val neighborBe = lvl.getBlockEntity(neighborPos)
        val neighborConnectable = neighborBe as? damien.nodeworks.network.Connectable
        // Wrench force-block on either side demotes the face out of PIPE so
        // the player gets card slots back on a face they cut off. Mirrors the
        // gate in [NodeBlock.computePipeFlag] so the GUI state agrees with
        // what the renderer is showing. The [adjacencyFaceAllowed] check is
        // what keeps a User's / Processing Handler's inert sides from being
        // claimed as PIPE faces, so cards remain valid there and the
        // through-Node laser doesn't pierce a non-connecting neighbour.
        if (neighborConnectable != null
            && neighborConnectable.adjacencyFaceAllowed(side.opposite, null)
            && !forcedPipeBlocked(side)
            && !neighborConnectable.forcedPipeBlocked(side.opposite)
        ) return FaceRole.PIPE
        val neighborState = lvl.getBlockState(neighborPos)
        if (neighborState.isAir) return FaceRole.FREE
        return FaceRole.DEVICE
    }

    /** Resolves all capabilities for this side based on inserted cards.
     *  Returns [emptyList] for PIPE-roled faces, the face is consumed by the
     *  network connection and any cards stashed there don't expose a target. */
    fun getSideCapabilities(side: Direction): List<SideCapabilityInfo> {
        if (faceRole(side) == FaceRole.PIPE) return emptyList()
        val adjacentPos = worldPosition.relative(side)
        val accessFace = side.opposite // face of the target block that faces the node
        return getCards(side).map { info ->
            val stack = items[sideOffset(side) + info.slotIndex]
            val capability = when (info.card) {
                is damien.nodeworks.card.IOCard -> IOSideCapability(adjacentPos, accessFace)
                is damien.nodeworks.card.TransferCard -> {
                    val customSide = damien.nodeworks.card.TransferCard.getCustomSide(stack)
                    damien.nodeworks.card.TransferSideCapability(
                        adjacentPos, customSide?.resolve(side) ?: accessFace,
                        damien.nodeworks.card.TransferCard.getMode(stack),
                        damien.nodeworks.card.TransferCard.getFilterRules(stack).filter { it.isNotBlank() },
                        damien.nodeworks.card.TransferCard.getRoundRobin(stack),
                        damien.nodeworks.card.TransferCard.getTickInterval(stack),
                    )
                }
                is damien.nodeworks.card.StorageCard -> {
                    val priority = damien.nodeworks.card.StorageCard.getPriority(stack)
                    val filterMode = damien.nodeworks.card.StorageCard.getFilterMode(stack)
                    val filterRules = damien.nodeworks.card.StorageCard.getFilterRules(stack)
                    val stackability = damien.nodeworks.card.StorageCard.getStackabilityFilter(stack)
                    val nbtFilter = damien.nodeworks.card.StorageCard.getNbtFilter(stack)
                    // Player-chosen side override resolves against the card's
                    // mounted node face. Null = use the default touching-face.
                    val customSide = damien.nodeworks.card.StorageCard.getCustomSide(stack)
                    val resolvedFace = customSide?.resolve(side) ?: accessFace
                    StorageSideCapability(
                        adjacentPos, resolvedFace, priority,
                        filterMode, filterRules, stackability, nbtFilter,
                    )
                }
                is damien.nodeworks.card.RedstoneCard -> RedstoneSideCapability(adjacentPos, worldPosition, side, accessFace)
                is damien.nodeworks.card.ObserverCard -> ObserverSideCapability(adjacentPos, accessFace)
                else -> null
            }
            // Pull the channel here so [NetworkDiscovery.snapshotNode] doesn't need a
            // back-reference to the BlockEntity to read it. White is the default for
            // pre-channel cards and any card the user hasn't dyed yet, the channel
            // helper reads directly from CUSTOM_DATA so untouched stacks return WHITE.
            val channel = damien.nodeworks.card.CardChannel.get(stack)
            SideCapabilityInfo(capability ?: return@map null, info.alias, info.slotIndex, channel)
        }.filterNotNull()
    }

    data class CardInfo(val card: NodeCard, val alias: String?, val slotIndex: Int)
    data class SideCapabilityInfo(
        val capability: SideCapability,
        val alias: String?,
        val slotIndex: Int,
        val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
    )

    // --- Transfer Cards ---

    /** True while any slot holds a Transfer Card, so the ticker returns at once for the
     *  many Nodes that hold none. Recomputed on every slot change and on load. */
    var hasTransferCards: Boolean = false
        private set

    /** A Transfer Card's settings, read from its item data once when the card lands in
     *  the slot. A card cannot be edited while it sits in a Node, so reading it every
     *  tick would only copy the same tag thousands of times a second on a big server. */
    private class TransferConfig(
        val mode: damien.nodeworks.card.TransferCard.Companion.Mode,
        val rules: List<String>,
        val roundRobin: Boolean,
        val interval: Int,
        val channel: damien.nodeworks.network.ChannelFilter,
        val face: damien.nodeworks.screen.widget.RelDir?,
    )

    private val transferConfigs = arrayOfNulls<TransferConfig>(TOTAL_SLOTS)

    // Per-slot run state. Not persisted: after a reload a card's countdown starts over
    // and its round robin resumes at the first Storage Card, neither of which loses items.
    private val transferCountdown = IntArray(TOTAL_SLOTS)
    /** Runs in a row that moved nothing. Each one doubles the wait before the next, up
     *  to [MAX_IDLE_BACKOFF_SHIFT] doublings, so an idle card costs little and a card
     *  that finds work again is back at its interval at once. */
    private val transferMisses = IntArray(TOTAL_SLOTS)
    /** Game time of each slot's last run and whether it moved anything, for the
     *  Diagnostic Tool. */
    private val transferLastRun = LongArray(TOTAL_SLOTS)
    private val transferLastMoved = BooleanArray(TOTAL_SLOTS)
    private var transferLastTick = 0L

    /** One line per Transfer Card for the Diagnostic Tool: what it is, when it next
     *  runs, how far it has backed off, and what its last run did. */
    fun transferStatus(): List<String> {
        val out = ArrayList<String>()
        val now = level?.gameTime ?: 0L
        (level as? net.minecraft.server.level.ServerLevel)?.let { lvl ->
            out.add("Ticker: " + (if (TransferCardTicker.isRegistered(lvl, worldPosition)) "registered" else "not registered") +
                ", chunk " + (if (lvl.shouldTickBlocksAt(worldPosition)) "ticking" else "not ticking"))
            out.add(if (transferLastTick == 0L) "  never ticked" else "  ticked ${now - transferLastTick}t ago")
        }
        for (slot in 0 until TOTAL_SLOTS) {
            val config = transferConfigs[slot] ?: continue
            val side = sideForSlot(slot).name.lowercase()
            val mode = config.mode.name.lowercase()
            val wait = (config.interval shl minOf(transferMisses[slot], MAX_IDLE_BACKOFF_SHIFT))
                .coerceAtMost(damien.nodeworks.card.TransferCard.MAX_TICK_INTERVAL)
            val due = (wait - transferCountdown[slot]).coerceAtLeast(0)
            out.add("Transfer $mode, $side face")
            out.add(
                when {
                    networkId == null -> "  no network"
                    faceRole(sideForSlot(slot)) != FaceRole.DEVICE -> "  face is not a device face"
                    else -> "  due in ${due}t, wait x${1 shl minOf(transferMisses[slot], MAX_IDLE_BACKOFF_SHIFT)}"
                }
            )
            if (transferLastRun[slot] == 0L) {
                out.add("  never ran")
            } else {
                out.add("  last run ${now - transferLastRun[slot]}t ago")
                out.add(if (transferLastMoved[slot]) "  moved items" else "  moved nothing")
            }
        }
        return out
    }
    private val transferCursors =
        arrayOfNulls<damien.nodeworks.script.NetworkTransfer.RoundRobinCursor>(TOTAL_SLOTS)

    /** Where each (card slot, inventory) pair resumes its scan of a slot-based inventory,
     *  so a source too big for one run is covered over several runs instead of rescanning
     *  its first slots every time. For an ME source the cursor holds the item type the
     *  last run stopped on. Keyed by the Storage Card's position, or by this Node's own
     *  position for the round robin source scan. */
    private val transferScanCursors = HashMap<Pair<Int, BlockPos>, damien.nodeworks.platform.ScanCursor>()

    private fun scanCursor(slot: Int, pos: BlockPos): damien.nodeworks.platform.ScanCursor =
        transferScanCursors.getOrPut(slot to pos) { damien.nodeworks.platform.ScanCursor() }

    private fun refreshTransferFlag() {
        var any = false
        for (slot in 0 until TOTAL_SLOTS) {
            val stack = items[slot]
            if (stack.item is damien.nodeworks.card.TransferCard) {
                any = true
                transferConfigs[slot] = TransferConfig(
                    damien.nodeworks.card.TransferCard.getMode(stack),
                    damien.nodeworks.card.TransferCard.getFilterRules(stack).filter { it.isNotBlank() },
                    damien.nodeworks.card.TransferCard.getRoundRobin(stack),
                    damien.nodeworks.card.TransferCard.getTickInterval(stack),
                    damien.nodeworks.card.TransferCard.getChannelFilter(stack),
                    damien.nodeworks.card.TransferCard.getCustomSide(stack),
                )
            } else {
                // A slot that lost its Transfer Card starts fresh if one is put back.
                transferConfigs[slot] = null
                transferCursors[slot] = null
                transferCountdown[slot] = 0
                transferMisses[slot] = 0
            }
        }
        hasTransferCards = any
        transferScanCursors.keys.removeIf { (slot, _) -> transferConfigs[slot] == null }
        java.util.Arrays.fill(transferViews, null)
        val serverLevel = level as? net.minecraft.server.level.ServerLevel ?: return
        // The cards are part of what a network walk reports, so a walk kept for card runs
        // is stale from here on.
        networkId?.let { damien.nodeworks.network.NetworkDiscovery.invalidate(it) }
        if (any) TransferCardTicker.register(serverLevel, worldPosition)
        else TransferCardTicker.unregister(serverLevel, worldPosition)
    }

    /** Runs every Transfer Card whose interval has elapsed. Called from
     *  [TransferCardTicker] for Nodes that hold a card, no other Node is ticked. */
    /** Advances every card by [elapsed] ticks and runs the ones that are due. Returns how
     *  many ticks until the next card is due, so the ticker can leave this Node alone
     *  until then instead of looking it up every tick. */
    fun serverTick(level: net.minecraft.server.level.ServerLevel, elapsed: Int): Int {
        transferLastTick = level.gameTime
        if (!hasTransferCards) return Int.MAX_VALUE
        if (networkId == null) return NO_NETWORK_RECHECK_TICKS
        var nextDue = Int.MAX_VALUE
        for (slot in 0 until TOTAL_SLOTS) {
            val config = transferConfigs[slot] ?: continue
            val wait = (config.interval shl minOf(transferMisses[slot], MAX_IDLE_BACKOFF_SHIFT))
                .coerceAtMost(damien.nodeworks.card.TransferCard.MAX_TICK_INTERVAL)
            transferCountdown[slot] += elapsed
            if (transferCountdown[slot] >= wait) {
                transferCountdown[slot] = 0
                val side = sideForSlot(slot)
                if (faceRole(side) == FaceRole.DEVICE) {
                    val moved = runTransferCard(level, slot, side, config)
                    transferLastRun[slot] = level.gameTime
                    transferLastMoved[slot] = moved
                    if (moved) transferMisses[slot] = 0 else transferMisses[slot]++
                }
            }
            val again = (config.interval shl minOf(transferMisses[slot], MAX_IDLE_BACKOFF_SHIFT))
                .coerceAtMost(damien.nodeworks.card.TransferCard.MAX_TICK_INTERVAL)
            nextDue = minOf(nextDue, (again - transferCountdown[slot]).coerceAtLeast(1))
        }
        return nextDue
    }

    /** The Storage Cards the run in progress may use, with the channel of the one
     *  facing the card's own inventory when there is one. Built once per network walk
     *  and exclusion set rather than once per run, since a walk lives up to a second. */
    private class TransferView(
        val snapshot: damien.nodeworks.network.NetworkSnapshot,
        val excluded: Set<BlockPos>,
        val channel: damien.nodeworks.network.ChannelFilter,
        val cards: List<damien.nodeworks.network.CardSnapshot>,
        val counterpart: net.minecraft.world.item.DyeColor?,
    )

    private val transferViews = arrayOfNulls<TransferView>(TOTAL_SLOTS)

    /** The cards of the run in progress, set by [runTransferCard] for its passes. */
    private var runCards: List<damien.nodeworks.network.CardSnapshot> = emptyList()

    /** True when the run moved anything. */
    private fun runTransferCard(level: net.minecraft.server.level.ServerLevel, slot: Int, side: Direction, config: TransferConfig): Boolean {
        val adjacentPos = worldPosition.relative(side)
        val face = config.face?.resolve(side) ?: side.opposite
        val itemTarget = damien.nodeworks.platform.PlatformServices.storage.getItemStorage(level, adjacentPos, face)
        val fluidTarget = damien.nodeworks.platform.PlatformServices.storage.getFluidStorage(level, adjacentPos, face)
        if (itemTarget == null && fluidTarget == null) return false
        var movedAny = false
        // A walk up to a second old is good enough to move items by, and a card at the
        // shortest interval then costs one walk a second rather than one a tick.
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(
            level, worldPosition, damien.nodeworks.card.TransferCard.SNAPSHOT_MAX_AGE_TICKS,
        )
        // Time comes from the Transfer Cards' own budget, items from the network's shared cap.
        val budget = damien.nodeworks.script.NetworkRateLimits.forTransferCards(networkId)
        val itemCap = damien.nodeworks.script.NetworkRateLimits.forNetwork(networkId)
        // Budgets count by the server tick, as scripts do. Mixing in the level's game time
        // would reset the shared item cap every time a card and a script took turns.
        val tick = damien.nodeworks.platform.PlatformServices.modState.tickCount
        // Only an existing cache is kept current, a terminal that is open somewhere.
        // Never create one, a poller on a network nobody looks at is pure cost.
        val cache = damien.nodeworks.script.NetworkInventoryCache.existing(snapshot.networkId)

        // Any Storage Card facing the same inventory as this card, on this Node's face or
        // another Node's, is neither source nor sink. A chest can then carry a Storage
        // Card and an Import card on the same face and sort its own contents out to the
        // rest of the network without churning items back into itself. Storage Cards on
        // the Node's other faces are ordinary destinations.
        val excluded = HashSet<BlockPos>()
        excluded.add(adjacentPos)
        // The other half of a double chest is the same inventory under another position.
        val targetState = level.getBlockState(adjacentPos)
        if (targetState.block is net.minecraft.world.level.block.ChestBlock &&
            targetState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE) &&
            targetState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
        ) {
            excluded.add(adjacentPos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(targetState)))
        }
        val view = transferViews[slot]?.takeIf {
            it.snapshot === snapshot && it.channel == config.channel && it.excluded == excluded
        } ?: TransferView(
            snapshot, excluded, config.channel,
            damien.nodeworks.script.NetworkTransfer.destinationCards(snapshot, config.channel, excluded),
            // When a Storage Card faces this inventory too, the terminal cache already counts
            // what is in it. A move then only shifts items between channels, see [noteMoved].
            snapshot.storageCards
                .firstOrNull { (it.capability as? StorageSideCapability)?.adjacentPos in excluded }
                ?.channel,
        ).also { transferViews[slot] = it }
        runCards = view.cards
        val counterpart = view.counterpart
        if (level.gameTime % CURSOR_PRUNE_TICKS == 0L) pruneScanCursors(slot, snapshot)

        if (itemTarget != null) {
            // Never moves more than nine stacks' worth in one run however much cap is left.
            val maxItems = minOf(itemCap.availableItems(tick), damien.nodeworks.card.TransferCard.MAX_ITEMS_PER_RUN)
            if (maxItems > 0L) {
                val moved = when (config.mode) {
                    damien.nodeworks.card.TransferCard.Companion.Mode.IMPORT ->
                        if (config.roundRobin) importRoundRobin(level, snapshot, cache, counterpart, itemTarget, excluded, config, slot, maxItems, budget, tick)
                        else importFromTarget(level, snapshot, cache, counterpart, itemTarget, excluded, config, slot, maxItems, budget, tick)
                    damien.nodeworks.card.TransferCard.Companion.Mode.EXPORT ->
                        exportToTarget(level, snapshot, cache, counterpart, itemTarget, excluded, config, slot, maxItems, budget, tick)
                }
                if (moved > 0L) {
                    itemCap.noteItemsMoved(tick, moved)
                    movedAny = true
                }
            }
        }
        if (fluidTarget != null) {
            val movedFluid = when (config.mode) {
                damien.nodeworks.card.TransferCard.Companion.Mode.IMPORT ->
                    importFluidFromTarget(level, snapshot, fluidTarget, excluded, config, slot, budget, tick)
                damien.nodeworks.card.TransferCard.Companion.Mode.EXPORT ->
                    exportFluidToTarget(level, snapshot, fluidTarget, excluded, config, budget, tick)
            }
            if (movedFluid > 0L) movedAny = true
        }
        return movedAny
    }

    /** Drops this slot's scan cursors for Storage Cards that are no longer on the
     *  network, so a card left in place for months does not keep one per retired chest. */
    private fun pruneScanCursors(slot: Int, snapshot: damien.nodeworks.network.NetworkSnapshot) {
        val live = HashSet<BlockPos>()
        live.add(worldPosition)
        for (card in snapshot.storageCards) (card.capability as? StorageSideCapability)?.let { live.add(it.adjacentPos) }
        transferScanCursors.keys.removeIf { (s, pos) -> s == slot && pos !in live }
    }

    /** Storage Cards a move may use, each with its storage, resolved one at a time as
     *  the caller walks them so a move that stops early never looks up the rest. The
     *  far half of a double chest is excluded by position before this. */
    private fun transferDestinations(
        level: net.minecraft.server.level.ServerLevel,
    ): Sequence<Pair<damien.nodeworks.network.CardSnapshot, damien.nodeworks.platform.ItemStorageHandle>> =
        runCards.asSequence().mapNotNull { card ->
            damien.nodeworks.script.NetworkStorageHelper.getStorage(level, card)?.let { card to it }
        }

    /** Keeps an open terminal's cache in step with one stack a card just moved. One
     *  map update per stack, nothing scanned. With a [counterpart], a Storage Card that
     *  faces the card's own inventory, both ends are already counted, so the totals
     *  stand and only the channel counts shift. Touching the totals there would mark the
     *  item dirty every run and keep the poll from ever correcting it. */
    private fun noteMoved(
        cache: damien.nodeworks.script.NetworkInventoryCache?,
        stack: ItemStack,
        count: Long,
        channel: net.minecraft.world.item.DyeColor,
        intoNetwork: Boolean,
        counterpart: net.minecraft.world.item.DyeColor?,
    ) {
        if (cache == null || count <= 0L) return
        val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return
        val patch = stack.componentsPatch
        if (counterpart != null) {
            val from = if (intoNetwork) counterpart else channel
            val to = if (intoNetwork) channel else counterpart
            if (from != to) {
                cache.onChannelDelta(from, id, patch, -count)
                cache.onChannelDelta(to, id, patch, count)
            }
            return
        }
        if (intoNetwork) {
            cache.onInserted(id, !patch.isEmpty, count, patch)
            cache.onChannelDelta(channel, id, patch, count)
        } else {
            cache.onExtracted(id, !patch.isEmpty, count, patch)
            cache.onChannelDelta(channel, id, patch, -count)
        }
    }

    private fun ruleMatcher(config: TransferConfig, registries: net.minecraft.core.HolderLookup.Provider): (ItemStack) -> Boolean =
        { stack -> config.rules.isEmpty() || config.rules.any { damien.nodeworks.script.CardHandle.matchesFilter(stack, it, registries) } }

    /** Priority fill: each Storage Card in order takes what it can. The move asks the
     *  destination before taking anything, so a source whose items nothing wants is never
     *  touched, and the scan resumes from the cursor next run when the item cap or the
     *  move-time budget stops it early. No rules means everything. */
    private fun importFromTarget(
        level: net.minecraft.server.level.ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        cache: damien.nodeworks.script.NetworkInventoryCache?,
        counterpart: net.minecraft.world.item.DyeColor?,
        source: damien.nodeworks.platform.ItemStorageHandle,
        exclude: Set<BlockPos>,
        config: TransferConfig,
        slot: Int,
        maxItems: Long,
        budget: damien.nodeworks.script.NetworkBudget,
        tick: Long,
    ): Long {
        val registries = level.registryAccess()
        val ruleMatch = ruleMatcher(config, registries)
        var remaining = maxItems
        var total = 0L
        budget.timedMove(tick, atomic = false) {
            for ((dest, storage) in transferDestinations(level)) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                val cap = dest.capability as? StorageSideCapability ?: continue
                val accept: (ItemStack) -> Boolean = { stack -> ruleMatch(stack) && cap.acceptsItem(stack, registries) }
                val moved = damien.nodeworks.platform.PlatformServices.storage.moveItems(
                    source, storage, { true }, remaining, scanCursor(slot, cap.adjacentPos), accept,
                ) { stack, n -> noteMoved(cache, stack, n, dest.channel, intoNetwork = true, counterpart) }
                remaining -= moved
                total += moved
            }
        }
        return total
    }

    /** Round robin: each stack is split evenly over a window of Storage Cards, so 32 logs
     *  over four furnaces land 8, 8, 8, 8, and the window moves on from run to run so
     *  a large network is covered a few cards at a time. Every share is placed before
     *  anything leaves the source, so nothing is lifted without a home. Resumes from the
     *  card's scan cursor and rotation index and stops at the item cap or the move-time
     *  budget. See [damien.nodeworks.platform.StorageService.moveItemsRoundRobin]. */
    private fun importRoundRobin(
        level: net.minecraft.server.level.ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        cache: damien.nodeworks.script.NetworkInventoryCache?,
        counterpart: net.minecraft.world.item.DyeColor?,
        source: damien.nodeworks.platform.ItemStorageHandle,
        exclude: Set<BlockPos>,
        config: TransferConfig,
        slot: Int,
        maxItems: Long,
        budget: damien.nodeworks.script.NetworkBudget,
        tick: Long,
    ): Long {
        val registries = level.registryAccess()
        val ruleMatch = ruleMatcher(config, registries)
        val cards = runCards
        if (cards.isEmpty()) return 0L
        // Built on demand, so a network of thousands of cards costs a run only the
        // window it offers items to.
        val targetAt: (Int) -> damien.nodeworks.platform.RoundRobinTarget = { idx ->
            val card = cards[idx]
            val cap = card.capability as? StorageSideCapability
            damien.nodeworks.platform.RoundRobinTarget(
                { damien.nodeworks.script.NetworkStorageHelper.getStorage(level, card) },
                cap?.let { c -> { stack: ItemStack -> c.acceptsItem(stack, registries) } },
                card,
            )
        }
        val rr = transferCursors[slot] ?: damien.nodeworks.script.NetworkTransfer.RoundRobinCursor().also { transferCursors[slot] = it }
        var moved = 0L
        budget.timedMove(tick, atomic = false) {
            val result = damien.nodeworks.platform.PlatformServices.storage.moveItemsRoundRobin(
                source, cards.size, targetAt, ruleMatch, maxItems, scanCursor(slot, worldPosition), rr.index,
                damien.nodeworks.card.TransferCard.ROUND_ROBIN_WINDOW,
            ) { target, stack, n ->
                noteMoved(cache, stack, n, (target.tag as damien.nodeworks.network.CardSnapshot).channel, intoNetwork = true, counterpart)
            }
            moved = result.moved
            rr.index = result.nextIndex
        }
        return moved
    }

    /** Each Storage Card's inventory is scanned from its own cursor, so a huge store
     *  holding the wanted items deep inside is reached over several runs. No rules pulls
     *  nothing, so a fresh card never drains the network. */
    private fun exportToTarget(
        level: net.minecraft.server.level.ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        cache: damien.nodeworks.script.NetworkInventoryCache?,
        counterpart: net.minecraft.world.item.DyeColor?,
        dest: damien.nodeworks.platform.ItemStorageHandle,
        exclude: Set<BlockPos>,
        config: TransferConfig,
        slot: Int,
        maxItems: Long,
        budget: damien.nodeworks.script.NetworkBudget,
        tick: Long,
    ): Long {
        if (config.rules.isEmpty()) return 0L
        val registries = level.registryAccess()
        val ruleMatch = ruleMatcher(config, registries)
        var remaining = maxItems
        var total = 0L
        budget.timedMove(tick, atomic = false) {
            for ((card, source) in transferDestinations(level)) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                val cap = card.capability as? StorageSideCapability ?: continue
                val moved = damien.nodeworks.platform.PlatformServices.storage.moveItems(
                    source, dest, { true }, remaining, scanCursor(slot, cap.adjacentPos), ruleMatch,
                ) { stack, n -> noteMoved(cache, stack, n, card.channel, intoNetwork = false, counterpart) }
                remaining -= moved
                total += moved
            }
        }
        return total
    }

    /** The order Storage Cards are offered fluid this run. Round robin starts at the
     *  card the item pass's cursor points at, so the two passes of one run agree. */
    private fun transferOrder(
        destinations: List<damien.nodeworks.network.CardSnapshot>,
        slot: Int,
        roundRobin: Boolean,
    ): List<damien.nodeworks.network.CardSnapshot> {
        if (!roundRobin || destinations.size <= 1) return destinations
        val start = (transferCursors[slot]?.index ?: 0).mod(destinations.size)
        return destinations.drop(start) + destinations.take(start)
    }

    private fun importFluidFromTarget(
        level: net.minecraft.server.level.ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        source: damien.nodeworks.platform.FluidStorageHandle,
        exclude: Set<BlockPos>,
        config: TransferConfig,
        slot: Int,
        budget: damien.nodeworks.script.NetworkBudget,
        tick: Long,
    ): Long {
        // No rules means every fluid, as for items.
        val matches: (String) -> Boolean = { fluidId ->
            config.rules.isEmpty() || config.rules.any {
                damien.nodeworks.script.CardHandle.matchesFilter(fluidId, damien.nodeworks.platform.ResourceKind.FLUID, it)
            }
        }
        val destinations = runCards
        if (destinations.isEmpty()) return 0L
        var remaining = damien.nodeworks.card.TransferCard.MAX_FLUID_PER_RUN
        // The move fills the destination in simulation before draining anything, so a
        // tank nothing wants is never touched.
        budget.timedMove(tick, atomic = false) {
            for (dest in transferOrder(destinations, slot, config.roundRobin)) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                val storage = damien.nodeworks.script.NetworkStorageHelper.getFluidStorage(level, dest) ?: continue
                val allowance = if (config.roundRobin) minOf(remaining, damien.nodeworks.card.TransferCard.BUCKET_MB) else remaining
                remaining -= damien.nodeworks.platform.PlatformServices.storage.moveFluid(source, storage, matches, allowance)
            }
        }
        return damien.nodeworks.card.TransferCard.MAX_FLUID_PER_RUN - remaining
    }

    private fun exportFluidToTarget(
        level: net.minecraft.server.level.ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        dest: damien.nodeworks.platform.FluidStorageHandle,
        exclude: Set<BlockPos>,
        config: TransferConfig,
        budget: damien.nodeworks.script.NetworkBudget,
        tick: Long,
    ): Long {
        // No rules pulls nothing, as for items.
        if (config.rules.isEmpty()) return 0L
        val matches: (String) -> Boolean = { fluidId ->
            config.rules.any { damien.nodeworks.script.CardHandle.matchesFilter(fluidId, damien.nodeworks.platform.ResourceKind.FLUID, it) }
        }
        var remaining = damien.nodeworks.card.TransferCard.MAX_FLUID_PER_RUN
        budget.timedMove(tick, atomic = false) {
            for (card in runCards) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                val source = damien.nodeworks.script.NetworkStorageHelper.getFluidStorage(level, card) ?: continue
                remaining -= damien.nodeworks.platform.PlatformServices.storage.moveFluid(source, dest, matches, remaining)
            }
        }
        return damien.nodeworks.card.TransferCard.MAX_FLUID_PER_RUN - remaining
    }

    // --- Side-aware access ---

    private fun sideOffset(side: Direction): Int = side.ordinal * SLOTS_PER_SIDE

    fun getStack(side: Direction, slot: Int): ItemStack {
        require(slot in 0 until SLOTS_PER_SIDE) { "Slot $slot out of range for side" }
        return items[sideOffset(side) + slot]
    }

    fun setStack(side: Direction, slot: Int, stack: ItemStack) {
        require(slot in 0 until SLOTS_PER_SIDE) { "Slot $slot out of range for side" }
        items[sideOffset(side) + slot] = stack
        clearRedstoneIfNoCard(side)
        refreshTransferFlag()
        markDirtyAndSync()
    }

    // --- Container implementation ---

    override fun getContainerSize(): Int = TOTAL_SLOTS

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) {
            clearRedstoneIfNoCard(sideForSlot(slot))
            refreshTransferFlag()
            markDirtyAndSync()
        }
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val taken = ContainerHelper.takeItem(items, slot)
        if (!taken.isEmpty) {
            clearRedstoneIfNoCard(sideForSlot(slot))
            refreshTransferFlag()
        }
        return taken
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        clearRedstoneIfNoCard(sideForSlot(slot))
        refreshTransferFlag()
        markDirtyAndSync()
    }

    override fun stillValid(player: Player): Boolean {
        return Container.stillValidBlockEntity(this, player)
    }

    override fun clearContent() {
        items.clear()
        for (dir in Direction.entries) clearRedstoneIfNoCard(dir)
        refreshTransferFlag()
        markDirtyAndSync()
    }

    private fun sideForSlot(slot: Int): Direction = Direction.entries[slot / SLOTS_PER_SIDE]

    /** Drop the side's emitted redstone strength when no Redstone Card remains
     *  in any of its slots, so pulling the card kills the signal it was driving. */
    private fun clearRedstoneIfNoCard(side: Direction) {
        if (redstoneOutputs[side.ordinal] == 0) return
        val offset = sideOffset(side)
        for (i in 0 until SLOTS_PER_SIDE) {
            val stack = items[offset + i]
            if (stack.item is damien.nodeworks.card.RedstoneCard) return
        }
        setRedstoneOutput(side, 0)
    }

    // --- WorldlyContainer: controls which slots are accessible from each direction ---

    override fun getSlotsForFace(side: Direction): IntArray {
        return SLOTS_BY_FACE[side.ordinal]
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean {
        return stack.item is NodeCard
    }

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, side: Direction?): Boolean {
        if (side == null) return false
        if (stack.item !is NodeCard) return false
        if (faceRole(side) == FaceRole.PIPE) return false
        val offset = sideOffset(side)
        return slot in offset until (offset + SLOTS_PER_SIDE)
    }

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, side: Direction): Boolean {
        val offset = sideOffset(side)
        return slot in offset until (offset + SLOTS_PER_SIDE)
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        // Pipe refactor: Node-side laser links are gone. We don't write the
        // legacy "connections" key any more, networks form via face-adjacency.
        if (hasAnyRedstoneOutput()) {
            output.putIntArray("redstoneOutputs", redstoneOutputs.copyOf())
        }
        networkId?.let { output.putString("networkId", it.toString()) }
        if (forcedPipeBlockedMask != 0) output.putInt("forcedPipeBlocked", forcedPipeBlockedMask)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        refreshTransferFlag()
        // Pipe refactor migration: discard any persisted laser links, the
        // network is rebuilt from adjacency on the next propagate. Old saves
        // with non-adjacent wrenched-link networks will silently lose those
        // links, players reconfigure with pipes.
        connections.clear()
        redstoneOutputs.fill(0)
        input.getIntArray("redstoneOutputs").ifPresent { saved ->
            for (i in 0 until minOf(saved.size, 6)) {
                redstoneOutputs[i] = saved[i].coerceIn(0, 15)
            }
        }
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        forcedPipeBlockedMask = input.getIntOr("forcedPipeBlocked", 0) and 0x3F
        if (level != null) damien.nodeworks.network.NetworkSettingsRegistry.notifyBlockSynced(worldPosition)
        invalidateRenderCache()
        nodeTracker?.onNodeChanged(worldPosition, true)

        // Legacy migration, prior builds stored monitors attached to node faces under
        // a "monitors" ListTag. That system is gone, read the count here so setLevel
        // can drop standalone Monitor items as a replacement. childrenListOrEmpty is
        // safe when the tag is absent, so new saves cost nothing.
        val legacy = input.childrenListOrEmpty("monitors")
        legacyMonitorDrops = legacy.count()
    }

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is net.minecraft.server.level.ServerLevel) {
            // Load ran before the level was attached, so the ticker learns of the cards here.
            if (hasTransferCards) TransferCardTicker.register(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
            // Legacy migration: drop one Monitor item per legacy per-face monitor
            // recorded on this node. The drop happens once (`legacyMonitorDrops` is
            // zeroed afterward) and isn't re-written on save since saveAdditional
            // no longer emits the "monitors" key. Scheduled via enqueueTickTask so
            // Containers.dropItemStack runs after the chunk finishes loading,
            // spawning entities mid-load can upset the chunk tracker.
            if (legacyMonitorDrops > 0) {
                val count = legacyMonitorDrops
                legacyMonitorDrops = 0
                newLevel.server.execute {
                    if (!newLevel.isLoaded(worldPosition)) return@execute
                    val stack = ItemStack(damien.nodeworks.registry.ModBlocks.MONITOR, count)
                    net.minecraft.world.Containers.dropItemStack(
                        newLevel,
                        worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5,
                        stack
                    )
                    logger.info(
                        "Migrated $count legacy monitor(s) on node at {} to standalone Monitor item drops.",
                        worldPosition
                    )
                }
            }
            // Pipe-refactor migration: drop any cards that sit on a face which
            // is now PIPE-roled (touching another Connectable). Same execute()
            // delay as the monitor migration since dropItemStack mid-load is
            // unsafe. Faces that gain PIPE role mid-game (because a Connectable
            // gets placed adjacent) keep their cards inert until the player
            // breaks one of the blocks, the migration only fires on load.
            newLevel.server.execute {
                if (!newLevel.isLoaded(worldPosition)) return@execute
                var dropped = 0
                for (dir in Direction.entries) {
                    if (faceRole(dir) != FaceRole.PIPE) continue
                    val offset = sideOffset(dir)
                    for (slotIdx in 0 until SLOTS_PER_SIDE) {
                        val stack = items[offset + slotIdx]
                        if (stack.isEmpty) continue
                        net.minecraft.world.Containers.dropItemStack(
                            newLevel,
                            worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5,
                            stack.copy(),
                        )
                        items[offset + slotIdx] = ItemStack.EMPTY
                        dropped++
                    }
                }
                if (dropped > 0) {
                    setChanged()
                    logger.info(
                        "Dropped $dropped card(s) from now-pipe faces on node at {} (pipe-refactor migration).",
                        worldPosition,
                    )
                }
            }
        }
    }

    /** Set to true by NodeBlock when the block is actually being destroyed. */
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    override fun setRemoved() {
        nodeTracker?.onNodeChanged(worldPosition, false)
        val currentLevel = level
        if (currentLevel is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.removeAllConnections(currentLevel, this)
            // The ticker entry stays. Looking up the world here would ask for a chunk that
            // may be mid-unload and stall the save, and an instance swapped out for a new
            // one at this position must not take the new one's entry with it. A broken or
            // unloaded Node is dropped or skipped by the ticker on its own walk.
            // Only populated while this Node anchored a controller-less network, and only
            // dropped when the Node is really gone. A chunk unload must leave the cache an
            // open terminal elsewhere on the network is reading.
            if (blockDestroyed) networkId?.takeIf { it == fallbackAnchorId }
                ?.let { damien.nodeworks.script.NetworkInventoryCache.removeByUUID(it) }
        }
        super.setRemoved()
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
