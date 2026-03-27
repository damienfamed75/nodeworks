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

    // Node shape bounds (5/16 to 11/16)
    private const val MIN = 5f / 16f
    private const val MAX = 11f / 16f

    fun register() {
        NodeBlockEntity.nodeTracker = NodeBlockEntity.NodeTracker { pos, loaded ->
            if (loaded) knownNodes.add(pos) else knownNodes.remove(pos)
        }

        PlatformServices.clientEvents.onWorldRender { poseStack, consumers, cameraPos ->
            if (poseStack != null && consumers != null) {
                render(poseStack, consumers, cameraPos)
            }
        }
    }

    private fun render(poseStack: PoseStack, consumers: net.minecraft.client.renderer.MultiBufferSource, cameraPos: net.minecraft.world.phys.Vec3) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val buffer = consumers.getBuffer(RenderTypes.linesTranslucent())

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val pose = poseStack.last()

        // Draw connection lines
        for (nodePos in knownNodes) {
            if (!level.isLoaded(nodePos)) continue
            val blockEntity = level.getBlockEntity(nodePos) as? NodeBlockEntity ?: continue

            for (targetPos in blockEntity.getConnections()) {
                if (!isLessThan(nodePos, targetPos)) continue
                if (!level.isLoaded(targetPos)) continue

                val from = nodePos.getCenter()
                val to = targetPos.getCenter()

                val dx = (to.x - from.x).toFloat()
                val dy = (to.y - from.y).toFloat()
                val dz = (to.z - from.z).toFloat()
                val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val nx = if (len > 0) dx / len else 0f
                val ny = if (len > 0) dy / len else 0f
                val nz = if (len > 0) dz / len else 0f

                buffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
                    .setColor(131, 224, 134, 200)
                    .setNormal(pose, nx, ny, nz)
                    .setLineWidth(2.0f)

                buffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
                    .setColor(131, 224, 134, 200)
                    .setNormal(pose, nx, ny, nz)
                    .setLineWidth(2.0f)
            }
        }

        // Draw selection highlight (only if node still exists and player holds wrench)
        val selectedPos = NetworkWrenchItem.clientSelectedNode
        val player = mc.player
        if (selectedPos != null && player != null && player.mainHandItem.`is`(ModItems.NETWORK_WRENCH)) {
            if (level.getBlockEntity(selectedPos) is NodeBlockEntity) {
                renderSelectionHighlight(poseStack, buffer, selectedPos)
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
            .setColor(131, 224, 134, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(3.0f)

        buffer.addVertex(pose, x1, y1, z1)
            .setColor(131, 224, 134, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(3.0f)
    }

    private fun renderMonitorText(poseStack: PoseStack, consumers: MultiBufferSource, level: net.minecraft.world.level.Level) {
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
                    Direction.EAST  -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
                    Direction.WEST  -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
                    Direction.DOWN  -> poseStack.mulPose(Quaternionf().rotateX((-Math.PI / 2).toFloat()))
                    Direction.UP    -> poseStack.mulPose(Quaternionf().rotateX((Math.PI / 2).toFloat()))
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
