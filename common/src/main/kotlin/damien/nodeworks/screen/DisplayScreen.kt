package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.platform.PlatformServices
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Settings screen for the Display block. A single name [EditBox] + [Set]
 * button inside a flat [NineSlice.WINDOW_FRAME], styled like the name row of
 * [BreakerScreen]. The name is the cluster-wide identity, opening the GUI on
 * any member of a multiblock Display edits the same shared name.
 *
 * The name seeds from [DisplayOpenData] and commits through
 * [damien.nodeworks.network.DeviceSettingsPayload] (`key = "name"`) on the
 * Set button, Enter, or click-outside, last-write-wins.
 */
class DisplayScreen(
    menu: DisplayMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<DisplayMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 180
        private const val OUTER_PAD = 4
        private const val FIELD_H = 12
        private const val SET_BTN_W = 24
        private const val SET_BTN_H = 16
        private const val LABEL_ROW_H = 8
        private const val LABEL_TOP_PAD = 3

        private const val LABEL_Y = OUTER_PAD + 1
        private const val NAME_ROW_Y = LABEL_Y + LABEL_ROW_H + LABEL_TOP_PAD
        private const val IMAGE_H = NAME_ROW_Y + SET_BTN_H + OUTER_PAD

        private const val LABEL_TEXT = "Display Name:"
    }

    private lateinit var nameField: EditBox
    private var lastSentName: String = ""

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private val setBtnX: Int get() = leftPos + IMAGE_W - OUTER_PAD - SET_BTN_W
    private val nameFieldX: Int get() = leftPos + OUTER_PAD + 2
    private val nameFieldW: Int get() = setBtnX - 4 - nameFieldX

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
        lastSentName = menu.initialName

        nameField = EditBox(
            font, nameFieldX, topPos + NAME_ROW_Y + 2, nameFieldW, FIELD_H, Component.literal("Name"),
        ).also {
            it.setMaxLength(DisplayOpenData.MAX_NAME_LENGTH)
            it.setBordered(true)
            it.setTextColor(0xFFFFFFFF.toInt())
            it.setHint(Component.literal("Display name").withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialName
        }
        addRenderableWidget(nameField)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        graphics.drawString(font, LABEL_TEXT, leftPos + OUTER_PAD + 2, topPos + LABEL_Y, 0xFFAAAAAA.toInt())
        drawSetButton(graphics, setBtnX, topPos + NAME_ROW_Y, mouseX, mouseY)
    }

    private fun drawSetButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in bx until bx + SET_BTN_W && mouseY in by until by + SET_BTN_H
        (if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, bx, by, SET_BTN_W, SET_BTN_H)
        val color = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, "Set", bx + (SET_BTN_W - font.width("Set")) / 2, by + 5, color)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        if (nameField.isFocused) {
            if (event.keyCode == 257 || event.keyCode == 335) {
                commitName()
                if (focused === nameField) setFocused(null) else nameField.isFocused = false
                return true
            }
            nameField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        if (mx in setBtnX until setBtnX + SET_BTN_W &&
            my in topPos + NAME_ROW_Y until topPos + NAME_ROW_Y + SET_BTN_H
        ) {
            commitName()
            playClickSound()
            if (focused === nameField) setFocused(null) else nameField.isFocused = false
            return true
        }
        val handled = super.mouseClicked(event, doubleClick)
        // Click outside the focused field commits + defocuses it.
        if (nameField.isFocused) {
            val inside = mx in nameField.x until nameField.x + nameField.width &&
                my in nameField.y until nameField.y + nameField.height
            if (!inside) {
                commitName()
                if (focused === nameField) setFocused(null) else nameField.isFocused = false
            }
        }
        return handled
    }

    private fun commitName() {
        if (nameField.value == lastSentName) return
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(menu.devicePos, "name", 0, nameField.value)
        )
        lastSentName = nameField.value
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f,
            )
        )
    }
}
