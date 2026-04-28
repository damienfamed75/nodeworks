package damien.nodeworks.screen.widget

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.screen.Icons
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * Parses canonical Processing-Set recipe ids (see [ProcessingSet.canonicalId]) and
 * renders them as inline icon hints for the script editor.
 *
 * Used by [ScriptEditor] via its `decorationAboveLine` and `renderDecoration`
 * callbacks, so the hints visually sit between real code lines without being
 * stored in the text buffer.
 *
 * Hint layout (15 px tall):
 *   ┌─ [raw_iron_icon] ×1  →  [iron_ingot_icon] ×1 ─┐
 */
object RecipeHintRenderer {

    /** Vertical space required for one hint line. 16px fits a 14px item with 1px top/bottom
     *  pad, ~10% smaller than the vanilla 18×16 slot proportions. */
    const val HINT_HEIGHT: Int = 16
    /** Visible item icon size in pixels. `renderItem` always emits a 16×16 quad, so we
     *  scale it via pose.scale to render at this size. */
    private const val ICON_SIZE: Int = 14
    /** Pose scale applied around `renderItem` to shrink the native 16×16 quad to ICON_SIZE. */
    private const val ITEM_SCALE: Float = ICON_SIZE / 16f  // 0.875 = 14/16
    /** Gap between adjacent ingredient entries. Width is always ICON_SIZE regardless of
     *  whether a count badge is shown, so spacing stays consistent across the row. */
    private const val ENTRY_GAP: Int = 2
    /** Horizontal slack before the crafting arrow. Negative = the arrow overlaps the
     *  trailing space of the last input icon, visually tightening the row. */
    private const val ARROW_LEFT_PAD: Int = 2
    private const val ARROW_RIGHT_PAD: Int = 2
    /** Width reserved for the Icons.ARROW_RIGHT glyph when drawn at ICON_SIZE, tinted gray. */
    private const val ARROW_ICON_SIZE: Int = 11
    private const val ARROW_TINT: Int = 0xFF888888.toInt()

