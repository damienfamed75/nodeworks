package damien.nodeworks.screen.widget

import damien.nodeworks.compat.drawString
import damien.nodeworks.screen.NineSlice
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Facing-relative direction labels used by [FacePickerWidget]. The host
 *  translates these to world [net.minecraft.core.Direction]s using its own
 *  orientation (e.g. a chest's `FACING`, a card's mounted face, etc.). */
enum class RelDir(val letter: String, val displayName: String) {
    UP("U", "Up"),
    DOWN("D", "Down"),
    LEFT("L", "Left"),
    RIGHT("R", "Right"),
    FRONT("F", "Front"),
    BACK("B", "Back");

    /** Resolve this RelDir to a world [net.minecraft.core.Direction] given the
     *  reference [nodeSide] (the face the card / device is mounted on, where
     *  the host treats `nodeSide.opposite` as FRONT, the face of the adjacent
     *  block touching the node).
     *
     *  Convention is "as seen by a player looking at the card-bearing node
     *  face from outside": LEFT/RIGHT are the player's left and right when
     *  facing the card. UP/DOWN are always world up/down. For
     *  vertically-mounted cards (`nodeSide` = UP/DOWN) the horizontal
     *  rotation has no meaningful axis, LEFT and RIGHT fall back to FRONT. */
    fun resolve(nodeSide: net.minecraft.core.Direction): net.minecraft.core.Direction {
        val front = nodeSide.opposite
        return when (this) {
            FRONT -> front
            BACK -> nodeSide
            UP -> net.minecraft.core.Direction.UP
            DOWN -> net.minecraft.core.Direction.DOWN
            LEFT -> if (front.axis.isHorizontal) front.counterClockWise else front
            RIGHT -> if (front.axis.isHorizontal) front.clockWise else front
        }
    }
}

/**
 * 16×16 face-picker swatch. Mirrors [ChannelPickerWidget]'s shape: a single
 * always-visible cell that displays the current selection (or a black-with-X
 * glyph when no face is chosen), expanding into a popup on click.
 *
 * Popup layout, a 3×3 cube unfold with the 7th cell repurposed as the
 * "none / clear" picker:
 *
 * ```
 *  U X
 * L F R
 *  D B
 * ```
 *
 * The widget is generic over the host's coordinate system: it speaks
 * [RelDir] only, hosts convert to/from world [net.minecraft.core.Direction]
 * themselves. That makes the widget reusable across blocks with different
 * orientations (chest FACING, card mounted face, etc.).
 *
 * Persistence is the host's responsibility, [onChange] fires the moment the
 * user picks a face, the host wires that to its menu / payload / BE
 * accordingly. The widget owns only the transient [currentFace] / [expanded]
 * UI state.
 */
