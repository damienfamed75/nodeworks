package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.RedstoneCycleButton
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
 * Two-panel GUI for the Import Chest, modelled on [ProcessingSetScreen]:
 *  * Upper panel: title, 1×9 chest grid, four settings rows (channel, redstone,
 *    round-robin, tick interval).
 *  * Lower panel: player inventory + hotbar in its own [NineSlice.WINDOW_FRAME]
 *    so the device controls are visually separated from the player's bag.
 *
 * Setting changes flow back to the BE via [DeviceSettingsPayload]:
 *  * `channel`, int = ChannelFilter NBT encoding (-1 = ALL, 0..15 = DyeColor.id)
 *  * `redstone`, int = 0 / 1 / 2 (Ignored / Active on Low / Active on High)
 *  * `roundRobin`, int = 0 / 1
 *  * `tickInterval`, int (1..600), accepts manual entry via the EditBox plus
 *    the standard ±1 / shift = ±5 stepper buttons.
 */
class ImportChestScreen(
    menu: ImportChestMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ImportChestMenu>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DIM = 0xFF555555.toInt()

        // 176-wide chest layout, two-panel split. The top panel is 2px shorter
        // than the inv-panel offset so a thin gap separates the two frames.
        // Without it, the bottom of the chest GUI butts directly into the
        // player inventory frame and the seam reads as a single fat border.
        private const val FRAME_W = 176
        private const val TOP_PANEL_H = 86
        private const val INV_PANEL_Y = 88
        private const val INV_PANEL_H = 96
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H
        private const val TOP_BAR_H = 20

        // Inventory section (matches [ImportChestMenu.INV_X / .INV_Y / .HOTBAR_Y]).
        private const val INV_X = 8
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val HOTBAR_GAP = 4

        // Settings rows. The four settings collapse to a 2×2 table:
        //   row 1: [Channel | Round-robin]
        //   row 2: [Redstone | Ticks]
        // The chest grid (1×9 at y=20) sits above, the inventory panel below.
        private const val ROW_1_Y = 44
        private const val ROW_2_Y = 64

        // Aliases per cell so call sites read clearly. ROW_REDSTONE_Y / ROW_TICK_Y
        // are unchanged in semantics, just mapped onto row 2.
        private const val ROW_CHANNEL_Y = ROW_1_Y
        private const val ROW_ROUND_ROBIN_Y = ROW_1_Y
        private const val ROW_REDSTONE_Y = ROW_2_Y
        private const val ROW_TICK_Y = ROW_2_Y

        // Common widget metrics. Col-1 controls (channel swatch, redstone icon,
        // round-robin checkbox) are all 16×16 so they line up vertically. The
        // user explicitly asked for a "table" feel where the swatch sits directly
        // above the redstone button.
        private const val ICON_BTN_SIZE = 16
        private const val SWATCH = 16
        private const val REDSTONE_BTN_SIZE = 16
        private const val STEPPER_BTN_SIZE = 14
        private const val TICK_ENTRY_W = 30

        /** Inner column margins. Outer left/right margins of the settings table.
         *  Mirrors the chest grid + player inventory left edge (x=8). */
        private const val LABEL_X = 8

        /** Right edge of column 1 (the channel/redstone column). Cells in this
         *  column flush their controls to this x, so the channel swatch and
         *  redstone icon button line up vertically. */
        private const val COL1_RIGHT = 72

        /** Left edge of column 2's labels. */
        private const val COL2_LABEL_X = 76

        // Tick stepper increments. Matches the ProcessingSet convention.
        private const val TICK_STEP = 1
        private const val TICK_STEP_SHIFT = 5
    }

    private var picker: ChannelPickerWidget? = null
    private var redstoneButton: RedstoneCycleButton? = null
    private var lastSyncedChannelNbt: Int = Int.MIN_VALUE
    private var tickBox: EditBox? = null
    private var lastSyncedTick: Int = -1

    init {
        titleLabelY = -9999
        inventoryLabelY = -9999
        imageWidth = FRAME_W
        imageHeight = FRAME_H
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        val initialFilter = menu.channelFilter
        val initialIsNone = initialFilter is ChannelFilter.All
        val initialColor = (initialFilter as? ChannelFilter.Color)?.color ?: DyeColor.WHITE
        lastSyncedChannelNbt = initialFilter.toNbtInt()

        // Single channel picker with a 17th "none" cell that maps to ChannelFilter.All.
        // Replaces the prior toggle + swatch combo: the picker's swatch shows the
        // current channel (or the black-with-X "none" glyph when matching all
        // channels), and the popup includes the "none" cell as a centred 17th option.
        picker = ChannelPickerWidget(
            leftPos + pickerX(), topPos + ROW_CHANNEL_Y,
            initialColor,
            canBeNone = true,
            initialIsNone = initialIsNone,
        ) { color ->
            sendChannelUpdate(color)
        }
        addRenderableWidget(picker!!)

        redstoneButton = RedstoneCycleButton(
            leftPos + COL1_RIGHT - REDSTONE_BTN_SIZE,
            topPos + ROW_REDSTONE_Y,
            menu.redstoneMode,
        ) { mode ->
            sendUpdate("redstone", mode, "")
        }
        addRenderableWidget(redstoneButton!!)

        // Tick interval entry. Manual typing accepts 1..MAX_TICK_INTERVAL.
        tickBox = EditBox(
            font, leftPos + tickEntryX(), topPos + ROW_TICK_Y + 1,
            TICK_ENTRY_W, STEPPER_BTN_SIZE - 2, Component.empty()
        ).also {
            it.setMaxLength(damien.nodeworks.block.entity.ImportChestBlockEntity.MAX_TICK_INTERVAL.toString().length)
            it.setValue(menu.tickInterval.toString())
            it.setResponder { value ->
                val v = value.toIntOrNull() ?: return@setResponder
                val clamped = v.coerceIn(
                    damien.nodeworks.block.entity.ImportChestBlockEntity.MIN_TICK_INTERVAL,
                    damien.nodeworks.block.entity.ImportChestBlockEntity.MAX_TICK_INTERVAL,
                )
                if (clamped != menu.tickInterval) sendUpdate("tickInterval", clamped, "")
            }
            addRenderableWidget(it)
        }
        lastSyncedTick = menu.tickInterval
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Clear last frame's tooltip. queueTooltip below repopulates if the
        // mouse is still over a tooltip-bearing widget.
        pendingTooltipLines.clear()
        val x = leftPos
        val y = topPos
        val networkColor = networkColor()

        // Two-panel frame: settings on top, player inventory on bottom.
        NineSlice.WINDOW_FRAME.draw(graphics, x, y, FRAME_W, TOP_PANEL_H)
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        NineSlice.drawTitleBar(graphics, font, title, x, y, FRAME_W, TOP_BAR_H, networkColor)

        // Server-driven channel sync (another player editing the same chest).
        val serverNbt = menu.channelFilter.toNbtInt()
        if (serverNbt != lastSyncedChannelNbt && picker?.expanded != true) {
            if (serverNbt < 0) {
                picker?.setNone()
            } else {
                picker?.setColor(runCatching { DyeColor.byId(serverNbt) }.getOrDefault(DyeColor.WHITE))
            }
            lastSyncedChannelNbt = serverNbt
        }
        // Same for tick interval, refresh entry when not focused.
        val serverTick = menu.tickInterval
        if (serverTick != lastSyncedTick && tickBox?.isFocused != true) {
            tickBox?.value = serverTick.toString()
            lastSyncedTick = serverTick
        }

        // Chest slot row backplates.
        for (col in 0 until ImportChestMenu.SLOT_COUNT) {
            NineSlice.SLOT.draw(
                graphics,
                x + ImportChestMenu.GRID_X + col * 18 - 1,
                y + ImportChestMenu.GRID_Y - 1,
                18, 18,
            )
        }

        // 2×2 settings table.
        //   Row 1: [Channel ……… swatch] [Round-robin ……… toggle-button]
        //   Row 2: [Redstone ……… icon ] [Ticks ……… [-] entry [+]]
        // Col 1 labels flush-left at LABEL_X, col 1 controls flush-right at
        // COL1_RIGHT, channel swatch and redstone icon line up vertically.
        // Col 2 labels flush-left at COL2_LABEL_X, col 2 controls flush-right
        // at controlsRight, round-robin button and tick [+] also line up.
        graphics.drawString(font, "Channel", x + LABEL_X, y + ROW_CHANNEL_Y + 5, LABEL_COLOR)
        graphics.drawString(font, "Redstone", x + LABEL_X, y + ROW_REDSTONE_Y + 5, LABEL_COLOR)
        graphics.drawString(font, "Round-robin", x + COL2_LABEL_X, y + ROW_ROUND_ROBIN_Y + 5, LABEL_COLOR)
        graphics.drawString(font, "Ticks", x + COL2_LABEL_X, y + ROW_TICK_Y + 5, LABEL_COLOR)

        // Channel picker swatch + redstone cycle button are rendered by their
        // widgets directly, including the channel "none" glyph and redstone
        // hover tooltip.
        redstoneButton?.setMode(menu.redstoneMode)

        // Round-robin: 16×16 icon button with CHECKMARK/X. Same size and column
        // as the channel swatch / redstone icon for the table-style alignment.
        val rrRect = roundRobinButtonRect()
        val rrHover = rrRect.contains(mouseX, mouseY)
        (if (rrHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, rrRect.x, rrRect.y, rrRect.w, rrRect.h)
        val rrIcon = if (menu.roundRobin) Icons.CHECKMARK else Icons.X
        rrIcon.draw(graphics, rrRect.x, rrRect.y)
        if (rrHover) {
            val state = if (menu.roundRobin) "On" else "Off"
            queueTooltip(mouseX, mouseY, "Round-robin: $state", "Click to toggle.")
        }

        // Tick stepper [-] and [+], drawn around the EditBox.
        val minusRect = tickMinusRect()
        val plusRect = tickPlusRect()
        val minusHover = minusRect.contains(mouseX, mouseY)
        val plusHover = plusRect.contains(mouseX, mouseY)
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, minusRect.x, minusRect.y, minusRect.w, minusRect.h)
        graphics.drawString(
            font, "-",
            minusRect.x + (minusRect.w - font.width("-")) / 2,
            minusRect.y + (minusRect.h - 8) / 2 + 1, WHITE
        )
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, plusRect.x, plusRect.y, plusRect.w, plusRect.h)
        graphics.drawString(
            font, "+",
            plusRect.x + (plusRect.w - font.width("+")) / 2,
            plusRect.y + (plusRect.h - 8) / 2 + 1, WHITE
        )

        // Inventory section: label + 9×3 grid + hotbar (drawPlayerInventory handles
        // the slot frames, we already drew the WINDOW_FRAME panel above).
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
        redstoneButton?.renderTooltip(graphics, mouseX, mouseY)
        // Tooltips queued during the background pass, render after the picker
        // overlay so they layer on top of every other GUI element.
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    // -- Widget rect helpers (all relative to leftPos / topPos, returns absolute). --

    private data class ButtonRect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(mx: Int, my: Int) = mx in x until x + w && my in y until y + h
    }

    /** Right edge of column 2's controls. Mirrors the left margin ([LABEL_X]). */
    private fun controlsRight(): Int = FRAME_W - LABEL_X

    /** Channel row, col 1: picker swatch flush right at [COL1_RIGHT]. */
    private fun pickerX(): Int = COL1_RIGHT - SWATCH

    /** Round-robin row, col 2: 16×16 icon button (CHECKMARK / X) flush right at
     *  [controlsRight], so it lines up vertically with the tick [+] below. */
    private fun roundRobinButtonRect(): ButtonRect = ButtonRect(
        leftPos + controlsRight() - ICON_BTN_SIZE,
        topPos + ROW_ROUND_ROBIN_Y,
        ICON_BTN_SIZE, ICON_BTN_SIZE,
    )

    /** Tick row, col 2: [-] [entry] [+], whole group flush right. */
    private fun tickPlusRect(): ButtonRect =
        ButtonRect(
            leftPos + controlsRight() - STEPPER_BTN_SIZE,
            topPos + ROW_TICK_Y,
            STEPPER_BTN_SIZE,
            STEPPER_BTN_SIZE
        )

    private fun tickEntryX(): Int = controlsRight() - STEPPER_BTN_SIZE - 2 - TICK_ENTRY_W
    private fun tickMinusRect(): ButtonRect =
        ButtonRect(
            leftPos + tickEntryX() - 2 - STEPPER_BTN_SIZE,
            topPos + ROW_TICK_Y,
            STEPPER_BTN_SIZE,
            STEPPER_BTN_SIZE
        )

    // -- Hover-tooltip plumbing for the redstone + round-robin buttons. Captured
    // during background render and drawn after super so the tooltip layers on top
    // of the rest of the GUI. Same shape [StorageCardScreen] uses.
    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Picker overlay (16-color popup) takes priority when expanded so its
        // 16 swatches receive the click before any other widget hit-test.
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }

        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        if (roundRobinButtonRect().contains(mx, my)) {
            sendUpdate("roundRobin", if (menu.roundRobin) 0 else 1, "")
            playClickSound()
            return true
        }
        // Tick stepper buttons: shift = ×5 step.
        if (tickMinusRect().contains(mx, my)) {
            applyTickStep(-currentTickStep())
            playClickSound()
            return true
        }
        if (tickPlusRect().contains(mx, my)) {
            applyTickStep(currentTickStep())
            playClickSound()
            return true
        }
        // Defocus the entry when clicking outside it. Vanilla EditBox handles
        // focus-on-click but doesn't drop focus on outside-click without help.
        val box = tickBox
        if (box != null && box.isFocused) {
            val inBox = mx in box.x until box.x + box.width && my in box.y until box.y + box.height
            if (!inBox) box.isFocused = false
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // Let the EditBox eat keystrokes when focused. Without this, slot
        // hotkeys (e.g. drop-stack 'q') pass through to the menu.
        if (event.keyCode == 256) return super.keyPressed(event) // ESC
        val box = tickBox
        if (box != null && box.isFocused) {
            box.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    private fun currentTickStep(): Int =
        if (hasShiftDownCompat()) TICK_STEP_SHIFT else TICK_STEP

    private fun applyTickStep(delta: Int) {
        val current = tickBox?.value?.toIntOrNull() ?: menu.tickInterval
        val next = (current + delta).coerceIn(
            damien.nodeworks.block.entity.ImportChestBlockEntity.MIN_TICK_INTERVAL,
            damien.nodeworks.block.entity.ImportChestBlockEntity.MAX_TICK_INTERVAL,
        )
        tickBox?.value = next.toString() // responder fires the network update
    }

    private fun sendChannelUpdate(color: DyeColor?) {
        val nbt = if (color == null) ChannelFilter.All.toNbtInt() else ChannelFilter.Color(color).toNbtInt()
        sendUpdate("channel", nbt, "")
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
            )
        )
    }

    private fun networkColor(): Int {
        val level =
            Minecraft.getInstance().level ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(level, menu.devicePos)
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(menu.devicePos, key, intValue, strValue)
        )
    }
}
