package damien.nodeworks.script

import damien.nodeworks.block.entity.MonitorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * Refreshes every Monitor block's displayed item count from the shared
 * [NetworkInventoryCache]. Runs every 20 ticks (1 second). Monitor BEs register
 * themselves via [trackMonitor] during `setLevel` and unregister via
 * [untrackMonitor] during `setRemoved`.
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

        for (pos in monitored) {
            if (!level.isLoaded(pos)) continue
            val be = level.getBlockEntity(pos) as? MonitorBlockEntity
            if (be == null) {
                toRemove.add(pos)
                continue
            }
            val itemId = be.trackedItemId ?: continue
            val cache = NetworkInventoryCache.getOrCreate(level, pos)
            val count = cache.count(itemId)
            if (count != be.displayCount) {
                be.updateDisplayCount(count)
            }
        }

        toRemove.forEach { monitored.remove(it) }
    }
}
