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

    /** Each dimension's registered Nodes as an array for the walk, rebuilt only when
     *  the set changes, so a tick copies nothing. */
    private val orderCache = HashMap<ResourceKey<Level>, LongArray>()
    private val orderDirty = HashSet<ResourceKey<Level>>()

    /** When each Node next has a card due, and when it last ran, by packed position. A
     *  Node that is not due costs a tick one map lookup, not a chunk and block entity
     *  lookup, which is what keeps ten thousand idle Nodes cheap. */
    private val dueAt = HashMap<ResourceKey<Level>, HashMap<Long, Long>>()
    private val lastRun = HashMap<ResourceKey<Level>, HashMap<Long, Long>>()

    private fun dueAt(dimension: ResourceKey<Level>): HashMap<Long, Long> = dueAt.getOrPut(dimension) { HashMap() }
    private fun lastRun(dimension: ResourceKey<Level>): HashMap<Long, Long> = lastRun.getOrPut(dimension) { HashMap() }

    /** Where each dimension's walk picks up next tick, so a tick cut short by the time
     *  cap does not leave the same Nodes at the back of the line every time. */
    private val resumeAt = HashMap<ResourceKey<Level>, Int>()

    /** Server-wide time Transfer Cards may spend per tick across every network in every
     *  dimension. Each network has its own move budget, and without this a server full
     *  of one-Node networks would have no ceiling on their sum. */
    private const val MAX_NANOS_PER_TICK = 5_000_000L

    /** How long a Node in a non-ticking chunk waits before the ticker looks at it again. */
    private const val UNLOADED_RECHECK_TICKS = 20L

    fun register(level: ServerLevel, pos: BlockPos) {
        if (byDimension.getOrPut(level.dimension()) { ConcurrentHashMap.newKeySet() }.add(pos.asLong())) {
            orderDirty.add(level.dimension())
        }
        // A card just arrived, so it runs on the next walk.
        dueAt[level.dimension()]?.remove(pos.asLong())
    }

    fun isRegistered(level: ServerLevel, pos: BlockPos): Boolean =
        byDimension[level.dimension()]?.contains(pos.asLong()) == true

    fun unregister(level: ServerLevel, pos: BlockPos) {
        if (byDimension[level.dimension()]?.remove(pos.asLong()) == true) orderDirty.add(level.dimension())
        dueAt[level.dimension()]?.remove(pos.asLong())
        lastRun[level.dimension()]?.remove(pos.asLong())
    }

    /** Which dimension goes first this tick, so one dimension's Nodes are not always the
     *  ones the shared time cap cuts off. */
    private var dimensionOffset = 0

    /** Runs every dimension's cards under one shared time cap. */
    fun tickAll(levels: Iterable<ServerLevel>) {
        val list = levels.toList()
        if (list.isEmpty()) return
        val began = System.nanoTime()
        val first = dimensionOffset % list.size
        dimensionOffset = (dimensionOffset + 1) % list.size
        for (i in list.indices) {
            if (System.nanoTime() - began > MAX_NANOS_PER_TICK) break
            tick(list[(first + i) % list.size], began)
        }
    }

    private fun tick(level: ServerLevel, began: Long) {
        val dimension = level.dimension()
        val positions = byDimension[dimension] ?: return
        if (positions.isEmpty()) return
        val order = if (orderDirty.remove(dimension) || dimension !in orderCache) {
            positions.toLongArray().also { orderCache[dimension] = it }
        } else orderCache.getValue(dimension)
        val n = order.size
        if (n == 0) return
        val start = (resumeAt[dimension] ?: 0) % n
        val now = level.gameTime
        val due = dueAt(dimension)
        val ran = lastRun(dimension)
        var walked = 0
        while (walked < n) {
            val packed = order[(start + walked) % n]
            walked++
            if ((due[packed] ?: 0L) > now) continue
            val pos = BlockPos.of(packed)
            // The same gate a block entity ticker runs under: a chunk that is loaded but
            // outside the ticking range does not run its cards. The entry stays for when it does.
            if (!level.shouldTickBlocksAt(pos)) {
                // Unloaded or out of simulation range. Look again in a second, so a Node
                // whose chunk went away costs one lookup a second rather than one a tick.
                due[packed] = now + UNLOADED_RECHECK_TICKS
                continue
            }
            // An entry whose Node is gone or no longer holds a card is stale, drop it.
            val node = level.getBlockEntity(pos) as? NodeBlockEntity
            if (node == null || node.isRemoved || !node.hasTransferCards) {
                positions.remove(packed)
                orderDirty.add(dimension)
                due.remove(packed)
                ran.remove(packed)
                continue
            }
            val elapsed = (now - (ran[packed] ?: (now - 1))).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val next = node.serverTick(level, elapsed)
            ran[packed] = now
            due[packed] = if (next == Int.MAX_VALUE) Long.MAX_VALUE else now + next
            if (System.nanoTime() - began > MAX_NANOS_PER_TICK) break
        }
        resumeAt[dimension] = (start + walked) % n
    }

    /** Server stop, the next run re-registers from each Node's load. */
    fun clear() {
        byDimension.clear()
        resumeAt.clear()
        orderCache.clear()
        orderDirty.clear()
        dueAt.clear()
        lastRun.clear()
    }
}
