package damien.nodeworks.script

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

    // Double buffer for diff detection, items
    private var frontBuffer = LinkedHashMap<String, ItemInfo>()
    private var backBuffer = LinkedHashMap<String, ItemInfo>()

    // Double buffer for diff detection, fluids
    private var fluidFrontBuffer = LinkedHashMap<String, FluidInfo>()
    private var fluidBackBuffer = LinkedHashMap<String, FluidInfo>()

    // Serial-tracked entries for delta sync to clients
    private val entries = LinkedHashMap<String, SerialEntry>()
    private val fluidEntries = LinkedHashMap<String, FluidSerialEntry>()
    private var nextSerial = 1L

    // Change tracking for delta sync (shared serial space, items + fluids)
    private val changedSerials = mutableSetOf<Long>()
    private val removedSerials = mutableSetOf<Long>()
    private val changedFluidSerials = mutableSetOf<Long>()

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
                // First idle tick after changes, stay fast for one more cycle
                lastChangeDetected = false
            } else {
                // Slow down gradually
                tickInterval = minOf(tickInterval + 5, MAX_TICK_INTERVAL)
            }
        }
        ticksUntilNext = tickInterval

        return changed
    }

    /** Read all storage card containers into the front buffers (items + fluids). */
    private fun pollContainers() {
        // Swap buffers
        val tmp = backBuffer
        backBuffer = frontBuffer
        frontBuffer = tmp
        frontBuffer.clear()

        val fluidTmp = fluidBackBuffer
        fluidBackBuffer = fluidFrontBuffer
        fluidFrontBuffer = fluidTmp
        fluidFrontBuffer.clear()

        // Discover network and read all storage
        val snapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
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

        // Add phantom craftable entries for recipe outputs not already in storage
        for (crafter in snapshot.crafters) {
            for (iset in crafter.instructionSets) {
                val outputId = iset.outputItemId
                if (outputId.isEmpty()) continue
                val key = cacheKey(outputId, false)
                val existing = frontBuffer[key]
                if (existing != null) {
                    // Already in storage, mark as craftable
                    frontBuffer[key] = existing.copy(isCraftable = true)
                } else {
                    // Not in storage, add phantom with count 0
                    val id = net.minecraft.resources.Identifier.tryParse(outputId) ?: continue
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: continue
                    val name = Component.translatable(item.descriptionId).string
                    frontBuffer[key] = ItemInfo(outputId, name, 0, item.getDefaultMaxStackSize(), false, isCraftable = true)
                }
            }
        }
        for (api in snapshot.processingApis) {
            for (procApi in api.apis) {
                for (outputId in procApi.outputItemIds) {
                    if (outputId.isEmpty()) continue
                    val key = cacheKey(outputId, false)
                    val existing = frontBuffer[key]
                    if (existing != null) {
                        frontBuffer[key] = existing.copy(isCraftable = true)
                    } else {
                        val id = net.minecraft.resources.Identifier.tryParse(outputId) ?: continue
                        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: continue
                        val name = Component.translatable(item.descriptionId).string
                        frontBuffer[key] = ItemInfo(outputId, name, 0, item.getDefaultMaxStackSize(), false, isCraftable = true)
                    }
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

        // Detect new and changed items. We compare count AND isCraftable: adding or
        //  removing a Processing Set / Instruction Set for an item that was already
        //  present in storage only flips the craftable flag, leaving count untouched
        //, the old count-only check missed that case, so adds/removes of recipes
        //  for in-stock items never reached the client.
        for ((key, info) in frontBuffer) {
            val existing = entries[key]
            if (existing == null) {
                // New item
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

        // Fluids, same diff pattern against fluidFrontBuffer / fluidBackBuffer.
        val fluidBackKeys = fluidBackBuffer.keys.toSet()
        for (key in fluidBackKeys) {
            if (key !in fluidFrontBuffer) {
                val entry = fluidEntries.remove(key)
                if (entry != null) {
                    removedSerials.add(entry.serial)
                    changedFluidSerials.remove(entry.serial)
                    changed = true
                }
            }
        }
        for ((key, info) in fluidFrontBuffer) {
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
     *  skip syncing until the next poll (up to MAX_TICK_INTERVAL ticks later). */
    fun onFluidInserted(fluidId: String, amount: Long) {
        if (amount <= 0) return
        val existing = fluidEntries[fluidId]
        if (existing != null) {
            fluidEntries[fluidId] = existing.copy(info = existing.info.copy(amount = existing.info.amount + amount))
            changedFluidSerials.add(existing.serial)
        } else {
            // New fluid appeared via delta, we don't have the FluidType's localized name
            // reachable from the server side cheaply, so use the fluid id as a placeholder.
            // The next full poll (within MAX_TICK_INTERVAL) overwrites this with the proper
            // hover name sampled from a live FluidStack.
            val serial = nextSerial++
            fluidEntries[fluidId] = FluidSerialEntry(serial, FluidInfo(fluidId, fluidId, amount))
            changedFluidSerials.add(serial)
        }
    }

    fun onFluidExtracted(fluidId: String, amount: Long) {
        if (amount <= 0) return
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
