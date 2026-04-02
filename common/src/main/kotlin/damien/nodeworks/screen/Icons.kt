package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

/**
 * Helper for drawing 16x16 icons from the shared icons atlas.
 *
 * Each icon occupies a 16x16 cell on a 256x256 texture, addressed by column and row.
 *
 * @param col Column index (0-based, each column = 16px)
 * @param row Row index (0-based, each row = 16px)
 */
class Icons private constructor(val col: Int, val row: Int) {

    val u: Int get() = col * 16
    val v: Int get() = row * 16

    /**
     * Draw this icon at full 16x16 size.
     */
    fun draw(graphics: GuiGraphics, x: Int, y: Int) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        graphics.blit(ATLAS, x, y, u.toFloat(), v.toFloat(), 16, 16, 256, 256)
        com.mojang.blaze3d.systems.RenderSystem.disableBlend()
    }

    /**
     * Draw this icon scaled to a custom size.
     */
    fun draw(graphics: GuiGraphics, x: Int, y: Int, size: Int) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        graphics.blit(ATLAS, x, y, size, size, u.toFloat(), v.toFloat(), 16, 16, 256, 256)
        com.mojang.blaze3d.systems.RenderSystem.disableBlend()
    }

    /**
     * Draw the center 8x8 of this icon (cropped 4px inset), useful for small inline icons.
     */
    fun drawSmall(graphics: GuiGraphics, x: Int, y: Int) {
        graphics.blit(ATLAS, x, y, (u + 4).toFloat(), (v + 4).toFloat(), 8, 8, 256, 256)
    }

    /**
     * Draw the center portion of this icon scaled to a custom size.
     */
    fun drawSmall(graphics: GuiGraphics, x: Int, y: Int, size: Int) {
        graphics.blit(ATLAS, x, y, size, size, (u + 4).toFloat(), (v + 4).toFloat(), 8, 8, 256, 256)
    }

    /**
     * Draw this icon tinted with an RGB color. Respects the icon's alpha channel.
     */
    fun drawTinted(graphics: GuiGraphics, x: Int, y: Int, color: Int, alpha: Float = 1f) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, alpha)
        graphics.blit(ATLAS, x, y, u.toFloat(), v.toFloat(), 16, 16, 256, 256)
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        com.mojang.blaze3d.systems.RenderSystem.disableBlend()
    }

    companion object {
        val ATLAS: ResourceLocation = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")

        // =====================================================================
        // Atlas Layout Reference (icons.png, 256x256, 16x16 per cell)
        // =====================================================================
        //
        // Col:    0            1            2            3             4           5            6              7              8
        // Row 0:  Checkmark    X            ArrowRight   ArrowLeft     Unpinned    Pinned       RedstoneIgnore RedstoneActive RedstoneInactive GlowSquare GlowCircle GlowDot GlowCreeper GlowCat GlowNone
        // Row 1:  IO Card      Storage Card Redstone Card Variable     CrystalInactive CrystalActive LayoutSmall LayoutWide LayoutTall LayoutLarge
        // Row 2:  CopyIdle     CopyHover    CopyPressed  TrashIdle     TrashHover  TrashPressed
        // Row 3+: (free)
        // =====================================================================

        // Row 0 — General UI icons
        val CHECKMARK     = Icons(0, 0)
        val X             = Icons(1, 0)
        val ARROW_RIGHT   = Icons(2, 0)
        val ARROW_LEFT    = Icons(3, 0)
        val UNPINNED          = Icons(4, 0)
        val PINNED            = Icons(5, 0)
        val REDSTONE_IGNORE   = Icons(6, 0)
        val REDSTONE_ACTIVE   = Icons(7, 0)
        val REDSTONE_INACTIVE = Icons(8, 0)
        val GLOW_SQUARE       = Icons(9, 0)
        val GLOW_CIRCLE       = Icons(10, 0)
        val GLOW_DOT          = Icons(11, 0)
        val GLOW_CREEPER      = Icons(12, 0)
        val GLOW_CAT          = Icons(13, 0)
        val GLOW_NONE         = Icons(14, 0)
        val CRYSTAL_INACTIVE  = Icons(4, 1)
        val CRYSTAL_ACTIVE    = Icons(5, 1)
        val LAYOUT_SMALL      = Icons(6, 1)
        val LAYOUT_WIDE       = Icons(7, 1)
        val LAYOUT_TALL       = Icons(8, 1)
        val LAYOUT_LARGE      = Icons(9, 1)

        // Row 1 — Card type icons
        val IO_CARD       = Icons(0, 1)
        val STORAGE_CARD  = Icons(1, 1)
        val REDSTONE_CARD = Icons(2, 1)
        val VARIABLE      = Icons(3, 1)

        // Row 2 — Button state icons
        val COPY_IDLE     = Icons(0, 2)
        val COPY_HOVER    = Icons(1, 2)
        val COPY_PRESSED  = Icons(2, 2)
        val TRASH_IDLE    = Icons(3, 2)
        val TRASH_HOVER   = Icons(4, 2)
        val TRASH_PRESSED = Icons(5, 2)
    }
}
