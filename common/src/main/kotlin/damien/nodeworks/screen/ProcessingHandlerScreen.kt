package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.network.ProcessingHandlerBindPayload
import damien.nodeworks.network.ProcessingHandlerSetAllInputsPayload
import damien.nodeworks.network.ProcessingHandlerSetInputChannelPayload
import damien.nodeworks.network.ProcessingHandlerSetOutputPayload
import damien.nodeworks.network.ProcessingHandlerUnbindPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

/**
 * Settings screen for the Processing Handler.
 *
 *  - **Recipe panel** (top): dual-purpose. When unbound, the panel renders a
 *    dimmed empty 3×3 → 3 grid with a "Click to pick recipe" prompt; clicking
 *    anywhere on the panel opens the picker overlay. When bound, the panel
 *    renders the full recipe (3×3 inputs + arrow + 3 outputs) with a small
 *    [x] in the top-right that unbinds the Handler.
 *  - **Picker overlay**: scrollable list of unclaimed Processing Sets on the
 *    parent network. Each row renders the same 3×3 → 3 visual the bound
 *    recipe panel uses, so what the player picks is exactly what they get.
 *  - **Inputs / Outputs sections**: NineSlice scrollboxes mirroring Storage
 *    Card's filter rule list. Per-input rows have a small editable channel
 *    swatch on the right; output rows have a same-sized read-only ghost
 *    swatch tinted with the section's output channel.
 *
 *  Live BE state mirrors every frame; the menu's [boundSet] / [availableSets]
 *  fields refresh via [ProcessingHandlerStateSyncPayload] after every server-
 *  side bind / unbind so the recipe panel and picker repopulate without a
 *  close/reopen.
 */
