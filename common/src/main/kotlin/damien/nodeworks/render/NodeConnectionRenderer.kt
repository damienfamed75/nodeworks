package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionRenderer {

    data class ConnectionPair(val fromPos: BlockPos, val toPos: BlockPos, val color: Int, val blocked: Boolean)

    private val knownNodes: MutableSet<BlockPos> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Default network color (RGB, no alpha). Used as fallback when no controller is found. */
    const val DEFAULT_NETWORK_COLOR = 0x888888

    /** How often to refresh line-of-sight cache (ticks). */
    private const val LOS_REFRESH_INTERVAL = 10

    /** Max raycasts per tick during incremental LOS refresh. */
    private const val LOS_RAYCASTS_PER_TICK = 10

    // Line-of-sight cache: (min(a,b), max(a,b)) → blocked?
    private val losCache = HashMap<Long, Boolean>()
    private var losRefreshTick = 0L
    private var losRefreshIndex = 0  // tracks progress through incremental refresh

    // Set of block positions reachable from any controller through unblocked connections
    private val reachablePositions = HashSet<BlockPos>()

    /** Beam effect toggle — disable for lower-end PCs. */
    var beamEffectEnabled = true

    /** Beam width in blocks (1.5px = 1.5/16). Adjustable. */
    var beamWidth = 2.0f / 16f

    /** Beam scroll speed (UV units per second). */
    var beamScrollSpeed = 2.5f

    /** Beam pulse speed (cycles per second). */
    var beamPulseSpeed = 1.0f

    // Node shape bounds (5/16 to 11/16)
    private const val MIN = 5f / 16f
    private const val MAX = 11f / 16f

    /** Global tracker — any Connectable block entity can register/unregister here. */
    fun trackConnectable(pos: BlockPos, loaded: Boolean) {
        if (loaded) knownNodes.add(pos) else knownNodes.remove(pos)
    }

    /**
     * Walks the connection graph to find the NetworkController for a given position.
     * BFS capped at 32 hops. Returns null if no controller found.
     */
    fun findController(level: net.minecraft.world.level.Level?, startPos: BlockPos): damien.nodeworks.block.entity.NetworkControllerBlockEntity? {
        if (level == null) return null
        val startEntity = level.getBlockEntity(startPos)
        if (startEntity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return startEntity
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        visited.add(startPos)
        val startConnectable = startEntity as? damien.nodeworks.network.Connectable ?: return null
        for (c in startConnectable.getConnections()) queue.add(c)
        var hops = 0
        while (queue.isNotEmpty() && hops < 32) {
            val size = queue.size
            for (i in 0 until size) {
                val cur = queue.removeFirst()
                if (!visited.add(cur)) continue
                val be = level.getBlockEntity(cur)
                if (be is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return be
                val connectable = be as? damien.nodeworks.network.Connectable ?: continue
                for (c in connectable.getConnections()) if (c !in visited) queue.add(c)
            }
            hops++
        }
        return null
    }

    /** Find the network color for a position. Falls back to DEFAULT_NETWORK_COLOR. */
    fun findNetworkColor(level: net.minecraft.world.level.Level?, startPos: BlockPos): Int {
        return findController(level, startPos)?.networkColor ?: DEFAULT_NETWORK_COLOR
    }

    /** Whether the connection between a and b is blocked by geometry (cached). */
    fun isConnectionBlocked(a: BlockPos, b: BlockPos): Boolean {
        val key = edgeKey(a, b)
        return losCache[key] ?: false
    }

    /** Whether this position is reachable from any controller through unblocked connections. */
    fun isReachable(pos: BlockPos): Boolean = reachablePositions.contains(pos)

    /** The currently pinned block position (shown highlighted by the Diagnostic Tool), or null. */
    var pinnedBlock: BlockPos? = null

    fun register() {
        NodeBlockEntity.nodeTracker = NodeBlockEntity.NodeTracker { pos, loaded ->
            trackConnectable(pos, loaded)
        }

        PlatformServices.clientEvents.onWorldRender { poseStack, consumers, cameraPos ->
            if (poseStack != null && consumers != null) {
                render(poseStack, consumers, cameraPos)
                renderPinHighlight(poseStack, consumers, cameraPos)
            }
        }
    }

    // TODO MC 26.1.2 WORLD RENDER REWRITE — stubbed.
    //
    // Pre-migration: draws the laser-beam connections between connected nodes,
    // color-tinted to the network color, with pulse/scroll animation. Also
    // consumed the LOS cache to dim blocked connections. See git history.
    //
    // The new world-render pipeline replaces `MultiBufferSource.getBuffer(RenderType)`
    // + `RenderSystem.setShaderColor`/`enableBlend` with `RenderPipeline`s and the
    // GuiRenderState-style extract/submit flow. The old `RenderType` constants
    // we used (translucent-no-cull with our beam texture) map to new custom
    // pipelines that need to be declared once and reused.
    //
    // See references/framedblocks-26.1 for a real-world port of custom
    // world-render pipelines to 26.1.
    @Suppress("UNUSED_PARAMETER")
    private fun render(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        // Intentionally empty — see TODO above.
    }

    // TODO MC 26.1.2 WORLD RENDER REWRITE — stubbed.
    //
    // Pre-migration: when holding the Diagnostic Tool, draws a pulsing tinted
    // overlay on the pinned block by walking its BlockModel quads and drawing
    // them with a custom shader + blend state. The RenderSystem shader-setup
    // path (setShader, setShaderTexture, setShaderColor) has been removed.
    @Suppress("UNUSED_PARAMETER")
    fun renderPinHighlight(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        // Intentionally empty — see TODO above.
    }

    // ---- internal helpers kept because they're used outside the render path ----

    private fun edgeKey(a: BlockPos, b: BlockPos): Long {
        val (lo, hi) = if (compare(a, b) < 0) a to b else b to a
        return (lo.asLong() * 31L) xor hi.asLong()
    }

    private fun compare(a: BlockPos, b: BlockPos): Int {
        if (a.x != b.x) return a.x - b.x
        if (a.y != b.y) return a.y - b.y
        return a.z - b.z
    }
}
