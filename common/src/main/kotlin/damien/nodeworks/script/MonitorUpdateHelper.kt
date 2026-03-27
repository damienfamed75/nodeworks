package damien.nodeworks.script

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import java.util.concurrent.ConcurrentHashMap

/**
 * Updates monitor display counts from the shared NetworkInventoryCache.
 * Runs every 20 ticks (1 second) for each node that has monitors.
 */
object MonitorUpdateHelper {

    private val monitoredNodes = ConcurrentHashMap.newKeySet<BlockPos>()

    fun trackNode(pos: BlockPos) {
        monitoredNodes.add(pos)
    }

    fun untrackNode(pos: BlockPos) {
        monitoredNodes.remove(pos)
    }

    fun tick(level: ServerLevel, tickCount: Long) {
        if (tickCount % 20 != 0L) return
        if (monitoredNodes.isEmpty()) return

        val toRemove = mutableListOf<BlockPos>()

        for (nodePos in monitoredNodes) {
            if (!level.isLoaded(nodePos)) continue
            val entity = level.getBlockEntity(nodePos) as? NodeBlockEntity
            if (entity == null) {
                toRemove.add(nodePos)
                continue
            }

            val monitorFaces = entity.getMonitorFaces()
            if (monitorFaces.isEmpty()) {
                toRemove.add(nodePos)
                continue
            }

            // Get or create the shared cache for this node's network
            val cache = NetworkInventoryCache.getOrCreate(level, nodePos)

            var changed = false
            for (face in monitorFaces) {
                val monitor = entity.getMonitor(face) ?: continue
                val itemId = monitor.trackedItemId ?: continue
                val count = cache.count(itemId)
                if (count != monitor.displayCount) {
                    entity.updateMonitorCount(face, count)
                    changed = true
                }
            }

            if (changed) {
                entity.setChanged()
                level.sendBlockUpdated(nodePos, entity.blockState, entity.blockState, Block.UPDATE_CLIENTS)
            }
        }

        toRemove.forEach { monitoredNodes.remove(it) }
    }
}
