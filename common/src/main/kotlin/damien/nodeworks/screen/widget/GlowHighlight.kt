package damien.nodeworks.screen.widget

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Shared XP-orb-style radial-glow helper.
 *
 * Stacks four concentric filled discs from a wide faint outer disc inward to a tight
 * bright inner disc. Each disc is rendered row-by-row — for each `y` offset inside
 * the disc we compute the half-width `dx = √(r² - dy²)` and emit a single
 * 1px-tall [graphics.fill] rect spanning the chord. Alpha-over blending accumulates
 * toward the centre, giving a smooth radial fall-off with genuinely circular shape
 * (unlike concentric rects which always read as square).
 *
 * Used by the Crafting Tree item-status halo ([CraftTreeGraph.drawItemHalo]) and by
 * the Diagnostic topology view's block-selection highlight — same visual language so
 * a "selected" or "active" thing looks consistent across the mod.
 */
object GlowHighlight {

    /**
     * Draw a square-box halo centered on the icon/block at ([x], [y]) with the given
     * [size] (icon or block size in px). The halo extends ≈4px beyond [size]/2 so it
     * reads as an aura, not an edge treatment. [color] is `0xAARRGGBB` — only the RGB
     * channels are used; alpha comes from the internal ring table.
     *
     * Callers typically draw the icon/block AFTER this helper so the glow bleeds
     * through transparent/empty pixels around the subject.
     */
    fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int) {
        val rgb = color and 0xFFFFFF
        val cx = x + size / 2
        val cy = y + size / 2
        // Outer → inner: alpha ramps up so overlap at the centre feels bright without
        // washing out. Radii scale with the subject size so the aura grows with bigger
        // targets — the base case (size=16) gives 13/11/9/7 like the original halo.
        val base = size / 2
        val rings = arrayOf(
            (base + 5) to 0x10,
            (base + 3) to 0x1C,
            (base + 1) to 0x30,
            (base - 1) to 0x48,
        )
        for ((radius, alpha) in rings) {
            if (radius > 0) fillDisc(graphics, cx, cy, radius, (alpha shl 24) or rgb)
        }
    }

    /** Filled-circle primitive. Emits one 1px-tall rect per scanline of the disc. */
    private fun fillDisc(graphics: GuiGraphicsExtractor, cx: Int, cy: Int, radius: Int, argb: Int) {
        val r2 = radius * radius
        for (dy in -radius..radius) {
            val dx = kotlin.math.sqrt((r2 - dy * dy).toDouble()).toInt()
            graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, argb)
        }
    }
}
