package damien.nodeworks.network

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionHelper {

    const val MAX_DISTANCE = 8.0
    private const val MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE

    private val nodesByDimension = ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, MutableSet<BlockPos>>>()

    /**
     * Set of connection pairs whose LOS is currently blocked by a block placed between
     * the two endpoints. Keyed by a canonical [pairKey]. Server-only state, the
     * client tracks its own LOS cache inside [damien.nodeworks.render.NodeConnectionRenderer].
     *
     * The connection itself is NOT removed from either endpoint when LOS breaks, this set is
     * consulted by [propagateNetworkId] (to stop the network-id BFS at blocked hops) and by
     * [checkNodeConnections] (to detect LOS transitions on neighbour-block updates).
     *
     * Persisted per dimension via [BlockedPairsData] SavedData so the cache survives reloads
     *, this is what lets propagate trust the cache instead of live-raycasting every edge,
     * keeping BFS cost linear in network size even for thousand-node networks.
     *
     * Cache structure is a two-layer lookup: the SavedData reference for each dimension is
     * kept in [blockedDataCache] to avoid the per-call map lookup inside level.dataStorage.
     */
    private val blockedDataCache = ConcurrentHashMap<ResourceKey<Level>, BlockedPairsData>()

    private fun blockedData(level: ServerLevel): BlockedPairsData =
        blockedDataCache.computeIfAbsent(level.dimension()) {
            level.dataStorage.computeIfAbsent(BlockedPairsData.TYPE)
        }

    private fun blockedPairs(level: ServerLevel): MutableSet<Long> = blockedData(level).pairs

    /** Drop cached SavedData references. Call on server shutdown so a subsequent restart
     *  doesn't keep a handle to the previous run's loaded data. */
    fun clearServerCaches() {
        blockedDataCache.clear()
        propagatedThisTickByDim.clear()
        pendingRevalidateByDim.clear()
        losThisTickByDim.clear()
    }

    fun pairKey(a: BlockPos, b: BlockPos): Long {
        val (lo, hi) = if (isLessThan(a, b)) a to b else b to a
        return lo.asLong() xor (hi.asLong() * 31L)
    }

    /** Whether a connection pair is currently LOS-blocked. Server-side authoritative state. */
    fun isPairBlocked(level: ServerLevel, a: BlockPos, b: BlockPos): Boolean =
        blockedPairs(level).contains(pairKey(a, b))

    private fun setPairBlocked(level: ServerLevel, a: BlockPos, b: BlockPos, blocked: Boolean) {
        val data = blockedData(level)
        val key = pairKey(a, b)
        val changed = if (blocked) data.pairs.add(key) else data.pairs.remove(key)
        if (changed) {
            data.setDirty()
            // Drop dedup for both endpoints, any propagate this tick decided BFS edge
            // crossings against the OLD cache value. Without this, destroying an
            // LOS-blocking block runs setRemoved's propagate first (with the still-blocked
            // cache, dead-ending at the LOS edge), then onBlockChanged → checkNodeConnections
            // clears the pair and re-calls propagate which dedup-skips, leaving the orphan
            // side stuck on null forever.
            propagatedThisTick(level).remove(a.asLong())
            propagatedThisTick(level).remove(b.asLong())
        }
    }

    /**
     * Per-tick set of positions already covered by a [propagateNetworkId] BFS. Propagate
     * visits everyone reachable through clear LOS, if ten blocks change inside the same
     * subgraph in one tick, we only need to BFS that subgraph once. Cleared by
     * [clearTickDedup] at the end of every server tick.
     */
    private val propagatedThisTickByDim = ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>>()

    private fun propagatedThisTick(level: ServerLevel): MutableSet<Long> =
        propagatedThisTickByDim.computeIfAbsent(level.dimension()) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap())
        }

    /** Per-tick LOS raycast cache. Bursts of block changes against the same
     *  edges (quarry beside lasers, farm clearing) collapse to one raycast. */
    private val losThisTickByDim = ConcurrentHashMap<ResourceKey<Level>, MutableMap<Long, Boolean>>()

    private fun losThisTick(level: ServerLevel): MutableMap<Long, Boolean> =
        losThisTickByDim.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }

    private fun checkLineOfSightCached(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val cache = losThisTick(level)
        val key = pairKey(posA, posB)
        cache[key]?.let { return it }
        val result = checkLineOfSight(level, posA, posB)
        cache[key] = result
        return result
    }

    /** Reset per-tick propagate dedup + LOS cache. Call once per server tick (Post). */
    fun clearTickDedup() {
        propagatedThisTickByDim.clear()
        losThisTickByDim.clear()
    }

    /**
     * Queue of connectable positions waiting to be revalidated on the next server tick.
     * Populated from each Connectable's `setLevel` (chunk load). Deferred by one tick so the
     * chunk has finished registering the BE before we try to walk its connection graph, doing
     * it in-line from setLevel recurses back into `level.getBlockEntity` for the still-being-
     * -wired BE and blows the stack.
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
        // Adjacent blocks can always see each other, skip raycast
        val dx = Math.abs(posA.x - posB.x)
        val dy = Math.abs(posA.y - posB.y)
        val dz = Math.abs(posA.z - posB.z)
        if (dx <= 1 && dy <= 1 && dz <= 1) return true

        val from = posA.center
        val to = posB.center
        val direction = to.subtract(from).normalize()
        // Offset must clear full-block shapes (0.5 from center), use 0.87 for diagonal safety
        val offsetFrom = from.add(direction.scale(0.87))
        val offsetTo = to.subtract(direction.scale(0.87))
        val result = level.clip(
            ClipContext(offsetFrom, offsetTo, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())
        )
        return result.type == HitResult.Type.MISS
    }

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

        // Refuse the connect if both sides' structural topology (ignoring LOS) already
        // reaches different controllers. Without this, a wrench can bridge two networks
        // through an LOS-blocked orphan: the orphan appears disconnected (networkId=null,
        // NetworkDiscovery's live LOS walk doesn't find its old controller), but its
        // connection to the old subgraph is still on the books, so restoring LOS later
        // would merge two controllers onto a single connectable.
        val topoA = findTopologyController(level, posA)
        val topoB = findTopologyController(level, posB)
        if (topoA != null && topoB != null && topoA != topoB) return false

        entityA.addConnection(posB)
        entityB.addConnection(posA)
        propagateNetworkId(level, posA)
        return true
    }

    /**
     * Walk the full connection graph from [startPos] ignoring [blockedPairs] and return the first
     * controller's networkId found, or null. Used to enforce "one network per connectable" across
     * soft-disconnected (LOS-blocked) subgraphs, the connection data is what we care about here,
     * not current reachability.
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

    /** BFS from a position to find a controller and propagate its networkId to all reachable connectables.
     *  Trusts the persisted [blockedPairs] cache, no per-edge raycast during the BFS. Cache freshness
     *  is maintained elsewhere: [checkNodeConnections] writes on live block-change transitions and
     *  [revalidateOnLoad] writes when a connectable's chunk loads. Net effect is an O(V+E) traversal
     *  with no raycasts, so propagating across a thousand-node network on a wrench click is cheap.
     *
     *  Per-tick dedup via [propagatedThisTickByDim]: a second call in the same tick whose startPos was
     *  already covered by a prior propagate's BFS is a no-op, the network was walked already. This
     *  keeps cost bounded when many blocks change near a large network in a single tick. */
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
                if (isPairBlocked(level, pos, conn)) continue
                if (visited.add(conn)) queue.add(conn)
            }
            // Face-adjacent Connectables join the network without a laser between them.
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

    fun disconnect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA)
        val entityB = getConnectable(level, posB)
        entityA?.removeConnection(posB)
        entityB?.removeConnection(posA)
        // A hard disconnect severs the pair for good, purge any stale blocked-state entry so
        // a later `connect` on the same endpoints starts fresh (live LOS check will re-populate it).
        // Routed through [setPairBlocked] for the SavedData dirty flag, otherwise the removal
        // wouldn't survive a save/restart and the stale entry would reappear from disk.
        setPairBlocked(level, posA, posB, false)
        // Re-propagate networkId for both sides (one side may have lost its controller)
        if (entityA != null) propagateNetworkId(level, posA)
        if (entityB != null) propagateNetworkId(level, posB)
        return entityA != null || entityB != null
    }

    /** Face-adjacent Connectable BEs. Both endpoints must opt into adjacency, so a
     *  Node next to a Controller doesn't silently bridge two networks through it. */
    private fun adjacentConnectableNeighbors(level: ServerLevel, pos: BlockPos, entity: Connectable): List<BlockPos> {
        if (!entity.usesAdjacency()) return emptyList()
        val out = ArrayList<BlockPos>(6)
        for (dir in Direction.entries) {
            val neighbor = pos.relative(dir)
            if (!level.isLoaded(neighbor)) continue
            val neighborBe = level.getBlockEntity(neighbor) as? Connectable ?: continue
            if (!neighborBe.usesAdjacency()) continue
            out.add(neighbor)
        }
        return out
    }

    /** Re-raycast every connection of [self] whose opposite endpoint is already loaded
     *  and reconcile the [blockedPairs] cache. Catches the edge case where a block was
     *  placed between two endpoints while one of them was in an unloaded chunk, the mixin
     *  couldn't reach the orphan to write the cache entry.
     *
     *  Runs from each [Connectable]'s `setLevel` after the chunk registered the BE. Takes
     *  the entity directly because looking it up via level.getBlockEntity mid-setLevel
     *  would recurse into setLevel and stack-overflow. */
    fun revalidateOnLoad(level: ServerLevel, self: Connectable) {
        val pos = self.getBlockPos()
        val connections = self.getConnections()
        // Every Connectable can pick up adjacency-based connectivity not captured in
        // [connections], so always propagate on load.
        if (connections.isEmpty()) {
            propagateNetworkId(level, pos)
            return
        }

        for (targetPos in connections) {
            if (!level.isLoaded(targetPos)) continue
            if (!isLessThan(pos, targetPos)) continue  // canonical side handles each pair once

            val wasBlocked = isPairBlocked(level, pos, targetPos)
            val hasLos = checkLineOfSightCached(level, pos, targetPos)
            if (hasLos == wasBlocked) {
                setPairBlocked(level, pos, targetPos, !hasLos)
            }
        }
        propagateNetworkId(level, pos)
    }

    fun removeAllConnections(level: ServerLevel, entity: Connectable) {
        val pos = entity.getBlockPos()
        val neighbors = entity.getConnections().toList()
        for (neighborPos in neighbors) {
            getConnectable(level, neighborPos)?.removeConnection(pos)
            // Routed through [setPairBlocked] so the SavedData dirty flag flips, a direct
            // set-mutation here was leaving stale blocked entries that came back from disk
            // on restart referencing this destroyed pos.
            setPairBlocked(level, pos, neighborPos, false)
        }
        for (neighborPos in neighbors) {
            entity.removeConnection(neighborPos)
        }
        // Surviving neighbours may have just lost their path to a controller, e.g. destroying
        // a middle node N2 in C→N1→N2→N3→N4 leaves N3↔N4 intact but no longer reachable from C.
        // Without this propagate, that orphan subgraph keeps its stale networkId and renders as
        // if it were still on the network.
        //
        // Gated on [blockDestroyed] so this ONLY runs on real player destruction, not on chunk
        // unload (setRemoved fires for both). During world save the chain
        // propagate → setChanged → sendBlockUpdated against entities mid-unload will freeze
        // the save and may corrupt in-memory connections before they're persisted. Per-tick
        // dedup inside propagateNetworkId keeps the multi-neighbour case cheap.
        if (entity.blockDestroyed) {
            for (neighborPos in neighbors) {
                propagateNetworkId(level, neighborPos)
            }
            // Re-propagate from every face-neighbour Connectable. Catches both
            // adjacency-using neighbours that just lost a path to a controller and
            // non-adjacency neighbours (Nodes) holding a stale networkId from a
            // prior walk that did include them.
            for (dir in Direction.entries) {
                val adjPos = pos.relative(dir)
                if (!level.isLoaded(adjPos)) continue
                if (level.getBlockEntity(adjPos) !is Connectable) continue
                propagateNetworkId(level, adjPos)
            }
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

            // Primary transition: cached blocked flag disagrees with live LOS, either a new
            // obstruction was placed or an existing one was removed.
            val wasBlocked = isPairBlocked(level, nodePos, targetPos)
            val hasLos = checkLineOfSightCached(level, nodePos, targetPos)
            val flipped = hasLos == wasBlocked

            // Stale-cache heal: on server load the blocked-set starts empty, so a restored-LOS
            // event can look like "no change" (wasBlocked=false, hasLos=true). Detect that case
            // by cross-checking the endpoints' networkIds: if they can see each other with clear
            // LOS but don't agree on which network they're in, the cache is lying and a propagate
            // is needed to reconcile them. Cheap, one field read per endpoint, no raycast.
            val targetEntity = getConnectable(level, targetPos)
            val inconsistent = hasLos && targetEntity != null && entity.networkId != targetEntity.networkId

            if (!flipped && !inconsistent) continue

            // LOS just broke and the obstruction itself is a fresh splicer Connectable?
            // Rewire A↔B → A↔C↔B instead of marking the pair blocked.
            if (!wasBlocked && !hasLos && trySplice(level, nodePos, targetPos, changedPos)) {
                continue
            }

            setPairBlocked(level, nodePos, targetPos, !hasLos)
            propagateNetworkId(level, nodePos)
            // If LOS just broke, the opposite endpoint may have just lost its controller, run
            // a second propagate from that side since the BFS from `nodePos` won't reach it.
            // (If `inconsistent` drove us here with clear LOS, one propagate already covered both.)
            if (!hasLos) propagateNetworkId(level, targetPos)
        }
    }

    /** Rewire A↔B into A↔C↔B when [splicerPos] holds a freshly placed splicer that
     *  blocks the laser. Splicer must opt in via [Connectable.autoSpliceOnPlace] and
     *  have no prior connections, otherwise a piston-pushed or /setblock-restored
     *  Connectable would get hijacked. Mutates connections atomically and propagates
     *  once at the end, going through [connect]/[disconnect] would interleave three
     *  propagates and cover the splicer pos in [propagatedThisTickByDim] before the
     *  final graph was settled. */
    private fun trySplice(level: ServerLevel, posA: BlockPos, posB: BlockPos, splicerPos: BlockPos): Boolean {
        val splicer = getConnectable(level, splicerPos) ?: return false
        if (!splicer.autoSpliceOnPlace()) return false
        if (splicer.getConnections().isNotEmpty()) return false
        if (!isWithinRange(posA, splicerPos)) return false
        if (!isWithinRange(splicerPos, posB)) return false
        if (!checkLineOfSightCached(level, posA, splicerPos)) return false
        if (!checkLineOfSightCached(level, splicerPos, posB)) return false
        val entityA = getConnectable(level, posA) ?: return false
        val entityB = getConnectable(level, posB) ?: return false

        entityA.addConnection(splicerPos)
        splicer.addConnection(posA)
        entityB.addConnection(splicerPos)
        splicer.addConnection(posB)
        entityA.removeConnection(posB)
        entityB.removeConnection(posA)
        // Routed through [setPairBlocked] for the SavedData dirty flag, otherwise the
        // stale A↔B blocked entry comes back from disk on restart and a future reconnect
        // of those same endpoints would BFS-skip the edge as if still LOS-blocked.
        setPairBlocked(level, posA, posB, false)

        propagateNetworkId(level, splicerPos)
        return true
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
