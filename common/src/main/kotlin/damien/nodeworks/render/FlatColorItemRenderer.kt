package damien.nodeworks.render

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Renders an item icon in GUI with all non-transparent pixels as a flat solid color.
 *
 * ## How it works
 * A custom fragment shader (flat_color_item.fsh) outputs `vec4(vertexColor.rgb, textureColor.a)`,
 * discarding the texture's RGB and keeping only its alpha for the silhouette shape.
 * A VertexConsumer proxy overrides every vertex's color to the desired flat highlight color.
 *
 * ## Setup (per platform)
 * Each loader (Fabric/NeoForge) must:
 *   1. Register the shader via their shader event and set [shaderInstance]
 *   2. Create a RenderType using the shader and set [renderType]
 * See: NeoForgeClientSetup.onRegisterShaders() / NodeworksClient.onInitializeClient()
 *
 * ## Maintenance
 * - Shader files: assets/nodeworks/shaders/core/flat_color_item.{json,vsh,fsh}
 * - Access transformers/wideners: needed for RenderType.create() and CompositeState
 * - If MC updates change VertexConsumer API, update ColorOverrideConsumer
 * - If MC updates change shader JSON format, update the .json file
 */
object FlatColorItemRenderer {

    /** Custom shader instance — set during platform shader registration. */
    var shaderInstance: ShaderInstance? = null

    /** Custom RenderType using the flat color shader — set by platform client setup. */
    var renderType: RenderType? = null

    /**
     * Render an item at GUI position with every visible pixel as a flat solid color.
     * Falls back to tinted rendering if the custom shader hasn't loaded.
     */
    fun renderFlatColorItem(
        graphics: GuiGraphics,
        stack: ItemStack,
        x: Int, y: Int,
        color: Int,
        alpha: Int = 200
    ) {
        if (stack.isEmpty) return
        val rt = renderType
        if (rt == null) {
            // Shader not loaded — fall back to tinted render (imperfect but functional)
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, alpha / 255f)
            graphics.renderItem(stack, x, y)
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            return
        }

        val mc = Minecraft.getInstance()
        val model = mc.itemRenderer.getModel(stack, null, null, 0)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x + 8f, y + 8f, 150f)
        pose.scale(16f, -16f, 16f)

        val proxy = object : MultiBufferSource {
            override fun getBuffer(ignored: RenderType): VertexConsumer {
                return ColorOverrideConsumer(graphics.bufferSource().getBuffer(rt), r, g, b, alpha)
            }
        }

        mc.itemRenderer.render(stack, ItemDisplayContext.GUI, false, pose, proxy, 0xF000F0,
            OverlayTexture.NO_OVERLAY, model)
        graphics.bufferSource().endBatch()
        pose.popPose()
    }

    /** Vertex consumer that replaces color on every vertex while passing all other data through. */
    private class ColorOverrideConsumer(
        private val d: VertexConsumer,
        private val r: Int, private val g: Int, private val b: Int, private val a: Int
    ) : VertexConsumer {
        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer { d.addVertex(x, y, z); return this }
        override fun setColor(r: Int, g: Int, b: Int, a: Int): VertexConsumer { d.setColor(this.r, this.g, this.b, this.a); return this }
        override fun setUv(u: Float, v: Float): VertexConsumer { d.setUv(u, v); return this }
        override fun setUv1(u: Int, v: Int): VertexConsumer { d.setUv1(u, v); return this }
        override fun setUv2(u: Int, v: Int): VertexConsumer { d.setUv2(u, v); return this }
        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer { d.setNormal(x, y, z); return this }
    }
}
