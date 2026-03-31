package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.*
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.TerminalScreenHandler
import damien.nodeworks.screen.widget.AutocompletePopup
import damien.nodeworks.screen.widget.ScriptEditor

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.SpriteIconButton
import net.minecraft.resources.ResourceLocation
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class TerminalScreen(
    menu: TerminalScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TerminalScreenHandler>(menu, playerInventory, title) {

    companion object {
        /** Client-side UI preferences — persisted across terminal opens, shared across all terminals */
        var savedLogCollapsed = false
        var savedLogPanelHeight = 80

    }

    private lateinit var editor: ScriptEditor

    /** Matches line numbers in error messages: `main:5` or `[string "main"]:6` */
    private val errorLinePattern = Regex(""":(\d+)""")

    /** Exposed for platform-specific input suppression (e.g., blocking JEI keybinds). */
    fun isEditorFocused(): Boolean = ::editor.isInitialized && editor.isFocused
    private lateinit var autocomplete: AutocompletePopup
    // All scanned client-side from block entities in the loaded world
    private val cards: List<CardSnapshot>
    private val itemTags: List<String>
    private val variables: List<Pair<String, Int>>
    private val localApiNames: List<String>
    private val localApis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo>
    private val craftableOutputs: List<String>
    private var scriptRunning: Boolean = menu.isRunning()
    private var autoRun: Boolean = menu.isAutoRun()

    // Multi-script state — scripts map keyed by name, activeTab tracks which is shown in editor
    private val scripts: MutableMap<String, String> = menu.getScripts().toMutableMap()
    private var activeTab: String = "main"

    // Layout constants
    private val cardPanelWidth = 80
    private val editorPadding = 4
    private val buttonHeight = 20
    private val topBarHeight = 24
    private val tabBarHeight = 18
    private var logPanelHeight = savedLogPanelHeight

    // New tab input state
    private var showNewTabInput = false
    private var newTabName = ""

    // Editor position (stored for autocomplete positioning)
    private var editorX = 0
    private var editorY = 0
    private val lineNumberWidth = 28 // gutter width for line numbers

    // Card panel scroll state
    private var cardScrollOffset = 0
    private var sidebarEntries = listOf<SidebarEntry>()

    // Log scroll state
    private var logScrollOffset = 0
    private var logAutoScroll = true
    private var logCollapsed: Boolean = savedLogCollapsed
    private var draggingLogPanel = false
    private var pressedButton: String? = null // "copy" or "clear" while mouse is down
    private val logCollapsedHeight = 12 // just enough for the toggle bar
    private var errorHighlightLine = -1 // 0-indexed line to highlight, -1 = none
    private var errorHighlightTime = 0L

    // Used to preserve editor text across layout changes
    private var rebuildWithText: String? = null
    // Suppresses autocomplete updates during programmatic text insertion
    private var suppressAutocomplete = false

    // Undo/redo stacks
    private data class UndoState(val text: String, val cursor: Int)
    private val undoStack = ArrayDeque<UndoState>(50)
    private val redoStack = ArrayDeque<UndoState>(50)
    private var lastSavedText = ""
    private var lastSavedCursor = 0
    private var undoInProgress = false

    private fun applyUndoState(state: UndoState) {
        editor.value = state.text
        editor.cursor = state.cursor.coerceIn(0, state.text.length)
        lastSavedText = state.text
    }

    private fun toggleLineComment() {
        val text = editor.value
        val hadSelection = editor.hasSelection
        val origSelStart = editor.selectionStart
        val origSelEnd = editor.selectionEnd
        val cursor = editor.getCursorPosition()
        val lines = text.split('\n').toMutableList()

        // Determine which lines are affected
        val startLine: Int
        val endLine: Int
        if (hadSelection) {
            startLine = text.substring(0, origSelStart).count { it == '\n' }
            endLine = text.substring(0, origSelEnd).count { it == '\n' }
        } else {
            val line = text.substring(0, cursor).count { it == '\n' }
            startLine = line
            endLine = line
        }

        // Check if ALL non-empty affected lines are commented (-- at column 0)
        val allCommented = (startLine..endLine).all { i ->
            i < lines.size && (lines[i].isBlank() || lines[i].startsWith("-- ") || lines[i].startsWith("--"))
        }

        // Track delta for selection start and end separately
        var startDelta = 0
        var endDelta = 0
        val selStartLine = text.substring(0, origSelStart).count { it == '\n' }

        for (i in startLine..minOf(endLine, lines.lastIndex)) {
            val line = lines[i]

            if (allCommented) {
                val uncommented = when {
                    line.startsWith("-- ") -> line.removePrefix("-- ")
                    line.startsWith("--") -> line.removePrefix("--")
                    else -> line
                }
                val removed = line.length - uncommented.length
                if (i < selStartLine) startDelta -= removed
                endDelta -= removed
                lines[i] = uncommented
            } else {
                if (line.isBlank()) continue
                lines[i] = "-- $line"
                if (i < selStartLine) startDelta += 3
                endDelta += 3
            }
        }

        val newText = lines.joinToString("\n")

        if (hadSelection) {
            val newSelStart = (origSelStart + startDelta).coerceIn(0, newText.length)
            val newSelEnd = (origSelEnd + endDelta).coerceIn(0, newText.length)
            // Preserve which end of the selection the cursor was on
            val cursorAtStart = cursor == origSelStart
            editor.setValueKeepScroll(newText, if (cursorAtStart) newSelStart else newSelEnd)
            if (cursorAtStart) editor.setSelection(newSelEnd, newSelStart)
            else editor.setSelection(newSelStart, newSelEnd)
        } else {
            val newCursor = (cursor + endDelta).coerceIn(0, newText.length)
            editor.setValueKeepScroll(newText, newCursor)
        }
    }

    /** Find the error line number from a click position in the log panel, or null if not an error line. */
    private fun getClickedErrorLine(mouseY: Int, logContentTop: Int, logW: Int): Int? {
        val logLineHeight = font.lineHeight + 1
        val maxLogWidth = logW - 6
        val clickedVisualLine = (mouseY - logContentTop) / logLineHeight
        val clickedWrappedIdx = logScrollOffset + clickedVisualLine

        val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
        var wrappedIdx = 0
        for (entry in logs) {
            val fullText = "> " + entry.displayMessage
            val splitCount = font.splitter.splitLines(fullText, maxLogWidth, net.minecraft.network.chat.Style.EMPTY).size
            if (clickedWrappedIdx in wrappedIdx until wrappedIdx + splitCount) {
                if (entry.isError) {
                    val lineMatch = errorLinePattern.find(entry.message)
                    return lineMatch?.groupValues?.get(1)?.toIntOrNull()
                }
                return null
            }
            wrappedIdx += splitCount
        }
        return null
    }

    private fun jumpToLine(lineNumber: Int) {
        val lines = editor.value.split('\n')
        val lineIdx = (lineNumber - 1).coerceIn(0, lines.lastIndex)
        var pos = 0
        for (i in 0 until lineIdx) pos += lines[i].length + 1
        // Select the entire line
        val lineEnd = pos + lines[lineIdx].length
        editor.setSelection(pos, lineEnd)
        editor.isFocused = true
        // Highlight the line temporarily
        errorHighlightLine = lineIdx
        errorHighlightTime = net.minecraft.Util.getMillis()
    }

    private fun rebind() {
        clearWidgets()
        init()
    }

    // Layout presets
    private data class SidebarEntry(val name: String, val color: Int, val iconU: Int, val iconV: Int, val type: String)

    enum class TerminalLayout(val w: Int, val h: Int, val spriteName: String) {
        SMALL(320, 220, "layout_small"),
        WIDE(480, 220, "layout_wide"),
        TALL(320, 300, "layout_tall"),
        LARGE(480, 300, "layout_large")
    }

    private var currentLayout = TerminalLayout.entries.getOrElse(menu.getLayoutIndex()) { TerminalLayout.SMALL }

    init {
        imageWidth = currentLayout.w
        imageHeight = currentLayout.h


        // Scan client-side block entities for all autocomplete data
        val scannedCards = mutableListOf<CardSnapshot>()
        val scannedVars = mutableListOf<Pair<String, Int>>()
        val scannedLocal = mutableListOf<String>()
        val scannedLocalApis = mutableListOf<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo>()
        val scannedProcessable = mutableListOf<String>()
        val scannedCraftable = mutableListOf<String>()
        val mc = net.minecraft.client.Minecraft.getInstance()
        val clientLevel = mc.level
        if (clientLevel != null) {
            val termPos = menu.getTerminalPos()
            val termEntity = clientLevel.getBlockEntity(termPos)
            if (termEntity is damien.nodeworks.network.Connectable) {
                val visited = mutableSetOf<net.minecraft.core.BlockPos>()
                val queue = ArrayDeque<net.minecraft.core.BlockPos>()
                visited.add(termPos)
                for (conn in termEntity.getConnections()) {
                    if (visited.add(conn)) queue.add(conn)
                }
                while (queue.isNotEmpty() && visited.size < 128) {
                    val pos = queue.removeFirst()
                    if (!clientLevel.isLoaded(pos)) continue
                    val entity = clientLevel.getBlockEntity(pos) ?: continue

                    when (entity) {
                        is damien.nodeworks.block.entity.NodeBlockEntity -> {
                            for (dir in net.minecraft.core.Direction.entries) {
                                val caps = entity.getSideCapabilities(dir)
                                for (info in caps) {
                                    scannedCards.add(CardSnapshot(info.capability, info.alias, info.slotIndex))
                                }
                            }
                        }
                        is damien.nodeworks.block.entity.VariableBlockEntity -> {
                            if (entity.variableName.isNotEmpty()) {
                                scannedVars.add(entity.variableName to entity.variableType.ordinal)
                            }
                        }
                        is damien.nodeworks.block.entity.InstructionStorageBlockEntity -> {
                            for (info in entity.getAllInstructionSets()) {
                                if (info.outputItemId.isNotEmpty()) scannedCraftable.add(info.outputItemId)
                            }
                        }
                        is damien.nodeworks.block.entity.ProcessingStorageBlockEntity -> {
                            for (api in entity.getAllProcessingApis()) {
                                scannedLocal.add(api.name)
                                scannedLocalApis.add(api)
                                scannedProcessable.addAll(api.outputItemIds)
                            }
                        }
                        is damien.nodeworks.block.entity.ReceiverAntennaBlockEntity -> {
                            if (entity.isPaired) {
                                val pairedData = entity.getItem(0)
                                if (!pairedData.isEmpty && pairedData.item is damien.nodeworks.item.LinkCrystalItem) {
                                    val chipData = damien.nodeworks.item.LinkCrystalItem.getPairingData(pairedData)
                                    if (chipData != null && clientLevel.isLoaded(chipData.pos)) {
                                        val broadcast = clientLevel.getBlockEntity(chipData.pos)
                                        if (broadcast is damien.nodeworks.block.entity.BroadcastAntennaBlockEntity) {
                                            for (api in broadcast.getAvailableApis()) {
                                                scannedProcessable.addAll(api.outputItemIds)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val connectable = entity as? damien.nodeworks.network.Connectable ?: continue
                    for (conn in connectable.getConnections()) {
                        if (visited.add(conn)) queue.add(conn)
                    }
                }
            }
        }

        // Assign auto-aliases to unnamed cards (same logic as NetworkDiscovery)
        val counters = mutableMapOf<String, Int>()
        for (card in scannedCards) {
            if (card.alias == null) {
                val type = card.capability.type
                val count = counters.getOrDefault(type, 0) + 1
                counters[type] = count
                card.autoAlias = "${type}_$count"
            }
        }

        // Item tags from the client registry
        val scannedTags = net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagNames()
            .map { it.location().toString() }
            .sorted()
            .toList()

        cards = scannedCards
        itemTags = scannedTags
        variables = scannedVars
        localApiNames = scannedLocal.distinct()
        localApis = scannedLocalApis
        craftableOutputs = (scannedCraftable + scannedProcessable).distinct()
    }

    override fun init() {
        super.init()

        imageWidth = currentLayout.w
        imageHeight = currentLayout.h
        // Clamp to screen bounds
        if (imageWidth > width - 10) imageWidth = width - 10
        if (imageHeight > height - 10) imageHeight = height - 10
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        editorX = leftPos + cardPanelWidth + editorPadding + lineNumberWidth
        editorY = topPos + topBarHeight + tabBarHeight
        val editorW = imageWidth - cardPanelWidth - editorPadding * 2 - lineNumberWidth
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val editorH = imageHeight - topBarHeight - tabBarHeight - effectiveLogHeight - editorPadding

        editor = ScriptEditor(font, editorX, editorY, editorW, editorH)

        // Hidden EditBox to signal to JEI and other mods that we have an active text input.
        // JEI checks if the focused element is an EditBox before stealing key events.
        val dummyInput = net.minecraft.client.gui.components.EditBox(font, -9999, -9999, 1, 1, Component.empty())
        dummyInput.isFocused = true
        addRenderableWidget(dummyInput)

        editor.value = rebuildWithText ?: scripts[activeTab] ?: ""
        rebuildWithText = null
        editor.setCharacterLimit(32767)

        lastSavedText = editor.value
        editor.setValueListener { newText ->
            // Push undo state when text changes (but not during undo/redo itself)
            if (!undoInProgress && newText != lastSavedText) {
                undoStack.addLast(UndoState(lastSavedText, lastSavedCursor))
                if (undoStack.size > 50) undoStack.removeFirst()
                redoStack.clear()
                lastSavedText = newText
            }
            // Update autocomplete whenever text changes (unless suppressed during programmatic insertion)
            if (!suppressAutocomplete) {
                autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, editorScrollY = editor.scrollY)
            }
        }
        addRenderableWidget(editor)

        autocomplete = AutocompletePopup(font, cards, itemTags, variables, localApiNames, craftableOutputs, localApis) { scripts }

        // Top bar buttons — right-aligned: [Layout] [Run] [Stop]
        val btnY = topPos + 2
        val stopX = leftPos + imageWidth - 44
        val runX = stopX - 44
        val layoutX = runX - 24

        // Layout cycle button (icon, standard MC button look)
        val layoutBtn = SpriteIconButton.builder(Component.literal("Layout"), { _ ->
            val savedText = editor.value
            currentLayout = TerminalLayout.entries[(currentLayout.ordinal + 1) % TerminalLayout.entries.size]
            PlatformServices.clientNetworking.sendToServer(SetLayoutPayload(menu.getTerminalPos(), currentLayout.ordinal))
            rebuildWithText = savedText
            rebind()
        }, true)
            .sprite(ResourceLocation.fromNamespaceAndPath("nodeworks", currentLayout.spriteName), 16, 16)
            .size(20, buttonHeight)
            .build()
        layoutBtn.x = layoutX
        layoutBtn.y = btnY
        addRenderableWidget(layoutBtn)

        // Run button — save current tab text first, then tell server to run
        addRenderableWidget(Button.builder(Component.literal("Run")) { _ ->
            scripts[activeTab] = editor.value
            PlatformServices.clientNetworking.sendToServer(SaveScriptPayload(menu.getTerminalPos(), activeTab, editor.value))
            PlatformServices.clientNetworking.sendToServer(RunScriptPayload(menu.getTerminalPos()))
            scriptRunning = true
        }.bounds(runX, btnY, 40, buttonHeight).build())

        // Stop button
        addRenderableWidget(Button.builder(Component.literal("Stop")) { _ ->
            PlatformServices.clientNetworking.sendToServer(StopScriptPayload(menu.getTerminalPos()))
            scriptRunning = false
        }.bounds(stopX, btnY, 40, buttonHeight).build())

        // Auto-run toggle
        addRenderableWidget(Button.builder(autoRunLabel()) { btn ->
            autoRun = !autoRun
            PlatformServices.clientNetworking.sendToServer(ToggleAutoRunPayload(menu.getTerminalPos(), autoRun))
            btn.message = autoRunLabel()
        }.bounds(leftPos + 4, topPos + imageHeight - 24, cardPanelWidth - 8, buttonHeight).build())
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Main background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + topBarHeight, 0xFF3C3C3C.toInt())

        // Tab bar background
        val tabBarY = topPos + topBarHeight
        val tabBarStartX = leftPos + cardPanelWidth + 1
        graphics.fill(tabBarStartX, tabBarY, leftPos + imageWidth, tabBarY + tabBarHeight, 0xFF252525.toInt())
        // Tab bar separator
        graphics.fill(tabBarStartX, tabBarY + tabBarHeight - 1, leftPos + imageWidth, tabBarY + tabBarHeight, 0xFF444444.toInt())

        // Draw tabs
        var tabX = tabBarStartX + 2
        for (name in scripts.keys) {
            val tabWidth = font.width(name) + 12 + if (name != "main") 10 else 0 // extra space for ✕
            val isActive = name == activeTab
            val tabBg = if (isActive) 0xFF3C3C3C.toInt() else 0xFF2B2B2B.toInt()
            val textColor = if (isActive) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()

            graphics.fill(tabX, tabBarY + 1, tabX + tabWidth, tabBarY + tabBarHeight - 1, tabBg)
            if (isActive) {
                // Active tab hides the bottom separator
                graphics.fill(tabX, tabBarY + tabBarHeight - 1, tabX + tabWidth, tabBarY + tabBarHeight, tabBg)
            }
            graphics.drawString(font, name, tabX + 4, tabBarY + 4, textColor, false)

            // Draw ✕ for non-main tabs
            if (name != "main") {
                val closeX = tabX + tabWidth - 10
                graphics.drawString(font, "\u00D7", closeX, tabBarY + 4, 0xFF666666.toInt(), false)
            }

            tabX += tabWidth + 2
        }

        // [+] button if under max tabs
        if (scripts.size < 8) {
            val plusWidth = font.width("+") + 8
            graphics.fill(tabX, tabBarY + 1, tabX + plusWidth, tabBarY + tabBarHeight - 1, 0xFF2B2B2B.toInt())
            graphics.drawString(font, "+", tabX + 4, tabBarY + 4, 0xFF888888.toInt(), false)
        }

        // Card panel background
        graphics.fill(leftPos, topPos + topBarHeight, leftPos + cardPanelWidth, topPos + imageHeight, 0xFF333333.toInt())

        // Card panel separator
        graphics.fill(leftPos + cardPanelWidth, topPos + topBarHeight, leftPos + cardPanelWidth + 1, topPos + imageHeight, 0xFF555555.toInt())

        // Title in top bar
        graphics.drawString(font, title, leftPos + 6, topPos + 7, 0xFFFFFFFF.toInt())

        // Status indicator — positioned left of the layout button
        val statusX = leftPos + imageWidth - 170
        val statusY = topPos + 7
        val circleColor = if (scriptRunning) 0xFF55FF55.toInt() else 0xFF666666.toInt()
        val statusText = if (scriptRunning) "Running" else "Stopped"
        val textColor = if (scriptRunning) 0xFF55FF55.toInt() else 0xFF888888.toInt()
        graphics.fill(statusX + 1, statusY, statusX + 4, statusY + 5, circleColor)
        graphics.fill(statusX, statusY + 1, statusX + 5, statusY + 4, circleColor)
        graphics.drawString(font, statusText, statusX + 7, statusY, textColor)

        // Card & variable list header
        val cardStartY = topPos + topBarHeight + 6
        graphics.drawString(font, "Network:", leftPos + 6, cardStartY, 0xFFAAAAAA.toInt())

        // Build combined sidebar entries: cards + variables
        val entries = mutableListOf<SidebarEntry>()
        for (card in cards) {
            val type = card.capability.type
            val iconU = when (type) { "io" -> 0; "storage" -> 16; "redstone" -> 32; else -> 0 }
            val color = when (type) {
                "io" -> 0xFF83E086.toInt()
                "storage" -> 0xFFAA83E0.toInt()
                "redstone" -> 0xFFF53B68.toInt()
                "energy" -> 0xFFFFD700.toInt()
                "fluid" -> 0xFF55AAFF.toInt()
                else -> 0xFFAAAAAA.toInt()
            }
            entries.add(SidebarEntry(card.effectiveAlias, color, iconU, 16, "card"))
        }
        for ((name, typeOrd) in variables) {
            entries.add(SidebarEntry(name, 0xFFFFAA33.toInt(), 48, 16, "var"))
        }

        // Sidebar entries (scrollable)
        val cardListTop = cardStartY + 12
        val cardListBottom = topPos + imageHeight - 28
        val cardLineHeight = 11

        sidebarEntries = entries

        graphics.enableScissor(leftPos, cardListTop, leftPos + cardPanelWidth, cardListBottom)
        val iconsTexture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")
        for ((i, entry) in entries.withIndex()) {
            val y = cardListTop + i * cardLineHeight - cardScrollOffset
            if (y + cardLineHeight < cardListTop) continue
            if (y > cardListBottom) break

            // Hover highlight
            val hovered = mouseX >= leftPos && mouseX < leftPos + cardPanelWidth - 3 &&
                mouseY >= y && mouseY < y + cardLineHeight
            if (hovered) {
                graphics.fill(leftPos + 1, y, leftPos + cardPanelWidth - 3, y + cardLineHeight, 0x30FFFFFF.toInt())
            }

            // 8x8 icon cropped from center of 16x16 tile in atlas
            graphics.blit(iconsTexture, leftPos + 4, y + 1, (entry.iconU + 4).toFloat(), (entry.iconV + 4).toFloat(), 8, 8, 256, 256)

            // Name
            graphics.drawString(font, entry.name, leftPos + 15, y + 1, entry.color)
        }
        graphics.disableScissor()

        // Scroll indicator
        val maxVisibleCards = (cardListBottom - cardListTop) / cardLineHeight
        if (entries.size > maxVisibleCards) {
            val scrollbarHeight = cardListBottom - cardListTop
            val thumbHeight = maxOf(8, scrollbarHeight * maxVisibleCards / entries.size)
            val maxCardScroll = maxOf(1, (entries.size - maxVisibleCards) * cardLineHeight)
            val thumbY = cardListTop + (scrollbarHeight - thumbHeight) * cardScrollOffset / maxCardScroll
            graphics.fill(leftPos + cardPanelWidth - 3, thumbY.toInt(), leftPos + cardPanelWidth - 1, (thumbY + thumbHeight).toInt(), 0xFF555555.toInt())
        }

        // Log panel
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val logX = leftPos + cardPanelWidth + editorPadding
        val logY = topPos + imageHeight - effectiveLogHeight
        val logW = imageWidth - cardPanelWidth - editorPadding * 2

        // Toggle bar background
        graphics.fill(logX, logY, logX + logW, logY + logCollapsedHeight, 0xFF1E1E1E.toInt())
        // Separator / drag handle
        if (!logCollapsed) {
            val hovering = mouseX >= logX && mouseX <= logX + logW && mouseY >= logY - 4 && mouseY <= logY + 3
            val sepColor = if (hovering || draggingLogPanel) 0xFF777777.toInt() else 0xFF555555.toInt()
            graphics.fill(logX, logY, logX + logW, logY + 1, sepColor)
            // Grip dots — centered on separator
            val centerX = logX + logW / 2
            val dotColor = if (hovering || draggingLogPanel) 0xFF999999.toInt() else 0xFF666666.toInt()
            for (d in -3..3) {
                graphics.fill(centerX + d * 3, logY - 2, centerX + d * 3 + 1, logY - 1, dotColor)
            }
        } else {
            graphics.fill(logX, logY, logX + logW, logY + 1, 0xFF555555.toInt())
        }

        // Toggle label with arrow
        val arrow = if (logCollapsed) "\u25B6" else "\u25BC"
        graphics.drawString(font, "$arrow Output", logX + 3, logY + 2, 0xFF888888.toInt())

        // Output toolbar buttons from icons atlas (row 2, y=32)
        // Each button: 16x16 tile with 3 variants (normal, hovered, pressed)
        val btnRenderSize = 10
        val atlasIconsTexture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")

        // Clear button (left of copy)
        val clearBtnX = logX + logW - btnRenderSize * 2 - 6
        val clearBtnY = logY + 3
        val clearHovered = mouseX >= clearBtnX && mouseX < clearBtnX + btnRenderSize &&
                mouseY >= clearBtnY && mouseY < clearBtnY + btnRenderSize
        val clearU = 48 + when { pressedButton == "clear" -> 32; clearHovered -> 16; else -> 0 }
        graphics.blit(atlasIconsTexture, clearBtnX, clearBtnY, btnRenderSize, btnRenderSize,
            (clearU + 3).toFloat(), 35f, 10, 10, 256, 256)

        // Copy button
        val copyBtnX = logX + logW - btnRenderSize - 3
        val copyBtnY = logY + 3
        val copyHovered = mouseX >= copyBtnX && mouseX < copyBtnX + btnRenderSize &&
                mouseY >= copyBtnY && mouseY < copyBtnY + btnRenderSize
        val copyU = when { pressedButton == "copy" -> 32; copyHovered -> 16; else -> 0 }
        graphics.blit(atlasIconsTexture, copyBtnX, copyBtnY, btnRenderSize, btnRenderSize,
            (copyU + 3).toFloat(), 35f, 10, 10, 256, 256)

        if (!logCollapsed) {
            // Log content area
            val logContentTop = logY + logCollapsedHeight
            val logContentBottom = logY + logPanelHeight - editorPadding
            graphics.fill(logX, logContentTop, logX + logW, logContentBottom, 0xFF1E1E1E.toInt())

            // Log entries with word wrapping
            val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
            val logLineHeight = font.lineHeight + 1
            val logTextAreaHeight = logContentBottom - logContentTop
            val maxLogWidth = logW - 6

            // Build wrapped lines
            data class WrappedLine(val text: String, val color: Int, val clickable: Boolean = false)
            val wrappedLines = mutableListOf<WrappedLine>()
            for (entry in logs) {
                val color = if (entry.isError) 0xFFFF5555.toInt() else 0xFF999999.toInt()
                val fullText = "> " + entry.displayMessage
                val hasLineRef = entry.isError && errorLinePattern.containsMatchIn(entry.message)
                val split = font.splitter.splitLines(fullText, maxLogWidth, net.minecraft.network.chat.Style.EMPTY)
                for ((j, line) in split.withIndex()) {
                    val prefix = if (j == 0) "" else "  "
                    wrappedLines.add(WrappedLine(prefix + line.string, color, hasLineRef && j == 0))
                }
            }

            val maxVisibleLines = logTextAreaHeight / logLineHeight
            if (logAutoScroll && wrappedLines.isNotEmpty()) {
                logScrollOffset = maxOf(0, wrappedLines.size - maxVisibleLines)
            }

            graphics.enableScissor(logX, logContentTop, logX + logW, logContentBottom)
            for (i in 0 until maxVisibleLines) {
                val lineIdx = logScrollOffset + i
                if (lineIdx >= wrappedLines.size) break
                val line = wrappedLines[lineIdx]
                val entryY = logContentTop + i * logLineHeight
                graphics.drawString(font, line.text, logX + 3, entryY, line.color)
                // Underline clickable error lines when hovered
                if (line.clickable && mouseY >= entryY && mouseY < entryY + logLineHeight &&
                    mouseX >= logX && mouseX < logX + logW) {
                    val textW = font.width(line.text)
                    graphics.fill(logX + 3, entryY + font.lineHeight, logX + 3 + textW, entryY + font.lineHeight + 1, 0xAAFF5555.toInt())
                }
            }
            graphics.disableScissor()
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Line number gutter
        renderLineNumbers(graphics)

        // Hover type tooltip
        if (!autocomplete.visible && !showNewTabInput) {
            renderTypeTooltip(graphics, mouseX, mouseY)
        }

        // Autocomplete popup renders on top of everything
        autocomplete.render(graphics, mouseX, mouseY)
        // New tab name input overlay — render on top of everything
        if (showNewTabInput) {
            val inputW = 120
            val inputH = 20
            val inputX = leftPos + imageWidth / 2 - inputW / 2
            val inputY = topPos + imageHeight / 2 - inputH / 2
            graphics.fill(inputX - 2, inputY - 2, inputX + inputW + 2, inputY + inputH + 2, 0xFF555555.toInt())
            graphics.fill(inputX, inputY, inputX + inputW, inputY + inputH, 0xFF1E1E1E.toInt())
            val displayText = if (newTabName.isEmpty()) "enter name..." else newTabName
            val displayColor = if (newTabName.isEmpty()) 0xFF666666.toInt() else 0xFFFFFFFF.toInt()
            graphics.drawString(font, displayText, inputX + 4, inputY + 6, displayColor, false)
            if (newTabName.isNotEmpty() || (net.minecraft.Util.getMillis() / 500) % 2 == 0L) {
                val cursorX = inputX + 4 + font.width(newTabName)
                graphics.fill(cursorX, inputY + 4, cursorX + 1, inputY + inputH - 4, 0xFFFFFFFF.toInt())
            }
        }

        renderTooltip(graphics, mouseX, mouseY)
    }

    /** Known method signatures for tooltip display. */
    private val methodSignatures = mapOf(
        // Network methods
        "get" to "network:get(alias: string) → CardHandle",
        "getAll" to "network:getAll(type: string) → CardHandle[]",
        "craft" to "network:craft(id: string, count?: number) → CraftBuilder",
        "handle" to "network:handle(cardName: string, fn: function(job, ...))",
        "route" to "network:route(alias: string, fn: function(item) → boolean)",
        "shapeless" to "network:shapeless(item: string, count?: number, ...) → ItemsHandle?",
        "debug" to "network:debug() — print network topology",
        "var" to "network:var(name: string) → VariableHandle",
        // Network item methods (also on CardHandle)
        "find" to "find(filter: string) → ItemsHandle?",
        "findEach" to "findEach(filter: string) → ItemsHandle[]",
        "insert" to "insert(items: ItemsHandle, count?: number) → number",
        "count" to "count(filter: string) → number",
        "face" to "face(side: string) → CardHandle",
        "slots" to "slots(...: number) → CardHandle",
        // ItemsHandle methods
        "hasTag" to "hasTag(tag: string) → boolean",
        "matches" to "matches(filter: string) → boolean",
        // Scheduler methods
        "tick" to "scheduler:tick(fn: function) → number",
        "second" to "scheduler:second(fn: function) → number",
        "delay" to "scheduler:delay(ticks: number, fn: function) → number",
        "cancel" to "scheduler:cancel(id: number)",
        // CraftBuilder methods
        "connect" to "connect(fn: function(item: ItemsHandle))",
        "store" to "store() — send result to network storage",
        // Job methods
        "pull" to "job:pull(card: CardHandle, ...) — wait for outputs",
        // RedstoneCard methods
        "powered" to "powered() → boolean",
        "strength" to "strength() → number (0-15)",
        "set" to "set(boolean | number) — emit redstone signal",
        "onChange" to "onChange(fn: function(strength: number))",
        // Lua builtins
        "print" to "print(...) — output to terminal",
        "clock" to "clock() → number (server tick count)",
        "tostring" to "tostring(value: any) → string",
        "tonumber" to "tonumber(value: any) → number?",
        "type" to "type(value: any) → string",
        "pairs" to "pairs(t: table) → iterator",
        "ipairs" to "ipairs(t: table) → iterator",
        "require" to "require(module: string) → table"
    )

    private fun renderTypeTooltip(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (mouseX < editorX || mouseX > editorX + editor.width ||
            mouseY < editorY || mouseY > editorY + editor.height) return

        val word = editor.getWordAt(mouseX.toDouble(), mouseY.toDouble()) ?: return

        // Check built-in method signatures first
        val methodSig = methodSignatures[word]

        val tooltipText = if (methodSig != null) {
            methodSig
        } else {
            // Check user-defined functions (current script + modules)
            val funcSig = autocomplete.getFunctionSignature(word, editor.value)
            if (funcSig != null) {
                funcSig
            } else {
                // Check variable types
                val type = when (word) {
                    "network" -> "Network API"
                    "scheduler" -> "Scheduler API"
                    "true", "false" -> "boolean"
                    "nil" -> "nil"
                    else -> {
                        val symbols = autocomplete.getSymbolTable(editor.value, editor.value.substring(0, editor.getCursorPosition()))
                        symbols[word]
                    }
                }
                if (type != null) "$word: $type" else return
            }
        }

        // Split into name (bright) and hint (dim) — find the first separator
        val separatorIdx = tooltipText.indexOfFirst { it == '(' || it == ':' || it == '—' || it == '→' }
        val namePart: String
        val hintPart: String
        if (separatorIdx > 0) {
            namePart = tooltipText.substring(0, separatorIdx)
            hintPart = tooltipText.substring(separatorIdx)
        } else {
            namePart = tooltipText
            hintPart = ""
        }

        val tooltipW = font.width(tooltipText) + 6
        val tooltipH = font.lineHeight + 4
        val tx = mouseX + 8
        val ty = mouseY - tooltipH - 2

        graphics.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, 0xFF555555.toInt())
        graphics.fill(tx, ty, tx + tooltipW, ty + tooltipH, 0xFF1A1A1A.toInt())
        graphics.drawString(font, namePart, tx + 3, ty + 2, 0xFFCCCCCC.toInt())
        if (hintPart.isNotEmpty()) {
            graphics.drawString(font, hintPart, tx + 3 + font.width(namePart), ty + 2, 0xFF888888.toInt())
        }
    }

    private fun renderLineNumbers(graphics: GuiGraphics) {
        val text = editor.value
        val lineHeight = font.lineHeight

        val gutterX = editorX - lineNumberWidth
        val gutterTop = editorY
        val gutterBottom = editorY + editor.height

        // Gutter background
        graphics.fill(gutterX, gutterTop, editorX - 1, gutterBottom, 0xFF1E1E1E.toInt())
        // Separator line
        graphics.fill(editorX - 1, gutterTop, editorX, gutterBottom, 0xFF3C3C3C.toInt())

        // Count total lines
        val totalLines = text.count { it == '\n' } + 1

        // Inner top of the editor, adjusted for scroll (matches ScriptEditor's padding)
        val innerTop = editor.y + 4 - editor.scrollY

        // Error highlight fades out over 2 seconds
        val highlightElapsed = if (errorHighlightLine >= 0) net.minecraft.Util.getMillis() - errorHighlightTime else Long.MAX_VALUE
        val highlightFadeDuration = 2000L
        val highlightAlpha = if (highlightElapsed < highlightFadeDuration) {
            ((1.0 - highlightElapsed.toDouble() / highlightFadeDuration) * 0x40).toInt().coerceIn(0, 0x40)
        } else 0

        graphics.enableScissor(gutterX, gutterTop, editorX - 1, gutterBottom)
        for (line in 1..totalLines) {
            val y = innerTop + (line - 1) * lineHeight
            if (y + lineHeight < gutterTop) continue
            if (y > gutterBottom) break

            // Error line highlight — red tint across gutter and editor, fading out
            if (highlightAlpha > 0 && line - 1 == errorHighlightLine) {
                val color = (highlightAlpha shl 24) or 0xFF3333
                graphics.fill(gutterX, y, editorX + editor.width, y + lineHeight, color)
            }

            val numStr = line.toString()
            val numWidth = font.width(numStr)
            val numColor = if (highlightAlpha > 0 && line - 1 == errorHighlightLine) {
                // Fade line number from red to normal gray
                val redAmount = (highlightAlpha * 255 / 0x40).coerceIn(0, 255)
                val grayBase = 0x55
                val r = grayBase + (0xFF - grayBase) * redAmount / 255
                val g = grayBase + (0x55 - grayBase) * redAmount / 255
                val b = grayBase + (0x55 - grayBase) * redAmount / 255
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else 0xFF555555.toInt()
            graphics.drawString(font, numStr, editorX - 4 - numWidth, y, numColor, false)
        }
        graphics.disableScissor()
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Handle new tab name input
        if (showNewTabInput) {
            when (keyCode) {
                InputConstants.KEY_ESCAPE -> {
                    showNewTabInput = false
                    newTabName = ""
                }
                InputConstants.KEY_RETURN -> {
                    if (newTabName.isNotEmpty() && newTabName !in scripts) {
                        scripts[newTabName] = ""
                        PlatformServices.clientNetworking.sendToServer(CreateScriptTabPayload(menu.getTerminalPos(), newTabName))
                        showNewTabInput = false
                        switchTab(newTabName)
                        newTabName = ""
                    }
                }
                InputConstants.KEY_BACKSPACE -> {
                    if (newTabName.isNotEmpty()) {
                        newTabName = newTabName.dropLast(1)
                    }
                }
                else -> {
                    // charTyped handles actual character input
                }
            }
            return true
        }

        if (editor.isFocused) {
            // Capture cursor before any edits for undo
            lastSavedCursor = editor.getCursorPosition()

            if (keyCode == InputConstants.KEY_ESCAPE) {
                if (autocomplete.visible) {
                    autocomplete.hide()
                    return true
                }
                return super.keyPressed(keyCode, scanCode, modifiers)
            }

            // Ctrl+Z = undo
            if (keyCode == InputConstants.KEY_Z && (modifiers and 2) != 0 && (modifiers and 1) == 0) {
                if (undoStack.isNotEmpty()) {
                    undoInProgress = true
                    val cursorPos = editor.getCursorPosition()
                    redoStack.addLast(UndoState(editor.value, cursorPos))
                    val prev = undoStack.removeLast()
                    applyUndoState(prev)
                    undoInProgress = false
                }
                return true
            }

            // Ctrl+Shift+Z or Ctrl+Y = redo
            if ((keyCode == InputConstants.KEY_Z && (modifiers and 3) == 3) ||
                (keyCode == InputConstants.KEY_Y && (modifiers and 2) != 0)) {
                if (redoStack.isNotEmpty()) {
                    undoInProgress = true
                    val cursorPos = editor.getCursorPosition()
                    undoStack.addLast(UndoState(editor.value, cursorPos))
                    val next = redoStack.removeLast()
                    applyUndoState(next)
                    undoInProgress = false
                }
                return true
            }

            // Ctrl+/ = toggle line comment
            if (keyCode == InputConstants.KEY_SLASH && (modifiers and 2) != 0) {
                toggleLineComment()
                return true
            }

            // Ctrl+Space triggers autocomplete
            if (keyCode == InputConstants.KEY_SPACE && (modifiers and 2) != 0) {
                autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, forced = true, editorScrollY = editor.scrollY)
                return true
            }

            // Autocomplete navigation
            if (autocomplete.visible) {
                when (keyCode) {
                    InputConstants.KEY_UP -> { autocomplete.moveUp(); return true }
                    InputConstants.KEY_DOWN -> { autocomplete.moveDown(); return true }
                    InputConstants.KEY_RETURN, InputConstants.KEY_TAB -> {
                        val result = autocomplete.accept()
                        if (result != null) {
                            // Suppress autocomplete updates during insertion
                            suppressAutocomplete = true
                            // Delete the typed prefix by manipulating text directly
                            val text = editor.value
                            val cursorPos = editor.getCursorPosition()
                            val deleteStart = cursorPos - result.deleteCount
                            val newText = text.substring(0, deleteStart) + result.insertText + text.substring(cursorPos)
                            editor.setValueKeepScroll(newText, deleteStart + result.cursorOffset)
                            suppressAutocomplete = false
                            // Trigger autocomplete at new cursor position (e.g. inside quotes after snippet)
                            autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, editorScrollY = editor.scrollY)
                            return true
                        }
                    }
                }
            }

            // Tab inserts 2 spaces (when autocomplete not visible) — ScriptEditor handles Tab internally,
            // but we override here to keep consistent behavior with the old 4-space tab
            if (keyCode == InputConstants.KEY_TAB && !autocomplete.visible) {
                for (i in 0..3) {
                    editor.charTyped(' ', 0)
                }
                return true
            }

            // Auto-delete pair: if backspace on empty pair like (), [], {}, ""
            if (keyCode == InputConstants.KEY_BACKSPACE) {
                val bText = editor.value
                val bCursor = editor.getCursorPosition()
                if (bCursor > 0 && bCursor < bText.length) {
                    val pair = "" + bText[bCursor - 1] + bText[bCursor]
                    if (pair == "()" || pair == "[]" || pair == "{}" || pair == "\"\"") {
                        val newText = bText.substring(0, bCursor - 1) + bText.substring(bCursor + 1)
                        editor.setValueKeepScroll(newText, bCursor - 1)
                        autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, editorScrollY = editor.scrollY)
                        return true
                    }
                }
            }

            // Auto-insert `end` when pressing Enter after block-opening statements
            if (keyCode == InputConstants.KEY_RETURN) {
                val text = editor.value
                val cursor = editor.getCursorPosition()
                val beforeCursor = text.substring(0, cursor)
                val currentLine = beforeCursor.substringAfterLast('\n').trimEnd()

                // Check if the line opens a block that needs `end`
                val needsEnd = currentLine.matches(Regex("""^\s*(local\s+)?function\s.*""")) ||
                    currentLine.matches(Regex("""^\s*if\s+.+\s+then\s*$""")) ||
                    currentLine.matches(Regex("""^\s*for\s+.+\s+do\s*$""")) ||
                    currentLine.matches(Regex("""^\s*while\s+.+\s+do\s*$"""))

                if (needsEnd) {
                    // Count block openers vs `end` keywords line-by-line
                    var depth = 0
                    for (line in text.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("--")) continue // skip comments
                        if (Regex("""\bfunction[\s(]|if\s.+\sthen|for\s.+\sdo|while\s.+\sdo""").containsMatchIn(trimmed)) depth++
                        if (Regex("""\bend\b""").containsMatchIn(trimmed)) depth--
                    }

                    if (depth > 0) {
                        val indent = currentLine.takeWhile { it == ' ' }
                        val newText = text.substring(0, cursor) + "\n$indent    \n${indent}end" + text.substring(cursor)
                        val newCursor = cursor + 1 + indent.length + 4
                        editor.setValueKeepScroll(newText, newCursor)
                        autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, editorScrollY = editor.scrollY)
                        return true
                    }
                }
            }

            editor.keyPressed(keyCode, scanCode, modifiers)
            // Update autocomplete only for keys that modify text, not navigation
            val isNavOrModifierKey = keyCode in setOf(
                InputConstants.KEY_UP, InputConstants.KEY_DOWN,
                InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT,
                InputConstants.KEY_HOME, InputConstants.KEY_END,
                InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN,
                InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT,
                InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL,
                InputConstants.KEY_LALT, InputConstants.KEY_RALT
            )
            if (!isNavOrModifierKey) {
                autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, editorScrollY = editor.scrollY)
            }
            // Always consume key events when editor is focused to prevent other mods from stealing them
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (showNewTabInput) {
            val c = codePoint
            if (c.isLetterOrDigit() || c == '_') {
                val candidate = newTabName + c.lowercaseChar()
                if (candidate.length <= 20 && TerminalBlockEntity.SCRIPT_NAME_REGEX.matches(candidate)) {
                    newTabName = candidate
                }
            }
            return true
        }
        if (editor.isFocused) {
            // Capture cursor before any edits for undo
            lastSavedCursor = editor.getCursorPosition()

            // Block the space from Ctrl+Space — it was already handled in keyPressed
            if (codePoint == ' ' && (modifiers and 2) != 0) {
                return true
            }
            val text = editor.value
            val cursor = editor.getCursorPosition()

            // Surround selection with matching pair
            val closingChar = when (codePoint) {
                '(' -> ')'; '[' -> ']'; '{' -> '}'; '"' -> '"'; else -> null
            }
            if (closingChar != null && editor.hasSelection) {
                val selStart = editor.selectionStart
                val selEnd = editor.selectionEnd
                val newText = text.substring(0, selStart) + codePoint + text.substring(selStart, selEnd) + closingChar + text.substring(selEnd)
                editor.setValueKeepScroll(newText, selEnd + 2)
                editor.setSelection(selStart + 1, selEnd + 1)
            } else {
                // Skip-over: if typing a closing char that's already next, just move cursor forward
                val nextChar = if (cursor < text.length) text[cursor] else null
                val isClosing = codePoint in listOf(')', ']', '}')
                val isQuoteSkip = codePoint == '"' && nextChar == '"'
                if ((isClosing || isQuoteSkip) && nextChar == codePoint) {
                    editor.setValueKeepScroll(text, cursor + 1)
                } else if (closingChar != null) {
                    // Auto-pair: insert both and place cursor between
                    val newText = text.substring(0, cursor) + codePoint + closingChar + text.substring(cursor)
                    editor.setValueKeepScroll(newText, cursor + 1)
                } else {
                    editor.charTyped(codePoint, modifiers)
                }
            }
            autocomplete.update(editor.value, editor.getCursorPosition(), editorX, editorY, editorScrollY = editor.scrollY)
            // Always consume when editor is focused to prevent other mods stealing input
            return true
        }
        return super.charTyped(codePoint, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()


        // Handle new tab input dialog — intercept all clicks
        if (showNewTabInput) {
            val inputW = 120
            val inputH = 20
            val inputX = leftPos + imageWidth / 2 - inputW / 2
            val inputY = topPos + imageHeight / 2 - inputH / 2
            if (mx < inputX - 2 || mx > inputX + inputW + 2 || my < inputY - 2 || my > inputY + inputH + 2) {
                showNewTabInput = false
                newTabName = ""
            }
            return true
        }

        // Check sidebar click — insert reference at top of file
        val cardStartY = topPos + topBarHeight + 6
        val cardListTop = cardStartY + 12
        val cardListBottom = topPos + imageHeight - 28
        val cardLineHeight = 11
        if (mx >= leftPos && mx < leftPos + cardPanelWidth - 3 && my >= cardListTop && my < cardListBottom) {
            val clickedIndex = (my - cardListTop + cardScrollOffset) / cardLineHeight
            if (clickedIndex in sidebarEntries.indices) {
                val entry = sidebarEntries[clickedIndex]
                val line = when (entry.type) {
                    "card" -> "local ${entry.name} = network:get(\"${entry.name}\")"
                    "var" -> "local ${entry.name} = network:var(\"${entry.name}\")"
                    else -> null
                }
                if (line != null) {
                    val text = editor.value
                    val newText = line + "\n" + text
                    editor.setValueKeepScroll(newText, 0)
                    editor.cursor = line.length
                }
                return true
            }
        }

        // Check tab bar BEFORE widgets get the click
        val tabBarY = topPos + topBarHeight
        val tabBarStartX = leftPos + cardPanelWidth + 1
        if (my >= tabBarY && my < tabBarY + tabBarHeight && mx >= tabBarStartX) {
            handleTabBarClick(mx, tabBarY, tabBarStartX)
            return true
        }

        // Check log toggle bar BEFORE widgets get the click
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val logX = leftPos + cardPanelWidth + editorPadding
        val logY = topPos + imageHeight - effectiveLogHeight
        val logW = imageWidth - cardPanelWidth - editorPadding * 2
        // Output toolbar buttons
        val btnRenderSize = 10

        // Clear button
        val clearBtnX = logX + logW - btnRenderSize * 2 - 6
        val clearBtnY = logY + 3
        if (mx >= clearBtnX && mx < clearBtnX + btnRenderSize && my >= clearBtnY && my < clearBtnY + btnRenderSize) {
            pressedButton = "clear"
            TerminalLogBuffer.clear(menu.getTerminalPos())
            logScrollOffset = 0
            minecraft?.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f)
            return true
        }

        // Copy button
        val copyBtnX = logX + logW - btnRenderSize - 3
        val copyBtnY = logY + 3
        if (mx >= copyBtnX && mx < copyBtnX + btnRenderSize && my >= copyBtnY && my < copyBtnY + btnRenderSize) {
            pressedButton = "copy"
            val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
            val text = logs.joinToString("\n") { (if (it.isError) "[ERR] " else "") + it.displayMessage }
            if (text.isNotEmpty()) {
                minecraft?.keyboardHandler?.clipboard = text
            }
            minecraft?.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f)
            return true
        }

        // Click on error line in log → jump to that line in editor
        if (!logCollapsed) {
            val logContentTop = logY + logCollapsedHeight
            val logContentBottom = logY + logPanelHeight - editorPadding
            if (mx >= logX && mx < logX + logW && my >= logContentTop && my < logContentBottom) {
                val lineNum = getClickedErrorLine(my, logContentTop, logW)
                if (lineNum != null) {
                    jumpToLine(lineNum)
                    minecraft?.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 1.2f)
                    return true
                }
            }
        }

        // Drag handle: full width of separator, extends 4px above and 3px below
        if (!logCollapsed && mx >= logX && mx <= logX + logW && my >= logY - 4 && my <= logY + 3) {
            draggingLogPanel = true
            return true
        }

        if (mx >= logX && mx <= logX + logW && my >= logY && my <= logY + logCollapsedHeight) {
            logCollapsed = !logCollapsed
            savedLogCollapsed = logCollapsed
            rebuildWithText = editor.value
            rebind()
            return true
        }

        // Also check if click is in the line number gutter area (don't let editor capture it)
        val gutterX = editorX - lineNumberWidth
        if (mx >= gutterX && mx < editorX && my >= editorY && my < editorY + editor.height) {
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun handleTabBarClick(mx: Int, tabBarY: Int, tabBarStartX: Int): Boolean {
        var tabX = tabBarStartX + 2
        for (name in scripts.keys.toList()) {
            val tabWidth = font.width(name) + 12 + if (name != "main") 10 else 0
            if (mx >= tabX && mx < tabX + tabWidth) {
                if (name != "main" && mx >= tabX + tabWidth - 10) {
                    scripts[activeTab] = editor.value
                    PlatformServices.clientNetworking.sendToServer(SaveScriptPayload(menu.getTerminalPos(), activeTab, editor.value))
                    scripts.remove(name)
                    PlatformServices.clientNetworking.sendToServer(DeleteScriptTabPayload(menu.getTerminalPos(), name))
                    if (activeTab == name) {
                        activeTab = "main"
                        rebuildWithText = scripts["main"] ?: ""
                        rebind()
                    }
                } else {
                    switchTab(name)
                }
                return true
            }
            tabX += tabWidth + 2
        }
        if (scripts.size < 8) {
            val plusWidth = font.width("+") + 8
            if (mx >= tabX && mx < tabX + plusWidth) {
                showNewTabInput = true
                newTabName = ""
                return true
            }
        }
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (draggingLogPanel) {
            val bottomY = topPos + imageHeight
            val minTop = topPos + topBarHeight + tabBarHeight + 40 // leave room for editor
            val newLogY = mouseY.toInt().coerceIn(minTop, bottomY - 30)
            logPanelHeight = (bottomY - newLogY).coerceIn(30, 200)
            rebuildWithText = editor.value
            rebind()
            return true
        }
        if (editor.isFocused && button == 0) {
            editor.mouseDragged(mouseX, mouseY, button, dragX, dragY)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        pressedButton = null
        if (draggingLogPanel) {
            draggingLogPanel = false
            savedLogPanelHeight = logPanelHeight
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    private fun switchTab(name: String) {
        if (name == activeTab) return
        // Save current tab
        scripts[activeTab] = editor.value
        PlatformServices.clientNetworking.sendToServer(SaveScriptPayload(menu.getTerminalPos(), activeTab, editor.value))
        // Switch
        activeTab = name
        undoStack.clear()
        redoStack.clear()
        rebuildWithText = scripts[name] ?: ""
        rebind()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // Check if mouse is over the card panel
        if (mouseX >= leftPos && mouseX <= leftPos + cardPanelWidth &&
            mouseY >= topPos + topBarHeight && mouseY <= topPos + imageHeight - 28) {
            val cardListTop = topPos + topBarHeight + 18
            val cardListBottom = topPos + imageHeight - 28
            val cardLineHeight = 11
            val maxVisibleCards = (cardListBottom - cardListTop) / cardLineHeight
            val maxScroll = maxOf(0, (cards.size - maxVisibleCards) * cardLineHeight)
            cardScrollOffset -= (scrollY * cardLineHeight).toInt()
            cardScrollOffset = cardScrollOffset.coerceIn(0, maxScroll)
            return true
        }
        // Forward to editor if mouse is over it
        if (editor.isMouseOver(mouseX, mouseY)) {
            return editor.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        // Check if mouse is over the log panel
        if (logCollapsed) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val logX = leftPos + cardPanelWidth + editorPadding
        val logY = topPos + imageHeight - logPanelHeight
        val logW = imageWidth - cardPanelWidth - editorPadding * 2
        if (mouseX >= logX && mouseX <= logX + logW && mouseY >= logY && mouseY <= topPos + imageHeight) {
            val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
            val logLineHeight = font.lineHeight + 1
            val maxLogWidth = logW - 6
            // Count wrapped lines for scroll calculation
            var totalWrapped = 0
            for (entry in logs) {
                val split = font.splitter.splitLines("> " + entry.displayMessage, maxLogWidth, net.minecraft.network.chat.Style.EMPTY)
                totalWrapped += split.size
            }
            val maxVisibleLines = (logPanelHeight - 14) / logLineHeight
            val maxScroll = maxOf(0, totalWrapped - maxVisibleLines)

            logScrollOffset -= scrollY.toInt()
            logScrollOffset = logScrollOffset.coerceIn(0, maxScroll)
            logAutoScroll = logScrollOffset >= maxScroll
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default inventory labels
    }

    override fun onClose() {
        scripts[activeTab] = editor.value
        PlatformServices.clientNetworking.sendToServer(SaveScriptPayload(menu.getTerminalPos(), activeTab, editor.value))
        super.onClose()
    }

    private fun autoRunLabel(): Component {
        val state = if (autoRun) "\u00A7aON" else "\u00A77OFF"
        return Component.literal("Auto: $state")
    }

    override fun isPauseScreen(): Boolean = false
}
