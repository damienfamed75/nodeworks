package damien.nodeworks.render

import damien.nodeworks.block.entity.NodeBlockEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object NodeConnectionRenderer {

    private val knownNodes: MutableSet<BlockPos> = Collections.newSetFromMap(ConcurrentHashMap())

    fun register() {
        NodeBlockEntity.nodeTracker = NodeBlockEntity.NodeTracker { pos, loaded ->
            if (loaded) knownNodes.add(pos) else knownNodes.remove(pos)
        }

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register { context ->
            render(context)
        }
    }

    private fun render(context: WorldRenderContext) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cameraPos = mc.gameRenderer.mainCamera.position()

        @Suppress("USELESS_CAST")
        val poseStack = context.matrices() as PoseStack? ?: return
        val consumers = context.consumers()
        val buffer = consumers.getBuffer(RenderTypes.linesTranslucent())

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val pose = poseStack.last()

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

        poseStack.popPose()
    }

    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }
}
