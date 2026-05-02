package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.FluidInfo
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
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
    private val networkEntryNode: BlockPos
) {
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
    private val frontBuffer = LinkedHashMap<String, ItemInfo>()
    private val fluidFrontBuffer = LinkedHashMap<String, FluidInfo>()

    // Serial-tracked entries for delta sync to clients
    private val entries = LinkedHashMap<String, SerialEntry>()
    private val fluidEntries = LinkedHashMap<String, FluidSerialEntry>()
    private var nextSerial = 1L

    // Change tracking for delta sync (shared serial space, items + fluids)
    private val changedSerials = mutableSetOf<Long>()
    private val removedSerials = mutableSetOf<Long>()
    private val changedFluidSerials = mutableSetOf<Long>()

    // Round-robin polling state. The previous "poll all cards every N ticks" model
    // is replaced with "spread one full poll across N ticks, polling 1/N of the
    // cards per tick." Same effective rate, no per-tick burst when networks have
    // many storage cards. The cycle state machine lives in [PollCycle], this class
    // just wires its callbacks to the cache-specific buffer-swap, per-card poll,
    // and apply-diff steps.
    private val pollCycle = PollCycle<CardSnapshot>(MIN_SLICES, MAX_SLICES)

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
    private val dirtyKeys = mutableSetOf<String>()
    private val dirtyFluidKeys = mutableSetOf<String>()

    companion object {
        // Slices per cycle, controls how many ticks one full poll spans.
        // MIN = active cycle (5 ticks per cycle, scan ~1/5 of cards each tick).
        // MAX = idle cycle (60 ticks per cycle, scan ~1/60 of cards each tick).
        private const val MIN_SLICES = 5
        private const val MAX_SLICES = 60

        /** Global registry of caches. Keyed by UUID string when controller exists, or dim:pos as fallback. */
        private val caches = ConcurrentHashMap<String, NetworkInventoryCache>()

        fun getOrCreate(level: ServerLevel, networkEntryNode: BlockPos): NetworkInventoryCache {
            // Discover network to find the controller UUID for the key
            val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, networkEntryNode)
            val key = if (snapshot.networkId != null) {
                snapshot.networkId.toString()
            } else {
                "${level.dimension().toString()}:${networkEntryNode.asLong()}"
            }
            return caches.getOrPut(key) { NetworkInventoryCache(level, networkEntryNode) }
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
        val cards = snapshotCardsForCycle()
        for (card in cards) pollCard(card)
        finalizeAndDiff()
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
        val sliceCountBefore = pollCycle.sliceCount

        val changed = pollCycle.tick(
            beginCycle = ::snapshotCardsForCycle,
            pollItem = if (polledThisTick != null) {
                { card -> polledThisTick.add(card); pollCard(card) }
            } else {
                ::pollCard
            },
            endCycle = ::finalizeAndDiff,
        )

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
                sliceCount = sliceCountBefore,
                polled = polledThisTick ?: emptyList(),
                cycleEnded = cycleEnded,
                cycleChanged = changed,
            )
        }
        return changed
    }

    /** Start a new poll cycle: clear the front buffers, capture the network
     *  snapshot for the cycle, return the frozen card list to scan. */
    private fun snapshotCardsForCycle(): List<CardSnapshot> {
        frontBuffer.clear()
        fluidFrontBuffer.clear()
        val snapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        cycleSnapshot = snapshot
        return NetworkStorageHelper.getStorageCards(snapshot).toList()
    }

    /** Read one storage card's items and fluids into the front buffers. Called
     *  once per card per cycle, distributed across the cycle's ticks. */
    private fun pollCard(card: CardSnapshot) {
        val storage = NetworkStorageHelper.getStorage(level, card)
        if (storage != null) {
            val items = PlatformServices.storage.findAllItemInfo(storage) { true }
            for (info in items) {
                val key = cacheKey(info.itemId, info.hasData)
                val existing = frontBuffer[key]
                if (existing != null) {
                    frontBuffer[key] = existing.copy(count = existing.count + info.count)
                } else {
                    frontBuffer[key] = info
                }
            }
        }
        // Storage cards are fluid-first (see NetworkStorageHelper.getStorage): getStorage
        // returns null when the block exposes a fluid cap, and getFluidStorage handles
        // the fluid side. The two branches are mutually exclusive for a given card.
        val fluidStorage = NetworkStorageHelper.getFluidStorage(level, card)
        if (fluidStorage != null) {
            val fluids = PlatformServices.storage.findAllFluidInfo(fluidStorage) { true }
            for (info in fluids) {
                val existing = fluidFrontBuffer[info.fluidId]
                if (existing != null) {
                    fluidFrontBuffer[info.fluidId] = existing.copy(amount = existing.amount + info.amount)
                } else {
                    fluidFrontBuffer[info.fluidId] = info
                }
            }
        }
    }

    /** Finalise a poll cycle: apply craftable phantoms (per-cycle, not per-card)
     *  and diff the front/back buffers. The slice-budget adaptation lives on
     *  [pollCycle] now and runs after this returns. */
    private fun finalizeAndDiff(): Boolean {
        val snapshot = cycleSnapshot
        if (snapshot != null) {
            // Phantom craftable entries for recipe outputs not already in storage.
            // Done at cycle end (not per-card) so the phantom logic only runs once.
            for (crafter in snapshot.crafters) {
                for (iset in crafter.instructionSets) {
                    val outputId = iset.outputItemId
                    if (outputId.isEmpty()) continue
                    addCraftablePhantom(outputId)
                }
            }
            for (api in snapshot.processingApis) {
                for (procApi in api.apis) {
                    for (outputId in procApi.outputItemIds) {
                        if (outputId.isEmpty()) continue
                        addCraftablePhantom(outputId)
                    }
                }
            }
        }
        cycleSnapshot = null
        return applyDiff()
    }

    /** Mark [outputId] as craftable in the front buffer, either by flipping the
     *  flag on an existing entry or by adding a phantom 0-count entry. Shared
     *  between Crafter and Processing-API phantom passes in [finalizeAndDiff]. */
    private fun addCraftablePhantom(outputId: String) {
        val key = cacheKey(outputId, false)
        val existing = frontBuffer[key]
        if (existing != null) {
            frontBuffer[key] = existing.copy(isCraftable = true)
        } else {
            val id = net.minecraft.resources.Identifier.tryParse(outputId) ?: return
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: return
            val name = Component.translatable(item.descriptionId).string
            frontBuffer[key] = ItemInfo(outputId, name, 0, item.getDefaultMaxStackSize(), false, isCraftable = true)
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
        val itemKeys = entries.keys.toSet()
        for (key in itemKeys) {
            if (key in dirtyKeys) continue
            if (key !in frontBuffer) {
                val entry = entries.remove(key) ?: continue
                removedSerials.add(entry.serial)
                changedSerials.remove(entry.serial)
                changed = true
            }
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
                entries[key] = SerialEntry(serial, info)
                changedSerials.add(serial)
                changed = true
            } else if (existing.info.count != info.count || existing.info.isCraftable != info.isCraftable) {
                entries[key] = existing.copy(
                    info = existing.info.copy(count = info.count, isCraftable = info.isCraftable)
                )
                changedSerials.add(existing.serial)
                changed = true
            }
        }

        // Fluids, same shape.
        val fluidKeys = fluidEntries.keys.toSet()
        for (key in fluidKeys) {
            if (key in dirtyFluidKeys) continue
            if (key !in fluidFrontBuffer) {
                val entry = fluidEntries.remove(key) ?: continue
                removedSerials.add(entry.serial)
                changedFluidSerials.remove(entry.serial)
                changed = true
            }
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

    // --- Queries ---

    fun count(filter: String): Long {
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

    fun onInserted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        // Mark dirty so the next applyDiff doesn't revert this update with a stale
        // pre-insert poll value. See [applyDiff] and [dirtyKeys] for the full rationale.
        dirtyKeys.add(key)
        val existing = entries[key]
        if (existing != null) {
            entries[key] = existing.copy(info = existing.info.copy(count = existing.info.count + amount))
            changedSerials.add(existing.serial)
        } else {
            val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
            val serial = nextSerial++
            entries[key] = SerialEntry(serial, ItemInfo(
                itemId = itemId,
                name = net.minecraft.world.item.ItemStack(item).hoverName.string,
                count = amount,
                maxStackSize = item.getDefaultMaxStackSize(),
                hasData = hasData
            ))
            changedSerials.add(serial)
        }
    }

    /** Push an immediate fluid-insert delta so `hasChanges()` flips this tick.
     *  Without this, the menu's broadcastChanges gates on hasChanges() and would
     *  skip syncing until the next poll (up to MAX_SLICES ticks later). */
    fun onFluidInserted(fluidId: String, amount: Long) {
        if (amount <= 0) return
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

    fun onExtracted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        dirtyKeys.add(key)
        val existing = entries[key] ?: return
        val newCount = existing.info.count - amount
        if (newCount <= 0) {
            if (existing.info.isCraftable) {
                // Keep as phantom craftable entry with 0 count
                entries[key] = existing.copy(info = existing.info.copy(count = 0))
                changedSerials.add(existing.serial)
            } else {
                entries.remove(key)
                removedSerials.add(existing.serial)
                changedSerials.remove(existing.serial)
            }
        } else {
            entries[key] = existing.copy(info = existing.info.copy(count = newCount))
            changedSerials.add(existing.serial)
        }
    }

    private fun cacheKey(itemId: String, hasData: Boolean): String = "$itemId:$hasData"
}
