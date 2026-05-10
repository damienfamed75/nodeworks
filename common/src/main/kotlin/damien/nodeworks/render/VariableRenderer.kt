package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.client.VariableEmissiveModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3

/**
 * BER for the Variable block. Lays a network-colour-tinted emissive overlay
 * on the body using the same parent-model pattern the User / Placer /
 * Breaker use: `variable_emissive.json` inherits `nodeworks:block/variable`
 * and only swaps `#particle` to variable_emissive.png, so any emissive paint
 * authored on that texture lights up exactly where the body texture shows
 * it. Variable has no FACING property so the overlay sits at the block
 * centre without rotation, just outset slightly to win the depth tie
 * against the underlying chunk-rendered body.
 */
open class VariableRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<VariableBlockEntity, VariableRenderer.VariableState>(context) {

    class VariableState : ConnectableRenderState() {
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        /** Outset factor applied around the block centre to the emissive
         *  overlay's geometry to win z-fight tests against the underlying
         *  chunk-rendered body. 1.001 = 1 px offset on a full-block face,
         *  invisible to the eye but enough for the depth test. */
        private const val EMISSIVE_OUTSET = 1.001f

        /** All six directions + null, the parameter to [BlockStateModelPart.getQuads]
         *  for "this face direction" and "no specific face" respectively. */
        private val DIRECTIONS_AND_NULL: Array<Direction?> =
            arrayOf(
                Direction.DOWN,
                Direction.UP,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST,
                null,
            )
    }

    override fun createRenderState(): VariableState = VariableState()

    override fun extractConnectable(
        blockEntity: VariableBlockEntity,
        state: VariableState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.color = resolveNetworkColor(blockEntity)
    }

    override fun submitConnectable(
        state: VariableState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val emissive = VariableEmissiveModel.get()
        if (emissive == null || state.color == NodeConnectionRenderer.DEFAULT_NETWORK_COLOR) return
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.scale(EMISSIVE_OUTSET, EMISSIVE_OUTSET, EMISSIVE_OUTSET)
        poseStack.translate(-0.5, -0.5, -0.5)
        submitEmissiveOverlay(poseStack, submitNodeCollector, emissive, state.color)
        poseStack.popPose()
    }

    /** Network-coloured additive overlay over the Variable's body. The baked
     *  quads carry variable.json's per-face UVs (since variable_emissive.json
     *  inherits geometry via JSON parent), so the overlay's texture sampling
     *  lands on the same atlas regions as the body model. The vertex tint
     *  multiplies the texture sample, so transparent pixels in
     *  variable_emissive.png contribute zero (additive blend) and only
     *  authored emissive areas glow. */
    private fun submitEmissiveOverlay(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        part: BlockStateModelPart,
        tintColor: Int,
    ) {
        val argb = ARGB.color(255, (tintColor shr 16) and 0xFF, (tintColor shr 8) and 0xFF, tintColor and 0xFF)
        val quadInstance = QuadInstance().apply {
            for (vertex in 0..3) setColor(vertex, argb)
        }
        collector.submitCustomGeometry(poseStack, EmissiveCubeRenderer.BLOCK_ATLAS_RENDER_TYPE) { pose, vc ->
            for (dir in DIRECTIONS_AND_NULL) {
                for (quad in part.getQuads(dir)) {
                    vc.putBakedQuad(pose, quad, quadInstance)
                }
            }
        }
    }
}
