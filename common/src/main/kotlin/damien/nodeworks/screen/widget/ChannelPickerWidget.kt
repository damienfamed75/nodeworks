package damien.nodeworks.screen.widget

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.screen.Icons
import damien.nodeworks.screen.NineSlice
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor

/**
 * 16×16 channel-color swatch button. Displays the currently-selected dye color and
 * a thin border, clicking expands a 4×4 popup of all 16 dye swatches that the user
 * can pick from.
 *
 * The popup is rendered via [renderOverlay] which the host screen must call AFTER
 * rendering its other widgets, otherwise the popup would draw under buttons /
 * labels rendered by the screen frame. Click handling for the popup goes through
 * [handleOverlayClick], when the popup is expanded the host screen routes every
 * click into that method first so the picker can claim the event before any
 * underlying widget sees it.
 *
 * Persistence is the host's responsibility, the [onChange] callback fires the
 * moment the user picks a colour and the host's menu syncs the new value to the
 * server. The widget itself only owns transient UI state ([currentColor],
 * [expanded]).
 *
 * Setting [canBeNone] adds a 17th option centred at the bottom of the popup,
 * rendered as a black square with a red X. Picking it sets [isNone] = true and
 * fires `onChange(null)`. Hosts that don't need a "no channel" state pass the
 * default `canBeNone = false` and the callback is guaranteed to receive a
 * non-null colour.
 */
