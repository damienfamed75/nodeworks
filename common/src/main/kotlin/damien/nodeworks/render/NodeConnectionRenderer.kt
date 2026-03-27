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
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionRenderer {

    private val knownNodes: MutableSet<BlockPos> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Default network color (RGB, no alpha). Later will read from the controller. */
    const val DEFAULT_NETWORK_COLOR = 0x83E086
    private val cr get() = (DEFAULT_NETWORK_COLOR shr 16) and 0xFF
    private val cg get() = (DEFAULT_NETWORK_COLOR shr 8) and 0xFF
    private val cb get() = DEFAULT_NETWORK_COLOR and 0xFF

    /** Beam effect toggle — disable for lower-end PCs. */
    var beamEffectEnabled = true

    /** Beam width in blocks (1.5px = 1.5/16). Adjustable. */
    var beamWidth = 2.0f / 16f

    /** Beam scroll speed (UV units per second). */
    var beamScrollSpeed = 2.5f

    /** Beam pulse speed (cycles per second). */
    var beamPulseSpeed = 1.0f

    private val LASER_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

    // Node shape bounds (5/16 to 11/16)
    private const val MIN = 5f / 16f
    private const val MAX = 11f / 16f

    /** Global tracker — any Connectable block entity can register/unregister here. */
    fun trackConnectable(pos: BlockPos, loaded: Boolean) {
        if (loaded) knownNodes.add(pos) else knownNodes.remove(pos)
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

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val pose = poseStack.last()

        // Collect all connection pairs
        data class Connection(val from: net.minecraft.world.phys.Vec3, val to: net.minecraft.world.phys.Vec3)

        val connections = mutableListOf<Connection>()

        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val connectable = level.getBlockEntity(nodePos) as? damien.nodeworks.network.Connectable ?: continue

            for (targetPos in connectable.getConnections()) {
                if (!isLessThan(nodePos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue
                connections.add(Connection(nodePos.getCenter(), targetPos.getCenter()))
            }
        }

        if (beamEffectEnabled) {
            // Beacon-style beams are rendered by MonitorRenderer (BlockEntityRenderer)
            // which has access to SubmitNodeCollector for proper emissive rendering.
            // Nothing to do here.
        } else {
            // Fallback: thin lines
            val lineBuffer = consumers.getBuffer(RenderTypes.linesTranslucent())
            for (conn in connections) {
                val from = conn.from
                val to = conn.to
                val dx = (to.x - from.x).toFloat()
                val dy = (to.y - from.y).toFloat()
                val dz = (to.z - from.z).toFloat()
                val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val nx = if (len > 0) dx / len else 0f
                val ny = if (len > 0) dy / len else 0f
                val nz = if (len > 0) dz / len else 0f

                lineBuffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
                    .setColor(cr, cg, cb, 200)
                    .setNormal(pose, nx, ny, nz)
                    .setLineWidth(2.0f)

                lineBuffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
                    .setColor(cr, cg, cb, 200)
                    .setNormal(pose, nx, ny, nz)
                    .setLineWidth(2.0f)
            }
        }

        // Draw selection highlight (only if node still exists and player holds wrench)
        val selectedPos = NetworkWrenchItem.clientSelectedNode
        val player = mc.player
        if (selectedPos != null && player != null && player.mainHandItem.`is`(ModItems.NETWORK_WRENCH)) {
            if (level.getBlockEntity(selectedPos) is damien.nodeworks.network.Connectable) {
                val highlightBuffer = consumers.getBuffer(RenderTypes.linesTranslucent())
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

    private fun drawLine(
        buffer: VertexConsumer, pose: PoseStack.Pose,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        val nx = if (len > 0) dx / len else 0f
        val ny = if (len > 0) dy / len else 0f
        val nz = if (len > 0) dz / len else 1f

        buffer.addVertex(pose, x0, y0, z0)
            .setColor(cr, cg, cb, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(3.0f)

        buffer.addVertex(pose, x1, y1, z1)
            .setColor(cr, cg, cb, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(3.0f)
    }

    /**
     * Renders a textured billboard beam between two points.
     * The quad faces the camera, UV scrolls for animation, brightness pulses.
     */
    private fun renderBeam(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
        cameraPos: net.minecraft.world.phys.Vec3,
        pulse: Float,
        uvScroll: Float
    ) {
        val dx = (to.x - from.x).toFloat()
        val dy = (to.y - from.y).toFloat()
        val dz = (to.z - from.z).toFloat()
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (len < 0.01f) return

        // Direction along the beam
        val dirX = dx / len
        val dirY = dy / len
        val dirZ = dz / len

        // Camera to beam midpoint
        val midX = ((from.x + to.x) / 2 - cameraPos.x).toFloat()
        val midY = ((from.y + to.y) / 2 - cameraPos.y).toFloat()
        val midZ = ((from.z + to.z) / 2 - cameraPos.z).toFloat()

        // Cross product of beam direction and camera direction = perpendicular (billboard axis)
        var perpX = dirY * midZ - dirZ * midY
        var perpY = dirZ * midX - dirX * midZ
        var perpZ = dirX * midY - dirY * midX
        val perpLen = Math.sqrt((perpX * perpX + perpY * perpY + perpZ * perpZ).toDouble()).toFloat()
        if (perpLen < 0.0001f) return
        perpX /= perpLen
        perpY /= perpLen
        perpZ /= perpLen

        // Half width of the beam
        val hw = beamWidth / 2

        // Beam quad corners
        val fx = from.x.toFloat()
        val fy = from.y.toFloat()
        val fz = from.z.toFloat()
        val tx = to.x.toFloat()
        val ty = to.y.toFloat()
        val tz = to.z.toFloat()

        // 4 corners: from-left, from-right, to-right, to-left
        val x0 = fx - perpX * hw;
        val y0 = fy - perpY * hw;
        val z0 = fz - perpZ * hw
        val x1 = fx + perpX * hw;
        val y1 = fy + perpY * hw;
        val z1 = fz + perpZ * hw
        val x2 = tx + perpX * hw;
        val y2 = ty + perpY * hw;
        val z2 = tz + perpZ * hw
        val x3 = tx - perpX * hw;
        val y3 = ty - perpY * hw;
        val z3 = tz - perpZ * hw

        // Use full white vertex color — let the texture provide the shape
        // entityTranslucent with full bright light
        val alpha = 255
        val uMax = 5f / 16f
        val vStart = uvScroll
        val vEnd = uvScroll + len * 2f

        buffer.addVertex(pose, x0, y0, z0).setUv(0f, vStart).setColor(255, 255, 255, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
        buffer.addVertex(pose, x1, y1, z1).setUv(uMax, vStart).setColor(255, 255, 255, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
        buffer.addVertex(pose, x2, y2, z2).setUv(uMax, vEnd).setColor(255, 255, 255, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
        buffer.addVertex(pose, x3, y3, z3).setUv(0f, vEnd).setColor(255, 255, 255, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
    }

    /**
     * Renders a colored glow beam — wider, semi-transparent, uses network color.
     * Drawn behind the white beam to create a colored aura effect.
     */
    private fun renderBeamColored(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
        cameraPos: net.minecraft.world.phys.Vec3,
        uvScroll: Float
    ) {
        val dx = (to.x - from.x).toFloat()
        val dy = (to.y - from.y).toFloat()
        val dz = (to.z - from.z).toFloat()
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (len < 0.01f) return

        val dirX = dx / len
        val dirY = dy / len
        val dirZ = dz / len

        val midX = ((from.x + to.x) / 2 - cameraPos.x).toFloat()
        val midY = ((from.y + to.y) / 2 - cameraPos.y).toFloat()
        val midZ = ((from.z + to.z) / 2 - cameraPos.z).toFloat()

        var perpX = dirY * midZ - dirZ * midY
        var perpY = dirZ * midX - dirX * midZ
        var perpZ = dirX * midY - dirY * midX
        val perpLen = Math.sqrt((perpX * perpX + perpY * perpY + perpZ * perpZ).toDouble()).toFloat()
        if (perpLen < 0.0001f) return
        perpX /= perpLen
        perpY /= perpLen
        perpZ /= perpLen

        val hw = beamWidth / 2

        val fx = from.x.toFloat(); val fy = from.y.toFloat(); val fz = from.z.toFloat()
        val tx = to.x.toFloat(); val ty = to.y.toFloat(); val tz = to.z.toFloat()

        val x0 = fx - perpX * hw; val y0 = fy - perpY * hw; val z0 = fz - perpZ * hw
        val x1 = fx + perpX * hw; val y1 = fy + perpY * hw; val z1 = fz + perpZ * hw
        val x2 = tx + perpX * hw; val y2 = ty + perpY * hw; val z2 = tz + perpZ * hw
        val x3 = tx - perpX * hw; val y3 = ty - perpY * hw; val z3 = tz - perpZ * hw

        val alpha = 120 // semi-transparent glow
        val uMax = 5f / 16f
        val vStart = uvScroll
        val vEnd = uvScroll + len * 2f

        buffer.addVertex(pose, x0, y0, z0).setUv(0f, vStart).setColor(cr, cg, cb, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
        buffer.addVertex(pose, x1, y1, z1).setUv(uMax, vStart).setColor(cr, cg, cb, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
        buffer.addVertex(pose, x2, y2, z2).setUv(uMax, vEnd).setColor(cr, cg, cb, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
        buffer.addVertex(pose, x3, y3, z3).setUv(0f, vEnd).setColor(cr, cg, cb, alpha).setLight(15728880)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 1f, 0f)
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
                    Font.DisplayMode.SEE_THROUGH,
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
