package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.BreakerBlock
import damien.nodeworks.block.entity.BreakerBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Breaker — two glow layers tinted by the network color.
 * `breaker_side_emissive` covers the 4 faces perpendicular to FACING (the device
 * body's wrap-around sides), and `breaker_back_emissive` covers the face opposite
 * FACING (the back of the body, away from the mining face). Same Variable / Terminal
 * pattern: extract `state.color = resolveNetworkColor(...)` and let
 * [EmissiveCubeRenderer.submit] do the geometry.
 */
open class BreakerRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<BreakerBlockEntity, BreakerRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val SIDE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/breaker_side_emissive.png")
        private val BACK_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/breaker_back_emissive.png")
        private val SIDE_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(SIDE_TEXTURE)
        private val BACK_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(BACK_TEXTURE)
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: BreakerBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(BreakerBlock.FACING)
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

        // Side overlay: 4 perpendicular faces with per-face UV rotation matching the
        // piston-style `_side.png` model rotations (top of texture points toward FACING
        // on every face). [submitSides] handles the FACING → per-face rotation lookup.
        EmissiveCubeRenderer.submitSides(submitNodeCollector, poseStack, SIDE_RENDER_TYPE, state.facing, r, g, b, 255)

        // Back overlay: the single face opposite FACING.
        val backBit = EmissiveCubeRenderer.faceOf(state.facing.opposite)
        EmissiveCubeRenderer.submit(submitNodeCollector, poseStack, BACK_RENDER_TYPE, backBit, r, g, b, 255)
    }
}
