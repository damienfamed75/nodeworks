package damien.nodeworks.screen

import damien.nodeworks.block.entity.MultiProcessingHandlerBlockEntity
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.network.MultiProcessingHandlerSetChannelPayload
import damien.nodeworks.network.MultiProcessingHandlerSetParallelPayload
import damien.nodeworks.network.MultiProcessingHandlerTogglePayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.RecipeGridRenderer
import net.minecraft.ChatFormatting
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
 * Settings screen for the Multi-Processing Handler. One list of every Processing Set
 * on the parent network, each row an inline `inputs -> outputs` strip with a checkbox.
 * Checked rows sort to the top, a search box filters by set or item name, and a
 * counter shows how many of the cap are bound. Rows a script or another handler
 * already owns, and every unchecked row once the cap is reached, are greyed with the
 * reason on hover. Below the list sit the input and output channel swatches.
 *
 * The bound list and channels are read live from the client block entity each frame.
 * The set list comes from the menu and is rebuilt only when a state sync arrives.
 */
class MultiProcessingHandlerScreen(
    menu: MultiProcessingHandlerMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<MultiProcessingHandlerMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 176
        private const val OUTER_PAD = 6
        private const val TOP_BAR_H = 20

        private const val SEARCH_Y = TOP_BAR_H + 4
        private const val SEARCH_H = 14
        private const val COUNTER_W = 44
        private const val SEARCH_X = OUTER_PAD
        private const val SEARCH_W = IMAGE_W - OUTER_PAD * 2 - COUNTER_W - 4

        private const val LIST_Y = SEARCH_Y + SEARCH_H + 4
        private const val LIST_W = IMAGE_W - OUTER_PAD * 2
        /** A row holds the set drawn as the editor shows it, a 3×3 grid and an output column. */
        private const val ROW_H = RecipeGridRenderer.HORIZONTAL_H + 6
        private const val VISIBLE_ROWS = 3
        private const val PANEL_INNER_PAD = 2
        private const val LIST_H = VISIBLE_ROWS * ROW_H + PANEL_INNER_PAD * 2
        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2
        private const val SEPARATOR_OVERLAP = 2
        private const val CHECK_SIZE = 12
        private const val CHECK_PAD = 3

        private const val FOOTER_Y = LIST_Y + LIST_H + 4
        /** A label line, a gap, and the toggle row beneath it. */
        private const val FOOTER_H = 9 + 2 + 16
        private const val BUTTON_SIZE = 14
        /** The Processing Set editor's toggle, same slices and size. */
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16
        private const val TOGGLE_GAP = 6
        private const val IMAGE_H = FOOTER_Y + FOOTER_H + OUTER_PAD

        private const val LABEL_GRAY = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val ORANGE = 0xFFFFAA00.toInt()
        private const val MISSING_RED = 0xFFCC8888.toInt()
        private const val DISABLED_OVERLAY = 0x90000000.toInt()
        private const val HOVER_OVERLAY = 0x22FFFFFF

        private const val GUIDE_REF = "nodeworks:items-blocks/multi_processing_handler.md"
    }

    /** One set with its search and sort text prepared once, not per frame. */
    private class Row(val entry: MultiProcessingHandlerOpenData.SetEntry, val searchText: String, val sortKey: String)

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX = 0
    private var pendingTooltipY = 0

    private lateinit var searchBox: EditBox
    /** Survives a resize, which rebuilds the widgets. */
    private var searchText = ""
    private var inputPicker: ChannelPickerWidget? = null
    private var outputPicker: ChannelPickerWidget? = null

    private var scroll = 0
    private var draggingScrollbar = false
    private var rows: List<Row> = emptyList()
    private var visible: List<Row> = emptyList()
    private var rowsVersion = -1
    private var lastBound: List<String> = emptyList()
    /** The bound list as read once at the start of the frame. */
    private var frameBound: List<String> = emptyList()
    private var lastSearch: String? = null

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        clearWidgets()
        searchBox = EditBox(
            font, leftPos + SEARCH_X, topPos + SEARCH_Y, SEARCH_W, SEARCH_H, Component.literal("Search"),
        ).also {
            it.setMaxLength(64)
            it.setBordered(true)
            it.setTextColor(WHITE)
            it.setHint(Component.literal("Search recipes").withStyle(ChatFormatting.DARK_GRAY))
            it.value = searchText
            it.setResponder { text -> searchText = text }
        }
        addRenderableWidget(searchBox)

        val be = entity()
        val pickerY = topPos + FOOTER_Y + FOOTER_H - TOGGLE_H
        val input = ChannelPickerWidget(
            x = leftPos + OUTER_PAD + font.width("In") + 4,
            y = pickerY,
            initialColor = be?.inputChannel ?: DyeColor.BLUE,
            tooltipFormatter = { c -> "Inputs go to ${pretty(c)} Storage Cards" },
            onChange = { color ->
                if (color != null) {
                    PlatformServices.clientNetworking.sendToServer(
                        MultiProcessingHandlerSetChannelPayload(menu.devicePos, output = false, channelId = color.id)
                    )
                }
            },
        )
        inputPicker = input
        addRenderableWidget(input)

        val output = ChannelPickerWidget(
            x = input.x + ChannelPickerWidget.SWATCH + 12 + font.width("Out") + 4,
            y = pickerY,
            initialColor = be?.outputChannel ?: DyeColor.RED,
            tooltipFormatter = { c -> "Outputs come from ${pretty(c)} Storage Cards" },
            onChange = { color ->
                if (color != null) {
                    PlatformServices.clientNetworking.sendToServer(
                        MultiProcessingHandlerSetChannelPayload(menu.devicePos, output = true, channelId = color.id)
                    )
                }
            },
        )
        outputPicker = output
        addRenderableWidget(output)
    }

    private fun entity(): MultiProcessingHandlerBlockEntity? {
        val level = Minecraft.getInstance().level ?: return null
        return level.getBlockEntity(menu.devicePos) as? MultiProcessingHandlerBlockEntity
    }

    private fun pretty(color: DyeColor): String =
        color.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    // ---- State ----

    /** The bound list the screen trusts: the block entity once it has synced,
     *  otherwise what the server sent with the menu. */
    private fun boundNames(): List<String> = entity()?.boundSetNames() ?: menu.bound

    private fun syncFromState() {
        if (menu.version != rowsVersion) {
            rowsVersion = menu.version
            rows = menu.sets.map { entry ->
                val names = (entry.set.outputs + entry.set.inputs).map { it.stack.hoverName.string }
                val output = entry.set.outputs.firstOrNull()?.stack?.hoverName?.string ?: entry.set.name
                Row(entry, (names + entry.set.name).joinToString(" ").lowercase(), output.lowercase())
            }
            lastSearch = null
        }
        val bound = boundNames()
        frameBound = bound
        if (bound != lastBound || searchText != lastSearch) {
            lastBound = bound
            lastSearch = searchText
            rebuildVisible(bound)
        }
        val be = entity() ?: return
        inputPicker?.let { if (!it.expanded && it.currentColor != be.inputChannel) it.setColor(be.inputChannel) }
        outputPicker?.let { if (!it.expanded && it.currentColor != be.outputChannel) it.setColor(be.outputChannel) }
    }

    /** Checked rows first, then by output name, so a bank's bound recipes read as one
     *  block at the top and the rest stay findable. */
    private fun rebuildVisible(bound: List<String>) {
        val query = searchText.trim().lowercase()
        val boundSet = bound.toHashSet()
        visible = rows
            // A missing set's row only exists to be unchecked, so it goes once it is.
            .filter { it.entry.set.inputs.isNotEmpty() || it.entry.set.outputs.isNotEmpty() || it.entry.set.name in boundSet }
            .filter { query.isEmpty() || it.searchText.contains(query) }
            .sortedWith(compareBy({ it.entry.set.name !in boundSet }, { it.sortKey }, { it.entry.set.name }))
        scroll = scroll.coerceIn(0, maxScroll())
    }

    private fun maxScroll(): Int = (visible.size - VISIBLE_ROWS).coerceAtLeast(0)

    // ---- Render ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
        syncFromState()
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        val networkColor = entity()?.networkId?.let {
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(it)
        } ?: 0x888888
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor)
        drawCounter(graphics)
        drawList(graphics, mouseX, mouseY)
        drawFooter(graphics, mouseX, mouseY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        inputPicker?.renderOverlay(graphics, mouseX, mouseY)
        outputPicker?.renderOverlay(graphics, mouseX, mouseY)
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    private fun drawCounter(graphics: GuiGraphicsExtractor) {
        val cap = menu.maxSets
        val text = "${frameBound.size} / $cap"
        val x = leftPos + IMAGE_W - OUTER_PAD - font.width(text)
        val y = topPos + SEARCH_Y + (SEARCH_H - font.lineHeight) / 2 + 1
        graphics.drawString(font, text, x, y, if (frameBound.size >= cap) ORANGE else WHITE)
    }

    private fun listInterior(): IntArray {
        val x = leftPos + OUTER_PAD + PANEL_INNER_PAD
        val y = topPos + LIST_Y + PANEL_INNER_PAD
        val w = LIST_W - PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        return intArrayOf(x, y, w, VISIBLE_ROWS * ROW_H)
    }

    private fun drawList(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panelX = leftPos + OUTER_PAD
        val panelY = topPos + LIST_Y
        NineSlice.PANEL_INSET.draw(graphics, panelX, panelY, LIST_W, LIST_H)
        val (interiorX, interiorY, interiorW, _) = listInterior().toList()

        val bound = frameBound.toHashSet()
        val cap = menu.maxSets
        val full = bound.size >= cap

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val rowY = interiorY + visibleIdx * ROW_H
            (if (visibleIdx % 2 == 0) NineSlice.ROW_HIGHLIGHT else NineSlice.ROW).draw(graphics, interiorX, rowY, interiorW, ROW_H)
            if (visibleIdx < VISIBLE_ROWS - 1) {
                NineSlice.SEPARATOR.draw(graphics, interiorX, rowY + ROW_H - 2, interiorW, 3)
            }
            val idx = scroll + visibleIdx
            if (idx >= visible.size) continue
            val row = visible[idx]
            val name = row.entry.set.name
            val checked = name in bound
            val reason = when {
                row.entry.claimedBy.isNotEmpty() -> "Handled by ${row.entry.claimedBy}"
                !checked && full -> "Limit reached, $cap of $cap bound"
                else -> null
            }
            val rowBottom = rowY + ROW_H - SEPARATOR_OVERLAP

            val boxX = interiorX + CHECK_PAD
            val boxY = rowY + (ROW_H - SEPARATOR_OVERLAP - CHECK_SIZE) / 2
            NineSlice.SLOT.draw(graphics, boxX, boxY, CHECK_SIZE, CHECK_SIZE)
            if (checked) Icons.CHECKMARK.draw(graphics, boxX + 1, boxY + 1, CHECK_SIZE - 2)

            val gridX = boxX + CHECK_SIZE + 4
            val textY = rowY + (ROW_H - SEPARATOR_OVERLAP - font.lineHeight) / 2 + 1
            val missing = row.entry.set.inputs.isEmpty() && row.entry.set.outputs.isEmpty()
            if (missing) {
                graphics.drawString(font, "Recipe missing", gridX + 2, textY, MISSING_RED)
            } else {
                RecipeGridRenderer.drawHorizontal(
                    graphics, font, gridX, rowY, RecipeGridRenderer.HORIZONTAL_W, ROW_H - SEPARATOR_OVERLAP, row.entry.set,
                )
            }

            val hover = mouseX in interiorX until interiorX + interiorW && mouseY in rowY until rowBottom
            if (reason != null) {
                graphics.fill(interiorX, rowY, interiorX + interiorW, rowBottom, DISABLED_OVERLAY)
                if (hover) queueTooltip(mouseX, mouseY, reason)
            } else if (hover) {
                graphics.fill(interiorX, rowY, interiorX + interiorW, rowBottom, HOVER_OVERLAY)
                queueTooltip(
                    mouseX, mouseY,
                    when {
                        missing -> "No longer on the network, uncheck to drop it"
                        checked -> "Click to stop handling"
                        else -> "Click to handle"
                    },
                )
            }
        }

        if (visible.isEmpty()) {
            val text = if (rows.isEmpty()) "No Processing Sets." else "No matches."
            val bottom = interiorY + VISIBLE_ROWS * ROW_H - SEPARATOR_OVERLAP + 2
            graphics.fill(interiorX, interiorY, interiorX + interiorW + 1, bottom, 0x80000000.toInt())
            graphics.drawString(
                font, text,
                interiorX + (interiorW - font.width(text)) / 2,
                interiorY + (bottom - interiorY - font.lineHeight) / 2,
                WHITE,
            )
        }

        val (sbX, sbY, sbW, trackH) = scrollbarTrack().toList()
        NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, sbY, sbW, trackH)
        if (visible.size > VISIBLE_ROWS) {
            val thumbH = thumbHeight()
            val thumbY = sbY + (trackH - thumbH) * scroll / maxScroll().coerceAtLeast(1)
            val hovered = draggingScrollbar || (mouseX in sbX until sbX + sbW && mouseY in sbY until sbY + trackH)
            (if (hovered) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB).draw(graphics, sbX, thumbY, sbW, thumbH)
        }
    }

    private fun scrollbarTrack(): IntArray = intArrayOf(
        leftPos + OUTER_PAD + LIST_W - PANEL_INNER_PAD - SCROLL_BAR_W,
        topPos + LIST_Y + PANEL_INNER_PAD,
        SCROLL_BAR_W,
        LIST_H - PANEL_INNER_PAD * 2,
    )

    private fun thumbHeight(): Int {
        val trackH = LIST_H - PANEL_INNER_PAD * 2
        return maxOf(12, trackH * VISIBLE_ROWS / visible.size.coerceAtLeast(1))
    }

    /** Puts the thumb's centre at [mouseY], for a click on the track or a drag. */
    private fun scrollToThumbCentre(mouseY: Int) {
        val max = maxScroll()
        if (max <= 0) return
        val (_, sbY, _, trackH) = scrollbarTrack().toList()
        val range = trackH - thumbHeight()
        if (range <= 0) return
        val rel = (mouseY - sbY - thumbHeight() / 2).toFloat() / range
        scroll = (rel * max).toInt().coerceIn(0, max)
    }

    /** Drops the search field's focus. The screen-level tracker is cleared too when it
     *  points here, or vanilla's same-target check would leave the field unclickable. */
    private fun unfocusSearchBox() {
        if (!searchBox.isFocused) return
        if (focused === searchBox) setFocused(null) else searchBox.isFocused = false
    }

    private fun drawFooter(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rowY = topPos + FOOTER_Y + FOOTER_H - TOGGLE_H
        val textY = rowY + (TOGGLE_H - font.lineHeight) / 2 + 1
        graphics.drawString(font, "In", leftPos + OUTER_PAD, textY, LABEL_GRAY)
        inputPicker?.let { graphics.drawString(font, "Out", it.x + ChannelPickerWidget.SWATCH + 12, textY, LABEL_GRAY) }

        // Parallel toggle with its label above, the Processing Set editor's control.
        val parallel = entity()?.parallel == true
        val (tx, ty, tw, th) = parallelToggleBounds().toList()
        val label = "Parallel"
        graphics.drawString(font, label, tx + (tw - font.width(label)) / 2, topPos + FOOTER_Y, LABEL_GRAY)
        (if (parallel) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE).draw(graphics, tx, ty, tw, th)
        if (mouseX in tx until tx + tw && mouseY in ty until ty + th) {
            queueTooltip(
                mouseX, mouseY,
                if (parallel) "Several recipes may run through this handler at once"
                else "One recipe at a time, other crafts wait or run elsewhere",
            )
        }

        val (hx, hy, hw, hh) = helpButtonBounds().toList()
        val helpHover = mouseX in hx until hx + hw && mouseY in hy until hy + hh
        (if (helpHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, hx, hy, hw, hh)
        Icons.QUESTION_9.drawTopLeft(graphics, hx + (hw - 9) / 2, hy + (hh - 9) / 2, 9, 9)
        if (helpHover) queueTooltip(mouseX, mouseY, "Open guidebook")
    }

    private fun helpButtonBounds(): IntArray {
        val x = leftPos + IMAGE_W - OUTER_PAD - BUTTON_SIZE
        val y = topPos + FOOTER_Y + FOOTER_H - TOGGLE_H + (TOGGLE_H - BUTTON_SIZE) / 2
        return intArrayOf(x, y, BUTTON_SIZE, BUTTON_SIZE)
    }

    private fun parallelToggleBounds(): IntArray {
        val x = leftPos + IMAGE_W - OUTER_PAD - BUTTON_SIZE - TOGGLE_GAP - TOGGLE_W
        val y = topPos + FOOTER_Y + FOOTER_H - TOGGLE_H
        return intArrayOf(x, y, TOGGLE_W, TOGGLE_H)
    }

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (picker in listOfNotNull(inputPicker, outputPicker)) {
            if (picker.expanded && picker.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // A click anywhere but the search box gives the keyboard back to the screen.
        val overSearch = mx in searchBox.x until searchBox.x + searchBox.width &&
            my in searchBox.y until searchBox.y + searchBox.height
        if (!overSearch) unfocusSearchBox()
        // Right click on the search box clears it.
        if (overSearch && event.buttonNum == 1) {
            searchBox.value = ""
            searchText = ""
            return true
        }

        val (sbX, sbY, sbW, sbH) = scrollbarTrack().toList()
        if (mx in sbX until sbX + sbW && my in sbY until sbY + sbH) {
            if (visible.size > VISIBLE_ROWS) {
                scrollToThumbCentre(my)
                draggingScrollbar = true
            }
            return true
        }

        val (interiorX, interiorY, interiorW, interiorH) = listInterior().toList()
        if (mx in interiorX until interiorX + interiorW && my in interiorY until interiorY + interiorH) {
            val idx = scroll + (my - interiorY) / ROW_H
            if (idx < visible.size) {
                val row = visible[idx]
                val name = row.entry.set.name
                val bound = boundNames()
                val checked = name in bound
                val blocked = row.entry.claimedBy.isNotEmpty() || (!checked && bound.size >= menu.maxSets)
                if (!blocked) {
                    playClickSound()
                    PlatformServices.clientNetworking.sendToServer(
                        MultiProcessingHandlerTogglePayload(menu.devicePos, name, !checked)
                    )
                }
            }
            return true
        }

        val (tx, ty, tw, th) = parallelToggleBounds().toList()
        if (mx in tx until tx + tw && my in ty until ty + th) {
            playClickSound()
            val enabled = entity()?.parallel != true
            PlatformServices.clientNetworking.sendToServer(MultiProcessingHandlerSetParallelPayload(menu.devicePos, enabled))
            return true
        }
        val (hx, hy, hw, hh) = helpButtonBounds().toList()
        if (mx in hx until hx + hw && my in hy until hy + hh) {
            playClickSound()
            PlatformServices.guidebook.open(GUIDE_REF)
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar) {
            scrollToThumbCentre(event.mouseY.toInt())
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        draggingScrollbar = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val panelX = leftPos + OUTER_PAD
        val panelY = topPos + LIST_Y
        if (mouseX.toInt() in panelX..(panelX + LIST_W) && mouseY.toInt() in panelY..(panelY + LIST_H)) {
            scroll = (scroll - scrollY.toInt()).coerceIn(0, maxScroll())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        if (searchBox.isFocused) {
            if (event.keyCode == 257 || event.keyCode == 335) {
                if (focused === searchBox) setFocused(null) else searchBox.isFocused = false
                return true
            }
            searchBox.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
        )
    }
}
