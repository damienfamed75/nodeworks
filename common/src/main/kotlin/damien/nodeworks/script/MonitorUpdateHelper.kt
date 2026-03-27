package damien.nodeworks.script

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import java.util.concurrent.ConcurrentHashMap

/**
 * Updates monitor display counts independently of the script engine.
 * Scans only the specific items being monitored, not the entire inventory.
 * Runs every 100 ticks (5 seconds) for each node that has monitors.
 */
object MonitorUpdateHelper {

    /** Tracks which nodes have monitors and need periodic updates. */
    private val monitoredNodes = ConcurrentHashMap.newKeySet<BlockPos>()

    fun trackNode(pos: BlockPos) {
        monitoredNodes.add(pos)
    }

    fun untrackNode(pos: BlockPos) {
        monitoredNodes.remove(pos)
    }

    /** Called every server tick. Updates monitors every 100 ticks (5 seconds). */
    fun tick(level: ServerLevel, tickCount: Long) {
        if (tickCount % 100 != 0L) return
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

            // Collect which items need counting
            val itemsToCount = mutableSetOf<String>()
            for (face in monitorFaces) {
                val monitor = entity.getMonitor(face) ?: continue
                val itemId = monitor.trackedItemId ?: continue
                itemsToCount.add(itemId)
            }

            if (itemsToCount.isEmpty()) continue

            // Discover network from this node and count the specific items
            val snapshot = NetworkDiscovery.discoverNetwork(level, nodePos)
            val counts = mutableMapOf<String, Long>()
            for (itemId in itemsToCount) {
                counts[itemId] = NetworkStorageHelper.countItems(level, snapshot, itemId)
            }

            // Update monitors
            var changed = false
            for (face in monitorFaces) {
                val monitor = entity.getMonitor(face) ?: continue
                val itemId = monitor.trackedItemId ?: continue
                val count = counts[itemId] ?: 0L
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
