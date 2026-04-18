package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.registry.ModItems
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlin.math.sin

object NodeConnectionRenderer {

    data class ConnectionPair(val fromPos: BlockPos, val toPos: BlockPos, val color: Int, val blocked: Boolean)

    private val knownNodes: MutableSet<BlockPos> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Default network color (RGB, no alpha). Used as fallback when no controller is found. */
    const val DEFAULT_NETWORK_COLOR = 0x888888

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
        for (conn in startConnectable.getConnections()) {
            if (!isConnectionBlocked(startPos, conn) && visited.add(conn)) queue.add(conn)
        }
        while (queue.isNotEmpty() && visited.size < 32) {
            val pos = queue.removeFirst()
            val entity = level.getBlockEntity(pos) ?: continue
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return entity
            val connectable = entity as? damien.nodeworks.network.Connectable ?: continue
            for (conn in connectable.getConnections()) {
                if (!isConnectionBlocked(pos, conn) && visited.add(conn)) queue.add(conn)
            }
        }
        return null
    }

    /** Convenience: find the network color for a position. Checks registry first, falls back to BFS. */
    fun findNetworkColor(level: net.minecraft.world.level.Level?, startPos: BlockPos): Int {
        // Try registry first (O(1), works even if controller is unloaded)
        val connectable = level?.getBlockEntity(startPos) as? damien.nodeworks.network.Connectable
        val networkId = connectable?.networkId
        if (networkId != null) {
            val regColor = damien.nodeworks.network.NetworkSettingsRegistry.getColor(networkId)
            if (regColor != DEFAULT_NETWORK_COLOR) return regColor
        }
        // Fallback to BFS
        return findController(level, startPos)?.networkColor ?: DEFAULT_NETWORK_COLOR
    }

    /** Whether a specific connection is blocked (no line-of-sight). Uses cache. */
    fun isConnectionBlocked(a: BlockPos, b: BlockPos): Boolean {
        val key = connectionKey(a, b)
        return losCache[key] ?: false
    }

    /** Whether a block position is reachable from a controller through unblocked connections. */
    fun isReachable(pos: BlockPos): Boolean = reachablePositions.contains(pos)

    private fun connectionKey(a: BlockPos, b: BlockPos): Long {
        val (lo, hi) = if (isLessThan(a, b)) a to b else b to a
        return lo.asLong() xor (hi.asLong() * 31)
    }

    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }

    /**
     * Incrementally refresh the LOS cache — processes a limited number of raycasts per tick
     * to avoid frame spikes. When all connections are checked, rebuilds reachability via BFS.
     */
    private fun refreshLosCache(level: net.minecraft.world.level.Level) {
        val pairs = mutableListOf<Pair<BlockPos, BlockPos>>()
        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val connectable = level.getBlockEntity(nodePos) as? damien.nodeworks.network.Connectable ?: continue
            for (targetPos in connectable.getConnections()) {
                if (!isLessThan(nodePos, targetPos)) continue
                pairs.add(nodePos to targetPos)
            }
        }

        var count = 0
        while (losRefreshIndex < pairs.size && count < LOS_RAYCASTS_PER_TICK) {
            val (a, b) = pairs[losRefreshIndex]
            val key = connectionKey(a, b)
            if (!level.isLoaded(b)) {
                losCache[key] = true
            } else {
                val blocked = !damien.nodeworks.network.NodeConnectionHelper.checkLineOfSight(level, a, b)
                losCache[key] = blocked
            }
            losRefreshIndex++
            count++
        }

        if (losRefreshIndex >= pairs.size) {
            losRefreshIndex = 0
            val validKeys = pairs.map { connectionKey(it.first, it.second) }.toHashSet()
            losCache.keys.retainAll(validKeys)

            reachablePositions.clear()
            for (nodePos in knownNodes) {
                if (!level.isLoaded(nodePos)) continue
                val entity = level.getBlockEntity(nodePos)
                if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) {
                    bfsReachable(level, nodePos)
                }
            }
        }
    }

    private fun bfsReachable(level: net.minecraft.world.level.Level, controllerPos: BlockPos) {
        val queue = ArrayDeque<BlockPos>()
        queue.add(controllerPos)
        reachablePositions.add(controllerPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val connectable = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable ?: continue
            for (targetPos in connectable.getConnections()) {
                if (targetPos in reachablePositions) continue
                if (isConnectionBlocked(pos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue
                reachablePositions.add(targetPos)
                queue.add(targetPos)
            }
        }
    }

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

    private fun render(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val tick = mc.level?.gameTime ?: 0L
        if (tick != losRefreshTick) {
            losRefreshTick = tick
            refreshLosCache(level)
        }

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val pose = poseStack.last()

        val connections = mutableListOf<ConnectionPair>()
        val colorCache = HashMap<BlockPos, Int>()
        val maxDistSq = 64.0 * 64.0

        for (nodePos in knownNodes) {
            val dx = nodePos.x + 0.5 - cameraPos.x
            val dy = nodePos.y + 0.5 - cameraPos.y
            val dz = nodePos.z + 0.5 - cameraPos.z
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue

            if (!level.isLoaded(nodePos)) continue
            val connectable = level.getBlockEntity(nodePos) as? damien.nodeworks.network.Connectable ?: continue

            val color = colorCache.getOrPut(nodePos) {
                val nid = connectable.networkId
                if (nid != null) damien.nodeworks.network.NetworkSettingsRegistry.getColor(nid)
                else DEFAULT_NETWORK_COLOR
            }

            for (targetPos in connectable.getConnections()) {
                if (!isLessThan(nodePos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue

                val blocked = isConnectionBlocked(nodePos, targetPos) ||
                    !isReachable(nodePos) || !isReachable(targetPos)
                connections.add(ConnectionPair(nodePos, targetPos, color, blocked))
            }
        }

        if (beamEffectEnabled) {
            renderConnectionBeams(poseStack, consumers, level, connections)
        } else {
            // Fallback: thin lines
            // 26.1: RenderType.lines() → RenderTypes.LINES (field, not method).
            val lineBuffer = consumers.getBuffer(RenderTypes.LINES)
            for (conn in connections) {
                val from = conn.fromPos.center
                val to = conn.toPos.center
                val lr = (conn.color shr 16) and 0xFF
                val lg = (conn.color shr 8) and 0xFF
                val lb = conn.color and 0xFF
                val dx = (to.x - from.x).toFloat()
                val dy = (to.y - from.y).toFloat()
                val dz = (to.z - from.z).toFloat()
                val len = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val nx = if (len > 0) dx / len else 0f
                val ny = if (len > 0) dy / len else 0f
                val nz = if (len > 0) dz / len else 0f

                lineBuffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
                    .setColor(lr, lg, lb, 200)
                    .setNormal(pose, nx, ny, nz)
                    .setLineWidth(1.0f)

                lineBuffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
                    .setColor(lr, lg, lb, 200)
                    .setNormal(pose, nx, ny, nz)
                    .setLineWidth(1.0f)
            }
        }

        // Draw selection highlight (only if node still exists and player holds wrench)
        val selectedPos = NetworkWrenchItem.clientSelectedNode
        val player = mc.player
        if (selectedPos != null && player != null && player.mainHandItem.`is`(ModItems.NETWORK_WRENCH)) {
            val selectedEntity = level.getBlockEntity(selectedPos) as? damien.nodeworks.network.Connectable
            if (selectedEntity != null) {
                val highlightBuffer = consumers.getBuffer(RenderTypes.LINES)
                val nid = selectedEntity.networkId
                hlColor = if (nid != null) damien.nodeworks.network.NetworkSettingsRegistry.getColor(nid) else DEFAULT_NETWORK_COLOR
                renderSelectionHighlight(poseStack, highlightBuffer, selectedPos)
            } else {
                NetworkWrenchItem.clientSelectedNode = null
            }
        }

        poseStack.popPose()

        // Draw monitor count text (after all line drawing to avoid buffer conflicts)
        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        renderMonitorText(poseStack, consumers, level)
        poseStack.popPose()
    }

    // ========== Billboard Beam Rendering ==========

    private val LASER_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")
    private const val BEAM_WIDTH = 1.0f / 16f
    private const val BEAM_SCROLL_SPEED = 0.8f

    private fun renderConnectionBeams(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        level: net.minecraft.world.level.Level,
        connections: List<ConnectionPair>
    ) {
        val time = (System.currentTimeMillis() % 100000) / 1000f
        val cam = Minecraft.getInstance().gameRenderer.mainCamera
        val camPos = cam.position()

        val pulse = (sin((time * 2.0).toDouble()).toFloat() * 0.3f + 0.7f)

        // 26.1: RenderType.beaconBeam(...) moved to RenderTypes.beaconBeam(...); signature unchanged.
        val opaqueType = RenderTypes.beaconBeam(LASER_TEXTURE, false)
        val translucentType = RenderTypes.beaconBeam(LASER_TEXTURE, true)

        for (conn in connections) {
            val fromPos = conn.fromPos
            val toPos = conn.toPos
            val color = conn.color
            val blocked = conn.blocked

            val nr = (color shr 16) and 0xFF
            val ng = (color shr 8) and 0xFF
            val nb = color and 0xFF
            val colorAlpha = (120 * pulse).toInt().coerceIn(0, 255)

            val fx = fromPos.x + 0.5f; val fy = fromPos.y + 0.5f; val fz = fromPos.z + 0.5f
            val tx = toPos.x + 0.5f; val ty = toPos.y + 0.5f; val tz = toPos.z + 0.5f

            if (blocked) {
                renderBillboardBeam(poseStack, consumers, translucentType, camPos, fx, fy, fz, tx, ty, tz, time, 180, 50, 50, 80, BEAM_WIDTH * 2f)
            } else {
                // White rotating rectangular prism core (half width)
                renderPrismBeam(poseStack, consumers, opaqueType, fx, fy, fz, tx, ty, tz, time, 255, 255, 255, 255, BEAM_WIDTH * 0.5f)
                // Colored pulsing billboard — pulsate both size and opacity
                val sizePulse = (sin((time * 2.0).toDouble()).toFloat() * 0.25f + 1.0f) // 0.75x–1.25x
                renderBillboardBeam(poseStack, consumers, translucentType, camPos, fx, fy, fz, tx, ty, tz, time, nr, ng, nb, colorAlpha, BEAM_WIDTH * 3.5f * sizePulse)
            }
        }
    }

    /** Renders a rectangular prism (4-sided tube) along the beam axis. */
    private fun renderPrismBeam(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        renderType: RenderType,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float
    ) {
        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return

        val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

        val refX: Float; val refY: Float; val refZ: Float
        if (kotlin.math.abs(dirY) < 0.9f) { refX = 0f; refY = 1f; refZ = 0f }
        else { refX = 1f; refY = 0f; refZ = 0f }

        var a1x = dirY * refZ - dirZ * refY
        var a1y = dirZ * refX - dirX * refZ
        var a1z = dirX * refY - dirY * refX
        val a1len = sqrt(a1x * a1x + a1y * a1y + a1z * a1z)
        a1x /= a1len; a1y /= a1len; a1z /= a1len

        var a2x = dirY * a1z - dirZ * a1y
        var a2y = dirZ * a1x - dirX * a1z
        var a2z = dirX * a1y - dirY * a1x
        val a2len = sqrt(a2x * a2x + a2y * a2y + a2z * a2z)
        a2x /= a2len; a2y /= a2len; a2z /= a2len

        val angle = time * 1.0f
        val cosA = kotlin.math.cos(angle); val sinA = sin(angle)
        val r1x = a1x * cosA + a2x * sinA
        val r1y = a1y * cosA + a2y * sinA
        val r1z = a1z * cosA + a2z * sinA
        val r2x = -a1x * sinA + a2x * cosA
        val r2y = -a1y * sinA + a2y * cosA
        val r2z = -a1z * sinA + a2z * cosA
        a1x = r1x; a1y = r1y; a1z = r1z
        a2x = r2x; a2y = r2y; a2z = r2z

        val hw = width / 2f

        val uMax = 5f / 16f
        val uvScroll = time * BEAM_SCROLL_SPEED
        val v0 = uvScroll
        val v1 = uvScroll + len * 0.5f

        val overlay = OverlayTexture.NO_OVERLAY
        val vc = consumers.getBuffer(renderType)
        val pose = poseStack.last()

        val f0x = fromX - a1x * hw - a2x * hw; val f0y = fromY - a1y * hw - a2y * hw; val f0z = fromZ - a1z * hw - a2z * hw
        val f1x = fromX + a1x * hw - a2x * hw; val f1y = fromY + a1y * hw - a2y * hw; val f1z = fromZ + a1z * hw - a2z * hw
        val f2x = fromX + a1x * hw + a2x * hw; val f2y = fromY + a1y * hw + a2y * hw; val f2z = fromZ + a1z * hw + a2z * hw
        val f3x = fromX - a1x * hw + a2x * hw; val f3y = fromY - a1y * hw + a2y * hw; val f3z = fromZ - a1z * hw + a2z * hw
        val t0x = toX - a1x * hw - a2x * hw; val t0y = toY - a1y * hw - a2y * hw; val t0z = toZ - a1z * hw - a2z * hw
        val t1x = toX + a1x * hw - a2x * hw; val t1y = toY + a1y * hw - a2y * hw; val t1z = toZ + a1z * hw - a2z * hw
        val t2x = toX + a1x * hw + a2x * hw; val t2y = toY + a1y * hw + a2y * hw; val t2z = toZ + a1z * hw + a2z * hw
        val t3x = toX - a1x * hw + a2x * hw; val t3y = toY - a1y * hw + a2y * hw; val t3z = toZ - a1z * hw + a2z * hw

        // 4 sides of the prism (front + back = 8 vertices per side)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
    }

    private fun renderBillboardBeam(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        renderType: RenderType,
        camPos: net.minecraft.world.phys.Vec3,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float
    ) {
        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return

        val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

        val midX = (fromX + toX) / 2f
        val midY = (fromY + toY) / 2f
        val midZ = (fromZ + toZ) / 2f
        val toCamX = (camPos.x - midX).toFloat()
        val toCamY = (camPos.y - midY).toFloat()
        val toCamZ = (camPos.z - midZ).toFloat()

        var px = dirY * toCamZ - dirZ * toCamY
        var py = dirZ * toCamX - dirX * toCamZ
        var pz = dirX * toCamY - dirY * toCamX
        val plen = sqrt(px * px + py * py + pz * pz)
        if (plen < 0.001f) return
        val hw = width / 2f
        px = px / plen * hw; py = py / plen * hw; pz = pz / plen * hw

        val uMax = 5f / 16f
        val uvScroll = time * BEAM_SCROLL_SPEED
        val v0 = uvScroll
        val v1 = uvScroll + len * 0.5f

        val overlay = OverlayTexture.NO_OVERLAY
        val vc = consumers.getBuffer(renderType)
        val pose = poseStack.last()

        // Front face
        vc.addVertex(pose, fromX - px, fromY - py, fromZ - pz)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX + px, fromY + py, fromZ + pz)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + px, toY + py, toZ + pz)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - px, toY - py, toZ - pz)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)

        // Back face
        vc.addVertex(pose, fromX + px, fromY + py, fromZ + pz)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX - px, fromY - py, fromZ - pz)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - px, toY - py, toZ - pz)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + px, toY + py, toZ + pz)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
    }

    private fun renderSelectionHighlight(poseStack: PoseStack, buffer: VertexConsumer, pos: BlockPos) {
        val x = pos.x.toFloat()
        val y = pos.y.toFloat()
        val z = pos.z.toFloat()
        val pose = poseStack.last()

        val x0 = x + MIN
        val y0 = y + MIN
        val z0 = z + MIN
        val x1 = x + MAX
        val y1 = y + MAX
        val z1 = z + MAX

        // 12 edges of a box
        drawLine(buffer, pose, x0, y0, z0, x1, y0, z0)
        drawLine(buffer, pose, x1, y0, z0, x1, y0, z1)
        drawLine(buffer, pose, x1, y0, z1, x0, y0, z1)
        drawLine(buffer, pose, x0, y0, z1, x0, y0, z0)
        drawLine(buffer, pose, x0, y1, z0, x1, y1, z0)
        drawLine(buffer, pose, x1, y1, z0, x1, y1, z1)
        drawLine(buffer, pose, x1, y1, z1, x0, y1, z1)
        drawLine(buffer, pose, x0, y1, z1, x0, y1, z0)
        drawLine(buffer, pose, x0, y0, z0, x0, y1, z0)
        drawLine(buffer, pose, x1, y0, z0, x1, y1, z0)
        drawLine(buffer, pose, x1, y0, z1, x1, y1, z1)
        drawLine(buffer, pose, x0, y0, z1, x0, y1, z1)
    }

    private var hlColor = DEFAULT_NETWORK_COLOR

    private fun drawLine(
        buffer: VertexConsumer, pose: PoseStack.Pose,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float
    ) {
        val lr = (hlColor shr 16) and 0xFF
        val lg = (hlColor shr 8) and 0xFF
        val lb = hlColor and 0xFF
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val len = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        val nx = if (len > 0) dx / len else 0f
        val ny = if (len > 0) dy / len else 0f
        val nz = if (len > 0) dz / len else 1f

        // 26.1: RenderTypes.LINES vertex format includes a per-vertex LineWidth element.
        //  Omitting setLineWidth() crashes BufferBuilder with "Missing elements in vertex:
        //  LineWidth". Pattern copied from vanilla ShapeRenderer.renderLineBox.
        buffer.addVertex(pose, x0, y0, z0)
            .setColor(lr, lg, lb, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(1.0f)

        buffer.addVertex(pose, x1, y1, z1)
            .setColor(lr, lg, lb, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(1.0f)
    }

    private fun renderMonitorText(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        level: net.minecraft.world.level.Level
    ) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val bufferSource = mc.renderBuffers().bufferSource()

        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val entity = level.getBlockEntity(nodePos) as? NodeBlockEntity ?: continue

            for (face in entity.getMonitorFaces()) {
                val monitor = entity.getMonitor(face) ?: continue
                val itemId = monitor.trackedItemId ?: continue

                val countText = formatMonitorCount(monitor.displayCount)
                val textWidth = font.width(countText)

                poseStack.pushPose()

                poseStack.translate(
                    nodePos.x.toDouble() + 0.5,
                    nodePos.y.toDouble() + 0.5,
                    nodePos.z.toDouble() + 0.5
                )

                when (face) {
                    Direction.SOUTH -> {}
                    Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
                    Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
                    Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
                    Direction.DOWN -> poseStack.mulPose(Quaternionf().rotateX((-Math.PI / 2).toFloat()))
                    Direction.UP -> poseStack.mulPose(Quaternionf().rotateX((Math.PI / 2).toFloat()))
                }

                poseStack.translate(0.0, -0.18, 0.502)
                poseStack.scale(0.01f, -0.01f, 0.01f)

                font.drawInBatch(
                    countText,
                    (-textWidth / 2).toFloat(),
                    0f,
                    0xFFFFFFFF.toInt(),
                    true,
                    poseStack.last().pose(),
                    bufferSource,
                    Font.DisplayMode.POLYGON_OFFSET,
                    0,
                    15728880
                )

                poseStack.popPose()
            }
        }

        bufferSource.endBatch()
    }

    private fun formatMonitorCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    // ========== Diagnostic Pin Highlight ==========

    /**
     * Draws a pulsing cyan outline around the pinned block whenever the Diagnostic
     * Tool is held.
     *
     * 26.1 rewrite: the pre-migration version rendered a pulsing filled overlay by
     * walking the block's BakedQuads and drawing them through an immediate-mode
     * `RenderSystem.setShader` + `BufferUploader.drawWithShader` path. Both of
     * those APIs are gone in MC 26.1 (replaced by RegisterRenderPipelinesEvent +
     * RenderType dispatch), and BakedQuad switched from `quad.vertices: int[]`
     * to `position(i)/packedUV(i)` getters. Rather than register a custom
     * RenderPipeline just for this effect, we express the "look at this block"
     * hint with a pulsing multi-line cube outline — same visual language as the
     * Network Wrench selection highlight, scaled to the block's exact AABB, and
     * tinted cyan so it reads as distinct from a wrench selection.
     */
    fun renderPinHighlight(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        val pos = pinnedBlock ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return

        val mainItem = player.mainHandItem.item
        val offItem = player.offhandItem.item
        if (mainItem !is damien.nodeworks.item.DiagnosticToolItem &&
            offItem !is damien.nodeworks.item.DiagnosticToolItem) return

        val blockState = level.getBlockState(pos)
        if (blockState.isAir) return

        // Pulse: 0.6 → 1.0 at ~0.5 Hz. Drives both brightness and outline thickness
        // so the highlight feels "alive" without a shader.
        val time = (System.currentTimeMillis() % 2000) / 2000f
        val pulse = (kotlin.math.sin(time * Math.PI * 2).toFloat() * 0.2f + 0.8f)

        poseStack.pushPose()
        poseStack.translate(
            (pos.x - cameraPos.x).toFloat(),
            (pos.y - cameraPos.y).toFloat(),
            (pos.z - cameraPos.z).toFloat()
        )

        val pose = poseStack.last()
        val buffer = consumers.getBuffer(RenderTypes.LINES)

        // Cyan (0.6, 0.9, 1.0) modulated by pulse.
        val r = (0.6f * pulse * 255).toInt().coerceIn(0, 255)
        val g = (0.9f * pulse * 255).toInt().coerceIn(0, 255)
        val b = (255 * pulse).toInt().coerceIn(0, 255)

        // Slight inset so the outline sits on the block surface rather than z-fighting
        // with adjacent blocks, plus a fractional outward breathe from the pulse.
        val inset = 0.002f
        val breathe = pulse * 0.015f
        val x0 = -breathe + inset
        val y0 = -breathe + inset
        val z0 = -breathe + inset
        val x1 = 1f + breathe - inset
        val y1 = 1f + breathe - inset
        val z1 = 1f + breathe - inset

        drawPinEdge(buffer, pose, x0, y0, z0, x1, y0, z0, r, g, b)
        drawPinEdge(buffer, pose, x1, y0, z0, x1, y0, z1, r, g, b)
        drawPinEdge(buffer, pose, x1, y0, z1, x0, y0, z1, r, g, b)
        drawPinEdge(buffer, pose, x0, y0, z1, x0, y0, z0, r, g, b)
        drawPinEdge(buffer, pose, x0, y1, z0, x1, y1, z0, r, g, b)
        drawPinEdge(buffer, pose, x1, y1, z0, x1, y1, z1, r, g, b)
        drawPinEdge(buffer, pose, x1, y1, z1, x0, y1, z1, r, g, b)
        drawPinEdge(buffer, pose, x0, y1, z1, x0, y1, z0, r, g, b)
        drawPinEdge(buffer, pose, x0, y0, z0, x0, y1, z0, r, g, b)
        drawPinEdge(buffer, pose, x1, y0, z0, x1, y1, z0, r, g, b)
        drawPinEdge(buffer, pose, x1, y0, z1, x1, y1, z1, r, g, b)
        drawPinEdge(buffer, pose, x0, y0, z1, x0, y1, z1, r, g, b)

        poseStack.popPose()
    }

    private fun drawPinEdge(
        buffer: VertexConsumer, pose: PoseStack.Pose,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Int, g: Int, b: Int
    ) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val nx = if (len > 0) dx / len else 0f
        val ny = if (len > 0) dy / len else 0f
        val nz = if (len > 0) dz / len else 1f

        buffer.addVertex(pose, x0, y0, z0)
            .setColor(r, g, b, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(2.5f)
        buffer.addVertex(pose, x1, y1, z1)
            .setColor(r, g, b, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(2.5f)
    }
}
