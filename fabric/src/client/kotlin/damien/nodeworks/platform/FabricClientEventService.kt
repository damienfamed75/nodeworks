package damien.nodeworks.platform

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3

class FabricClientEventService : ClientEventService {
    override fun onWorldRender(handler: (PoseStack?, MultiBufferSource?, Vec3) -> Unit) {
        val callback = object : WorldRenderEvents.End {
            override fun onEnd(context: WorldRenderContext) {
                val mc = Minecraft.getInstance()
                val cameraPos = mc.gameRenderer.mainCamera.getPosition()
                val bufferSource = mc.renderBuffers().bufferSource()
                // In 1.21.1, WorldRenderContext doesn't expose matrixStack directly.
                // Create a new PoseStack for our rendering.
                val poseStack = PoseStack()
                handler(poseStack, bufferSource, cameraPos)
            }
        }
        WorldRenderEvents.END.register(callback)
    }
}
