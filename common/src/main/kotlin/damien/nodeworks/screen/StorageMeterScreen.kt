package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.JobStatusIndicator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/**
 * Settings screen for the Storage Meter. Ghost slot + threshold stepper +
 * channel picker on one row, status indicator below, Autocraft toggle on the
 * bottom row.
 */
class StorageMeterScreen(
    menu: StorageMeterMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<StorageMeterMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        // Inventory panel constants are declared first so IMAGE_H can use them.

        private const val UPPER_PANEL_H = 78
        private const val PANEL_GAP = 2

        const val INV_PANEL_Y = UPPER_PANEL_H + PANEL_GAP
        private const val INV_PANEL_H = 94
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        const val INV_GRID_Y = INV_PANEL_Y + 14
        const val INV_X = 8
        const val HOTBAR_GAP = 4

        // Slot positions (1 px inset from the frame). Shared with the menu.
        const val INV_MAIN_SLOT_X = INV_X + 1
        const val INV_MAIN_SLOT_Y = INV_GRID_Y + 1
        const val INV_HOTBAR_SLOT_Y = INV_MAIN_SLOT_Y + 3 * 18 + HOTBAR_GAP

        private const val IMAGE_W = 176
        private const val IMAGE_H = INV_PANEL_Y + INV_PANEL_H

        private const val ICON_SIZE = 16
        private const val STEP_BTN_W = 16
        private const val STEP_BTN_H = 14
        private const val FIELD_W = 40
        private const val FIELD_H = 14
        private const val GAP = 8
        private const val INNER_GAP = 2

        // One centered row: slot, stepper, channel picker.
        private const val PICKER_SIZE = 16
        private const val PAIR_W = ICON_SIZE + GAP + STEP_BTN_W + INNER_GAP + FIELD_W + INNER_GAP + STEP_BTN_W + GAP + PICKER_SIZE
        private const val PAIR_X = (IMAGE_W - PAIR_W) / 2

        const val SLOT_X = PAIR_X
        const val SLOT_Y = 24

        private const val MINUS_BTN_X = PAIR_X + ICON_SIZE + GAP
        private const val FIELD_X = MINUS_BTN_X + STEP_BTN_W + INNER_GAP
        private const val PLUS_BTN_X = FIELD_X + FIELD_W + INNER_GAP
        private const val PICKER_X = PLUS_BTN_X + STEP_BTN_W + GAP
        private const val STEPPER_Y = SLOT_Y + 1

        private const val LABEL_Y = 8
        private const val LABEL = "Target Amount in Network"

        // Status indicator row beneath the slot, left-aligned to it.
        private const val JOB_LABEL_X = SLOT_X
        private const val JOB_LABEL_Y = SLOT_Y + ICON_SIZE + 4
        private const val JOB_LABEL_MAX_W = IMAGE_W - JOB_LABEL_X - 8

        // Autocraft toggle, 48×16 NineSlice like the Controller's chunk-load.
        private const val AUTOCRAFT_LABEL = "Autocraft"
        private const val AUTOCRAFT_BTN_W = 48
        private const val AUTOCRAFT_BTN_H = 16
        private const val AUTOCRAFT_ROW_Y = 58
        private const val AUTOCRAFT_LABEL_GAP = 6
    }

    fun getLeft(): Int = leftPos
    fun getTop(): Int = topPos

    private lateinit var amountField: EditBox
    private var jobIndicator: JobStatusIndicator? = null
    private var channelPicker: ChannelPickerWidget? = null
    private var lastSyncedChannel: Int = Int.MIN_VALUE

    // Threshold value at GUI-open. onClose compares against this to skip
    // a no-op send.
    private var lastSentValue: Int = 0

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        lastSentValue = menu.threshold
        amountField = EditBox(
            font,
            leftPos + FIELD_X, topPos + STEPPER_Y,
            FIELD_W, FIELD_H,
            Component.literal("Amount"),
        ).also {
            it.setMaxLength(7)
            it.setBordered(true)
            it.value = menu.threshold.toString()
        }
        addRenderableWidget(amountField)

        jobIndicator = JobStatusIndicator(
            leftPos + JOB_LABEL_X, topPos + JOB_LABEL_Y, JOB_LABEL_MAX_W,
        )

        // Drives both the stock count and the craft delivery channel.
        val initialFilter = damien.nodeworks.network.ChannelFilter.fromNbtInt(menu.channelId)
        lastSyncedChannel = menu.channelId
        val initialColor = (initialFilter as? damien.nodeworks.network.ChannelFilter.Color)?.color ?: DyeColor.WHITE
        val initialIsNone = initialFilter is damien.nodeworks.network.ChannelFilter.All
        channelPicker = ChannelPickerWidget(
            leftPos + PICKER_X, topPos + SLOT_Y,
            initialColor,
            canBeNone = true,
            initialIsNone = initialIsNone,
        ) { color ->
            val filter = if (color == null) damien.nodeworks.network.ChannelFilter.All
                         else damien.nodeworks.network.ChannelFilter.Color(color)
            val nbtInt = filter.toNbtInt()
            if (nbtInt != lastSyncedChannel) {
                lastSyncedChannel = nbtInt
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.DeviceSettingsPayload(
                        menu.devicePos, "channel", nbtInt, "",
                    ),
                )
            }
        }
        addRenderableWidget(channelPicker!!)
    }

    override fun containerTick() {
        super.containerTick()
        // Threshold flushes from onClose so local edits don't switch the
        // meter's state mid-edit.
        val be = Minecraft.getInstance().level?.getBlockEntity(menu.devicePos)
            as? damien.nodeworks.block.entity.StorageMeterBlockEntity
        jobIndicator?.setConnected(be?.networkId != null)
        jobIndicator?.setActive(be?.hasActiveJob == true)
        jobIndicator?.setErrors(be?.errorLines ?: emptyList())
        jobIndicator?.setEmittingSignal(be?.isBelowThreshold == true)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        channelPicker?.renderOverlay(graphics, mouseX, mouseY)
        jobIndicator?.renderTooltip(graphics, font, mouseX, mouseY)
    }

    private fun sendThreshold(value: Int) {
        lastSentValue = value
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(
                menu.devicePos, "threshold", value, "",
            ),
        )
    }

    private fun sendAutocraftToggle(enabled: Boolean) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(
                menu.devicePos, "autocraft", if (enabled) 1 else 0, "",
            ),
        )
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Upper panel: label + slot + stepper.
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, IMAGE_W, UPPER_PANEL_H)
        NineSlice.SLOT.draw(graphics, leftPos + SLOT_X - 1, topPos + SLOT_Y - 1, ICON_SIZE + 2, ICON_SIZE + 2)

        // Lower panel: player inventory + hotbar.
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos + INV_PANEL_Y, IMAGE_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_LABEL_Y, 0xFFAAAAAA.toInt())
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_GRID_Y, HOTBAR_GAP)

        // Centered title above the slot row.
        val labelW = font.width(LABEL)
        val labelX = leftPos + (IMAGE_W - labelW) / 2
        graphics.drawString(font, LABEL, labelX, topPos + LABEL_Y, 0xFFCCCCCC.toInt())

        val minusHover = mouseX in (leftPos + MINUS_BTN_X) until (leftPos + MINUS_BTN_X + STEP_BTN_W) &&
            mouseY in (topPos + STEPPER_Y) until (topPos + STEPPER_Y + STEP_BTN_H)
        val plusHover = mouseX in (leftPos + PLUS_BTN_X) until (leftPos + PLUS_BTN_X + STEP_BTN_W) &&
            mouseY in (topPos + STEPPER_Y) until (topPos + STEPPER_Y + STEP_BTN_H)
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, leftPos + MINUS_BTN_X, topPos + STEPPER_Y, STEP_BTN_W, STEP_BTN_H)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, leftPos + PLUS_BTN_X, topPos + STEPPER_Y, STEP_BTN_W, STEP_BTN_H)
        graphics.drawString(
            font, "-",
            leftPos + MINUS_BTN_X + (STEP_BTN_W - font.width("-")) / 2,
            topPos + STEPPER_Y + 3,
            if (minusHover) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt(),
        )
        graphics.drawString(
            font, "+",
            leftPos + PLUS_BTN_X + (STEP_BTN_W - font.width("+")) / 2,
            topPos + STEPPER_Y + 3,
            if (plusHover) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt(),
        )

        jobIndicator?.render(graphics, font)

        // Autocraft label + 48×16 toggle, centered on the row.
        val autocraftLabelW = font.width(AUTOCRAFT_LABEL)
        val rowW = autocraftLabelW + AUTOCRAFT_LABEL_GAP + AUTOCRAFT_BTN_W
        val rowX = leftPos + (IMAGE_W - rowW) / 2
        graphics.drawString(
            font, AUTOCRAFT_LABEL,
            rowX, topPos + AUTOCRAFT_ROW_Y + (AUTOCRAFT_BTN_H - 8) / 2,
            0xFFCCCCCC.toInt(),
        )
        val toggleSlice = if (menu.autocraftEnabled) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        toggleSlice.draw(
            graphics,
            rowX + autocraftLabelW + AUTOCRAFT_LABEL_GAP, topPos + AUTOCRAFT_ROW_Y,
            AUTOCRAFT_BTN_W, AUTOCRAFT_BTN_H,
        )
    }

    // Shared by draw + hit-test so they can't drift apart.
    private fun autocraftToggleX(): Int {
        val labelW = font.width(AUTOCRAFT_LABEL)
        val rowW = labelW + AUTOCRAFT_LABEL_GAP + AUTOCRAFT_BTN_W
        return leftPos + (IMAGE_W - rowW) / 2 + labelW + AUTOCRAFT_LABEL_GAP
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Picker popup swallows clicks before they reach widgets underneath.
        if (channelPicker?.expanded == true && channelPicker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true

        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        val step = if (hasShiftDownCompat()) 10 else 1
        if (mx in (leftPos + MINUS_BTN_X) until (leftPos + MINUS_BTN_X + STEP_BTN_W) &&
            my in (topPos + STEPPER_Y) until (topPos + STEPPER_Y + STEP_BTN_H)
        ) {
            adjustAmount(-step)
            return true
        }
        if (mx in (leftPos + PLUS_BTN_X) until (leftPos + PLUS_BTN_X + STEP_BTN_W) &&
            my in (topPos + STEPPER_Y) until (topPos + STEPPER_Y + STEP_BTN_H)
        ) {
            adjustAmount(step)
            return true
        }
        val toggleX = autocraftToggleX()
        if (mx in toggleX until toggleX + AUTOCRAFT_BTN_W &&
            my in (topPos + AUTOCRAFT_ROW_Y) until (topPos + AUTOCRAFT_ROW_Y + AUTOCRAFT_BTN_H)
        ) {
            sendAutocraftToggle(!menu.autocraftEnabled)
            Minecraft.getInstance().soundManager.play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f),
            )
            return true
        }
        val result = super.mouseClicked(event, doubleClick)
        // Defocus the field if the click landed outside it.
        if (amountField.isFocused) {
            val fieldX = leftPos + FIELD_X
            val fieldY = topPos + STEPPER_Y
            val inField = mx in fieldX until fieldX + FIELD_W &&
                my in fieldY until fieldY + FIELD_H
            if (!inField) setFocused(null)
        }
        return result
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (amountField.isFocused && (event.keyCode == InputConstants.KEY_RETURN ||
            event.keyCode == InputConstants.KEY_NUMPADENTER)
        ) {
            setFocused(null)
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        // Single threshold send on close, coerced and change-guarded.
        val parsed = amountField.value.toIntOrNull()?.coerceIn(0, 1_000_000)
        if (parsed != null && parsed != lastSentValue) {
            sendThreshold(parsed)
        }
        super.onClose()
    }

    private fun adjustAmount(delta: Int) {
        val current = amountField.value.toIntOrNull() ?: 0
        val next = (current + delta).coerceIn(0, 1_000_000)
        amountField.value = next.toString()
        if (amountField.isFocused) setFocused(null)
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f),
        )
        // onClose sends the final value, stepper clicks stay local-only.
    }
}
