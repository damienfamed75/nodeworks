package damien.nodeworks.screen

import damien.nodeworks.block.entity.VariableType
import damien.nodeworks.platform.PlatformServices
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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

class VariableScreen(
    menu: VariableMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<VariableMenu>(menu, playerInventory, title, 200, 110) {

    companion object {
        private const val TOP_BAR_H = 20
        private const val ROW_H = 24
        private const val LABEL_W = 50
        private val TYPE_LABELS = arrayOf("Number", "String", "Bool")

        // Set button dimensions
        private const val SET_BTN_W = 26
        private const val SET_BTN_H = 16

        // Icon atlas (256x256, 16x16 icons)
        private val ICONS = Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")
        // Icon positions in atlas (column, row)
        const val ICON_CHECKMARK = 0
        const val ICON_CROSS = 1
        const val ICON_ARROW_RIGHT = 2
        const val ICON_ARROW_LEFT = 3
    }

    private lateinit var nameField: EditBox
    private lateinit var valueField: EditBox

    // Checkmark flash state: which button ("name" or "value") and tick when it was set
    private var checkmarkId: String? = null
    private var checkmarkTime: Long = 0
    private val CHECKMARK_DURATION = 30L // ticks (~1.5 seconds)

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        val contentLeft = leftPos + 4 + LABEL_W + 4

        // Name field (narrower to fit Set button)
        nameField = EditBox(font, contentLeft, topPos + TOP_BAR_H + (ROW_H - 16) / 2, 90, 16, Component.literal("Name"))
        nameField.setMaxLength(32)
        nameField.value = menu.initialName
        addRenderableWidget(nameField)

        // Value field (narrower to make room for Set button)
        val valueFieldW = 120 - SET_BTN_W - 4
        valueField = EditBox(font, contentLeft, topPos + TOP_BAR_H + ROW_H * 2 + (ROW_H - 16) / 2, valueFieldW, 16, Component.literal("Value"))
        valueField.setMaxLength(256)
        valueField.value = menu.initialValue
        addRenderableWidget(valueField)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Main background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + TOP_BAR_H, 0xFF3C3C3C.toInt())
        graphics.fill(leftPos, topPos + TOP_BAR_H - 1, leftPos + imageWidth, topPos + TOP_BAR_H, 0xFF555555.toInt())
        graphics.drawString(font, title, leftPos + 6, topPos + 6, 0xFFFFFFFF.toInt())

        val listLeft = leftPos + 4
        val listRight = leftPos + imageWidth - 4
        val controlX = listLeft + LABEL_W + 4

        // Row 0: Name (+ Set button)
        var rowY = topPos + TOP_BAR_H
        graphics.fill(listLeft, rowY, listRight, rowY + ROW_H, 0xFF1E1E1E.toInt())
        graphics.fill(listLeft, rowY + ROW_H - 1, listRight, rowY + ROW_H, 0xFF3C3C3C.toInt())
        graphics.drawString(font, "Name", listLeft + 6, rowY + (ROW_H - 8) / 2, 0xFFAAAAAA.toInt())
        renderSetButton(graphics, controlX + 94, rowY + (ROW_H - SET_BTN_H) / 2, mouseX, mouseY, "name")

        // Row 1: Type
        rowY += ROW_H
        graphics.fill(listLeft, rowY, listRight, rowY + ROW_H, 0xFF252525.toInt())
        graphics.fill(listLeft, rowY + ROW_H - 1, listRight, rowY + ROW_H, 0xFF3C3C3C.toInt())
        graphics.drawString(font, "Type", listLeft + 6, rowY + (ROW_H - 8) / 2, 0xFFAAAAAA.toInt())
        renderTypeButtons(graphics, controlX, rowY + (ROW_H - 16) / 2, mouseX, mouseY)

        // Row 2: Value (+ Set button or Bool toggle)
        rowY += ROW_H
        graphics.fill(listLeft, rowY, listRight, rowY + ROW_H, 0xFF1E1E1E.toInt())
        graphics.fill(listLeft, rowY + ROW_H - 1, listRight, rowY + ROW_H, 0xFF3C3C3C.toInt())
        graphics.drawString(font, "Value", listLeft + 6, rowY + (ROW_H - 8) / 2, 0xFFAAAAAA.toInt())

        if (menu.variableType == VariableType.BOOL.ordinal) {
            valueField.visible = false
            renderBoolToggle(graphics, controlX, rowY + (ROW_H - 16) / 2, mouseX, mouseY)
        } else {
            valueField.visible = true
            renderSetButton(graphics, controlX + 120 - SET_BTN_W, rowY + (ROW_H - SET_BTN_H) / 2, mouseX, mouseY, "value")
        }
    }

    private fun renderSetButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int, id: String) {
        val hovered = mouseX >= bx && mouseX < bx + SET_BTN_W && mouseY >= by && mouseY < by + SET_BTN_H
        val bg = if (hovered) 0xFF3A5A3A.toInt() else 0xFF2A4A2A.toInt()
        graphics.fill(bx, by, bx + SET_BTN_W, by + SET_BTN_H, bg)
        graphics.fill(bx, by, bx + SET_BTN_W, by + 1, 0xFF4A6A4A.toInt())
        graphics.fill(bx, by + SET_BTN_H - 1, bx + SET_BTN_W, by + SET_BTN_H, 0xFF1A3A1A.toInt())
        val label = "Set"
        val textColor = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, bx + (SET_BTN_W - font.width(label)) / 2, by + 4, textColor)

        // Checkmark icon after click
        if (checkmarkId == id) {
            val mc = net.minecraft.client.Minecraft.getInstance()
            val elapsed = mc.level?.gameTime?.minus(checkmarkTime) ?: CHECKMARK_DURATION
            if (elapsed < CHECKMARK_DURATION) {
                val iconX = bx + SET_BTN_W + 1
                val iconY = by
                val u = (ICON_CHECKMARK * 16).toFloat()
                val v = 0f
                graphics.blit(ICONS, iconX, iconY, u, v, 16, 16, 256, 256)
            } else {
                checkmarkId = null
            }
        }
    }

    private fun renderTypeButtons(graphics: GuiGraphicsExtractor, startX: Int, by: Int, mouseX: Int, mouseY: Int) {
        val currentType = menu.variableType
        for (i in 0 until 3) {
            val bx = startX + i * 42
            val bw = 40
            val bh = 16
            val selected = currentType == i
            val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh

            val bg = when {
                selected -> 0xFF4A4A4A.toInt()
                hovered -> 0xFF3A3A3A.toInt()
                else -> 0xFF333333.toInt()
            }
            graphics.fill(bx, by, bx + bw, by + bh, bg)
            if (selected) {
                graphics.fill(bx, by, bx + bw, by + 1, 0xFFAAAAAA.toInt())
                graphics.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFFAAAAAA.toInt())
                graphics.fill(bx, by, bx + 1, by + bh, 0xFFAAAAAA.toInt())
                graphics.fill(bx + bw - 1, by, bx + bw, by + bh, 0xFFAAAAAA.toInt())
            } else {
                graphics.fill(bx, by, bx + bw, by + 1, 0xFF4A4A4A.toInt())
                graphics.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF1E1E1E.toInt())
            }

            val textColor = if (selected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()
            val label = TYPE_LABELS[i]
            graphics.drawString(font, label, bx + (bw - font.width(label)) / 2, by + 4, textColor)
        }
    }

    private fun renderBoolToggle(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val currentValue = menu.boolValue
        val bw = 40
        val bh = 16
        val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh

        val bg = if (currentValue) {
            if (hovered) 0xFF2A6A2A.toInt() else 0xFF1E5A1E.toInt()
        } else {
            if (hovered) 0xFF6A2A2A.toInt() else 0xFF5A1E1E.toInt()
        }
        graphics.fill(bx, by, bx + bw, by + bh, bg)
        graphics.fill(bx, by, bx + bw, by + 1, 0xFF4A4A4A.toInt())
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF1E1E1E.toInt())

        val label = if (currentValue) "true" else "false"
        val textColor = if (currentValue) 0xFF55FF55.toInt() else 0xFFFF5555.toInt()
        graphics.drawString(font, label, bx + (bw - font.width(label)) / 2, by + 4, textColor)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
        if (nameField.isFocused || valueField.isFocused) {
            if (keyCode == 256) return super.keyPressed(event) // ESC
            nameField.keyPressed(event)
            valueField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val listLeft = leftPos + 4
        val controlX = listLeft + LABEL_W + 4

        // Row 0: Name Set button
        val nameRowY = topPos + TOP_BAR_H
        val nameSetX = controlX + 94
        val nameSetY = nameRowY + (ROW_H - SET_BTN_H) / 2
        if (mx >= nameSetX && mx < nameSetX + SET_BTN_W && my >= nameSetY && my < nameSetY + SET_BTN_H) {
            sendUpdate("name", 0, nameField.value)
            playClickSound()
            showCheckmark("name")
            return true
        }

        // Row 1: Type buttons
        val typeRowY = topPos + TOP_BAR_H + ROW_H + (ROW_H - 16) / 2
        for (i in 0 until 3) {
            val bx = controlX + i * 42
            if (mx >= bx && mx < bx + 40 && my >= typeRowY && my < typeRowY + 16) {
                sendUpdate("type", i, "")
                valueField.value = VariableType.fromOrdinal(i).defaultValue
                return true
            }
        }

        // Row 2: Value
        val valRowY = topPos + TOP_BAR_H + ROW_H * 2
        if (menu.variableType == VariableType.BOOL.ordinal) {
            // Bool toggle
            val toggleY = valRowY + (ROW_H - 16) / 2
            if (mx >= controlX && mx < controlX + 40 && my >= toggleY && my < toggleY + 16) {
                sendUpdate("toggle", 0, "")
                return true
            }
        } else {
            // Value Set button
            val valSetX = controlX + 120 - SET_BTN_W
            val valSetY = valRowY + (ROW_H - SET_BTN_H) / 2
            if (mx >= valSetX && mx < valSetX + SET_BTN_W && my >= valSetY && my < valSetY + SET_BTN_H) {
                sendUpdate("value", 0, valueField.value)
                playClickSound()
                showCheckmark("value")
                return true
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun removed() {
        super.removed()
        // No auto-save on close — user must click Set buttons explicitly
    }

    private fun playClickSound() {
        val mc = net.minecraft.client.Minecraft.getInstance()
        mc.soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private fun showCheckmark(id: String) {
        checkmarkId = id
        checkmarkTime = net.minecraft.client.Minecraft.getInstance().level?.gameTime ?: 0
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.VariableSettingsPayload(menu.variablePos, key, intValue, strValue)
        )
    }
}
