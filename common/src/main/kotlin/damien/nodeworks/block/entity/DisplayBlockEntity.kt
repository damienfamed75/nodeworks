package damien.nodeworks.block.entity

import damien.nodeworks.block.DisplayBlock
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.DisplayVolume
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
import java.util.UUID

/**
 * Backs the Display block, a script-driven retained-mode UI surface. Holds a
 * flat list of [DisplayElement]s committed by a Lua script's `flush()` and a
 * script-addressable [displayName] read from the item's custom name on
 * placement. Connectable so it joins the node-connection graph like the
 * Monitor and Terminal, which is how `network:display(name)` reaches it.
 */
class DisplayBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.DISPLAY, pos, state), Connectable {

    companion object {
        /** Upper bound on committed elements per frame. Bounds the sync packet
         *  size and client render cost, a script needing more is likely buggy. */
        const val MAX_ELEMENTS = 256
    }

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** Script-addressable name, set from the item's custom name on placement.
     *  Empty means the display won't appear in `network:display` lookups. */
    var displayName: String = ""
        set(value) {
            field = value.take(32)
            markDirtyAndSync()
        }

    /** The currently-committed frame. Replaced wholesale by [setElements].
     *  The Display is a pure draw surface, this is the only state it holds. */
    var elements: List<DisplayElement> = emptyList()
        private set

    fun setElements(list: List<DisplayElement>) {
        elements = if (list.size > MAX_ELEMENTS) list.take(MAX_ELEMENTS) else list.toList()
        markDirtyAndSync()
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Multiblock cluster ---
    //
    // Display blocks placed edge-adjacent in the plane of their face, all
    // sharing the same FACING, merge into one logical display when (and only
    // when) they form a filled rectangle. The cluster's logical volume is
    // `(cols * CANVAS) x (rows * CANVAS) x CANVAS_DEPTH`. The [anchor] is the
    // member at volume coord (0,0), it carries the committed element list and
    // the owner-terminal link; every member shares the same name.

    /** Result of a [cluster] walk. [members] is the full connected set, or
     *  just this block when the arrangement isn't a filled rectangle. */
    data class Cluster(
        val members: List<BlockPos>,
        val anchor: BlockPos,
        val cols: Int,
        val rows: Int,
    )

    /** Walk the in-plane connected component of same-facing Displays. Returns
     *  the cluster only when it's a filled rectangle, otherwise this block
     *  stands alone as a 1x1. Not cached, callers are cold paths (render
     *  extract, network scan, GUI open). */
    fun cluster(): Cluster {
        val lvl = level ?: return Cluster(listOf(worldPosition), worldPosition, 1, 1)
        val facing = blockState.getValue(DisplayBlock.FACING)
        val (xDir, yDir) = DisplayVolume.faceAxes(facing)
        val planeDirs = listOf(xDir, xDir.opposite, yDir, yDir.opposite)

        val seen = mutableSetOf(worldPosition)
        val queue = ArrayDeque<BlockPos>()
        queue.add(worldPosition)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (d in planeDirs) {
                val np = p.relative(d)
                if (np in seen || !lvl.isLoaded(np)) continue
                val be = lvl.getBlockEntity(np) as? DisplayBlockEntity ?: continue
                if (be.blockState.getValue(DisplayBlock.FACING) != facing) continue
                seen.add(np)
                queue.add(np)
            }
        }

        // volume-x runs along xDir; volume-y runs along -yDir (DisplayVolume's
        // negative y-scale puts y=0 at the top of the face).
        fun vx(p: BlockPos) = p.x * xDir.stepX + p.y * xDir.stepY + p.z * xDir.stepZ
        fun vy(p: BlockPos) = -(p.x * yDir.stepX + p.y * yDir.stepY + p.z * yDir.stepZ)
        val minVx = seen.minOf(::vx)
        val minVy = seen.minOf(::vy)
        val cols = seen.maxOf(::vx) - minVx + 1
        val rows = seen.maxOf(::vy) - minVy + 1
        if (seen.size != cols * rows) {
            return Cluster(listOf(worldPosition), worldPosition, 1, 1)
        }
        val anchor = seen.first { vx(it) == minVx && vy(it) == minVy }
        return Cluster(seen.toList(), anchor, cols, rows)
    }

    /** True when this block carries the cluster's elements + owner link. */
    fun isClusterAnchor(): Boolean = cluster().anchor == worldPosition

    /** The cluster's shared name. The anchor's name wins, falling back to the
     *  first non-empty member name so a freshly-placed block that becomes the
     *  new anchor still resolves until the next GUI commit. */
    fun clusterName(): String {
        val lvl = level ?: return displayName
        val c = cluster()
        (lvl.getBlockEntity(c.anchor) as? DisplayBlockEntity)?.displayName
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        for (p in c.members) {
            val n = (lvl.getBlockEntity(p) as? DisplayBlockEntity)?.displayName
            if (!n.isNullOrEmpty()) return n
        }
        return ""
    }

    /** Write [name] to every cluster member so the shared GUI stays consistent. */
    fun setClusterName(name: String) {
        val lvl = level
        if (lvl == null) {
            displayName = name
            return
        }
        for (p in cluster().members) {
            (lvl.getBlockEntity(p) as? DisplayBlockEntity)?.let { it.displayName = name }
        }
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val currentLevel = level
        if (currentLevel is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(currentLevel, this)
            NodeConnectionHelper.untrackNode(currentLevel, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putBlockPosList("connections", connections)
        networkId?.let { output.putString("networkId", it.toString()) }
        if (displayName.isNotEmpty()) output.putString("displayName", displayName)
        if (elements.isNotEmpty()) {
            val list = output.childrenList("elements")
            for (e in elements) e.writeTo(list.addChild())
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        displayName = input.getStringOr("displayName", "")
        val loaded = ArrayList<DisplayElement>()
        for (child in input.childrenListOrEmpty("elements")) {
            DisplayElement.readFrom(child)?.let { loaded.add(it) }
        }
        elements = loaded
    }

    // --- Client sync ---
    //
    // `saveWithoutMetadata` delegates to `saveAdditional`, so the update packet
    // carries `networkId`, `connections`, `displayName`, and `elements`, the
    // renderer needs all four.

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
