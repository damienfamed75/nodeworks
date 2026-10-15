package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.EntrySnapshot
import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.world.item.ItemStack
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached inventory index for a node network, updated by periodic polling with adaptive tick rate.
 * Uses double-buffer diff detection (same approach as AE2's CompositeStorage).
 *
 * Shared between: script engine, monitors, inventory terminal.
 * One cache per network entry node. Stored in the global registry.
 */
class NetworkInventoryCache(
    private val level: ServerLevel,
    private var networkEntryNode: BlockPos
) {
    /** Reseat the BFS entry point and re-poll synchronously. Used when a UI consumer
     *  opens against a cache built earlier by a now-destroyed consumer, the stale entry
     *  point resolves to a missing BE so the natural cycle would empty [entries].
     *  Mirroring [init]'s scan here populates the cache before the caller returns. */
    fun rebindEntryPoint(pos: BlockPos) {
        if (networkEntryNode == pos) return
        networkEntryNode = pos
        fullScan()
    }

    /** Synchronous scan of every card, then restart the poll cycle. A cycle left
     *  running would keep adding its remaining units into the front buffer this
     *  just filled and diffed, double counting them at its end. Ranges are
     *  planned from last measured sizes, unmeasured cards are read whole. */
    private fun fullScan() {
        val queue = ArrayDeque(snapshotCardsForCycle())
        var work = 0L
        var nanos = 0L
        // Continuations go back on the local queue, so a storage of unknown size
        // is still read to its end before this returns.
        while (queue.isNotEmpty()) {
            val start = System.nanoTime()
            val done = pollUnit(queue.removeFirst(), queue::addLast)
            if (done >= 0) {
                work += done
                nanos += System.nanoTime() - start
            }
        }
        // Timed so the first planned cycle is sized from this network's real
        // per-slot cost. Whole-only reads are left out, as in [PollCycle].
        pollCycle.seedCost(nanos, work)
        finalizeAndDiff(complete = true)
        pollCycle.restart()
    }

    data class SerialEntry(
        val serial: Long,
        val info: ItemInfo
    )

    data class FluidSerialEntry(
        val serial: Long,
        val info: FluidInfo
    )

    // Authoritative view of the latest poll. Filled across the cycle's slices,
    // diffed against [entries] at cycle end. Anything in [entries] but not in
    // [frontBuffer] (and not protected by the dirty mark) is treated as gone.
    //
    // Keys are [BufferKey.Key] (itemId + componentsHash) so component-bearing
    // variants of one item (five potions, three dyed armors) live in separate
    // buckets instead of collapsing under a single boolean `hasData`. Fluids
    // don't have components, so the fluid map stays String-keyed.
    private val frontBuffer = LinkedHashMap<BufferKey.Key, ItemInfo>()
    private val fluidFrontBuffer = LinkedHashMap<String, FluidInfo>()

    // Serial-tracked entries for delta sync to clients
    private val entries = LinkedHashMap<BufferKey.Key, SerialEntry>()
    private val fluidEntries = LinkedHashMap<String, FluidSerialEntry>()
    private var nextSerial = 1L

    /** Secondary index from itemId to the set of component-variant keys
     *  registered under it. Keeps the exact-id [count] / [find] fast paths
     *  O(variants-per-item) instead of O(total-entries), so a high-traffic
     *  Monitor polling for a plain itemId every tick doesn't scan every
     *  variant in storage. Mutated in lockstep with [entries] via the
     *  [putEntry] / [removeEntry] helpers. */
    private val keysByItemId = HashMap<String, MutableSet<BufferKey.Key>>()

    private fun putEntry(key: BufferKey.Key, entry: SerialEntry) {
        entries[key] = entry
        keysByItemId.getOrPut(key.itemId) { HashSet() }.add(key)
    }

    private fun removeEntry(key: BufferKey.Key) {
        if (entries.remove(key) != null) {
            keysByItemId[key.itemId]?.let { set ->
                set.remove(key)
                if (set.isEmpty()) keysByItemId.remove(key.itemId)
            }
        }
    }

    // Per-channel counts so the Inventory Terminal can show one channel. Filled by the
    // same poll as [frontBuffer], from the channel of each card read, and swapped in at
    // cycle end like [entries]. Kept current between polls by the channel deltas.
    private var channelFront = HashMap<net.minecraft.world.item.DyeColor, HashMap<BufferKey.Key, Long>>()
    private var channelFluidFront = HashMap<net.minecraft.world.item.DyeColor, HashMap<String, Long>>()
    private var channelItems = HashMap<net.minecraft.world.item.DyeColor, HashMap<BufferKey.Key, Long>>()
    private var channelFluids = HashMap<net.minecraft.world.item.DyeColor, HashMap<String, Long>>()

    /** Channel of the card being polled, read by [addToFrontBuffer]. */
    private var pollChannel: net.minecraft.world.item.DyeColor? = null

    /** Bumped on every change to any count, so a filtered view can skip quiet ticks. */
    var version: Long = 0L
        private set

    // Change tracking for delta sync (shared serial space, items + fluids)
    private val changedSerials = mutableSetOf<Long>()
    private val removedSerials = mutableSetOf<Long>()
    private val changedFluidSerials = mutableSetOf<Long>()

    // Round-robin polling state. One full poll is spread across a cycle of ticks,
    // balanced by slots read rather than card count, so one card on a giant
    // storage controller can't land its whole scan on a single tick. The cycle
    // state machine lives in [PollCycle], this class wires its callbacks to the
    // cache-specific buffer-swap, per-unit poll, and apply-diff steps.
    private val pollCycle = PollCycle<PollUnit>(MIN_SLICES, MAX_SLICES)

    /** One unit of poll work: a card's whole inventory, or one slot range of a
     *  large one. An [until] of Int.MAX_VALUE is open-ended: it reads one range
     *  from [from] and defers the rest, so a handler of unknown or grown size is
     *  read in full without any single poll exceeding [SLOTS_PER_UNIT] slots.
     *  [nodePos] and [side] locate the card, for the still-attached check on
     *  later ranges. Null only if the card wasn't found on a node. */
    private class PollUnit(
        val card: CardSnapshot,
        val pos: BlockPos,
        val nodePos: BlockPos?,
        val side: net.minecraft.core.Direction?,
        val from: Int,
        val until: Int,
        val weight: Int,
    )

    // Ranges of storages being read across ticks, merged into [frontBuffer] only
    // once a storage is read to its end. See [SplitScanStaging].
    private val staging = SplitScanStaging<BlockPos, BufferKey.Key, ItemInfo> { a, b ->
        a.copy(count = a.count + b.count)
    }

    /** Size seen on the last poll of a storage position, plans the next cycle's
     *  split. [indexed] false means a handle that can only be read whole. */
    private class MeasuredSize(val weight: Int, val indexed: Boolean)
    private val measuredSizes = HashMap<BlockPos, MeasuredSize>()

    // Entry snapshots (ME networks) being read across ticks this cycle, one per
    // storage, taken by its first range and shared by the rest.
    private val entryPasses = HashMap<BlockPos, EntrySnapshot>()
    // Per-storage reuse state kept across cycles, see [StorageService.snapshotEntries].
    private val entryMemos = HashMap<BlockPos, Any>()

    /** A storage read by index range: live slots, or a frozen entry snapshot. */
    private class RangeReader(
        val size: Int,
        val read: (from: Int, until: Int, visit: (BufferKey.Key, ItemInfo) -> Unit) -> Unit,
    )

    // Snapshot held between [snapshotCardsForCycle] and [finalizeAndDiff] so the
    // craftable-phantom pass at cycle end can read the same network topology that
    // the cycle's per-card scans saw.
    private var cycleSnapshot: NetworkSnapshot? = null

    // Keys whose count was updated mid-cycle by an immediate-delta hook
    // ([onInserted] / [onExtracted] / [onFluidInserted] / [onFluidExtracted]).
    // [applyDiff] skips these keys so a stale poll captured before the hook fired
    // can't revert the entries map to the pre-hook count. Without this, a Terminal
    // extract that happens mid-cycle would briefly flicker back to the pre-extract
    // count when the cycle ends, since the polled `frontBuffer` value is older
    // than the hook-updated `entries` value. Cleared after each [applyDiff].
    private val dirtyKeys = mutableSetOf<BufferKey.Key>()
    private val dirtyFluidKeys = mutableSetOf<String>()

    companion object {
        // Slices per cycle, controls how many ticks one full poll spans.
        // MIN = active cycle (5 ticks per cycle, scan ~1/5 of cards each tick).
        // MAX = idle cycle (60 ticks per cycle, scan ~1/60 of cards each tick).
        private const val MIN_SLICES = 5
        private const val MAX_SLICES = 60
        // Handlers above this many slots are read in ranges of this size, each
        // range scheduled like its own card.
        private const val SLOTS_PER_UNIT = 1024
        // Slots past this on one handler are never read. Real setups are far
        // below it (1000 large chests is ~100k), it stops a handler reporting
        // an absurd slot count from planning millions of units every cycle.
        private const val MAX_SLOTS_PER_HANDLER = 1 shl 20
        private val logger = org.slf4j.LoggerFactory.getLogger("nodeworks-inventory-cache")

        /** Global registry of caches. Keyed by UUID string when controller exists, or dim:pos as fallback. */
        private val caches = ConcurrentHashMap<String, NetworkInventoryCache>()

        fun getOrCreate(level: ServerLevel, networkEntryNode: BlockPos): NetworkInventoryCache {
            // Key on controller permanentId so all consumers of one network share a cache.
            val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, networkEntryNode)
            val uuidKey = snapshot.networkId?.toString()
            val fallbackKey = "${level.dimension().toString()}:${networkEntryNode.asLong()}"

            // Self-heal orphans from a prior multi-controller conflict period: a
            // consumer that opened during the conflict was keyed on dim:pos because
            // [snapshot.networkId] was null. Once the conflict resolves, that orphan
            // would never be evicted, controller cleanup only fires removeByUUID and
            // the dim:pos entry stays in [caches] ticking forever. Migrate this
            // caller's-position orphan onto the UUID key so the consumer's next call
            // self-heals without requiring multiple opens.
            if (uuidKey != null) {
                val orphan = caches.remove(fallbackKey)
                if (orphan != null) caches.putIfAbsent(uuidKey, orphan)
            }

            val key = uuidKey ?: fallbackKey
            val existing = caches[key]
            if (existing != null) {
                // The cached entry point may belong to a consumer that's since been
                // destroyed, adopt the live caller's pos so the next cycle's BFS starts
                // from a still-loaded Connectable.
                existing.rebindEntryPoint(networkEntryNode)
                return existing
            }
            val fresh = NetworkInventoryCache(level, networkEntryNode)
            caches[key] = fresh
            return fresh
        }

        fun removeByUUID(uuid: java.util.UUID) {
            caches.remove(uuid.toString())
        }

        fun remove(level: ServerLevel, networkEntryNode: BlockPos) {
            val key = "${level.dimension().toString()}:${networkEntryNode.asLong()}"
            caches.remove(key)
        }

        fun getAll(): Collection<NetworkInventoryCache> = caches.values
    }

    init {
        // Initial full scan, synchronous so consumers see populated state on first read.
        // Bypasses the round-robin path (a multi-tick first scan would leave the cache
        // empty for several ticks, which Inventory Terminal opens would briefly show
        // as "empty network").
        fullScan()
    }

    /**
     * Called every server tick. Polls one slice of the storage cards and, when a
     * full cycle has completed, applies the diff and adapts the cycle length.
     * Returns true if changes were detected on this tick's diff (only fires on
     * the cycle-end tick, intermediate slices return false).
     *
     * When the `/nwdebug poll` command has any listeners, also emits a chat line
     * per tick describing which cards were just scanned. The bookkeeping is
     * skipped entirely when nobody's listening, so production paths pay the cost
     * of one [PollDebugger.hasListeners] check.
     */
    fun tick(): Boolean {
        val debug = PollDebugger.hasListeners()
        val polledThisTick: MutableList<CardSnapshot>? = if (debug) mutableListOf() else null
        // Snapshot the cycle position BEFORE pollCycle.tick advances it. After the
        // call cycleTick is either incremented (intermediate tick) or reset to 0
        // (cycle-end tick), so we need both the pre-tick value (the slice that was
        // just polled) and the post-tick state (to detect cycle end).
        val cycleTickBefore = pollCycle.cycleTick

        val changed = pollCycle.tickWeighted(
            beginCycle = ::snapshotCardsForCycle,
            weightOf = { it.weight },
            pollItem = if (polledThisTick != null) {
                { unit ->
                    // A split card shows once per tick, not once per range.
                    if (polledThisTick.lastOrNull() !== unit.card) polledThisTick.add(unit.card)
                    pollUnit(unit, pollCycle::defer)
                }
            } else {
                { unit -> pollUnit(unit, pollCycle::defer) }
            },
            endCycle = { finalizeAndDiff(pollCycle.cycleComplete) },
        )
        // Planned at the start of the cycle this tick belongs to, unchanged until the next one.
        val cycleLength = pollCycle.cycleLength

        if (debug) {
            // pollCycle.cycleTick == 0 after the tick means the cycle just ended (it
            // either incremented past sliceCount and reset, or sliceCount was 1 and
            // the tick was a one-tick cycle).
            val cycleEnded = pollCycle.cycleTick == 0
            PollDebugger.emit(
                level = level,
                networkEntryNode = networkEntryNode,
                tick = level.gameTime,
                cycleTick = cycleTickBefore,
                sliceCount = cycleLength,
                polled = polledThisTick ?: emptyList(),
                cycleEnded = cycleEnded,
                cycleChanged = changed,
            )
        }
        return changed
    }

    /** Start a new poll cycle: clear the front buffers, capture the network
     *  snapshot for the cycle, return the frozen unit list to scan. Cards are
     *  deduped by adjacentPos so multi-face setups on one block don't poll
     *  the inventory more than once per cycle. Cards last measured above
     *  [SLOTS_PER_UNIT] slots become one unit per slot range, unmeasured and
     *  whole-only cards stay one unit. */
    private fun snapshotCardsForCycle(): List<PollUnit> {
        frontBuffer.clear()
        fluidFrontBuffer.clear()
        channelFront = HashMap()
        channelFluidFront = HashMap()
        staging.clear()
        entryPasses.clear()
        val snapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        cycleSnapshot = snapshot
        val cards = NetworkStorageHelper.getDedupedStorageCards(snapshot)
        // Which node and side each card is mounted on. Same instances the
        // storage card list is built from, so identity lookup is exact.
        val mounts = java.util.IdentityHashMap<CardSnapshot, Pair<BlockPos, net.minecraft.core.Direction>>()
        for (node in snapshot.nodes) {
            for ((side, sideCards) in node.sides) {
                for (c in sideCards) mounts[c] = node.pos to side
            }
        }
        val units = ArrayList<PollUnit>(cards.size)
        val livePositions = HashSet<BlockPos>()
        for (card in cards) {
            val pos = (card.capability as? damien.nodeworks.card.StorageSideCapability)?.adjacentPos ?: continue
            livePositions.add(pos)
            val mount = mounts[card]
            val size = measuredSizes[pos]
            if (size == null || !size.indexed || size.weight <= SLOTS_PER_UNIT) {
                units.add(PollUnit(card, pos, mount?.first, mount?.second, 0, Int.MAX_VALUE, size?.weight ?: 1))
                continue
            }
            val planned = minOf(size.weight, MAX_SLOTS_PER_HANDLER)
            var from = 0
            while (from < planned) {
                val next = from + SLOTS_PER_UNIT
                val last = next >= planned
                units.add(PollUnit(
                    card, pos, mount?.first, mount?.second,
                    from, if (last) Int.MAX_VALUE else next, minOf(next, planned) - from,
                ))
                from = next
            }
        }
        // Forget removed storages so these can't grow without bound.
        measuredSizes.keys.retainAll(livePositions)
        entryMemos.keys.retainAll(livePositions)
        return units
    }

    /** Read one unit's items (and, on a card's first unit, its fluids). Returns
     *  the entries read so [PollCycle] can measure cost, or
     *  [PollCycle.UNMEASURED] for a handle that can only be read whole.
     *
     *  A storage read in one unit goes straight into [frontBuffer]. One read
     *  across several units is staged and only merged once its last range is
     *  read, so it never counts partially. If it goes away partway (card
     *  pulled, block broken, chunk unloaded, handler changed kind) it's
     *  abandoned for the cycle and its items drop out cleanly. Slot handles
     *  are re-resolved per unit and each range clamped to the live slot count,
     *  so a storage that shrank or grew mid-cycle is read safely. An ME
     *  network is read from one snapshot taken by its first range, so every
     *  range sees the same moment. [defer] queues the rest of an open-ended
     *  unit for a later poll. */
    private fun pollUnit(unit: PollUnit, defer: (PollUnit) -> Unit): Int {
        if (staging.isAbandoned(unit.pos)) return 0
        pollChannel = unit.card.channel
        // The card list is frozen at cycle start. Later ranges re-check the card
        // so a pulled one stops being read, and the chunk so a continuation
        // never force-loads one.
        if (unit.from > 0 && !isStillAttached(unit)) {
            abandon(unit.pos)
            return 0
        }
        var work = 0
        val storage = NetworkStorageHelper.getStorage(level, unit.card)
        val reader = if (storage != null) rangeReaderFor(unit, storage) else null
        when {
            storage == null -> if (unit.from > 0) abandon(unit.pos)
            reader != null -> work = readRange(unit, reader, defer)
            unit.from == 0 -> {
                val items = PlatformServices.storage.findAllItemInfo(storage) { true }
                // Its cost per entry isn't a per-slot cost, keep it out of the estimate.
                work = PollCycle.UNMEASURED
                measuredSizes[unit.pos] = MeasuredSize(items.size, indexed = false)
                addToFrontBuffer(items)
            }
            // A later range with nothing left to read it from.
            else -> abandon(unit.pos)
        }
        // Independent of the item branch: a hybrid block (casting table, ME network)
        // contributes to both buffers in the same poll. Once per card, on its first unit.
        val fluidStorage = if (unit.from == 0) NetworkStorageHelper.getFluidStorage(level, unit.card) else null
        if (fluidStorage != null) {
            val fluids = PlatformServices.storage.findAllFluidInfo(fluidStorage) { true }
            for (info in fluids) {
                val existing = fluidFrontBuffer[info.fluidId]
                if (existing != null) {
                    fluidFrontBuffer[info.fluidId] = existing.copy(amount = existing.amount + info.amount)
                } else {
                    fluidFrontBuffer[info.fluidId] = info
                }
                channelFluidFront.getOrPut(unit.card.channel) { HashMap() }.merge(info.fluidId, info.amount, Long::plus)
            }
        }
        return work
    }

    /** Live slots when the handle has them. Otherwise an entry snapshot: the
     *  first range takes it, later ranges reuse it even if the live handle
     *  changed, since the snapshot is self-contained. Null when neither applies. */
    private fun rangeReaderFor(unit: PollUnit, storage: ItemStorageHandle): RangeReader? {
        val pass = entryPasses[unit.pos]
        if (unit.from == 0 || pass == null) {
            val slots = PlatformServices.storage.slotCount(storage)
            if (slots >= 0) {
                // A later slot range of a storage this cycle started as entries.
                if (unit.from > 0 && pass != null) return null
                return RangeReader(slots) { from, until, visit ->
                    for (info in PlatformServices.storage.findAllItemInfoInRange(storage, from, until) { true }) {
                        visit(cacheKey(info), info)
                    }
                }
            }
            if (unit.from > 0) return null
        }
        val snapshot = if (unit.from == 0) {
            PlatformServices.storage.snapshotEntries(storage, entryMemos[unit.pos])
                ?.also { entryPasses[unit.pos] = it } ?: return null
        } else {
            pass ?: return null
        }
        return RangeReader(snapshot.size) { from, until, visit ->
            PlatformServices.storage.readEntries(snapshot, from, until, visit)
        }
    }

    /** Read [unit]'s range from [reader], staging or committing as described on
     *  [pollUnit]. Returns entries read. */
    private fun readRange(unit: PollUnit, reader: RangeReader, defer: (PollUnit) -> Unit): Int {
        measuredSizes[unit.pos] = MeasuredSize(reader.size, indexed = true)
        val limit = minOf(reader.size, MAX_SLOTS_PER_HANDLER)
        val open = unit.until == Int.MAX_VALUE
        val end = if (open) minOf(limit, unit.from + SLOTS_PER_UNIT) else minOf(unit.until, limit)
        val work = (end - unit.from).coerceAtLeast(0)
        if (unit.from == 0 && end >= limit) {
            if (work > 0) reader.read(0, end, ::addToFrontBuffer)
            finishPass(unit.pos)
        } else {
            if (work > 0) reader.read(unit.from, end) { key, info -> staging.stage(unit.pos, key, info) }
            if (end >= limit) {
                val committed = LinkedHashMap<BufferKey.Key, ItemInfo>()
                staging.commit(unit.pos, committed)
                for ((key, info) in committed) addToFrontBuffer(key, info)
                finishPass(unit.pos)
            } else if (open) {
                defer(PollUnit(unit.card, unit.pos, unit.nodePos, unit.side, end, Int.MAX_VALUE, SLOTS_PER_UNIT))
            }
        }
        return work
    }

    /** A storage was read to its end: keep its entry snapshot's reuse state. */
    private fun finishPass(pos: BlockPos) {
        entryPasses.remove(pos)?.let { entryMemos[pos] = it.memo }
    }

    private fun abandon(pos: BlockPos) {
        staging.abandon(pos)
        entryPasses.remove(pos)
    }

    private fun addToFrontBuffer(key: BufferKey.Key, info: ItemInfo) {
        val existing = frontBuffer[key]
        frontBuffer[key] = if (existing != null) existing.copy(count = existing.count + info.count) else info
        pollChannel?.let { channelFront.getOrPut(it) { HashMap() }.merge(key, info.count, Long::plus) }
    }

    private fun addToFrontBuffer(items: List<ItemInfo>) {
        for (info in items) addToFrontBuffer(cacheKey(info), info)
    }

    /** Whether a later range of [unit] should still be read: its chunks are
     *  loaded and its node still holds a Storage Card in the same slot. */
    private fun isStillAttached(unit: PollUnit): Boolean {
        if (!level.isLoaded(unit.pos)) return false
        val nodePos = unit.nodePos ?: return true
        val side = unit.side ?: return true
        if (!level.isLoaded(nodePos)) return false
        val node = level.getBlockEntity(nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity ?: return false
        return node.getCards(side).any {
            it.slotIndex == unit.card.slotIndex && it.card is damien.nodeworks.card.StorageCard
        }
    }

    /** Finalise a poll cycle: apply craftable phantoms (per-cycle, not per-card)
     *  and diff the front/back buffers. The slice-budget adaptation lives on
     *  [pollCycle] now and runs after this returns.
     *
     *  An incomplete cycle (deferred work dropped at the hard cap) didn't see
     *  everything, and diffing it would remove items that were never read. It's
     *  skipped and the last good state is kept. Every split storage is committed
     *  or abandoned before a complete cycle ends, anything still staged would be
     *  a partial read and is discarded rather than counted. */
    private fun finalizeAndDiff(complete: Boolean): Boolean {
        val stray = staging.hasPending
        staging.clear()
        if (!complete) {
            cycleSnapshot = null
            return false
        }
        if (stray) logger.debug("Inventory cache at {} ended a cycle with a storage half-read, discarded it", networkEntryNode)
        val snapshot = cycleSnapshot
        if (snapshot != null) {
            // Phantom craftable entries for recipe outputs not already in storage.
            // Done at cycle end (not per-card) so the phantom logic only runs once.
            for (crafter in snapshot.crafters) {
                for (iset in crafter.instructionSets) {
                    val outputId = iset.outputItemId
                    if (outputId.isEmpty()) continue
                    if (iset.outputComponents.isEmpty) {
                        addCraftablePhantom(outputId)
                        continue
                    }
                    // Shown as the variant the recipe makes, e.g. an oak barrel.
                    val item = net.minecraft.resources.Identifier.tryParse(outputId)
                        ?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(it) } ?: continue
                    addCraftablePhantom(ItemStack(item).also { it.applyComponents(iset.outputComponents) })
                }
            }
            for (api in snapshot.processingApis) {
                for (procApi in api.apis) {
                    for (output in procApi.outputs) {
                        if (output.itemId.isEmpty()) continue
                        // Pass the full stack so the phantom carries any
                        // DataComponents (potion contents, dyed color,
                        // enchantments) and renders as the proper variant
                        // in the Inventory Terminal instead of falling back
                        // to "Uncraftable Potion" / blank-glint placeholders.
                        addCraftablePhantom(output.stack)
                    }
                }
            }
        }
        cycleSnapshot = null
        val channelsChanged = swapChannelCounts()
        val changed = applyDiff()
        if (changed || channelsChanged) version++
        return changed
    }

    /** Mark [outputId] as craftable, plain-components variant. Used by
     *  Instruction Set recipes whose output is always a plain itemId. */
    private fun addCraftablePhantom(outputId: String) {
        val id = net.minecraft.resources.Identifier.tryParse(outputId) ?: return
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: return
        addCraftablePhantom(ItemStack(item))
    }

    /** Mark the variant identified by [template] as craftable in the front
     *  buffer, either by flipping the flag on an existing entry or by adding
     *  a phantom 0-count entry. Keyed on the template's component-aware
     *  [BufferKey.Key] so component-bearing recipe outputs (potions, dyed
     *  armor) get distinct phantom rows from plain variants of the same
     *  itemId. */
    private fun addCraftablePhantom(template: ItemStack) {
        if (template.isEmpty) return
        val key = BufferKey.of(template)
        val existing = frontBuffer[key]
        if (existing != null) {
            frontBuffer[key] = existing.copy(isCraftable = true)
        } else {
            frontBuffer[key] = ItemInfo(
                itemId = key.itemId,
                name = template.hoverName.string,
                count = 0,
                maxStackSize = template.item.getDefaultMaxStackSize(),
                hasData = !key.isPlain,
                isCraftable = true,
                componentsPatch = template.componentsPatch,
            )
        }
    }

    /** Reconcile [entries] / [fluidEntries] against this cycle's poll.
     *
     *  Removal pass iterates [entries] (not the prior poll), so entries added
     *  via [onInserted] but never observed in a [frontBuffer] still get evicted
     *  when they're really gone. The previous back-vs-front diff missed that
     *  class of orphan: items routed through the pool faster than the poll
     *  could see them (e.g. an importer rule that immediately re-extracts what
     *  another rule just inserted) accumulated forever in [entries] because
     *  they were never in any cycle's back buffer to be diffed away.
     *
     *  Keys in [dirtyKeys] / [dirtyFluidKeys] are protected: they were just
     *  updated by [onInserted] this cycle and the poll may not have covered
     *  the relevant card yet, the next cycle's full sweep will pick them up.
     *  Dirty sets are cleared at the end so the next cycle starts fresh. */
    private fun applyDiff(): Boolean {
        var changed = false

        // Items removed: anything in entries the latest poll didn't see.
        // Iterator-based eviction avoids a full keyset copy every cycle-end.
        val keysToRemove = mutableListOf<BufferKey.Key>()
        for ((key, entry) in entries) {
            if (key in dirtyKeys) continue
            if (key in frontBuffer) continue
            keysToRemove.add(key)
            removedSerials.add(entry.serial)
            changedSerials.remove(entry.serial)
        }
        if (keysToRemove.isNotEmpty()) {
            for (k in keysToRemove) removeEntry(k)
            changed = true
        }

        // Items added or changed. count + isCraftable are both diffed: adding /
        // removing a recipe for an in-stock item only flips the craftable flag
        // without touching count, the count-only check missed that case so
        // recipe registrations never reached the client.
        for ((key, info) in frontBuffer) {
            if (key in dirtyKeys) continue
            val existing = entries[key]
            if (existing == null) {
                val serial = nextSerial++
                putEntry(key, SerialEntry(serial, info))
                changedSerials.add(serial)
                changed = true
            } else {
                // Pick up patch updates too, the prior count-and-craftable-only
                // check left a stale empty patch on entries that were created via
                // [onInserted] (which doesn't see the actual stack), the next
                // poll has the real components and we want them to land.
                val patchChanged = existing.info.componentsPatch != info.componentsPatch
                val countChanged = existing.info.count != info.count
                val craftableChanged = existing.info.isCraftable != info.isCraftable
                if (countChanged || craftableChanged || patchChanged) {
                    putEntry(key, existing.copy(
                        info = existing.info.copy(
                            count = info.count,
                            isCraftable = info.isCraftable,
                            componentsPatch = info.componentsPatch,
                        )
                    ))
                    changedSerials.add(existing.serial)
                    changed = true
                }
            }
        }

        // Fluids, same shape.
        val fluidIter = fluidEntries.entries.iterator()
        while (fluidIter.hasNext()) {
            val (key, entry) = fluidIter.next()
            if (key in dirtyFluidKeys) continue
            if (key in fluidFrontBuffer) continue
            fluidIter.remove()
            removedSerials.add(entry.serial)
            changedFluidSerials.remove(entry.serial)
            changed = true
        }
        for ((key, info) in fluidFrontBuffer) {
            if (key in dirtyFluidKeys) continue
            val existing = fluidEntries[key]
            if (existing == null) {
                val serial = nextSerial++
                fluidEntries[key] = FluidSerialEntry(serial, info)
                changedFluidSerials.add(serial)
                changed = true
            } else if (existing.info.amount != info.amount) {
                fluidEntries[key] = existing.copy(info = existing.info.copy(amount = info.amount))
                changedFluidSerials.add(existing.serial)
                changed = true
            }
        }

        dirtyKeys.clear()
        dirtyFluidKeys.clear()

        return changed
    }

    /** Adopt this cycle's per-channel counts. Keys updated by a hook this cycle keep
     *  their current counts, same as [applyDiff], so a poll older than the hook
     *  can't revert them. Must run before [applyDiff] clears the dirty sets. */
    private fun swapChannelCounts(): Boolean {
        for (key in dirtyKeys) keepCurrent(key, channelItems, channelFront)
        for (key in dirtyFluidKeys) keepCurrent(key, channelFluids, channelFluidFront)
        val changed = channelFront != channelItems || channelFluidFront != channelFluids
        channelItems = channelFront
        channelFluids = channelFluidFront
        channelFront = HashMap()
        channelFluidFront = HashMap()
        return changed
    }

    private fun <K> keepCurrent(
        key: K,
        current: Map<net.minecraft.world.item.DyeColor, Map<K, Long>>,
        polled: HashMap<net.minecraft.world.item.DyeColor, HashMap<K, Long>>,
    ) {
        for (channel in current.keys + polled.keys) {
            val now = current[channel]?.get(key)
            if (now == null) polled[channel]?.remove(key)
            else polled.getOrPut(channel) { HashMap() }[key] = now
        }
    }

    // --- Per-channel queries and deltas ---

    /** Item counts held by [channel]'s Storage Cards, keyed like [entries]. */
    fun channelItemCounts(channel: net.minecraft.world.item.DyeColor): Map<BufferKey.Key, Long> =
        channelItems[channel] ?: emptyMap()

    fun channelFluidCounts(channel: net.minecraft.world.item.DyeColor): Map<String, Long> =
        channelFluids[channel] ?: emptyMap()

    /** How many of one exact variant the network holds, plain included. */
    fun variantCount(key: BufferKey.Key): Long = entries[key]?.info?.count ?: 0L

    /** Every variant of [itemId] held by [channel]. */
    fun channelCount(channel: net.minecraft.world.item.DyeColor, itemId: String): Long {
        val counts = channelItems[channel] ?: return 0L
        return keysByItemId[itemId]?.sumOf { counts[it] ?: 0L } ?: 0L
    }

    /** Pairs every entry with its key, for views that look counts up per channel. */
    fun forEachEntry(visit: (BufferKey.Key, SerialEntry) -> Unit) {
        for ((key, entry) in entries) visit(key, entry)
    }

    fun forEachFluidEntry(visit: (String, FluidSerialEntry) -> Unit) {
        for ((key, entry) in fluidEntries) visit(key, entry)
    }

    /** Record [delta] items moved in or out of a card on [channel]. Pairs with
     *  [onInserted] / [onExtracted], which only see the network total. */
    fun onChannelDelta(
        channel: net.minecraft.world.item.DyeColor,
        itemId: String,
        componentsPatch: DataComponentPatch,
        delta: Long,
    ) {
        if (delta == 0L) return
        val counts = channelItems.getOrPut(channel) { HashMap() }
        val key = cacheKey(itemId, componentsPatch)
        val next = (counts[key] ?: 0L) + delta
        if (next > 0L) counts[key] = next else counts.remove(key)
        version++
    }

    fun onChannelFluidDelta(channel: net.minecraft.world.item.DyeColor, fluidId: String, delta: Long) {
        if (delta == 0L) return
        val counts = channelFluids.getOrPut(channel) { HashMap() }
        val next = (counts[fluidId] ?: 0L) + delta
        if (next > 0L) counts[fluidId] = next else counts.remove(fluidId)
        version++
    }

    // --- Queries ---

    /** True for filters that resolve to an exact item id with no wildcards or
     *  predicate syntax. `*`, `#tag`, `/regex/`, `mod:*`. Those need full
     *  iteration, but a bare `mod:item` filter is just a HashMap lookup. */
    private fun isExactItemFilter(filter: String): Boolean {
        if (filter == "*" || filter.isEmpty()) return false
        if (filter.startsWith("#") || filter.startsWith("/")) return false
        if (filter.endsWith(":*")) return false
        if (filter.startsWith("\$item:") || filter.startsWith("\$fluid:")) return false
        return true
    }

    fun count(filter: String): Long {
        // Fast path so 500 Monitors × every-20-ticks polling for exact ids stays
        // O(1)-ish per query. With component-aware keying we sum across every
        // variant that shares the requested itemId (`minecraft:potion` sums
        // all five potion buckets). The [keysByItemId] secondary index gives
        // us only the variants for this id directly, so plain-item lookups
        // are effectively O(1) and variant-laden lookups (potions) are
        // O(variants) instead of O(total cache entries).
        if (isExactItemFilter(filter)) {
            val variantKeys = keysByItemId[filter] ?: return 0L
            var total = 0L
            for (key in variantKeys) {
                total += entries[key]?.info?.count ?: 0L
            }
            return total
        }
        var total = 0L
        for ((_, entry) in entries) {
            if (CardHandle.matchesFilter(entry.info.itemId, filter)) {
                total += entry.info.count
            }
        }
        return total
    }

    fun find(filter: String): ItemInfo? {
        for ((_, entry) in entries) {
            if (CardHandle.matchesFilter(entry.info.itemId, filter)) {
                return entry.info
            }
        }
        return null
    }

    fun findAll(filter: String): List<ItemInfo> {
        return entries.values.map { it.info }.filter { CardHandle.matchesFilter(it.itemId, filter) }
    }

    fun getAllItems(): Collection<ItemInfo> = entries.values.map { it.info }

    // --- Delta sync for clients (Inventory Terminal) ---

    fun getAllEntries(): Collection<SerialEntry> = entries.values

    fun getAllFluidEntries(): Collection<FluidSerialEntry> = fluidEntries.values

    fun hasChanges(): Boolean = changedSerials.isNotEmpty() ||
            changedFluidSerials.isNotEmpty() ||
            removedSerials.isNotEmpty()

    fun consumeChanges(): Pair<List<SerialEntry>, List<Long>> {
        val changed = changedSerials.mapNotNull { serial ->
            entries.values.find { it.serial == serial }
        }
        val removed = removedSerials.toList()
        changedSerials.clear()
        removedSerials.clear()
        return Pair(changed, removed)
    }

    fun consumeFluidChanges(): List<FluidSerialEntry> {
        val changed = changedFluidSerials.mapNotNull { serial ->
            fluidEntries.values.find { it.serial == serial }
        }
        changedFluidSerials.clear()
        return changed
    }

    // --- Delta updates from script operations (for immediate feedback) ---

    fun onInserted(
        itemId: String,
        hasData: Boolean,
        amount: Long,
        componentsPatch: DataComponentPatch = DataComponentPatch.EMPTY,
    ) {
        if (amount <= 0) return
        version++
        // Bucket on (itemId, componentsHash) so an inserted strength potion
        // doesn't collide with a healing potion bucket. The legacy [hasData]
        // boolean param survives for API stability but the patch is authoritative.
        val key = cacheKey(itemId, componentsPatch)
        // Mark dirty so the next applyDiff doesn't revert this update with a stale
        // pre-insert poll value. See [applyDiff] and [dirtyKeys] for the full rationale.
        dirtyKeys.add(key)
        val existing = entries[key]
        if (existing != null) {
            // Same-key existing entry already has matching components by construction.
            putEntry(key, existing.copy(
                info = existing.info.copy(count = existing.info.count + amount)
            ))
            changedSerials.add(existing.serial)
        } else {
            val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
            val serial = nextSerial++
            // Build a representative stack so the displayed name reflects the
            // specific variant (e.g. "Potion of Strength" instead of "Potion").
            val displayStack = net.minecraft.world.item.ItemStack(item).also {
                it.applyComponents(componentsPatch)
            }
            putEntry(key, SerialEntry(
                serial, ItemInfo(
                    itemId = itemId,
                    name = displayStack.hoverName.string,
                    count = amount,
                    maxStackSize = item.getDefaultMaxStackSize(),
                    hasData = hasData,
                    componentsPatch = componentsPatch,
                )
            ))
            changedSerials.add(serial)
        }
    }

    /** Push an immediate fluid-insert delta so `hasChanges()` flips this tick.
     *  Without this, the menu's broadcastChanges gates on hasChanges() and would
     *  skip syncing until the next poll (up to MAX_SLICES ticks later). */
    fun onFluidInserted(fluidId: String, amount: Long) {
        if (amount <= 0) return
        version++
        dirtyFluidKeys.add(fluidId)
        val existing = fluidEntries[fluidId]
        if (existing != null) {
            fluidEntries[fluidId] = existing.copy(info = existing.info.copy(amount = existing.info.amount + amount))
            changedFluidSerials.add(existing.serial)
        } else {
            // New fluid appeared via delta, we don't have the FluidType's localized name
            // reachable from the server side cheaply, so use the fluid id as a placeholder.
            // The next full poll (within MAX_SLICES) overwrites this with the proper
            // hover name sampled from a live FluidStack.
            val serial = nextSerial++
            fluidEntries[fluidId] = FluidSerialEntry(serial, FluidInfo(fluidId, fluidId, amount))
            changedFluidSerials.add(serial)
        }
    }

    fun onFluidExtracted(fluidId: String, amount: Long) {
        if (amount <= 0) return
        version++
        dirtyFluidKeys.add(fluidId)
        val existing = fluidEntries[fluidId] ?: return
        val newAmount = existing.info.amount - amount
        if (newAmount <= 0) {
            fluidEntries.remove(fluidId)
            removedSerials.add(existing.serial)
            changedFluidSerials.remove(existing.serial)
        } else {
            fluidEntries[fluidId] = existing.copy(info = existing.info.copy(amount = newAmount))
            changedFluidSerials.add(existing.serial)
        }
    }

    fun onExtracted(
        itemId: String,
        hasData: Boolean,
        amount: Long,
        componentsPatch: DataComponentPatch = DataComponentPatch.EMPTY,
    ) {
        if (amount <= 0) return
        version++
        val key = cacheKey(itemId, componentsPatch)
        dirtyKeys.add(key)
        val existing = entries[key] ?: return
        val newCount = existing.info.count - amount
        if (newCount <= 0) {
            if (existing.info.isCraftable) {
                // Keep as phantom craftable entry with 0 count
                putEntry(key, existing.copy(info = existing.info.copy(count = 0)))
                changedSerials.add(existing.serial)
            } else {
                removeEntry(key)
                removedSerials.add(existing.serial)
                changedSerials.remove(existing.serial)
            }
        } else {
            putEntry(key, existing.copy(info = existing.info.copy(count = newCount)))
            changedSerials.add(existing.serial)
        }
    }

    private fun cacheKey(itemId: String, componentsHash: String): BufferKey.Key =
        BufferKey.Key(itemId, componentsHash)

    private fun cacheKey(info: ItemInfo): BufferKey.Key =
        BufferKey.Key(info.itemId, BufferKey.componentsHash(info.componentsPatch))

    /** Helper used by the immediate-delta hook callers ([onInserted], [onExtracted])
     *  which carry a [DataComponentPatch] rather than an [ItemInfo]. */
    private fun cacheKey(itemId: String, componentsPatch: DataComponentPatch): BufferKey.Key =
        BufferKey.Key(itemId, BufferKey.componentsHash(componentsPatch))
}