class ProcessingHandlerScreen(
    menu: ProcessingHandlerMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ProcessingHandlerMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        // OUTER_PAD applies to the left, right, and bottom sides (top is
        // governed by the title bar). IMAGE_W absorbs the extra horizontal
        // padding so the two interior columns keep their original widths.
        private const val IMAGE_W = 224
        private const val OUTER_PAD = 6
        private const val ICON_SIZE = 16
        private const val SLOT_SIZE = 18

        /** Title bar at the top of the GUI, mirrors NetworkController +
         *  InventoryTerminal. Drawn via [NineSlice.drawTitleBar]. */
        private const val TOP_BAR_H = 20

        // Two-column body. Left column hosts the vertical recipe panel +
        // a redstone/unbind button row beneath it. Right column hosts the
        // Inputs and Outputs scrollboxes stacked.
        private const val LEFT_X = OUTER_PAD
        private const val LEFT_COL_W = 60
        private const val COL_GAP = 4
        private const val RIGHT_X = LEFT_X + LEFT_COL_W + COL_GAP
        private const val RIGHT_COL_W = IMAGE_W - RIGHT_X - OUTER_PAD

        // Recipe panel: 3×3 input grid stacked above a down arrow stacked
        // above a 1×3 output row. The panel doubles as the picker entry
        // point - clicking it opens the dropdown. Hover effect runs even
        // when bound so it always reads as interactive.
        private const val RECIPE_PANEL_Y = TOP_BAR_H + 4
        private const val RECIPE_GRID_W = 3 * SLOT_SIZE                // 54
        private const val RECIPE_GRID_INPUT_H = 3 * SLOT_SIZE          // 54
        private const val RECIPE_ARROW_H = 24
        private const val RECIPE_GRID_OUTPUT_ROW_H = SLOT_SIZE         // 18
        private const val RECIPE_GRID_VERT_H =
            RECIPE_GRID_INPUT_H + RECIPE_ARROW_H + RECIPE_GRID_OUTPUT_ROW_H  // 96
        private const val RECIPE_PANEL_INNER_PAD = 2
        private const val RECIPE_PANEL_H =
            RECIPE_GRID_VERT_H + RECIPE_PANEL_INNER_PAD * 2 + 6        // 106
        private const val RECIPE_PANEL_W = LEFT_COL_W

        // Buttons row beneath the recipe panel: [redstone] [x] (unbind).
        // Both NineSlice.BUTTON, sized to match the picker close button so
        // they read as proper actionable controls, not inline glyphs.
        private const val BUTTON_SIZE = 14
        private const val BUTTON_GAP = 2
        private const val BUTTONS_Y = RECIPE_PANEL_Y + RECIPE_PANEL_H + 4

        /** Legacy [x] glyph size, retained because the previous in-panel
         *  unbind toggle was 11×11. The current button is 14×14 ([BUTTON_SIZE])
         *  but the constant is left in place pending art alignment. */
        private const val UNBIND_BTN_SIZE = 11

        // Section scrollboxes (Inputs / Outputs). Mirror StorageCard's rule
        // list: PANEL_INSET, ROW / ROW_HIGHLIGHT alternating stripes, SLOT-
        // backed item icon, scrollbar on the right (inputs only - outputs
        // can never overflow 3 rows so the track is hidden).
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 3
        private const val PANEL_INNER_PAD = 2
        private const val PANEL_W = RIGHT_COL_W
        private const val PANEL_H = VISIBLE_ROWS * ROW_H + PANEL_INNER_PAD * 2
        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2

        /** Section header row (label + channel picker). Headers are commented
         *  out in the new layout, kept as a constant in case header pickers
         *  are reintroduced. */
        private const val SECTION_HEADER_H = 16
        private const val SEPARATOR_OVERLAP = 2
        private const val ROW_ICON_SIZE = 16
        private const val ROW_ICON_PAD = 2

        // No section headers in the two-column layout - inputs / outputs
        // panels start flush with the top bar gap. Header Y constants are
        // retained (commented context) so the addSectionHeaderPickers path
        // can be re-enabled without re-deriving offsets.
        private const val INPUTS_PANEL_Y = TOP_BAR_H + 4
        private const val OUTPUTS_PANEL_Y = INPUTS_PANEL_Y + PANEL_H + 4

        // Headers temporarily disabled - kept for the (currently commented)
        // addSectionHeaderPickers path.
        private const val INPUTS_HEADER_Y = INPUTS_PANEL_Y - SECTION_HEADER_H
        private const val OUTPUTS_HEADER_Y = OUTPUTS_PANEL_Y - SECTION_HEADER_H

        // IMAGE_H = max(left col bottom, right col bottom). Left col bottom is
        // BUTTONS_Y + BUTTON_SIZE + OUTER_PAD (taller in the current layout);
        // right col bottom is OUTPUTS_PANEL_Y + PANEL_H + OUTER_PAD. Hardcoded
        // because Kotlin requires `const val` initializers to be primitive
        // expressions, and `maxOf` doesn't qualify.
        private const val IMAGE_H = BUTTONS_Y + BUTTON_SIZE + OUTER_PAD

        // Picker overlay covers the right column only (Inputs + Outputs).
        // Each row shows a horizontal recipe thumbnail (3×3 + arrow + 1×3
        // column) - keeping the thumbnail horizontal lets two rows fit in
        // the right column's height instead of one cramped vertical row.
        private const val PICKER_ROW_H = RECIPE_GRID_INPUT_H + 6
        private const val PICKER_OVERLAY_X = RIGHT_X
        private const val PICKER_OVERLAY_Y = INPUTS_PANEL_Y
        private const val PICKER_OVERLAY_W = RIGHT_COL_W
        private const val PICKER_OVERLAY_H = OUTPUTS_PANEL_Y + PANEL_H - INPUTS_PANEL_Y
        private const val PICKER_PANEL_INNER_PAD = 0
        private const val PICKER_VISIBLE_ROWS =
            (PICKER_OVERLAY_H - PICKER_PANEL_INNER_PAD * 2) / PICKER_ROW_H
        private const val PICKER_PANEL_H = PICKER_OVERLAY_H
        private const val PICKER_BTN_W = 56
        private const val PICKER_BTN_H = 14

        /** Small swatch size used by the per-input row pickers and the
         *  ghost output indicators. Sized down from [ChannelPickerWidget.SWATCH]
         *  so the swatches sit cleanly inside the row interior beside the
         *  16 px slot icon. */
        private const val SMALL_SWATCH = 10
        private const val ROW_PICKER_RIGHT_PAD = 4

        private const val LABEL_GRAY = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Tint used when no recipe is bound. Multiplied over the recipe
         *  grid + dimmed background so the panel reads as "click me to pick"
         *  without losing the empty-grid visual cue. */
        private const val UNBOUND_DIM = 0xC0000000.toInt()
        private const val UNBOUND_DIM_HOVER = 0x80000000.toInt()

        /** Subtle white overlay applied to the recipe panel on hover even when
         *  bound, so the panel always reads as interactive (click = open the
         *  picker to change the binding). */
        private const val BOUND_HOVER_OVERLAY = 0x22FFFFFF

        /** Translucent gray laid over the read-only output ghost swatches so
         *  they read as not-clickable while sharing the editable swatch's
         *  texture. */
        private const val GHOST_GRAY_OVERLAY = 0x80808080.toInt()

        /** Guidebook ref opened when the player clicks the `[?]` help button.
         *  The page itself doesn't exist yet (will be authored separately);
         *  the click attempts the open and the guidebook handles the missing
         *  page gracefully. */
        private const val PROCESSING_HANDLER_GUIDE_REF =
            "nodeworks:items-blocks/processing_handler.md"
    }

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    init {
        // Suppress vanilla title + Inventory labels; the WINDOW_FRAME provides
        // the visual container and the raw Set name should never reach the
        // player UI (only the recipe-as-grid view is shown).
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private val availableSets: List<ProcessingHandlerOpenData.AvailableSet>
        get() = menu.availableSets
    private val boundSet: ProcessingHandlerOpenData.AvailableSet?
        get() = menu.boundSet
    private val boundSetMissing: Boolean
        get() = menu.boundSetMissing

    private var pickerOpen = false
    private var pickerScroll = 0
    private var inputsScroll = 0
    private var outputsScroll = 0

    private val inputPickers = mutableMapOf<damien.nodeworks.script.BufferKey.Key, ChannelPickerWidget>()
    private var inputsHeaderPicker: ChannelPickerWidget? = null
    private var outputsHeaderPicker: ChannelPickerWidget? = null

    private var lastBoundApiName: String = ""
    private var lastInputItemIds: List<damien.nodeworks.script.BufferKey.Key> = emptyList()
    private var lastInputsScroll = -1

    override fun init() {
        super.init()
        rebuildPickers()
    }

    private fun entity(): ProcessingHandlerBlockEntity? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        return level.getBlockEntity(menu.devicePos) as? ProcessingHandlerBlockEntity
    }

    // ---- Channel pickers (per-input + section headers) ----

    private fun rebuildPickers() {
        clearWidgets()
        inputPickers.clear()
        inputsHeaderPicker = null
        outputsHeaderPicker = null
        val be = entity() ?: return
        val inputIds = be.snapshotInputChannels().keys.toList()
        lastBoundApiName = be.processingApiName
        lastInputItemIds = inputIds
        lastInputsScroll = inputsScroll
        if (be.processingApiName.isEmpty()) return
        // Section header channel pickers are commented out for the two-column
        // layout (no headers rendered). Re-enable both this call and the
        // drawSectionHeader calls in extractBackground to bring them back.
        // addSectionHeaderPickers(be)
        addInputRowPickers(be)
    }

    private fun addSectionHeaderPickers(be: ProcessingHandlerBlockEntity) {
        // Header swatches stay at the standard 16×16 since the bumped header
        // height accommodates them; only the row pickers shrink.
        val swatch = ChannelPickerWidget.SWATCH
        val pickerX = leftPos + IMAGE_W - OUTER_PAD - swatch - 2
        // Lift the swatches a couple px above the header centre so they hug
        // the section label without crowding the scrollbox below it. -2 keeps
        // them visually paired with the label baseline.
        val inputsPickerY = topPos + INPUTS_HEADER_Y + (SECTION_HEADER_H - swatch) / 2 - 2
        val headerInputColor = be.snapshotInputChannels().values.firstOrNull() ?: DyeColor.BLUE
        val headerInputs = ChannelPickerWidget(
            x = pickerX,
            y = inputsPickerY,
            initialColor = headerInputColor,
            onChange = { color ->
                if (color != null) {
                    PlatformServices.clientNetworking.sendToServer(
                        ProcessingHandlerSetAllInputsPayload(menu.devicePos, color.id)
                    )
                }
            },
        )
        inputsHeaderPicker = headerInputs
        addRenderableWidget(headerInputs)

        val outputsPickerY = topPos + OUTPUTS_HEADER_Y + (SECTION_HEADER_H - swatch) / 2 - 2
        val headerOutputs = ChannelPickerWidget(
            x = pickerX,
            y = outputsPickerY,
            initialColor = be.outputChannel,
            onChange = { color ->
                if (color != null) {
                    PlatformServices.clientNetworking.sendToServer(
                        ProcessingHandlerSetOutputPayload(menu.devicePos, color.id)
                    )
                }
            },
        )
        outputsHeaderPicker = headerOutputs
        addRenderableWidget(headerOutputs)
    }

    private fun addInputRowPickers(be: ProcessingHandlerBlockEntity) {
        val inputChannels = be.snapshotInputChannels().toList()
        val interiorX = leftPos + RIGHT_X + PANEL_INNER_PAD
        val interiorY = topPos + INPUTS_PANEL_Y + PANEL_INNER_PAD
        val interiorW = PANEL_W - PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val pickerX = interiorX + interiorW - ROW_PICKER_RIGHT_PAD - SMALL_SWATCH
        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val rowIdx = inputsScroll + visibleIdx
            if (rowIdx >= inputChannels.size) break
            val (bufferKey, color) = inputChannels[rowIdx]
            val rowY = interiorY + visibleIdx * ROW_H
            val pickerY = rowY + (ROW_H - SEPARATOR_OVERLAP - SMALL_SWATCH) / 2
            val picker = ChannelPickerWidget(
                x = pickerX,
                y = pickerY,
                initialColor = color,
                swatchSize = SMALL_SWATCH,
                tooltipFormatter = { c ->
                    "Routes to ${c.name.lowercase().replaceFirstChar { it.uppercase() }} Storage Cards"
                },
                onChange = { newColor ->
                    if (newColor != null) {
                        PlatformServices.clientNetworking.sendToServer(
                            ProcessingHandlerSetInputChannelPayload(menu.devicePos, bufferKey.itemId, bufferKey.componentsHash, newColor.id)
                        )
                    }
                },
            )
            inputPickers[bufferKey] = picker
            addRenderableWidget(picker)
        }
    }

    private fun syncPickersToBe() {
        val be = entity() ?: return
        // Cheap checks first: rebuild if scroll moved or the bound api changed,
        // before doing any per-frame map allocation. Snapshot only when we
        // actually need to compare input ids.
        if (inputsScroll != lastInputsScroll || be.processingApiName != lastBoundApiName) {
            if (be.processingApiName != lastBoundApiName) {
                inputsScroll = 0
                outputsScroll = 0
            }
            rebuildPickers()
            return
        }
        val inputChannels = be.snapshotInputChannels()
        if (inputChannels.keys.toList() != lastInputItemIds) {
            rebuildPickers()
            return
        }
        for ((bufferKey, picker) in inputPickers) {
            val color = inputChannels[bufferKey] ?: continue
            if (picker.currentColor != color) picker.setColor(color)
        }
        outputsHeaderPicker?.let { picker ->
            if (picker.currentColor != be.outputChannel) picker.setColor(be.outputChannel)
        }
        if (inputsScroll > 0 && inputsScroll + VISIBLE_ROWS > inputChannels.size) {
            inputsScroll = (inputChannels.size - VISIBLE_ROWS).coerceAtLeast(0)
            rebuildPickers()
        }
    }

    // ---- Render entry points ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        syncPickersToBe()
        pendingTooltipLines.clear()
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        val networkColor = entity()?.networkId?.let {
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(it)
        } ?: 0x888888
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor)
        drawRecipePanel(graphics, mouseX, mouseY)
        drawButtonsRow(graphics, mouseX, mouseY)
        // Section headers (label + channel picker) are commented out for the
        // two-column layout - the inputs/outputs panels start flush with the
        // top bar gap. drawSectionHeader is preserved for re-enabling later.
        // drawSectionHeader(graphics, "Inputs", INPUTS_HEADER_Y)
        // drawSectionHeader(graphics, "Outputs", OUTPUTS_HEADER_Y)
        drawSectionScrollbox(graphics, INPUTS_PANEL_Y, isOutputs = false, mouseX, mouseY)
        drawSectionScrollbox(graphics, OUTPUTS_PANEL_Y, isOutputs = true, mouseX, mouseY)
    }

    private fun drawButtonsRow(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val be = entity()
        val isBound = be != null && be.processingApiName.isNotEmpty() && findBoundSet() != null

        val (rx, ry, rw, rh) = redstoneButtonBounds().toList()
        val redstoneHover = mouseX in rx until rx + rw && mouseY in ry until ry + rh
        (if (redstoneHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, rx, ry, rw, rh)
        Icons.REDSTONE_IGNORE.draw(graphics, rx + (rw - 16) / 2, ry + (rh - 16) / 2)
        if (redstoneHover) {
            queueTooltip(mouseX, mouseY, "Redstone control", "(Not implemented yet)")
        }

        // Unbind only meaningful when bound; render dimmed but in-place when
        // unbound so the button row layout doesn't reflow.
        val (bx, by, bw, bh) = unbindButtonBounds().toList()
        val unbindHover = mouseX in bx until bx + bw && mouseY in by until by + bh
        val unbindActive = isBound && unbindHover
        (if (unbindActive) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, bx, by, bw, bh)
        graphics.drawString(
            font, "x",
            bx + (bw - font.width("x")) / 2 + 1,
            by + (bh - font.lineHeight) / 2,
            if (isBound) WHITE else LABEL_GRAY,
        )
        if (unbindHover) {
            if (isBound) {
                queueTooltip(mouseX, mouseY, "Unbind recipe")
            } else {
                queueTooltip(mouseX, mouseY, "Unbind recipe", "(No recipe bound)")
            }
        }

        val (hx, hy, hw, hh) = helpButtonBounds().toList()
        val helpHover = mouseX in hx until hx + hw && mouseY in hy until hy + hh
        (if (helpHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, hx, hy, hw, hh)
        // QUESTION_9 is a 9×9 glyph - centre it inside the 14×14 button.
        Icons.QUESTION_9.drawTopLeft(graphics, hx + (hw - 9) / 2, hy + (hh - 9) / 2, 9, 9)
        if (helpHover) {
            queueTooltip(mouseX, mouseY, "Open guidebook")
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        renderChannelPickerOverlays(graphics, mouseX, mouseY)
        if (pickerOpen) renderPickerOverlay(graphics, mouseX, mouseY)
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    // ---- Recipe panel (clickable; opens picker, hosts unbind [x]) ----

    private fun recipePanelBounds(): IntArray {
        return intArrayOf(leftPos + LEFT_X, topPos + RECIPE_PANEL_Y, RECIPE_PANEL_W, RECIPE_PANEL_H)
    }

    /** Buttons row beneath the recipe panel: redstone (slot 0) and unbind
     *  (slot 1). Returns the [x, y, w, h] for the slot at [index]. */
    private fun buttonRowBounds(index: Int): IntArray {
        val bx = leftPos + LEFT_X + index * (BUTTON_SIZE + BUTTON_GAP)
        val by = topPos + BUTTONS_Y
        return intArrayOf(bx, by, BUTTON_SIZE, BUTTON_SIZE)
    }

    private fun redstoneButtonBounds(): IntArray = buttonRowBounds(0)
    private fun unbindButtonBounds(): IntArray = buttonRowBounds(1)
    private fun helpButtonBounds(): IntArray = buttonRowBounds(2)

    private fun inputsPanelBounds(): IntArray =
        intArrayOf(leftPos + RIGHT_X, topPos + INPUTS_PANEL_Y, PANEL_W, PANEL_H)

    private fun outputsPanelBounds(): IntArray =
        intArrayOf(leftPos + RIGHT_X, topPos + OUTPUTS_PANEL_Y, PANEL_W, PANEL_H)

    private fun drawRecipePanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val (panelX, panelY, panelW, panelH) = recipePanelBounds().toList()
        NineSlice.PANEL_INSET.draw(graphics, panelX, panelY, panelW, panelH)

        val be = entity()
        val recipe = if (be != null) findBoundSet() else null
        val isBound = be != null && be.processingApiName.isNotEmpty() && recipe != null
        val hovering = mouseX in panelX until panelX + panelW &&
                mouseY in panelY until panelY + panelH && !pickerOpen

        drawRecipeGrid(graphics, panelX, panelY, panelW, panelH, recipe, vertical = true)

        val interiorX = panelX + RECIPE_PANEL_INNER_PAD
        val interiorY = panelY + RECIPE_PANEL_INNER_PAD
        val interiorRight = panelX + panelW - RECIPE_PANEL_INNER_PAD
        val interiorBottom = panelY + panelH - RECIPE_PANEL_INNER_PAD

        if (!isBound) {
            // Dim the empty grid + render a hover-prompt overlay so the panel
            // reads as "click me to pick a recipe."
            val dimColor = if (hovering) UNBOUND_DIM_HOVER else UNBOUND_DIM
            graphics.fill(interiorX, interiorY, interiorRight, interiorBottom, dimColor)

            val prompt = if (boundSetMissing && be?.processingApiName?.isNotEmpty() == true) {
                "Recipe missing"
            } else {
                "Pick recipe"
            }
            val tx = panelX + (panelW - font.width(prompt)) / 2
            val ty = panelY + (panelH - font.lineHeight) / 2
            graphics.drawString(font, prompt, tx, ty, WHITE)
            return
        }

        // Bound: persistent hover overlay so the panel always reads as
        // interactive (clicking opens the picker to change the binding).
        // [x] unbind moved to the buttons row beneath, so the entire panel
        // interior gets the hover tint without a cutout.
        if (hovering) {
            graphics.fill(interiorX, interiorY, interiorRight, interiorBottom, BOUND_HOVER_OVERLAY)
            queueTooltip(mouseX, mouseY, "Click to change recipe")
        }
    }

    /** Draw the 3×3 input grid, arrow, and outputs inside the given bounds.
     *  When [vertical] is true the layout is inputs (top) → arrow down →
     *  output row (1×3 horizontal, bottom); otherwise inputs (left) → arrow
     *  right → output column (1×3 vertical, right) - the original layout
     *  used by the picker thumbnails. When [recipe] is null the grid renders
     *  empty (slot frames only) so the panel still reads as a recipe-shaped
     *  area pre-binding. */
    private fun drawRecipeGrid(
        graphics: GuiGraphicsExtractor,
        panelX: Int,
        panelY: Int,
        panelW: Int,
        panelH: Int,
        recipe: ProcessingHandlerOpenData.AvailableSet?,
        vertical: Boolean = false,
    ) {
        if (vertical) {
            val blockW = RECIPE_GRID_W
            val gridX = panelX + (panelW - blockW) / 2
            val gridY = panelY + (panelH - RECIPE_GRID_VERT_H) / 2
            val outputY = gridY + RECIPE_GRID_INPUT_H + RECIPE_ARROW_H

            for (row in 0..2) {
                for (col in 0..2) {
                    NineSlice.SLOT.draw(
                        graphics,
                        gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE,
                        SLOT_SIZE, SLOT_SIZE,
                    )
                }
            }
            for (col in 0..2) {
                NineSlice.SLOT.draw(
                    graphics,
                    gridX + col * SLOT_SIZE, outputY,
                    SLOT_SIZE, SLOT_SIZE,
                )
            }

            // Down arrow: rotate ARROW_RIGHT 90° clockwise around its centre.
            // pose().rotate is CCW in math coords; screen Y points down so a
            // math-CCW rotation reads as visually clockwise, mapping right →
            // down for an arrow pointing right.
            val arrowX = gridX + (RECIPE_GRID_W - 16) / 2
            val arrowY = gridY + RECIPE_GRID_INPUT_H + (RECIPE_ARROW_H - 16) / 2
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(arrowX + 8f, arrowY + 8f)
            pose.rotate((Math.PI / 2.0).toFloat())
            pose.translate(-8f, -8f)
            Icons.ARROW_RIGHT.draw(graphics, 0, 0)
            pose.popMatrix()

            if (recipe == null) return
            for ((idx, pair) in recipe.inputs.withIndex()) {
                if (idx >= 9) break
                val ingr = pair
                val col = idx % 3
                val row = idx / 3
                val sx = gridX + col * SLOT_SIZE + 1
                val sy = gridY + row * SLOT_SIZE + 1
                val stack = ingr.stack.copyWithCount(ingr.count.coerceIn(1, 99))
                graphics.renderItem(stack, sx, sy)
                graphics.renderItemDecorations(font, stack, sx, sy)
            }
            for ((idx, pair) in recipe.outputs.withIndex()) {
                if (idx >= 3) break
                val ingr = pair
                val sx = gridX + idx * SLOT_SIZE + 1
                val sy = outputY + 1
                val stack = ingr.stack.copyWithCount(ingr.count.coerceIn(1, 99))
                graphics.renderItem(stack, sx, sy)
                graphics.renderItemDecorations(font, stack, sx, sy)
            }
            return
        }

        // Horizontal (picker thumbnail).
        val blockW = RECIPE_GRID_W + RECIPE_ARROW_H + SLOT_SIZE
        val gridX = panelX + (panelW - blockW) / 2
        val gridY = panelY + (panelH - RECIPE_GRID_INPUT_H) / 2
        val outputX = gridX + RECIPE_GRID_W + RECIPE_ARROW_H

        for (row in 0..2) {
            for (col in 0..2) {
                NineSlice.SLOT.draw(graphics, gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
            }
        }
        for (row in 0..2) {
            NineSlice.SLOT.draw(graphics, outputX, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
        }

        val arrowX = gridX + RECIPE_GRID_W + (RECIPE_ARROW_H - 16) / 2
        val arrowY = gridY + (RECIPE_GRID_INPUT_H - 16) / 2
        Icons.ARROW_RIGHT.draw(graphics, arrowX, arrowY)

        if (recipe == null) return
        for ((idx, ingr) in recipe.inputs.withIndex()) {
            if (idx >= 9) break
            val col = idx % 3
            val row = idx / 3
            val sx = gridX + col * SLOT_SIZE + 1
            val sy = gridY + row * SLOT_SIZE + 1
            val stack = ingr.stack.copyWithCount(ingr.count.coerceIn(1, 99))
            graphics.renderItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
        }
        for ((idx, ingr) in recipe.outputs.withIndex()) {
            if (idx >= 3) break
            val sx = outputX + 1
            val sy = gridY + idx * SLOT_SIZE + 1
            val stack = ingr.stack.copyWithCount(ingr.count.coerceIn(1, 99))
            graphics.renderItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
        }
    }

    // ---- Inputs / Outputs sections ----

    private fun drawSectionHeader(graphics: GuiGraphicsExtractor, label: String, headerY: Int) {
        // Vertically centred inside the bumped 16-tall header bounds.
        val ty = topPos + headerY + (SECTION_HEADER_H - font.lineHeight) / 2
        graphics.drawString(font, label, leftPos + OUTER_PAD + 4, ty, LABEL_GRAY)
    }

    private fun drawSectionScrollbox(
        graphics: GuiGraphicsExtractor,
        panelY: Int,
        isOutputs: Boolean,
        mouseX: Int,
        mouseY: Int,
    ) {
        val be = entity() ?: return
        // When the bound recipe isn't on the network, outputs aren't recoverable
        // (the BE doesn't store them). Blank inputs too in this state so the two
        // sections stay consistent and the recipe-panel "click to pick" prompt
        // remains the single call to action.
        val recipeMissing = be.processingApiName.isNotEmpty() && findBoundSet() == null
        // Iterate the bound recipe's component-aware ingredients so each row
        // can render the actual variant (e.g. "Potion of Strength") instead
        // of a generic plain-item placeholder. Channel-map is still keyed by
        // itemId, so two variants sharing an itemId currently share a channel.
        val rows: List<RowItem> = when {
            recipeMissing -> emptyList()
            isOutputs -> findBoundSet()?.outputs?.map { RowItem(it.stack, it.count) } ?: emptyList()
            else -> findBoundSet()?.inputs?.map { RowItem(it.stack, it.count) } ?: emptyList()
        }
        val scroll = if (isOutputs) outputsScroll else inputsScroll

        val panelX = leftPos + RIGHT_X
        val panelTop = topPos + panelY
        NineSlice.PANEL_INSET.draw(graphics, panelX, panelTop, PANEL_W, PANEL_H)

        val interiorX = panelX + PANEL_INNER_PAD
        val interiorY = panelTop + PANEL_INNER_PAD
        // Outputs has no scrollbar, so the row strip claims the full panel
        // width. Inputs reserves the scrollbar gutter on the right.
        val interiorW = if (isOutputs)
            PANEL_W - PANEL_INNER_PAD * 2
        else
            PANEL_W - PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val rowY = interiorY + visibleIdx * ROW_H
            val rowSlice = if (visibleIdx % 2 == 0) NineSlice.ROW_HIGHLIGHT else NineSlice.ROW
            rowSlice.draw(graphics, interiorX, rowY, interiorW, ROW_H)
            if (visibleIdx < VISIBLE_ROWS - 1) {
                NineSlice.SEPARATOR.draw(graphics, interiorX, rowY + ROW_H - 2, interiorW, 3)
            }
            val rowIdx = scroll + visibleIdx
            if (rowIdx >= rows.size) continue
            renderRowContent(graphics, interiorX, interiorW, rowY, rows[rowIdx], isOutputs)
        }

        if (rows.isEmpty()) {
            val emptyText = if (isOutputs) "No Outputs" else "No Inputs"
            val overlayLeft = interiorX
            val overlayTop = interiorY
            val overlayRight = interiorX + interiorW + 1
            val overlayBottom = interiorY + VISIBLE_ROWS * ROW_H - SEPARATOR_OVERLAP + 2
            graphics.fill(overlayLeft, overlayTop, overlayRight, overlayBottom, 0x80000000.toInt())
            val tx = overlayLeft + (overlayRight - overlayLeft - font.width(emptyText)) / 2
            val ty = overlayTop + (overlayBottom - overlayTop - font.lineHeight) / 2
            graphics.drawString(font, emptyText, tx, ty, WHITE)
        }

        // Outputs maxes out at 3 (the recipe's output column) and the panel
        // shows 3 rows, so scrolling never matters - skip the track entirely.
        if (!isOutputs) {
            renderSectionScrollbar(graphics, panelX, panelTop, rows.size, scroll, mouseX, mouseY)
        }
    }

    /** One row in the Inputs / Outputs scroll panel. Carries the full
     *  component-bearing stack so render and labelling pick up the variant's
     *  hover name (e.g. "Potion of Strength") instead of falling back to the
     *  generic itemId display. */
    private data class RowItem(val stack: ItemStack, val count: Int) {
        val itemId: String get() = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
    }

    private fun renderRowContent(
        graphics: GuiGraphicsExtractor,
        interiorX: Int,
        interiorW: Int,
        rowY: Int,
        item: RowItem,
        isOutputs: Boolean,
    ) {
        val slotSize = ROW_ICON_SIZE
        val slotX = interiorX + ROW_ICON_PAD
        val slotY = rowY + (ROW_H - SEPARATOR_OVERLAP - slotSize) / 2
        NineSlice.SLOT.draw(graphics, slotX, slotY, slotSize, slotSize)
        val stack = item.stack.copyWithCount(item.count.coerceIn(1, 99))
        if (!stack.isEmpty) {
            // Render item + decorations at 16×16 directly aligned with the
            // slot so the count overlay reads correctly (same convention as
            // a vanilla inventory slot).
            graphics.renderItem(stack, slotX, slotY)
            graphics.renderItemDecorations(font, stack, slotX, slotY)
        }
        val labelX = slotX + slotSize + 4
        // Right edge for the label: stop short of the trailing swatch / ghost
        // so long names truncate with an ellipsis instead of running under it.
        val labelRight = interiorX + interiorW - ROW_PICKER_RIGHT_PAD - SMALL_SWATCH - 4
        val labelW = (labelRight - labelX).coerceAtLeast(0)
        // Use the stack's hover name so component-bearing variants surface
        // ("Potion of Strength", not just "Potion") in the row label.
        graphics.drawString(
            font, truncateToWidth(item.stack.hoverName.string, labelW),
            labelX,
            rowY + (ROW_H - SEPARATOR_OVERLAP - font.lineHeight) / 2 + 1,
            WHITE,
        )

        if (isOutputs) {
            // Read-only ghost swatch, shares the editable swatch's wool icon
            // and slot frame so the visual matches. A translucent lock glyph
            // sits on top to signal "not clickable" without flattening the
            // channel colour the way the previous gray overlay did.
            val be = entity() ?: return
            val ghostX = interiorX + interiorW - ROW_PICKER_RIGHT_PAD - SMALL_SWATCH
            val ghostY = rowY + (ROW_H - SEPARATOR_OVERLAP - SMALL_SWATCH) / 2
            NineSlice.SLOT.draw(graphics, ghostX, ghostY, SMALL_SWATCH, SMALL_SWATCH)
            val rgb = be.outputChannel.textureDiffuseColor and 0xFFFFFF
            Icons.WHITE_WOOL.drawTinted(graphics, ghostX + 1, ghostY + 1, SMALL_SWATCH - 2, rgb)
            // Lock is 8×8 in the top-left of its 16×16 atlas cell. Centred
            // inside the 10×10 swatch (1 px inset).
            Icons.LOCK.drawTopLeftTinted(graphics, ghostX + 1, ghostY + 1, 8, 8, 0xFFFFFF, alpha = 0.6f)
        }
    }

    private fun renderSectionScrollbar(
        graphics: GuiGraphicsExtractor,
        panelX: Int,
        panelTop: Int,
        rowCount: Int,
        scrollOffset: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val sbX = panelX + PANEL_W - PANEL_INNER_PAD - SCROLL_BAR_W
        val sbY = panelTop + PANEL_INNER_PAD
        val trackH = PANEL_H - PANEL_INNER_PAD * 2
        NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, sbY, SCROLL_BAR_W, trackH)
        if (rowCount > VISIBLE_ROWS) {
            val thumbH = maxOf(12, trackH * VISIBLE_ROWS / rowCount)
            val maxScroll = (rowCount - VISIBLE_ROWS).coerceAtLeast(1)
            val thumbY = sbY + ((trackH - thumbH) * scrollOffset / maxScroll)
            val hovered = mouseX in sbX until sbX + SCROLL_BAR_W && mouseY in sbY until sbY + trackH
            val slice = if (hovered) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            slice.draw(graphics, sbX, thumbY, SCROLL_BAR_W, thumbH)
        }
    }

    // ---- Picker overlay (scrolling dropdown of available recipes) ----

    private fun pickerOverlayBounds(): IntArray {
        return intArrayOf(
            leftPos + PICKER_OVERLAY_X,
            topPos + PICKER_OVERLAY_Y,
            PICKER_OVERLAY_W,
            PICKER_OVERLAY_H,
        )
    }

    private fun renderPickerOverlay(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val (overlayX, overlayY, overlayW, overlayH) = pickerOverlayBounds().toList()
        // Dim only the rest of the GUI (left column + top bar). Right column
        // is replaced by the picker panel itself - clicking anywhere outside
        // the overlay closes it.
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC000000.toInt())
        NineSlice.PANEL_INSET.draw(graphics, overlayX, overlayY, overlayW, overlayH)

        val interiorX = overlayX
        val interiorY = overlayY
        val interiorW = overlayW - SCROLL_BAR_W - SCROLL_BAR_GAP

        if (availableSets.isEmpty()) {
            val text = "No Processing Sets."
            graphics.drawString(
                font,
                Component.literal(text).withStyle(ChatFormatting.GRAY),
                interiorX + (interiorW - font.width(text)) / 2,
                interiorY + (overlayH - font.lineHeight) / 2,
                WHITE,
            )
        }
        for (visibleIdx in 0 until PICKER_VISIBLE_ROWS) {
            val setIdx = pickerScroll + visibleIdx
            if (setIdx >= availableSets.size) break
            val set = availableSets[setIdx]
            val rowY = interiorY + visibleIdx * PICKER_ROW_H
            val hover = mouseX in interiorX..(interiorX + interiorW) &&
                    mouseY in rowY..(rowY + PICKER_ROW_H)
            NineSlice.ROW.draw(graphics, interiorX, rowY, interiorW, PICKER_ROW_H)
            graphics.fill(interiorX, rowY, interiorX + interiorW, rowY + PICKER_ROW_H, 0x40000000)
            if (hover) {
                graphics.fill(interiorX, rowY, interiorX + interiorW, rowY + PICKER_ROW_H, 0x33FFFFFF)
            }
            drawRecipeGrid(graphics, interiorX, rowY, interiorW, PICKER_ROW_H, set)
        }

        val sbX = overlayX + overlayW - SCROLL_BAR_W
        val sbY = overlayY
        val trackH = overlayH
        NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, sbY, SCROLL_BAR_W, trackH)
        if (availableSets.size > PICKER_VISIBLE_ROWS) {
            val thumbH = maxOf(12, trackH * PICKER_VISIBLE_ROWS / availableSets.size)
            val maxScroll = (availableSets.size - PICKER_VISIBLE_ROWS).coerceAtLeast(1)
            val thumbY = sbY + ((trackH - thumbH) * pickerScroll / maxScroll)
            val hovered = mouseX in sbX until sbX + SCROLL_BAR_W && mouseY in sbY until sbY + trackH
            val slice = if (hovered) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            slice.draw(graphics, sbX, thumbY, SCROLL_BAR_W, thumbH)
        }
    }

    private fun renderChannelPickerOverlays(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Header pickers commented out alongside addSectionHeaderPickers.
        // inputsHeaderPicker?.renderOverlay(graphics, mouseX, mouseY)
        // outputsHeaderPicker?.renderOverlay(graphics, mouseX, mouseY)
        // renderOverlay handles BOTH the expanded popup and the collapsed
        // hover tooltip, so it must run every frame regardless of expanded
        // state - the previous `if (expanded)` gate suppressed the swatch
        // hover tooltip entirely.
        for (picker in inputPickers.values) {
            picker.renderOverlay(graphics, mouseX, mouseY)
        }
    }

    // ---- Input handling ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (pickerOpen) {
            return handlePickerClick(event)
        }
        for (picker in listOfNotNull(inputsHeaderPicker, outputsHeaderPicker) + inputPickers.values) {
            if (picker.expanded) {
                if (picker.handleOverlayClick(event.mouseX, event.mouseY)) return true
            }
        }
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // Recipe-panel area: clicking anywhere on it opens the picker.
        val (px, py, pw, ph) = recipePanelBounds().toList()
        if (mx in px until px + pw && my in py until py + ph) {
            playClickSound()
            pickerOpen = true
            pickerScroll = 0
            return true
        }

        // Redstone button (placeholder, no behavior bound yet).
        val (rx, ry, rw, rh) = redstoneButtonBounds().toList()
        if (mx in rx until rx + rw && my in ry until ry + rh) {
            playClickSound()
            return true
        }

        // Unbind button - only fires when bound, otherwise the click swallows
        // silently so the player can't unbind nothing.
        val (bx, by, bw, bh) = unbindButtonBounds().toList()
        if (mx in bx until bx + bw && my in by until by + bh) {
            val be = entity()
            val isBound = be != null && be.processingApiName.isNotEmpty() && findBoundSet() != null
            if (isBound) {
                playClickSound()
                PlatformServices.clientNetworking.sendToServer(
                    ProcessingHandlerUnbindPayload(menu.devicePos)
                )
            }
            return true
        }

        // Help button - opens the Processing Handler guidebook page.
        val (hx, hy, hw, hh) = helpButtonBounds().toList()
        if (mx in hx until hx + hw && my in hy until hy + hh) {
            playClickSound()
            PlatformServices.guidebook.open(PROCESSING_HANDLER_GUIDE_REF)
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun handlePickerClick(event: MouseButtonEvent): Boolean {
        val (overlayX, overlayY, overlayW, overlayH) = pickerOverlayBounds().toList()
        val mxI = event.mouseX.toInt()
        val myI = event.mouseY.toInt()

        // Click outside the right-column overlay closes it - the user's
        // explicit "click the left side to exit" replacement for the old
        // Close button.
        if (mxI !in overlayX..(overlayX + overlayW) || myI !in overlayY..(overlayY + overlayH)) {
            pickerOpen = false
            return true
        }

        val interiorW = overlayW - SCROLL_BAR_W - SCROLL_BAR_GAP
        for (visibleIdx in 0 until PICKER_VISIBLE_ROWS) {
            val setIdx = pickerScroll + visibleIdx
            if (setIdx >= availableSets.size) break
            val rowY = overlayY + visibleIdx * PICKER_ROW_H
            if (mxI in overlayX..(overlayX + interiorW) && myI in rowY..(rowY + PICKER_ROW_H)) {
                playClickSound()
                PlatformServices.clientNetworking.sendToServer(
                    ProcessingHandlerBindPayload(menu.devicePos, availableSets[setIdx].name)
                )
                pickerOpen = false
                return true
            }
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (pickerOpen) {
            val maxScroll = (availableSets.size - PICKER_VISIBLE_ROWS).coerceAtLeast(0)
            pickerScroll = (pickerScroll - scrollY.toInt()).coerceIn(0, maxScroll)
            return true
        }
        val be = entity() ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val mxI = mouseX.toInt()
        val myI = mouseY.toInt()
        if (within(mxI, myI, leftPos + RIGHT_X, topPos + INPUTS_PANEL_Y, PANEL_W, PANEL_H)) {
            val rowCount = be.snapshotInputChannels().size
            val maxScroll = (rowCount - VISIBLE_ROWS).coerceAtLeast(0)
            inputsScroll = (inputsScroll - scrollY.toInt()).coerceIn(0, maxScroll)
            return true
        }
        if (within(mxI, myI, leftPos + RIGHT_X, topPos + OUTPUTS_PANEL_Y, PANEL_W, PANEL_H)) {
            val recipe = findBoundSet()
            val rowCount = recipe?.outputs?.size ?: 0
            val maxScroll = (rowCount - VISIBLE_ROWS).coerceAtLeast(0)
            outputsScroll = (outputsScroll - scrollY.toInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun within(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        mx in x..(x + w) && my in y..(y + h)

    // ---- Helpers ----

    private fun findBoundSet(): ProcessingHandlerOpenData.AvailableSet? {
        val be = entity() ?: return null
        if (be.processingApiName.isEmpty()) return null
        val cached = boundSet
        if (cached != null && cached.name == be.processingApiName) return cached
        return null
    }

    private fun stackOf(itemId: String, count: Int = 1): ItemStack {
        val id = Identifier.tryParse(itemId) ?: return ItemStack.EMPTY
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return ItemStack.EMPTY
        return ItemStack(item, count.coerceIn(1, 99))
    }

    private fun displayName(idOrName: String): String =
        idOrName.substringAfter(':').replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

    /** Truncate [str] with a trailing "..." so the rendered text stays under
     *  [maxWidth] px. Long mod item names (e.g. "Waxed weathered copper golem
     *  statue") otherwise overflow row interiors and run under the trailing
     *  channel swatch. */
    private fun truncateToWidth(str: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(str) <= maxWidth) return str
        val ellipsis = "..."
        val ellipsisW = font.width(ellipsis)
        if (maxWidth <= ellipsisW) return ""
        return font.plainSubstrByWidth(str, maxWidth - ellipsisW) + ellipsis
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
        )
    }
}
