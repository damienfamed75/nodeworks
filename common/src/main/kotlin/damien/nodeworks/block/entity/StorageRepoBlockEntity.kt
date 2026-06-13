package damien.nodeworks.block.entity

import damien.nodeworks.block.StorageRepoBlock
import damien.nodeworks.block.repo.RepoClusterRole
import damien.nodeworks.block.repo.StorageRepoTier
import damien.nodeworks.card.StorageCard
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * Block entity for the Storage Repo. Holds [StorageRepoTier.slotCount] slots × the
 * tier's per-slot capacity. Cluster machinery merges face-adjacent Repo blocks of
 * matching tier into a single logical silo when their bounding box satisfies the
 * shape rule (X×Z ∈ {1×1, 2×2, 3×3}, any height Y).
 *
 * **Settings ownership:** the cluster's channel + filter rules live on the anchor
 * block (lex-lowest position in the cluster). Non-anchor members redirect to the
 * anchor for reads and writes. The per-block [items] inventory stays per-block,
 * which is what gives the cache its locality — modifying one block's slots only
 * dirties that block's [modificationVersion], not the whole cluster.
 *
 * **Shape rule:** a cluster is "valid" iff its bounding box is X × Z × any-Y where
 * X == Z ∈ {1, 2, 3} and every cell inside the bbox is a tier-matching Repo. When
 * invalid, the BE still works as a storage container (you can put items in it,
 * extract from it) but the connected-texture renderer falls back to standalone
 * blocks. Shape validity is a render concern; storage works either way.
 */
