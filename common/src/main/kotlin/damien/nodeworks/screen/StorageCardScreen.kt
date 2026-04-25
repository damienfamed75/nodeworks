package damien.nodeworks.screen

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
import damien.nodeworks.screen.widget.ChannelPickerWidget
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

class StorageCardScreen(
    menu: StorageCardMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<StorageCardMenu>(menu, playerInventory, title, W, H) {

    companion object {
        private const val W = 140
        // H expanded from 50 → 70 to host the channel picker row below the priority
        // stepper. Inset height grows by the same delta so both controls share one
        // recessed pane rather than splitting into two.
        private const val H = 70
        private const val INSET_X = 4
        private const val INSET_Y = 19
        private const val INSET_W = W - 8
        private const val INSET_H = H - 24

        // Stepper layout — [-] [entry] [+] with 2 px gaps, matching ProcessingSetScreen.
        private const val STEPPER_BTN_SIZE = 14
        private const val STEPPER_GAP = 2
        private const val PRIORITY_FIELD_W = 26
        private const val LABEL_TO_BTN_GAP = 4      // gap between "Priority:" and the [-] button
        private const val STEPPER_Y_OFFSET = 7      // y relative to INSET_Y for buttons
        private const val FIELD_Y_OFFSET = 8        // y relative to INSET_Y for the entry
        // Channel row sits 20px below the priority row (one widget height + breathing room).
        private const val CHANNEL_Y_OFFSET = STEPPER_Y_OFFSET + STEPPER_BTN_SIZE + 6
        private const val CHANNEL_LABEL_TO_PICKER_GAP = 4

        private const val LABEL_TEXT = "Priority:"
        private const val CHANNEL_LABEL_TEXT = "Channel:"
    }

    private var priorityField: EditBox? = null
    /** Tracks last known server value to detect external changes. */
    private var lastSyncedPriority = -1
    private var lastSyncedChannel = -1
    private var picker: ChannelPickerWidget? = null

    // X offsets relative to leftPos, computed once in init() so the label-text width
    // (which depends on the font) can drive layout. The whole Label + [-] + entry + [+]
    // row is centered horizontally in the frame.
    private var labelX = 0
    private var minusX = 0
    private var fieldX = 0
    private var plusX = 0
    private var channelLabelX = 0
    private var pickerX = 0

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()

        // Center the whole "Priority: [-] [entry] [+]" row horizontally in the frame.
        val labelW = font.width(LABEL_TEXT)
        val rowW = labelW + LABEL_TO_BTN_GAP + STEPPER_BTN_SIZE + STEPPER_GAP +
            PRIORITY_FIELD_W + STEPPER_GAP + STEPPER_BTN_SIZE
        val rowStart = (W - rowW) / 2
        labelX = rowStart
        minusX = labelX + labelW + LABEL_TO_BTN_GAP
        fieldX = minusX + STEPPER_BTN_SIZE + STEPPER_GAP
        plusX = fieldX + PRIORITY_FIELD_W + STEPPER_GAP

        val fieldY = topPos + INSET_Y + FIELD_Y_OFFSET
        priorityField = EditBox(font, leftPos + fieldX, fieldY, PRIORITY_FIELD_W, 12, Component.literal("Priority"))
        priorityField!!.setMaxLength(3)
        priorityField!!.value = "${menu.getPriority()}"
        lastSyncedPriority = menu.getPriority()
        addRenderableWidget(priorityField!!)

        // Channel row — Channel: [swatch], horizontally centered like the priority row
        // above. Picker click sends the dye ordinal as a clickMenuButton id offset by
        // 2000 so it never collides with the priority decrement/increment ids.
        val channelLabelW = font.width(CHANNEL_LABEL_TEXT)
        val channelRowW = channelLabelW + CHANNEL_LABEL_TO_PICKER_GAP + ChannelPickerWidget.SWATCH
        val channelRowStart = (W - channelRowW) / 2
        channelLabelX = channelRowStart
        pickerX = channelLabelX + channelLabelW + CHANNEL_LABEL_TO_PICKER_GAP
        val pickerY = topPos + INSET_Y + CHANNEL_Y_OFFSET
        val initialChannel = menu.getChannel()
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(leftPos + pickerX, pickerY, initialChannel) { color ->
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 2000 + color.id)
        }
        addRenderableWidget(picker!!)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val codePoint = event.character
        val modifiers = 0
        if (priorityField?.isFocused == true) {
            if (codePoint.isDigit()) {
                return priorityField?.charTyped(event) ?: false
            }
            return true // consume non-digits
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
        if (priorityField?.isFocused == true) {
            // Allow typing keys to pass through to the field
            if (keyCode == 256) { // Escape
                priorityField!!.isFocused = false
                return true
            }
            if (keyCode == 257 || keyCode == 335) { // Enter/KP_Enter
                commitFieldValue()
                priorityField!!.isFocused = false
                return true
            }
            return priorityField!!.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    private fun commitFieldValue() {
        val value = priorityField?.value?.toIntOrNull()?.coerceIn(0, 999) ?: 0
        priorityField?.value = "$value"
        // Use clickMenuButton to set exact value: id 100+ encodes the value directly
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 100 + value)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Flat frame (no title bar)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Title text
        graphics.drawString(font, "Storage Card", leftPos + 6, topPos + 7, 0xFFFFFFFF.toInt())

        // Recessed inset for priority area
        NineSlice.WINDOW_RECESSED.draw(graphics, leftPos + INSET_X, topPos + INSET_Y, INSET_W, INSET_H)

        // "Priority:" label — leftmost element in the centered row.
        graphics.drawString(font, LABEL_TEXT, leftPos + labelX, topPos + INSET_Y + 9, 0xFFAAAAAA.toInt())

        // Stepper buttons — [-] left of the entry, [+] right of the entry.
        val stepY = topPos + INSET_Y + STEPPER_Y_OFFSET
        val mX = leftPos + minusX
        val pX = leftPos + plusX
        val btn = STEPPER_BTN_SIZE
        val minusHover = mouseX >= mX && mouseX < mX + btn && mouseY >= stepY && mouseY < stepY + btn
        val plusHover = mouseX >= pX && mouseX < pX + btn && mouseY >= stepY && mouseY < stepY + btn
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, mX, stepY, btn, btn)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, pX, stepY, btn, btn)
        graphics.drawString(font, "-", mX + (btn - font.width("-")) / 2, stepY + 3, 0xFFFFFFFF.toInt())
        graphics.drawString(font, "+", pX + (btn - font.width("+")) / 2, stepY + 3, 0xFFFFFFFF.toInt())

        // "Channel:" label vertically centered against the picker swatch.
        val channelRowY = topPos + INSET_Y + CHANNEL_Y_OFFSET
        graphics.drawString(
            font, CHANNEL_LABEL_TEXT,
            leftPos + channelLabelX,
            channelRowY + (ChannelPickerWidget.SWATCH - font.lineHeight) / 2 + 1,
            0xFFAAAAAA.toInt(),
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Sync field if server value changed (e.g. from stepper button round-trip)
        val serverVal = menu.getPriority()
        if (serverVal != lastSyncedPriority && priorityField?.isFocused != true) {
            priorityField?.value = "$serverVal"
            lastSyncedPriority = serverVal
        }
        val serverChannel = menu.channelData.get(0)
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // Render popup overlay last so it layers above buttons/labels.
        picker?.renderOverlay(graphics, mouseX, mouseY)
        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Channel popup gets priority while open so its grid clicks aren't stolen
        // by the priority steppers underneath.
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (button == 0) {
            val stepY = topPos + INSET_Y + STEPPER_Y_OFFSET
            val mX = leftPos + minusX
            val pX = leftPos + plusX
            val btnW = STEPPER_BTN_SIZE
            val btnH = STEPPER_BTN_SIZE
            val mx = mouseX.toInt()
            val my = mouseY.toInt()

            if (mx >= mX && mx < mX + btnW && my >= stepY && my < stepY + btnH) {
                val step = if (hasShiftDownCompat()) 10 else 1
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0)
                if (step > 1) {
                    // For shift-click, send multiple decrements
                    repeat(step - 1) {
                        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0)
                    }
                }
                return true
            }
            // Bug fix: `plusX` is the local x relative to leftPos — must add leftPos
            //  to match the actual button draw position (cf. `pX` above).
            if (mx >= pX && mx < pX + btnW && my >= stepY && my < stepY + btnH) {
                val step = if (hasShiftDownCompat()) 10 else 1
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1)
                if (step > 1) {
                    repeat(step - 1) {
                        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1)
                    }
                }
                return true
            }

            // Commit field when clicking outside it
            if (priorityField?.isFocused == true) {
                commitFieldValue()
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun removed() {
        // Commit any pending field value on close
        commitFieldValue()
        super.removed()
    }
}
