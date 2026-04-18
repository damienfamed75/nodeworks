package damien.nodeworks.screen.widget

import damien.nodeworks.screen.Icons
import damien.nodeworks.screen.NineSlice
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.Minecraft
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.GuiGraphicsExtractor
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.components.AbstractWidget
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.network.chat.Component

/**
 * A button widget that uses 9-sliced textures instead of MC's default button style.
 * Supports optional icon from the Icons atlas drawn to the left of text.
 */
class SlicedButton(
    x: Int, y: Int, w: Int, h: Int,
    private val label: Component,
    private val onPress: (SlicedButton) -> Unit,
    private val icon: Icons? = null,
    private var textColor: Int = 0xFFFFFFFF.toInt(),
    private var hoverTextColor: Int = 0xFFFFFFFF.toInt()
) : AbstractWidget(x, y, w, h, label) {

    private var pressed = false

    override fun renderWidget(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val slice = when {
            !active -> NineSlice.BUTTON
            pressed -> NineSlice.BUTTON_ACTIVE
            isHovered -> NineSlice.BUTTON_HOVER
            else -> NineSlice.BUTTON
        }
        slice.draw(graphics, x, y, width, height)

        val font = Minecraft.getInstance().font
        val color = if (isHovered && active) hoverTextColor else textColor
        val text = message.string

        if (icon != null) {
            val iconSize = minOf(height - 4, 12)
            val iconY = y + (height - iconSize) / 2
            if (text.isEmpty()) {
                // Icon-only: center the icon
                val iconX = x + (width - iconSize) / 2
                icon.draw(graphics, iconX, iconY, iconSize)
            } else {
                val totalW = iconSize + 2 + font.width(text)
                val startX = x + (width - totalW) / 2
                icon.draw(graphics, startX, iconY, iconSize)
                graphics.drawString(font, text, startX + iconSize + 2, y + (height - font.lineHeight) / 2 + 1, color)
            }
        } else {
            graphics.drawString(font, text, x + (width - font.width(text)) / 2, y + (height - font.lineHeight) / 2 + 1, color)
        }
    }

    override fun onClick(mouseX: Double, mouseY: Double) {
        pressed = true
    }

    override fun onRelease(mouseX: Double, mouseY: Double) {
        if (pressed) {
            pressed = false
            onPress(this)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }

    companion object {
        fun create(
            x: Int, y: Int, w: Int, h: Int,
            label: String,
            onPress: (SlicedButton) -> Unit
        ): SlicedButton = SlicedButton(x, y, w, h, Component.literal(label), onPress)

        fun create(
            x: Int, y: Int, w: Int, h: Int,
            label: String,
            icon: Icons?,
            onPress: (SlicedButton) -> Unit
        ): SlicedButton = SlicedButton(x, y, w, h, Component.literal(label), onPress, icon)

        fun createColored(
            x: Int, y: Int, w: Int, h: Int,
            label: String,
            textColor: Int,
            hoverTextColor: Int,
            onPress: (SlicedButton) -> Unit
        ): SlicedButton = SlicedButton(x, y, w, h, Component.literal(label), onPress, null, textColor, hoverTextColor)
    }
}
