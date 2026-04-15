package damien.nodeworks.screen.widget

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.screen.Icons
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * Parses canonical Processing-Set recipe ids (see [ProcessingSet.canonicalId]) and
 * renders them as inline icon hints for the script editor.
 *
 * Used by [ScriptEditor] via its `decorationAboveLine` and `renderDecoration`
 * callbacks — so the hints visually sit between real code lines without being
 * stored in the text buffer.
 *
 * Hint layout (15 px tall):
 *   ┌─ [raw_iron_icon] ×1  →  [iron_ingot_icon] ×1 ─┐
 */
object RecipeHintRenderer {

    /** Vertical space required for one hint line. 18px fits a 16px item with 1px top/bottom
     *  pad — matches vanilla inventory slot spacing (18×18 slot, 16×16 item inset 1px). */
    const val HINT_HEIGHT: Int = 18
    /** Item icon size in pixels. `renderItem` always emits a 16×16 quad — using any smaller
     *  value causes icons to overlap the next entry because layout and render disagree. */
    private const val ICON_SIZE: Int = 16
    /** Gap between adjacent ingredient entries. Width is always ICON_SIZE regardless of
     *  whether a count badge is shown, so spacing stays consistent across the row. */
    private const val ENTRY_GAP: Int = 2
    /** Horizontal slack before the crafting arrow. Negative = the arrow overlaps the
     *  trailing space of the last input icon, visually tightening the row. */
    private const val ARROW_LEFT_PAD: Int = 2
    private const val ARROW_RIGHT_PAD: Int = 2
    /** Width reserved for the Icons.ARROW_RIGHT glyph when drawn at ICON_SIZE, tinted gray. */
    private const val ARROW_ICON_SIZE: Int = 12
    private const val ARROW_TINT: Int = 0xFF888888.toInt()

    /**
     * Extract a canonical recipe id from a Lua source line, or null if the line
     * doesn't contain a `network:handle("<id>"` call. Matches the first id per line;
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
     * Returns null if the id is malformed — caller should skip the hint row in that case.
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
     * [w]. Icons render at 12 px, counts in small text to the right of each icon.
     */
    fun render(
        graphics: GuiGraphics,
        font: Font,
        canonicalId: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val parsed = parse(canonicalId) ?: return
        val (inputs, outputs) = parsed

        // Render the whole strip with depth WRITES disabled. Items, arrow, and count
        // badges still draw visually (depth TEST still runs, so they render at their
        // natural Z) but nothing updates the depth buffer. That lets the autocomplete
        // popup and hover tooltips — which render AFTER this decoration in the screen's
        // render loop — pass the depth test and draw on top regardless of the item's
        // internal Z push.
        //
        // This avoids the Z-frustum clipping trap: translating the pose far enough back
        // to hide items (roughly -200 to -300) also pushes flat geometry past MC's GUI
        // near clip plane and makes it disappear entirely.
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false)
        try {
            // ARGB: alpha 0x50, mid-grey 0x505050 — enough contrast against the editor's
            // near-black background that the hint band reads as a distinct metadata row
            // without drawing attention away from the code itself.
            graphics.fill(x, y, x + w, y + h, 0x50505050)
            val iconY = y + (h - ICON_SIZE) / 2
            val textY = y + (h - font.lineHeight) / 2 + 1
            val right = x + w
            var cx = x + 2

            Icons.beginBatch()
            try {
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
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true)
        }
    }

    /** Draw one (icon + ×count) pair. Returns the horizontal advance, or null if the
     *  pair would overflow `right` — caller should finish with ellipsis. */
    private fun drawEntry(
        graphics: GuiGraphics,
        font: Font,
        entry: Pair<String, Int>,
        cx: Int,
        iconY: Int,
        textY: Int,
        right: Int
    ): Int? {
        val (itemId, count) = entry
        val id = ResourceLocation.tryParse(itemId) ?: return 0
        val item = BuiltInRegistries.ITEM.get(id) ?: return 0
        // Advance is ALWAYS ICON_SIZE — counts overlay the icon vanilla-style and never
        // extend the entry's footprint. Ingredient spacing is therefore consistent
        // regardless of whether a given slot shows a count.
        if (cx + ICON_SIZE > right) return null

        val stack = ItemStack(item, count.coerceAtMost(64).coerceAtLeast(1))
        graphics.renderItem(stack, cx, iconY)

        // Count badge positioned exactly like vanilla's ItemRenderer stack-count overlay:
        // right-aligned within the 16-wide icon (1px inset from the right edge), baseline
        // 9px down from the icon's top. White text with drop shadow. Hidden when 1.
        if (count > 1) {
            val countText = count.toString()
            graphics.drawString(
                font,
                countText,
                cx + 17 - font.width(countText),
                iconY + 9,
                0xFFFFFFFF.toInt(),
                true
            )
        }
        return ICON_SIZE
    }

    private fun finishWithEllipsis(graphics: GuiGraphics, font: Font, cx: Int, textY: Int) {
        graphics.drawString(font, "\u2026", cx, textY, 0xFF888888.toInt(), false)
    }
}
