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
 * Solid white cube drawn at the centre of Pipe junctions and Nodes to mask
 * the seam where multiple half-beams meet. Vertex-coloured (POSITION+COLOR,
 * no texture sampler), depth-write enabled, and pulled toward the camera
 * via VIEW_OFFSET_Z_LAYERING so it always wins the depth tie with the
 * translucent beam quads passing through the same point. Same approach
 * [CrystalCoreRenderType] uses for the controller crystal core.
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
