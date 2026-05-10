package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.math.Axis
import damien.nodeworks.block.PlacerBlock
import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.client.PlacerEmissiveModel
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3

/**
 * BER for the Placer device. Drives two visual pieces:
 *  * A network-colour-tinted emissive overlay layered onto the body. Reads
 *    geometry + per-face UVs from `placer_emissive.json` (which inherits
 *    `nodeworks:block/placer`) so any emissive paint authored on
 *    placer_emissive.png lights up exactly where placer.png shows it.
 *  * A short interior laser stub along the front-back axis that mirrors
 *    the User's: 5 px long, 4×4 cross-section, in the throat region where
 *    the model is hollow enough for the beam to read.
 */
open class PlacerRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<PlacerBlockEntity, PlacerRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR

        /** Network laser plumbing, mirroring UserRenderer's interior stub. */
        var laserMode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY
        var hasNetwork: Boolean = false
        var isMicro: Boolean = false
    }

    companion object {
        /** Length of the interior network-laser stub, matching the User. 5 px
         *  runs the beam from block centre (pixel 8) to pixel 3 along the
         *  back-front axis, landing in the model's throat region where it's
         *  visible past the solid back body. */
        private const val INNER_LASER_HALF_LENGTH = 5f / 16f

        /** Outset factor applied around the block centre to the emissive
         *  overlay's geometry to win z-fight tests against the underlying
         *  chunk-rendered body. 1.001 = 1 px offset on a full-block face,
         *  invisible to the eye but enough for the depth test. Matches the
         *  User's [UserRenderer.EMISSIVE_OUTSET]. */
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
        val id = blockEntity.networkId
        val settings = NetworkSettingsRegistry.get(id)
        state.laserMode = settings.laserMode
        state.hasNetwork = id != null
        state.isMicro = MicroNetworkClientRegistry.isMicro(id)
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // Interior laser stub, same treatment as UserRenderer - back half is
        // solid model geometry so the beam runs from centre toward the FRONT
        // face into the throat region where it's actually visible.
        if (state.hasNetwork) {
            PipeLaserBeam.submitStub(
                poseStack,
                submitNodeCollector,
                state.pos,
                camera.pos,
                dir = state.facing,
                color = state.color,
                laserMode = state.laserMode,
                halfLength = INNER_LASER_HALF_LENGTH,
                isMicro = state.isMicro,
            )
        }

        // Network-coloured emissive overlay on the body. Mirrors the User's
        // approach: rotate + slightly-outset the baked emissive model and
        // route its quads through the additive-glow pipeline. Skipped when
        // the device isn't on a network -- no sensible colour.
        val emissive = PlacerEmissiveModel.get()
        if (emissive != null && state.color != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR) {
            poseStack.pushPose()
            poseStack.translate(0.5, 0.5, 0.5)
            applyBodyRotation(poseStack, state.facing)
            poseStack.scale(EMISSIVE_OUTSET, EMISSIVE_OUTSET, EMISSIVE_OUTSET)
            poseStack.translate(-0.5, -0.5, -0.5)
            submitEmissiveOverlay(poseStack, submitNodeCollector, emissive, state.color)
            poseStack.popPose()
        }
    }

    /** Network-coloured additive overlay over the Placer's body. The baked
     *  quads carry placer.json's per-face UVs (since placer_emissive.json
     *  inherits geometry via JSON parent), so the overlay's texture
     *  sampling lands on the same atlas regions as the body model. The
     *  vertex tint multiplies the texture sample, so transparent pixels
     *  in placer_emissive.png contribute zero (additive blend) and only
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

    /** Apply the same rotation the chunk renderer applies to the Placer
     *  body model via `blockstates/placer.json`'s variants - identical
     *  variant table to the User block, so the math matches
     *  [UserRenderer.applyBodyRotation]. */
    private fun applyBodyRotation(poseStack: PoseStack, facing: Direction) {
        when (facing) {
            Direction.NORTH -> Unit
            Direction.SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(-180f))
            Direction.EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-270f))
            Direction.UP -> poseStack.mulPose(Axis.XP.rotationDegrees(-270f))
            Direction.DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(-90f))
        }
    }
}
