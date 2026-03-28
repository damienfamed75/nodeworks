package damien.nodeworks.screen

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.awt.Color

/**
 * Popup color picker screen. Opens on top of the controller screen.
 * Calls onConfirm with the selected color when the player clicks Confirm.
 */
class ColorPickerScreen(
    private val parentScreen: Screen,
    private val initialColor: Int,
    private val defaultColor: Int,
    private val onConfirm: (Int) -> Unit
) : Screen(Component.literal("Color Picker")) {

    companion object {
        private const val PANEL_W = 160
        private const val PANEL_H = 130
        private const val PICKER_W = 140
        private const val PICKER_H = 60
    }

    private var selectedColor: Int = initialColor
    private lateinit var hexField: EditBox
    private var pickerTextureId: Identifier? = null
    private var panelX = 0
    private var panelY = 0
    private var updatingField = false

    override fun init() {
        super.init()
        panelX = (width - PANEL_W) / 2
        panelY = (height - PANEL_H) / 2

        if (pickerTextureId == null) {
            pickerTextureId = createPickerTexture()
        }

        // Hex input field
        hexField = EditBox(font, panelX + 10, panelY + 78, 50, 14, Component.literal("Hex"))
        hexField.setMaxLength(6)
        hexField.value = String.format("%06X", selectedColor)
        hexField.setResponder { text ->
            if (!updatingField) {
                try {
                    val c = text.toInt(16)
                    if (c in 0..0xFFFFFF) selectedColor = c
                } catch (_: NumberFormatException) {}
            }
        }
        addRenderableWidget(hexField)

        // Confirm button
        addRenderableWidget(Button.builder(Component.literal("Confirm")) {
            onConfirm(selectedColor)
            minecraft?.setScreen(parentScreen)
        }.bounds(panelX + PANEL_W - 58, panelY + PANEL_H - 24, 50, 20).build())

        // Default button
        addRenderableWidget(Button.builder(Component.literal("Default")) {
            selectedColor = defaultColor
            updateHexField()
        }.bounds(panelX + 8, panelY + PANEL_H - 24, 50, 20).build())
    }

    private fun createPickerTexture(): Identifier {
        val image = NativeImage(PICKER_W, PICKER_H, false)
        for (x in 0 until PICKER_W) {
            val hue = x.toFloat() / PICKER_W
            for (y in 0 until PICKER_H) {
                val brightness = 1.0f - (y.toFloat() / PICKER_H) * 0.8f
                val rgb = Color.HSBtoRGB(hue, 0.85f, brightness)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                image.setPixel(x, y, (0xFF shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        val texture = DynamicTexture({ "nodeworks_color_picker" }, image)
        val id = Identifier.fromNamespaceAndPath("nodeworks", "dynamic/color_picker")
        minecraft?.textureManager?.register(id, texture)
        return id
    }

    override fun removed() {
        super.removed()
        pickerTextureId?.let { minecraft?.textureManager?.release(it) }
        pickerTextureId = null
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Dim background
        graphics.fill(0, 0, width, height, 0x88000000.toInt())

        // Panel background (MC style)
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFFC6C6C6.toInt())
        // Raised border
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, 0xFFFFFFFF.toInt())
        graphics.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, 0xFFFFFFFF.toInt())
        graphics.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555.toInt())
        graphics.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555.toInt())

        // Title
        graphics.drawCenteredString(font, title, panelX + PANEL_W / 2, panelY + 4, 0xFF404040.toInt())

        // Color palette
        val px = panelX + 10
        val py = panelY + 16
        pickerTextureId?.let { texId ->
            graphics.blit(RenderPipelines.GUI_TEXTURED, texId, px, py, 0f, 0f, PICKER_W, PICKER_H, PICKER_W, PICKER_H)
        }
        // Inset border
        graphics.fill(px - 1, py - 1, px + PICKER_W + 1, py, 0xFF555555.toInt())
        graphics.fill(px - 1, py - 1, px, py + PICKER_H + 1, 0xFF555555.toInt())
        graphics.fill(px + PICKER_W, py - 1, px + PICKER_W + 1, py + PICKER_H + 1, 0xFFFFFFFF.toInt())
        graphics.fill(px - 1, py + PICKER_H, px + PICKER_W + 1, py + PICKER_H + 1, 0xFFFFFFFF.toInt())

        // Preview swatch (next to hex field)
        val swatchX = panelX + 64
        val swatchY = panelY + 78
        graphics.fill(swatchX, swatchY, swatchX + 14, swatchY + 14, selectedColor or 0xFF000000.toInt())
        graphics.fill(swatchX - 1, swatchY - 1, swatchX + 15, swatchY, 0xFF555555.toInt())
        graphics.fill(swatchX - 1, swatchY - 1, swatchX, swatchY + 15, 0xFF555555.toInt())
        graphics.fill(swatchX + 14, swatchY - 1, swatchX + 15, swatchY + 15, 0xFFFFFFFF.toInt())
        graphics.fill(swatchX - 1, swatchY + 14, swatchX + 15, swatchY + 15, 0xFFFFFFFF.toInt())

        // Hash prefix
        graphics.drawString(font, "#", panelX + 4, panelY + 81, 0xFF404040.toInt(), false)

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, flag: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()
        val px = panelX + 10
        val py = panelY + 16
        if (mx >= px && mx < px + PICKER_W && my >= py && my < py + PICKER_H) {
            pickColorAt((mx - px).toDouble(), (my - py).toDouble())
            return true
        }
        return super.mouseClicked(event, flag)
    }

    override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mx = event.x()
        val my = event.y()
        val px = panelX + 10
        val py = panelY + 16
        if (event.button() == 0 && mx >= px && mx < px + PICKER_W && my >= py && my < py + PICKER_H) {
            pickColorAt((mx - px).toDouble(), (my - py).toDouble())
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    private fun pickColorAt(relX: Double, relY: Double) {
        val hue = (relX / PICKER_W).toFloat().coerceIn(0f, 1f)
        val brightness = (1.0f - (relY / PICKER_H).toFloat() * 0.8f).coerceIn(0.2f, 1f)
        selectedColor = Color.HSBtoRGB(hue, 0.85f, brightness) and 0xFFFFFF
        updateHexField()
    }

    private fun updateHexField() {
        updatingField = true
        hexField.value = String.format("%06X", selectedColor)
        updatingField = false
    }

    override fun isPauseScreen(): Boolean = false
}
