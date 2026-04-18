package damien.nodeworks.network

import damien.nodeworks.block.NetworkControllerBlock
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.block.VariableBlock
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

    /**
     * Set of connection pairs whose LOS is currently blocked by a block placed between
     * the two endpoints. Keyed by a canonical [pairKey]. Server-only state — the
     * client tracks its own LOS cache inside [damien.nodeworks.render.NodeConnectionRenderer].
     *
     * The connection itself is NOT removed from either endpoint when LOS breaks; this set is
     * consulted by [propagateNetworkId] (to stop the network-id BFS at blocked hops) and by
     * [checkNodeConnections] (to detect LOS-clear transitions on neighbour-block updates).
     * Soft-disconnect semantics — once the obstruction is removed the pair resumes
     * carrying the network. Not persisted; see rebuild note in [onServerLevelLoaded].
     */
    private val blockedPairsByDimension = ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>>()

    private fun blockedPairs(level: ServerLevel): MutableSet<Long> =
        blockedPairsByDimension.computeIfAbsent(level.dimension()) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap())
        }

    private fun pairKey(a: BlockPos, b: BlockPos): Long {
        val (lo, hi) = if (isLessThan(a, b)) a to b else b to a
        return lo.asLong() xor (hi.asLong() * 31L)
    }

    /** Whether a connection pair is currently LOS-blocked. Server-side authoritative state. */
    fun isPairBlocked(level: ServerLevel, a: BlockPos, b: BlockPos): Boolean =
        blockedPairs(level).contains(pairKey(a, b))

    private fun setPairBlocked(level: ServerLevel, a: BlockPos, b: BlockPos, blocked: Boolean) {
        val set = blockedPairs(level)
        val key = pairKey(a, b)
        if (blocked) set.add(key) else set.remove(key)
    }

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
        // Adjacent blocks can always see each other — skip raycast
        val dx = Math.abs(posA.x - posB.x)
        val dy = Math.abs(posA.y - posB.y)
        val dz = Math.abs(posA.z - posB.z)
        if (dx <= 1 && dy <= 1 && dz <= 1) return true

        val from = posA.center
        val to = posB.center
        val direction = to.subtract(from).normalize()
        // Offset must clear full-block shapes (0.5 from center) — use 0.87 for diagonal safety
        val offsetFrom = from.add(direction.scale(0.87))
        val offsetTo = to.subtract(direction.scale(0.87))
        val result = level.clip(
            ClipContext(offsetFrom, offsetTo, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())
        )
        return result.type == HitResult.Type.MISS
    }

    /** Get a Connectable block entity at the given position. Returns null if chunk is not loaded. */
    fun getConnectable(level: Level, pos: BlockPos): Connectable? {
        if (!level.isLoaded(pos)) return null
        val block = level.getBlockState(pos).block
        if (block !is NodeBlock && block !is NetworkControllerBlock && block !is VariableBlock && block !is TerminalBlock && block !is damien.nodeworks.block.CraftingCoreBlock && block !is damien.nodeworks.block.InstructionStorageBlock && block !is damien.nodeworks.block.ProcessingStorageBlock && block !is damien.nodeworks.block.ReceiverAntennaBlock && block !is damien.nodeworks.block.InventoryTerminalBlock) return null
        return level.getBlockEntity(pos) as? Connectable
    }

    /** Get a NodeBlockEntity specifically (for legacy code that needs node-specific access). */
    fun getNodeEntity(level: Level, pos: BlockPos): NodeBlockEntity? {
        if (!level.isLoaded(pos)) return null
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
        // Propagate network UUID across the newly connected network
        propagateNetworkId(level, posA)
        return true
    }

    /** BFS from a position to find a controller and propagate its networkId to all reachable connectables.
     *  Each edge is re-raycast and its entry in [blockedPairs] refreshed as we visit it — so propagate is
     *  self-healing: whatever the cached blocked state was before this call, it is correct after. That
     *  lets us avoid persisting the set across saves (it rebuilds on the first propagate after load). */
    fun propagateNetworkId(level: ServerLevel, startPos: BlockPos) {
        // Full BFS through unblocked pairs. LOS is re-verified live per edge.
        val visited = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        visited.add(startPos)
        queue.add(startPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = getConnectable(level, pos) ?: continue
            for (conn in entity.getConnections()) {
                if (!level.isLoaded(conn)) continue
                val blocked = !checkLineOfSight(level, pos, conn)
                setPairBlocked(level, pos, conn, blocked)
                if (blocked) continue
                if (visited.add(conn)) queue.add(conn)
            }
        }

        // Find controller in the visited set
        var foundId: java.util.UUID? = null
        for (pos in visited) {
            val entity = getConnectable(level, pos)
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) {
                foundId = entity.networkId
                break
            }
        }

        // Update all visited nodes and sync to client
        for (pos in visited) {
            val entity = getConnectable(level, pos) ?: continue
            if (entity.networkId != foundId) {
                entity.networkId = foundId
                val be = entity as? net.minecraft.world.level.block.entity.BlockEntity
                if (be != null) {
                    be.setChanged()
                    level.sendBlockUpdated(pos, be.blockState, be.blockState, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
                }
            }
        }
    }

    fun disconnect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA)
        val entityB = getConnectable(level, posB)
        entityA?.removeConnection(posB)
        entityB?.removeConnection(posA)
        // A hard disconnect severs the pair for good — purge any stale blocked-state entry so
        // a later `connect` on the same endpoints starts fresh (live LOS check will re-populate it).
        blockedPairs(level).remove(pairKey(posA, posB))
        // Re-propagate networkId for both sides (one side may have lost its controller)
        if (entityA != null) propagateNetworkId(level, posA)
        if (entityB != null) propagateNetworkId(level, posB)
        return entityA != null || entityB != null
    }

    fun removeAllConnections(level: ServerLevel, entity: Connectable) {
        val pos = entity.getBlockPos()
        val neighbors = entity.getConnections().toList()
        val blocked = blockedPairs(level)
        for (neighborPos in neighbors) {
            getConnectable(level, neighborPos)?.removeConnection(pos)
            blocked.remove(pairKey(pos, neighborPos))
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

            // Soft-transition: compare live LOS against the tracked blocked state. If the state
            // actually flipped — either direction — update it and re-propagate. Placement that
            // breaks LOS orphans the downstream subgraph; removal that restores LOS rejoins it,
            // without ever mutating the connection list itself.
            val wasBlocked = isPairBlocked(level, nodePos, targetPos)
            val hasLos = checkLineOfSight(level, nodePos, targetPos)
            val flipped = hasLos == wasBlocked
            if (!flipped) continue

            setPairBlocked(level, nodePos, targetPos, !hasLos)
            propagateNetworkId(level, nodePos)
            // If LOS just broke, the opposite endpoint may have just lost its controller; run
            // a second propagate from that side since the BFS from `nodePos` won't reach it.
            if (!hasLos) propagateNetworkId(level, targetPos)
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
