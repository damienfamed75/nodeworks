package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * Helper for drawing 16x16 icons from the shared icons atlas.
 *
 * Each icon occupies a 16x16 cell on a 256x256 texture, addressed by column and row.
 *
 * 26.1: all the per-draw `RenderSystem.enableBlend / defaultBlendFunc / disableBlend`
 * sandwiches from pre-migration are gone — the GUI pipeline that `graphics.blit`
 * routes through sets those states internally. Same story for
 * `RenderSystem.setShaderColor`: tints are now per-draw ARGB arguments via the
 * tinted blit overload in compat/GuiCompat.kt. [beginBatch]/[endBatch] stay as a
 * public contract in case future rendering batches need explicit start/end hooks,
 * but in 26.1 they're currently no-ops.
 */
class Icons private constructor(val col: Int, val row: Int) {

    val u: Int get() = col * 16
    val v: Int get() = row * 16

    /** Draw this icon at full 16x16 size. */
    fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.blit(ATLAS, x, y, u.toFloat(), v.toFloat(), 16, 16, 256, 256)
    }

    /** Draw this icon scaled to a custom size. */
    fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        graphics.blit(ATLAS, x, y, size, size, u.toFloat(), v.toFloat(), 16, 16, 256, 256)
    }

    /** Draw the center 8x8 of this icon (cropped 4px inset). */
    fun drawSmall(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.blit(ATLAS, x, y, (u + 4).toFloat(), (v + 4).toFloat(), 8, 8, 256, 256)
    }

    /** Draw a center 10x8 slice of this icon (3px horizontal inset, 4px vertical
     *  inset). Use for cells whose artwork extends wider than 8px and would lose
     *  edge columns under [drawSmall]'s 4px crop — typically when the small render
     *  needs to preserve the leftmost / rightmost authored pixels. */
    fun drawSmallWide(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.blit(ATLAS, x, y, (u + 3).toFloat(), (v + 4).toFloat(), 10, 8, 256, 256)
    }

    /** Draw only the top-left [w] × [h] region of this cell, at its native size. Useful for
     *  icons smaller than 16×16 (e.g. a 5×5 X) authored in the top-left corner of a cell. */
    fun drawTopLeft(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
        graphics.blit(ATLAS, x, y, u.toFloat(), v.toFloat(), w, h, 256, 256)
    }

    /** Draw only the top-left [w] × [h] region tinted with an RGB color. */
    fun drawTopLeftTinted(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Int,
        alpha: Float = 1f
    ) {
        graphics.blit(ATLAS, x, y, u.toFloat(), v.toFloat(), w, h, 256, 256, packArgb(color, alpha))
    }

    /** Draw the center portion of this icon scaled to a custom size. */
    fun drawSmall(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        graphics.blit(ATLAS, x, y, size, size, (u + 4).toFloat(), (v + 4).toFloat(), 8, 8, 256, 256)
    }

    /** Draw this icon tinted with an RGB color. Respects the icon's alpha channel. */
    fun drawTinted(graphics: GuiGraphicsExtractor, x: Int, y: Int, color: Int, alpha: Float = 1f) {
        graphics.blit(ATLAS, x, y, u.toFloat(), v.toFloat(), 16, 16, 256, 256, packArgb(color, alpha))
    }

    /** Draw this icon tinted and scaled to a custom size. */
    fun drawTinted(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int, alpha: Float = 1f) {
        // No stretched-tinted overload in compat yet — use the non-stretched form with matching size.
        // 26.1 GuiGraphicsExtractor.blit with tint requires the full `(pipeline, tex, x, y, u, v,
        //  drawW, drawH, srcW, srcH, texW, texH, argb)` form; the stretched + tinted path isn't
        //  needed yet so we render at native 16x16 into a size×size box by passing width=size,
        //  height=size and letting the shader stretch.
        graphics.blit(
            net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            ATLAS, x, y, u.toFloat(), v.toFloat(),
            size, size, 16, 16, 256, 256,
            packArgb(color, alpha)
        )
    }

    companion object {
        val ATLAS: Identifier = Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")

        /** Pack an RGB [color] + [alpha]∈[0..1] into 0xAARRGGBB for the GUI blit overload. */
        private fun packArgb(color: Int, alpha: Float): Int {
            val a = (alpha.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
            return (a shl 24) or (color and 0xFFFFFF)
        }

        /** Kept as public API — pre-migration callers wrapped multi-icon draws in
         *  begin/end to batch the old `enableBlend`/`disableBlend` state changes. The
         *  26.1 GUI pipeline handles blend state per-draw internally, so these are
         *  now no-ops; leaving them in keeps call-site code forward-compatible with
         *  future explicit batching. */
        fun beginBatch() {}
        fun endBatch() {}

        // =====================================================================
        // Atlas Layout Reference (icons.png, 256x256, 16x16 per cell)
        // =====================================================================
        //
        // Col:    0            1            2            3             4           5            6              7              8
        // Row 0:  Checkmark    X            ArrowRight   ArrowLeft     Unpinned    Pinned       RedstoneIgnore RedstoneActive RedstoneInactive GlowSquare GlowCircle GlowDot GlowCreeper GlowCat GlowNone
        // Row 1:  IO Card      Storage Card Redstone Card Variable     CrystalInactive CrystalActive LayoutSmall LayoutWide LayoutTall LayoutLarge SmallScrew
        // Row 2:  CopyIdle     CopyHover    CopyPressed  TrashIdle     TrashHover  TrashPressed CollapseIdle CollapseHover CollapsePressed ExpandIdle ExpandHover ExpandPressed Badge FluidAndItems ItemsOnly FluidsOnly
        // Row 3:  SortAlpha    SortCountDesc SortCountAsc FilterStorage FilterRecipes FilterBoth AutoFocusOn AutoFocusOff CraftInProgress CraftComplete CraftPlus AutoPullOn AutoPullOff CraftGridClear CraftGridDistribute
        // =====================================================================

        // Row 0 — General UI icons
        val CHECKMARK = Icons(0, 0)
        val X = Icons(1, 0)
        val ARROW_RIGHT = Icons(2, 0)
        val ARROW_LEFT = Icons(3, 0)
        val UNPINNED = Icons(4, 0)
        val PINNED = Icons(5, 0)
        val REDSTONE_IGNORE = Icons(6, 0)
        val REDSTONE_ACTIVE = Icons(7, 0)
        val REDSTONE_INACTIVE = Icons(8, 0)
        val GLOW_SQUARE = Icons(9, 0)
        val GLOW_CIRCLE = Icons(10, 0)
        val GLOW_DOT = Icons(11, 0)
        val GLOW_CREEPER = Icons(12, 0)
        val GLOW_CAT = Icons(13, 0)
        val GLOW_NONE = Icons(14, 0)
        val CRYSTAL_INACTIVE = Icons(4, 1)
        val CRYSTAL_ACTIVE = Icons(5, 1)
        val LAYOUT_SMALL = Icons(6, 1)
        val LAYOUT_WIDE = Icons(7, 1)
        val LAYOUT_TALL = Icons(8, 1)
        val LAYOUT_LARGE = Icons(9, 1)
        val SMALL_SCREW = Icons(10, 1)
        val NETWORK = Icons(11, 1)
        val FIRE = Icons(12, 1)
        val SNOWBALL = Icons(13, 1)
        val WARNING = Icons(14, 1)
        val X_SMALL = Icons(15, 1)  // 5×5 in top-left corner of the cell

        // Row 1 — Card type icons
        val IO_CARD = Icons(0, 1)
        val STORAGE_CARD = Icons(1, 1)
        val REDSTONE_CARD = Icons(2, 1)
        val VARIABLE = Icons(3, 1)

        // Row 2 — Button state icons
        val COPY_IDLE = Icons(0, 2)
        val COPY_HOVER = Icons(1, 2)
        val COPY_PRESSED = Icons(2, 2)
        val TRASH_IDLE = Icons(3, 2)
        val TRASH_HOVER = Icons(4, 2)
        val TRASH_PRESSED = Icons(5, 2)
        val COLLAPSE_IDLE = Icons(6, 2)
        val COLLAPSE_HOVER = Icons(7, 2)
        val COLLAPSE_PRESSED = Icons(8, 2)
        val EXPAND_IDLE = Icons(9, 2)
        val EXPAND_HOVER = Icons(10, 2)
        val EXPAND_PRESSED = Icons(11, 2)
        val BADGE = Icons(12, 2) // 9x9 in top-left corner of the cell
        val FLUID_AND_ITEMS = Icons(13, 2)
        val ITEMS_ONLY = Icons(14, 2)
        val FLUIDS_ONLY = Icons(15, 2)

        // Row 3 — Inventory Terminal icons
        val SORT_ALPHA = Icons(0, 3)
        val SORT_COUNT_DESC = Icons(1, 3)
        val SORT_COUNT_ASC = Icons(2, 3)
        val FILTER_STORAGE = Icons(3, 3)
        val FILTER_RECIPES = Icons(4, 3)
        val FILTER_BOTH = Icons(5, 3)
        val AUTO_FOCUS_ON = Icons(6, 3)
        val AUTO_FOCUS_OFF = Icons(7, 3)
        val CRAFTING_IN_PROGRESS = Icons(8, 3)
        val CRAFTING_COMPLETE = Icons(9, 3)
        val CRAFT_PLUS = Icons(10, 3)
        val AUTO_PULL_ON = Icons(11, 3)
        val AUTO_PULL_OFF = Icons(12, 3)
        val CRAFTING_GRID_CLEAR = Icons(13, 3)
        val CRAFTING_GRID_DISTRIBUTE = Icons(14, 3)
        val RESERVED_SLOT = Icons(15, 3)

        // Row 4 — More Card / Device Icons
        val OBSERVER_CARD = Icons(0, 4)
        val BREAKER = Icons(1, 4)
        val PLACER = Icons(2, 4)
    }
}
