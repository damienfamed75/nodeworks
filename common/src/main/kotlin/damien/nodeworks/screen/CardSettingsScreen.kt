package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.screen.widget.ChannelPickerWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/**
 * Minimal "Card Settings" screen. Only renders the channel picker today; if more
 * per-card settings ever land they'd grow here without needing a per-card screen.
 *
 * The channel picker lives at a fixed offset inside a recessed inset, with a
 * "Channel:" label to its left. Same NineSlice frame + title-text layout as
 * [StorageCardScreen] so the two read consistently when a player flips between
 * a Storage Card (which has priority) and an IO/Redstone/Observer card (which
 * gets only this minimal panel).
 */
class CardSettingsScreen(
    menu: CardSettingsMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<CardSettingsMenu>(menu, playerInventory, title, W, H) {

    companion object {
        private const val W = 140
        private const val H = 50
        private const val INSET_X = 4
        private const val INSET_Y = 19
        private const val INSET_W = W - 8
        private const val INSET_H = H - 24
        private const val LABEL_TEXT = "Channel:"
        private const val LABEL_TO_PICKER_GAP = 4
    }

    private var picker: ChannelPickerWidget? = null
    private var labelX = 0
    private var pickerX = 0
    /** Tracks last seen server value so external menu updates (e.g. from another
     *  client) can flow into the widget without clobbering an in-progress click. */
    private var lastSyncedChannel: Int = -1

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()

        val labelW = font.width(LABEL_TEXT)
        val rowW = labelW + LABEL_TO_PICKER_GAP + ChannelPickerWidget.SWATCH
        val rowStart = (W - rowW) / 2
        labelX = rowStart
        pickerX = labelX + labelW + LABEL_TO_PICKER_GAP

        val pickerY = topPos + INSET_Y + (INSET_H - ChannelPickerWidget.SWATCH) / 2
        val initial = menu.getChannel()
        lastSyncedChannel = initial.id
        picker = ChannelPickerWidget(leftPos + pickerX, pickerY, initial) { color ->
            // Server sees the choice via the menu button id; the screen-side widget
            // already updated currentColor synchronously so the swatch reflects the
            // pick before the round-trip lands. The next data-sync tick then pushes
            // the same value back via [extractRenderState].
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, color.id)
        }
        addRenderableWidget(picker!!)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        graphics.drawString(font, "Card Settings", leftPos + 6, topPos + 7, 0xFFFFFFFF.toInt())
        NineSlice.WINDOW_RECESSED.draw(graphics, leftPos + INSET_X, topPos + INSET_Y, INSET_W, INSET_H)
        graphics.drawString(
            font, LABEL_TEXT,
            leftPos + labelX,
            topPos + INSET_Y + (INSET_H - font.lineHeight) / 2 + 1,
            0xFFAAAAAA.toInt(),
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Sync widget to server value when we don't have an open popup (so an
        // external mutation doesn't snap the swatch back mid-pick).
        val serverVal = menu.channelData.get(0)
        if (serverVal != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverVal) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverVal
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // Render the popup overlay AFTER all other widgets so it layers on top.
        picker?.renderOverlay(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Route clicks to the popup first so it can claim them — otherwise a click
        // in the popup grid would also fall through to whatever widget sits behind.
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        return super.mouseClicked(event, doubleClick)
    }
}
