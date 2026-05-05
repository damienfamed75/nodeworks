package damien.nodeworks.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

/**
 * Tiny solid-coloured cube at the centre of Pipe junctions and Nodes, drawn
 * over the network laser beams to mask any seam where multiple half-beams
 * meet at the centre. Uses [RenderPipelines.DEBUG_FILLED_SNIPPET] (POSITION
 * + COLOR, no texture sampler) so the colour is pure vertex-driven.
 *
 * Two tricks copied from [CrystalCoreRenderType] to make sure the cube
 * actually appears on top of the laser beams (which are translucent and
 * also pass through the same volume):
 *  1. Depth-write on. The default for DEBUG_FILLED_SNIPPET leaves
 *     `LESS_THAN_OR_EQUAL` with write off, that lets the beam quads
 *     overdraw the cube despite being submitted earlier. Flipping the
 *     write flag to true makes the cube occupy the depth buffer.
 *  2. [LayeringTransform.VIEW_OFFSET_Z_LAYERING] pulls the geometry a
 *     hair toward the camera in view space, so the cube wins any tie with
 *     the billboarded beam quad's exact-centre depth.
 */
object PipeLaserCoreRenderType {
    val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("nodeworks", "pipeline/pipe_laser_core"))
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        .build()

    val RENDER_TYPE: RenderType = RenderType.create(
        "nodeworks_pipe_laser_core",
        RenderSetup.builder(PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )
}
