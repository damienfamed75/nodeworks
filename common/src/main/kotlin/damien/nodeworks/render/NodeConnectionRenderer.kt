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

    /** Global tracker, any Connectable block entity can register/unregister here. */
    fun trackConnectable(pos: BlockPos, loaded: Boolean) {
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
            if (!isConnectionBlocked(startPos, conn) && visited.add(conn)) queue.add(conn)
        }
        // Seed face-adjacent Connectable neighbours, blocks that touch each
        // other share a network even with no laser connection between them.
        enqueueAdjacentConnectables(level, startPos, startEntity, visited, queue)
        while (queue.isNotEmpty() && visited.size < 32) {
            val pos = queue.removeFirst()
            val entity = level.getBlockEntity(pos) ?: continue
            if (entity is damien.nodeworks.block.entity.NetworkControllerBlockEntity) return entity
            val connectable = entity as? damien.nodeworks.network.Connectable ?: continue
            for (conn in connectable.getConnections()) {
                if (!isConnectionBlocked(pos, conn) && visited.add(conn)) queue.add(conn)
            }
            enqueueAdjacentConnectables(level, pos, entity, visited, queue)
        }
        return null
    }

    /** Enqueue face-adjacent Connectable BEs. No-op when [entity] isn't a Connectable
     *  or either endpoint opts out via [Connectable.usesAdjacency] (Nodes). */
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
     * Incrementally refresh the LOS cache, processes a limited number of raycasts per tick
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
            // Face-adjacent Connectables count as reachable too, so a device
            // touching a wired Node inherits the network color even without a
            // laser between them.
            enqueueAdjacentConnectables(level, pos, entity, reachablePositions, queue)
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
        // layer) and only refreshes when the section is marked dirty, setSectionDirty
        // forces a re-query of NetworkColorTintSource.colorInWorld next frame.
        damien.nodeworks.network.NetworkSettingsRegistry.onChanged = label@{ networkId ->
            // Short-circuit on null: a batch of disconnected BEs (networkId == null)
            // loading at once previously caused O(n²) chunk re-renders, every load
            // iterated knownNodes and dirtied every disconnected BE's section. The
            // null case carries no useful colour change (disconnected BEs all render
            // the default grey from the same fallback path) and the chunk is being
            // built anyway, so skipping it here is correctness-neutral.
            if (networkId == null) return@label
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
                renderSelectionThroughWalls(poseStack, consumers, cameraPos)
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

        // Monitor count text, stays here because it wants camera-relative billboarding
        // over the entire knownNodes set, not a per-BER pass.
        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        renderMonitorText(poseStack, consumers, level, cameraPos)
        poseStack.popPose()
    }

    /** Through-walls highlight on the wrench's selected endpoint. Self-clears
     *  the selection field if the underlying block has been removed. */
    fun renderSelectionThroughWalls(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3,
    ) {
        val pos = NetworkWrenchItem.clientSelectedPos ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        // I hate this so much but whatever. Curse my choice of kotlin
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
        // Applied BEFORE getBlockEntity / font lookups, the squared-distance check
        // is the cheapest possible gate.
        val maxDistSq = 64.0 * 64.0

        // Iterate every tracked Connectable (MonitorBlockEntity registers via
        // trackConnectable in setLevel, `knownNodes` is the full live set despite the
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

    /** Pulsing through-walls box around [pos] tinted [r,g,b]. Box bounds come
     *  from the block's outline shape so a Node gets a 6/16 cube and a full
     *  block gets a 1×1×1 cube. */
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

        // Modulate vertex color by pulse, additive ignores dst alpha so this is
        // what drives glow strength.
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
