package damien.nodeworks.screen.widget

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo
import damien.nodeworks.screen.Icons
import damien.nodeworks.script.RecipeId
import damien.nodeworks.script.RecipeIngredient
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.renderItem
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

/**
 * Renders a recipe id (`recipe_<hex>` hash) as an inline input → output icon
 * strip for the script editor and diagnostic panels. The renderer never parses
 * the recipe out of the id, callers pass in (or supply a resolver for) the
 * resolved [ProcessingApiInfo] so component-bearing ingredients (potions,
 * dyed items, enchanted books) display the actual variant.
 *
 * Used by [TerminalScreen] via `decorationAboveLine` / `renderDecoration` and by
 * [DiagnosticScreen] for the per-terminal handler list. Hints are not stored in
 * the editor's text buffer.
 *
 * Hint layout (16 px tall):
 *   [icon ×n]  →  [icon ×n]
 */
object RecipeHintRenderer {

    /** Vertical space required for one hint line. 16px fits a 14px item with 1px top/bottom
     *  pad, ~10% smaller than the vanilla 18×16 slot proportions. */
    const val HINT_HEIGHT: Int = 16
    private const val ICON_SIZE: Int = 14
    private const val ITEM_SCALE: Float = ICON_SIZE / 16f
    private const val ENTRY_GAP: Int = 2
    private const val ARROW_LEFT_PAD: Int = 2
    private const val ARROW_RIGHT_PAD: Int = 2
    private const val ARROW_ICON_SIZE: Int = 11
    private const val ARROW_TINT: Int = 0xFF888888.toInt()

    /** Regex for `recipe_<hex>` literals (RecipeId emits 12-hex but the matcher
     *  accepts any positive length so format changes don't silently break the
     *  hint row). */
    private val RECIPE_ID_REGEX = Regex("""recipe_[0-9a-f]+""")

