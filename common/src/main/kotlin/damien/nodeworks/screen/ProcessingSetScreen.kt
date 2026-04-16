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

        private const val FRAME_W = 180
        private const val BG_H = 120  // upper panel height (matches exported PNG)

        private const val INV_PANEL_Y = 122
        private const val INV_PANEL_H = 96
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val INV_X = 8
        private const val HOTBAR_GAP = 4
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H

        private const val INPUT_COL_X = 36
        private const val OUTPUT_COL_X = 128
        private const val INPUT_SECTION_Y = 13
        private const val INPUT_SECTION_H = 54

        private const val PANEL_LABEL_Y = 83
        private const val PANEL_CONTROL_Y = 95
        private const val TIMEOUT_STEP = 20
        private const val STEPPER_BTN_SIZE = 14
        private const val TIMEOUT_ENTRY_W = 26
        private const val STEPPER_GAP = 2
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16
        private const val TIMEOUT_GROUP_W = STEPPER_BTN_SIZE + STEPPER_GAP + TIMEOUT_ENTRY_W + STEPPER_GAP + STEPPER_BTN_SIZE
        private const val GROUP_GAP = 14
        // Both groups centered as a unit: [timeout stepper] <gap> [parallel toggle]
        private const val TOTAL_CONTROLS_W = TIMEOUT_GROUP_W + GROUP_GAP + TOGGLE_W
        private const val CONTROLS_START_X = (FRAME_W - TOTAL_CONTROLS_W) / 2
        private const val TIMEOUT_GROUP_CENTER_X = CONTROLS_START_X + TIMEOUT_GROUP_W / 2
        private const val TIMEOUT_MINUS_X = CONTROLS_START_X
        private const val TIMEOUT_ENTRY_X = TIMEOUT_MINUS_X + STEPPER_BTN_SIZE + STEPPER_GAP
        private const val TIMEOUT_PLUS_X = TIMEOUT_ENTRY_X + TIMEOUT_ENTRY_W + STEPPER_GAP
        private const val PARALLEL_GROUP_CENTER_X = CONTROLS_START_X + TIMEOUT_GROUP_W + GROUP_GAP + TOGGLE_W / 2
        private const val TOGGLE_X = CONTROLS_START_X + TIMEOUT_GROUP_W + GROUP_GAP

        private const val CLEAR_BTN_SIZE = 14
        private const val CLEAR_BTN_X = 16
        private const val CLEAR_BTN_Y = INPUT_SECTION_Y + (INPUT_SECTION_H - CLEAR_BTN_SIZE) / 2


        private val BG_TEXTURE = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            "nodeworks", "textures/gui/processing_set_bg.png"
        )
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

        // Upper panel — single blit of the pre-composited static background.
        graphics.blit(BG_TEXTURE, x, y, 0f, 0f, FRAME_W, BG_H, FRAME_W, BG_H)

        // Slot frames drawn at runtime over the black placeholder regions in the PNG.
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

        // Text labels (can't be baked into the PNG — need MC's font renderer).
        val timeoutLabel = "Timeout (ticks)"
        graphics.drawString(font, timeoutLabel,
            x + TIMEOUT_GROUP_CENTER_X - font.width(timeoutLabel) / 2,
            y + PANEL_LABEL_Y, LABEL_COLOR)
        graphics.drawString(font, "-",
            x + TIMEOUT_MINUS_X + (STEPPER_BTN_SIZE - font.width("-")) / 2,
            y + PANEL_CONTROL_Y + 3, WHITE)
        graphics.drawString(font, "+",
            x + TIMEOUT_PLUS_X + (STEPPER_BTN_SIZE - font.width("+")) / 2,
            y + PANEL_CONTROL_Y + 3, WHITE)
        val toggleLabel = "Parallel"
        graphics.drawString(font, toggleLabel,
            x + PARALLEL_GROUP_CENTER_X - font.width(toggleLabel) / 2,
            y + PANEL_LABEL_Y, LABEL_COLOR)

        // Buttons — drawn at runtime as 9-slice, swapping to BUTTON_HOVER on hover.
        // Clear button is drawn 1px shorter on its bottom and right edges so the X icon
        // appears visually centered — the BUTTON 9-slice's bottom+right shadows otherwise
        // push the visual center up and left.
        val clearX = x + CLEAR_BTN_X
        val clearY = y + CLEAR_BTN_Y
        val clearDrawW = CLEAR_BTN_SIZE - 1
        val clearDrawH = CLEAR_BTN_SIZE - 1
        val clearHover = mouseX in clearX until clearX + CLEAR_BTN_SIZE && mouseY in clearY until clearY + CLEAR_BTN_SIZE
        (if (clearHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, clearX, clearY, clearDrawW, clearDrawH)
        Icons.X_SMALL.drawTopLeftTinted(graphics,
            clearX + (clearDrawW - 5) / 2,
            clearY + (clearDrawH - 5) / 2,
            5, 5, WHITE)

        val minusHover = mouseX in (x + TIMEOUT_MINUS_X) until (x + TIMEOUT_MINUS_X + STEPPER_BTN_SIZE) &&
                         mouseY in (y + PANEL_CONTROL_Y) until (y + PANEL_CONTROL_Y + STEPPER_BTN_SIZE)
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
            graphics, x + TIMEOUT_MINUS_X, y + PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE
        )
        val plusHover = mouseX in (x + TIMEOUT_PLUS_X) until (x + TIMEOUT_PLUS_X + STEPPER_BTN_SIZE) &&
                        mouseY in (y + PANEL_CONTROL_Y) until (y + PANEL_CONTROL_Y + STEPPER_BTN_SIZE)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
            graphics, x + TIMEOUT_PLUS_X, y + PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE
        )

        // Parallel toggle — dynamic state. Sits 1px above the stepper row so the
        // switch visually aligns with the entry field's text baseline.
        val toggleSlice = if (!menu.serial) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        toggleSlice.draw(graphics, x + TOGGLE_X, y + PANEL_CONTROL_Y - 1, TOGGLE_W, TOGGLE_H)

        // Player inventory — separate panel.
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
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

        // Clear-all button (left of input grid).
        val clearX = leftPos + CLEAR_BTN_X
        val clearY = topPos + CLEAR_BTN_Y
        if (mx in clearX until clearX + CLEAR_BTN_SIZE && my in clearY until clearY + CLEAR_BTN_SIZE) {
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
        val tBtnY = topPos + PANEL_CONTROL_Y - 1
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
