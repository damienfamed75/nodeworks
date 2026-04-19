package damien.nodeworks.script

import damien.nodeworks.block.entity.MonitorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Refreshes every Monitor block's displayed item count from the shared
 * [NetworkInventoryCache]. Runs every 20 ticks (1 second). Monitor BEs register
 * themselves via [trackMonitor] during `setLevel` and unregister via
 * [untrackMonitor] during `setRemoved`.
 *
 * **Cache-lookup dedupe:** [NetworkInventoryCache.getOrCreate] calls
 * `NetworkDiscovery.discoverNetwork` every invocation just to resolve the
 * UUID-keyed cache entry. Calling it once per monitor would rediscover the same
 * network N times — a trivially-observable perf hit on big networks. Instead we
 * group monitors by [MonitorBlockEntity.networkId] and only ask the registry for
 * one cache per unique network per tick. Monitors with `networkId == null` (not
 * yet joined / orphaned) fall through to a per-position lookup since there's no
 * other way to key them — that path is rare (a single monitor tracking nothing
 * useful anyway).
 */
object MonitorUpdateHelper {

    private val monitored = ConcurrentHashMap.newKeySet<BlockPos>()

    fun trackMonitor(pos: BlockPos) {
        monitored.add(pos)
    }

    fun untrackMonitor(pos: BlockPos) {
        monitored.remove(pos)
    }

    fun tick(level: ServerLevel, tickCount: Long) {
        if (tickCount % 20 != 0L) return
        if (monitored.isEmpty()) return

        val toRemove = mutableListOf<BlockPos>()
        // Build (BE, itemId) work list while collecting unique networkIds, so the
        // discovery cost scales with networks, not monitors.
        data class Entry(val be: MonitorBlockEntity, val itemId: String)
        val entries = mutableListOf<Entry>()
        val uniqueNetIds = LinkedHashSet<UUID>()
        val orphans = mutableListOf<Entry>()

        for (pos in monitored) {
            if (!level.isLoaded(pos)) continue
            val be = level.getBlockEntity(pos) as? MonitorBlockEntity
            if (be == null) {
                toRemove.add(pos)
                continue
            }
            val itemId = be.trackedItemId ?: continue
            val entry = Entry(be, itemId)
            val nid = be.networkId
            if (nid == null) orphans.add(entry) else {
                entries.add(entry)
                uniqueNetIds.add(nid)
            }
        }

        // Resolve a cache PER NETWORK once. Any monitor on the same network reuses it.
        // We pick the first monitor's position as the entry node — the cache's key is
        // the controller UUID so every monitor on the same network hits the same slot.
        val cachesByNet = HashMap<UUID, NetworkInventoryCache>(uniqueNetIds.size)
        for (entry in entries) {
            val nid = entry.be.networkId ?: continue
            val cache = cachesByNet.getOrPut(nid) {
                NetworkInventoryCache.getOrCreate(level, entry.be.blockPos)
            }
            val count = cache.count(entry.itemId)
            if (count != entry.be.displayCount) entry.be.updateDisplayCount(count)
        }

        // Orphans (no networkId yet) — one lookup each, same as the old path.
        for (entry in orphans) {
            val cache = NetworkInventoryCache.getOrCreate(level, entry.be.blockPos)
            val count = cache.count(entry.itemId)
            if (count != entry.be.displayCount) entry.be.updateDisplayCount(count)
        }

        toRemove.forEach { monitored.remove(it) }
    }
}
