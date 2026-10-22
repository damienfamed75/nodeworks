package damien.nodeworks.screen

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.network.ProcessingSetGhostClickPayload
import damien.nodeworks.network.SetProcessingApiDataPayload
import damien.nodeworks.network.SetProcessingApiSlotPayload
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Processing Set GUI, 9-sliced version. Uses [NineSlice.WINDOW_FRAME] for the outer
 * frame (no separate title bar), bare [NineSlice.SLOT] frames for the input grid and
 * output column (matching the Inventory Terminal's crafting-grid style), a unicode `→`
 * between them, and [NineSlice.INVENTORY_BORDER] around the player inventory + hotbar.
 *
 * The 3x3 input grid and the 3 output cells are a window over a 3x9 grid and a 9 cell
 * column kept on the menu. Scrolling moves the window on this client alone, nothing is
 * sent. A click on a cell is mapped through the window to a backing cell and sent as
 * one packet, after the same change is applied here so the cell responds at once.
 *
 * Slot positions are owned by [ProcessingSetScreenHandler], this screen only paints
 * the backgrounds underneath them. Widths/heights are picked to frame those slot
 * coordinates with consistent padding.
 */
class ProcessingSetScreen(
    menu: ProcessingSetScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ProcessingSetScreenHandler>(menu, playerInventory, title, FRAME_W, FRAME_H) {

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

        /** One scrollbar against the output column's right edge moves the grid and the
         *  column together. As tall as the three visible rows plus their frame. */
        private const val SCROLLBAR_W = 6
        private const val BAR_X = OUTPUT_COL_X + 18 + 2
        private const val BAR_Y = INPUT_SECTION_Y - 1
        private const val BAR_H = INPUT_SECTION_H + 2
        private const val CHEVRON_COLOR = 0xFFDDDDDD.toInt()

        private const val PANEL_LABEL_Y = 83
        private const val PANEL_CONTROL_Y = 95
        private const val TIMEOUT_STEP = 20
        /** Upper cap for the per-set tick timeout. ~50s of game time, beyond that the
         *  recipe is almost certainly wedged and a higher cap just hides bugs. */
        private const val TIMEOUT_MAX = 999
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

        private const val TAG_CYCLE_PERIOD_MS = 1200L
        private const val OPTIONAL_BADGE = 0xFF55FFFF.toInt()
        private const val CLEAR_BTN_SIZE = 14
        private const val CLEAR_BTN_X = 16
        // Stacked pair: fuzzy toggle on top, clear button beneath, centered as a
        // pair against the 3-row input grid. Mirrors InstructionSetScreen's
        // substitutions-and-clear layout so the two recipe editors look alike.
        private const val FUZZY_BTN_SIZE = 14
        private const val FUZZY_BTN_X = CLEAR_BTN_X
        private const val FUZZY_ICON_SIZE = 9
        private const val BTN_PAIR_GAP = 2
        private const val BTN_PAIR_H = FUZZY_BTN_SIZE + BTN_PAIR_GAP + CLEAR_BTN_SIZE
        private const val FUZZY_BTN_Y = INPUT_SECTION_Y + (INPUT_SECTION_H - BTN_PAIR_H) / 2
        private const val CLEAR_BTN_Y = FUZZY_BTN_Y + FUZZY_BTN_SIZE + BTN_PAIR_GAP

        private val BG_TEXTURE = net.minecraft.resources.Identifier.fromNamespaceAndPath(
            "nodeworks", "textures/gui/processing_set_bg.png"
        )

        private const val VISIBLE_INPUTS = ProcessingSetScreenHandler.VISIBLE_INPUT_SLOTS
        private const val WINDOW_SLOTS = ProcessingSetScreenHandler.WINDOW_SLOTS
    }

    /** Public accessors for JEI ghost ingredient handler. */
    fun getLeft(): Int = leftPos
    fun getTop(): Int = topPos

    /** The backing cell a window slot shows, in the linear space the slot packets use:
     *  inputs 0 until 27, then outputs. For the JEI ghost drop. */
    fun backingLinearIndex(windowSlot: Int): Int =
        if (windowSlot < VISIBLE_INPUTS) menu.inputWindow.backingIndex(windowSlot)
        else ProcessingSetScreenHandler.INPUT_SLOTS + menu.outputWindow.backingIndex(windowSlot - VISIBLE_INPUTS)

    private var timeoutBox: EditBox? = null

    private var draggingBar = false

    /** A placement drag across cells: the mouse button held, and the last cell placed
     *  into, so each cell is placed once as the cursor crosses it. */
    private var paintButton = -1
    private var paintLastSlot = -1

    init {
        // Hide vanilla title / inventory labels, we draw our own.
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
            it.setMaxLength(TIMEOUT_MAX.toString().length)
            it.setValue(menu.timeout.toString())
            it.setResponder { value ->
                val timeout = (value.toIntOrNull() ?: 0).coerceIn(0, TIMEOUT_MAX)
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(menu.containerId, "timeout", 0, timeout)
                )
            }
            addRenderableWidget(it)
        }
    }

    /** A shift-click from the inventory lands in the first empty backing cell, which may
     *  be outside the window. Scroll so the player sees where it went. */
    override fun containerTick() {
        super.containerTick()
        val target = menu.lastQuickMoveTarget
        if (target < 0) return
        menu.lastQuickMoveTarget = -1
        val row = target / ProcessingSetScreenHandler.INPUT_COLS
        if (row < menu.scrollRow) menu.scrollRow = row
        else if (row >= menu.scrollRow + ProcessingSetScreenHandler.VISIBLE_ROWS) {
            menu.scrollRow = row - ProcessingSetScreenHandler.VISIBLE_ROWS + 1
        }
    }

    /** Hover tooltip queued during [extractBackground] and rendered after the
     *  rest of the GUI in [extractRenderState] so it overlays everything else.
     *  Same pattern StorageCardScreen uses for its filter-toggle tooltips. */
    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
        val x = leftPos
        val y = topPos

        // Upper panel, single blit of the pre-composited static background.
        graphics.blit(BG_TEXTURE, x, y, 0f, 0f, FRAME_W, BG_H, FRAME_W, BG_H)

        // Slot frames drawn at runtime over the black placeholder regions in the PNG.
        for (row in 0 until ProcessingSetScreenHandler.VISIBLE_ROWS) {
            for (col in 0 until ProcessingSetScreenHandler.INPUT_COLS) {
                val sx = x + INPUT_COL_X + col * 18
                val sy = y + INPUT_SECTION_Y + row * 18
                NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
            }
        }
        for (i in 0 until ProcessingSetScreenHandler.VISIBLE_OUTPUT_SLOTS) {
            val sx = x + OUTPUT_COL_X
            val sy = y + INPUT_SECTION_Y + i * 18
            NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
        }

        drawScrollbar(
            graphics, x + BAR_X, y + BAR_Y, menu.scrollRow,
            menu.hasContentAbove(menu.scrollRow),
            menu.hasContentAtOrBelow(menu.scrollRow + ProcessingSetScreenHandler.VISIBLE_ROWS),
        )

        // Text labels (can't be baked into the PNG, need MC's font renderer).
        val timeoutLabel = "Timeout (ticks)"
        graphics.drawString(font, timeoutLabel,
            x + TIMEOUT_GROUP_CENTER_X - font.width(timeoutLabel) / 2,
            y + PANEL_LABEL_Y, LABEL_COLOR)
        val toggleLabel = "Parallel"
        graphics.drawString(font, toggleLabel,
            x + PARALLEL_GROUP_CENTER_X - font.width(toggleLabel) / 2,
            y + PANEL_LABEL_Y, LABEL_COLOR)

        // Buttons, drawn at runtime as 9-slice, swapping to BUTTON_HOVER on hover.
        // Clear button is drawn 1px shorter on its bottom and right edges so the X icon
        // appears visually centered, the BUTTON 9-slice's bottom+right shadows otherwise
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
        if (clearHover) queueTooltip(mouseX, mouseY, "Clear slots")

        // Substitutions toggle, same icons as the Instruction Set editor.
        val fuzzyX = x + FUZZY_BTN_X
        val fuzzyY = y + FUZZY_BTN_Y
        val fuzzyDrawW = FUZZY_BTN_SIZE - 1
        val fuzzyDrawH = FUZZY_BTN_SIZE - 1
        val fuzzyHover = mouseX in fuzzyX until fuzzyX + FUZZY_BTN_SIZE && mouseY in fuzzyY until fuzzyY + FUZZY_BTN_SIZE
        (if (fuzzyHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, fuzzyX, fuzzyY, fuzzyDrawW, fuzzyDrawH)
        val fuzzyIcon = if (menu.fuzzy) Icons.SUBSTITUTIONS_ON else Icons.SUBSTITUTIONS_OFF
        fuzzyIcon.drawTopLeft(
            graphics,
            fuzzyX + (fuzzyDrawW - FUZZY_ICON_SIZE) / 2,
            fuzzyY + (fuzzyDrawH - FUZZY_ICON_SIZE) / 2,
            FUZZY_ICON_SIZE, FUZZY_ICON_SIZE,
        )
        if (fuzzyHover) {
            val lines = mutableListOf(
                "Substitutions: ${if (menu.fuzzy) "On" else "Off"}",
                "Tag inputs accept any item in their tag,",
                "other inputs accept any component variant.",
                "JEI's (+) sets a tag on inputs that show several items.",
            )
            for (i in 0 until ProcessingSetScreenHandler.INPUT_SLOTS) {
                val tag = menu.inputTags[i] ?: continue
                lines += "Slot ${i + 1}: #$tag"
            }
            lines += "Click to toggle."
            queueTooltip(mouseX, mouseY, *lines.toTypedArray())
        }

        // Stepper buttons, draw the 9-slice background first, then paint the +/- glyph
        // on top. (Previously the text was drawn before the button and got painted over.)
        val minusHover = mouseX in (x + TIMEOUT_MINUS_X) until (x + TIMEOUT_MINUS_X + STEPPER_BTN_SIZE) &&
                         mouseY in (y + PANEL_CONTROL_Y) until (y + PANEL_CONTROL_Y + STEPPER_BTN_SIZE)
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
            graphics, x + TIMEOUT_MINUS_X, y + PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE
        )
        graphics.drawString(font, "-",
            x + TIMEOUT_MINUS_X + (STEPPER_BTN_SIZE - font.width("-")) / 2,
            y + PANEL_CONTROL_Y + (STEPPER_BTN_SIZE - font.lineHeight) / 2 + 1, WHITE)

        val plusHover = mouseX in (x + TIMEOUT_PLUS_X) until (x + TIMEOUT_PLUS_X + STEPPER_BTN_SIZE) &&
                        mouseY in (y + PANEL_CONTROL_Y) until (y + PANEL_CONTROL_Y + STEPPER_BTN_SIZE)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
            graphics, x + TIMEOUT_PLUS_X, y + PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE
        )
        graphics.drawString(font, "+",
            x + TIMEOUT_PLUS_X + (STEPPER_BTN_SIZE - font.width("+")) / 2,
            y + PANEL_CONTROL_Y + (STEPPER_BTN_SIZE - font.lineHeight) / 2 + 1, WHITE)

        // Parallel toggle, dynamic state. Sits 1px above the stepper row so the
        // switch visually aligns with the entry field's text baseline.
        val toggleSlice = if (!menu.serial) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        toggleSlice.draw(graphics, x + TOGGLE_X, y + PANEL_CONTROL_Y - 1, TOGGLE_W, TOGGLE_H)

        // Player inventory, separate panel.
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    /** The bar with its thumb at [offset], and a chevron at an end when a filled cell lies
     *  beyond the window that way, so a card with more rows than show never looks like
     *  one that fits. */
    private fun drawScrollbar(
        graphics: GuiGraphicsExtractor, bx: Int, by: Int, offset: Int,
        contentAbove: Boolean, contentBelow: Boolean,
    ) {
        NineSlice.SCROLLBAR_TRACK.draw(graphics, bx, by, SCROLLBAR_W, BAR_H)
        val thumbH = (BAR_H * ProcessingSetScreenHandler.VISIBLE_ROWS / ProcessingSetScreenHandler.INPUT_ROWS).coerceAtLeast(8)
        val thumbY = by + (BAR_H - thumbH) * offset / ProcessingSetScreenHandler.MAX_SCROLL
        NineSlice.SCROLLBAR_THUMB.draw(graphics, bx, thumbY, SCROLLBAR_W, thumbH)
        if (contentAbove) drawChevron(graphics, bx, by - 5, up = true)
        if (contentBelow) drawChevron(graphics, bx, by + BAR_H + 1, up = false)
    }

    /** A 5 wide, 3 tall triangle in the bar's column. */
    private fun drawChevron(graphics: GuiGraphicsExtractor, bx: Int, ty: Int, up: Boolean) {
        val cx = bx + SCROLLBAR_W / 2
        for (i in 0 until 3) {
            val half = if (up) i else 2 - i
            graphics.fill(cx - half, ty + i, cx + half + 1, ty + i + 1, CHEVRON_COLOR)
        }
    }

    /** Members of each tag slot's tag, looked up once per tag. */
    private val tagMemberCache = HashMap<String, List<net.minecraft.world.item.Item>>()

    /** A tag input shows its tag's members in turn instead of its example item, so the
     *  player can see what the slot accepts. The slot's real item is untouched. Our own
     *  count badges are drawn later, so nothing else of the vanilla draw is needed. */
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: net.minecraft.world.inventory.Slot, mouseX: Int, mouseY: Int) {
        val idx = slot.index
        val isWindowInput = slot.container === menu.inputWindow && idx in 0 until VISIBLE_INPUTS
        val tag = if (menu.fuzzy && isWindowInput) menu.inputTags[menu.inputWindow.backingIndex(idx)] else null
        if (tag != null && slot.hasItem() && menu.carried.isEmpty) {
            val members = tagMemberCache.getOrPut(tag) { damien.nodeworks.card.ItemTagLookup.membersOf(tag) }
            if (members.isNotEmpty()) {
                val shown = members[((net.minecraft.util.Util.getMillis() / TAG_CYCLE_PERIOD_MS) % members.size).toInt()]
                graphics.renderItem(net.minecraft.world.item.ItemStack(shown), slot.x, slot.y)
                return
            }
        }
        super.extractSlot(graphics, slot, mouseX, mouseY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // Ghost overlay dim on occupied window cells, placed before the count badges so
        // the badges overlay the dim too.
        for (i in 0 until WINDOW_SLOTS) {
            val slot = menu.slots[i]
            if (slot.hasItem()) {
                val sx = leftPos + slot.x
                val sy = topPos + slot.y
                graphics.fillGradient(sx, sy, sx + 16, sy + 16, GHOST_OVERLAY, GHOST_OVERLAY)
            }
        }

        // Count badges, simple text draw on top of the item cell, read through the window
        // so each cell shows the count of the backing cell it is showing.
        val inputCounts = menu.inputCounts
        for (i in 0 until VISIBLE_INPUTS) {
            val slot = menu.slots[i]
            if (slot.hasItem()) {
                val count = inputCounts[menu.inputWindow.backingIndex(i)].coerceAtLeast(1)
                if (count > 1) drawStackCountBadge(graphics, leftPos + slot.x, topPos + slot.y, count)
            }
        }
        val outputCounts = menu.outputCounts
        for (i in 0 until ProcessingSetScreenHandler.VISIBLE_OUTPUT_SLOTS) {
            val slot = menu.slots[VISIBLE_INPUTS + i]
            if (slot.hasItem()) {
                val backing = menu.outputWindow.backingIndex(i)
                val count = outputCounts[backing].coerceAtLeast(1)
                if (count > 1) drawStackCountBadge(graphics, leftPos + slot.x, topPos + slot.y, count)
                // An optional output wears a question mark in its top-left corner.
                if (menu.outputOptional[backing]) graphics.text(font, "?", leftPos + slot.x + 1, topPos + slot.y + 1, OPTIONAL_BADGE, true)
            }
        }
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    /** Vanilla stack-count badge, right-aligned, white w/ shadow, at the bottom-right
     *  of a 16×16 item cell. */
    private fun drawStackCountBadge(graphics: GuiGraphicsExtractor, sx: Int, sy: Int, count: Int) {
        val text = count.toString()
        val tx = sx + 17 - font.width(text)
        val ty = sy + 9
        graphics.text(font, text, tx, ty, WHITE, true)
    }

    /** The window slot under the mouse, or -1. */
    private fun windowSlotAt(mx: Int, my: Int): Int {
        for (i in 0 until WINDOW_SLOTS) {
            val slot = menu.slots[i]
            val sx = leftPos + slot.x
            val sy = topPos + slot.y
            if (mx in sx until sx + 16 && my in sy until sy + 16) return i
        }
        return -1
    }

    /** A click on window cell [windowSlot]: applied here at once, then sent with the
     *  backing index so the server applies the same rule against its carried stack. */
    private fun clickCell(windowSlot: Int, button: Int) {
        val isOutput = windowSlot >= VISIBLE_INPUTS
        val index = if (isOutput) menu.outputWindow.backingIndex(windowSlot - VISIBLE_INPUTS)
            else menu.inputWindow.backingIndex(windowSlot)
        val rightClick = button == 1
        menu.applyGhostClick(index, isOutput, rightClick)
        PlatformServices.clientNetworking.sendToServer(
            ProcessingSetGhostClickPayload(menu.containerId, index, isOutput, button)
        )
    }

    private fun barRect(mx: Int, my: Int): Boolean =
        mx in (leftPos + BAR_X) until (leftPos + BAR_X + SCROLLBAR_W) && my in (topPos + BAR_Y) until (topPos + BAR_Y + BAR_H)

    private fun scrollTo(my: Int) {
        val rel = ((my - (topPos + BAR_Y)).toFloat() / BAR_H).coerceIn(0f, 0.999f)
        menu.scrollRow = (rel * (ProcessingSetScreenHandler.MAX_SCROLL + 1)).toInt()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Defocus the timeout field on any click outside it so inventory shortcuts work again.
        timeoutBox?.let { box ->
            val inBox = mx >= box.x && mx < box.x + box.width && my >= box.y && my < box.y + box.height
            if (!inBox && box.isFocused) box.isFocused = false
        }

        // Window cells, before vanilla can turn the click into a slot click.
        val cell = windowSlotAt(mx, my)
        if (cell >= 0 && (button == 0 || button == 1)) {
            clickCell(cell, button)
            // Painting only places. An empty hand clears, and a drag should never wipe a
            // row because the cursor slipped.
            if (!menu.carried.isEmpty) {
                paintButton = button
                paintLastSlot = cell
            }
            return true
        }

        if (barRect(mx, my)) {
            draggingBar = true
            scrollTo(my)
            return true
        }

        // Fuzzy-mode toggle (above the clear button).
        val fuzzyX = leftPos + FUZZY_BTN_X
        val fuzzyY = topPos + FUZZY_BTN_Y
        if (mx in fuzzyX until fuzzyX + FUZZY_BTN_SIZE && my in fuzzyY until fuzzyY + FUZZY_BTN_SIZE) {
            menu.fuzzy = !menu.fuzzy
            PlatformServices.clientNetworking.sendToServer(
                SetProcessingApiDataPayload(menu.containerId, "fuzzy", 0, if (menu.fuzzy) 1 else 0)
            )
            return true
        }

        // Clear-all button (left of input grid). One packet, every cell on both sides.
        val clearX = leftPos + CLEAR_BTN_X
        val clearY = topPos + CLEAR_BTN_Y
        if (mx in clearX until clearX + CLEAR_BTN_SIZE && my in clearY until clearY + CLEAR_BTN_SIZE) {
            menu.clearAll()
            PlatformServices.clientNetworking.sendToServer(
                SetProcessingApiDataPayload(menu.containerId, "clear", 0, 0)
            )
            return true
        }

        // Timeout stepper [-] button. Shift-click = ×5 step.
        val minusX = leftPos + TIMEOUT_MINUS_X
        val minusY = topPos + PANEL_CONTROL_Y
        if (mx in minusX until minusX + STEPPER_BTN_SIZE && my in minusY until minusY + STEPPER_BTN_SIZE) {
            val step = if (hasShiftDownCompat()) TIMEOUT_STEP * 5 else TIMEOUT_STEP
            val next = ((timeoutBox?.value?.toIntOrNull() ?: menu.timeout) - step).coerceAtLeast(0)
            timeoutBox?.value = next.toString()
            return true
        }

        // Timeout stepper [+] button. Shift-click = ×5 step.
        val plusX = leftPos + TIMEOUT_PLUS_X
        val plusY = topPos + PANEL_CONTROL_Y
        if (mx in plusX until plusX + STEPPER_BTN_SIZE && my in plusY until plusY + STEPPER_BTN_SIZE) {
            val step = if (hasShiftDownCompat()) TIMEOUT_STEP * 5 else TIMEOUT_STEP
            val next = ((timeoutBox?.value?.toIntOrNull() ?: menu.timeout) + step).coerceAtMost(TIMEOUT_MAX)
            timeoutBox?.value = next.toString()
            return true
        }

        // Parallel toggle. Mirror the change to the server, without this packet, the
        // server-side menu's `serial` field stays at whatever isSerial(stack) returned
        // when the menu opened, so saveRecipe persists the wrong value on close.
        val tBtnX = leftPos + TOGGLE_X
        val tBtnY = topPos + PANEL_CONTROL_Y - 1
        if (mx in tBtnX until tBtnX + TOGGLE_W && my in tBtnY until tBtnY + TOGGLE_H) {
            menu.serial = !menu.serial
            PlatformServices.clientNetworking.sendToServer(
                SetProcessingApiDataPayload(menu.containerId, "serial", 0, if (menu.serial) 1 else 0)
            )
            return true
        }

        return super.mouseClicked(event, doubleClick)
    }

    /** Dragging a thumb scrolls. Dragging across cells with a button held places into each
     *  cell entered, as painting into the grid worked through vanilla's drag before. */
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        if (draggingBar) {
            scrollTo(my)
            return true
        }
        if (paintButton >= 0) {
            val cell = windowSlotAt(mx, my)
            if (cell >= 0 && cell != paintLastSlot) {
                clickCell(cell, paintButton)
                paintLastSlot = cell
            }
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val wasOurs = draggingBar || paintButton >= 0
        draggingBar = false
        paintButton = -1
        paintLastSlot = -1
        if (wasOurs) return true
        return super.mouseReleased(event)
    }

    /**
     * The wheel over a filled cell adjusts its count: up adds one, down takes one, down at
     * one clears the cell. Anywhere else across the grid, the arrow, the column or the bar
     * scrolls both together.
     */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val cell = windowSlotAt(mx, my)
        if (cell >= 0 && menu.slots[cell].hasItem()) {
            val isInput = cell < VISIBLE_INPUTS
            val backing = if (isInput) menu.inputWindow.backingIndex(cell)
                else menu.outputWindow.backingIndex(cell - VISIBLE_INPUTS)
            val currentCount = if (isInput) menu.inputCounts[backing] else menu.outputCounts[backing]
            val delta = if (scrollY > 0) 1 else -1
            val newCount = currentCount + delta
            // Applied here first so the cell answers the wheel at once, the sync confirms.
            if (newCount <= 0) {
                val linear = if (isInput) backing else ProcessingSetScreenHandler.INPUT_SLOTS + backing
                menu.setSlotFromStack(linear, net.minecraft.world.item.ItemStack.EMPTY)
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(menu.containerId, linear, net.minecraft.world.item.ItemStack.EMPTY)
                )
            } else {
                if (isInput) menu.setInputCount(backing, newCount) else menu.setOutputCount(backing, newCount)
                val key = if (isInput) "input" else "output"
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(menu.containerId, key, backing, newCount)
                )
            }
            return true
        }
        val step = if (scrollY > 0) -1 else 1
        val overRecipe = mx in (leftPos + INPUT_COL_X - 1) until (leftPos + BAR_X + SCROLLBAR_W) &&
            my in (topPos + BAR_Y) until (topPos + BAR_Y + BAR_H)
        if (overRecipe) {
            menu.scrollRow += step
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val tBox = timeoutBox
        if (tBox != null && tBox.isFocused) {
            if (keyCode == 256) return super.keyPressed(event)  // ESC
            tBox.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }
}
