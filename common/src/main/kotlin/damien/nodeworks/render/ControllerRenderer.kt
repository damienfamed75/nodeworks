package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Renders an emissive overlay on the Network Controller (AE2-style glowing lines).
 */
class ControllerRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<NetworkControllerBlockEntity, ControllerRenderer.ControllerRenderState> {

    companion object {
        private val EMISSIVE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/network_controller_emissive.png")
        private val EMISSIVE_TOP_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/network_controller_top_emissive.png")
    }

    class ControllerRenderState : BlockEntityRenderState()

    override fun createRenderState(): ControllerRenderState = ControllerRenderState()

    override fun extractRenderState(
        entity: NetworkControllerBlockEntity,
        state: ControllerRenderState,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        super.extractRenderState(entity, state, partialTick, cameraPos, crumbling)
    }

    override fun submit(
        state: ControllerRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val color = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY
        val eyesType = RenderTypes.eyes(EMISSIVE_TEXTURE)

        // Slight offset to prevent Z-fighting with the block model
        val o = -0.001f
        val s = 1.0f + 0.002f

        collector.submitCustomGeometry(poseStack, eyesType) { pose, vc ->
            // South face (+Z) — reversed winding to face outward
            vc.addVertex(pose, s, o, s).setUv(1f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, s).setUv(1f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, s, s).setUv(0f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, o, s).setUv(0f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)

            // North face (-Z)
            vc.addVertex(pose, o, o, o).setUv(1f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, o, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, s, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, s, o, o).setUv(0f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)

            // East face (+X)
            vc.addVertex(pose, s, o, o).setUv(1f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, s, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, s, s, s).setUv(0f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, s, o, s).setUv(0f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)

            // West face (-X)
            vc.addVertex(pose, o, o, s).setUv(1f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, o, s, s).setUv(1f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, o, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, o, o, o).setUv(0f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)

            // No emissive on bottom face
        }

        // Top face uses separate emissive texture
        val eyesTopType = RenderTypes.eyes(EMISSIVE_TOP_TEXTURE)
        collector.submitCustomGeometry(poseStack, eyesTopType) { pose, vc ->
            vc.addVertex(pose, o, s, s).setUv(0f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, s, s, s).setUv(1f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, s, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, o, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
        }
    }
}
