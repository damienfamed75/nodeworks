package damien.nodeworks.screen.widget

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.screen.NineSlice
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * Title-bar rename strip for the card settings screens. Replaces the static
 * "Card Settings" / "Storage Card" header with a vanilla-bordered [EditBox] +
 * [Set] button, sized to fit inside the existing title-bar height so card
 * GUIs don't have to grow taller. The host screen forwards [mouseClicked] and
 * [keyPressed] in before its own handlers so Enter / clicking Set commits.
 */
class CardRenameRow(
    private val font: Font,
    private val leftPos: Int,
    private val topPos: Int,
    private val imageWidth: Int,
    initialName: String,
    private val sendRename: (String) -> Unit,
) {
    companion object {
        private const val ROW_Y = 4
        private const val FIELD_H = 14
        private const val SET_BTN_W = 32
        private const val SET_BTN_H = 14
        private const val FIELD_X = 5
        private const val FIELD_RIGHT_PAD = 4
        private const val FIELD_TO_BTN_GAP = 4
    }

    private val fieldX: Int = leftPos + FIELD_X
    private val fieldY: Int = topPos + ROW_Y
    private val setBtnX: Int = leftPos + imageWidth - SET_BTN_W - FIELD_RIGHT_PAD
    private val setBtnY: Int = topPos + ROW_Y
    private val fieldW: Int = setBtnX - fieldX - FIELD_TO_BTN_GAP

    private val nameField: EditBox = EditBox(
        font, fieldX, fieldY, fieldW, FIELD_H, Component.literal("Name")
    ).also {
        it.setMaxLength(damien.nodeworks.network.SetCardNamePayload.MAX_NAME_LENGTH)
        it.setBordered(true)
        it.setTextColor(0xFFFFFFFF.toInt())
        it.setHint(Component.literal("Card name").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
        it.value = initialName
    }

    fun addToScreen(register: (AbstractWidget) -> Unit) {
        register(nameField)
    }

    /** The EditBox is registered as a normal screen widget so vanilla handles
     *  its render and click; only the Set button is drawn here. */
    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        drawSetButton(graphics, mouseX, mouseY)
    }

    private fun drawSetButton(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in setBtnX until setBtnX + SET_BTN_W && mouseY in setBtnY until setBtnY + SET_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, setBtnX, setBtnY, SET_BTN_W, SET_BTN_H)
        val label = "Set"
        val color = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, setBtnX + (SET_BTN_W - font.width(label)) / 2, setBtnY + (SET_BTN_H - font.lineHeight) / 2 + 1, color)
    }

    /** Returns true when the click landed on the Set button. Field clicks
     *  fall through to vanilla's widget dispatch (the EditBox is registered
     *  as a screen widget). */
    fun mouseClicked(event: MouseButtonEvent): Boolean {
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        if (mx in setBtnX until setBtnX + SET_BTN_W && my in setBtnY until setBtnY + SET_BTN_H) {
            commit()
            return true
        }
        return false
    }

    /** Forwards keys to the focused name field. Enter commits, Esc falls
     *  through so the host's close-screen behavior still wins. */
    fun keyPressed(event: KeyEvent): Boolean {
        if (!nameField.isFocused) return false
        if (event.keyCode == InputConstants.KEY_RETURN) {
            commit()
            return true
        }
        if (event.keyCode == InputConstants.KEY_ESCAPE) return false
        return nameField.keyPressed(event)
    }

    private fun commit() {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
        )
        sendRename(nameField.value)
        nameField.isFocused = false
    }
}
