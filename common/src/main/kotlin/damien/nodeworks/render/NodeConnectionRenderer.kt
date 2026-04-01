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
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
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

    /** How often to refresh line-of-sight cache (ticks). */
    private const val LOS_REFRESH_INTERVAL = 10

    // Line-of-sight cache: (min(a,b), max(a,b)) → blocked?
    private val losCache = HashMap<Long, Boolean>()
    private var losRefreshTick = 0L

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

    /** Convenience: find the network color for a position. */
    fun findNetworkColor(level: net.minecraft.world.level.Level?, startPos: BlockPos): Int {
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
        // Deterministic key: hash both positions into a single long
        val (lo, hi) = if (isLessThan(a, b)) a to b else b to a
        return lo.asLong() xor (hi.asLong() * 31)
    }

    /** Refresh the LOS cache and reachability set. Called from render() every N ticks. */
    private fun refreshLosCache(level: net.minecraft.world.level.Level) {
        losCache.clear()

        // Check LOS for all connections
        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val connectable = level.getBlockEntity(nodePos) as? damien.nodeworks.network.Connectable ?: continue
            for (targetPos in connectable.getConnections()) {
                val key = connectionKey(nodePos, targetPos)
                if (losCache.containsKey(key)) continue
                if (!level.isLoaded(targetPos)) { losCache[key] = true; continue }
                val blocked = !damien.nodeworks.network.NodeConnectionHelper.checkLineOfSight(level, nodePos, targetPos)
                losCache[key] = blocked
            }
        }

        // BFS from all controllers through unblocked connections
        reachablePositions.clear()
        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val entity = level.getBlockEntity(nodePos)
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) {
                bfsReachable(level, nodePos)
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
        consumers: net.minecraft.client.renderer.MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        // Refresh LOS cache periodically
        val tick = mc.level?.gameTime ?: 0L
        if (tick - losRefreshTick >= LOS_REFRESH_INTERVAL) {
            losRefreshTick = tick
            refreshLosCache(level)
        }

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val pose = poseStack.last()

        // Collect all connection pairs with their network color
        val connections = mutableListOf<ConnectionPair>()

        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val connectable = level.getBlockEntity(nodePos) as? damien.nodeworks.network.Connectable ?: continue

            for (targetPos in connectable.getConnections()) {
                if (!isLessThan(nodePos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue
                val color = findNetworkColor(level, nodePos)
                val blocked = isConnectionBlocked(nodePos, targetPos) ||
                    !isReachable(nodePos) || !isReachable(targetPos)
                connections.add(ConnectionPair(nodePos, targetPos, color, blocked))
            }
        }

        if (beamEffectEnabled) {
            renderConnectionBeams(poseStack, consumers, level, connections)
        } else {
            // Fallback: thin lines
            val lineBuffer = consumers.getBuffer(RenderType.lines())
            for (conn in connections) {
                val from = conn.fromPos.center
                val to = conn.toPos.center
                val lr = (conn.color shr 16) and 0xFF
                val lg = (conn.color shr 8) and 0xFF
                val lb = conn.color and 0xFF
                val dx = (to.x - from.x).toFloat()
                val dy = (to.y - from.y).toFloat()
                val dz = (to.z - from.z).toFloat()
                val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val nx = if (len > 0) dx / len else 0f
                val ny = if (len > 0) dy / len else 0f
                val nz = if (len > 0) dz / len else 0f

                lineBuffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
                    .setColor(lr, lg, lb, 200)
                    .setNormal(pose, nx, ny, nz)

                lineBuffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
                    .setColor(lr, lg, lb, 200)
                    .setNormal(pose, nx, ny, nz)
            }
        }

        // Draw selection highlight (only if node still exists and player holds wrench)
        val selectedPos = NetworkWrenchItem.clientSelectedNode
        val player = mc.player
        if (selectedPos != null && player != null && player.mainHandItem.`is`(ModItems.NETWORK_WRENCH)) {
            if (level.getBlockEntity(selectedPos) is damien.nodeworks.network.Connectable) {
                val highlightBuffer = consumers.getBuffer(RenderType.lines())
                hlColor = findNetworkColor(level, selectedPos)
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

    private val LASER_TEXTURE = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")
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
        val camPos = cam.position

        val pulse = (sin((time * 2.0).toDouble()).toFloat() * 0.3f + 0.7f)

        val opaqueType = RenderType.beaconBeam(LASER_TEXTURE, false)
        val translucentType = RenderType.beaconBeam(LASER_TEXTURE, true)

        for (conn in connections) {
            val fromPos = conn.fromPos
            val toPos = conn.toPos
            val color = conn.color
            val blocked = conn.blocked

            val nr = (color shr 16) and 0xFF
            val ng = (color shr 8) and 0xFF
            val nb = color and 0xFF
            val colorAlpha = (120 * pulse).toInt().coerceIn(0, 255)

            // Raycast from source to target, offsetting past source block
            val fromVec = net.minecraft.world.phys.Vec3.atCenterOf(fromPos)
            val toVec = net.minecraft.world.phys.Vec3.atCenterOf(toPos)
            val dir = toVec.subtract(fromVec).normalize()
            val offsetFrom = fromVec.add(dir.scale(0.55))
            val hitResult = level.clip(
                net.minecraft.world.level.ClipContext(
                    offsetFrom, toVec,
                    net.minecraft.world.level.ClipContext.Block.VISUAL,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()
                )
            )
            val endVec = if (hitResult.type != net.minecraft.world.phys.HitResult.Type.MISS) {
                hitResult.location
            } else {
                toVec
            }

            // World-space endpoints
            val fx = fromVec.x.toFloat(); val fy = fromVec.y.toFloat(); val fz = fromVec.z.toFloat()
            val tx = endVec.x.toFloat(); val ty = endVec.y.toFloat(); val tz = endVec.z.toFloat()

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

        // Find two perpendicular axes
        val refX: Float; val refY: Float; val refZ: Float
        if (kotlin.math.abs(dirY) < 0.9f) { refX = 0f; refY = 1f; refZ = 0f }
        else { refX = 1f; refY = 0f; refZ = 0f }

        // axis1 = normalize(cross(dir, ref))
        var a1x = dirY * refZ - dirZ * refY
        var a1y = dirZ * refX - dirX * refZ
        var a1z = dirX * refY - dirY * refX
        val a1len = sqrt(a1x * a1x + a1y * a1y + a1z * a1z)
        a1x /= a1len; a1y /= a1len; a1z /= a1len

        // axis2 = normalize(cross(dir, axis1))
        var a2x = dirY * a1z - dirZ * a1y
        var a2y = dirZ * a1x - dirX * a1z
        var a2z = dirX * a1y - dirY * a1x
        val a2len = sqrt(a2x * a2x + a2y * a2y + a2z * a2z)
        a2x /= a2len; a2y /= a2len; a2z /= a2len

        // Rotate axes around beam direction
        val angle = time * 1.0f // 1 radian/sec
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

        // 4 corners at each end: ±axis1 ± axis2
        // c0 = -a1 -a2, c1 = +a1 -a2, c2 = +a1 +a2, c3 = -a1 +a2
        fun corner(baseX: Float, baseY: Float, baseZ: Float, s1: Float, s2: Float): Triple<Float, Float, Float> {
            return Triple(
                baseX + a1x * hw * s1 + a2x * hw * s2,
                baseY + a1y * hw * s1 + a2y * hw * s2,
                baseZ + a1z * hw * s1 + a2z * hw * s2
            )
        }

        val f0 = corner(fromX, fromY, fromZ, -1f, -1f)
        val f1 = corner(fromX, fromY, fromZ,  1f, -1f)
        val f2 = corner(fromX, fromY, fromZ,  1f,  1f)
        val f3 = corner(fromX, fromY, fromZ, -1f,  1f)
        val t0 = corner(toX, toY, toZ, -1f, -1f)
        val t1 = corner(toX, toY, toZ,  1f, -1f)
        val t2 = corner(toX, toY, toZ,  1f,  1f)
        val t3 = corner(toX, toY, toZ, -1f,  1f)

        // 4 sides of the prism (each a quad, rendered double-sided)
        fun quad(
            ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float, dx2: Float, dy2: Float, dz2: Float
        ) {
            // Front
            vc.addVertex(pose, ax, ay, az).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, bx, by, bz).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, cx, cy, cz).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, dx2, dy2, dz2).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            // Back
            vc.addVertex(pose, bx, by, bz).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, ax, ay, az).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, dx2, dy2, dz2).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, cx, cy, cz).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        }

        // Side 1: f0→f1 to t0→t1
        quad(f0.first, f0.second, f0.third, f1.first, f1.second, f1.third, t1.first, t1.second, t1.third, t0.first, t0.second, t0.third)
        // Side 2: f1→f2 to t1→t2
        quad(f1.first, f1.second, f1.third, f2.first, f2.second, f2.third, t2.first, t2.second, t2.third, t1.first, t1.second, t1.third)
        // Side 3: f2→f3 to t2→t3
        quad(f2.first, f2.second, f2.third, f3.first, f3.second, f3.third, t3.first, t3.second, t3.third, t2.first, t2.second, t2.third)
        // Side 4: f3→f0 to t3→t0
        quad(f3.first, f3.second, f3.third, f0.first, f0.second, f0.third, t0.first, t0.second, t0.third, t3.first, t3.second, t3.third)
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

        // Camera-to-beam-midpoint for billboarding
        val midX = (fromX + toX) / 2f
        val midY = (fromY + toY) / 2f
        val midZ = (fromZ + toZ) / 2f
        val toCamX = (camPos.x - midX).toFloat()
        val toCamY = (camPos.y - midY).toFloat()
        val toCamZ = (camPos.z - midZ).toFloat()

        // Perpendicular = cross(beamDir, toCam)
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
        // Bottom face
        drawLine(buffer, pose, x0, y0, z0, x1, y0, z0)
        drawLine(buffer, pose, x1, y0, z0, x1, y0, z1)
        drawLine(buffer, pose, x1, y0, z1, x0, y0, z1)
        drawLine(buffer, pose, x0, y0, z1, x0, y0, z0)
        // Top face
        drawLine(buffer, pose, x0, y1, z0, x1, y1, z0)
        drawLine(buffer, pose, x1, y1, z0, x1, y1, z1)
        drawLine(buffer, pose, x1, y1, z1, x0, y1, z1)
        drawLine(buffer, pose, x0, y1, z1, x0, y1, z0)
        // Vertical edges
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
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        val nx = if (len > 0) dx / len else 0f
        val ny = if (len > 0) dy / len else 0f
        val nz = if (len > 0) dz / len else 1f

        buffer.addVertex(pose, x0, y0, z0)
            .setColor(lr, lg, lb, 255)
            .setNormal(pose, nx, ny, nz)

        buffer.addVertex(pose, x1, y1, z1)
            .setColor(lr, lg, lb, 255)
            .setNormal(pose, nx, ny, nz)
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

                // Position at block center
                poseStack.translate(
                    nodePos.x.toDouble() + 0.5,
                    nodePos.y.toDouble() + 0.5,
                    nodePos.z.toDouble() + 0.5
                )

                // Rotate to face direction (same as MonitorRenderer)
                when (face) {
                    Direction.SOUTH -> {}
                    Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
                    Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
                    Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
                    Direction.DOWN -> poseStack.mulPose(Quaternionf().rotateX((-Math.PI / 2).toFloat()))
                    Direction.UP -> poseStack.mulPose(Quaternionf().rotateX((Math.PI / 2).toFloat()))
                }

                // Push to surface, below the item
                poseStack.translate(0.0, -0.18, 0.502)
                // Scale and flip Y for text
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

    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }

    // ========== Diagnostic Pin Highlight ==========

    /** The currently pinned block position, or null. Global across all networks. */
    var pinnedBlock: BlockPos? = null

    fun renderPinHighlight(poseStack: PoseStack, consumers: MultiBufferSource, cameraPos: net.minecraft.world.phys.Vec3) {
        val pos = pinnedBlock ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return

        val mainItem = player.mainHandItem.item
        val offItem = player.offhandItem.item
        if (mainItem !is damien.nodeworks.item.DiagnosticToolItem && offItem !is damien.nodeworks.item.DiagnosticToolItem) return

        val blockState = level.getBlockState(pos)
        if (blockState.isAir) return

        // Flush any pending MC render batches first
        if (consumers is MultiBufferSource.BufferSource) consumers.endBatch()

        val model = mc.blockRenderer.getBlockModel(blockState)
        val random = net.minecraft.util.RandomSource.create()

        val time = (System.currentTimeMillis() % 2000) / 2000f
        val pulse = (kotlin.math.sin(time * Math.PI * 2).toFloat() * 0.15f + 0.75f)

        poseStack.pushPose()
        val x = pos.x.toDouble() - cameraPos.x
        val y = pos.y.toDouble() - cameraPos.y
        val z = pos.z.toDouble() - cameraPos.z
        poseStack.translate(x, y, z)

        // Pulse scale centered on block (1.01–1.07)
        val scalePulse = (kotlin.math.sin(time * Math.PI * 2).toFloat() * 0.03f + 1.04f)
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.scale(scalePulse, scalePulse, scalePulse)
        poseStack.translate(-0.5, -0.5, -0.5)

        val matrix = poseStack.last().pose()

        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest()
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader)
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(0.6f, 0.9f, 1.0f, pulse)

        val tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance()
        val buf = tesselator.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX)

        var quadCount = 0
        val directions: List<net.minecraft.core.Direction?> = Direction.entries + listOf(null)
        for (dir in directions) {
            random.setSeed(42L)
            for (quad in model.getQuads(blockState, dir, random)) {
                val verts = quad.vertices
                for (i in 0 until 4) {
                    val off = i * 8
                    val vx = Float.fromBits(verts[off])
                    val vy = Float.fromBits(verts[off + 1])
                    val vz = Float.fromBits(verts[off + 2])
                    val u = Float.fromBits(verts[off + 4])
                    val v = Float.fromBits(verts[off + 5])
                    buf.addVertex(matrix, vx, vy, vz).setUv(u, v)
                }
                quadCount++
            }
        }

        if (quadCount > 0) {
            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buf.buildOrThrow())
        }

        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest()
        com.mojang.blaze3d.systems.RenderSystem.disableBlend()

        poseStack.popPose()
    }
}
