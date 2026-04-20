package damien.nodeworks.platform

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * NeoForge implementation of [FluidSpriteRenderer]. Pulls the fluid's still texture
 * straight from the client's fluid-model registry (the same data the world renderer
 * uses for block-side fluid rendering) so modded fluids without bucket items still
 * render recognizably.
 *
 * 26.1 moved fluid client assets onto `FluidModel` (stillMaterial / flowingMaterial /
 * tintSource) instead of the NeoForge `IClientFluidTypeExtensions.getStillTexture()`
 * hook that older versions exposed; the public accessor is
 * `ModelManager#getFluidStateModelSet()`.
 */
class NeoForgeFluidSpriteRenderer : FluidSpriteRenderer {
    override fun render(graphics: GuiGraphicsExtractor, fluidId: String, x: Int, y: Int, size: Int) {
        val id = Identifier.tryParse(fluidId) ?: return fallback(graphics, x, y, size)
        val fluid = BuiltInRegistries.FLUID.getValue(id) ?: return fallback(graphics, x, y, size)
        val fluidState = fluid.defaultFluidState()
        val models = Minecraft.getInstance().modelManager.fluidStateModelSet ?: return fallback(graphics, x, y, size)
        val fluidModel = models.get(fluidState) ?: return fallback(graphics, x, y, size)
        val sprite = fluidModel.stillMaterial().sprite() ?: return fallback(graphics, x, y, size)

        // BlockTintSource.color(BlockState) returns the "no-world-context" tint.
        // Biome-dependent sources (water) return -1 here and defer to colorInWorld,
        // which needs a BlockAndTintGetter we don't have in a GUI. When the tint
        // source gives us -1/white, fall back to a known-good default so vanilla
        // water doesn't render gray. Modded fluids with constant tints return their
        // real color from color(state) and bypass the fallback cleanly.
        val rawTint = try {
            fluidModel.tintSource()?.color(fluidState.createLegacyBlock()) ?: -1
        } catch (_: Exception) {
            -1
        }
        val tintRgb = if ((rawTint and 0xFFFFFF) == 0xFFFFFF) defaultTintFor(fluidId) else (rawTint and 0xFFFFFF)
        val argb = 0xFF shl 24 or (tintRgb and 0xFFFFFF)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, size, size, argb)
    }

    private fun fallback(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        graphics.fill(x, y, x + size, y + size, 0xFF808080.toInt())
    }

    /** Per-fluid defaults when the tint source has no static color (biome-dependent). */
    private fun defaultTintFor(fluidId: String): Int = when (fluidId) {
        "minecraft:water" -> 0x3F76E4 // vanilla default water color
        else -> 0xFFFFFF
    }
}
