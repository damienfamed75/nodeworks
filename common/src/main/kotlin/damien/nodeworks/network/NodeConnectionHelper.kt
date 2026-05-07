package damien.nodeworks.network

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionHelper {

    // --- Advanced Node link gating: range + line of sight ---
    //
    // Only Advanced-Node-to-Advanced-Node laser links go through these checks.
    // Pipe / Node face-adjacency stays LOS-free (it's a physical contact, not
    // a line) so the cache stays bounded by explicit wrench-link count, not by
    // total network size.

    /** Persisted blocked-pair set per dimension. Keyed by [pairKey]. Read on
     *  every walk (cached via [losThisTickByDim] for per-tick batching), written
     *  by [setPairBlocked] when a [checkLineOfSight] result flips state.
     *  Survives world reload so we don't have to re-raycast every link to
     *  self-heal on startup. */
    private val blockedDataCache = ConcurrentHashMap<ResourceKey<Level>, BlockedPairsData>()

    private fun blockedData(level: ServerLevel): BlockedPairsData =
        blockedDataCache.computeIfAbsent(level.dimension()) {
            level.dataStorage.computeIfAbsent(BlockedPairsData.TYPE)
        }

    /** Stable, order-independent 64-bit key for a pair of positions. The
     *  lex-lower endpoint goes in the high 32 bits so `pairKey(a, b) ==
     *  pairKey(b, a)`. Only the bottom 16 bits of each axis are used, so
     *  collisions across far-apart pairs are theoretically possible. The
     *  32-block link range cap plus the "both endpoints must be Focus
     *  Nodes" gate makes that practically unreachable. */
    fun pairKey(a: BlockPos, b: BlockPos): Long {
        val (lo, hi) = if (isLessThan(a, b)) a to b else b to a
        val loPacked = ((lo.x.toLong() and 0xFFFF) shl 32) or
            ((lo.y.toLong() and 0xFFFF) shl 16) or
            (lo.z.toLong() and 0xFFFF)
        val hiPacked = ((hi.x.toLong() and 0xFFFF) shl 32) or
            ((hi.y.toLong() and 0xFFFF) shl 16) or
            (hi.z.toLong() and 0xFFFF)
        return loPacked xor (hiPacked.inv())
    }

    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }

    fun isPairBlocked(level: ServerLevel, a: BlockPos, b: BlockPos): Boolean =
        pairKey(a, b) in blockedPairs(level)

    private fun blockedPairs(level: ServerLevel): MutableSet<Long> = blockedData(level).pairs

    private fun setPairBlocked(level: ServerLevel, a: BlockPos, b: BlockPos, blocked: Boolean) {
        val data = blockedData(level)
        val key = pairKey(a, b)
        val changed = if (blocked) data.pairs.add(key) else data.pairs.remove(key)
        if (changed) data.setDirty()
    }

    /** Per-tick LOS result cache. A walk that traverses a link asks `is the
     *  blocked-pair flag still right?` and only re-raycasts on the first ask
     *  per tick per pair. Subsequent walks in the same tick (e.g. propagate
     *  + discovery + a script's network walk) read the cached result so the
     *  raycast cost is paid once per pair per tick max. */
    private val losThisTickByDim = ConcurrentHashMap<ResourceKey<Level>, MutableMap<Long, Boolean>>()

    private fun losThisTick(level: ServerLevel): MutableMap<Long, Boolean> =
        losThisTickByDim.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }

    /** Re-raycast on first call per tick, return cached result thereafter. The
     *  passive recheck path: if a player builds a wall mid-link, a propagate
     *  pass that walks through that link picks up the new blocked state on its
     *  first call this tick and updates the persisted set. No mixin / global
     *  block-change hook required. */
    private fun checkLineOfSightCached(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val cache = losThisTick(level)
        val key = pairKey(posA, posB)
        cache[key]?.let { return it }
        val result = checkLineOfSight(level, posA, posB)
        cache[key] = result
        // Sync the persisted blocked set so a future tick (or a future world
        // load) sees the same answer without paying for the raycast.
        setPairBlocked(level, posA, posB, !result)
        return result
    }

    /** Maximum LOS distance for an Advanced Node link. */
    fun isWithinRange(posA: BlockPos, posB: BlockPos): Boolean =
        posA.center.distanceToSqr(posB.center) <= NetworkRuntimeConfig.FOCUS_NODE_MAX_DISTANCE_SQ

    /** Raycast clear path between two Advanced Node centres. Returns true when
     *  the line is unobstructed. Adjacent blocks (within a 1-step neighbourhood)
     *  short-circuit to true since a raycast over a 1-block line tends to clip
     *  the source/destination block's own collision shape. */
    fun checkLineOfSight(level: Level, posA: BlockPos, posB: BlockPos): Boolean {
        if (!NetworkRuntimeConfig.FOCUS_NODE_LOS_CHECK_ENABLED) return true
        val dx = kotlin.math.abs(posA.x - posB.x)
        val dy = kotlin.math.abs(posA.y - posB.y)
        val dz = kotlin.math.abs(posA.z - posB.z)
        if (dx <= 1 && dy <= 1 && dz <= 1) return true
        val from = posA.center
        val to = posB.center
        val direction = to.subtract(from).normalize()
        // Inset both endpoints by ~0.87 so the raycast starts and ends
        // just outside each Focus Node's own shape, otherwise self-collision
        // flips every check to "blocked".
        val offsetFrom = from.add(direction.scale(0.87))
        val offsetTo = to.subtract(direction.scale(0.87))
        val result = level.clip(
            ClipContext(offsetFrom, offsetTo, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())
        )
        return result.type == HitResult.Type.MISS
    }

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
        losThisTickByDim.clear()
    }

    /** Drop cached state. Call on server shutdown. */
    fun clearServerCaches() {
        propagatedThisTickByDim.clear()
        pendingRevalidateByDim.clear()
        losThisTickByDim.clear()
        blockedDataCache.clear()
        nodesByDimension.clear()
        WirelessBroadcastRegistry.clear()
        NetworkDiscovery.invalidateAll()
    }

    // --- Per-dimension Connectable chunk index, used by [onBlockChanged] ---
    //
    // Indexed by 16-block chunk key so a block change only re-validates
    // links in the 3×3 chunk neighbourhood of the changed pos. Every
    // Connectable's setLevel calls trackNode, but checkNodeConnections
    // short-circuits on empty getConnections so only Focus Nodes pay the
    // recheck cost.

    private val nodesByDimension = ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, MutableSet<BlockPos>>>()

    private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    private fun chunkKeyOf(pos: BlockPos): Long = chunkKey(pos.x shr 4, pos.z shr 4)

    private fun chunkIndex(level: ServerLevel): ConcurrentHashMap<Long, MutableSet<BlockPos>> {
        return nodesByDimension.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }
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

    /** Add a Connectable to the chunk index so [onBlockChanged] can find it
     *  when a block changes nearby. Called from each Connectable's `setLevel`. */
    fun trackNode(level: ServerLevel, pos: BlockPos) {
        chunkIndex(level).computeIfAbsent(chunkKeyOf(pos)) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap())
        }.add(pos)
    }

    /** Remove a Connectable from the chunk index. The chunk's set is left
     *  in place when empty, cleanup happens lazily on the next [trackNode]. */
    fun untrackNode(level: ServerLevel, pos: BlockPos) {
        val chunks = nodesByDimension[level.dimension()] ?: return
        val key = chunkKeyOf(pos)
        chunks[key]?.remove(pos)
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
    //
    // Used by the wrench's non-shift right-click pair-link flow on Advanced
    // Nodes. Pipe / Node face-adjacency networks form via [propagateNetworkId]
    // alone and never go through [connect].

    /** Outcome of a [connect] attempt. The wrench reads the variant to pick
     *  the right player-facing message, callers that only care about success
     *  can match on `is Connected`. */
    sealed class ConnectResult {
        /** Link established, both endpoints' connection sets updated, network
         *  has been re-propagated. */
        object Connected : ConnectResult()
        /** One or both positions don't currently host a Connectable BE
         *  (chunk unloaded, block destroyed mid-flow, target swapped). */
        object InvalidEndpoint : ConnectResult()
        /** Both endpoints are the same position. */
        object SameEndpoint : ConnectResult()
        /** Already linked, link toggle should call [disconnect] instead. */
        object AlreadyConnected : ConnectResult()
        /** Distance exceeds [NetworkRuntimeConfig.advancedNodeMaxDistance]. */
        object OutOfRange : ConnectResult()
        /** Raycast found a block obstructing the line. */
        object NoLineOfSight : ConnectResult()
        /** Either endpoint already has [NetworkRuntimeConfig.advancedNodeMaxLinks]
         *  links. The wrench surfaces this so the player can disconnect a
         *  spare link first. */
        object MaxLinksReached : ConnectResult()
        /** Linking would merge two networks each running their own controller.
         *  The propagate step would mark the merged subgraph as conflicted
         *  (`networkId = null`) so we refuse pre-emptively. */
        object DuplicateController : ConnectResult()
    }

    fun toggleConnection(level: ServerLevel, posA: BlockPos, posB: BlockPos): ConnectResult {
        val entityA = getConnectable(level, posA) ?: return ConnectResult.InvalidEndpoint
        if (entityA.hasConnection(posB)) {
            disconnect(level, posA, posB)
            return ConnectResult.AlreadyConnected
        }
        return connect(level, posA, posB)
    }

    fun connect(level: ServerLevel, posA: BlockPos, posB: BlockPos): ConnectResult {
        if (posA == posB) return ConnectResult.SameEndpoint
        val entityA = getConnectable(level, posA) ?: return ConnectResult.InvalidEndpoint
        val entityB = getConnectable(level, posB) ?: return ConnectResult.InvalidEndpoint
        if (entityA.hasConnection(posB)) return ConnectResult.AlreadyConnected

        if (!isWithinRange(posA, posB)) return ConnectResult.OutOfRange
        if (!checkLineOfSight(level, posA, posB)) return ConnectResult.NoLineOfSight

        val maxLinks = NetworkRuntimeConfig.FOCUS_NODE_MAX_LINKS
        if (maxLinks > 0) {
            if (entityA.getConnections().size >= maxLinks) return ConnectResult.MaxLinksReached
            if (entityB.getConnections().size >= maxLinks) return ConnectResult.MaxLinksReached
        }

        // Refuse the connect if both sides' topology already reaches different
        // controllers (would merge two controllers into one subgraph).
        val topoA = findTopologyController(level, posA)
        val topoB = findTopologyController(level, posB)
        if (topoA != null && topoB != null && topoA != topoB) return ConnectResult.DuplicateController

        entityA.addConnection(posB)
        entityB.addConnection(posA)
        // Clear any stale blocked-pair entry from a prior LOS-blocked period.
        setPairBlocked(level, posA, posB, false)
        propagateNetworkId(level, posA)
        return ConnectResult.Connected
    }

    fun disconnect(level: ServerLevel, posA: BlockPos, posB: BlockPos): Boolean {
        val entityA = getConnectable(level, posA)
        val entityB = getConnectable(level, posB)
        entityA?.removeConnection(posB)
        entityB?.removeConnection(posA)
        // Disconnection invalidates the persisted blocked-pair record.
        setPairBlocked(level, posA, posB, false)
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
                // Lazy LOS recheck on first walk through this pair per tick.
                // Blocked pairs stay in both BEs' connection sets but skip
                // network propagation here.
                if (!checkLineOfSightCached(level, pos, conn)) continue
                if (visited.add(conn)) queue.add(conn)
            }
            for (adjacentPos in adjacentConnectableNeighbors(level, pos, entity)) {
                if (visited.add(adjacentPos)) queue.add(adjacentPos)
            }
        }
        return null
    }

    /** BFS to find a controller and propagate its networkId to all reachable
     *  Connectables. Walks both `getConnections()` (Focus Node laser links)
     *  and face-adjacency, gated on LOS for the laser side.
     *
     *  Per-tick dedup via [propagatedThisTickByDim]: a second call whose
     *  startPos was already covered by a prior propagate's BFS no-ops. Keeps cost
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
                // Lazy LOS recheck on Advanced Node laser links, see
                // [findTopologyController] for the same gate.
                if (!checkLineOfSightCached(level, pos, conn)) continue
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

        // `markUnsaved` persists the new id without queuing the BE for the
        // standard per-BE sync broadcast. Clients receive the batched payload below.
        val changedPositions = ArrayList<BlockPos>()
        val previousIds = HashSet<java.util.UUID>()
        for (pos in visited) {
            val entity = getConnectable(level, pos) ?: continue
            if (entity.networkId != foundId) {
                entity.networkId?.let { previousIds.add(it) }
                entity.networkId = foundId
                changedPositions.add(pos)
                level.getChunk(pos).markUnsaved()
            }
        }

        if (changedPositions.isNotEmpty()) {
            damien.nodeworks.platform.PlatformServices.serverNetworking.sendToPlayersInDimension(
                level,
                NetworkIdBatchPayload(foundId, changedPositions),
            )
        }

        foundId?.let { NetworkDiscovery.invalidate(it) }
        for (id in previousIds) NetworkDiscovery.invalidate(id)
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

    /** Re-propagate from this position when a Connectable's chunk loads. */
    fun revalidateOnLoad(level: ServerLevel, self: Connectable) {
        propagateNetworkId(level, self.getBlockPos())
    }

    // --- Block-change driven LOS revalidation ---
    //
    // Fired by [ServerLevelSetBlockMixin] on every block change. Walks the
    // 3×3 chunk neighbourhood, re-raycasts each link passing near here,
    // updates blocked-pair state + propagates. Connectables with empty
    // getConnections (everything but Focus Nodes) short-circuit, so this
    // only does real work for the small fraction of BEs that have laser links.

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
            // Process each pair from its lex-lower endpoint to avoid the
            // A→B + B→A double-walk.
            if (!isLessThan(nodePos, targetPos)) continue
            // Skip raycast for changes outside the link's bounding AABB.
            if (!isInsideConnectionBounds(nodePos, targetPos, changedPos)) continue

            val wasBlocked = isPairBlocked(level, nodePos, targetPos)
            val hasLos = checkLineOfSight(level, nodePos, targetPos)
            val flipped = hasLos == wasBlocked

            // Stale-cache heal: on first server load the blocked-set is
            // empty so a "restored LOS" event looks like no change. Detect
            // by cross-checking endpoint networkIds, mismatch with clear LOS
            // means propagate is needed.
            val targetEntity = getConnectable(level, targetPos)
            val inconsistent = hasLos && targetEntity != null && entity.networkId != targetEntity.networkId

            if (!flipped && !inconsistent) continue

            // Auto-splice when the new obstruction is itself a fresh Focus Node.
            if (!wasBlocked && !hasLos && trySplice(level, nodePos, targetPos, changedPos)) continue

            setPairBlocked(level, nodePos, targetPos, !hasLos)
            propagateNetworkId(level, nodePos)
            // BFS from nodePos can't cross the now-blocked edge, so the far
            // end (which may have lost its controller) needs its own propagate.
            if (!hasLos) propagateNetworkId(level, targetPos)
        }
    }

    private fun isInsideConnectionBounds(a: BlockPos, b: BlockPos, point: BlockPos): Boolean {
        return point.x in (minOf(a.x, b.x) - 1)..(maxOf(a.x, b.x) + 1)
            && point.y in (minOf(a.y, b.y) - 1)..(maxOf(a.y, b.y) + 1)
            && point.z in (minOf(a.z, b.z) - 1)..(maxOf(a.z, b.z) + 1)
    }

    /** Auto-splice a fresh Focus Node placed mid-link. A↔B becomes A↔C↔B
     *  when C (a freshly-placed Focus Node, no existing links of its own)
     *  is on the line and within range of both endpoints. Returns true when
     *  the splice succeeded so [checkNodeConnections] skips its blocked-pair
     *  bookkeeping for that edge. */
    private fun trySplice(level: ServerLevel, posA: BlockPos, posB: BlockPos, splicerPos: BlockPos): Boolean {
        val splicer = level.getBlockEntity(splicerPos) as? damien.nodeworks.block.entity.FocusNodeBlockEntity ?: return false
        if (splicer.getConnections().isNotEmpty()) return false
        if (!isWithinRange(posA, splicerPos)) return false
        if (!isWithinRange(splicerPos, posB)) return false
        if (!checkLineOfSight(level, posA, splicerPos)) return false
        if (!checkLineOfSight(level, splicerPos, posB)) return false
        val entityA = getConnectable(level, posA) ?: return false
        val entityB = getConnectable(level, posB) ?: return false

        entityA.addConnection(splicerPos)
        splicer.addConnection(posA)
        entityB.addConnection(splicerPos)
        splicer.addConnection(posB)
        entityA.removeConnection(posB)
        entityB.removeConnection(posA)
        // Routed through [setPairBlocked] for the dirty flag, otherwise
        // a stale A->B blocked entry would come back from disk on restart.
        setPairBlocked(level, posA, posB, false)

        propagateNetworkId(level, splicerPos)
        return true
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
