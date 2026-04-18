package damien.nodeworks.render

import damien.nodeworks.compat.renderItem
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.world.item.ItemStack

/**
 * Renders an item icon in GUI with all non-transparent pixels as a flat solid color.
 *
 * TODO MC 26.1.2 SHADER PIPELINE REWRITE — fallback path only.
 *
 * Pre-migration: used a custom fragment shader (flat_color_item.fsh) + a
 * bespoke RenderType built via RenderType.create() (see the AT file) to
 * stamp the item silhouette in a single flat color. Consumed a custom
 * VertexConsumer proxy (ColorOverrideConsumer) that overrode every vertex's
 * RGB.
 *
 * MC 26.1 replaced the CompositeState/RenderStateShard/ShaderInstance path
 * with the RenderPipeline / GpuPipeline abstraction. The custom shader
 * needs to be migrated to the new RenderPipeline system and the
 * ColorOverrideConsumer replaced with the GuiRenderState color channel.
 *
 * Until that's done we fall back to just drawing the item un-tinted. This
 * is a visual regression (tinted card icons on Processing / Instruction
 * Storage block faces appear in their normal colors), not a functional one.
 */
object FlatColorItemRenderer {

    /** Custom shader instance — set during platform shader registration. Not used by the fallback. */
    var shaderInstance: ShaderInstance? = null

    /** Custom RenderType using the flat color shader — set by platform client setup. Not used by the fallback. */
    var renderType: RenderType? = null

    /**
     * Render an item at GUI position with every visible pixel as a flat solid color.
     * Until the shader pipeline is ported to 26.1 this just draws the item
     * normally (ignoring `color`/`alpha`/`scale`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun renderFlatColorItem(
        graphics: GuiGraphicsExtractor,
        stack: ItemStack,
        x: Int, y: Int,
        color: Int,
        alpha: Int = 200,
        scale: Float = 1f
    ) {
        if (stack.isEmpty) return
        graphics.renderItem(stack, x, y)
    }
}
