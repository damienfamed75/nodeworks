package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.VariableBlockEntity
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
 * Renders an emissive overlay on the Variable block.
 */
class VariableRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<VariableBlockEntity, VariableRenderer.VariableRenderState> {

    companion object {
        private val EMISSIVE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/variable_emissive.png")
    }

    class VariableRenderState : BlockEntityRenderState() {
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    override fun createRenderState(): VariableRenderState = VariableRenderState()

    override fun extractRenderState(
        entity: VariableBlockEntity,
        state: VariableRenderState,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        super.extractRenderState(entity, state, partialTick, cameraPos, crumbling)
        state.networkColor = NodeConnectionRenderer.findNetworkColor(entity.level, entity.blockPos)
    }

    override fun submit(
        state: VariableRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val color = state.networkColor
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY
        val o = -0.001f
        val s = 1.0f + 0.002f

        // Side faces emissive
        val eyesType = RenderTypes.eyes(EMISSIVE_TEXTURE)
        collector.submitCustomGeometry(poseStack, eyesType) { pose, vc ->
            // South face (+Z)
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
        }

    }
}
