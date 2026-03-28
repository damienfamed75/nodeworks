package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.TerminalBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Renders an emissive overlay on the front face of the Terminal block.
 */
class TerminalRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<TerminalBlockEntity, TerminalRenderer.TerminalRenderState> {

    companion object {
        private val EMISSIVE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/terminal_front_emissive.png")
    }

    class TerminalRenderState : BlockEntityRenderState() {
        var facing: Direction = Direction.NORTH
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    override fun createRenderState(): TerminalRenderState = TerminalRenderState()

    override fun extractRenderState(
        entity: TerminalBlockEntity,
        state: TerminalRenderState,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        super.extractRenderState(entity, state, partialTick, cameraPos, crumbling)
        state.facing = entity.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
        state.networkColor = NodeConnectionRenderer.findNetworkColor(entity.level, entity.blockPos)
    }

    override fun submit(
        state: TerminalRenderState,
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
        val eyesType = RenderTypes.eyes(EMISSIVE_TEXTURE)

        poseStack.pushPose()

        // Move to block center, rotate to face direction, then push to front face
        poseStack.translate(0.5, 0.5, 0.5)
        when (state.facing) {
            Direction.NORTH -> {} // blockstate y=0
            Direction.SOUTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat())) // y=180
            Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI * 1.5).toFloat())) // y=270
            Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat())) // y=90
            else -> {}
        }

        // Front face quad at z = -0.501 (slightly outside block surface)
        val o = 0.501f
        val h = 0.5f // half-size

        collector.submitCustomGeometry(poseStack, eyesType) { pose, vc ->
            vc.addVertex(pose, -h, -h, -o).setUv(1f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, -h, h, -o).setUv(1f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, h, h, -o).setUv(0f, 0f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, h, -h, -o).setUv(0f, 1f).setColor(r, g, b, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
        }

        poseStack.popPose()
    }
}
