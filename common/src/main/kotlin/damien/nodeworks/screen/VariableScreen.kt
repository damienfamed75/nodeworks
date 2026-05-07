package damien.nodeworks.screen

import damien.nodeworks.block.entity.VariableType
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.screen.widget.ChannelPickerWidget
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/**
 * Variable settings screen, themed after the User/Breaker/Placer device GUIs:
 * a flat [NineSlice.WINDOW_FRAME] with no title bar.
 *
 *  * Row 1: name [EditBox] with "Variable name" hint + [Set] button.
 *  * Row 2: "Type:" caption + three [NineSlice.BUTTON_ACTIVE]-highlighted
 *    buttons (Number / String / Bool).
 *  * Row 3: "Value:" caption + value [EditBox] + [Set] button. Swaps to a
 *    bool [NineSlice.TOGGLE_ACTIVE]/[NineSlice.TOGGLE_INACTIVE] when the
 *    variable type is Bool.
 *  * Settings cluster ([NineSlice.WINDOW_RECESSED]): channel label + picker.
 *
 * Channel rides on [VariableMenu]'s [ContainerData] so external mutations
 * push down without clobbering an in-flight pick. Name + value seed from
 * [VariableOpenData] and use the same commit-style pattern as the device
 * screens (Set button, Enter, click-out, last-write-wins, change-detected).
 */
