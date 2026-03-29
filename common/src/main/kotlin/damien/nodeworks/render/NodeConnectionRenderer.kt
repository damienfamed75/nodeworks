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
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionRenderer {

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

    private val LASER_TEXTURE = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

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
        data class Connection(val from: net.minecraft.world.phys.Vec3, val to: net.minecraft.world.phys.Vec3, val color: Int)

        val connections = mutableListOf<Connection>()

        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val connectable = level.getBlockEntity(nodePos) as? damien.nodeworks.network.Connectable ?: continue

            for (targetPos in connectable.getConnections()) {
                if (!isLessThan(nodePos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue
                val color = findNetworkColor(level, nodePos)
                connections.add(Connection(nodePos.getCenter(), targetPos.getCenter(), color))
            }
        }

        if (beamEffectEnabled) {
            // Beacon-style beams are rendered by MonitorRenderer (BlockEntityRenderer)
            // which has access to SubmitNodeCollector for proper emissive rendering.
            // Nothing to do here.
        } else {
            // Fallback: thin lines
            val lineBuffer = consumers.getBuffer(RenderType.lines())
            for (conn in connections) {
                val from = conn.from
                val to = conn.to
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
}