    /** First quoted string arg inside a `network:handle(` or `network:craft(`
     *  call, or null when there's none on this line. The script editor's
     *  auto-snippet puts handle calls on their own line so a single-line scan
     *  is sufficient. */
    private val HANDLE_CALL_REGEX = Regex("""network:(?:handle|craft)\s*\(\s*"([^"]+)"""")

    /**
     * Extract a `recipe_<hex>` id from a Lua source line, or null if the line
     * doesn't reference a recipe id. Returns the literal text (no normalization)
     * so callers can resolve it directly against the snapshot.
     */
    fun detectHandleId(line: String): String? {
        val match = HANDLE_CALL_REGEX.find(line) ?: return null
        val arg = match.groupValues[1]
        return if (RecipeId.isRecipeId(arg)) arg else null
    }

    /**
     * Render an icon strip for an already-resolved recipe. [inputs] and
     * [outputs] supply the component-bearing ItemStacks. When [valid] is false
     * the band is tinted red and a warning glyph prefixes the row.
     */
    fun render(
        graphics: GuiGraphicsExtractor,
        font: Font,
        inputs: List<RecipeIngredient>,
        outputs: List<RecipeIngredient>,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        valid: Boolean = true,
    ) {
        // Items, arrow, and count badges all draw within the strip rectangle.
        // The default GUI pipelines don't write depth, so subsequent draws
        // (autocomplete popup, hover tooltips) overlay cleanly without needing
        // an explicit depth mask.
        val bgColor = if (valid) 0x50505050 else 0x60601515
        graphics.fill(x, y, x + w, y + h, bgColor)
        val iconY = y + (h - ICON_SIZE) / 2
        val textY = y + (h - font.lineHeight) / 2 + 1
        val right = x + w
        var cx = x + 2

        Icons.beginBatch()
        try {
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
    }

    /**
     * Resolve [recipeId] via [resolver] and render. Draws the red "missing"
     * placeholder (warning glyph plus the raw hash) when the resolver returns
     * null, so the player sees a clear cue that the script references a recipe
     * that isn't on the network.
     */
    fun renderById(
        graphics: GuiGraphicsExtractor,
        font: Font,
        recipeId: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        resolver: (String) -> ProcessingApiInfo?,
    ) {
        val api = resolver(recipeId)
        if (api != null) {
            render(graphics, font, api.inputs, api.outputs, x, y, w, h, valid = true)
        } else {
            renderMissing(graphics, font, recipeId, x, y, w, h)
        }
    }

    /** Draw one (icon + ×count) pair from a [RecipeIngredient]. Returns the
     *  horizontal advance, or null if the entry would overflow `right` (caller
     *  finishes with an ellipsis). */
    private fun drawEntry(
        graphics: GuiGraphicsExtractor,
        font: Font,
        entry: RecipeIngredient,
        cx: Int,
        iconY: Int,
        textY: Int,
        right: Int,
    ): Int? {
        if (cx + ICON_SIZE > right) return null
        val stack = entry.stack.copyWithCount(entry.count.coerceAtMost(64).coerceAtLeast(1))

        graphics.pose().pushMatrix()
        graphics.pose().translate(cx.toFloat(), iconY.toFloat())
        graphics.pose().scale(ITEM_SCALE, ITEM_SCALE)
        graphics.renderItem(stack, 0, 0)
        graphics.pose().popMatrix()

        if (entry.count > 1) {
            val countText = entry.count.toString()
            graphics.drawString(
                font,
                countText,
                cx + (ICON_SIZE + 1) - font.width(countText),
                iconY + 7,
                0xFFFFFFFF.toInt(),
                true,
            )
        }
        return ICON_SIZE
    }

    /** Red-tinted "recipe not on network" placeholder. Shows the warning glyph
     *  plus an explanatory message, with the raw id appended when there's room
     *  so the player can copy or search for it. */
    private fun renderMissing(
        graphics: GuiGraphicsExtractor,
        font: Font,
        recipeId: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        graphics.fill(x, y, x + w, y + h, 0x60601515)
        val iconY = y + (h - ICON_SIZE) / 2
        val textY = y + (h - font.lineHeight) / 2 + 1
        var cx = x + 2
        Icons.beginBatch()
        try {
            Icons.WARNING.draw(graphics, cx, iconY, ICON_SIZE)
            cx += ICON_SIZE + ENTRY_GAP
            val maxTextW = (x + w) - cx - 2
            val label = "Unknown recipe"
            val full = "$label: $recipeId"
            val text = when {
                font.width(full) <= maxTextW -> full
                font.width(label) <= maxTextW -> label
                else -> font.plainSubstrByWidth(label, maxTextW - 4) + "…"
            }
            graphics.drawString(font, text, cx, textY, 0xFFCC8888.toInt(), false)
        } finally {
            Icons.endBatch()
        }
    }

    private fun finishWithEllipsis(graphics: GuiGraphicsExtractor, font: Font, cx: Int, textY: Int) {
        graphics.drawString(font, "…", cx, textY, 0xFF888888.toInt(), false)
    }

    /**
     * Stack [handlers] as icon strips, one per row, starting at ([x], [y]) with
     * width [w]. Recipe-hash ids resolve via [resolver]. Non-hash names
     * (legacy / Lua handler labels) fall back to plain gray text. Returns the
     * total vertical advance so the caller can continue laying out below.
     */
    fun renderHandlers(
        graphics: GuiGraphicsExtractor,
        font: Font,
        handlers: List<String>,
        x: Int,
        y: Int,
        w: Int,
        rowGap: Int = 1,
        resolver: (String) -> ProcessingApiInfo? = { null },
    ): Int {
        if (handlers.isEmpty()) return 0
        var cy = y
        for (id in handlers) {
            if (RecipeId.isRecipeId(id)) {
                renderById(graphics, font, id, x, cy, w, HINT_HEIGHT, resolver)
            } else {
                graphics.drawString(font, id, x, cy + (HINT_HEIGHT - font.lineHeight) / 2 + 1, 0xFF888888.toInt(), false)
            }
            cy += HINT_HEIGHT + rowGap
        }
        return cy - y
    }
}
