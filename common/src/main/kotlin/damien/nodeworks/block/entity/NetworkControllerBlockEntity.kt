package damien.nodeworks.block.entity

import damien.nodeworks.network.ChunkForceLoadManager
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.world.level.ChunkPos
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getLongList
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.compat.putLongList
import java.util.UUID

/**
 * Block entity for the Network Controller, the heart of every network.
 * Stores a UUID that defines the network's identity.
 * Generated on first placement, persists through world save/load.
 */
class NetworkControllerBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.NETWORK_CONTROLLER, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false

    /** Persistent identity. Generated on first placement, never changed by propagate.
     *  Survives conflict / restoration cycles, so disconnecting one of two warring
     *  controllers cleanly restores the other's network using its preserved id. */
    var permanentId: UUID = UUID.randomUUID()
        private set

    /** Current membership. Equal to [permanentId] in normal operation, null when
     *  propagate detected a multi-controller conflict in this subgraph. */
    override var networkId: UUID? = permanentId

    // --- Network Settings ---

    /** Network color as RGB (no alpha). Default green. */
    var networkColor: Int = 0x83E086
        set(value) {
            field = value and 0xFFFFFF
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Custom network name. Empty = unnamed. */
    var networkName: String = ""
        set(value) {
            field = value.take(32) // cap length
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Redstone mode: 0 = Ignored, 1 = Active on Low, 2 = Active on High */
    var redstoneMode: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Node glow style: 0=square, 1=circle, 2=dot, 3=creeper, 4=spiral, 5=none */
    var nodeGlowStyle: Int = GLOW_SQUARE
        set(value) {
            field = value.coerceIn(0, 5)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** How many times the Crafting CPU retries a Processing Set handler whose inputs
     *  went unmoved before giving up. 0 = never retry (fail fast). Default 50. */
    var handlerRetryLimit: Int = 50
        set(value) {
            field = value.coerceIn(0, 500)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Whether this controller is force-loading the chunks of every block in its network.
     *  Updated via [setChunkLoadingEnabled], direct write by NBT load only (to avoid
     *  triggering claim/unclaim before the level is known). */
    var chunkLoadingEnabled: Boolean = false
        private set

    /** Whether laser beams between this network's connectables render. Nodes and
     *  their colored glows still render either way, this only gates the inter-node
     *  beams. Default true. */
    var laserEnabled: Boolean = true
        set(value) {
            field = value
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Beam render mode: 0 = Fancy (current animated prism + glow), 1 = Fast
     *  (single thin colored line per connection). Default Fancy. */
    var laserMode: Int = damien.nodeworks.network.NetworkSettingsRegistry.LASER_MODE_FANCY
        set(value) {
            field = value.coerceIn(0, 1)
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }

    /** Chunk-long keys this controller has claimed with [ChunkForceLoadManager]. Persisted
     *  in NBT so a reload can re-claim (rebuilding the in-memory refcount map) without
     *  re-walking the network. */
    private val claimedChunks: MutableSet<Long> = HashSet()

    companion object {
        const val REDSTONE_IGNORED = 0
        const val REDSTONE_LOW = 1
        const val REDSTONE_HIGH = 2

        const val GLOW_SQUARE = 0
        const val GLOW_CIRCLE = 1
        const val GLOW_DOT = 2
        const val GLOW_CREEPER = 3
        const val GLOW_SPIRAL = 4
        const val GLOW_NONE = 5

        /** Interval between [serverTick] chunk-claim refreshes. Each walk
         *  is O(N) in network size but reads only already-loaded chunks
         *  (no disk I/O), so even multi-thousand-block networks scan in
         *  well under a millisecond. The diff-apply only touches
         *  [ChunkForceLoadManager] for chunks that actually changed
         *  status since the last refresh. */
        const val CHUNK_REFRESH_INTERVAL_TICKS = 100L
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
            // Rebuild the in-memory chunk-load refcount for every chunk this controller
            // had claimed before the server shut down. Vanilla's forcedchunks.dat already
            // restored the actual chunk-forced flags, this just gets the manager back in
            // sync so disabling later correctly decrements.
            if (chunkLoadingEnabled && claimedChunks.isNotEmpty()) {
                for (packed in claimedChunks) {
                    ChunkForceLoadManager.claim(level, ChunkPos.getX(packed), ChunkPos.getZ(packed))
                }
            }
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            // Only release on player destruction. Detecting other removal paths
            // (`/setblock air`, etc.) by reading the new block state here would
            // race with chunk-unload during world save and hang the save.
            if (blockDestroyed && chunkLoadingEnabled) {
                releaseAllClaims(lvl)
            }
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
            // Key on permanentId since networkId is null mid-conflict.
            damien.nodeworks.script.NetworkInventoryCache.removeByUUID(permanentId)
            damien.nodeworks.network.NetworkSettingsRegistry.remove(permanentId)
        }
        super.setRemoved()
    }

    /** Toggle chunk-loading for this controller. Enabling does a one-shot
     *  force-loaded walk (chunks get loaded as we visit them so even
     *  distant micro-networks get included). After that the [serverTick]
     *  periodic refresh keeps the claim set in sync as players add /
     *  remove blocks - no manual re-toggle needed.
     *
     *  Disabling releases every previously claimed chunk;
     *  [ChunkForceLoadManager]'s refcount ensures chunks still claimed by
     *  other enabled controllers in the same dimension stay loaded. */
    fun setChunkLoadingEnabled(enabled: Boolean) {
        if (enabled == chunkLoadingEnabled) return
        val lvl = level as? ServerLevel ?: run {
            // No level yet (BE construction path), just store the flag, setLevel will
            // pick it up via the NBT-load path next time.
            chunkLoadingEnabled = enabled
            setChanged()
            return
        }
        if (enabled) {
            // First-time walk forces chunks open so the BFS can reach
            // blocks beyond the player's view distance. Periodic refreshes
            // in serverTick skip the force-load to stay cheap.
            val chunks = gatherTopologyChunks(lvl, forceLoadMissing = true)
            claimedChunks.clear()
            claimedChunks.addAll(chunks)
            for (packed in chunks) {
                ChunkForceLoadManager.claim(lvl, ChunkPos.getX(packed), ChunkPos.getZ(packed))
            }
        } else {
            releaseAllClaims(lvl)
        }
        chunkLoadingEnabled = enabled
        setChanged()
        lvl.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    private fun releaseAllClaims(lvl: ServerLevel) {
        for (packed in claimedChunks) {
            ChunkForceLoadManager.unclaim(lvl, ChunkPos.getX(packed), ChunkPos.getZ(packed))
        }
        claimedChunks.clear()
    }

    /** Per-controller phase offset so refresh ticks don't pile up. If 50
     *  chunk-loaded controllers come online on the same gameTime (server
     *  restart, batch placement, etc.), aligning their refreshes would
     *  produce a tick spike every interval. Hashing the position spreads
     *  them deterministically across the window with no extra state.
     *
     *  Why: a public server with hundreds of controllers + multi-thousand
     *  block networks could otherwise stack every refresh into the same
     *  tick. With this offset, controllers refresh on different ticks
     *  within [CHUNK_REFRESH_INTERVAL_TICKS] so the cost is amortised. */
    private val refreshPhase: Long =
        Math.floorMod(worldPosition.hashCode().toLong(), CHUNK_REFRESH_INTERVAL_TICKS)

    /** Per-tick entry point wired by [damien.nodeworks.block.NetworkControllerBlock.getTicker].
     *  No-op when chunk loading is off, so the tick cost for the common
     *  case is one branch. When on, walks the topology every
     *  [CHUNK_REFRESH_INTERVAL_TICKS] ticks and diff-applies the change
     *  set against [ChunkForceLoadManager] - so extending the network
     *  (placing more pipes, attaching a new Processing Handler with its
     *  own micro-network, ...) auto-claims the new chunks without the
     *  player toggling chunk loading off and on. */
    fun serverTick(lvl: ServerLevel) {
        if (!chunkLoadingEnabled) return
        if (Math.floorMod(lvl.gameTime + refreshPhase, CHUNK_REFRESH_INTERVAL_TICKS) != 0L) return
        refreshClaimedChunks(lvl)
    }

    /** Walk the current topology (loaded chunks only, no I/O) and apply
     *  the delta to [ChunkForceLoadManager]. Walks Processing Handler
     *  micro-network boundaries explicitly with a single force-load of
     *  the front-face chunk so a newly placed handler that spans an
     *  unclaimed-but-loaded chunk still picks up its micro side. */
    private fun refreshClaimedChunks(lvl: ServerLevel) {
        val fresh = gatherTopologyChunks(lvl, forceLoadMissing = false)
        if (fresh == claimedChunks) return
        // Newly visible chunks: claim them. Cheap iteration since the
        // typical refresh tick has zero diff and we just check sizes /
        // membership.
        var changed = false
        for (packed in fresh) {
            if (claimedChunks.add(packed)) {
                ChunkForceLoadManager.claim(lvl, ChunkPos.getX(packed), ChunkPos.getZ(packed))
                changed = true
            }
        }
        // Chunks no longer in the topology: release them. Iterate a copy
        // so we can mutate [claimedChunks] safely.
        val stale = claimedChunks.filter { it !in fresh }
        for (packed in stale) {
            claimedChunks.remove(packed)
            ChunkForceLoadManager.unclaim(lvl, ChunkPos.getX(packed), ChunkPos.getZ(packed))
            changed = true
        }
        if (changed) setChanged()
    }

    /** BFS through the full topological connection graph and return the set
     *  of packed ChunkPos longs. Walks both [Connectable.getConnections] (the
     *  legacy laser-link graph) AND face-adjacency neighbours (the pipe-based
     *  graph) so every device, node, pipe, antenna, etc. that participates
     *  in the network gets its chunk claimed regardless of which connection
     *  model it uses. LOS-blocked pairs are included since chunk loading
     *  shouldn't depend on transient line-of-sight state.
     *
     *  Chunks are force-loaded as the walk visits each block so the BFS can
     *  see blocks beyond the player's view distance. Without that, the
     *  toggle-on walk would silently truncate the moment it hit an unloaded
     *  chunk and those chunks would never make it into [claimedChunks] - the
     *  classic "I enabled chunk loading but my far-side micro-network still
     *  unloads" bug. The toggle is one-shot so the per-claim chunk-load cost
     *  is fine.
     *
     *  Processing Handler micro-networks are crossed explicitly: when the
     *  walk reaches a [ProcessingHandlerBlockEntity], it enqueues the
     *  front-face neighbour so the micro-network on the other side of the
     *  handler gets force-loaded too. The generic adjacency loop would also
     *  walk that neighbour, but the explicit branch makes the intent clear
     *  and is robust against any future face-gating changes on the handler. */
    /** Walk the network topology and return the set of packed chunk keys
     *  it occupies.
     *
     *  [forceLoadMissing] controls the I/O behaviour:
     *   * `true` (one-shot toggle-on path) - force-loads each visited
     *     chunk via [ChunkSource.getChunk] so the BFS can reach blocks
     *     beyond the player's current view distance. Slower (synchronous
     *     chunk reads) but guarantees the full network is discovered the
     *     first time the user enables chunk loading.
     *   * `false` (periodic [serverTick] refresh) - skips force-loading
     *     entirely. The walk only crosses chunks that are already loaded.
     *     Since enabling chunk loading already force-loaded every network
     *     chunk via [ChunkForceLoadManager.claim], the parent network
     *     stays fully reachable in steady state, and newly-extended
     *     chunks are by definition loaded (the player is there placing).
     *     Result: no disk I/O, sub-millisecond even for thousand-block
     *     networks, so the dynamic refresh has effectively zero overhead.
     *
     *  Processing Handler micro-network boundaries always force-load the
     *  front-face chunk (one chunk per handler at most) so the
     *  player can add a new handler with its micro side on a non-claimed
     *  chunk and have it picked up by the next refresh tick. */
    private fun gatherTopologyChunks(lvl: ServerLevel, forceLoadMissing: Boolean): Set<Long> {
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        val chunks = HashSet<Long>()
        visited.add(worldPosition)
        queue.add(worldPosition)
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            if (forceLoadMissing) {
                lvl.chunkSource.getChunk(pos.x shr 4, pos.z shr 4, true)
            }
            val entity = NodeConnectionHelper.getConnectable(lvl, pos) ?: continue
            // Only record chunks that actually contain a [Connectable] - a
            // dead-end via the explicit handler branch (no BE on the front
            // side) shouldn't pollute claimedChunks with a chunk we don't
            // care about.
            chunks.add(ChunkPos.pack(pos.x shr 4, pos.z shr 4))
            for (conn in entity.getConnections()) {
                if (visited.add(conn)) queue.add(conn)
            }
            if (entity.usesAdjacency()) {
                for (dir in net.minecraft.core.Direction.entries) {
                    val neighbor = pos.relative(dir)
                    if (forceLoadMissing) {
                        lvl.chunkSource.getChunk(neighbor.x shr 4, neighbor.z shr 4, true)
                    }
                    val neighborBe = lvl.getBlockEntity(neighbor) as? damien.nodeworks.network.Connectable ?: continue
                    if (!neighborBe.usesAdjacency()) continue
                    if (!entity.canConnectAdjacentTo(neighborBe)) continue
                    if (!neighborBe.canConnectAdjacentTo(entity)) continue
                    if (entity.forcedPipeBlocked(dir)) continue
                    if (neighborBe.forcedPipeBlocked(dir.opposite)) continue
                    if (visited.add(neighbor)) queue.add(neighbor)
                }
            }
            // Processing Handler boundary crossing - always force-load the
            // front face since a newly placed handler may span into an
            // unloaded chunk regardless of the wider walk's I/O policy.
            // At most one extra chunk load per handler in the network.
            if (entity is ProcessingHandlerBlockEntity) {
                val frontPos = pos.relative(entity.frontFace)
                lvl.chunkSource.getChunk(frontPos.x shr 4, frontPos.z shr 4, true)
                if (visited.add(frontPos)) queue.add(frontPos)
            }
        }
        return chunks
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("permanentId", permanentId.toString())
        // Omitting networkId when null is intentional, absence on load means "in conflict".
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putInt("networkColor", networkColor)
        output.putString("networkName", networkName)
        output.putInt("redstoneMode", redstoneMode)
        output.putInt("nodeGlowStyle", nodeGlowStyle)
        output.putInt("handlerRetryLimit", handlerRetryLimit)
        output.putBoolean("chunkLoadingEnabled", chunkLoadingEnabled)
        output.putBoolean("laserEnabled", laserEnabled)
        output.putInt("laserMode", laserMode)
        output.putLongList("claimedChunks", claimedChunks.toLongArray())
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        // Legacy worlds stored identity under "networkId". Fall back to that key on
        // the first post-upgrade load and let propagate decide membership on revalidation.
        val permIdStr = input.getStringOr("permanentId", "")
        val networkIdStr = input.getStringOr("networkId", "")
        val identityStr = permIdStr.takeIf { it.isNotEmpty() } ?: networkIdStr
        if (identityStr.isNotEmpty()) {
            try {
                permanentId = UUID.fromString(identityStr)
            } catch (_: IllegalArgumentException) {
                permanentId = UUID.randomUUID()
            }
        }
        networkId = if (permIdStr.isNotEmpty()) {
            // Post-upgrade: missing networkId key means "in conflict".
            if (networkIdStr.isEmpty()) null
            else try {
                UUID.fromString(networkIdStr)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            // Legacy save without permanentId, propagate will recheck on revalidation.
            permanentId
        }
        networkColor = input.getIntOr("networkColor", 0x83E086)
        networkName = input.getStringOr("networkName", "")
        redstoneMode = input.getIntOr("redstoneMode", 0)
        nodeGlowStyle = input.getIntOr("nodeGlowStyle", GLOW_SQUARE)
        handlerRetryLimit = input.getIntOr("handlerRetryLimit", 50)
        chunkLoadingEnabled = input.getBooleanOr("chunkLoadingEnabled", false)
        laserEnabled = input.getBooleanOr("laserEnabled", true)
        laserMode = input.getIntOr("laserMode", damien.nodeworks.network.NetworkSettingsRegistry.LASER_MODE_FANCY)
        claimedChunks.clear()
        for (packed in input.getLongList("claimedChunks")) claimedChunks.add(packed)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        // Seed the client-visible registry on reload so renderer + GUI pick up persisted
        // settings without waiting for the next sync. Keyed on permanentId so the entry
        // survives a conflict cycle.
        damien.nodeworks.network.NetworkSettingsRegistry.update(
            permanentId,
            damien.nodeworks.network.NetworkSettingsRegistry.NetworkSettings(
                color = networkColor,
                glowStyle = nodeGlowStyle,
                laserEnabled = laserEnabled,
                laserMode = laserMode,
            )
        )
        // Invalidate the chunk tint cache when membership flipped to/from null but
        // colour settings stayed identical, [update] above doesn't fire onChanged then.
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
