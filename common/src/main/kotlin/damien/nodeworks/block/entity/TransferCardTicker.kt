package damien.nodeworks.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * The Nodes that hold a Transfer Card, per dimension. Only these need a tick, and a
 * server can hold a great many Nodes that hold none, so instead of a block entity
 * ticker on every Node the server tick walks this set. A Node joins when a card lands
 * in it or it loads holding one, and leaves when the card goes or it unloads.
 */
object TransferCardTicker {

    private val byDimension = ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>>()

    fun register(level: ServerLevel, pos: BlockPos) {
        byDimension.getOrPut(level.dimension()) { ConcurrentHashMap.newKeySet() }.add(pos.asLong())
    }

    fun unregister(level: ServerLevel, pos: BlockPos) {
        byDimension[level.dimension()]?.remove(pos.asLong())
    }

    fun tick(level: ServerLevel) {
        val positions = byDimension[level.dimension()] ?: return
        if (positions.isEmpty()) return
        val it = positions.iterator()
        while (it.hasNext()) {
            val pos = BlockPos.of(it.next())
            // The same gate a block entity ticker runs under: a chunk that is loaded but
            // outside the ticking range does not run its cards. The entry stays for when it does.
            if (!level.shouldTickBlocksAt(pos)) continue
            // An entry whose Node is gone or no longer holds a card is stale, drop it.
            val node = level.getBlockEntity(pos) as? NodeBlockEntity
            if (node == null || node.isRemoved || !node.hasTransferCards) {
                it.remove()
                continue
            }
            node.serverTick(level)
        }
    }

    /** Server stop, the next run re-registers from each Node's load. */
    fun clear() {
        byDimension.clear()
    }
}
