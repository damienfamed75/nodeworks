package damien.nodeworks.screen

import com.mojang.blaze3d.platform.NativeImage
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.hasAltDownCompat
import damien.nodeworks.compat.hasControlDownCompat
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.modifierBits
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import damien.nodeworks.compat.scan
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
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
    private var ignoreFirstClick = true

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
                // 26.1 renamed setPixelRGBA to setPixelABGR. Color.HSBtoRGB returns
                // 0xAARRGGBB, so we still swap R and B for the BGR byte order.
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                image.setPixelABGR(x, y, (0xFF shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        // 26.1: DynamicTexture requires a label arg (Supplier<String> or String)
        //  before the NativeImage/dims. The (label, width, height, zero) + setPixels
        //  path only swaps the CPU-side NativeImage — it doesn't re-upload to the GPU
        //  texture, leaving the picker rendering as solid black. Use the
        //  (label, NativeImage) ctor instead, which uploads immediately.
        val id = Identifier.fromNamespaceAndPath("nodeworks", "dynamic/color_picker")
        val texture = DynamicTexture({ id.toString() }, image)
        minecraft?.textureManager?.register(id, texture)
        return id
    }

    override fun removed() {
        super.removed()
        pickerTextureId?.let { minecraft?.textureManager?.release(it) }
        pickerTextureId = null
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Override to prevent MC's default blur/darken background.
        // We draw our own dim overlay in extractRenderState() instead.
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Dim background (no blur)
        graphics.fill(0, 0, width, height, 0x88000000.toInt())

        // Panel frame
        NineSlice.WINDOW_FRAME.draw(graphics, panelX, panelY, PANEL_W, PANEL_H)

        // Top bar
        NineSlice.drawTitleBar(graphics, font, title, panelX, panelY, PANEL_W, TOP_BAR_H, selectedColor)

        // Color palette with border frame
        val px = panelX + PICKER_X
        val py = panelY + PICKER_Y
        pickerTextureId?.let { texId ->
            graphics.blit(texId, px, py, 0f, 0f, PICKER_W, PICKER_H, PICKER_W, PICKER_H)
        }
        NineSlice.CONTENT_BORDER.draw(graphics, px - 2, py - 2, PICKER_W + 4, PICKER_H + 4)

        // Hex label
        graphics.drawString(font, "#", panelX + PICKER_X + 10, panelY + PICKER_Y + PICKER_H + 9, 0xFFAAAAAA.toInt(), false)

        // Preview swatch with slot border
        val swatchX = panelX + PICKER_X + 72
        val swatchY = panelY + PICKER_Y + PICKER_H + 6
        NineSlice.SLOT.draw(graphics, swatchX - 1, swatchY - 1, 16, 16)
        graphics.fill(swatchX, swatchY, swatchX + 14, swatchY + 14, selectedColor or 0xFF000000.toInt())

        // Buttons
        val btnY = panelY + PANEL_H - BTN_H - 8

        // Default button
        val defX = panelX + 10
        renderButton(graphics, defX, btnY, BTN_W, BTN_H, "Default", mouseX, mouseY)

        // Confirm button
        val confX = panelX + PANEL_W - BTN_W - 10
        renderButton(graphics, confX, btnY, BTN_W, BTN_H, "Confirm", mouseX, mouseY, green = true)

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, bw: Int, bh: Int, label: String, mouseX: Int, mouseY: Int, green: Boolean = false) {
        val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, bx, by, bw, bh)
        val textColor = if (green) {
            if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        } else {
            if (hovered) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
        }
        graphics.drawString(font, label, bx + (bw - font.width(label)) / 2, by + (bh - 8) / 2, textColor)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (ignoreFirstClick) {
            ignoreFirstClick = false
            return true
        }
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

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

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val px = panelX + PICKER_X
        val py = panelY + PICKER_Y
        if (button == 0 && mx >= px && mx < px + PICKER_W && my >= py && my < py + PICKER_H) {
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
