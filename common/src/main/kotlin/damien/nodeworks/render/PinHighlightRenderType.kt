package damien.nodeworks.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

/**
 * Through-walls highlight pipeline shared by the wrench selection and the
 * diagnostic-tool pin. POSITION_COLOR (no texture sampler) keeps the tint
 * pure, additive blend lets it stack into a bright halo without depth-sort.
 */
object PinHighlightRenderType {
    val THROUGH_WALLS_PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("nodeworks", "pipeline/pin_highlight_through_walls"))
        .withColorTargetState(ColorTargetState(BlendFunction.ADDITIVE))
        .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build()

    val THROUGH_WALLS: RenderType = RenderType.create(
        "nodeworks_pin_highlight",
        RenderSetup.builder(THROUGH_WALLS_PIPELINE)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )
}
