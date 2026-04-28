package damien.nodeworks.screen.widget

import damien.nodeworks.compat.drawString
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
 */
class ChannelPickerWidget(
    x: Int,
    y: Int,
    initialColor: DyeColor,
    private val onChange: (DyeColor) -> Unit,
) : AbstractWidget(x, y, SWATCH, SWATCH, Component.literal("Channel")) {

    var currentColor: DyeColor = initialColor
        private set

    /** True while the popup is open. Host screens read this to decide whether to
     *  forward clicks through [handleOverlayClick]. */
    var expanded: Boolean = false
        private set

    fun setColor(color: DyeColor) {
        currentColor = color
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
        // Slot frame, then a 14×14 fill of the current dye colour. Border looks the
        // same as Storage Card's priority slot, keeps the GUI visually consistent.
        NineSlice.SLOT.draw(graphics, x, y, SWATCH, SWATCH)
        val rgb = currentColor.textureDiffuseColor or 0xFF000000.toInt()
        graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, rgb)

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

    /** Bounds of the 4×4 popup grid as (x, y, w, h). The popup hangs DOWN from the
     *  swatch by default, but flips upward when the swatch is too close to the
     *  screen's bottom edge to fit a full grid below it. Used by [renderOverlay]
     *  and [handleOverlayClick] so the two stay in sync without recomputing the
     *  layout in two places. */
    private fun popupBounds(): IntArray {
        val w = POPUP_COLS * CELL + POPUP_PAD * 2
        val h = POPUP_ROWS * CELL + POPUP_PAD * 2
        val px = x
        val screenH = Minecraft.getInstance().window.guiScaledHeight
        val belowY = y + SWATCH + 2
        val aboveY = y - h - 2
        val py = if (belowY + h <= screenH) belowY else aboveY.coerceAtLeast(0)
        return intArrayOf(px, py, w, h)
    }

    /** Host screens must call this AFTER rendering their other widgets so the popup
     *  layers above buttons / labels rendered by the screen frame. No-op when the
     *  popup is not open. */
    fun renderOverlay(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!expanded) return
        val (px, py, pw, ph) = popupBounds().toList()

        // Frame + interior dim so individual swatches read clearly against any
        // background.
        NineSlice.WINDOW_FRAME.draw(graphics, px, py, pw, ph)
        graphics.fill(px + 2, py + 2, px + pw - 2, py + ph - 2, 0xCC1A1A1A.toInt())

        for (i in 0 until POPUP_COLS * POPUP_ROWS) {
            val color = DyeColor.byId(i)
            val cellX = px + POPUP_PAD + (i % POPUP_COLS) * CELL
            val cellY = py + POPUP_PAD + (i / POPUP_COLS) * CELL
            val rgb = color.textureDiffuseColor or 0xFF000000.toInt()
            graphics.fill(cellX + 1, cellY + 1, cellX + CELL - 1, cellY + CELL - 1, rgb)

            val hovered = mouseX in cellX..(cellX + CELL) && mouseY in cellY..(cellY + CELL)
            val selected = color == currentColor
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
    }

    /** Host screens call this BEFORE forwarding clicks to other widgets while
     *  [expanded] is true. Returns true when the click was consumed (always true
     *  while the popup is open, clicks outside the grid close it). */
    fun handleOverlayClick(mouseX: Double, mouseY: Double): Boolean {
        if (!expanded) return false
        val (px, py, pw, ph) = popupBounds().toList()

        // Inside the grid → pick the swatch.
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
                if (picked != currentColor) {
                    currentColor = picked
                    onChange(picked)
                }
            }
            expanded = false
            return true
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
    }
}
