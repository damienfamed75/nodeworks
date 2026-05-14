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
 * Hover-tooltip image showing a recipe as a full 3×3 input grid → arrow →
 * 1×3 output column. Mirrors the picker-thumbnail layout used by the
 * Processing Handler so the same visual vocabulary carries over to
 * Instruction / Processing Set item hovers.
 *
 * [inputs] is a fixed-length 9-element list aligned to grid slot indices
 * (`row * 3 + col`), with [ItemStack.EMPTY] entries for unfilled cells.
 * [outputs] is a 0..3-length list rendered top-to-bottom in the right
 * column. Both ItemStacks carry their per-craft count baked in via
 * [ItemStack.getCount], so the renderer can surface count badges
 * directly through [renderItemDecorations].
 */
data class RecipeIconTooltip(
    val inputs: List<ItemStack>,
    val outputs: List<ItemStack>,
) : TooltipComponent {
    companion object {
        const val INPUT_SLOTS = 9
        const val MAX_OUTPUT_SLOTS = 3
    }
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

    override fun getHeight(font: Font): Int = TOP_PADDING + GRID_H + BOTTOM_PADDING

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

        // Slot frames for the 3×3 input grid.
        for (row in 0..2) {
            for (col in 0..2) {
                NineSlice.SLOT.draw(
                    graphics,
                    gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE,
                    SLOT_SIZE, SLOT_SIZE,
                )
            }
        }
        // Slot frames for the output column. Drawn for all 3 cells even when
        // the recipe has fewer outputs, so the column reads as "up to 3".
        for (row in 0..2) {
            NineSlice.SLOT.draw(graphics, outputX, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
        }

        // Arrow between input grid and output column, vertically centred.
        val arrowX = gridX + GRID_W + (ARROW_W - 16) / 2
        val arrowY = gridY + (GRID_H - 16) / 2
        Icons.ARROW_RIGHT.draw(graphics, arrowX, arrowY)

        // Input items at their grid positions.
        for (idx in 0 until RecipeIconTooltip.INPUT_SLOTS) {
            val stack = data.inputs.getOrNull(idx) ?: continue
            if (stack.isEmpty) continue
            val col = idx % 3
            val row = idx / 3
            val sx = gridX + col * SLOT_SIZE + 1
            val sy = gridY + row * SLOT_SIZE + 1
            graphics.renderFakeItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
        }
        // Output items, top-to-bottom in the right column.
        for (idx in 0 until RecipeIconTooltip.MAX_OUTPUT_SLOTS) {
            val stack = data.outputs.getOrNull(idx) ?: continue
            if (stack.isEmpty) continue
            val sx = outputX + 1
            val sy = gridY + idx * SLOT_SIZE + 1
            graphics.renderFakeItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
        }
    }

    companion object {
        private const val SLOT_SIZE = 18
        private const val GRID_W = 3 * SLOT_SIZE  // 54
        private const val GRID_H = 3 * SLOT_SIZE  // 54
        private const val ARROW_W = 24
        private const val OUTPUT_COL_W = SLOT_SIZE  // 18
        // Vertical breathing room around the grid. Split evenly so the gap
        // above (between the item name and the grid) and the gap below
        // (between the grid and the next text line) read the same.
        private const val TOP_PADDING = 2
        private const val BOTTOM_PADDING = 2
    }
}
