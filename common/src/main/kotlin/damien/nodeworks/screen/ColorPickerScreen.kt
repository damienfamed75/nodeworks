package damien.nodeworks.screen

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import java.awt.Color

/**
 * Color picker popup — dark themed to match the Network Controller GUI.
 */
class ColorPickerScreen(
    private val parentScreen: net.minecraft.client.gui.screens.Screen,
    private val initialColor: Int,
    private val defaultColor: Int,
    private val onConfirm: (Int) -> Unit
) : net.minecraft.client.gui.screens.Screen(Component.literal("Color Picker")) {

    companion object {
        private const val PANEL_W = 180
        private const val PANEL_H = 140
        private const val TOP_BAR_H = 20
        private const val PICKER_W = 160
        private const val PICKER_H = 60
        private const val PICKER_X = 10
        private const val PICKER_Y = 24
        private const val BTN_W = 50
        private const val BTN_H = 16
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
        hexField = EditBox(font, panelX + PICKER_X + 18, panelY + PICKER_Y + PICKER_H + 6, 50, 14, Component.literal("Hex"))
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
    }

    private fun createPickerTexture(): Identifier {
        val image = NativeImage(PICKER_W, PICKER_H, false)
        for (x in 0 until PICKER_W) {
            val hue = x.toFloat() / PICKER_W
            for (y in 0 until PICKER_H) {
                val brightness = 1.0f - (y.toFloat() / PICKER_H) * 0.8f
                val rgb = Color.HSBtoRGB(hue, 0.85f, brightness)
                // Color.HSBtoRGB returns 0xAARRGGBB, NativeImage expects ABGR
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

        // Dark panel background
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + TOP_BAR_H, 0xFF3C3C3C.toInt())
        graphics.fill(panelX, panelY + TOP_BAR_H - 1, panelX + PANEL_W, panelY + TOP_BAR_H, 0xFF555555.toInt())
        graphics.drawString(font, title, panelX + 6, panelY + 6, 0xFFFFFFFF.toInt())

        // Color palette
        val px = panelX + PICKER_X
        val py = panelY + PICKER_Y
        pickerTextureId?.let { texId ->
            graphics.blit(RenderPipelines.GUI_TEXTURED, texId, px, py, 0f, 0f, PICKER_W, PICKER_H, PICKER_W, PICKER_H)
        }
        // Inset border
        graphics.fill(px - 1, py - 1, px + PICKER_W + 1, py, 0xFF555555.toInt())
        graphics.fill(px - 1, py - 1, px, py + PICKER_H + 1, 0xFF555555.toInt())
        graphics.fill(px + PICKER_W, py - 1, px + PICKER_W + 1, py + PICKER_H + 1, 0xFF3C3C3C.toInt())
        graphics.fill(px - 1, py + PICKER_H, px + PICKER_W + 1, py + PICKER_H + 1, 0xFF3C3C3C.toInt())

        // Hex label
        graphics.drawString(font, "#", panelX + PICKER_X + 10, panelY + PICKER_Y + PICKER_H + 9, 0xFFAAAAAA.toInt(), false)

        // Preview swatch
        val swatchX = panelX + PICKER_X + 72
        val swatchY = panelY + PICKER_Y + PICKER_H + 6
        graphics.fill(swatchX, swatchY, swatchX + 14, swatchY + 14, selectedColor or 0xFF000000.toInt())
        graphics.fill(swatchX - 1, swatchY - 1, swatchX + 15, swatchY, 0xFF555555.toInt())
        graphics.fill(swatchX - 1, swatchY - 1, swatchX, swatchY + 15, 0xFF555555.toInt())
        graphics.fill(swatchX + 14, swatchY - 1, swatchX + 15, swatchY + 15, 0xFF3C3C3C.toInt())
        graphics.fill(swatchX - 1, swatchY + 14, swatchX + 15, swatchY + 15, 0xFF3C3C3C.toInt())

        // Buttons
        val btnY = panelY + PANEL_H - BTN_H - 8

        // Default button
        val defX = panelX + 10
        renderButton(graphics, defX, btnY, BTN_W, BTN_H, "Default", mouseX, mouseY)

        // Confirm button
        val confX = panelX + PANEL_W - BTN_W - 10
        renderConfirmButton(graphics, confX, btnY, BTN_W, BTN_H, "Confirm", mouseX, mouseY)

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderButton(graphics: GuiGraphics, bx: Int, by: Int, bw: Int, bh: Int, label: String, mouseX: Int, mouseY: Int) {
        val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh
        val bg = if (hovered) 0xFF444444.toInt() else 0xFF333333.toInt()
        graphics.fill(bx, by, bx + bw, by + bh, bg)
        graphics.fill(bx, by, bx + bw, by + 1, 0xFF4A4A4A.toInt())
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF1E1E1E.toInt())
        val textColor = if (hovered) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
        graphics.drawString(font, label, bx + (bw - font.width(label)) / 2, by + (bh - 8) / 2, textColor)
    }

    private fun renderConfirmButton(graphics: GuiGraphics, bx: Int, by: Int, bw: Int, bh: Int, label: String, mouseX: Int, mouseY: Int) {
        val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh
        val bg = if (hovered) 0xFF3A5A3A.toInt() else 0xFF2A4A2A.toInt()
        graphics.fill(bx, by, bx + bw, by + bh, bg)
        graphics.fill(bx, by, bx + bw, by + 1, 0xFF4A6A4A.toInt())
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF1A3A1A.toInt())
        val textColor = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, bx + (bw - font.width(label)) / 2, by + (bh - 8) / 2, textColor)
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, flag: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()

        // Color picker click
        val px = panelX + PICKER_X
        val py = panelY + PICKER_Y
        if (mx >= px && mx < px + PICKER_W && my >= py && my < py + PICKER_H) {
            pickColorAt((mx - px).toDouble(), (my - py).toDouble())
            return true
        }

        // Button clicks
        val btnY = panelY + PANEL_H - BTN_H - 8

        // Default button
        val defX = panelX + 10
        if (mx >= defX && mx < defX + BTN_W && my >= btnY && my < btnY + BTN_H) {
            selectedColor = defaultColor
            updateHexField()
            playClick()
            return true
        }

        // Confirm button
        val confX = panelX + PANEL_W - BTN_W - 10
        if (mx >= confX && mx < confX + BTN_W && my >= btnY && my < btnY + BTN_H) {
            onConfirm(selectedColor)
            playClick()
            minecraft?.setScreen(parentScreen)
            return true
        }

        return super.mouseClicked(event, flag)
    }

    override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mx = event.x()
        val my = event.y()
        val px = panelX + PICKER_X
        val py = panelY + PICKER_Y
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

    private fun playClick() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    override fun isPauseScreen(): Boolean = false
}
