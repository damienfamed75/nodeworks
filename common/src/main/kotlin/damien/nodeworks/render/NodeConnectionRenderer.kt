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
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import org.joml.Quaternionf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionRenderer {

    private val knownNodes: MutableSet<BlockPos> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Network ids pending tint-cache invalidation, drained once per render
     *  frame by the runnable [flushScheduled] guards. */
    private val pendingDirtyNetworks: MutableSet<java.util.UUID> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Default network color (RGB, no alpha). Used as fallback when no controller is found. */
    const val DEFAULT_NETWORK_COLOR = 0x888888

    /** Global tracker, any Connectable block entity can register/unregister here.
     *  Gated on [Level.isClientSide]: the [knownNodes] set drives the client
     *  reachability render, server-side BEs in single-player or on a dedicated
     *  server have no need to participate (and writing the same global map
     *  from the integrated-server thread races the render thread). */
    fun trackConnectable(level: net.minecraft.world.level.Level?, pos: BlockPos, loaded: Boolean) {
        if (level == null || !level.isClientSide) return
        if (loaded) knownNodes.add(pos) else knownNodes.remove(pos)
    }

    /**
     * Walks the connection graph to find the NetworkController for a given position.
     * BFS capped at 32 hops. Returns null if no controller found.
     */
    fun findController(
        level: net.minecraft.world.level.Level?,
        startPos: BlockPos
    ): damien.nodeworks.block.entity.NetworkControllerBlockEntity? {
        if (level == null) return null
        val startEntity = level.getBlockEntity(startPos)
        if (startEntity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return startEntity
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        visited.add(startPos)
        val startConnectable = startEntity as? damien.nodeworks.network.Connectable ?: return null
        for (conn in startConnectable.getConnections()) {
            if (visited.add(conn)) queue.add(conn)
        }
        // Seed face-adjacent Connectable neighbours, blocks that touch each
        // other share a network through pure adjacency.
        enqueueAdjacentConnectables(level, startPos, startEntity, visited, queue)
        while (queue.isNotEmpty() && visited.size < 32) {
            val pos = queue.removeFirst()
            val entity = level.getBlockEntity(pos) ?: continue
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return entity
            val connectable = entity as? damien.nodeworks.network.Connectable ?: continue
            for (conn in connectable.getConnections()) {
                if (visited.add(conn)) queue.add(conn)
            }
            enqueueAdjacentConnectables(level, pos, entity, visited, queue)
        }
        return null
    }

    /** Enqueue face-adjacent Connectable BEs. No-op when [entity] isn't a Connectable
     *  or either endpoint opts out via [Connectable.usesAdjacency]. */
    private fun enqueueAdjacentConnectables(
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        entity: net.minecraft.world.level.block.entity.BlockEntity?,
        visited: MutableSet<BlockPos>,
        queue: ArrayDeque<BlockPos>,
    ) {
        if (entity !is damien.nodeworks.network.Connectable) return
        if (!entity.usesAdjacency()) return
        for (dir in Direction.entries) {
            val neighbor = pos.relative(dir)
            if (neighbor in visited) continue
            if (!level.isLoaded(neighbor)) continue
            val neighborBe = level.getBlockEntity(neighbor) as? damien.nodeworks.network.Connectable ?: continue
            if (!neighborBe.usesAdjacency()) continue
            visited.add(neighbor)
            queue.add(neighbor)
        }
    }

    /** Network colour for the Connectable BE at [startPos]. Defers to
     *  [Connectable.networkColor] so screens and renderers all key off the same
     *  state propagate writes. */
    fun findNetworkColor(level: net.minecraft.world.level.Level?, startPos: BlockPos): Int {
        val connectable = level?.getBlockEntity(startPos) as? damien.nodeworks.network.Connectable
        return connectable?.networkColor() ?: DEFAULT_NETWORK_COLOR
    }

    /** Whether [pos] is part of an active network. Same answer as
     *  `Connectable.networkId != null`. */
    fun isReachable(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val be = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable ?: return false
        return be.networkId != null
    }

    /** The currently pinned block position (shown highlighted by the Diagnostic Tool), or null. */
    var pinnedBlock: BlockPos? = null

    fun register() {
        NodeBlockEntity.nodeTracker = NodeBlockEntity.NodeTracker { pos, loaded ->
            // [register] only fires client-side (NeoForgeClientSetup), so the
            // tracker callback runs on the client; pass the client's level so
            // the gate inside [trackConnectable] sees `isClientSide == true`.
            trackConnectable(Minecraft.getInstance().level, pos, loaded)
        }

        // Invalidate the BlockTintCache via setSectionDirty for every Connectable
        // belonging to a network whose settings just changed. Coalesced through
        // [pendingDirtyNetworks] + [flushDirtyNetworks] so a controller attach
        // doesn't trigger one flush per Node BE sync. Disconnected BEs (null id)
        // all render the default grey from the same fallback so no flush needed.
        damien.nodeworks.network.NetworkSettingsRegistry.onChanged = label@{ networkId ->
            if (networkId == null) return@label
            pendingDirtyNetworks.add(networkId)
            if (flushScheduled.compareAndSet(false, true)) {
                Minecraft.getInstance().execute { flushDirtyNetworks() }
            }
        }

        PlatformServices.clientEvents.onWorldRender { poseStack, consumers, cameraPos ->
            if (poseStack != null && consumers != null) {
                render(poseStack, consumers, cameraPos)
                renderPinHighlight(poseStack, consumers, cameraPos)
                renderSelectionThroughWalls(poseStack, consumers, cameraPos)
            }
        }
    }

    /** One-pass walk of [knownNodes] that calls `setSectionDirty` for every
     *  section containing a BE on a pending-dirty network. Section-level dedup
     *  collapses a many-node single-network update to a handful of re-renders.
     *  Runs on the render thread via [Minecraft.execute]. */
    private fun flushDirtyNetworks() {
        flushScheduled.set(false)
        val networks = HashSet(pendingDirtyNetworks)
        pendingDirtyNetworks.clear()
        if (networks.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val renderer = mc.levelRenderer
        val dirtied = java.util.HashSet<Long>()
        for (pos in knownNodes) {
            if (!level.isLoaded(pos)) continue
            val be = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable ?: continue
            val id = be.networkId ?: continue
            if (id !in networks) continue
            val sx = net.minecraft.core.SectionPos.blockToSectionCoord(pos.x)
            val sy = net.minecraft.core.SectionPos.blockToSectionCoord(pos.y)
            val sz = net.minecraft.core.SectionPos.blockToSectionCoord(pos.z)
            val key = net.minecraft.core.SectionPos.asLong(sx, sy, sz)
            if (!dirtied.add(key)) continue
            renderer.setSectionDirty(sx, sy, sz)
        }
    }

    /** Through-walls halo on the wrench's pending Focus Node selection. Reads
     *  the selected pos from [NetworkWrenchItem.clientSelectedPos], self-clears
     *  the selection field if the underlying block was destroyed mid-selection. */
    fun renderSelectionThroughWalls(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3,
    ) {
        val pos = NetworkWrenchItem.clientSelectedPos ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        // Only show the halo while the wrench is in hand. The user might have
        // started a selection then swapped tools, the rendered halo confuses
        // more than it helps.
        if (!player.mainHandItem.`is`(ModItems.NETWORK_WRENCH)) return

        val be = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable
        if (be == null) {
            NetworkWrenchItem.clientSelectedPos = null
            return
        }
        val color = be.networkId?.let { damien.nodeworks.network.NetworkSettingsRegistry.getColor(it) }
            ?: DEFAULT_NETWORK_COLOR
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        submitThroughWallsBox(poseStack, consumers, cameraPos, level, pos, r, g, b)
    }

    private fun render(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        // Monitor count text, kept here because it wants camera-relative billboarding
        // over the entire knownNodes set, not a per-BER pass.
        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        renderMonitorText(poseStack, consumers, level, cameraPos)
        poseStack.popPose()
    }

    private fun renderMonitorText(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        level: net.minecraft.world.level.Level,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val bufferSource = mc.renderBuffers().bufferSource()

        // Same 64-block distance cull so text work scales with nearby monitors,
        // not total monitors on the network.
        val maxDistSq = 64.0 * 64.0

        for (pos in knownNodes) {
            val dx = pos.x + 0.5 - cameraPos.x
            val dy = pos.y + 0.5 - cameraPos.y
            val dz = pos.z + 0.5 - cameraPos.z
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue

            if (!level.isLoaded(pos)) continue
            val be = level.getBlockEntity(pos) as? damien.nodeworks.block.entity.MonitorBlockEntity ?: continue
            if (be.trackedItemId == null) continue

            val facing = be.blockState.getValue(damien.nodeworks.block.MonitorBlock.FACING)
            val countText = formatMonitorCount(be.displayCount)
            val textWidth = font.width(countText)

            poseStack.pushPose()
            poseStack.translate(
                pos.x.toDouble() + 0.5,
                pos.y.toDouble() + 0.5,
                pos.z.toDouble() + 0.5
            )

            when (facing) {
                Direction.SOUTH -> {}
                Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
                Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
                Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
                else -> {}
            }

            poseStack.translate(0.0, -0.22, 0.502)
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
                RenderUtils.FULL_BRIGHT
            )

            poseStack.popPose()
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

    /** Through-walls highlight on the diagnostic-tool's pinned block. */
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
            offItem !is damien.nodeworks.item.DiagnosticToolItem
        ) return

        val be = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable
        val color = be?.networkId?.let { damien.nodeworks.network.NetworkSettingsRegistry.getColor(it) }
            ?: DEFAULT_NETWORK_COLOR
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        submitThroughWallsBox(poseStack, consumers, cameraPos, level, pos, r, g, b)
    }

    /** Pulsing through-walls box around [pos] tinted [r,g,b]. */
    private fun submitThroughWallsBox(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3,
        level: Level,
        pos: BlockPos,
        r: Int, g: Int, b: Int,
    ) {
        val blockState = level.getBlockState(pos)
        if (blockState.isAir) return

        val shape = blockState.getShape(level, pos)
        val aabb = if (shape.isEmpty) net.minecraft.world.phys.AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
            else shape.bounds()

        val time = (System.currentTimeMillis() % 2000) / 2000f
        val pulse = (kotlin.math.sin(time * Math.PI * 2).toFloat() * 0.15f + 0.85f)

        poseStack.pushPose()
        poseStack.translate(
            (pos.x - cameraPos.x).toFloat(),
            (pos.y - cameraPos.y).toFloat(),
            (pos.z - cameraPos.z).toFloat()
        )
        val scale = 1.0f + pulse * 0.05f
        poseStack.translate(0.5f, 0.5f, 0.5f)
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-0.5f, -0.5f, -0.5f)
        val pose = poseStack.last()

        val pr = (r * pulse).toInt().coerceIn(0, 255)
        val pg = (g * pulse).toInt().coerceIn(0, 255)
        val pb = (b * pulse).toInt().coerceIn(0, 255)
        val buffer = consumers.getBuffer(PinHighlightRenderType.THROUGH_WALLS)

        emitBox(
            buffer, pose,
            aabb.minX.toFloat(), aabb.minY.toFloat(), aabb.minZ.toFloat(),
            aabb.maxX.toFloat(), aabb.maxY.toFloat(), aabb.maxZ.toFloat(),
            pr, pg, pb, 255,
        )

        poseStack.popPose()
    }

    private fun emitBox(
        buffer: VertexConsumer, pose: PoseStack.Pose,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        emitQuad(buffer, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a)
        emitQuad(buffer, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a)
        emitQuad(buffer, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a)
        emitQuad(buffer, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a)
        emitQuad(buffer, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a)
        emitQuad(buffer, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a)
    }

    private fun emitQuad(
        buffer: VertexConsumer, pose: PoseStack.Pose,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        buffer.addVertex(pose, ax, ay, az).setColor(r, g, b, a)
        buffer.addVertex(pose, bx, by, bz).setColor(r, g, b, a)
        buffer.addVertex(pose, cx, cy, cz).setColor(r, g, b, a)
        buffer.addVertex(pose, dx, dy, dz).setColor(r, g, b, a)
    }
}
