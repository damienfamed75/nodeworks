package damien.nodeworks.screen

import damien.nodeworks.block.entity.WidgetBlockEntity
import damien.nodeworks.block.entity.WidgetType
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
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
 * Widget settings screen, themed after the Variable / device GUIs: a flat
 * [NineSlice.WINDOW_FRAME] with no title bar.
 *
 *  * Name row: name [EditBox] + Set button.
 *  * Type row: "Type:" caption + four buttons (Button / Switch / Radio / Slider).
 *  * Config area, type-specific:
 *      - Button, a hold-length field (ticks the button stays latched on).
 *      - Switch, nothing.
 *      - Radio, a scrollable add / edit / delete option list.
 *      - Slider, Min / Max / Step fields.
 *  * Settings cluster: channel picker.
 */
class WidgetScreen(
    menu: WidgetMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<WidgetMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 176
        private const val OUTER_PAD = 4

        private const val FIELD_H = 12
        private const val SET_BTN_W = 24
        private const val SET_BTN_H = 16
        private const val ICON_BTN_SIZE = 16
        private const val TYPE_BTN_W = 40
        private const val TYPE_BTN_H = 16
        private const val TYPE_BTN_GAP = 2

        private const val LABEL_ROW_H = 8
        private const val LABEL_TOP_PAD = 3
        private const val LABEL_BOTTOM_PAD = 2

        // Radio option list metrics.
        private const val OPT_VISIBLE_ROWS = 4
        private const val OPT_ROW_H = 14
        private const val OPT_PANEL_INNER_PAD = 2
        private const val OPT_PANEL_H = OPT_VISIBLE_ROWS * OPT_ROW_H + OPT_PANEL_INNER_PAD * 2
        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2
        private const val OPT_DELETE_W = 11
        private const val ADD_BTN_W = 80
        private const val ADD_BTN_H = 12
        private const val ADD_BTN_GAP = 2
        private const val MAX_OPTIONS = 6

        /** Config area is sized to the tallest variant (the radio list). The
         *  other types just leave the lower part empty. */
        private const val CONFIG_AREA_H = OPT_PANEL_H + ADD_BTN_GAP + ADD_BTN_H

        private const val NAME_ROW_Y = OUTER_PAD + 1
        private const val TYPE_LABEL_Y = NAME_ROW_Y + 16 + LABEL_TOP_PAD
        private const val TYPE_ROW_Y = TYPE_LABEL_Y + LABEL_ROW_H + LABEL_BOTTOM_PAD
        private const val CONFIG_LABEL_Y = TYPE_ROW_Y + TYPE_BTN_H + LABEL_TOP_PAD
        private const val CONFIG_AREA_Y = CONFIG_LABEL_Y + LABEL_ROW_H + LABEL_BOTTOM_PAD

        private const val SETTINGS_INNER_PAD_TOP = 6
        private const val SETTINGS_INNER_PAD_BOTTOM = 4
        private const val SETTINGS_PANEL_H =
            SETTINGS_INNER_PAD_TOP + SETTINGS_INNER_PAD_BOTTOM + ICON_BTN_SIZE
        private const val SETTINGS_PANEL_Y = CONFIG_AREA_Y + CONFIG_AREA_H + OUTER_PAD

        private const val IMAGE_H = SETTINGS_PANEL_Y + SETTINGS_PANEL_H + OUTER_PAD

        private val TYPE_LABELS = arrayOf("Button", "Switch", "Radio", "Slider")
        private const val TYPE_CLUSTER_W = TYPE_BTN_W * 4 + TYPE_BTN_GAP * 3
    }

    private lateinit var nameField: EditBox
    private lateinit var holdField: EditBox
    private lateinit var minField: EditBox
    private lateinit var maxField: EditBox
    private lateinit var stepField: EditBox
    private var picker: ChannelPickerWidget? = null

    /** Dynamic per-row EditBoxes for the radio option list, rebuilt on
     *  add / delete / scroll, removed when the type isn't Radio. */
    private val optionFields: MutableList<EditBox> = mutableListOf()
    /** Local mirror of the radio option list, pushed to the server whole. */
    private val localOptions: MutableList<String> =
        menu.initialOptions.split('\n').filter { it.isNotEmpty() }.toMutableList()
    private var optionScroll: Int = 0
    private var optionsBuilt: Boolean = false
    private var draggingScrollbar: Boolean = false

    private var lastSyncedChannel: Int = -1
    private var lastSentName: String = ""
    private var lastSentHold: String = ""
    private var lastSentSlider: String = ""

    private var channelLabelW: Int = 0

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    // ---- Layout helpers ----

    private val nameSetBtnX: Int get() = leftPos + IMAGE_W - OUTER_PAD - SET_BTN_W
    private val nameFieldX: Int get() = leftPos + OUTER_PAD + 2
    private val nameFieldW: Int get() = nameSetBtnX - 4 - nameFieldX

    private val typeRowFirstX: Int get() = leftPos + (IMAGE_W - TYPE_CLUSTER_W) / 2

    private val configSetBtnX: Int get() = nameSetBtnX
    private val configFieldX: Int get() = nameFieldX
    private val configFieldW: Int get() = nameFieldW

    private val optPanelX: Int get() = leftPos + OUTER_PAD
    private val optPanelY: Int get() = topPos + CONFIG_AREA_Y
    private val optPanelW: Int get() = IMAGE_W - OUTER_PAD * 2
    private val optInteriorX: Int get() = optPanelX + OPT_PANEL_INNER_PAD
    private val optInteriorY: Int get() = optPanelY + OPT_PANEL_INNER_PAD
    private val optListW: Int get() = optPanelW - OPT_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
    private val optScrollbarX: Int get() = optPanelX + optPanelW - OPT_PANEL_INNER_PAD - SCROLL_BAR_W
    private val addBtnX: Int get() = optPanelX + (optPanelW - ADD_BTN_W) / 2
    private val addBtnY: Int get() = optPanelY + OPT_PANEL_H + ADD_BTN_GAP

    private val settingsPanelX: Int get() = leftPos + OUTER_PAD
    private val settingsPanelW: Int get() = IMAGE_W - OUTER_PAD * 2
    private val settingsRowY: Int get() = topPos + SETTINGS_PANEL_Y + SETTINGS_INNER_PAD_TOP

    private val channelClusterWidth: Int get() = channelLabelW + 4 + ICON_BTN_SIZE
    private val channelLabelX: Int get() = settingsPanelX + (settingsPanelW - channelClusterWidth) / 2
    private val channelPickerX: Int get() = channelLabelX + channelLabelW + 4

    private val currentType: WidgetType get() = WidgetType.fromOrdinal(menu.widgetType)

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        channelLabelW = font.width("Channel:")

        lastSentName = menu.initialName
        lastSentHold = menu.initialButtonHold.toString()
        lastSentSlider = sliderString(menu.initialSliderMin, menu.initialSliderMax, menu.initialSliderStep)

        nameField = EditBox(font, nameFieldX, topPos + NAME_ROW_Y + 2, nameFieldW, FIELD_H, Component.literal("Name")).also {
            it.setMaxLength(32)
            it.setHint(Component.literal("Widget name").withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialName
        }
        addRenderableWidget(nameField)

        holdField = EditBox(font, configFieldX, topPos + CONFIG_AREA_Y + 2, 48, FIELD_H, Component.literal("Hold")).also {
            it.setMaxLength(4)
            it.setHint(Component.literal(WidgetBlockEntity.MIN_HOLD_TICKS.toString()).withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialButtonHold.toString()
        }
        addRenderableWidget(holdField)

        val third = (configFieldW - 8) / 3
        minField = EditBox(font, configFieldX, topPos + CONFIG_AREA_Y + 2, third, FIELD_H, Component.literal("Min")).also {
            it.setMaxLength(12)
            it.value = trimFloat(menu.initialSliderMin)
        }
        maxField = EditBox(font, configFieldX + third + 4, topPos + CONFIG_AREA_Y + 2, third, FIELD_H, Component.literal("Max")).also {
            it.setMaxLength(12)
            it.value = trimFloat(menu.initialSliderMax)
        }
        stepField = EditBox(font, configFieldX + (third + 4) * 2, topPos + CONFIG_AREA_Y + 2, third, FIELD_H, Component.literal("Step")).also {
            it.setMaxLength(12)
            it.value = trimFloat(menu.initialSliderStep)
        }
        addRenderableWidget(minField)
        addRenderableWidget(maxField)
        addRenderableWidget(stepField)

        val initialChannel = runCatching { DyeColor.byId(menu.channelId) }.getOrDefault(DyeColor.WHITE)
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(channelPickerX, settingsRowY, initialChannel) { color ->
            if (color != null) sendUpdate("channel", color.id, "")
        }
        addRenderableWidget(picker!!)

        applyFieldVisibility()
    }

    /** Show only the config widgets for the current type, building / tearing
     *  down the dynamic radio option fields as the type changes. */
    private fun applyFieldVisibility() {
        val type = currentType
        holdField.visible = type == WidgetType.BUTTON
        val slider = type == WidgetType.SLIDER
        minField.visible = slider
        maxField.visible = slider
        stepField.visible = slider

        val radio = type == WidgetType.RADIO
        if (radio && !optionsBuilt) {
            rebuildOptionFields()
            optionsBuilt = true
        } else if (!radio && optionsBuilt) {
            clearOptionFields()
            optionsBuilt = false
        }
    }

    // ---- Radio option list ----

    private fun rebuildOptionFields() {
        for (f in optionFields) removeWidget(f)
        optionFields.clear()
        val fieldW = optListW - OPT_DELETE_W - 4
        for (visibleIdx in 0 until OPT_VISIBLE_ROWS) {
            val optIdx = optionScroll + visibleIdx
            if (optIdx >= localOptions.size) break
            val rowY = optInteriorY + visibleIdx * OPT_ROW_H + (OPT_ROW_H - FIELD_H) / 2
            val box = EditBox(font, optInteriorX + 1, rowY, fieldW, FIELD_H, Component.literal("Option"))
            box.setMaxLength(32)
            box.value = localOptions[optIdx]
            optionFields.add(box)
            addRenderableWidget(box)
        }
    }

    private fun clearOptionFields() {
        for (f in optionFields) removeWidget(f)
        optionFields.clear()
    }

    private fun commitOptionField(visibleIdx: Int) {
        val optIdx = optionScroll + visibleIdx
        if (optIdx !in localOptions.indices) return
        val field = optionFields.getOrNull(visibleIdx) ?: return
        if (field.value == localOptions[optIdx]) return
        localOptions[optIdx] = field.value
        sendOptions()
    }

    private fun commitAllOptionFields() {
        var changed = false
        for (visibleIdx in optionFields.indices) {
            val optIdx = optionScroll + visibleIdx
            if (optIdx !in localOptions.indices) continue
            val field = optionFields[visibleIdx]
            if (field.value != localOptions[optIdx]) {
                localOptions[optIdx] = field.value
                changed = true
            }
        }
        if (changed) sendOptions()
    }

    private fun sendOptions() {
        sendUpdate("options", 0, localOptions.joinToString("\n"))
    }

    private fun addOption() {
        if (localOptions.size >= MAX_OPTIONS) return
        commitAllOptionFields()
        localOptions.add("")
        if (localOptions.size > optionScroll + OPT_VISIBLE_ROWS) {
            optionScroll = localOptions.size - OPT_VISIBLE_ROWS
        }
        rebuildOptionFields()
        sendOptions()
    }

    private fun deleteOption(optIdx: Int) {
        if (optIdx !in localOptions.indices) return
        commitAllOptionFields()
        localOptions.removeAt(optIdx)
        val maxScroll = (localOptions.size - OPT_VISIBLE_ROWS).coerceAtLeast(0)
        if (optionScroll > maxScroll) optionScroll = maxScroll
        rebuildOptionFields()
        sendOptions()
    }

    // ---- Rendering ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        val serverChannel = menu.channelId
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }

        applyFieldVisibility()

        drawSetButton(graphics, nameSetBtnX, topPos + NAME_ROW_Y, mouseX, mouseY)

        graphics.drawString(font, "Type:", leftPos + OUTER_PAD + 2, topPos + TYPE_LABEL_Y, 0xFFAAAAAA.toInt())
        for (i in 0 until 4) {
            drawTypeButton(graphics, typeRowFirstX + i * (TYPE_BTN_W + TYPE_BTN_GAP), topPos + TYPE_ROW_Y, i, mouseX, mouseY)
        }

        when (currentType) {
            WidgetType.BUTTON -> {
                graphics.drawString(font, "Hold length (ticks):", leftPos + OUTER_PAD + 2, topPos + CONFIG_LABEL_Y, 0xFFAAAAAA.toInt())
                drawSetButton(graphics, configSetBtnX, topPos + CONFIG_AREA_Y, mouseX, mouseY)
                graphics.drawString(
                    font, "min ${WidgetBlockEntity.MIN_HOLD_TICKS}",
                    holdField.x + holdField.width + 6, topPos + CONFIG_AREA_Y + 4, 0xFF777777.toInt(),
                )
            }
            WidgetType.SWITCH -> {
                graphics.drawString(
                    font, "No extra configuration.",
                    leftPos + OUTER_PAD + 2, topPos + CONFIG_LABEL_Y, 0xFF777777.toInt(),
                )
            }
            WidgetType.RADIO -> {
                graphics.drawString(font, "Options:", leftPos + OUTER_PAD + 2, topPos + CONFIG_LABEL_Y, 0xFFAAAAAA.toInt())
                renderOptionPanel(graphics, mouseX, mouseY)
            }
            WidgetType.SLIDER -> {
                graphics.drawString(font, "Min / Max / Step:", leftPos + OUTER_PAD + 2, topPos + CONFIG_LABEL_Y, 0xFFAAAAAA.toInt())
                drawSetButton(graphics, configSetBtnX, topPos + CONFIG_AREA_Y, mouseX, mouseY)
            }
        }

        NineSlice.WINDOW_RECESSED.draw(graphics, settingsPanelX, topPos + SETTINGS_PANEL_Y, settingsPanelW, SETTINGS_PANEL_H)
        val labelY = settingsRowY + (ICON_BTN_SIZE - 8) / 2 + 1
        graphics.drawString(font, "Channel:", channelLabelX, labelY, 0xFFAAAAAA.toInt())
    }

    private fun renderOptionPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        NineSlice.PANEL_INSET.draw(graphics, optPanelX, optPanelY, optPanelW, OPT_PANEL_H)

        for (visibleIdx in 0 until OPT_VISIBLE_ROWS) {
            val optIdx = optionScroll + visibleIdx
            if (optIdx >= localOptions.size) break
            val rowY = optInteriorY + visibleIdx * OPT_ROW_H
            // Per-row delete button.
            val delX = optInteriorX + optListW + SCROLL_BAR_GAP - OPT_DELETE_W - SCROLL_BAR_GAP
            val delY = rowY + (OPT_ROW_H - OPT_DELETE_W) / 2
            val delHover = mouseX in delX until delX + OPT_DELETE_W && mouseY in delY until delY + OPT_DELETE_W
            (if (delHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, delX, delY, OPT_DELETE_W, OPT_DELETE_W)
            graphics.drawString(font, "x", delX + (OPT_DELETE_W - font.width("x")) / 2 + 1, delY + 2, 0xFFFFFFFF.toInt())
        }

        if (localOptions.isEmpty()) {
            graphics.drawString(
                font, "No options",
                optInteriorX + (optListW - font.width("No options")) / 2,
                optInteriorY + (OPT_PANEL_H - OPT_PANEL_INNER_PAD * 2 - 8) / 2,
                0xFF777777.toInt(),
            )
        }

        // Scrollbar.
        val trackH = OPT_PANEL_H - OPT_PANEL_INNER_PAD * 2
        NineSlice.SCROLLBAR_TRACK.draw(graphics, optScrollbarX, optInteriorY, SCROLL_BAR_W, trackH)
        if (localOptions.size > OPT_VISIBLE_ROWS) {
            val thumbH = maxOf(12, trackH * OPT_VISIBLE_ROWS / localOptions.size)
            val maxScroll = (localOptions.size - OPT_VISIBLE_ROWS).coerceAtLeast(1)
            val thumbY = optInteriorY + (trackH - thumbH) * optionScroll / maxScroll
            val hovered = mouseX in optScrollbarX until optScrollbarX + SCROLL_BAR_W &&
                mouseY in optInteriorY until optInteriorY + trackH
            (if (hovered || draggingScrollbar) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB)
                .draw(graphics, optScrollbarX, thumbY, SCROLL_BAR_W, thumbH)
        }

        // Add button.
        val addHover = mouseX in addBtnX until addBtnX + ADD_BTN_W && mouseY in addBtnY until addBtnY + ADD_BTN_H
        val addEnabled = localOptions.size < MAX_OPTIONS
        val addSlice = when {
            !addEnabled -> NineSlice.BUTTON
            addHover -> NineSlice.BUTTON_HOVER
            else -> NineSlice.BUTTON
        }
        addSlice.draw(graphics, addBtnX, addBtnY, ADD_BTN_W, ADD_BTN_H)
        val addText = "+ Add option"
        val addColor = if (addEnabled) 0xFFFFFFFF.toInt() else 0xFF777777.toInt()
        graphics.drawString(font, addText, addBtnX + (ADD_BTN_W - font.width(addText)) / 2, addBtnY + 2, addColor)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
    }

    private fun drawSetButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in bx until bx + SET_BTN_W && mouseY in by until by + SET_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, bx, by, SET_BTN_W, SET_BTN_H)
        val textColor = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, "Set", bx + (SET_BTN_W - font.width("Set")) / 2, by + 5, textColor)
    }

    private fun drawTypeButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, idx: Int, mouseX: Int, mouseY: Int) {
        val selected = menu.widgetType == idx
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

    // ---- Input ----

    override fun keyPressed(event: KeyEvent): Boolean {
        // Escape always falls through so the GUI can close.
        if (event.keyCode == 256) return super.keyPressed(event)
        val enter = event.keyCode == 257 || event.keyCode == 335

        // Radio option rows.
        for ((idx, field) in optionFields.withIndex()) {
            if (!field.isFocused) continue
            if (enter) {
                commitOptionField(idx)
                if (focused === field) setFocused(null) else field.isFocused = false
                return true
            }
            field.keyPressed(event)
            return true
        }

        // Fixed fields, consume every key so 'e' types instead of closing the GUI.
        for (field in listOf(nameField, holdField, minField, maxField, stepField)) {
            if (!field.visible || !field.isFocused) continue
            if (enter) {
                commitField(field)
                if (focused === field) setFocused(null) else field.isFocused = false
                return true
            }
            field.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (picker?.expanded == true && picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // Name Set.
        val nameSetY = topPos + NAME_ROW_Y
        if (mx in nameSetBtnX until nameSetBtnX + SET_BTN_W && my in nameSetY until nameSetY + SET_BTN_H) {
            commitName()
            playClickSound()
            clearFixedFieldFocus()
            return true
        }

        // Type buttons.
        val typeY = topPos + TYPE_ROW_Y
        for (i in 0 until 4) {
            val bx = typeRowFirstX + i * (TYPE_BTN_W + TYPE_BTN_GAP)
            if (mx in bx until bx + TYPE_BTN_W && my in typeY until typeY + TYPE_BTN_H) {
                sendUpdate("type", i, "")
                playClickSound()
                clearFixedFieldFocus()
                return true
            }
        }

        // Type-specific config interactions.
        when (currentType) {
            WidgetType.BUTTON -> {
                val setY = topPos + CONFIG_AREA_Y
                if (mx in configSetBtnX until configSetBtnX + SET_BTN_W && my in setY until setY + SET_BTN_H) {
                    commitHold()
                    playClickSound()
                    clearFixedFieldFocus()
                    return true
                }
            }
            WidgetType.SLIDER -> {
                val setY = topPos + CONFIG_AREA_Y
                if (mx in configSetBtnX until configSetBtnX + SET_BTN_W && my in setY until setY + SET_BTN_H) {
                    commitSlider()
                    playClickSound()
                    clearFixedFieldFocus()
                    return true
                }
            }
            WidgetType.RADIO -> {
                if (handleOptionPanelClick(mx, my)) return true
            }
            WidgetType.SWITCH -> {}
        }

        val handled = super.mouseClicked(event, doubleClick)

        // Commit + defocus any field the click landed outside of.
        for (field in listOf(nameField, holdField, minField, maxField, stepField)) {
            if (field.visible) defocusIfClickedOutside(field, mx, my)
        }
        for ((idx, field) in optionFields.withIndex()) {
            if (!field.isFocused) continue
            val inside = mx in field.x until field.x + field.width && my in field.y until field.y + field.height
            if (!inside) {
                commitOptionField(idx)
                if (focused === field) setFocused(null) else field.isFocused = false
            }
        }
        return handled
    }

    /** Returns true when the click hit a radio-panel control (delete / add /
     *  scrollbar). Field clicks fall through to `super.mouseClicked`. */
    private fun handleOptionPanelClick(mx: Int, my: Int): Boolean {
        // Delete buttons.
        for (visibleIdx in 0 until OPT_VISIBLE_ROWS) {
            val optIdx = optionScroll + visibleIdx
            if (optIdx >= localOptions.size) break
            val rowY = optInteriorY + visibleIdx * OPT_ROW_H
            val delX = optInteriorX + optListW + SCROLL_BAR_GAP - OPT_DELETE_W - SCROLL_BAR_GAP
            val delY = rowY + (OPT_ROW_H - OPT_DELETE_W) / 2
            if (mx in delX until delX + OPT_DELETE_W && my in delY until delY + OPT_DELETE_W) {
                playClickSound()
                deleteOption(optIdx)
                return true
            }
        }
        // Add button.
        if (mx in addBtnX until addBtnX + ADD_BTN_W && my in addBtnY until addBtnY + ADD_BTN_H) {
            if (localOptions.size < MAX_OPTIONS) {
                playClickSound()
                addOption()
            }
            return true
        }
        // Scrollbar drag start.
        val trackH = OPT_PANEL_H - OPT_PANEL_INNER_PAD * 2
        if (localOptions.size > OPT_VISIBLE_ROWS &&
            mx in optScrollbarX until optScrollbarX + SCROLL_BAR_W &&
            my in optInteriorY until optInteriorY + trackH
        ) {
            draggingScrollbar = true
            scrollTo((my - optInteriorY).toFloat() / trackH)
            return true
        }
        return false
    }

    private fun scrollTo(fraction: Float) {
        val maxScroll = (localOptions.size - OPT_VISIBLE_ROWS).coerceAtLeast(0)
        val newOffset = (fraction.coerceIn(0f, 1f) * maxScroll).toInt().coerceIn(0, maxScroll)
        if (newOffset != optionScroll) {
            commitAllOptionFields()
            optionScroll = newOffset
            rebuildOptionFields()
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar && localOptions.size > OPT_VISIBLE_ROWS) {
            val trackH = OPT_PANEL_H - OPT_PANEL_INNER_PAD * 2
            scrollTo((event.mouseY.toInt() - optInteriorY).toFloat() / trackH)
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        draggingScrollbar = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        if (currentType == WidgetType.RADIO && localOptions.size > OPT_VISIBLE_ROWS &&
            mouseX.toInt() in optPanelX until optPanelX + optPanelW &&
            mouseY.toInt() in optPanelY until optPanelY + OPT_PANEL_H
        ) {
            val maxScroll = localOptions.size - OPT_VISIBLE_ROWS
            val newOffset = (optionScroll + if (deltaY > 0) -1 else 1).coerceIn(0, maxScroll)
            if (newOffset != optionScroll) {
                commitAllOptionFields()
                optionScroll = newOffset
                rebuildOptionFields()
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
    }

    override fun removed() {
        commitName()
        commitHold()
        commitSlider()
        commitAllOptionFields()
        super.removed()
    }

    // ---- Commits ----

    private fun defocusIfClickedOutside(field: EditBox, mx: Int, my: Int) {
        if (!field.isFocused) return
        val inside = mx in field.x until field.x + field.width && my in field.y until field.y + field.height
        if (inside) return
        commitField(field)
        if (focused === field) setFocused(null) else field.isFocused = false
    }

    private fun commitField(field: EditBox) {
        when (field) {
            nameField -> commitName()
            holdField -> commitHold()
            minField, maxField, stepField -> commitSlider()
        }
    }

    private fun commitName() {
        if (nameField.value == lastSentName) return
        sendUpdate("name", 0, nameField.value)
        lastSentName = nameField.value
    }

    private fun commitHold() {
        val ticks = (holdField.value.toIntOrNull() ?: WidgetBlockEntity.MIN_HOLD_TICKS)
            .coerceIn(WidgetBlockEntity.MIN_HOLD_TICKS, WidgetBlockEntity.MAX_HOLD_TICKS)
        holdField.value = ticks.toString()
        if (holdField.value == lastSentHold) return
        sendUpdate("hold", ticks, "")
        lastSentHold = holdField.value
    }

    private fun commitSlider() {
        val s = sliderString(
            minField.value.toFloatOrNull() ?: 0f,
            maxField.value.toFloatOrNull() ?: 10f,
            stepField.value.toFloatOrNull() ?: 1f,
        )
        if (s == lastSentSlider) return
        sendUpdate("slider", 0, s)
        lastSentSlider = s
    }

    private fun clearFixedFieldFocus() {
        nameField.isFocused = false
        holdField.isFocused = false
        minField.isFocused = false
        maxField.isFocused = false
        stepField.isFocused = false
        for (f in optionFields) f.isFocused = false
        setFocused(null)
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f,
            )
        )
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(menu.widgetPos, key, intValue, strValue)
        )
    }

    private fun sliderString(min: Float, max: Float, step: Float): String =
        "${trimFloat(min)},${trimFloat(max)},${trimFloat(step)}"

    private fun trimFloat(f: Float): String =
        if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
}
