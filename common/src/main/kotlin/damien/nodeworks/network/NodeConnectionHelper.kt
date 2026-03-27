package damien.nodeworks.network

import damien.nodeworks.block.InstructionCrafterBlock
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
        val offsetFrom = from.add(direction.scale(0.4))
        val offsetTo = to.subtract(direction.scale(0.4))
        val result = level.clip(
            ClipContext(offsetFrom, offsetTo, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())
        )
        return result.type == HitResult.Type.MISS
    }

    /** Get a Connectable block entity (Node or Instruction Crafter) at the given position. */
    fun getConnectable(level: Level, pos: BlockPos): Connectable? {
        val block = level.getBlockState(pos).block
        if (block !is NodeBlock && block !is InstructionCrafterBlock) return null
        return level.getBlockEntity(pos) as? Connectable
    }

    /** Get a NodeBlockEntity specifically (for legacy code that needs node-specific access). */
    fun getNodeEntity(level: Level, pos: BlockPos): NodeBlockEntity? {
        if (level.getBlockState(pos).block !is NodeBlock) return null
        return level.getBlockEntity(pos) as? NodeBlockEntity
    }

    // --- Connection operations ---

    fun toggleConnection(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA) ?: return false
        return if (entityA.hasConnection(posB)) {
            disconnect(level, posA, posB)
            false
        } else {
            connect(level, posA, posB)
            true
        }
    }

    fun connect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA) ?: return false
        val entityB = getConnectable(level, posB) ?: return false
        entityA.addConnection(posB)
        entityB.addConnection(posA)
        return true
    }

    fun disconnect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA)
        val entityB = getConnectable(level, posB)
        entityA?.removeConnection(posB)
        entityB?.removeConnection(posA)
        return entityA != null || entityB != null
    }

    fun removeAllConnections(level: ServerLevel, entity: Connectable) {
        val pos = entity.getBlockPos()
        val neighbors = entity.getConnections().toList()
        for (neighborPos in neighbors) {
            getConnectable(level, neighborPos)?.removeConnection(pos)
        }
        for (neighborPos in neighbors) {
            entity.removeConnection(neighborPos)
        }
    }

    // --- Block change handling ---

    fun onBlockChanged(level: ServerLevel, changedPos: BlockPos) {
        val chunks = nodesByDimension[level.dimension()] ?: return

        val cx = changedPos.x shr 4
        val cz = changedPos.z shr 4

        for (dx in -1..1) {
            for (dz in -1..1) {
                val nodes = chunks[chunkKey(cx + dx, cz + dz)] ?: continue
                for (nodePos in nodes) {
                    checkNodeConnections(level, nodePos, changedPos)
                }
            }
        }
    }

    private fun checkNodeConnections(level: ServerLevel, nodePos: BlockPos, changedPos: BlockPos) {
        val entity = getConnectable(level, nodePos) ?: return
        val connections = entity.getConnections()
        if (connections.isEmpty()) return

        for (targetPos in connections) {
            if (!isLessThan(nodePos, targetPos)) continue
            if (!isInsideConnectionBounds(nodePos, targetPos, changedPos)) continue
            if (!checkLineOfSight(level, nodePos, targetPos)) {
                disconnect(level, nodePos, targetPos)
            }
        }
    }

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
