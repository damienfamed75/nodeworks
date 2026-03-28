package damien.nodeworks.script

import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
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

    // Double buffer for diff detection
    private var frontBuffer = LinkedHashMap<String, ItemInfo>()
    private var backBuffer = LinkedHashMap<String, ItemInfo>()

    // Serial-tracked entries for delta sync to clients
    private val entries = LinkedHashMap<String, SerialEntry>()
    private var nextSerial = 1L

    // Change tracking for delta sync
    private val changedSerials = mutableSetOf<Long>()
    private val removedSerials = mutableSetOf<Long>()

    // Adaptive tick rate
    private var tickInterval = 5 // start fast
    private var ticksUntilNext = 0
    private var lastChangeDetected = false

    companion object {
        private const val MIN_TICK_INTERVAL = 5    // fastest: every 5 ticks (0.25s)
        private const val MAX_TICK_INTERVAL = 60   // slowest: every 60 ticks (3s)

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
        // Initial full scan
        pollContainers()
        applyDiff()
    }

    /**
     * Called every server tick. Manages adaptive tick rate and polls when due.
     * Returns true if changes were detected.
     */
    fun tick(): Boolean {
        ticksUntilNext--
        if (ticksUntilNext > 0) return false

        // Poll all storage card containers
        pollContainers()
        val changed = applyDiff()

        // Adaptive tick rate
        if (changed) {
            tickInterval = MIN_TICK_INTERVAL // speed up
            lastChangeDetected = true
        } else {
            if (lastChangeDetected) {
                // First idle tick after changes — stay fast for one more cycle
                lastChangeDetected = false
            } else {
                // Slow down gradually
                tickInterval = minOf(tickInterval + 5, MAX_TICK_INTERVAL)
            }
        }
        ticksUntilNext = tickInterval

        return changed
    }

    /** Read all storage card containers into the front buffer. */
    private fun pollContainers() {
        // Swap buffers
        val tmp = backBuffer
        backBuffer = frontBuffer
        frontBuffer = tmp
        frontBuffer.clear()

        // Discover network and read all storage
        val snapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
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
    }

    /** Compare front buffer against back buffer, update entries and change tracking. */
    private fun applyDiff(): Boolean {
        var changed = false

        // Detect removed items (in back but not in front)
        val backKeys = backBuffer.keys.toSet()
        for (key in backKeys) {
            if (key !in frontBuffer) {
                val entry = entries.remove(key)
                if (entry != null) {
                    removedSerials.add(entry.serial)
                    changedSerials.remove(entry.serial)
                    changed = true
                }
            }
        }

        // Detect new and changed items
        for ((key, info) in frontBuffer) {
            val existing = entries[key]
            if (existing == null) {
                // New item
                val serial = nextSerial++
                entries[key] = SerialEntry(serial, info)
                changedSerials.add(serial)
                changed = true
            } else if (existing.info.count != info.count) {
                // Count changed
                entries[key] = existing.copy(info = existing.info.copy(count = info.count))
                changedSerials.add(existing.serial)
                changed = true
            }
        }

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

    fun hasChanges(): Boolean = changedSerials.isNotEmpty() || removedSerials.isNotEmpty()

    fun consumeChanges(): Pair<List<SerialEntry>, List<Long>> {
        val changed = changedSerials.mapNotNull { serial ->
            entries.values.find { it.serial == serial }
        }
        val removed = removedSerials.toList()
        changedSerials.clear()
        removedSerials.clear()
        return Pair(changed, removed)
    }

    // --- Delta updates from script operations (for immediate feedback) ---

    fun onInserted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
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
                name = item.getName(net.minecraft.world.item.ItemStack(item)).string,
                count = amount,
                maxStackSize = item.defaultMaxStackSize,
                hasData = hasData
            ))
            changedSerials.add(serial)
        }
    }

    fun onExtracted(itemId: String, hasData: Boolean, amount: Long) {
        if (amount <= 0) return
        val key = cacheKey(itemId, hasData)
        val existing = entries[key] ?: return
        val newCount = existing.info.count - amount
        if (newCount <= 0) {
            entries.remove(key)
            removedSerials.add(existing.serial)
            changedSerials.remove(existing.serial)
        } else {
            entries[key] = existing.copy(info = existing.info.copy(count = newCount))
            changedSerials.add(existing.serial)
        }
    }

    private fun cacheKey(itemId: String, hasData: Boolean): String = "$itemId:$hasData"
}
