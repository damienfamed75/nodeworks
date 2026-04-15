package damien.nodeworks.screen

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.network.SetProcessingApiDataPayload
import damien.nodeworks.network.SetProcessingApiSlotPayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Processing Set GUI — 9-sliced version. Uses [NineSlice.WINDOW_FRAME] for the outer
 * frame (no separate title bar), bare [NineSlice.SLOT] frames for the input grid and
 * output column (matching the Inventory Terminal's crafting-grid style), a unicode `→`
 * between them, and [NineSlice.INVENTORY_BORDER] around the player inventory + hotbar.
 *
 * Slot positions are owned by [ProcessingSetScreenHandler]; this screen only paints
 * the backgrounds underneath them. Widths/heights are picked to frame those slot
 * coordinates with consistent padding.
 */
class ProcessingSetScreen(
    menu: ProcessingSetScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ProcessingSetScreenHandler>(menu, playerInventory, title) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GHOST_OVERLAY = 0x40FFFFFF.toInt()

        // Layout knobs (all relative to top-left of the frame).
        private const val FRAME_W = 180
        private const val FRAME_H = 220

        private const val TITLE_Y = 7
        private const val SECTION_LABEL_Y = 18
        private const val INPUT_COL_X = 36
        private const val OUTPUT_COL_X = 128
        private const val INPUT_SECTION_Y = 30
        private const val INPUT_SECTION_H = 54   // 3 rows × 18
        private const val ARROW_ICON_SIZE = 12
        private const val ARROW_TINT: Int = 0xFF888888.toInt()
        private const val ARROW_OFFSET_X = -2

        // Recessed control panel (behind Timeout stepper + Parallel toggle). Matches
        // the scripting terminal's autorun area — 4-corner screws + inset. The panel
        // itself can be nudged vertically without disturbing the controls inside by
        // editing PANEL_Y alone; PANEL_LABEL_Y and PANEL_CONTROL_Y are absolute.
        private const val PANEL_X = 10
        private const val PANEL_Y = 88
        private const val PANEL_W = 160
        private const val PANEL_H = 42
        private const val PANEL_LABEL_Y = 95
        private const val PANEL_CONTROL_Y = 109
        private const val SCREW_SIZE = 6
        private const val SCREW_OFFSET = 1

        // Timeout stepper layout. "Timeout (ticks)" label centered above a
        // [-] [entry] [+] row with 2 px gap between each. Step = 20 ticks per click;
        // Shift-click = 100.
        private const val TIMEOUT_GROUP_CENTER_X = 53
        private const val TIMEOUT_STEP = 20
        private const val STEPPER_BTN_SIZE = 14
        private const val TIMEOUT_ENTRY_W = 26
        private const val STEPPER_GAP = 2
        private const val TIMEOUT_GROUP_W = STEPPER_BTN_SIZE + STEPPER_GAP + TIMEOUT_ENTRY_W + STEPPER_GAP + STEPPER_BTN_SIZE
        private const val TIMEOUT_MINUS_X = TIMEOUT_GROUP_CENTER_X - TIMEOUT_GROUP_W / 2
        private const val TIMEOUT_ENTRY_X = TIMEOUT_MINUS_X + STEPPER_BTN_SIZE + STEPPER_GAP
        private const val TIMEOUT_PLUS_X = TIMEOUT_ENTRY_X + TIMEOUT_ENTRY_W + STEPPER_GAP

        // Parallel toggle layout — vanilla TOGGLE (48×16) centered in the right half of
        // the recessed panel, with its "Parallel" label above.
        private const val PARALLEL_GROUP_CENTER_X = 130
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16
        private const val TOGGLE_X = PARALLEL_GROUP_CENTER_X - TOGGLE_W / 2

        private const val CLEAR_HIT = 9

        // Player inventory + hotbar (centered horizontally).
        private const val INV_Y = FRAME_H - 4 - 18 - 4 - 18 * 3  // hotbar + gap + 3 rows above
        private const val HOTBAR_Y = FRAME_H - 4 - 18
    }

    /** Public accessors for JEI ghost ingredient handler. */
    fun getLeft(): Int = leftPos
    fun getTop(): Int = topPos

    private var timeoutBox: EditBox? = null

    init {
        imageWidth = FRAME_W
        imageHeight = FRAME_H
        // Hide vanilla title / inventory labels; we draw our own.
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Timeout entry sits between the [-] and [+] stepper buttons, vertically
        // centered on PANEL_CONTROL_Y.
        timeoutBox = EditBox(
            font,
            leftPos + TIMEOUT_ENTRY_X, topPos + PANEL_CONTROL_Y + 1,
            TIMEOUT_ENTRY_W, STEPPER_BTN_SIZE - 2, Component.empty()
        ).also {
            it.setMaxLength(6)
            it.setValue(menu.timeout.toString())
            it.setResponder { value ->
                val timeout = value.toIntOrNull() ?: 0
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(menu.containerId, "timeout", 0, timeout)
                )
            }
            addRenderableWidget(it)
        }
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos

        // Outer frame — single WINDOW_FRAME, no separate top bar (matches StorageCardScreen style).
        NineSlice.WINDOW_FRAME.draw(graphics, x, y, FRAME_W, FRAME_H)

        // Title + clear button share the same top-row band.
        graphics.drawString(font, title, x + 6, y + TITLE_Y, WHITE)

        val clearX = x + FRAME_W - CLEAR_HIT - 5
        val clearY = y + 5
        val clearHovered = mouseX in clearX until clearX + CLEAR_HIT && mouseY in clearY until clearY + CLEAR_HIT
        val clearTint = if (clearHovered) WHITE else LABEL_COLOR
        Icons.X_SMALL.drawTopLeftTinted(graphics, clearX + 2, clearY + 2, 5, 5, clearTint)

        // Section labels above the grid.
        graphics.drawString(font, "Inputs", x + INPUT_COL_X, y + SECTION_LABEL_Y, LABEL_COLOR)
        graphics.drawString(font, "Output", x + OUTPUT_COL_X, y + SECTION_LABEL_Y, LABEL_COLOR)

        // Input 3×3 and output column — bare SLOT frames per cell, matching the crafting
        // terminal's crafting grid style. No background panel behind them.
        for (row in 0..2) {
            for (col in 0..2) {
                val sx = x + INPUT_COL_X + col * 18
                val sy = y + INPUT_SECTION_Y + row * 18
                NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
            }
        }
        for (i in 0 until ProcessingSetScreenHandler.OUTPUT_SLOTS) {
            val sx = x + OUTPUT_COL_X
            val sy = y + INPUT_SECTION_Y + i * 18
            NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
        }

        // Crafting arrow — gray-tinted Icons.ARROW_RIGHT, horizontally centered in the
        // gap between input grid (ends at INPUT_COL_X + 54 = 90) and output column
        // (starts at OUTPUT_COL_X = 128). Vertically centered in the input section.
        val arrowGapCenter = (INPUT_COL_X + 54 + OUTPUT_COL_X) / 2
        val arrowIconX = x + arrowGapCenter - ARROW_ICON_SIZE / 2 + ARROW_OFFSET_X
        val arrowIconY = y + INPUT_SECTION_Y + (INPUT_SECTION_H - ARROW_ICON_SIZE) / 2
        Icons.ARROW_RIGHT.drawTinted(graphics, arrowIconX, arrowIconY, ARROW_ICON_SIZE, ARROW_TINT)

        // ===== Recessed control panel (behind Timeout stepper + Parallel toggle) =====
        NineSlice.WINDOW_RECESSED.draw(graphics, x + PANEL_X, y + PANEL_Y, PANEL_W, PANEL_H)

        // Four corner screws just outside the recessed area — mirrors the scripting
        // terminal's autorun area style.
        val screwU = Icons.SMALL_SCREW.u + 5f
        val screwV = Icons.SMALL_SCREW.v + 5f
        val sTL_X = x + PANEL_X - SCREW_SIZE - SCREW_OFFSET
        val sTL_Y = y + PANEL_Y - SCREW_SIZE - SCREW_OFFSET
        val sTR_X = x + PANEL_X + PANEL_W + SCREW_OFFSET
        val sBR_Y = y + PANEL_Y + PANEL_H + SCREW_OFFSET
        graphics.blit(Icons.ATLAS, sTL_X, sTL_Y, screwU, screwV, SCREW_SIZE, SCREW_SIZE, 256, 256)
        graphics.blit(Icons.ATLAS, sTR_X, sTL_Y, screwU, screwV, SCREW_SIZE, SCREW_SIZE, 256, 256)
        graphics.blit(Icons.ATLAS, sTL_X, sBR_Y, screwU, screwV, SCREW_SIZE, SCREW_SIZE, 256, 256)
        graphics.blit(Icons.ATLAS, sTR_X, sBR_Y, screwU, screwV, SCREW_SIZE, SCREW_SIZE, 256, 256)

        // Timeout group: "Timeout (ticks)" label centered above a [-] [entry] [+] row.
        val timeoutLabel = "Timeout (ticks)"
        graphics.drawString(
            font, timeoutLabel,
            x + TIMEOUT_GROUP_CENTER_X - font.width(timeoutLabel) / 2,
            y + PANEL_LABEL_Y,
            LABEL_COLOR
        )
        val minusHovered = mouseX in (x + TIMEOUT_MINUS_X) until (x + TIMEOUT_MINUS_X + STEPPER_BTN_SIZE) &&
                           mouseY in (y + PANEL_CONTROL_Y) until (y + PANEL_CONTROL_Y + STEPPER_BTN_SIZE)
        val plusHovered = mouseX in (x + TIMEOUT_PLUS_X) until (x + TIMEOUT_PLUS_X + STEPPER_BTN_SIZE) &&
                          mouseY in (y + PANEL_CONTROL_Y) until (y + PANEL_CONTROL_Y + STEPPER_BTN_SIZE)
        (if (minusHovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
            graphics, x + TIMEOUT_MINUS_X, y + PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE
        )
        (if (plusHovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
            graphics, x + TIMEOUT_PLUS_X, y + PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE
        )
        graphics.drawString(font, "-",
            x + TIMEOUT_MINUS_X + (STEPPER_BTN_SIZE - font.width("-")) / 2,
            y + PANEL_CONTROL_Y + 3, WHITE)
        graphics.drawString(font, "+",
            x + TIMEOUT_PLUS_X + (STEPPER_BTN_SIZE - font.width("+")) / 2,
            y + PANEL_CONTROL_Y + 3, WHITE)

        // Parallel group: "Parallel" label centered above a TOGGLE switch.
        val parallelActive = !menu.serial
        val toggleSlice = if (parallelActive) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        val toggleLabel = "Parallel"
        graphics.drawString(
            font, toggleLabel,
            x + PARALLEL_GROUP_CENTER_X - font.width(toggleLabel) / 2,
            y + PANEL_LABEL_Y,
            LABEL_COLOR
        )
        toggleSlice.draw(graphics, x + TOGGLE_X, y + PANEL_CONTROL_Y, TOGGLE_W, TOGGLE_H)

        // Player inventory + hotbar — direct SLOT blit per cell (same as InventoryTerminalScreen).
        val slotU = NineSlice.SLOT.u.toFloat()
        val slotV = NineSlice.SLOT.v.toFloat()
        for (i in ProcessingSetScreenHandler.TOTAL_GHOST_SLOTS until menu.slots.size) {
            val slot = menu.slots[i]
            graphics.blit(NineSlice.GUI_ATLAS, x + slot.x - 1, y + slot.y - 1, slotU, slotV, 18, 18, 256, 256)
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Ghost overlay dim on occupied ghost slots — placed before the count badges so
        // the badges overlay the dim too.
        for (i in 0 until ProcessingSetScreenHandler.TOTAL_GHOST_SLOTS) {
            val slot = menu.slots[i]
            if (slot.hasItem()) {
                val sx = leftPos + slot.x
                val sy = topPos + slot.y
                graphics.fillGradient(
                    net.minecraft.client.renderer.RenderType.guiOverlay(),
                    sx, sy, sx + 16, sy + 16, GHOST_OVERLAY, GHOST_OVERLAY, 0
                )
            }
        }

        // Count badges — rendered via Font.SEE_THROUGH display mode. That render type
        // disables the depth test internally, so the labels aren't culled by the item
        // icons' depth values regardless of what state GL is in at our draw site. Flushing
        // the buffer source right after commits the batch before renderTooltip runs.
        val inputCounts = menu.inputCounts
        for (i in 0 until ProcessingSetScreenHandler.INPUT_SLOTS) {
            val slot = menu.slots[i]
            if (slot.hasItem()) {
                val count = inputCounts[i].coerceAtLeast(1)
                if (count > 1) drawStackCountBadge(graphics, leftPos + slot.x, topPos + slot.y, count)
            }
        }
        val outputCounts = menu.outputCounts
        for (i in 0 until ProcessingSetScreenHandler.OUTPUT_SLOTS) {
            val slot = menu.slots[ProcessingSetScreenHandler.INPUT_SLOTS + i]
            if (slot.hasItem()) {
                val count = outputCounts[i].coerceAtLeast(1)
                if (count > 1) drawStackCountBadge(graphics, leftPos + slot.x, topPos + slot.y, count)
            }
        }
        graphics.flush()

        renderTooltip(graphics, mouseX, mouseY)
    }

    /** Vanilla stack-count badge — right-aligned, white w/ shadow, at the bottom-right
     *  of a 16×16 item cell. Drawn via Font.SEE_THROUGH so depth test is bypassed and
     *  the label reliably layers in front of the item icon underneath. */
    private fun drawStackCountBadge(graphics: GuiGraphics, sx: Int, sy: Int, count: Int) {
        val text = count.toString()
        val tx = (sx + 17 - font.width(text)).toFloat()
        val ty = (sy + 9).toFloat()
        font.drawInBatch(
            text,
            tx, ty,
            WHITE,
            true,                                   // drop shadow
            graphics.pose().last().pose(),
            graphics.bufferSource(),
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            0,                                      // no background fill
            15728880                                // packed light = fullbright
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Defocus the timeout field on any click outside it so inventory shortcuts work again.
        timeoutBox?.let { box ->
            val inBox = mx >= box.x && mx < box.x + box.width && my >= box.y && my < box.y + box.height
            if (!inBox && box.isFocused) box.isFocused = false
        }

        // Clear-all button (top-right).
        val clearX = leftPos + FRAME_W - CLEAR_HIT - 5
        val clearY = topPos + 5
        if (mx in clearX until clearX + CLEAR_HIT && my in clearY until clearY + CLEAR_HIT) {
            for (i in 0 until ProcessingSetScreenHandler.TOTAL_GHOST_SLOTS) {
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(menu.containerId, i, "")
                )
            }
            return true
        }

        // Timeout stepper [-] button. Shift-click = ×5 step.
        val minusX = leftPos + TIMEOUT_MINUS_X
        val minusY = topPos + PANEL_CONTROL_Y
        if (mx in minusX until minusX + STEPPER_BTN_SIZE && my in minusY until minusY + STEPPER_BTN_SIZE) {
            val step = if (hasShiftDown()) TIMEOUT_STEP * 5 else TIMEOUT_STEP
            val next = ((timeoutBox?.value?.toIntOrNull() ?: menu.timeout) - step).coerceAtLeast(0)
            timeoutBox?.value = next.toString()
            return true
        }

        // Timeout stepper [+] button. Shift-click = ×5 step.
        val plusX = leftPos + TIMEOUT_PLUS_X
        val plusY = topPos + PANEL_CONTROL_Y
        if (mx in plusX until plusX + STEPPER_BTN_SIZE && my in plusY until plusY + STEPPER_BTN_SIZE) {
            val step = if (hasShiftDown()) TIMEOUT_STEP * 5 else TIMEOUT_STEP
            val next = ((timeoutBox?.value?.toIntOrNull() ?: menu.timeout) + step)
            timeoutBox?.value = next.toString()
            return true
        }

        // Parallel toggle.
        val tBtnX = leftPos + TOGGLE_X
        val tBtnY = topPos + PANEL_CONTROL_Y
        if (mx in tBtnX until tBtnX + TOGGLE_W && my in tBtnY until tBtnY + TOGGLE_H) {
            menu.serial = !menu.serial
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    /**
     * Scroll over a filled ghost slot to adjust its count. Scroll up = +1. Scroll down
     * at count > 1 = -1. Scroll down at count == 1 = clear the slot entirely.
     */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        for (i in 0 until ProcessingSetScreenHandler.TOTAL_GHOST_SLOTS) {
            val slot = menu.slots[i]
            val sx = leftPos + slot.x
            val sy = topPos + slot.y
            if (mx !in sx until sx + 16 || my !in sy until sy + 16) continue
            if (!slot.hasItem()) return false

            val isInput = i < ProcessingSetScreenHandler.INPUT_SLOTS
            val currentCount = if (isInput) menu.inputCounts[i]
                               else menu.outputCounts[i - ProcessingSetScreenHandler.INPUT_SLOTS]
            val delta = if (scrollY > 0) 1 else -1
            val newCount = currentCount + delta

            if (newCount <= 0) {
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(menu.containerId, i, "")
                )
            } else {
                val key = if (isInput) "input" else "output"
                val slotIdx = if (isInput) i else i - ProcessingSetScreenHandler.INPUT_SLOTS
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(menu.containerId, key, slotIdx, newCount)
                )
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val tBox = timeoutBox
        if (tBox != null && tBox.isFocused) {
            if (keyCode == 256) return super.keyPressed(keyCode, scanCode, modifiers)  // ESC
            tBox.keyPressed(keyCode, scanCode, modifiers)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }
}
