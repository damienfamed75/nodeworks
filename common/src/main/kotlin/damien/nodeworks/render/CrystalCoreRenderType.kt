package damien.nodeworks.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

/**
 * Custom RenderType + RenderPipeline for the Network Controller crystal core.
 *
 * Mirrors vanilla EYES verbatim (base snippet, shaders, defines, samplers, vertex
 * format, blend, cull=off) EXCEPT depth write is enabled. EYES itself sets
 * DepthStencilState(LESS_THAN_OR_EQUAL, false), we flip the boolean to true so the
 * core occupies the depth buffer. Without that, node-to-node laser beams from
 * [NodeConnectionRenderer], which start at the controller centre and use their own
 * depth-write-off pipeline, pass right through the crystal and draw on top of it.
 *
 * Crucially the base snippet must be [RenderPipelines.MATRICES_FOG_SNIPPET] (same as
 * EYES), not `ENTITY_EMISSIVE_SNIPPET`. The latter inherits
 * MATRICES_FOG_LIGHT_DIR_SNIPPET which adds light-direction uniforms that the entity
 * shader reads even with NO_CARDINAL_LIGHTING set, resulting in subtle per-face
 * darkening. EYES skips the light-dir snippet to stay truly unshaded.
 *
 * A [LayeringTransform.VIEW_OFFSET_Z_LAYERING] nudges the geometry toward the camera
 * in view space so the core draws on top of the translucent shells regardless of
 * render-queue order.
 */
object CrystalCoreRenderType {
    private val CRYSTAL_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crystal_core.png")

    val CORE_PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("nodeworks", "pipeline/crystal_core"))
        .withVertexShader("core/entity")
        .withFragmentShader("core/entity")
        .withShaderDefine("EMISSIVE")
        .withShaderDefine("NO_OVERLAY")
        .withShaderDefine("NO_CARDINAL_LIGHTING")
        .withSampler("Sampler0")
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
        .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        .withCull(false)
        .build()

    val CORE: RenderType = RenderType.create(
        "nodeworks_crystal_core",
        RenderSetup.builder(CORE_PIPELINE)
            .withTexture("Sampler0", CRYSTAL_TEXTURE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )
}
