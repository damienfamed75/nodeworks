package damien.nodeworks.network

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionHelper {

    /**
     * Per-tick set of positions already covered by a [propagateNetworkId] BFS. If many
     * blocks change inside the same subgraph in one tick we only need to walk it once.
     * Cleared by [clearTickDedup] at the end of every server tick.
     */
    private val propagatedThisTickByDim = ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>>()

    private fun propagatedThisTick(level: ServerLevel): MutableSet<Long> =
        propagatedThisTickByDim.computeIfAbsent(level.dimension()) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap())
        }

    /** Reset per-tick propagate dedup. Call once per server tick (Post). */
    fun clearTickDedup() {
        propagatedThisTickByDim.clear()
    }

    /** Drop cached state. Call on server shutdown. */
    fun clearServerCaches() {
        propagatedThisTickByDim.clear()
        pendingRevalidateByDim.clear()
    }

    /**
     * Queue of connectable positions waiting to be revalidated on the next server tick.
     * Populated from each Connectable's `setLevel` (chunk load). Deferred by one tick so the
     * chunk has finished registering the BE before we walk its connection graph, doing
     * it inline from setLevel recurses back into `level.getBlockEntity` for the still-being-
     * wired BE and blows the stack.
     *
     * Drained by [drainPendingRevalidations], called once per server tick. One-shot cost per
     * chunk load, zero cost on idle ticks.
     */
    private val pendingRevalidateByDim = ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>>()

    fun queueRevalidation(level: ServerLevel, pos: BlockPos) {
        pendingRevalidateByDim
            .computeIfAbsent(level.dimension()) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }
            .add(pos.asLong())
    }

    /** Spread large revalidation bursts (player teleport, render-distance jump)
     *  across ticks so one chunk-load wave can't stall a single tick. */
    private const val MAX_REVALIDATIONS_PER_TICK = 64

    fun drainPendingRevalidations(server: net.minecraft.server.MinecraftServer) {
        for (level in server.allLevels) {
            val pending = pendingRevalidateByDim[level.dimension()] ?: continue
            if (pending.isEmpty()) continue
            // Snapshot so we can clear and let setLevel calls that happen DURING processing
            // accumulate into the next-tick batch rather than mutate the set we're iterating.
            val snapshot = pending.toLongArray()
            pending.clear()
            var processed = 0
            for ((index, packed) in snapshot.withIndex()) {
                if (processed >= MAX_REVALIDATIONS_PER_TICK) {
                    for (i in index until snapshot.size) pending.add(snapshot[i])
                    break
                }
                val pos = BlockPos.of(packed)
                if (!level.isLoaded(pos)) continue
                val entity = getConnectable(level, pos) ?: continue
                revalidateOnLoad(level, entity)
                processed++
            }
        }
    }

    /** Legacy no-op kept so existing BE call sites compile. The chunk-keyed
     *  index this used to populate was only consumed by the LOS revalidation
     *  system, which is gone. Safe to delete the call sites in a follow-up. */
    fun trackNode(@Suppress("UNUSED_PARAMETER") level: ServerLevel, @Suppress("UNUSED_PARAMETER") pos: BlockPos) {}

    /** Legacy no-op, see [trackNode]. */
    fun untrackNode(@Suppress("UNUSED_PARAMETER") level: ServerLevel, @Suppress("UNUSED_PARAMETER") pos: BlockPos) {}

    /** Get a Connectable block entity at the given position. Returns null if the
     *  chunk isn't loaded or the block entity at [pos] doesn't implement
     *  [Connectable]. The interface cast is the single source of truth for
     *  "is this a network device?", adding a new device type just means
     *  implementing [Connectable], no allowlist to update. */
    fun getConnectable(level: Level, pos: BlockPos): Connectable? {
        if (!level.isLoaded(pos)) return null
        return level.getBlockEntity(pos) as? Connectable
    }

    /** Get a NodeBlockEntity specifically (for legacy code that needs node-specific access). */
    fun getNodeEntity(level: Level, pos: BlockPos): NodeBlockEntity? {
        if (!level.isLoaded(pos)) return null
        if (level.getBlockState(pos).block !is NodeBlock) return null
        return level.getBlockEntity(pos) as? NodeBlockEntity
    }

    // --- Connection operations ---
    //
    // The connect / disconnect / toggleConnection trio is kept here for the
    // transitional period while the wrench still has a wire-up flow on
    // non-Node Connectables. Phase 5 of the pipe refactor replaces the wrench
    // with a face-toggle and these can be deleted.

    fun toggleConnection(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA) ?: return false
        return if (entityA.hasConnection(posB)) {
            disconnect(level, posA, posB)
            false
        } else {
            connect(level, posA, posB)
        }
    }

    fun connect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA) ?: return false
        val entityB = getConnectable(level, posB) ?: return false

        // Refuse the connect if both sides' topology already reaches different
        // controllers (would merge two controllers into one subgraph).
        val topoA = findTopologyController(level, posA)
        val topoB = findTopologyController(level, posB)
        if (topoA != null && topoB != null && topoA != topoB) return false

        entityA.addConnection(posB)
        entityB.addConnection(posA)
        propagateNetworkId(level, posA)
        return true
    }

    fun disconnect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA)
        val entityB = getConnectable(level, posB)
        entityA?.removeConnection(posB)
        entityB?.removeConnection(posA)
        if (entityA != null) propagateNetworkId(level, posA)
        if (entityB != null) propagateNetworkId(level, posB)
        return entityA != null || entityB != null
    }

    /**
     * Walk the full connection graph from [startPos] and return the first controller's
     * networkId found, or null. Used to enforce "one network per connectable" so a wrench
     * link can't merge two controllers into one subgraph.
     */
    fun findTopologyController(level: ServerLevel, startPos: BlockPos): java.util.UUID? {
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        visited.add(startPos)
        queue.add(startPos)
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = getConnectable(level, pos) ?: continue
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) {
                // Stable identity, the transient networkId may be null mid-conflict.
                return entity.permanentId
            }
            for (conn in entity.getConnections()) {
                if (!level.isLoaded(conn)) continue
                if (visited.add(conn)) queue.add(conn)
            }
            for (adjacentPos in adjacentConnectableNeighbors(level, pos, entity)) {
                if (visited.add(adjacentPos)) queue.add(adjacentPos)
            }
        }
        return null
    }

    /** BFS from a position to find a controller and propagate its networkId to all reachable
     *  connectables. With lasers gone, the graph is just `getConnections()` (legacy wrench
     *  links on non-Node Connectables) plus face-adjacency. No LOS gating required.
     *
     *  Per-tick dedup via [propagatedThisTickByDim]: a second call in the same tick whose
     *  startPos was already covered by a prior propagate's BFS is a no-op. Keeps cost
     *  bounded when many blocks change near a large network in a single tick. */
    fun propagateNetworkId(level: ServerLevel, startPos: BlockPos) {
        val coveredThisTick = propagatedThisTick(level)
        if (!coveredThisTick.add(startPos.asLong())) return

        val visited = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        visited.add(startPos)
        queue.add(startPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = getConnectable(level, pos) ?: continue
            for (conn in entity.getConnections()) {
                if (!level.isLoaded(conn)) continue
                if (visited.add(conn)) queue.add(conn)
            }
            // Face-adjacent Connectables join the network without an explicit link.
            for (adjacentPos in adjacentConnectableNeighbors(level, pos, entity)) {
                if (visited.add(adjacentPos)) queue.add(adjacentPos)
            }
        }

        for (p in visited) coveredThisTick.add(p.asLong())

        // Two+ controllers in one subgraph is a conflict (e.g. [Controller][Device][Controller]
        // wired together via adjacency or a wrench bridge). Assigns null so every block goes
        // grey and downstream operations refuse to run, rather than latching onto an arbitrary
        // controller. Reads permanentId so a controller currently null-mid-conflict still
        // contributes the right id when the conflict resolves.
        var foundId: java.util.UUID? = null
        var controllerCount = 0
        for (pos in visited) {
            val entity = getConnectable(level, pos)
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) {
                controllerCount++
                if (controllerCount == 1) foundId = entity.permanentId
                else { foundId = null; break }
            }
        }

        // UPDATE_ALL matches the pattern other Connectable BE setters use, the BE NBT sync
        // piggybacks on the chunk-broadcast pass after setChanged.
        for (pos in visited) {
            val entity = getConnectable(level, pos) ?: continue
            if (entity.networkId != foundId) {
                entity.networkId = foundId
                val be = entity as? net.minecraft.world.level.block.entity.BlockEntity
                if (be != null) {
                    be.setChanged()
                    level.sendBlockUpdated(pos, be.blockState, be.blockState, net.minecraft.world.level.block.Block.UPDATE_ALL)
                }
            }
        }
    }

    /** Face-adjacent Connectable BEs. Both endpoints must opt into adjacency, and both
     *  must accept the pair via [Connectable.canConnectAdjacentTo]. Used by leaves
     *  (import / export chests) to refuse other leaves so two chests don't auto-bridge.
     *  Wrench force-blocks on either side's touching face also break the pair, so
     *  the network propagation matches what the multipart blockstate is rendering. */
    private fun adjacentConnectableNeighbors(level: ServerLevel, pos: BlockPos, entity: Connectable): List<BlockPos> {
        if (!entity.usesAdjacency()) return emptyList()
        val out = ArrayList<BlockPos>(6)
        for (dir in Direction.entries) {
            val neighbor = pos.relative(dir)
            if (!level.isLoaded(neighbor)) continue
            val neighborBe = level.getBlockEntity(neighbor) as? Connectable ?: continue
            if (!neighborBe.usesAdjacency()) continue
            if (!entity.canConnectAdjacentTo(neighborBe)) continue
            if (!neighborBe.canConnectAdjacentTo(entity)) continue
            if (entity.forcedPipeBlocked(dir)) continue
            if (neighborBe.forcedPipeBlocked(dir.opposite)) continue
            out.add(neighbor)
        }
        return out
    }

    /** Re-propagate from this position when a Connectable's chunk loads. Adjacency is the
     *  source of truth now, no LOS reconciliation needed. */
    fun revalidateOnLoad(level: ServerLevel, self: Connectable) {
        propagateNetworkId(level, self.getBlockPos())
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
        // Surviving neighbours may have just lost their path to a controller. Gated on
        // [blockDestroyed] so this only runs on real player destruction, not chunk unload.
        if (entity.blockDestroyed) {
            for (neighborPos in neighbors) {
                propagateNetworkId(level, neighborPos)
            }
            // Also re-propagate from face-adjacent Connectables so a destroyed Node frees
            // its old subgraph correctly.
            for (dir in Direction.entries) {
                val adjPos = pos.relative(dir)
                if (!level.isLoaded(adjPos)) continue
                if (level.getBlockEntity(adjPos) !is Connectable) continue
                propagateNetworkId(level, adjPos)
            }
        }
    }
}
