package damien.nodeworks.platform

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3

class FabricClientEventService : ClientEventService {
    override fun onWorldRender(handler: (PoseStack?, MultiBufferSource?, Vec3) -> Unit) {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register { context ->
            val cameraPos = Minecraft.getInstance().gameRenderer.mainCamera.position()
            handler(
                context.matrices(),
                context.consumers(),
                cameraPos
            )
        }
    }
}
