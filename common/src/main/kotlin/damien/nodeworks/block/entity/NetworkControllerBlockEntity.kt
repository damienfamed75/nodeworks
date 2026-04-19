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
 * Block entity for the Network Controller — the heart of every network.
 * Stores a UUID that defines the network's identity.
 * Generated on first placement, persists through world save/load.
 */
class NetworkControllerBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.NETWORK_CONTROLLER, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false

    /** The network's unique identity. Generated on first placement. */
    override var networkId: UUID? = UUID.randomUUID()

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
     *  Updated via [setChunkLoadingEnabled]; direct write by NBT load only (to avoid
     *  triggering claim/unclaim before the level is known). */
    var chunkLoadingEnabled: Boolean = false
        private set

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
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

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
            // restored the actual chunk-forced flags; this just gets the manager back in
            // sync so disabling later correctly decrements.
            if (chunkLoadingEnabled && claimedChunks.isNotEmpty()) {
                for (packed in claimedChunks) {
                    ChunkForceLoadManager.claim(level, ChunkPos.getX(packed), ChunkPos.getZ(packed))
                }
            }
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            // Unclaim chunk-loading ONLY on actual block destruction — chunk unload runs
            // setRemoved too and we don't want to tear down our own force-loads there.
            if (blockDestroyed && chunkLoadingEnabled) {
                releaseAllClaims(lvl)
            }
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
            networkId?.let {
                damien.nodeworks.script.NetworkInventoryCache.removeByUUID(it)
                damien.nodeworks.network.NetworkSettingsRegistry.remove(it)
            }
        }
        super.setRemoved()
    }

    /** Toggle chunk-loading for this controller. Enabling walks the current network
     *  topology (full connection graph, ignoring LOS) and claims every visited block's
     *  chunk with [ChunkForceLoadManager]. Disabling releases every previously claimed
     *  chunk — the manager's refcount ensures chunks still claimed by other enabled
     *  controllers in the same dimension stay loaded.
     *
     *  Topology is snapshotted at enable-time. Extending the network afterwards does NOT
     *  auto-claim the new blocks' chunks; re-toggle off/on to refresh. */
    fun setChunkLoadingEnabled(enabled: Boolean) {
        if (enabled == chunkLoadingEnabled) return
        val lvl = level as? ServerLevel ?: run {
            // No level yet (BE construction path) — just store the flag; setLevel will
            // pick it up via the NBT-load path next time.
            chunkLoadingEnabled = enabled
            setChanged()
            return
        }
        if (enabled) {
            val chunks = gatherTopologyChunks(lvl)
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

    /** BFS through the full topological connection graph (LOS-blocked pairs included —
     *  we want every physically-connected block's chunk loaded regardless of temporary
     *  obstructions) and return the set of packed ChunkPos longs. */
    private fun gatherTopologyChunks(lvl: ServerLevel): Set<Long> {
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        val chunks = HashSet<Long>()
        visited.add(worldPosition)
        queue.add(worldPosition)
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            chunks.add(ChunkPos.pack(pos.x shr 4, pos.z shr 4))
            val entity = NodeConnectionHelper.getConnectable(lvl, pos) ?: continue
            for (conn in entity.getConnections()) {
                if (visited.add(conn)) queue.add(conn)
            }
        }
        return chunks
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putInt("networkColor", networkColor)
        output.putString("networkName", networkName)
        output.putInt("redstoneMode", redstoneMode)
        output.putInt("nodeGlowStyle", nodeGlowStyle)
        output.putInt("handlerRetryLimit", handlerRetryLimit)
        output.putBoolean("chunkLoadingEnabled", chunkLoadingEnabled)
        output.putLongList("claimedChunks", claimedChunks.toLongArray())
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val idStr = input.getStringOr("networkId", "")
        if (idStr.isNotEmpty()) {
            try {
                networkId = UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                networkId = UUID.randomUUID()
            }
        }
        networkColor = input.getIntOr("networkColor", 0x83E086)
        networkName = input.getStringOr("networkName", "")
        redstoneMode = input.getIntOr("redstoneMode", 0)
        nodeGlowStyle = input.getIntOr("nodeGlowStyle", GLOW_SQUARE)
        handlerRetryLimit = input.getIntOr("handlerRetryLimit", 50)
        chunkLoadingEnabled = input.getBooleanOr("chunkLoadingEnabled", false)
        claimedChunks.clear()
        for (packed in input.getLongList("claimedChunks")) claimedChunks.add(packed)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        // Push loaded settings into client-visible registry so color/glow survive reloads.
        networkId?.let {
            damien.nodeworks.network.NetworkSettingsRegistry.update(
                it,
                damien.nodeworks.network.NetworkSettingsRegistry.NetworkSettings(networkColor, nodeGlowStyle)
            )
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