class VariableScreen(
    menu: VariableMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<VariableMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 176
        private const val OUTER_PAD = 4

        private const val FIELD_H = 12
        private const val SET_BTN_W = 24
        private const val SET_BTN_H = 16
        private const val ICON_BTN_SIZE = 16
        private const val TYPE_BTN_W = 48
        private const val TYPE_BTN_H = 16
        private const val TYPE_BTN_GAP = 4
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16

        private const val LABEL_ROW_H = 8
        private const val LABEL_TOP_PAD = 3
        private const val LABEL_BOTTOM_PAD = 2

        private const val SETTINGS_INNER_PAD_TOP = 6
        private const val SETTINGS_INNER_PAD_BOTTOM = 4
        private const val SETTINGS_PANEL_H =
            SETTINGS_INNER_PAD_TOP + SETTINGS_INNER_PAD_BOTTOM + ICON_BTN_SIZE

        private const val NAME_ROW_Y = OUTER_PAD + 1
        private const val TYPE_LABEL_Y = NAME_ROW_Y + 16 + LABEL_TOP_PAD
        private const val TYPE_ROW_Y = TYPE_LABEL_Y + LABEL_ROW_H + LABEL_BOTTOM_PAD
        private const val VALUE_LABEL_Y = TYPE_ROW_Y + TYPE_BTN_H + LABEL_TOP_PAD
        private const val VALUE_ROW_Y = VALUE_LABEL_Y + LABEL_ROW_H + LABEL_BOTTOM_PAD
        private const val SETTINGS_PANEL_Y = VALUE_ROW_Y + 16 + OUTER_PAD

        private const val IMAGE_H = SETTINGS_PANEL_Y + SETTINGS_PANEL_H + OUTER_PAD

        /** Checkmark flash duration after a successful Set click (ticks). */
        private const val CHECKMARK_DURATION = 30L

        /** Gate for the post-Set checkmark flash. Hidden for visual parity
         *  with the other device GUIs (User / Breaker / Placer) which don't
         *  show a confirmation glyph. The state-tracking + render path are
         *  kept intact so flipping this back to `true` re-enables the
         *  flash without touching call sites. */
        private const val SHOW_CHECKMARK_FLASH = false

        private val TYPE_LABELS = arrayOf("Number", "String", "Bool")

        /** Total width of the three [TYPE_BTN_W] buttons + [TYPE_BTN_GAP]
         *  separators. Used to centre the cluster horizontally. */
        private const val TYPE_CLUSTER_W = TYPE_BTN_W * 3 + TYPE_BTN_GAP * 2
    }

    private lateinit var nameField: EditBox
    private lateinit var valueField: EditBox
    private var picker: ChannelPickerWidget? = null

    private var lastSyncedChannel: Int = -1

    /** Last value committed to the server for each text field. Suppresses
     *  redundant [VariableSettingsPayload] sends when the field's value
     *  hasn't changed since last commit. */
    private var lastSentName: String = ""
    private var lastSentValue: String = ""

    private var checkmarkId: String? = null
    private var checkmarkTime: Long = 0

    private var channelLabelW: Int = 0

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    // ---- Layout helpers ----

    private val nameSetBtnX: Int get() = leftPos + IMAGE_W - OUTER_PAD - SET_BTN_W
    private val nameFieldX: Int get() = leftPos + OUTER_PAD + 2
    private val nameFieldW: Int get() = nameSetBtnX - 4 - nameFieldX

    /** First [TYPE_BTN_W] button x. Centres the 3-button cluster inside the
     *  GUI's horizontal bounds. */
    private val typeRowFirstX: Int get() = leftPos + (IMAGE_W - TYPE_CLUSTER_W) / 2

    private val valueSetBtnX: Int get() = nameSetBtnX
    private val valueFieldX: Int get() = nameFieldX
    private val valueFieldW: Int get() = nameFieldW

    /** Bool toggle x. Centres the [TOGGLE_W] toggle inside the GUI's
     *  horizontal bounds for the Bool variant of the value row. */
    private val boolToggleX: Int get() = leftPos + (IMAGE_W - TOGGLE_W) / 2

    private val settingsPanelX: Int get() = leftPos + OUTER_PAD
    private val settingsPanelW: Int get() = IMAGE_W - OUTER_PAD * 2
    private val settingsRowY: Int get() = topPos + SETTINGS_PANEL_Y + SETTINGS_INNER_PAD_TOP

    /** Channel: label + picker centred horizontally in the recessed panel. */
    private val channelClusterWidth: Int get() = channelLabelW + 4 + ICON_BTN_SIZE
    private val channelLabelX: Int get() = settingsPanelX + (settingsPanelW - channelClusterWidth) / 2
    private val channelPickerX: Int get() = channelLabelX + channelLabelW + 4

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        channelLabelW = font.width("Channel:")

        lastSentName = menu.initialName
        lastSentValue = menu.initialValue

        nameField = EditBox(font, nameFieldX, topPos + NAME_ROW_Y + 2, nameFieldW, FIELD_H, Component.literal("Name")).also {
            it.setMaxLength(32)
            it.setBordered(true)
            it.setTextColor(0xFFFFFFFF.toInt())
            it.setHint(Component.literal("Variable name").withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialName
        }
        addRenderableWidget(nameField)

        valueField = EditBox(font, valueFieldX, topPos + VALUE_ROW_Y + 2, valueFieldW, FIELD_H, Component.literal("Value")).also {
            it.setMaxLength(256)
            it.setBordered(true)
            it.setTextColor(0xFFFFFFFF.toInt())
            it.setHint(Component.literal("Value").withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialValue
        }
        addRenderableWidget(valueField)

        val initialChannel = runCatching { DyeColor.byId(menu.channelId) }.getOrDefault(DyeColor.WHITE)
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(channelPickerX, settingsRowY, initialChannel) { color ->
            if (color != null) sendUpdate("channel", color.id, "")
        }
        addRenderableWidget(picker!!)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        val serverChannel = menu.channelId
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }

        // Name row + Set button.
        drawSetButton(graphics, nameSetBtnX, topPos + NAME_ROW_Y, mouseX, mouseY, "name")

        // Type caption + three buttons.
        graphics.drawString(font, "Type:", leftPos + OUTER_PAD + 2, topPos + TYPE_LABEL_Y, 0xFFAAAAAA.toInt())
        for (i in 0 until 3) {
            drawTypeButton(graphics, typeRowFirstX + i * (TYPE_BTN_W + TYPE_BTN_GAP), topPos + TYPE_ROW_Y, i, mouseX, mouseY)
        }

        // Value caption + value control (field+Set, or Bool toggle).
        graphics.drawString(font, "Value:", leftPos + OUTER_PAD + 2, topPos + VALUE_LABEL_Y, 0xFFAAAAAA.toInt())
        if (menu.variableType == VariableType.BOOL.ordinal) {
            valueField.visible = false
            drawBoolToggle(graphics, boolToggleX, topPos + VALUE_ROW_Y, mouseX, mouseY)
        } else {
            valueField.visible = true
            drawSetButton(graphics, valueSetBtnX, topPos + VALUE_ROW_Y, mouseX, mouseY, "value")
        }

        // Channel cluster (recessed).
        NineSlice.WINDOW_RECESSED.draw(graphics, settingsPanelX, topPos + SETTINGS_PANEL_Y, settingsPanelW, SETTINGS_PANEL_H)
        val labelY = settingsRowY + (ICON_BTN_SIZE - 8) / 2 + 1
        graphics.drawString(font, "Channel:", channelLabelX, labelY, 0xFFAAAAAA.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    private fun drawSetButton(
        graphics: GuiGraphicsExtractor,
        bx: Int,
        by: Int,
        mouseX: Int,
        mouseY: Int,
        id: String,
    ) {
        val hovered = mouseX in bx until bx + SET_BTN_W && mouseY in by until by + SET_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, bx, by, SET_BTN_W, SET_BTN_H)
        val label = "Set"
        val textColor = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, bx + (SET_BTN_W - font.width(label)) / 2, by + 5, textColor)

        if (SHOW_CHECKMARK_FLASH && checkmarkId == id) {
            val mc = Minecraft.getInstance()
            val elapsed = mc.level?.gameTime?.minus(checkmarkTime) ?: CHECKMARK_DURATION
            if (elapsed < CHECKMARK_DURATION) {
                Icons.CHECKMARK.draw(graphics, bx + SET_BTN_W + 1, by)
            } else {
                checkmarkId = null
            }
        }
    }

    private fun drawTypeButton(
        graphics: GuiGraphicsExtractor,
        bx: Int,
        by: Int,
        idx: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val selected = menu.variableType == idx
        val hovered = mouseX in bx until bx + TYPE_BTN_W && mouseY in by until by + TYPE_BTN_H
        val slice = when {
            selected -> NineSlice.BUTTON_ACTIVE
            hovered -> NineSlice.BUTTON_HOVER
            else -> NineSlice.BUTTON
        }
        slice.draw(graphics, bx, by, TYPE_BTN_W, TYPE_BTN_H)
        val label = TYPE_LABELS[idx]
        val textColor = if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
        graphics.drawString(font, label, bx + (TYPE_BTN_W - font.width(label)) / 2, by + 5, textColor)
    }

    private fun drawBoolToggle(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val slice = if (menu.boolValue) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        slice.draw(graphics, bx, by, TOGGLE_W, TOGGLE_H)
        val hovered = mouseX in bx until bx + TOGGLE_W && mouseY in by until by + TOGGLE_H
        if (hovered) queueTooltip(
            mouseX, mouseY,
            if (menu.boolValue) "true" else "false",
            "Click to toggle.",
        )
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        if (nameField.isFocused) {
            if (event.keyCode == 257 || event.keyCode == 335) {
                commitName()
                showCheckmark("name")
                if (focused === nameField) setFocused(null) else nameField.isFocused = false
                return true
            }
            nameField.keyPressed(event)
            return true
        }
        if (valueField.isFocused) {
            if (event.keyCode == 257 || event.keyCode == 335) {
                commitValue()
                showCheckmark("value")
                if (focused === valueField) setFocused(null) else valueField.isFocused = false
                return true
            }
            valueField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // Name Set button.
        val nameSetY = topPos + NAME_ROW_Y
        if (mx in nameSetBtnX until nameSetBtnX + SET_BTN_W && my in nameSetY until nameSetY + SET_BTN_H) {
            commitName()
            showCheckmark("name")
            playClickSound()
            if (focused === nameField) setFocused(null) else nameField.isFocused = false
            return true
        }

        // Type buttons.
        val typeY = topPos + TYPE_ROW_Y
        for (i in 0 until 3) {
            val bx = typeRowFirstX + i * (TYPE_BTN_W + TYPE_BTN_GAP)
            if (mx in bx until bx + TYPE_BTN_W && my in typeY until typeY + TYPE_BTN_H) {
                sendUpdate("type", i, "")
                valueField.value = VariableType.fromOrdinal(i).defaultValue
                lastSentValue = valueField.value
                playClickSound()
                clearFieldFocus()
                return true
            }
        }

        // Value control: Bool toggle OR Set button.
        val valueY = topPos + VALUE_ROW_Y
        if (menu.variableType == VariableType.BOOL.ordinal) {
            if (mx in boolToggleX until boolToggleX + TOGGLE_W && my in valueY until valueY + TOGGLE_H) {
                sendUpdate("toggle", 0, "")
                playClickSound()
                clearFieldFocus()
                return true
            }
        } else {
            if (mx in valueSetBtnX until valueSetBtnX + SET_BTN_W && my in valueY until valueY + SET_BTN_H) {
                commitValue()
                showCheckmark("value")
                playClickSound()
                if (focused === valueField) setFocused(null) else valueField.isFocused = false
                return true
            }
        }

        val handled = super.mouseClicked(event, doubleClick)

        // Click outside a focused field commits + defocuses it. Mirrors the
        // device-screen pattern; routing through [setFocused(null)] avoids
        // vanilla's equality short-circuit blocking re-focus.
        defocusIfClickedOutside(nameField, mx, my, ::commitName)
        defocusIfClickedOutside(valueField, mx, my, ::commitValue)

        return handled
    }

    private fun defocusIfClickedOutside(field: EditBox, mx: Int, my: Int, commit: () -> Unit) {
        if (!field.isFocused) return
        val inside = mx in field.x until field.x + field.width &&
            my in field.y until field.y + field.height
        if (inside) return
        commit()
        if (focused === field) setFocused(null) else field.isFocused = false
    }

    private fun commitName() {
        if (nameField.value == lastSentName) return
        sendUpdate("name", 0, nameField.value)
        lastSentName = nameField.value
    }

    private fun commitValue() {
        if (valueField.value == lastSentValue) return
        sendUpdate("value", 0, valueField.value)
        lastSentValue = valueField.value
    }

    private fun clearFieldFocus() {
        nameField.isFocused = false
        valueField.isFocused = false
        setFocused(null)
    }

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f,
            )
        )
    }

    private fun showCheckmark(id: String) {
        checkmarkId = id
        checkmarkTime = Minecraft.getInstance().level?.gameTime ?: 0
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.VariableSettingsPayload(menu.variablePos, key, intValue, strValue)
        )
    }
}
