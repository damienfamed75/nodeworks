package damien.nodeworks.screen.widget

import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.screen.Icons
import damien.nodeworks.screen.NineSlice
import damien.nodeworks.screen.ProcessingHandlerOpenData
import damien.nodeworks.script.RecipeIngredient
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * Draws a Processing Set the way the Processing Set editor shows it: a 3×3 input grid,
 * an arrow, and a column of up to three outputs. Shared by both handler screens so a
 * set looks the same wherever it is picked or shown. A tag input cycles through its
 * tag's members, as the editor does, so the player sees what the slot accepts.
 *
 * Horizontal: inputs left, arrow right, output column on the right, [HORIZONTAL_W] by
 * [HORIZONTAL_H]. Vertical: inputs on top, arrow down, output row beneath,
 * [VERTICAL_W] by [VERTICAL_H]. A null set draws the empty frames.
 */
object RecipeGridRenderer {

    const val SLOT_SIZE = 18
    const val GRID_W = 3 * SLOT_SIZE
    const val GRID_INPUT_H = 3 * SLOT_SIZE
    const val ARROW_SPAN = 24
    const val HORIZONTAL_W = GRID_W + ARROW_SPAN + SLOT_SIZE
    const val HORIZONTAL_H = GRID_INPUT_H
    const val VERTICAL_W = GRID_W
    const val VERTICAL_H = GRID_INPUT_H + ARROW_SPAN + SLOT_SIZE

    private const val TAG_CYCLE_PERIOD_MS = 1200L

    /** Members of each tag seen, looked up once per tag per session. */
    private val tagMembers = HashMap<String, List<Item>>()

    /** The stack to show for an input right now: its tag's current member when it has
     *  a tag with members, else its own example stack. */
    fun shownStack(ingr: RecipeIngredient): ItemStack {
        val tag = ingr.tag ?: return ingr.stack
        val members = tagMembers.getOrPut(tag) { damien.nodeworks.card.ItemTagLookup.membersOf(tag) }
        if (members.isEmpty()) return ingr.stack
        val shown = members[((net.minecraft.util.Util.getMillis() / TAG_CYCLE_PERIOD_MS) % members.size).toInt()]
        return ItemStack(shown)
    }

    private fun drawEntry(graphics: GuiGraphicsExtractor, font: Font, ingr: RecipeIngredient, slotX: Int, slotY: Int) {
        val stack = shownStack(ingr).copyWithCount(ingr.count.coerceIn(1, 99))
        graphics.renderItem(stack, slotX + 1, slotY + 1)
        graphics.renderItemDecorations(font, stack, slotX + 1, slotY + 1)
    }

    /** Centred inside the given bounds. */
    fun drawHorizontal(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        recipe: ProcessingHandlerOpenData.AvailableSet?,
    ) {
        val gridX = x + (w - HORIZONTAL_W) / 2
        val gridY = y + (h - HORIZONTAL_H) / 2
        val outputX = gridX + GRID_W + ARROW_SPAN

        for (row in 0..2) {
            for (col in 0..2) {
                NineSlice.SLOT.draw(graphics, gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
            }
        }
        for (row in 0..2) {
            NineSlice.SLOT.draw(graphics, outputX, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
        }
        Icons.ARROW_RIGHT.draw(graphics, gridX + GRID_W + (ARROW_SPAN - 16) / 2, gridY + (GRID_INPUT_H - 16) / 2)

        if (recipe == null) return
        for ((idx, ingr) in recipe.inputs.withIndex()) {
            if (idx >= 9) break
            drawEntry(graphics, font, ingr, gridX + (idx % 3) * SLOT_SIZE, gridY + (idx / 3) * SLOT_SIZE)
        }
        for ((idx, ingr) in recipe.outputs.withIndex()) {
            if (idx >= 3) break
            drawEntry(graphics, font, ingr, outputX, gridY + idx * SLOT_SIZE)
        }
    }

    /** Centred inside the given bounds, inputs above the outputs. */
    fun drawVertical(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        recipe: ProcessingHandlerOpenData.AvailableSet?,
    ) {
        val gridX = x + (w - VERTICAL_W) / 2
        val gridY = y + (h - VERTICAL_H) / 2
        val outputY = gridY + GRID_INPUT_H + ARROW_SPAN

        for (row in 0..2) {
            for (col in 0..2) {
                NineSlice.SLOT.draw(graphics, gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
            }
        }
        for (col in 0..2) {
            NineSlice.SLOT.draw(graphics, gridX + col * SLOT_SIZE, outputY, SLOT_SIZE, SLOT_SIZE)
        }

        // Down arrow: the right arrow turned a quarter turn about its centre. The pose
        // rotates counter clockwise in maths terms, which reads as clockwise on a screen
        // whose Y points down, so right becomes down.
        val arrowX = gridX + (GRID_W - 16) / 2
        val arrowY = gridY + GRID_INPUT_H + (ARROW_SPAN - 16) / 2
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(arrowX + 8f, arrowY + 8f)
        pose.rotate((Math.PI / 2.0).toFloat())
        pose.translate(-8f, -8f)
        Icons.ARROW_RIGHT.draw(graphics, 0, 0)
        pose.popMatrix()

        if (recipe == null) return
        for ((idx, ingr) in recipe.inputs.withIndex()) {
            if (idx >= 9) break
            drawEntry(graphics, font, ingr, gridX + (idx % 3) * SLOT_SIZE, gridY + (idx / 3) * SLOT_SIZE)
        }
        for ((idx, ingr) in recipe.outputs.withIndex()) {
            if (idx >= 3) break
            drawEntry(graphics, font, ingr, gridX + idx * SLOT_SIZE, outputY)
        }
    }

    /** [str] cut with a trailing "..." so it fits in [maxWidth] pixels. */
    fun truncateToWidth(font: Font, str: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(str) <= maxWidth) return str
        val ellipsis = "..."
        val ellipsisW = font.width(ellipsis)
        if (maxWidth <= ellipsisW) return ""
        return font.plainSubstrByWidth(str, maxWidth - ellipsisW) + ellipsis
    }
}
