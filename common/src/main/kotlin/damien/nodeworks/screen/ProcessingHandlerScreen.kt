package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
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
        private const val IMAGE_W = 220
        private const val IMAGE_H = 252
        private const val OUTER_PAD = 4
        private const val ICON_SIZE = 16
        private const val SLOT_SIZE = 18

        /** Title bar at the top of the GUI, mirrors NetworkController +
         *  InventoryTerminal. Drawn via [NineSlice.drawTitleBar]. */
        private const val TOP_BAR_H = 20

        // Recipe panel: 3×3 input grid + arrow + 3 output column. The panel
        // doubles as the picker entry point - clicking it opens the dropdown.
        // The hover effect runs even when bound so it always reads as
        // interactive.
        private const val RECIPE_PANEL_Y = TOP_BAR_H + 4
        private const val RECIPE_GRID_INPUT_W = 3 * SLOT_SIZE
        private const val RECIPE_GRID_OUTPUT_W = SLOT_SIZE
        private const val RECIPE_GRID_H = 3 * SLOT_SIZE
        private const val RECIPE_ARROW_W = 24
        private const val RECIPE_PANEL_INNER_PAD = 2
        private const val RECIPE_PANEL_H = RECIPE_GRID_H + RECIPE_PANEL_INNER_PAD * 2 + 6

        /** Unbind-toggle in the top-right corner of the bound recipe panel.
         *  StorageCard uses the same 11×11 NineSlice.BUTTON for its delete
         *  glyph so the visual matches. */
        private const val UNBIND_BTN_SIZE = 11

        // Section scrollboxes (Inputs / Outputs). Mirror StorageCard's rule
        // list: PANEL_INSET, ROW / ROW_HIGHLIGHT alternating stripes, SLOT-
        // backed item icon, scrollbar on the right (inputs only - outputs
        // can never overflow 3 rows so the track is hidden).
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 3
        private const val PANEL_INNER_PAD = 2
        private const val PANEL_W = IMAGE_W - OUTER_PAD * 2
        private const val PANEL_H = VISIBLE_ROWS * ROW_H + PANEL_INNER_PAD * 2
        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2
        /** Section header row (label + channel picker). 16 px tall so the
         *  16×16 swatch fits cleanly inside without crowding the scrollbox
         *  below it. */
        private const val SECTION_HEADER_H = 16
        private const val SEPARATOR_OVERLAP = 2
        private const val ROW_ICON_SIZE = 16
        private const val ROW_ICON_PAD = 2

        private const val INPUTS_HEADER_Y = RECIPE_PANEL_Y + RECIPE_PANEL_H + 4
        private const val INPUTS_PANEL_Y = INPUTS_HEADER_Y + SECTION_HEADER_H
        private const val OUTPUTS_HEADER_Y = INPUTS_PANEL_Y + PANEL_H + 4
        private const val OUTPUTS_PANEL_Y = OUTPUTS_HEADER_Y + SECTION_HEADER_H

        // Picker overlay (full-screen popup from the recipe panel). Each row
        // is the same 3×3 → 3 grid the bound recipe panel renders, so the
        // player sees exactly what they're picking.
        private const val PICKER_ROW_H = RECIPE_GRID_H + 6
        private const val PICKER_VISIBLE_ROWS = 3
        private const val PICKER_PANEL_INNER_PAD = 2
        private const val PICKER_PANEL_H = PICKER_VISIBLE_ROWS * PICKER_ROW_H + PICKER_PANEL_INNER_PAD * 2
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

    private val inputPickers = mutableMapOf<String, ChannelPickerWidget>()
    private var inputsHeaderPicker: ChannelPickerWidget? = null
    private var outputsHeaderPicker: ChannelPickerWidget? = null

    private var lastBoundApiName: String = ""
    private var lastInputItemIds: List<String> = emptyList()
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
        addSectionHeaderPickers(be)
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
        val interiorX = leftPos + OUTER_PAD + PANEL_INNER_PAD
        val interiorY = topPos + INPUTS_PANEL_Y + PANEL_INNER_PAD
        val interiorW = PANEL_W - PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val pickerX = interiorX + interiorW - ROW_PICKER_RIGHT_PAD - SMALL_SWATCH
        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val rowIdx = inputsScroll + visibleIdx
            if (rowIdx >= inputChannels.size) break
            val (itemId, color) = inputChannels[rowIdx]
            val rowY = interiorY + visibleIdx * ROW_H
            val pickerY = rowY + (ROW_H - SEPARATOR_OVERLAP - SMALL_SWATCH) / 2
            val picker = ChannelPickerWidget(
                x = pickerX,
                y = pickerY,
                initialColor = color,
                swatchSize = SMALL_SWATCH,
                onChange = { newColor ->
                    if (newColor != null) {
                        PlatformServices.clientNetworking.sendToServer(
                            ProcessingHandlerSetInputChannelPayload(menu.devicePos, itemId, newColor.id)
                        )
                    }
                },
            )
            inputPickers[itemId] = picker
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
        for ((itemId, picker) in inputPickers) {
            val color = inputChannels[itemId] ?: continue
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
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        // TOP_BAR header tinted with the parent network's color, mirrors
        // NetworkController + InventoryTerminal so the GUI's top-row chrome
        // reads consistent with the rest of the mod. Color resolved from the
        // BE's network id (back face's network); falls back to neutral gray
        // when the Handler isn't on a network.
        val networkColor = entity()?.networkId?.let {
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(it)
        } ?: 0x888888
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor)
        drawRecipePanel(graphics, mouseX, mouseY)
        drawSectionHeader(graphics, "Inputs", INPUTS_HEADER_Y)
        drawSectionScrollbox(graphics, INPUTS_PANEL_Y, isOutputs = false, mouseX, mouseY)
        drawSectionHeader(graphics, "Outputs", OUTPUTS_HEADER_Y)
        drawSectionScrollbox(graphics, OUTPUTS_PANEL_Y, isOutputs = true, mouseX, mouseY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        renderChannelPickerOverlays(graphics, mouseX, mouseY)
        if (pickerOpen) renderPickerOverlay(graphics, mouseX, mouseY)
    }

    // ---- Recipe panel (clickable; opens picker, hosts unbind [x]) ----

    private fun recipePanelBounds(): IntArray {
        return intArrayOf(leftPos + OUTER_PAD, topPos + RECIPE_PANEL_Y, PANEL_W, RECIPE_PANEL_H)
    }

    private fun unbindButtonBounds(): IntArray {
        val (px, py, pw, _) = recipePanelBounds().toList()
        val bx = px + pw - UNBIND_BTN_SIZE - 4
        val by = py + 4
        return intArrayOf(bx, by, UNBIND_BTN_SIZE, UNBIND_BTN_SIZE)
    }

    private fun drawRecipePanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val (panelX, panelY, panelW, panelH) = recipePanelBounds().toList()
        NineSlice.PANEL_INSET.draw(graphics, panelX, panelY, panelW, panelH)

        val be = entity()
        val recipe = if (be != null) findBoundSet() else null
        val isBound = be != null && be.processingApiName.isNotEmpty() && recipe != null
        val hovering = mouseX in panelX until panelX + panelW &&
            mouseY in panelY until panelY + panelH && !pickerOpen

        drawRecipeGrid(graphics, panelX, panelY, panelW, panelH, recipe)

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
                "Bound recipe is missing, click to pick"
            } else {
                "Click to pick recipe"
            }
            val tx = panelX + (panelW - font.width(prompt)) / 2
            val ty = panelY + (panelH - font.lineHeight) / 2
            graphics.drawString(font, prompt, tx, ty, WHITE)
            return
        }

        // Bound: persistent hover overlay so the panel always reads as
        // interactive (clicking opens the picker to change the binding).
        if (hovering) {
            // Skip the [x] unbind hit-rect from the hover overlay so the
            // button's own hover state isn't fighting the panel-level tint.
            val (bx, by, bw, bh) = unbindButtonBounds().toList()
            val overlayLeft = interiorX
            val overlayTop = interiorY
            val overlayBottom = interiorBottom
            // Top strip (above the [x])
            graphics.fill(overlayLeft, overlayTop, interiorRight, by, BOUND_HOVER_OVERLAY)
            // Strip beside the [x]
            graphics.fill(overlayLeft, by, bx, by + bh, BOUND_HOVER_OVERLAY)
            // Bottom strip (below the [x])
            graphics.fill(overlayLeft, by + bh, interiorRight, overlayBottom, BOUND_HOVER_OVERLAY)
        }

        val (bx, by, bw, bh) = unbindButtonBounds().toList()
        val unbindHover = mouseX in bx until bx + bw && mouseY in by until by + bh
        (if (unbindHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, bx, by, bw, bh)
        graphics.drawString(
            font, "x",
            bx + (bw - font.width("x")) / 2 + 1,
            by + 1,
            WHITE,
        )
    }

    /** Draw the 3×3 input grid, arrow, and 3-output column inside the given
     *  bounds. When [recipe] is null the grid renders empty (slot frames only)
     *  so the panel still reads as a recipe-shaped area pre-binding. */
    private fun drawRecipeGrid(
        graphics: GuiGraphicsExtractor,
        panelX: Int,
        panelY: Int,
        panelW: Int,
        panelH: Int,
        recipe: ProcessingHandlerOpenData.AvailableSet?,
    ) {
        val blockW = RECIPE_GRID_INPUT_W + RECIPE_ARROW_W + RECIPE_GRID_OUTPUT_W
        val gridX = panelX + (panelW - blockW) / 2
        val gridY = panelY + (panelH - RECIPE_GRID_H) / 2
        val outputX = gridX + RECIPE_GRID_INPUT_W + RECIPE_ARROW_W

        for (row in 0..2) {
            for (col in 0..2) {
                NineSlice.SLOT.draw(graphics, gridX + col * SLOT_SIZE, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
            }
        }
        for (row in 0..2) {
            NineSlice.SLOT.draw(graphics, outputX, gridY + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)
        }

        val arrowX = gridX + RECIPE_GRID_INPUT_W + (RECIPE_ARROW_W - 16) / 2
        val arrowY = gridY + (RECIPE_GRID_H - 16) / 2
        Icons.ARROW_RIGHT.draw(graphics, arrowX, arrowY)

        if (recipe == null) return
        for ((idx, pair) in recipe.inputs.withIndex()) {
            if (idx >= 9) break
            val (id, count) = pair
            val col = idx % 3
            val row = idx / 3
            val sx = gridX + col * SLOT_SIZE + 1
            val sy = gridY + row * SLOT_SIZE + 1
            val stack = stackOf(id, count)
            graphics.renderItem(stack, sx, sy)
            graphics.renderItemDecorations(font, stack, sx, sy)
        }
        for ((idx, pair) in recipe.outputs.withIndex()) {
            if (idx >= 3) break
            val (id, count) = pair
            val sx = outputX + 1
            val sy = gridY + idx * SLOT_SIZE + 1
            val stack = stackOf(id, count)
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
        val rows: List<RowItem> = when {
            recipeMissing -> emptyList()
            isOutputs -> findBoundSet()?.outputs?.map { RowItem(it.first, it.second) } ?: emptyList()
            else -> be.snapshotInputChannels().keys.toList().map { RowItem(it, 1) }
        }
        val scroll = if (isOutputs) outputsScroll else inputsScroll

        val panelX = leftPos + OUTER_PAD
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

    private data class RowItem(val itemId: String, val count: Int)

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
        val stack = stackOf(item.itemId, item.count)
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
        graphics.drawString(
            font, truncateToWidth(displayName(item.itemId), labelW),
            labelX,
            rowY + (ROW_H - SEPARATOR_OVERLAP - font.lineHeight) / 2 + 1,
            WHITE,
        )

        if (isOutputs) {
            // Read-only ghost swatch, shares the editable swatch's wool icon
            // and slot frame so the visual matches, then tinted with a
            // translucent gray to read as not-clickable.
            val be = entity() ?: return
            val ghostX = interiorX + interiorW - ROW_PICKER_RIGHT_PAD - SMALL_SWATCH
            val ghostY = rowY + (ROW_H - SEPARATOR_OVERLAP - SMALL_SWATCH) / 2
            NineSlice.SLOT.draw(graphics, ghostX, ghostY, SMALL_SWATCH, SMALL_SWATCH)
            val rgb = be.outputChannel.textureDiffuseColor and 0xFFFFFF
            Icons.WHITE_WOOL.drawTinted(graphics, ghostX + 1, ghostY + 1, SMALL_SWATCH - 2, rgb)
            graphics.fill(ghostX, ghostY, ghostX + SMALL_SWATCH, ghostY + SMALL_SWATCH, GHOST_GRAY_OVERLAY)
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
        val overlayX = leftPos + 4
        val overlayY = topPos + 4
        val overlayW = imageWidth - 8
        // Just-tall-enough: title row + panel + bottom margin. The previous
        // full-height overlay had ~30 px of dead space below the panel.
        val overlayH = 22 + PICKER_PANEL_H + 6
        return intArrayOf(overlayX, overlayY, overlayW, overlayH)
    }

    private fun renderPickerOverlay(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val (overlayX, overlayY, overlayW, overlayH) = pickerOverlayBounds().toList()
        // Dim everything outside the overlay so the rest of the GUI reads as
        // disabled while the picker is up.
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC000000.toInt())
        NineSlice.WINDOW_FRAME.draw(graphics, overlayX, overlayY, overlayW, overlayH)
        graphics.drawString(
            font,
            Component.literal("Pick a Processing Set").withStyle(ChatFormatting.WHITE),
            overlayX + OUTER_PAD + 2, overlayY + 6, WHITE,
        )
        val closeX = overlayX + overlayW - OUTER_PAD - PICKER_BTN_W
        val closeY = overlayY + 4
        val closeHover = mouseX in closeX until closeX + PICKER_BTN_W && mouseY in closeY until closeY + PICKER_BTN_H
        (if (closeHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, closeX, closeY, PICKER_BTN_W, PICKER_BTN_H)
        graphics.drawString(font, "Close", closeX + (PICKER_BTN_W - font.width("Close")) / 2, closeY + 3, WHITE)

        val panelX = overlayX + OUTER_PAD
        val panelY = overlayY + 22
        val panelW = overlayW - OUTER_PAD * 2
        NineSlice.PANEL_INSET.draw(graphics, panelX, panelY, panelW, PICKER_PANEL_H)
        val interiorX = panelX + PICKER_PANEL_INNER_PAD
        val interiorY = panelY + PICKER_PANEL_INNER_PAD
        val interiorW = panelW - PICKER_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

        if (availableSets.isEmpty()) {
            val text = "No unclaimed Processing Sets."
            graphics.drawString(
                font,
                Component.literal(text).withStyle(ChatFormatting.GRAY),
                interiorX + (interiorW - font.width(text)) / 2,
                interiorY + (PICKER_PANEL_H - PICKER_PANEL_INNER_PAD * 2 - font.lineHeight) / 2,
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
            // All rows use the darker NineSlice.ROW base; hover gets a small
            // white tint so the cursor target still reads. The previous
            // alternating-stripe pattern was too bright against the recipe
            // grids each row contains.
            NineSlice.ROW.draw(graphics, interiorX, rowY, interiorW, PICKER_ROW_H)
            graphics.fill(interiorX, rowY, interiorX + interiorW, rowY + PICKER_ROW_H, 0x40000000)
            if (hover) {
                graphics.fill(interiorX, rowY, interiorX + interiorW, rowY + PICKER_ROW_H, 0x33FFFFFF)
            }
            // Each row is a 3×3 → 3 grid, identical to the bound recipe panel
            // so what the player picks visually matches what they get.
            drawRecipeGrid(graphics, interiorX, rowY, interiorW, PICKER_ROW_H, set)
        }

        val sbX = panelX + panelW - PICKER_PANEL_INNER_PAD - SCROLL_BAR_W
        val sbY = panelY + PICKER_PANEL_INNER_PAD
        val trackH = PICKER_PANEL_H - PICKER_PANEL_INNER_PAD * 2
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
        inputsHeaderPicker?.let { if (it.expanded) it.renderOverlay(graphics, mouseX, mouseY) }
        outputsHeaderPicker?.let { if (it.expanded) it.renderOverlay(graphics, mouseX, mouseY) }
        for (picker in inputPickers.values) {
            if (picker.expanded) picker.renderOverlay(graphics, mouseX, mouseY)
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

        // Recipe-panel area: [x] inside it unbinds when bound; clicking
        // anywhere else opens the picker.
        val (px, py, pw, ph) = recipePanelBounds().toList()
        if (mx in px until px + pw && my in py until py + ph) {
            val be = entity()
            val isBound = be != null && be.processingApiName.isNotEmpty() && findBoundSet() != null
            if (isBound) {
                val (bx, by, bw, bh) = unbindButtonBounds().toList()
                if (mx in bx until bx + bw && my in by until by + bh) {
                    playClickSound()
                    PlatformServices.clientNetworking.sendToServer(
                        ProcessingHandlerUnbindPayload(menu.devicePos)
                    )
                    return true
                }
            }
            playClickSound()
            pickerOpen = true
            pickerScroll = 0
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun handlePickerClick(event: MouseButtonEvent): Boolean {
        val (overlayX, overlayY, overlayW, overlayH) = pickerOverlayBounds().toList()
        val mxI = event.mouseX.toInt()
        val myI = event.mouseY.toInt()

        val closeX = overlayX + overlayW - OUTER_PAD - PICKER_BTN_W
        val closeY = overlayY + 4
        if (mxI in closeX..(closeX + PICKER_BTN_W) && myI in closeY..(closeY + PICKER_BTN_H)) {
            playClickSound()
            pickerOpen = false
            return true
        }

        val panelX = overlayX + OUTER_PAD
        val panelY = overlayY + 22
        val panelW = overlayW - OUTER_PAD * 2
        val interiorX = panelX + PICKER_PANEL_INNER_PAD
        val interiorY = panelY + PICKER_PANEL_INNER_PAD
        val interiorW = panelW - PICKER_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        for (visibleIdx in 0 until PICKER_VISIBLE_ROWS) {
            val setIdx = pickerScroll + visibleIdx
            if (setIdx >= availableSets.size) break
            val rowY = interiorY + visibleIdx * PICKER_ROW_H
            if (mxI in interiorX..(interiorX + interiorW) && myI in rowY..(rowY + PICKER_ROW_H)) {
                playClickSound()
                PlatformServices.clientNetworking.sendToServer(
                    ProcessingHandlerBindPayload(menu.devicePos, availableSets[setIdx].name)
                )
                pickerOpen = false
                return true
            }
        }
        if (mxI !in overlayX..(overlayX + overlayW) || myI !in overlayY..(overlayY + overlayH)) {
            pickerOpen = false
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
        if (within(mxI, myI, leftPos + OUTER_PAD, topPos + INPUTS_PANEL_Y, PANEL_W, PANEL_H)) {
            val rowCount = be.snapshotInputChannels().size
            val maxScroll = (rowCount - VISIBLE_ROWS).coerceAtLeast(0)
            inputsScroll = (inputsScroll - scrollY.toInt()).coerceIn(0, maxScroll)
            return true
        }
        if (within(mxI, myI, leftPos + OUTER_PAD, topPos + OUTPUTS_PANEL_Y, PANEL_W, PANEL_H)) {
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