    /**
     * Extract a canonical recipe id from a Lua source line, or null if the line
     * doesn't contain a `network:handle("<id>"` call. Matches the first id per line,
     * multiple handles on a single line aren't supported (and the editor's
     * auto-snippet always puts them on separate lines anyway).
     */
    fun detectHandleId(line: String): String? {
        val match = Regex("""network:handle\s*\(\s*"([^"]+)"""").find(line) ?: return null
        val id = match.groupValues[1]
        // Only treat this as a recipe if it looks canonical (contains `>>`). Arbitrary
        // string args like legacy names shouldn't trigger a hint row with broken icons.
        return if (id.contains(">>")) id else null
    }

    /**
     * Parse a canonical id into (inputs, outputs). Each list entry is (itemId, count).
     * Returns null if the id is malformed, caller should skip the hint row in that case.
     */
    fun parse(canonicalId: String): Pair<List<Pair<String, Int>>, List<Pair<String, Int>>>? {
        val parts = canonicalId.split(">>")
        if (parts.size != 2) return null
        val inputs = parseSection(parts[0]) ?: return null
        val outputs = parseSection(parts[1]) ?: return null
        return inputs to outputs
    }

    private fun parseSection(section: String): List<Pair<String, Int>>? {
        if (section.isEmpty()) return emptyList()
        val entries = section.split("|")
        val result = ArrayList<Pair<String, Int>>(entries.size)
        for (entry in entries) {
            val at = entry.lastIndexOf('@')
            if (at < 0) return null
            val id = entry.substring(0, at)
            val count = entry.substring(at + 1).toIntOrNull() ?: return null
            result.add(id to count)
        }
        return result
    }

    /**
     * Render the icon strip for [canonicalId] inside the rectangle (x, y, w, h). The
     * strip is left-aligned and truncated with a trailing ellipsis if it would overflow
     * [w]. Icons render at ICON_SIZE px, counts in small text right-aligned per vanilla.
     *
     * When [valid] is false, the band is tinted red and prefixed with a warning icon so
     * the player can spot a `network:handle("…")` whose recipe isn't currently registered
     * on any reachable Processing Storage block.
     */
    fun render(
        graphics: GuiGraphicsExtractor,
        font: Font,
        canonicalId: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        valid: Boolean = true
    ) {
        val parsed = parse(canonicalId) ?: return
        val (inputs, outputs) = parsed

        // Render the whole strip with depth WRITES disabled. Items, arrow, and count
        // badges still draw visually (depth TEST still runs, so they render at their
        // natural Z) but nothing updates the depth buffer. That lets the autocomplete
        // popup and hover tooltips, which render AFTER this decoration in the screen's
        // render loop, pass the depth test and draw on top regardless of the item's
        // internal Z push.
        //
        // This avoids the Z-frustum clipping trap: translating the pose far enough back
        // to hide items (roughly -200 to -300) also pushes flat geometry past MC's GUI
        // near clip plane and makes it disappear entirely.
        // 26.1: `RenderSystem.depthMask(false)` is gone. The old code masked depth
        //  writes so item-icon quads wouldn't occlude subsequent draws. In the new
        //  RenderPipeline system each draw's pipeline declares its own depth state,
        //  and the default GUI pipelines don't write depth, so skipping the mask
        //  is correct, not a stub.
        try {
            // Background, neutral grey when valid, dark red when the handler doesn't
            // match any known recipe so the row visually flags the problem.
            val bgColor = if (valid) 0x50505050 else 0x60601515
            graphics.fill(x, y, x + w, y + h, bgColor)
            val iconY = y + (h - ICON_SIZE) / 2
            val textY = y + (h - font.lineHeight) / 2 + 1
            val right = x + w
            var cx = x + 2

            Icons.beginBatch()
            try {
                // Warning prefix when invalid. Sized to ICON_SIZE so it sits in the same
                // visual row as the ingredient icons and clearly signals "this is broken".
                if (!valid) {
                    val warnY = y + (h - ICON_SIZE) / 2
                    Icons.WARNING.draw(graphics, cx, warnY, ICON_SIZE)
                    cx += ICON_SIZE + ENTRY_GAP
                }
                for ((idx, entry) in inputs.withIndex()) {
                    val advance = drawEntry(graphics, font, entry, cx, iconY, textY, right)
                        ?: return finishWithEllipsis(graphics, font, cx, textY)
                    cx += advance
                    if (idx != inputs.lastIndex) cx += ENTRY_GAP
                }

                if (inputs.isNotEmpty() && outputs.isNotEmpty()) {
                    cx += ARROW_LEFT_PAD
                    if (cx + ARROW_ICON_SIZE > right) return finishWithEllipsis(graphics, font, cx, textY)
                    val arrowY = y + (h - ARROW_ICON_SIZE) / 2
                    Icons.ARROW_RIGHT.drawTinted(graphics, cx, arrowY, ARROW_ICON_SIZE, ARROW_TINT)
                    cx += ARROW_ICON_SIZE + ARROW_RIGHT_PAD
                }

                for ((idx, entry) in outputs.withIndex()) {
                    val advance = drawEntry(graphics, font, entry, cx, iconY, textY, right)
                        ?: return finishWithEllipsis(graphics, font, cx, textY)
                    cx += advance
                    if (idx != outputs.lastIndex) cx += ENTRY_GAP
                }
            } finally {
                Icons.endBatch()
            }
        } finally {
            // (depthMask restore no-op, see TODO above)
        }
    }

    /** Draw one (icon + ×count) pair. Returns the horizontal advance, or null if the
     *  pair would overflow `right`, caller should finish with ellipsis. */
    private fun drawEntry(
        graphics: GuiGraphicsExtractor,
        font: Font,
        entry: Pair<String, Int>,
        cx: Int,
        iconY: Int,
        textY: Int,
        right: Int
    ): Int? {
        val (itemId, count) = entry
        val id = Identifier.tryParse(itemId) ?: return 0
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return 0
        // Advance is ALWAYS ICON_SIZE, counts overlay the icon vanilla-style and never
        // extend the entry's footprint. Ingredient spacing is therefore consistent
        // regardless of whether a given slot shows a count.
        if (cx + ICON_SIZE > right) return null

        val stack = ItemStack(item, count.coerceAtMost(64).coerceAtLeast(1))
        // Scale the 16×16 native item render down to ICON_SIZE via pose.scale. Translate
        // first so the scale's origin is at the icon's top-left.
        graphics.pose().pushMatrix()
        graphics.pose().translate(cx.toFloat(), iconY.toFloat())
        graphics.pose().scale((ITEM_SCALE).toFloat(), (ITEM_SCALE).toFloat())
        graphics.renderItem(stack, 0, 0)
        graphics.pose().popMatrix()

        // Count badge, vanilla-style positioning, scaled for the smaller icon. Vanilla
        // uses (cx + 17 - W, cy + 9) for a 16-wide cell, for our 14-wide cell that's
        // (cx + 15 - W, cy + 7), same proportions, scaled down ~10%.
        if (count > 1) {
            val countText = count.toString()
            graphics.drawString(
                font,
                countText,
                cx + (ICON_SIZE + 1) - font.width(countText),
                iconY + 7,
                0xFFFFFFFF.toInt(),
                true
            )
        }
        return ICON_SIZE
    }

    private fun finishWithEllipsis(graphics: GuiGraphicsExtractor, font: Font, cx: Int, textY: Int) {
        graphics.drawString(font, "\u2026", cx, textY, 0xFF888888.toInt(), false)
    }

    /**
     * Stack [handlers] as icon strips, one per row, starting at ([x], [y]) with width [w].
     * Non-canonical ids (no `>>`) fall back to a plain gray text label, useful for
     * legacy or user-chosen handler names. Returns the total vertical advance so the
     * caller can continue laying out below. [rowGap] is the pixel gap between rows.
     */
    fun renderHandlers(
        graphics: GuiGraphicsExtractor,
        font: Font,
        handlers: List<String>,
        x: Int,
        y: Int,
        w: Int,
        rowGap: Int = 1
    ): Int {
        if (handlers.isEmpty()) return 0
        var cy = y
        for (id in handlers) {
            if (id.contains(">>")) {
                render(graphics, font, id, x, cy, w, HINT_HEIGHT)
                cy += HINT_HEIGHT + rowGap
            } else {
                graphics.drawString(font, id, x, cy + (HINT_HEIGHT - font.lineHeight) / 2 + 1, 0xFF888888.toInt(), false)
                cy += HINT_HEIGHT + rowGap
            }
        }
        return cy - y
    }
}
