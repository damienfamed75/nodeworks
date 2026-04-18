package damien.nodeworks.render

import damien.nodeworks.compat.renderItem
import net.minecraft.client.gui.GuiGraphicsExtractor
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
 *
 * The old `shaderInstance` / `renderType` fields used to be written from
 * `NeoForgeClientSetup.onRegisterShaders` — those wiring points need to be
 * rebuilt against the new RenderPipeline registration flow when this path
 * is restored.
 */
object FlatColorItemRenderer {

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
