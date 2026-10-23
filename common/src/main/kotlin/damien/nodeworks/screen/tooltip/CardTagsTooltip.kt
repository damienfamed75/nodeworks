package damien.nodeworks.screen.tooltip

import damien.nodeworks.compat.drawString
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.world.inventory.tooltip.TooltipComponent

/** A card's tags, drawn as a row of chips in its tooltip. */
data class CardTagsTooltip(val tags: List<String>) : TooltipComponent

class CardTagsTooltipRenderer(private val data: CardTagsTooltip) : ClientTooltipComponent {

    companion object {
        const val CHIP_H = 11
        const val CHIP_PAD_X = 3
        const val CHIP_GAP = 3
        const val ROW_GAP = 2
        /** Chips wrap past this width so a tooltip never grows wider than its text. */
        const val MAX_ROW_W = 180
        const val CHIP_BG = 0xFF3A4A5A.toInt()
        const val CHIP_BORDER = 0xFF7FA6C9.toInt()
        const val CHIP_TEXT = 0xFFE8F1FA.toInt()

        fun chipWidth(font: Font, tag: String): Int = font.width(tag) + CHIP_PAD_X * 2

        /** Rows of tags as they wrap at [MAX_ROW_W]. */
        fun rows(font: Font, tags: List<String>): List<List<String>> {
            val out = ArrayList<List<String>>()
            var row = ArrayList<String>()
            var w = 0
            for (tag in tags) {
                val cw = chipWidth(font, tag)
                if (row.isNotEmpty() && w + CHIP_GAP + cw > MAX_ROW_W) {
                    out.add(row)
                    row = ArrayList()
                    w = 0
                }
                w += (if (row.isEmpty()) 0 else CHIP_GAP) + cw
                row.add(tag)
            }
            if (row.isNotEmpty()) out.add(row)
            return out
        }

        fun drawChip(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, tag: String, bg: Int = CHIP_BG, border: Int = CHIP_BORDER) {
            val w = chipWidth(font, tag)
            graphics.fill(x, y, x + w, y + CHIP_H, border)
            graphics.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, bg)
            graphics.drawString(font, tag, x + CHIP_PAD_X, y + 2, CHIP_TEXT)
        }
    }

    override fun getHeight(font: Font): Int {
        val n = rows(font, data.tags).size
        return n * CHIP_H + (n - 1).coerceAtLeast(0) * ROW_GAP + 2
    }

    override fun getWidth(font: Font): Int =
        rows(font, data.tags).maxOfOrNull { row -> row.sumOf { chipWidth(font, it) } + (row.size - 1) * CHIP_GAP } ?: 0

    override fun extractImage(font: Font, x: Int, y: Int, width: Int, height: Int, graphics: GuiGraphicsExtractor) {
        var cy = y + 1
        for (row in rows(font, data.tags)) {
            var cx = x
            for (tag in row) {
                drawChip(graphics, font, cx, cy, tag)
                cx += chipWidth(font, tag) + CHIP_GAP
            }
            cy += CHIP_H + ROW_GAP
        }
    }
}
