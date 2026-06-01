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
 * Settings screen for the Craft Requester. Ghost target slot, batch-size
 * stepper, output channel picker. Craft-planning failures appear in the
 * status indicator beneath the slot. Inventory below.
 */
class CraftRequesterScreen(
    menu: CraftRequesterMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<CraftRequesterMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        // ---- Inventory panel (same shape as Storage Meter) ----

        private const val UPPER_PANEL_H = 60
        private const val PANEL_GAP = 2

        const val INV_PANEL_Y = UPPER_PANEL_H + PANEL_GAP
        private const val INV_PANEL_H = 94
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        const val INV_GRID_Y = INV_PANEL_Y + 14
        const val INV_X = 8
        const val HOTBAR_GAP = 4
        const val INV_MAIN_SLOT_X = INV_X + 1
        const val INV_MAIN_SLOT_Y = INV_GRID_Y + 1
        const val INV_HOTBAR_SLOT_Y = INV_MAIN_SLOT_Y + 3 * 18 + HOTBAR_GAP

        // ---- Upper panel ----

        private const val IMAGE_W = 176
        private const val IMAGE_H = INV_PANEL_Y + INV_PANEL_H

        private const val ICON_SIZE = 16
        private const val STEP_BTN_W = 16
        private const val STEP_BTN_H = 14
        private const val FIELD_W = 40
        private const val FIELD_H = 14
        private const val SLOT_TO_STEPPER_GAP = 8
        private const val INNER_GAP = 2

        // Single row: slot + stepper + channel picker, all centered. Tooltip
        // on the picker carries the "Outputs go to" context so no label is
        // needed beside the swatch.
        private const val PICKER_SIZE = 16
        private const val PAIR_W = ICON_SIZE + SLOT_TO_STEPPER_GAP + STEP_BTN_W + INNER_GAP + FIELD_W + INNER_GAP + STEP_BTN_W + SLOT_TO_STEPPER_GAP + PICKER_SIZE
        private const val PAIR_X = (IMAGE_W - PAIR_W) / 2

        const val SLOT_X = PAIR_X
        const val SLOT_Y = 24

        private const val MINUS_BTN_X = PAIR_X + ICON_SIZE + SLOT_TO_STEPPER_GAP
        private const val FIELD_X = MINUS_BTN_X + STEP_BTN_W + INNER_GAP
        private const val PLUS_BTN_X = FIELD_X + FIELD_W + INNER_GAP
        private const val PICKER_X = PLUS_BTN_X + STEP_BTN_W + SLOT_TO_STEPPER_GAP
        private const val STEPPER_Y = SLOT_Y + 1

        private const val BATCH_LABEL_Y = 8
        private const val BATCH_LABEL = "Batch Size per Request"

        // JobStatusIndicator beneath the ghost slot, left-aligned to the slot,
        // extending to the right inset of the panel so error text has room.
        private const val JOB_LABEL_X = SLOT_X
        private const val JOB_LABEL_Y = SLOT_Y + ICON_SIZE + 4
        private const val JOB_LABEL_MAX_W = IMAGE_W - JOB_LABEL_X - 8

        // Throttle / sync constants, same model as the Storage Meter.
        private const val SEND_THROTTLE_TICKS = 4L
        private const val SYNC_DOWN_QUIET_TICKS = 40L
    }

    fun getLeft(): Int = leftPos
    fun getTop(): Int = topPos

    private lateinit var batchField: EditBox
    private var channelPicker: ChannelPickerWidget? = null
    private var jobIndicator: JobStatusIndicator? = null

    private var lastSentBatch: Int = 1
    private var lastSendTick: Long = Long.MIN_VALUE / 2L
    private var lastSyncedChannel: Int = Int.MIN_VALUE

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        lastSentBatch = menu.batchSize

        batchField = EditBox(
            font,
            leftPos + FIELD_X, topPos + STEPPER_Y,
            FIELD_W, FIELD_H,
            Component.literal("Batch"),
        ).also {
            it.setMaxLength(7)
            it.setBordered(true)
            it.value = menu.batchSize.toString()
        }
        addRenderableWidget(batchField)

        // Channel picker on the same row as the slot + stepper. The
        // "Outputs go to" tooltip prefix carries the context that the row
        // label used to provide.
        val initialFilter = damien.nodeworks.network.ChannelFilter.fromNbtInt(menu.channelId)
        lastSyncedChannel = menu.channelId
        val initialColor = (initialFilter as? damien.nodeworks.network.ChannelFilter.Color)?.color ?: DyeColor.WHITE
        val initialIsNone = initialFilter is damien.nodeworks.network.ChannelFilter.All
        channelPicker = ChannelPickerWidget(
            leftPos + PICKER_X, topPos + SLOT_Y,
            initialColor,
            canBeNone = true,
            initialIsNone = initialIsNone,
            tooltipPrefix = "Outputs go to",
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

        jobIndicator = JobStatusIndicator(
            leftPos + JOB_LABEL_X, topPos + JOB_LABEL_Y, JOB_LABEL_MAX_W,
        )
    }

    override fun containerTick() {
        super.containerTick()
        val now = Minecraft.getInstance().level?.gameTime ?: 0L
        val parsed = batchField.value.toIntOrNull()?.coerceIn(1, 1_000_000)
        val server = menu.batchSize

        if (parsed != null && !batchField.isFocused
            && parsed != lastSentBatch
            && now - lastSendTick >= SEND_THROTTLE_TICKS
        ) {
            sendBatch(parsed, now)
        }

        if (parsed != null && parsed == lastSentBatch && server != lastSentBatch
            && !batchField.isFocused
            && now - lastSendTick >= SYNC_DOWN_QUIET_TICKS
        ) {
            batchField.value = server.toString()
            lastSentBatch = server
        }

        // Mirror BE state into the status indicator.
        val be = Minecraft.getInstance().level?.getBlockEntity(menu.devicePos)
            as? damien.nodeworks.block.entity.CraftRequesterBlockEntity
        jobIndicator?.setConnected(be?.networkId != null)
        jobIndicator?.setActive(be?.hasActiveJob == true)
        jobIndicator?.setErrors(be?.errorLines ?: emptyList())
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, IMAGE_W, UPPER_PANEL_H)
        NineSlice.SLOT.draw(graphics, leftPos + SLOT_X - 1, topPos + SLOT_Y - 1, ICON_SIZE + 2, ICON_SIZE + 2)

        // Inventory panel
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos + INV_PANEL_Y, IMAGE_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_LABEL_Y, 0xFFAAAAAA.toInt())
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_GRID_Y, HOTBAR_GAP)

        // Batch label centered above the stepper.
        val batchLabelW = font.width(BATCH_LABEL)
        graphics.drawString(
            font, BATCH_LABEL,
            leftPos + (IMAGE_W - batchLabelW) / 2, topPos + BATCH_LABEL_Y,
            0xFFCCCCCC.toInt(),
        )

        // Stepper buttons.
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
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        channelPicker?.renderOverlay(graphics, mouseX, mouseY)
        jobIndicator?.renderTooltip(graphics, font, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Route popup clicks first.
        if (channelPicker?.expanded == true && channelPicker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true

        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        val step = if (hasShiftDownCompat()) 10 else 1
        if (mx in (leftPos + MINUS_BTN_X) until (leftPos + MINUS_BTN_X + STEP_BTN_W) &&
            my in (topPos + STEPPER_Y) until (topPos + STEPPER_Y + STEP_BTN_H)
        ) {
            adjustBatch(-step)
            return true
        }
        if (mx in (leftPos + PLUS_BTN_X) until (leftPos + PLUS_BTN_X + STEP_BTN_W) &&
            my in (topPos + STEPPER_Y) until (topPos + STEPPER_Y + STEP_BTN_H)
        ) {
            adjustBatch(step)
            return true
        }
        val result = super.mouseClicked(event, doubleClick)
        if (batchField.isFocused) {
            val fieldX = leftPos + FIELD_X
            val fieldY = topPos + STEPPER_Y
            val inField = mx in fieldX until fieldX + FIELD_W &&
                my in fieldY until fieldY + FIELD_H
            if (!inField) setFocused(null)
        }
        return result
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (batchField.isFocused && (event.keyCode == InputConstants.KEY_RETURN ||
            event.keyCode == InputConstants.KEY_NUMPADENTER)
        ) {
            setFocused(null)
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        val parsed = batchField.value.toIntOrNull()?.coerceIn(1, 1_000_000)
        if (parsed != null && parsed != lastSentBatch) {
            val now = Minecraft.getInstance().level?.gameTime ?: 0L
            sendBatch(parsed, now)
        }
        super.onClose()
    }

    private fun adjustBatch(delta: Int) {
        val current = batchField.value.toIntOrNull() ?: 1
        val next = (current + delta).coerceIn(1, 1_000_000)
        batchField.value = next.toString()
        if (batchField.isFocused) setFocused(null)
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f),
        )
    }

    private fun sendBatch(value: Int, now: Long) {
        lastSentBatch = value
        lastSendTick = now
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(
                menu.devicePos, "batch", value, "",
            ),
        )
    }
}