class FacePickerWidget(
    x: Int,
    y: Int,
    initialFace: RelDir?,
    private val onChange: (RelDir?) -> Unit,
) : AbstractWidget(x, y, SWATCH, SWATCH, Component.literal("Auto-push")) {

    /** Currently selected face, or null when in the "none / clear" state. */
    var currentFace: RelDir? = initialFace
        private set

    /** True while the popup is open. Host screens read this to decide whether
     *  to forward clicks through [handleOverlayClick]. */
    var expanded: Boolean = false
        private set

    fun setFace(face: RelDir?) {
        currentFace = face
    }

    /** Programmatically close the popup. */
    fun closePopup() {
        expanded = false
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        NineSlice.SLOT.draw(graphics, x, y, SWATCH, SWATCH)
        val face = currentFace
        if (face == null) {
            drawNoneGlyph(graphics, x, y, SWATCH)
        } else {
            graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, FACE_BG)
            val font = Minecraft.getInstance().font
            val letter = face.letter
            graphics.drawString(
                font, letter,
                x + (SWATCH - font.width(letter)) / 2,
                y + (SWATCH - font.lineHeight) / 2 + 1,
                0xFFFFFFFF.toInt(),
            )
        }

        if (isHovered) {
            graphics.fill(x, y, x + SWATCH, y + 1, 0x80FFFFFF.toInt())
            graphics.fill(x, y + SWATCH - 1, x + SWATCH, y + SWATCH, 0x80FFFFFF.toInt())
            graphics.fill(x, y, x + 1, y + SWATCH, 0x80FFFFFF.toInt())
            graphics.fill(x + SWATCH - 1, y, x + SWATCH, y + SWATCH, 0x80FFFFFF.toInt())
        }
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        expanded = !expanded
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }

    /** Bounds of the popup as (x, y, w, h). Hangs DOWN from the swatch by
     *  default, flips upward when the swatch is too close to the screen's
     *  bottom edge. */
    private fun popupBounds(): IntArray {
        val w = POPUP_GRID * CELL + POPUP_PAD * 2
        val h = POPUP_GRID * CELL + POPUP_PAD * 2
        val px = x
        val screenH = Minecraft.getInstance().window.guiScaledHeight
        val belowY = y + SWATCH + 2
        val aboveY = y - h - 2
        val py = if (belowY + h <= screenH) belowY else aboveY.coerceAtLeast(0)
        return intArrayOf(px, py, w, h)
    }

    /** Resolve the cell at grid (row, col) to its [RelDir], or null when the
     *  cell is empty / the X clear-cell. The X cell is at row 0, col 2. */
    private fun cellAt(row: Int, col: Int): RelDir? = LAYOUT[row][col]

    /** Returns true when (row, col) is the "X" clear-cell. */
    private fun isClearCell(row: Int, col: Int): Boolean = row == 0 && col == 2

    fun renderOverlay(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!expanded) return
        val (px, py, pw, ph) = popupBounds().toList()

        NineSlice.WINDOW_FRAME.draw(graphics, px, py, pw, ph)
        graphics.fill(px + 2, py + 2, px + pw - 2, py + ph - 2, 0xCC1A1A1A.toInt())

        val font = Minecraft.getInstance().font
        for (row in 0 until POPUP_GRID) {
            for (col in 0 until POPUP_GRID) {
                val cellX = px + POPUP_PAD + col * CELL
                val cellY = py + POPUP_PAD + row * CELL
                val rel = cellAt(row, col)
                val isClear = isClearCell(row, col)

                if (rel == null && !isClear) continue  // truly empty cell

                val hovered = mouseX in cellX..(cellX + CELL) && mouseY in cellY..(cellY + CELL)
                val selected = (isClear && currentFace == null) || (rel != null && rel == currentFace)

                if (isClear) {
                    drawNoneGlyph(graphics, cellX, cellY, CELL)
                } else {
                    graphics.fill(cellX + 1, cellY + 1, cellX + CELL - 1, cellY + CELL - 1, FACE_BG)
                    val letter = rel!!.letter
                    graphics.drawString(
                        font, letter,
                        cellX + (CELL - font.width(letter)) / 2,
                        cellY + (CELL - font.lineHeight) / 2 + 1,
                        0xFFFFFFFF.toInt(),
                    )
                }

                if (hovered || selected) {
                    val outline = if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                    graphics.fill(cellX, cellY, cellX + CELL, cellY + 1, outline)
                    graphics.fill(cellX, cellY + CELL - 1, cellX + CELL, cellY + CELL, outline)
                    graphics.fill(cellX, cellY, cellX + 1, cellY + CELL, outline)
                    graphics.fill(cellX + CELL - 1, cellY, cellX + CELL, cellY + CELL, outline)
                }

                if (hovered) {
                    val name = if (isClear) "none" else rel!!.displayName.lowercase()
                    val tw = font.width(name) + 4
                    val tx = (cellX - tw / 2 + CELL / 2)
                        .coerceIn(2, Minecraft.getInstance().window.guiScaledWidth - tw - 2)
                    val ty = (py - font.lineHeight - 2).coerceAtLeast(2)
                    graphics.fill(tx - 2, ty - 1, tx + tw - 2, ty + font.lineHeight + 1, 0xCC000000.toInt())
                    graphics.drawString(font, name, tx, ty, 0xFFFFFFFF.toInt(), false)
                }
            }
        }
    }

    /** Black square + red X, shared by the swatch (when current face is null)
     *  and the popup's clear-cell. Same proportions as
     *  [ChannelPickerWidget.drawNoneGlyph]. */
    private fun drawNoneGlyph(graphics: GuiGraphicsExtractor, x0: Int, y0: Int, size: Int) {
        graphics.fill(x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1, 0xFF1A1A1A.toInt())
        val inset = 2
        val red = 0xFFE53935.toInt()
        val xn = size - inset * 2
        for (i in 0 until xn) {
            graphics.fill(x0 + inset + i, y0 + inset + i, x0 + inset + i + 1, y0 + inset + i + 1, red)
            graphics.fill(x0 + inset + i, y0 + size - inset - 1 - i, x0 + inset + i + 1, y0 + size - inset - i, red)
        }
    }

    fun handleOverlayClick(mouseX: Double, mouseY: Double): Boolean {
        if (!expanded) return false
        val (px, py, pw, ph) = popupBounds().toList()

        val gridX0 = px + POPUP_PAD
        val gridY0 = py + POPUP_PAD
        if (mouseX >= gridX0 && mouseY >= gridY0 &&
            mouseX < gridX0 + POPUP_GRID * CELL && mouseY < gridY0 + POPUP_GRID * CELL
        ) {
            val col = ((mouseX - gridX0) / CELL).toInt()
            val row = ((mouseY - gridY0) / CELL).toInt()
            val rel = cellAt(row, col)
            val isClear = isClearCell(row, col)
            if (isClear) {
                if (currentFace != null) {
                    currentFace = null
                    onChange(null)
                }
                expanded = false
                return true
            }
            if (rel != null) {
                if (rel != currentFace) {
                    currentFace = rel
                    onChange(rel)
                }
                expanded = false
                return true
            }
            // Truly empty cell, eat the click but don't change state.
            return true
        }

        // Click outside the popup closes it (unless on the swatch itself, which
        // the swatch's own onClick handles as a toggle-off).
        val swatchHit = mouseX >= x && mouseY >= y && mouseX < x + SWATCH && mouseY < y + SWATCH
        if (!swatchHit) {
            expanded = false
            return true
        }
        return false
    }

    companion object {
        const val SWATCH = 16
        private const val POPUP_GRID = 3
        private const val CELL = 16
        private const val POPUP_PAD = 4
        private const val FACE_BG = 0xFF3A3A3A.toInt()

        /** Fixed cube-unfold layout. Row 0 col 2 is the X clear-cell (handled
         *  via [isClearCell]), the two `null` slots in row 0 col 0 and row 2
         *  col 0 are truly empty padding cells. */
        private val LAYOUT: Array<Array<RelDir?>> = arrayOf(
            arrayOf(null, RelDir.UP, null),
            arrayOf(RelDir.LEFT, RelDir.FRONT, RelDir.RIGHT),
            arrayOf(null, RelDir.DOWN, RelDir.BACK),
        )
    }
}
