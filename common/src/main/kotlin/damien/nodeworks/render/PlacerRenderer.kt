package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.PlacerBlock
import damien.nodeworks.block.entity.PlacerBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Placer — two glow layers tinted by the network color.
 * `placer_side_emissive` covers the 4 faces perpendicular to FACING (the device
 * body's wrap-around sides), and `placer_back_emissive` covers the face opposite
 * FACING (the back of the body, away from the placement face).
 */
open class PlacerRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<PlacerBlockEntity, PlacerRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val SIDE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/placer_side_emissive.png")
        private val BACK_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/placer_back_emissive.png")
        private val SIDE_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(SIDE_TEXTURE)
        private val BACK_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(BACK_TEXTURE)
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: PlacerBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(PlacerBlock.FACING)
        state.color = resolveNetworkColor(blockEntity)
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val r = (state.color shr 16) and 0xFF
        val g = (state.color shr 8) and 0xFF
        val b = state.color and 0xFF

        EmissiveCubeRenderer.submitSides(submitNodeCollector, poseStack, SIDE_RENDER_TYPE, state.facing, r, g, b, 255)
        val backBit = EmissiveCubeRenderer.faceOf(state.facing.opposite)
        EmissiveCubeRenderer.submit(submitNodeCollector, poseStack, BACK_RENDER_TYPE, backBit, r, g, b, 255)
    }
}
