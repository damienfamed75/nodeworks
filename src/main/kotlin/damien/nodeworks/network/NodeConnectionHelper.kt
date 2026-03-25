package damien.nodeworks.network

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionHelper {

    const val MAX_DISTANCE = 8.0
    private const val MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE

    /**
     * Per-dimension spatial index: dimension -> (chunk column key -> set of node positions).
     * Keeps dimensions fully isolated — a block change in the overworld never touches nether nodes.
     */
    private val nodesByDimension = ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, MutableSet<BlockPos>>>()

    private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    private fun chunkKeyOf(pos: BlockPos): Long = chunkKey(pos.x shr 4, pos.z shr 4)

    private fun chunkIndex(level: ServerLevel): ConcurrentHashMap<Long, MutableSet<BlockPos>> {
        return nodesByDimension.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }
    }

    fun trackNode(level: ServerLevel, pos: BlockPos) {
        chunkIndex(level).computeIfAbsent(chunkKeyOf(pos)) { ConcurrentHashMap.newKeySet() }.add(pos)
    }

    fun untrackNode(level: ServerLevel, pos: BlockPos) {
        val chunks = nodesByDimension[level.dimension()] ?: return
        val key = chunkKeyOf(pos)
        chunks.computeIfPresent(key) { _, set ->
            set.remove(pos)
            if (set.isEmpty()) null else set
        }
    }

    // --- Validation ---

    fun isWithinRange(posA: BlockPos, posB: BlockPos): Boolean {
        return posA.center.distanceToSqr(posB.center) <= MAX_DISTANCE_SQ
    }

    fun checkLineOfSight(level: Level, posA: BlockPos, posB: BlockPos): Boolean {
        val from = posA.center
        val to = posB.center
        val direction = to.subtract(from).normalize()
        // Offset past the node collision shapes (nodes are 0.375 blocks wide from center)
        // so the ray only checks blocks *between* the two nodes
        val offsetFrom = from.add(direction.scale(0.4))
        val offsetTo = to.subtract(direction.scale(0.4))
        // VISUAL uses the occlusion shape — empty for transparent blocks (glass, ice, etc.)
        // so lasers pass through them, but solid for opaque blocks
        val result = level.clip(
            ClipContext(offsetFrom, offsetTo, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())
        )
        return result.type == HitResult.Type.MISS
    }

    fun getNodeEntity(level: Level, pos: BlockPos): NodeBlockEntity? {
        if (level.getBlockState(pos).block !is NodeBlock) return null
        return level.getBlockEntity(pos) as? NodeBlockEntity
    }

    // --- Connection operations ---

    fun toggleConnection(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getNodeEntity(level, posA) ?: return false
        return if (entityA.hasConnection(posB)) {
            disconnect(level, posA, posB)
            false
        } else {
            connect(level, posA, posB)
            true
        }
    }

    fun connect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getNodeEntity(level, posA) ?: return false
        val entityB = getNodeEntity(level, posB) ?: return false
        entityA.addConnection(posB)
        entityB.addConnection(posA)
        return true
    }

    fun disconnect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getNodeEntity(level, posA)
        val entityB = getNodeEntity(level, posB)
        entityA?.removeConnection(posB)
        entityB?.removeConnection(posA)
        return entityA != null || entityB != null
    }

    /**
     * Removes all connections from the given entity. Called during block entity removal
     * when the block state may already be air.
     */
    fun removeAllConnectionsOf(level: ServerLevel, entity: NodeBlockEntity) {
        val pos = entity.blockPos
        val neighbors = entity.getConnections()
        for (neighborPos in neighbors) {
            getNodeEntity(level, neighborPos)?.removeConnection(pos)
        }
        for (neighborPos in neighbors) {
            entity.removeConnection(neighborPos)
        }
    }

    // --- Block change handling ---

    /**
     * Called when a block changes at [changedPos]. Uses the per-dimension spatial index
     * to find only nearby nodes in the same dimension, then checks only connections whose
     * bounding box contains the changed position.
     */
    fun onBlockChanged(level: ServerLevel, changedPos: BlockPos) {
        val chunks = nodesByDimension[level.dimension()] ?: return

        val cx = changedPos.x shr 4
        val cz = changedPos.z shr 4

        // Check 3x3 chunk columns around the changed block.
        // MAX_DISTANCE=8 < 16 (chunk width), so 1 chunk radius is sufficient.
        for (dx in -1..1) {
            for (dz in -1..1) {
                val nodes = chunks[chunkKey(cx + dx, cz + dz)] ?: continue
                for (nodePos in nodes) {
                    checkNodeConnections(level, nodePos, changedPos)
                }
            }
        }
    }

    /**
     * For a single node, checks if any of its connections are affected by a block
     * change at [changedPos], and disconnects only those that lost line-of-sight.
     */
    private fun checkNodeConnections(level: ServerLevel, nodePos: BlockPos, changedPos: BlockPos) {
        val entity = getNodeEntity(level, nodePos) ?: return
        val connections = entity.getConnections()
        if (connections.isEmpty()) return

        for (targetPos in connections) {
            // Only check from the "lesser" side to avoid double-raycasting A↔B
            if (!isLessThan(nodePos, targetPos)) continue
            // Cheap AABB test: is the changed block within the connection's bounding box?
            if (!isInsideConnectionBounds(nodePos, targetPos, changedPos)) continue
            // Expensive: raycast to verify LOS is actually broken
            if (!checkLineOfSight(level, nodePos, targetPos)) {
                disconnect(level, nodePos, targetPos)
            }
        }
    }

    /**
     * Returns true if [point] is inside the axis-aligned bounding box between [a] and [b],
     * expanded by 1 block margin to account for block size.
     */
    private fun isInsideConnectionBounds(a: BlockPos, b: BlockPos, point: BlockPos): Boolean {
        return point.x in (minOf(a.x, b.x) - 1)..(maxOf(a.x, b.x) + 1)
            && point.y in (minOf(a.y, b.y) - 1)..(maxOf(a.y, b.y) + 1)
            && point.z in (minOf(a.z, b.z) - 1)..(maxOf(a.z, b.z) + 1)
    }

    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }
}
