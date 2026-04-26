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
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.Identifier

/**
 * Custom RenderType + RenderPipeline for the Diagnostic Tool's pinned-block
 * highlight, draws the block's full baked mesh tinted cyan with depth-test
 * disabled so the silhouette shows through walls.
 *
 * Pre-migration used `RenderSystem.setShader` + `BufferUploader.drawWithShader`
 * to stamp the block's quads with a custom fragment shader. Those APIs are
 * gone in MC 26.1, the replacement stack is a registered [RenderPipeline] +
 * a [RenderType] referencing it. Pattern copied from EnderIO 26.1's
 * `OutlineRenderType.CUTOUT_NO_DEPTH`, same shape (block-atlas snippet +
 * depth-always + translucent blend) but with translucent alpha instead of
 * an alpha-cutout since we want the tinted colour to blend rather than
 * hard-discard.
 */
object PinHighlightRenderType {
    /** Block-atlas pipeline with depth-test disabled and translucent blending. */
    val THROUGH_WALLS_PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("nodeworks", "pipeline/pin_highlight_through_walls"))
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build()

    val THROUGH_WALLS: RenderType = RenderType.create(
        "nodeworks_pin_highlight",
        RenderSetup.builder(THROUGH_WALLS_PIPELINE)
            .useLightmap()
            .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )
}
