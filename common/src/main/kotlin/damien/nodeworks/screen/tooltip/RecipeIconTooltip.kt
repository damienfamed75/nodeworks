package damien.nodeworks.screen.tooltip

import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.screen.Icons
import damien.nodeworks.screen.NineSlice
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.ItemStack

/**
 * Hover-tooltip image showing a recipe as an input grid, 3 wide, then an arrow, then an
 * output column. Mirrors the picker-thumbnail layout used by the Processing Handler.
 *
 * [inputs] is aligned to grid slot indices (`row * 3 + col`), with [ItemStack.EMPTY]
 * entries for unfilled cells, any length up to 27. [outputs] is rendered top to bottom
 * in the right column, up to 9. The picture shows the rows the recipe uses, at least the
 * three a small recipe always had, so a SAG Mill shows four outputs and a wide machine
 * shows its whole grid.
 */
data class RecipeIconTooltip(
    val inputs: List<ItemStack>,
    val outputs: List<ItemStack>,
    /** Indices into [outputs] the machine only sometimes makes, drawn with a `?` badge
     *  like the Processing Set editor shows them. */
    val optionalOutputs: Set<Int> = emptySet(),
) : TooltipComponent {
    companion object {
        const val COLS = 3
        const val MIN_ROWS = 3
        const val MAX_INPUT_ROWS = 9
        const val MAX_OUTPUTS = 9
    }

    /** Input rows drawn: the last filled row, at least [MIN_ROWS], at most [MAX_INPUT_ROWS]. */
    val inputRows: Int get() {
        var last = -1
        for ((i, s) in inputs.withIndex()) if (!s.isEmpty) last = i
        return ((last / COLS) + 1).coerceIn(MIN_ROWS, MAX_INPUT_ROWS)
    }

    /** Output cells drawn: the outputs present, at least [MIN_ROWS], at most [MAX_OUTPUTS]. */
    val outputRows: Int get() = outputs.size.coerceIn(MIN_ROWS, MAX_OUTPUTS)
}

/** Client-side renderer for [RecipeIconTooltip]. Lays out:
 *  ```
 *  ┌──┬──┬──┐    ┌──┐
 *  │  │  │  │    │  │
 *  ├──┼──┼──┤ →  ├──┤
 *  │  │  │  │    │  │
 *  ├──┼──┼──┤    ├──┤
 *  │  │  │  │    │  │
 *  └──┴──┴──┘    └──┘
 *  ```
 *  with empty slot frames drawn at every grid position so the recipe
 *  shape is legible even when sparse (e.g. a single-ingredient recipe). */
class RecipeIconTooltipRenderer(
    private val data: RecipeIconTooltip,
) : ClientTooltipComponent {

    private val rows: Int get() = maxOf(data.inputRows, data.outputRows)

    override fun getHeight(font: Font): Int = TOP_PADDING + rows * SLOT_SIZE + BOTTOM_PADDING

    override fun getWidth(font: Font): Int = GRID_W + ARROW_W + OUTPUT_COL_W

    override fun extractImage(
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        graphics: GuiGraphicsExtractor,
    ) {
        val gridX = x
        val gridY = y + TOP_PADDING
        val outputX = gridX + GRID_W + ARROW_W
        val inputRows = data.inputRows
        val outputRows = data.outputRows

        // Slot frames for the input grid.
        for (row in 0 until inputRows) {
            for (col in 0 until RecipeIconTooltip.COLS) {
                NineSlice.SLOT.draw(
                    graphics,
                    gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE,
                    SLOT_SIZE, SLOT_SIZE,
                )
            }
        }
        // Slot frames for the output column. Drawn for every cell shown even when
        // the recipe has fewer outputs, so the column reads as "up to this many".
        for (row in 0 until outputRows) {
            NineSlice.SLOT.draw(graphics, outputX, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
        }

        // Arrow between input grid and output column, vertically centred on the picture.
        val arrowX = gridX + GRID_W + (ARROW_W - 16) / 2
        val arrowY = gridY + (rows * SLOT_SIZE - 16) / 2
        Icons.ARROW_RIGHT.draw(graphics, arrowX, arrowY)

        // Input items at their grid positions.
        for (idx in 0 until inputRows * RecipeIconTooltip.COLS) {
            val stack = data.inputs.getOrNull(idx) ?: continue
            if (stack.isEmpty) continue
            val col = idx % RecipeIconTooltip.COLS
            val row = idx / RecipeIconTooltip.COLS
            val sx = gridX + col * SLOT_SIZE + 1
            val sy = gridY + row * SLOT_SIZE + 1
            graphics.renderFakeItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
        }
        // Output items, top-to-bottom in the right column.
        for (idx in 0 until outputRows) {
            val stack = data.outputs.getOrNull(idx) ?: continue
            if (stack.isEmpty) continue
            val sx = outputX + 1
            val sy = gridY + idx * SLOT_SIZE + 1
            graphics.renderFakeItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
            if (idx in data.optionalOutputs) graphics.text(font, "?", sx + 1, sy + 1, OPTIONAL_BADGE, true)
        }
    }

    companion object {
        /** Same colour as the editor's optional badge. */
        private const val OPTIONAL_BADGE = 0xFF55FFFF.toInt()
        private const val SLOT_SIZE = 18
        private const val GRID_W = RecipeIconTooltip.COLS * SLOT_SIZE  // 54
        private const val ARROW_W = 24
        private const val OUTPUT_COL_W = SLOT_SIZE  // 18
        // Vertical breathing room around the grid. Split evenly so the gap
        // above (between the item name and the grid) and the gap below
        // (between the grid and the next text line) read the same.
        private const val TOP_PADDING = 2
        private const val BOTTOM_PADDING = 2
    }
}
