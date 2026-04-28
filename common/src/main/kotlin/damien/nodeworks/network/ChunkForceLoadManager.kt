package damien.nodeworks.network

import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-dimension refcount on chunks claimed by Network Controllers with chunk loading
 * enabled. A chunk is force-loaded iff at least one enabled controller claims it, so two
 * networks overlapping in the same chunk keep each other alive, disabling one doesn't
 * unload chunks the other still wants.
 *
 * Not persisted. On server startup the vanilla forced-chunks set (forcedchunks.dat) is
 * already restored by the engine, each controller's `setLevel` will re-claim its stored
 * chunks, rebuilding the refcount map in memory. The first re-claim of a still-forced
 * chunk is a no-op at the vanilla level (idempotent), so no double-force.
 */
object ChunkForceLoadManager {
    private val refCounts = ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, Int>>()

    /** Claim a chunk for force-loading. If the refcount was 0, the chunk is actually
     *  force-loaded now, otherwise just the count bumps. */
    fun claim(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val map = refCounts.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }
        val key = ChunkPos.pack(chunkX, chunkZ)
        val newCount = map.merge(key, 1) { a, b -> a + b } ?: 1
        if (newCount == 1) level.setChunkForced(chunkX, chunkZ, true)
    }

    /** Drop one claim on a chunk. If the refcount reaches 0, the chunk is actually
     *  unforced, otherwise other claimants keep it loaded. */
    fun unclaim(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val map = refCounts[level.dimension()] ?: return
        val key = ChunkPos.pack(chunkX, chunkZ)
        val newCount = map.compute(key) { _, v -> if (v == null || v <= 1) null else v - 1 }
        if (newCount == null) level.setChunkForced(chunkX, chunkZ, false)
    }

    /** Drop every claim held in this dimension. Called from server shutdown to reset the
     *  in-memory state, vanilla re-persists the forced-chunks set so the next run will
     *  pick up where we left off. */
    fun clearDimension(key: ResourceKey<Level>) {
        refCounts.remove(key)
    }

    fun clearAll() {
        refCounts.clear()
    }
}
