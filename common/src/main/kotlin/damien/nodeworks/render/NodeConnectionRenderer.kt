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
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.joml.Quaternionf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object NodeConnectionRenderer {

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
        // Seed cluster-adjacency edges from the start position, same reasoning as
        // the server BFS — trailing cluster storages have no laser connections so
        // they'd otherwise be unreachable from here.
        enqueueClusterNeighbors(level, startPos, startEntity, visited, queue)
        while (queue.isNotEmpty() && visited.size < 32) {
            val pos = queue.removeFirst()
            val entity = level.getBlockEntity(pos) ?: continue
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return entity
            val connectable = entity as? damien.nodeworks.network.Connectable ?: continue
            for (conn in connectable.getConnections()) {
                if (!isConnectionBlocked(pos, conn) && visited.add(conn)) queue.add(conn)
            }
            enqueueClusterNeighbors(level, pos, entity, visited, queue)
        }
        return null
    }

    /** Enqueue face-adjacent cluster siblings of a storage BE. No-op for non-storage
     *  BEs. Used by both [bfsReachable] and [findController] to walk the
     *  adjacency-based connections that Instruction/Processing Storage blocks form. */
    private fun enqueueClusterNeighbors(
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        entity: net.minecraft.world.level.block.entity.BlockEntity?,
        visited: MutableSet<BlockPos>,
        queue: ArrayDeque<BlockPos>,
    ) {
        val instr = entity is damien.nodeworks.block.entity.InstructionStorageBlockEntity
        val proc = entity is damien.nodeworks.block.entity.ProcessingStorageBlockEntity
        if (!instr && !proc) return
        for (dir in Direction.entries) {
            val neighbor = pos.relative(dir)
            if (neighbor in visited) continue
            if (!level.isLoaded(neighbor)) continue
            val neighborBe = level.getBlockEntity(neighbor)
            val sameCluster =
                (instr && neighborBe is damien.nodeworks.block.entity.InstructionStorageBlockEntity) ||
                (proc && neighborBe is damien.nodeworks.block.entity.ProcessingStorageBlockEntity)
            if (sameCluster) {
                visited.add(neighbor)
                queue.add(neighbor)
            }
        }
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

            // Snapshot the previous reachable set before rebuilding so we can diff
            // and invalidate chunk sections for any block whose reachability flipped.
            // Every network-tint-driven emissive texture (controller / terminal /
            // variable / receiver antenna / processing & instruction storage, plus
            // any future block in the BlockTintSources list) goes through a
            // NetworkColorTintSource that only re-evaluates on section rebuild,
            // so LOS changes that don't move a block between chunks otherwise go
            // visually unnoticed until an unrelated chunk reload.
            val previousReachable = reachableSnapshot
            reachablePositions.clear()
            for (nodePos in knownNodes) {
                if (!level.isLoaded(nodePos)) continue
                val entity = level.getBlockEntity(nodePos)
                if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) {
                    bfsReachable(level, nodePos)
                }
            }
            invalidateChangedSections(previousReachable, reachablePositions)
            reachableSnapshot = HashSet(reachablePositions)
        }
    }

    /** Snapshot of [reachablePositions] taken after each LOS-refresh cycle so the next
     *  cycle can diff against it and issue chunk rebuilds only for blocks that flipped. */
    private var reachableSnapshot: HashSet<BlockPos> = HashSet()

    private fun invalidateChangedSections(before: Set<BlockPos>, after: Set<BlockPos>) {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val changed = HashSet<BlockPos>()
        for (pos in before) if (pos !in after) changed.add(pos)
        for (pos in after) if (pos !in before) changed.add(pos)
        if (changed.isEmpty()) return
        val dirtiedSections = HashSet<Long>()
        for (pos in changed) {
            val sx = net.minecraft.core.SectionPos.blockToSectionCoord(pos.x)
            val sy = net.minecraft.core.SectionPos.blockToSectionCoord(pos.y)
            val sz = net.minecraft.core.SectionPos.blockToSectionCoord(pos.z)
            val key = net.minecraft.core.SectionPos.asLong(sx, sy, sz)
            if (!dirtiedSections.add(key)) continue
            mc.levelRenderer.setSectionDirtyWithNeighbors(sx, sy, sz)
        }
    }

    private fun bfsReachable(level: net.minecraft.world.level.Level, controllerPos: BlockPos) {
        val queue = ArrayDeque<BlockPos>()
        queue.add(controllerPos)
        reachablePositions.add(controllerPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = level.getBlockEntity(pos)
            val connectable = entity as? damien.nodeworks.network.Connectable ?: continue
            for (targetPos in connectable.getConnections()) {
                if (targetPos in reachablePositions) continue
                if (isConnectionBlocked(pos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue
                reachablePositions.add(targetPos)
                queue.add(targetPos)
            }
            // Cluster-storage adjacency: Instruction/Processing Storages cluster
            // via face adjacency, not lasers. Without this walk, only the storage
            // laser-wired to a Node would be reachable — trailing storages in a
            // CNSSS chain would fail the `isReachable` gate in
            // ConnectableBER.resolveNetworkColor and render with the default grey
            // color despite having a correctly synced networkId. Mirrors the
            // server-side cluster traversal in NodeConnectionHelper.propagateNetworkId.
            enqueueClusterNeighbors(level, pos, entity, reachablePositions, queue)
        }
    }

    /** The currently pinned block position (shown highlighted by the Diagnostic Tool), or null. */
    var pinnedBlock: BlockPos? = null

    fun register() {
        NodeBlockEntity.nodeTracker = NodeBlockEntity.NodeTracker { pos, loaded ->
            trackConnectable(pos, loaded)
        }

        // Invalidate the BlockTintCache for every Connectable block belonging to a
        // network whose settings just changed. The cache is keyed on (section, pos,
        // layer) and only refreshes when the section is marked dirty — setSectionDirty
        // forces a re-query of NetworkColorTintSource.colorInWorld next frame.
        damien.nodeworks.network.NetworkSettingsRegistry.onChanged = label@{ networkId ->
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@label
            val renderer = mc.levelRenderer
            for (pos in knownNodes) {
                if (!level.isLoaded(pos)) continue
                val be = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable ?: continue
                if (be.networkId != networkId) continue
                renderer.setSectionDirty(
                    net.minecraft.core.SectionPos.blockToSectionCoord(pos.x),
                    net.minecraft.core.SectionPos.blockToSectionCoord(pos.y),
                    net.minecraft.core.SectionPos.blockToSectionCoord(pos.z)
                )
            }
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

        // LOS cache + reachability is computed here (once per tick) so per-BE
        // connection-beam renderers can read the cached state cheaply. Connection beam
        // drawing itself moved to ConnectionBeamRenderer (called from each Connectable's
        // BER) so it also works in GuideME scene renders, which don't fire this event.
        val tick = mc.level?.gameTime ?: 0L
        if (tick != losRefreshTick) {
            losRefreshTick = tick
            refreshLosCache(level)
        }

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        // Wrench selection highlight (only if node still exists and player holds wrench)
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

        // Monitor count text — stays here because it wants camera-relative billboarding
        // over the entire knownNodes set, not a per-BER pass.
        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        renderMonitorText(poseStack, consumers, level, cameraPos)
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
        level: net.minecraft.world.level.Level,
        cameraPos: net.minecraft.world.phys.Vec3
    ) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val bufferSource = mc.renderBuffers().bufferSource()

        // Same 64-block distance cull as the main connection-render loop so text
        // work scales with nearby monitors, not total monitors on the network.
        // Applied BEFORE getBlockEntity / font lookups — the squared-distance check
        // is the cheapest possible gate.
        val maxDistSq = 64.0 * 64.0

        // Iterate every tracked Connectable (MonitorBlockEntity registers via
        // trackConnectable in setLevel; `knownNodes` is the full live set despite the
        // historical name). Non-monitor positions fall through the `as?` cast and
        // are skipped cheaply.
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

            // Rotate so -Z points out the front face of the block (matches the
            // MonitorRenderer's icon orientation).
            when (facing) {
                Direction.SOUTH -> {}
                Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
                Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
                Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
                else -> {}
            }

            // Anchor text just below the centered item icon, on the face of the block
            // (Z = 0.5 + ~1/32 so it sits flush with the emissive layer).
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
                15728880
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

    /**
     * Draws a pulsing cyan full-block overlay around the pinned block whenever the
     * Diagnostic Tool is held. Drawn through walls (ALWAYS_PASS depth) so the
     * player can locate the block from anywhere.
     *
     * 26.1 rewrite: the pre-migration version stamped the block's quads through
     * an immediate-mode `RenderSystem.setShader` + `BufferUploader.drawWithShader`
     * path. Both of those APIs are gone in 26.1 (replaced by
     * `RegisterRenderPipelinesEvent` + `RenderType` dispatch), and `BakedQuad`
     * switched from `quad.vertices: int[]` to `position(i)` / `packedUV(i)`
     * accessors. The new flow: look up the block's `BlockStateModel`, collect
     * its `BlockStateModelPart`s, walk every `BakedQuad`, and emit each vertex
     * to our registered [PinHighlightRenderType.THROUGH_WALLS] — which uses the
     * block atlas with translucent blending and depth-test disabled.
     *
     * Vertex format is `DefaultVertexFormat.BLOCK` (Position + Color + UV0 + UV2) —
     * no normal, no overlay.
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

        val modelSet = mc.modelManager.blockStateModelSet ?: return
        val model = modelSet.get(blockState) ?: return

        // Pulse 0.6 → 1.0 at ~0.5 Hz modulates alpha so the highlight "breathes".
        val time = (System.currentTimeMillis() % 2000) / 2000f
        val pulse = (kotlin.math.sin(time * Math.PI * 2).toFloat() * 0.2f + 0.8f)

        // Collect model parts with a deterministic seed so quad order is stable.
        val random = net.minecraft.util.RandomSource.create(blockState.getSeed(pos))
        val parts = ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>()
        model.collectParts(random, parts)
        if (parts.isEmpty()) return

        poseStack.pushPose()
        poseStack.translate(
            (pos.x - cameraPos.x).toFloat(),
            (pos.y - cameraPos.y).toFloat(),
            (pos.z - cameraPos.z).toFloat()
        )
        // Slight outward breathe around block center so the overlay visibly
        // pulses without z-fighting the block below it.
        val scale = 1.0f + pulse * 0.04f
        poseStack.translate(0.5f, 0.5f, 0.5f)
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-0.5f, -0.5f, -0.5f)
        val pose = poseStack.last()

        // Cyan tint (0.6, 0.9, 1.0) with pulse-driven alpha.
        val r = (0.6f * 255).toInt().coerceIn(0, 255)
        val g = (0.9f * 255).toInt().coerceIn(0, 255)
        val b = 255
        val a = (pulse * 200).toInt().coerceIn(0, 255)

        val buffer = consumers.getBuffer(PinHighlightRenderType.THROUGH_WALLS)

        // Fullbright lightmap so the overlay ignores block/sky light.
        val lightU = 240
        val lightV = 240

        for (part in parts) {
            // `null` direction collects quads without a culling face (interior geometry).
            emitQuads(buffer, pose, part.getQuads(null), r, g, b, a, lightU, lightV)
            for (dir in Direction.entries) {
                emitQuads(buffer, pose, part.getQuads(dir), r, g, b, a, lightU, lightV)
            }
        }

        poseStack.popPose()
    }

    private fun emitQuads(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        quads: List<net.minecraft.client.resources.model.geometry.BakedQuad>,
        r: Int, g: Int, b: Int, a: Int,
        lightU: Int, lightV: Int
    ) {
        for (quad in quads) {
            for (i in 0 until 4) {
                val p = quad.position(i)
                val packedUv = quad.packedUV(i)
                val u = net.minecraft.client.model.geom.builders.UVPair.unpackU(packedUv)
                val v = net.minecraft.client.model.geom.builders.UVPair.unpackV(packedUv)
                buffer.addVertex(pose, p.x(), p.y(), p.z())
                    .setColor(r, g, b, a)
                    .setUv(u, v)
                    .setUv2(lightU, lightV)
            }
        }
    }
}