class StorageRepoBlockEntity(
    val tier: StorageRepoTier,
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.STORAGE_REPO, pos, state), Container, Connectable {

    private val items = NonNullList.withSize(tier.slotCount, ItemStack.EMPTY)

    override var blockDestroyed: Boolean = false

    override var networkId: UUID? = null

    override fun getConnections(): Set<BlockPos> = emptySet()
    override fun addConnection(pos: BlockPos): Boolean = false
    override fun removeConnection(pos: BlockPos): Boolean = false
    override fun hasConnection(pos: BlockPos): Boolean = false

    /** Storage Repos chain network membership via face-adjacency only to:
     *
     *    * Other Storage Repos in the SAME valid-shape silo cluster. Two adjacent Repos
     *      that don't form a 1×1, 2×2, or 3×3 footprint (any height) refuse to bridge
     *      — each must be pipe-connected independently. A 3×3 silo with a corner
     *      broken (8 blocks, footprint 3×3 with a hole) is shape-invalid: those 8
     *      blocks each become their own network entry point.
     *    * Pipes (the canonical network entry point).
     *
     *  Everything else (Terminals, Controllers, Nodes, antennas, chests, ...) is
     *  refused so a player can't bypass a Pipe by jamming a Terminal directly onto a
     *  Repo. All meaningful network access flows through a Pipe. */
    override fun canConnectAdjacentTo(other: Connectable): Boolean {
        return when (other) {
            is StorageRepoBlockEntity -> isClusterValidShape() && other.isClusterValidShape()
            is PipeBlockEntity -> true
            is CoveredPipeBlockEntity -> true
            else -> false
        }
    }

    /** Per-block dirty version. The planned NetworkInventoryCache integration
     *  reads this to skip re-walking blocks that haven't changed since the last
     *  cache snapshot. */
    var modificationVersion: Long = 0L
        private set

    // The anchor is the source of truth; non-anchor blocks delegate reads to it via
    // [getAnchor]. Writes go through [applySettings] which writes the anchor and
    // marks dirty exactly once.

    private var localChannel: DyeColor = DyeColor.WHITE
    private var localFilterMode: StorageCard.Companion.FilterMode = StorageCard.Companion.FilterMode.ALLOW
    private var localFilterRules: List<String> = emptyList()
    private var localStackability: StorageCard.Companion.StackabilityFilter = StorageCard.Companion.StackabilityFilter.ANY
    private var localNbtFilter: StorageCard.Companion.NbtFilter = StorageCard.Companion.NbtFilter.ANY
    private var localPriority: Int = 0

    val channel: DyeColor get() = getAnchor()?.localChannel ?: localChannel
    val filterMode: StorageCard.Companion.FilterMode get() = getAnchor()?.localFilterMode ?: localFilterMode
    val filterRules: List<String> get() = getAnchor()?.localFilterRules ?: localFilterRules
    val stackability: StorageCard.Companion.StackabilityFilter get() = getAnchor()?.localStackability ?: localStackability
    val nbtFilter: StorageCard.Companion.NbtFilter get() = getAnchor()?.localNbtFilter ?: localNbtFilter
    val priority: Int get() = getAnchor()?.localPriority ?: localPriority

    /** Atomic settings update routed to the cluster anchor. One [markSettingsDirty]
     *  for the whole change, not one per field — closing the menu otherwise produced
     *  6 BE-update packets for what's logically one edit. */
    fun applySettings(
        channel: DyeColor,
        filterMode: StorageCard.Companion.FilterMode,
        filterRules: List<String>,
        stackability: StorageCard.Companion.StackabilityFilter,
        nbtFilter: StorageCard.Companion.NbtFilter,
        priority: Int,
    ) {
        val anchor = getAnchor() ?: return
        anchor.localChannel = channel
        anchor.localFilterMode = filterMode
        anchor.localFilterRules = filterRules.toList()
        anchor.localStackability = stackability
        anchor.localNbtFilter = nbtFilter
        anchor.localPriority = priority
        anchor.markSettingsDirty()
    }

    // ---- Cluster cache ----

    private var cachedClusterAnchor: BlockPos? = null
    private var cachedClusterMembers: Set<BlockPos>? = null
    private var cachedClusterValid: Boolean = false
    /** Bounds of the cluster as (minPos, maxPos). Both inclusive. Null when cache invalid. */
    private var cachedClusterBounds: Pair<BlockPos, BlockPos>? = null
    /** Cluster-epoch value the current cache was computed at. Mismatch with
     *  [clusterEpoch] forces a recompute. */
    private var cachedEpoch: Long = -1L

    /** Tear down cached cluster info. The cache is rebuilt lazily on next access. */
    fun invalidateClusterCache() {
        cachedClusterAnchor = null
        cachedClusterMembers = null
        cachedClusterBounds = null
        cachedClusterValid = false
        cachedEpoch = -1L
    }

    private fun ensureClusterComputed() {
        val now = clusterEpoch.get()
        if (cachedClusterAnchor != null && cachedEpoch == now) return
        val lvl = level ?: return
        val members = walkCluster(lvl)
        val anchor = members.minByOrNull { it.asLong() } ?: worldPosition
        val (minPos, maxPos) = computeBounds(members)
        val bounds = minPos to maxPos
        val valid = isValidSiloShape(lvl, members, minPos, maxPos)

        // Stamp the BFS result into every member's cache so a 27-block cluster
        // does one walk, not 27. Without this, discovery's per-member visit
        // recomputes from scratch on each Repo BE.
        for (memberPos in members) {
            val be = if (memberPos == worldPosition) this
                else lvl.getBlockEntity(memberPos) as? StorageRepoBlockEntity ?: continue
            be.cachedClusterMembers = members
            be.cachedClusterAnchor = anchor
            be.cachedClusterBounds = bounds
            be.cachedClusterValid = valid
            be.cachedEpoch = now
        }
    }

    companion object {
        /** Monotonic counter bumped on every Storage Repo lifecycle event (placement,
         *  break, chunk-load BE registration). Per-BE cluster caches store the epoch
         *  they were computed at; mismatch on read forces a re-walk. Using a global
         *  epoch instead of poking neighbours during [setLevel] / [setRemoved] avoids
         *  the recursive `level.getBlockEntity(neighborPos)` trap — Level.getBlockEntity
         *  lazily creates BEs, and during chunk load that creation re-enters [setLevel]
         *  on the new BE, which would loop infinitely. */
        private val clusterEpoch = java.util.concurrent.atomic.AtomicLong(0L)

        fun bumpClusterEpoch() {
            clusterEpoch.incrementAndGet()
        }
    }

    /** BFS across face-adjacent Repo blocks of matching tier identity. Two tiers
     *  never merge because the block-instance check fails. */
    private fun walkCluster(lvl: Level): Set<BlockPos> {
        val members = mutableSetOf(worldPosition)
        val queue = ArrayDeque<BlockPos>()
        queue.add(worldPosition)
        val selfBlock = blockState.block
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            for (dir in Direction.entries) {
                val neighbor = pos.relative(dir)
                if (neighbor in members) continue
                if (!lvl.isLoaded(neighbor)) continue
                val neighborState = lvl.getBlockState(neighbor)
                if (neighborState.block !== selfBlock) continue
                members.add(neighbor)
                queue.add(neighbor)
            }
        }
        return members
    }

    private fun computeBounds(members: Set<BlockPos>): Pair<BlockPos, BlockPos> {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (p in members) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z
        }
        return BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
    }

    /** Shape rule: X×Z footprint ∈ {1×1, 2×2, 3×3}, any Y height, every cell inside
     *  the bbox is a Repo of this tier. The bbox-fully-filled check piggybacks on
     *  members.size == expectedCount because walkCluster already returns the exact
     *  tier-matching connected component. */
    private fun isValidSiloShape(
        @Suppress("UNUSED_PARAMETER") lvl: Level,
        members: Set<BlockPos>,
        minPos: BlockPos,
        maxPos: BlockPos,
    ): Boolean {
        val sizeX = maxPos.x - minPos.x + 1
        val sizeY = maxPos.y - minPos.y + 1
        val sizeZ = maxPos.z - minPos.z + 1
        if (sizeX != sizeZ) return false
        if (sizeX !in 1..3) return false
        if (sizeY < 1) return false
        return members.size == sizeX * sizeY * sizeZ
    }

    /** Cluster anchor (lex-lowest [BlockPos] in this cluster), the block that owns
     *  the channel + filter settings. Returns null when the level isn't available
     *  yet (e.g. during NBT load before [setLevel]). */
    fun getAnchor(): StorageRepoBlockEntity? {
        ensureClusterComputed()
        val anchorPos = cachedClusterAnchor ?: return null
        if (anchorPos == worldPosition) return this
        val lvl = level ?: return null
        return lvl.getBlockEntity(anchorPos) as? StorageRepoBlockEntity
    }

    /** Lex-lowest position in this cluster. Used by NetworkDiscovery to dedup
     *  cluster-wide reads (only the anchor contributes diagnostic / settings). */
    fun getClusterAnchorPos(): BlockPos {
        ensureClusterComputed()
        return cachedClusterAnchor ?: worldPosition
    }

    /** Every member position of this cluster. */
    fun getClusterMembers(): Set<BlockPos> {
        ensureClusterComputed()
        return cachedClusterMembers ?: setOf(worldPosition)
    }

    /** True when the cluster matches the silo shape rule (see class doc). Affects
     *  connected-texture rendering only — storage works either way. */
    fun isClusterValidShape(): Boolean {
        ensureClusterComputed()
        return cachedClusterValid
    }

    /** This block's [RepoClusterRole] within its cluster — the value the renderer
     *  reads to pick a baked-model variant. Computed from the cached cluster bounds
     *  + this block's Y position inside them. STANDALONE is returned whenever the
     *  cluster fails the silo shape rule, so an invalid blob's blocks all look like
     *  plain standalone Repos visually. */
    fun getClusterRole(): RepoClusterRole {
        ensureClusterComputed()
        if (!cachedClusterValid) return RepoClusterRole.STANDALONE
        val bounds = cachedClusterBounds ?: return RepoClusterRole.STANDALONE
        val (minPos, maxPos) = bounds
        val height = maxPos.y - minPos.y + 1
        val relY = worldPosition.y - minPos.y
        return when {
            // 1×1×1 lone-but-valid cluster: pick CAP_TOP so the single Repo still
            // shows its roof texture and reads as part of a (tiny) silo.
            height == 1 -> RepoClusterRole.CAP_TOP
            relY == height - 1 -> RepoClusterRole.CAP_TOP
            relY == 0 -> RepoClusterRole.CAP_BOTTOM
            else -> RepoClusterRole.MIDDLE
        }
    }

    // ---- Container ----

    override fun getContainerSize(): Int = tier.slotCount

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack =
        if (slot in items.indices) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) markStorageDirty()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in items.indices) {
            items[slot] = stack
            markStorageDirty()
        }
    }

    override fun getMaxStackSize(): Int = tier.slotCapacity

    override fun stillValid(player: Player): Boolean =
        player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun clearContent() {
        items.clear()
        markStorageDirty()
    }

    /** Container-mutation dirty path. Bumps the version + persists, but skips the
     *  client sync — the client doesn't render slot contents and a hopper feeding a
     *  Repo every tick would otherwise broadcast the entire inventory once per tick
     *  to every viewer in range. Settings changes use [markSettingsDirty] which does
     *  send the update. */
    private fun markStorageDirty() {
        modificationVersion++
        setChanged()
    }

    /** Settings-mutation dirty path. Used by the menu's anchor-routed writes when
     *  channel / filter / priority change. Persists AND broadcasts so other viewers
     *  see the new state. */
    private fun markSettingsDirty() {
        setChanged()
        val lvl = level ?: return
        lvl.sendBlockUpdated(worldPosition, blockState, blockState,
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
    }

    // ---- Lifecycle ----

    override fun setLevel(newLevel: Level) {
        super.setLevel(newLevel)
        // Global epoch bump invalidates every other Repo's cluster cache on its next
        // read. Direct neighbour BE lookups here would recurse during chunk load (each
        // setLevel triggers getBlockEntity, which lazily creates the neighbour BE,
        // which fires its own setLevel — infinite descent).
        bumpClusterEpoch()
        invalidateClusterCache()
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(newLevel, worldPosition, true)
    }

    override fun setRemoved() {
        val lvl = level
        bumpClusterEpoch()
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(lvl, worldPosition, false)
        invalidateClusterCache()
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
            // Queue face-adjacent positions for network revalidation so a split cluster
            // re-derives its network id. queueRevalidation only stashes positions in a
            // queue; it doesn't fetch BEs, so no recursion risk here.
            for (dir in Direction.entries) {
                NodeConnectionHelper.queueRevalidation(lvl, worldPosition.relative(dir))
            }
        }
        super.setRemoved()
    }

    // ---- Serialization ----

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        networkId?.let { output.putString("networkId", it.toString()) }
        // Lowercase serialized form so DyeColor.byName round-trips. The vanilla
        // DyeColor.name() (Java enum) is uppercase and doesn't match byName.
        output.putString("channel", localChannel.getSerializedName())
        output.putString("filterMode", localFilterMode.name)
        output.putString("stackability", localStackability.name)
        output.putString("nbtFilter", localNbtFilter.name)
        output.putInt("priority", localPriority)
        if (localFilterRules.isNotEmpty()) {
            output.store(
                "filterRules",
                com.mojang.serialization.Codec.STRING.listOf(),
                localFilterRules,
            )
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        networkId = input.getStringOrNull("networkId")
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        localChannel = DyeColor.byName(input.getStringOr("channel", "white"), DyeColor.WHITE) ?: DyeColor.WHITE
        localFilterMode = enumValueOrDefault(input.getStringOr("filterMode", "ALLOW"), StorageCard.Companion.FilterMode.ALLOW)
        localStackability = enumValueOrDefault(input.getStringOr("stackability", "ANY"), StorageCard.Companion.StackabilityFilter.ANY)
        localNbtFilter = enumValueOrDefault(input.getStringOr("nbtFilter", "ANY"), StorageCard.Companion.NbtFilter.ANY)
        localPriority = input.getIntOr("priority", 0)
        localFilterRules = input.read(
            "filterRules",
            com.mojang.serialization.Codec.STRING.listOf(),
        ).orElse(emptyList())
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
    }

    private inline fun <reified E : Enum<E>> enumValueOrDefault(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