class ChannelPickerWidget(
    x: Int,
    y: Int,
    initialColor: DyeColor,
    private val canBeNone: Boolean = false,
    initialIsNone: Boolean = false,
    private val onChange: (DyeColor?) -> Unit,
) : AbstractWidget(x, y, SWATCH, SWATCH, Component.literal("Channel")) {

    var currentColor: DyeColor = initialColor
        private set

    /** True when the picker is in the "no channel" state. Always false when
     *  [canBeNone] is false. */
    var isNone: Boolean = initialIsNone && canBeNone
        private set

    /** True while the popup is open. Host screens read this to decide whether to
     *  forward clicks through [handleOverlayClick]. */
    var expanded: Boolean = false
        private set

    fun setColor(color: DyeColor) {
        currentColor = color
        isNone = false
    }

    fun setNone() {
        if (!canBeNone) return
        isNone = true
    }

    /** Programmatically close the popup. Host screens call this on key-Escape or
     *  when another widget gains focus. */
    fun closePopup() {
        expanded = false
    }

    // ---- Swatch button (the always-visible 16×16) ----

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // Slot frame, then either the dye-coloured fill or the "none" black-with-X
        // glyph. Border looks the same as Storage Card's priority slot, keeps the
        // GUI visually consistent.
        NineSlice.SLOT.draw(graphics, x, y, SWATCH, SWATCH)
        if (isNone) {
            drawNoneGlyph(graphics, x, y, SWATCH)
        } else {
            // White wool tinted with the dye colour fills the slot interior
            // (1 px inset on every side so the slot frame still reads). Gives
            // the swatch a wool texture instead of a flat fill while staying
            // chromatically identical to the picker grid below.
            val rgb = currentColor.textureDiffuseColor and 0xFFFFFF
            Icons.WHITE_WOOL.drawTinted(graphics, x + 1, y + 1, SWATCH - 2, rgb)
        }

        // Hover outline so the player knows the swatch is interactive.
        if (isHovered) {
            graphics.fill(x, y, x + SWATCH, y + 1, 0x80FFFFFF.toInt())
            graphics.fill(x, y + SWATCH - 1, x + SWATCH, y + SWATCH, 0x80FFFFFF.toInt())
            graphics.fill(x, y, x + 1, y + SWATCH, 0x80FFFFFF.toInt())
            graphics.fill(x + SWATCH - 1, y, x + SWATCH, y + SWATCH, 0x80FFFFFF.toInt())
        }
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        // Toggle popup on swatch click.
        expanded = !expanded
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }

    // ---- Popup overlay (host-driven render + click) ----

    /** Bounds of the popup as (x, y, w, h). Includes the 4×4 colour grid, plus a
     *  17th centred "none" cell at the bottom when [canBeNone] is true. The popup
     *  hangs DOWN from the swatch by default, but flips upward when the swatch is
     *  too close to the screen's bottom edge. Used by [renderOverlay] and
     *  [handleOverlayClick] so the two stay in sync. */
    private fun popupBounds(): IntArray {
        val w = POPUP_COLS * CELL + POPUP_PAD * 2
        val gridH = POPUP_ROWS * CELL
        val noneH = if (canBeNone) NONE_GAP + CELL else 0
        val h = gridH + noneH + POPUP_PAD * 2
        val px = x
        val screenH = Minecraft.getInstance().window.guiScaledHeight
        val belowY = y + SWATCH + 2
        val aboveY = y - h - 2
        val py = if (belowY + h <= screenH) belowY else aboveY.coerceAtLeast(0)
        return intArrayOf(px, py, w, h)
    }

    /** Bounds of the centred "none" cell within the popup, or null when
     *  [canBeNone] is false. (px, py, cellSize) shaped like grid cells. */
    private fun noneCellBounds(): IntArray? {
        if (!canBeNone) return null
        val (px, py, pw, _) = popupBounds().toList()
        val cellX = px + (pw - CELL) / 2
        val cellY = py + POPUP_PAD + POPUP_ROWS * CELL + NONE_GAP
        return intArrayOf(cellX, cellY, CELL, CELL)
    }

    /** Host screens must call this AFTER rendering their other widgets so the popup
     *  + hover tooltip layer above buttons / labels rendered by the screen frame.
     *  When the popup is closed, draws a "Channel: <name>" hover tooltip; when
     *  open, draws the swatch grid. */
    fun renderOverlay(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!expanded) {
            renderSwatchTooltip(graphics, mouseX, mouseY)
            return
        }
        val (px, py, pw, ph) = popupBounds().toList()

        // Frame + interior dim so individual swatches read clearly against any
        // background.
        NineSlice.WINDOW_FRAME.draw(graphics, px, py, pw, ph)
        graphics.fill(px + 2, py + 2, px + pw - 2, py + ph - 2, 0xCC1A1A1A.toInt())

        for (i in 0 until POPUP_COLS * POPUP_ROWS) {
            val color = DyeColor.byId(i)
            val cellX = px + POPUP_PAD + (i % POPUP_COLS) * CELL
            val cellY = py + POPUP_PAD + (i / POPUP_COLS) * CELL
            val rgb = color.textureDiffuseColor and 0xFFFFFF
            Icons.WHITE_WOOL.drawTinted(graphics, cellX + 1, cellY + 1, CELL - 2, rgb)

            val hovered = mouseX in cellX..(cellX + CELL) && mouseY in cellY..(cellY + CELL)
            val selected = !isNone && color == currentColor
            if (hovered || selected) {
                val outline = if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                graphics.fill(cellX, cellY, cellX + CELL, cellY + 1, outline)
                graphics.fill(cellX, cellY + CELL - 1, cellX + CELL, cellY + CELL, outline)
                graphics.fill(cellX, cellY, cellX + 1, cellY + CELL, outline)
                graphics.fill(cellX + CELL - 1, cellY, cellX + CELL, cellY + CELL, outline)
            }

            if (hovered) {
                val font = Minecraft.getInstance().font
                val name = color.name.lowercase().replace('_', ' ')
                val tw = font.width(name) + 4
                val tx = (cellX - tw / 2 + CELL / 2).coerceIn(2, Minecraft.getInstance().window.guiScaledWidth - tw - 2)
                val ty = (py - font.lineHeight - 2).coerceAtLeast(2)
                graphics.fill(tx - 2, ty - 1, tx + tw - 2, ty + font.lineHeight + 1, 0xCC000000.toInt())
                graphics.drawString(font, name, tx, ty, 0xFFFFFFFF.toInt(), false)
            }
        }

        // 17th "none" cell, centred under the grid.
        val noneCell = noneCellBounds()
        if (noneCell != null) {
            val (cellX, cellY, _, _) = noneCell.toList()
            drawNoneGlyph(graphics, cellX, cellY, CELL)
            val hovered = mouseX in cellX..(cellX + CELL) && mouseY in cellY..(cellY + CELL)
            val selected = isNone
            if (hovered || selected) {
                val outline = if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                graphics.fill(cellX, cellY, cellX + CELL, cellY + 1, outline)
                graphics.fill(cellX, cellY + CELL - 1, cellX + CELL, cellY + CELL, outline)
                graphics.fill(cellX, cellY, cellX + 1, cellY + CELL, outline)
                graphics.fill(cellX + CELL - 1, cellY, cellX + CELL, cellY + CELL, outline)
            }
            if (hovered) {
                val font = Minecraft.getInstance().font
                val name = "none"
                val tw = font.width(name) + 4
                val tx = (cellX - tw / 2 + CELL / 2).coerceIn(2, Minecraft.getInstance().window.guiScaledWidth - tw - 2)
                val ty = (py - font.lineHeight - 2).coerceAtLeast(2)
                graphics.fill(tx - 2, ty - 1, tx + tw - 2, ty + font.lineHeight + 1, 0xCC000000.toInt())
                graphics.drawString(font, name, tx, ty, 0xFFFFFFFF.toInt(), false)
            }
        }
    }

    /** "Any channel" glyph. Drawn at the swatch (when [isNone]) and on the
     *  17th popup cell. Backed by [Icons.ANY_CHANNEL] which scales to whichever
     *  size the host hands in (12 px in the popup, 16 px on the swatch). */
    private fun drawNoneGlyph(graphics: GuiGraphicsExtractor, x0: Int, y0: Int, size: Int) {
        Icons.ANY_CHANNEL.draw(graphics, x0, y0, size)
    }

    /** Channel-name + "Click to change." vanilla-style tooltip rendered when
     *  the cursor's over the collapsed swatch. Hosts get this for free by
     *  calling [renderOverlay] each frame, no per-screen tooltip plumbing. */
    private fun renderSwatchTooltip(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (mouseX !in x until x + width || mouseY !in y until y + height) return
        val font = Minecraft.getInstance().font
        val firstLine = if (isNone) "All channels"
            else "Channel: ${currentColor.name.lowercase().replaceFirstChar { it.uppercase() }}"
        graphics.renderComponentTooltip(
            font,
            listOf(Component.literal(firstLine), Component.literal("Click to change.")),
            mouseX,
            mouseY,
        )
    }

    /** Host screens call this BEFORE forwarding clicks to other widgets while
     *  [expanded] is true. Returns true when the click was consumed (always true
     *  while the popup is open, clicks outside the grid close it). */
    fun handleOverlayClick(mouseX: Double, mouseY: Double): Boolean {
        if (!expanded) return false
        val (px, py, pw, ph) = popupBounds().toList()

        // Inside the colour grid → pick the swatch.
        val gridX0 = px + POPUP_PAD
        val gridY0 = py + POPUP_PAD
        if (mouseX >= gridX0 && mouseY >= gridY0 &&
            mouseX < gridX0 + POPUP_COLS * CELL && mouseY < gridY0 + POPUP_ROWS * CELL
        ) {
            val col = ((mouseX - gridX0) / CELL).toInt()
            val row = ((mouseY - gridY0) / CELL).toInt()
            val idx = row * POPUP_COLS + col
            if (idx in 0 until POPUP_COLS * POPUP_ROWS) {
                val picked = DyeColor.byId(idx)
                if (picked != currentColor || isNone) {
                    currentColor = picked
                    isNone = false
                    onChange(picked)
                }
            }
            expanded = false
            return true
        }

        // 17th "none" cell.
        val noneCell = noneCellBounds()
        if (noneCell != null) {
            val (ncX, ncY, ncW, ncH) = noneCell.toList()
            if (mouseX >= ncX && mouseY >= ncY && mouseX < ncX + ncW && mouseY < ncY + ncH) {
                if (!isNone) {
                    isNone = true
                    onChange(null)
                }
                expanded = false
                return true
            }
        }

        // Outside grid (and outside swatch) → close. Returning true so the host
        // doesn't accidentally fire whatever's underneath the popup.
        val swatchHit = mouseX >= x && mouseY >= y && mouseX < x + SWATCH && mouseY < y + SWATCH
        if (!swatchHit) {
            expanded = false
            return true
        }
        // Click is on the swatch itself, let the normal widget click handler toggle
        // the popup off.
        return false
    }

    companion object {
        const val SWATCH = 16
        private const val POPUP_COLS = 4
        private const val POPUP_ROWS = 4
        private const val CELL = 12
        private const val POPUP_PAD = 4
        /** Vertical gap between the 4×4 colour grid and the 17th "none" cell. */
        private const val NONE_GAP = 3
    }
}
