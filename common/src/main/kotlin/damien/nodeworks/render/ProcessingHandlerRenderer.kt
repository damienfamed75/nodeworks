package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Renders the Processing Handler's per-frame extras over its base model:
 *
 *  - **Back-face stub**: parent-network-tinted half-beam from the block
 *    centre to the back face. Reads as a pipe extension of the parent net.
 *  - **Front-face stub**: yellow hazard-stripe half-beam to the front face,
 *    micro-styled so the visual transitions from "parent" to "micro" right
 *    inside the block.
 *  - **Transition cube**: small emissive cube in the centre using the
 *    `transition_cube.png` texture (white→yellow gradient) so the colour
 *    handoff between the two stubs is visible at any angle.
 */
class ProcessingHandlerRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<ProcessingHandlerBlockEntity, ProcessingHandlerRenderer.RenderState>(context) {

    companion object {
        /** Yellow tint passed to [PipeLaserBeam] for the front-face micro
         *  stub (#FFDC3B). */
        private const val MICRO_R = 0xFF
        private const val MICRO_G = 0xDC
        private const val MICRO_B = 0x3B

        /** Half-extent of the central cube, in block units. Matches
         *  [PipeLaserBeam]'s `CORE_HALF` (1/16 = 1 px each side from centre,
         *  for a 2-px cube) so the Handler's transition core reads at the
         *  same scale as the glowing centre cubes Pipe junctions and Nodes
         *  emit on micro networks. */
        private const val CUBE_HALF = 1f / 16f
    }

    class RenderState : ConnectableRenderState() {
        var backFace: Direction = Direction.NORTH
        var frontFace: Direction = Direction.SOUTH
        var parentColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var laserMode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY
        /** False when the Handler isn't on a parent network. Suppresses the
         *  back-face stub so a disconnected Handler reads as inert. */
        var hasParentNetwork: Boolean = false
        /** Same gate for the micro side - when the front face hasn't joined
         *  a micro-network yet (block freshly placed, no neighbours), skip
         *  the front stub. */
        var hasMicroNetwork: Boolean = false
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: ProcessingHandlerBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.backFace = blockEntity.backFace
        state.frontFace = blockEntity.frontFace
        val parentId = blockEntity.networkId
        val settings = NetworkSettingsRegistry.get(parentId)
        state.parentColor = settings.color
        state.laserMode = settings.laserMode
        state.hasParentNetwork = parentId != null
        state.hasMicroNetwork = blockEntity.microNetworkId != null
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.hasParentNetwork) {
            PipeLaserBeam.submit(
                poseStack, submitNodeCollector, state.pos, camera.pos,
                setOf(state.backFace), state.parentColor, state.laserMode,
                drawCenterCore = false,
                isMicro = false,
            )
        }
        if (state.hasMicroNetwork) {
            PipeLaserBeam.submit(
                poseStack, submitNodeCollector, state.pos, camera.pos,
                setOf(state.frontFace),
                color = (MICRO_R shl 16) or (MICRO_G shl 8) or MICRO_B,
                laserMode = state.laserMode,
                drawCenterCore = false,
                isMicro = true,
            )
        }
        // Centre transition cube. Drawn whenever EITHER side is on a
        // network so the player sees the colour handoff while diagnosing a
        // half-connected handler; suppressed entirely when both sides are
        // disconnected (matches stub gating).
        if (state.hasParentNetwork || state.hasMicroNetwork) {
            emitTransitionCube(poseStack, submitNodeCollector, state.frontFace)
        }
    }

    /** Emit a small solid cube at the block centre using the same render
     *  type as Pipe / Node centre cubes ([PipeLaserCoreRenderType], position
     *  + colour, no texture). Per-vertex tint is white on vertices facing
     *  the back of the Handler and yellow (#FFDC3B) on vertices facing the
     *  front, so the cube reads as a colour handoff: solid white on the
     *  back face, solid yellow on the front face, smooth gradient on the
     *  four side faces. */
    private fun emitTransitionCube(
        poseStack: PoseStack,
        submitter: SubmitNodeCollector,
        frontFace: Direction,
    ) {
        val mn = 0.5f - CUBE_HALF
        val mx = 0.5f + CUBE_HALF
        submitter.submitCustomGeometry(poseStack, PipeLaserCoreRenderType.RENDER_TYPE) { p, vc ->
            // +Z face.
            emitVertex(p, vc, mx, mn, mx, frontFace, mn, mx)
            emitVertex(p, vc, mx, mx, mx, frontFace, mn, mx)
            emitVertex(p, vc, mn, mx, mx, frontFace, mn, mx)
            emitVertex(p, vc, mn, mn, mx, frontFace, mn, mx)
            // -Z face.
            emitVertex(p, vc, mn, mn, mn, frontFace, mn, mx)
            emitVertex(p, vc, mn, mx, mn, frontFace, mn, mx)
            emitVertex(p, vc, mx, mx, mn, frontFace, mn, mx)
            emitVertex(p, vc, mx, mn, mn, frontFace, mn, mx)
            // +X face.
            emitVertex(p, vc, mx, mn, mn, frontFace, mn, mx)
            emitVertex(p, vc, mx, mx, mn, frontFace, mn, mx)
            emitVertex(p, vc, mx, mx, mx, frontFace, mn, mx)
            emitVertex(p, vc, mx, mn, mx, frontFace, mn, mx)
            // -X face.
            emitVertex(p, vc, mn, mn, mx, frontFace, mn, mx)
            emitVertex(p, vc, mn, mx, mx, frontFace, mn, mx)
            emitVertex(p, vc, mn, mx, mn, frontFace, mn, mx)
            emitVertex(p, vc, mn, mn, mn, frontFace, mn, mx)
            // +Y face.
            emitVertex(p, vc, mn, mx, mx, frontFace, mn, mx)
            emitVertex(p, vc, mx, mx, mx, frontFace, mn, mx)
            emitVertex(p, vc, mx, mx, mn, frontFace, mn, mx)
            emitVertex(p, vc, mn, mx, mn, frontFace, mn, mx)
            // -Y face.
            emitVertex(p, vc, mn, mn, mn, frontFace, mn, mx)
            emitVertex(p, vc, mx, mn, mn, frontFace, mn, mx)
            emitVertex(p, vc, mx, mn, mx, frontFace, mn, mx)
            emitVertex(p, vc, mn, mn, mx, frontFace, mn, mx)
        }
    }

    /** Vertex helper. Tints by position relative to [frontFace]: vertices on
     *  the front side of the cube get yellow, the back side get white. The
     *  rasterizer's per-vertex interpolation produces a smooth gradient on
     *  the four side faces. POSITION_COLOR vertex format only - no UV,
     *  normal, overlay, or lightmap, since [PipeLaserCoreRenderType] uses
     *  DEBUG_FILLED_SNIPPET. */
    private fun emitVertex(
        p: PoseStack.Pose,
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        x: Float, y: Float, z: Float,
        frontFace: Direction,
        mn: Float, mx: Float,
    ) {
        val onFront = when (frontFace) {
            Direction.NORTH -> z <= mn + 0.0001f
            Direction.SOUTH -> z >= mx - 0.0001f
            Direction.WEST -> x <= mn + 0.0001f
            Direction.EAST -> x >= mx - 0.0001f
            Direction.DOWN -> y <= mn + 0.0001f
            Direction.UP -> y >= mx - 0.0001f
        }
        val r = if (onFront) MICRO_R else 255
        val g = if (onFront) MICRO_G else 255
        val b = if (onFront) MICRO_B else 255
        vc.addVertex(p, x, y, z).setColor(r, g, b, 255)
    }
}
