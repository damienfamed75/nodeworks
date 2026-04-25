package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.*
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.AutocompletePopup
import damien.nodeworks.screen.widget.ScriptEditor

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.hasAltDownCompat
import damien.nodeworks.compat.hasControlDownCompat
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.modifierBits
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import damien.nodeworks.compat.scan
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class TerminalScreen(
    menu: TerminalScreenHandler,
    playerInventory: Inventory,
    title: Component
// TODO MC 26.1.2: ACS imageWidth/imageHeight are now final. Using a large
// default that fits the "wide" layout; the layout-switch resize is commented
// out in init() and switchLayout() below. Restore once mutability is available.
) : AbstractContainerScreen<TerminalScreenHandler>(menu, playerInventory, title, 500, 280) {

    companion object {
        /** Client-side UI preferences — persisted across terminal opens, shared across all terminals */
        var savedLogCollapsed = false
        var savedLogPanelHeight = 80

        /** Max pixel width for the hover tooltip text block. Long descriptions wrap via
         *  `font.split` at this limit so the panel stays readable. */
        private const val TOOLTIP_MAX_WIDTH_PX = 200

        // Tooltip line colours match [AutocompletePopup]'s name/hint pair so hover
        // tooltips and completion suggestions read as one visual language — signature
        // in the bright-name colour, description + fallback hints in the dim-hint one.
        private const val COLOR_SIGNATURE = 0xFFCCCCCC.toInt()
        private const val COLOR_DESCRIPTION = 0xFF888888.toInt()
        private const val COLOR_PLAIN = 0xFFCCCCCC.toInt()
    }

    private lateinit var editor: ScriptEditor

    /** Matches line numbers in error messages: `main:5` or `[string "main"]:6` */
    private val errorLinePattern = Regex(""":(\d+)""")

    /** Exposed for platform-specific input suppression (e.g., blocking JEI keybinds). */
    fun isEditorFocused(): Boolean = ::editor.isInitialized && editor.isFocused

    /** True if either Ctrl key is currently physically held. CharacterEvent doesn't
     *  carry modifier bits in 26.1, so we consult [net.minecraft.client.Minecraft.hasControlDown]
     *  (which queries GLFW for the L/R control key state) to suppress the space char
     *  glfw still emits for Ctrl+Space. */
    private fun isControlHeld(): Boolean =
        net.minecraft.client.Minecraft.getInstance().hasControlDown()

    private lateinit var autocomplete: AutocompletePopup

    // All scanned client-side from block entities in the loaded world
    private val cards: List<CardSnapshot>
    private val itemTags: List<String>
    private val fluidTags: List<String>
    private val itemIds: List<String>
    private val fluidIds: List<String>
    private val variables: List<Pair<String, Int>>
    private val localApiNames: List<String>
    private val localApis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo>
    private val craftableOutputs: List<String>
    private val scriptRunning: Boolean get() = menu.isRunning()
    private var cachedNetworkColor: Int? = null
    private var autoRun: Boolean = menu.isAutoRun()

    // Multi-script state — scripts map keyed by name, activeTab tracks which is shown in editor
    private val scripts: MutableMap<String, String> = menu.getScripts().toMutableMap()
    private var activeTab: String = "main"

    // Layout constants
    private val cardPanelWidth = 80
    private val editorPadding = 4
    private val buttonHeight = 16
    private val topBarHeight = 24
    private val tabBarHeight = 17
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
    private var draggingSidebarScrollbar = false
    private var sidebarEntries = listOf<SidebarEntry>()
    private var sidebarHoverIndex = -1
    private var sidebarHoverStart = 0L

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

    /**
     * Re-indent a multi-line autocomplete snippet so its body and closing line line up
     * under the surrounding block. Snippet templates are authored as if they're
     * inserted at column 0 (the inner empty line has 4 spaces, the closing `end)` has
     * none); when the cursor is already nested inside a `for`/`function`/`if`, we glue
     * [leading] onto every newline within the snippet so the inserted text continues
     * the existing indent ladder. Cursor offset shifts forward by the count of inserted
     * indent characters that fall before the original cursor position.
     */
    private fun indentSnippetLines(
        snippet: String,
        cursorOffset: Int,
        leading: String,
    ): Pair<String, Int> {
        val out = StringBuilder(snippet.length + leading.length * 4)
        var newCursor = cursorOffset
        for ((i, ch) in snippet.withIndex()) {
            out.append(ch)
            if (ch == '\n') {
                out.append(leading)
                if (i < cursorOffset) newCursor += leading.length
            }
        }
        return out.toString() to newCursor
    }

    /**
     * VSCode-style block indent / unindent of the currently-selected lines.
     *
     * @param shift  true = unindent (Shift+Tab), false = indent (Tab).
     * @param allLinesEvenIfNoMultiSel  when true, also operates on the cursor's line
     *   even if there's no multi-line selection — used so Shift+Tab with no selection
     *   still unindents the current line.
     *
     * Indent inserts 4 spaces at the start of every covered line; unindent strips up
     * to 4 leading spaces (or one leading tab) from each. Selection is preserved to
     * cover the same logical lines after the transformation.
     */
    private fun indentSelection(shift: Boolean, allLinesEvenIfNoMultiSel: Boolean) {
        val text = editor.value
        val hadSelection = editor.hasSelection
        val origSelStart = editor.selectionStart
        val origSelEnd = editor.selectionEnd
        val cursor = editor.getCursorPosition()

        val origLines = text.split('\n')
        val lines = origLines.toMutableList()

        val startLine: Int
        val endLine: Int
        if (hadSelection) {
            startLine = text.substring(0, origSelStart).count { it == '\n' }
            // A selection that ends exactly at a line start (just after '\n') doesn't
            // actually cover that trailing line — pull the endLine back by one so we
            // don't indent a line the user didn't select. Matches VSCode.
            val endLineRaw = text.substring(0, origSelEnd).count { it == '\n' }
            endLine = if (origSelEnd > origSelStart && origSelEnd > 0 && text[origSelEnd - 1] == '\n')
                (endLineRaw - 1).coerceAtLeast(startLine) else endLineRaw
        } else {
            if (!allLinesEvenIfNoMultiSel) return
            val line = text.substring(0, cursor).count { it == '\n' }
            startLine = line
            endLine = line
        }

        val indent = "    "
        // Per-line delta tracked so remapPos() can translate each position with
        // per-column precision instead of bulk-subtracting from absolute offsets.
        val lineDelta = IntArray(lines.size)

        for (i in startLine..minOf(endLine, lines.lastIndex)) {
            val line = lines[i]
            if (shift) {
                // Strip up to 4 leading spaces; a leading tab also counts as one unindent.
                val stripped = when {
                    line.startsWith(indent) -> line.removePrefix(indent)
                    line.startsWith("\t") -> line.removePrefix("\t")
                    else -> {
                        var n = 0
                        while (n < line.length && n < 4 && line[n] == ' ') n++
                        if (n == 0) line else line.substring(n)
                    }
                }
                lineDelta[i] = -(line.length - stripped.length)
                lines[i] = stripped
            } else {
                lineDelta[i] = indent.length
                lines[i] = indent + line
            }
        }

        val newText = lines.joinToString("\n")

        fun origLineStart(idx: Int): Int {
            var p = 0; for (i in 0 until idx) p += origLines[i].length + 1; return p
        }
        fun newLineStart(idx: Int): Int {
            var p = 0; for (i in 0 until idx) p += lines[i].length + 1; return p
        }
        // Translate an original-text position to a new-text position with column
        // awareness: on an unindented line, a cursor/sel-end inside the removed
        // whitespace clamps to col 0 rather than rolling back into the previous line.
        fun remapPos(pos: Int): Int {
            val lineIdx = text.substring(0, pos).count { it == '\n' }
            val origCol = pos - origLineStart(lineIdx)
            val newCol = if (lineIdx in startLine..endLine) {
                val d = lineDelta[lineIdx]
                if (d >= 0) origCol + d
                else {
                    val removed = -d
                    if (origCol <= removed) 0 else origCol - removed
                }
            } else origCol
            return newLineStart(lineIdx) + newCol.coerceAtLeast(0)
        }

        if (hadSelection) {
            val newSelStart = remapPos(origSelStart).coerceIn(0, newText.length)
            val newSelEnd = remapPos(origSelEnd).coerceIn(0, newText.length)
            val cursorAtStart = cursor == origSelStart
            editor.setValueKeepScroll(newText, if (cursorAtStart) newSelStart else newSelEnd)
            if (cursorAtStart) editor.setSelection(newSelEnd, newSelStart)
            else editor.setSelection(newSelStart, newSelEnd)
        } else {
            val newCursor = remapPos(cursor).coerceIn(0, newText.length)
            editor.setValueKeepScroll(newText, newCursor)
        }
    }

    data class ErrorLocation(val scriptName: String?, val line: Int)

    /** Find the error script name and line number from a click position in the log panel. */
    private fun getClickedErrorLocation(mouseY: Int, logContentTop: Int, logW: Int): ErrorLocation? {
        val logLineHeight = font.lineHeight + 1
        val maxLogWidth = logW - 6
        val clickedVisualLine = (mouseY - logContentTop) / logLineHeight
        val clickedWrappedIdx = logScrollOffset + clickedVisualLine

        val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
        var wrappedIdx = 0
        for (entry in logs) {
            val fullText = "> " + entry.displayMessage
            val splitCount =
                font.splitter.splitLines(fullText, maxLogWidth, net.minecraft.network.chat.Style.EMPTY).size
            if (clickedWrappedIdx in wrappedIdx until wrappedIdx + splitCount) {
                if (entry.isError) {
                    // Match [string "name"]:line or name:line
                    val bracketMatch = Regex("""\[string "(\w+)"\]:(\d+)""").find(entry.message)
                    if (bracketMatch != null) {
                        return ErrorLocation(bracketMatch.groupValues[1], bracketMatch.groupValues[2].toInt())
                    }
                    val simpleMatch = Regex("""(\w+):(\d+)""").find(entry.message)
                    if (simpleMatch != null) {
                        return ErrorLocation(simpleMatch.groupValues[1], simpleMatch.groupValues[2].toInt())
                    }
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
        errorHighlightTime = net.minecraft.util.Util.getMillis()
    }

    private fun rebind() {
        clearWidgets()
        init()
    }

    // Layout presets
    private data class SidebarEntry(val name: String, val color: Int, val iconU: Int, val iconV: Int, val type: String)

    enum class TerminalLayout(val w: Int, val h: Int, val icon: Icons) {
        SMALL(320, 220, Icons.LAYOUT_SMALL),
        WIDE(480, 220, Icons.LAYOUT_WIDE),
        TALL(320, 300, Icons.LAYOUT_TALL),
        LARGE(480, 300, Icons.LAYOUT_LARGE)
    }

    private var currentLayout = TerminalLayout.entries.getOrElse(menu.getLayoutIndex()) { TerminalLayout.SMALL }

    init {
        damien.nodeworks.compat.AcsCompat.setImageSize(this, currentLayout.w, currentLayout.h)


        // Scan client-side block entities for all autocomplete data
        val scannedCards = mutableListOf<CardSnapshot>()
        val scannedVars = mutableListOf<Pair<String, Int>>()
        val scannedLocal = mutableListOf<String>()
        val scannedLocalApis =
            mutableListOf<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo>()
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
                                    // Only Processing-Storage-kind crystals produce an API autocomplete
                                    // surface. A Network-Controller-kind crystal stuffed into a Receiver
                                    // Antenna is a type mismatch (see ReceiverAntennaBlockEntity's status
                                    // 7) — walking the antenna for `getAvailableApis()` would return
                                    // empty anyway, but being explicit here keeps the intent visible.
                                    if (chipData != null
                                        && chipData.kind == damien.nodeworks.item.BroadcastSourceKind.PROCESSING_STORAGE
                                        && clientLevel.isLoaded(chipData.pos)) {
                                        val broadcast = clientLevel.getBlockEntity(chipData.pos)
                                        if (broadcast is damien.nodeworks.block.entity.BroadcastAntennaBlockEntity) {
                                            // Mirror the local ProcessingStorage case: in addition to the
                                            // output-item autocomplete list, feed the API name + info into
                                            // the same lists a local processing storage would fill. Without
                                            // this, `network:craft("...")` wouldn't suggest remote recipes
                                            // even though the Diagnostic Tool and Inventory Terminal both
                                            // see them (they use the server-side NetworkDiscovery path,
                                            // which already walks the antenna).
                                            for (api in broadcast.getAvailableApis()) {
                                                scannedLocal.add(api.name)
                                                scannedLocalApis.add(api)
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

        // Remote cross-dim APIs pre-resolved by the server (via the Receiver Antenna's
        // paired Broadcast Antenna in another dimension). We can't read those BEs
        // client-side, so the server shipped the API list in openData; fold it into
        // the same lists a local ProcessingStorage would fill so autocomplete treats
        // them uniformly.
        for (api in menu.getRemoteApis()) {
            scannedLocal.add(api.name)
            scannedLocalApis.add(api)
            scannedProcessable.addAll(api.outputItemIds)
        }

        // Assign auto-aliases to unnamed cards. Routes through the shared
        // [autoAliasPrefix] so client-side and server-side discovery always
        // produce the same names — without it the sidebar drifted from
        // `network:get(...)` lookups when type-specific overrides were added.
        val counters = mutableMapOf<String, Int>()
        for (card in scannedCards) {
            if (card.alias == null) {
                val type = card.capability.type
                val count = counters.getOrDefault(type, 0) + 1
                counters[type] = count
                card.autoAlias = "${damien.nodeworks.network.autoAliasPrefix(type)}_$count"
            }
        }

        // Item + fluid tag/id lists from the client registry — 26.1 replaces `getTagNames()`
        // (Stream<TagKey>) with `getTags()` (Stream<HolderSet.Named<T>>); the
        // tag key is exposed via key(), and its Identifier via the record
        // component `location`.
        val scannedTags = net.minecraft.core.registries.BuiltInRegistries.ITEM.getTags()
            .map { it.key().location.toString() }
            .sorted()
            .toList()
        val scannedFluidTags = net.minecraft.core.registries.BuiltInRegistries.FLUID.getTags()
            .map { it.key().location.toString() }
            .sorted()
            .toList()
        val scannedItemIds = net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()
            .map { it.toString() }
            .sorted()
            .toList()
        // Filter out minecraft:empty and flowing variants — users almost always want source
        // fluids (minecraft:water, not minecraft:flowing_water) since that's what shows up
        // inside tanks.
        val scannedFluidIds = net.minecraft.core.registries.BuiltInRegistries.FLUID.keySet()
            .map { it.toString() }
            .filter { !it.endsWith(":empty") && !it.contains(":flowing_") }
            .sorted()
            .toList()

        cards = scannedCards
        itemTags = scannedTags
        fluidTags = scannedFluidTags
        itemIds = scannedItemIds
        fluidIds = scannedFluidIds
        variables = scannedVars
        localApiNames = scannedLocal.distinct()
        localApis = scannedLocalApis
        craftableOutputs = (scannedCraftable + scannedProcessable).distinct()
    }

    override fun init() {
        super.init()

        val w = currentLayout.w.coerceAtMost(width - 10)
        val h = currentLayout.h.coerceAtMost(height - 10)
        damien.nodeworks.compat.AcsCompat.setImageSize(this, w, h)
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        editorX = leftPos + cardPanelWidth + editorPadding + lineNumberWidth
        editorY = topPos + topBarHeight + tabBarHeight
        val editorW = imageWidth - cardPanelWidth - editorPadding * 2 - lineNumberWidth
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val editorH = imageHeight - topBarHeight - tabBarHeight - effectiveLogHeight - editorPadding - 4

        editor = ScriptEditor(font, editorX, editorY, editorW, editorH)

        // Feed the autocomplete's existing variable-type inference into the editor's
        // hover-doc lookup. No parallel inference — same symbol table as completion, so
        // e.g. `local cards = card; cards:setPowered(…)` hovers resolve via `Card:...`
        // consistently in both places. The editor passes the character offset of the
        // hovered token so scope walks anchor at the hover, not the cursor.
        editor.symbolTableProvider = { scopeAnchor ->
            val clamped = scopeAnchor.coerceIn(0, editor.value.length)
            autocomplete.getSymbolTable(
                editor.value,
                editor.value.substring(0, clamped),
            )
        }
        // G-on-hover → open the guidebook at the doc's anchor. Delegates to the platform
        // service so :common doesn't import GuideME (neoforge-only dep); see
        // PlatformServices.guidebook.
        editor.openGuidebookRef = { ref -> damien.nodeworks.platform.PlatformServices.guidebook.open(ref) }
        // Key binding is registered loader-side as a `KeyMapping` (rebindable in the
        // controls menu). The editor polls the held state each frame via this callback
        // — see PlatformServices.openDocsKeyHeld for the loader impl.
        editor.isOpenDocsKeyHeld = { damien.nodeworks.platform.PlatformServices.openDocsKeyHeld() }

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
                autocomplete.update(
                    editor.value,
                    editor.getCursorPosition(),
                    editorX,
                    editorY,
                    editorScrollY = editor.scrollY,
                    editorScrollX = editor.scrollX,
                )
            }
        }
        addRenderableWidget(editor)

        // Inline recipe-hint decorations. When a line contains a `network:handle("<id>"`
        // call whose id is a canonical recipe, reserve a hint row above that line and
        // render the recipe as item icons → arrow → output icons.
        editor.decorationAboveLine = { lineIdx ->
            val line = editor.getLine(lineIdx)
            if (damien.nodeworks.screen.widget.RecipeHintRenderer.detectHandleId(line) != null) {
                damien.nodeworks.screen.widget.RecipeHintRenderer.HINT_HEIGHT
            } else 0
        }
        editor.renderDecoration = { graphics, lineIdx, hintX, hintY, hintW, hintH ->
            val line = editor.getLine(lineIdx)
            val id = damien.nodeworks.screen.widget.RecipeHintRenderer.detectHandleId(line)
            if (id != null) {
                // Flag handlers whose recipe id doesn't match any registered processing
                // set on the network — visible cue that the handler will never fire.
                val isValid = localApis.any { it.name == id }
                damien.nodeworks.screen.widget.RecipeHintRenderer.render(
                    graphics, font, id, hintX, hintY, hintW, hintH, valid = isValid
                )
            }
        }

        // Fold the long canonical recipe id inside `network:handle("...")` to "..." while
        // the cursor isn't sitting inside the string. The full id stays in the buffer and
        // re-appears the moment the cursor enters the range, so editing still works.
        // Combined with the inline icon hint above, players can keep handle calls on a
        // single visual line even for recipes with many ingredients.
        editor.foldsForLine = { lineIdx ->
            val line = editor.getLine(lineIdx)
            val match = Regex("""network:handle\s*\(\s*"([^"]+)"""").find(line)
            if (match != null && match.groupValues[1].contains(">>")) {
                val idRange = match.groups[1]!!.range
                listOf(damien.nodeworks.screen.widget.ScriptEditor.Fold(idRange.first, idRange.last + 1, "..."))
            } else emptyList()
        }

        autocomplete =
            AutocompletePopup(
                font, cards, itemTags, variables, localApiNames, craftableOutputs, localApis,
                itemIds, fluidIds, fluidTags
            ) { scripts }
        // Position popups directly under the cursor's text row. Using yBottomOfLine
        // (instead of yTopOfLine of the next line) deliberately excludes any decoration
        // band above the following line — that band sits BETWEEN cursor and next line and
        // shouldn't push the popup further down.
        // The +4 here is the editor's internal textTop padding (yBottomOfLine is content-
        // relative; editorY in update() is the widget top, not the content top).
        autocomplete.lineBottomYResolver = { lineIdx ->
            editor.yBottomOfLine(lineIdx) + 4
        }

        // Top bar buttons — right-aligned: [Layout] [Run] [Stop]
        val btnY = topPos + 5
        val stopX = leftPos + imageWidth - 44
        val runX = stopX - 44
        val layoutX = runX - 24

        // Layout cycle button — shows current layout icon
        addRenderableWidget(
            damien.nodeworks.screen.widget.SlicedButton.create(
                layoutX, btnY, 20, buttonHeight, "", currentLayout.icon
            ) { _ ->
                val savedText = editor.value
                currentLayout = TerminalLayout.entries[(currentLayout.ordinal + 1) % TerminalLayout.entries.size]
                PlatformServices.clientNetworking.sendToServer(
                    SetLayoutPayload(
                        menu.getTerminalPos(),
                        currentLayout.ordinal
                    )
                )
                rebuildWithText = savedText
                rebind()
            })

        // Run button — save current tab text first, then tell server to run
        addRenderableWidget(
            damien.nodeworks.screen.widget.SlicedButton.createColored(
                runX, btnY, 40, buttonHeight, "Run",
                0xFF55CC55.toInt(), 0xFF88FF88.toInt()
            ) { _ ->
                scripts[activeTab] = editor.value
                PlatformServices.clientNetworking.sendToServer(
                    SaveScriptPayload(
                        menu.getTerminalPos(),
                        activeTab,
                        editor.value
                    )
                )
                PlatformServices.clientNetworking.sendToServer(RunScriptPayload(menu.getTerminalPos()))
            })

        // Stop button
        addRenderableWidget(
            damien.nodeworks.screen.widget.SlicedButton.createColored(
                stopX, btnY, 40, buttonHeight, "Stop",
                0xFFCC5555.toInt(), 0xFFFF8888.toInt()
            ) { _ ->
                PlatformServices.clientNetworking.sendToServer(StopScriptPayload(menu.getTerminalPos()))
            })

        // Auto-run toggle is rendered manually in renderBg and handled in mouseClicked
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Resolve network color once for the whole frame (gray if disconnected)
        val mcInst = net.minecraft.client.Minecraft.getInstance()
        val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(menu.getTerminalPos())
        val networkColor = if (reachable) {
            val termEntity = mcInst.level?.getBlockEntity(menu.getTerminalPos()) as? damien.nodeworks.network.Connectable
            if (termEntity?.networkId != null) {
                damien.nodeworks.network.NetworkSettingsRegistry.getColor(termEntity.networkId)
            } else {
                cachedNetworkColor ?: damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(
                    mcInst.level, menu.getTerminalPos()
                ).also { cachedNetworkColor = it }
            }
        } else {
            -1
        }

        // Main background
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Sidebar window frame — drawn early so sidebar content renders on top
        // NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, cardPanelWidth + editorPadding - 3, imageHeight)

        // Tab bar background — aligned with gutter/editor
        val tabBarY = topPos + topBarHeight
        val tabBarStartX = leftPos + cardPanelWidth + editorPadding
        NineSlice.PANEL_INSET.draw(
            graphics,
            tabBarStartX,
            tabBarY,
            leftPos + imageWidth - tabBarStartX - editorPadding,
            tabBarHeight
        )

        // Draw tabs
        var tabX = tabBarStartX + 3
        for (name in scripts.keys) {
            val tabWidth = font.width(name) + 12 + if (name != "main") 10 else 0 // extra space for ✕
            val isActive = name == activeTab
            val textColor = if (isActive) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()

            val tabTop = tabBarY + 1
            val tabH = tabBarHeight - 1
            val tabHovered = !isActive && mouseX >= tabX && mouseX < tabX + tabWidth && mouseY >= tabTop && mouseY < tabTop + tabH
            val tabSlice = when {
                isActive -> NineSlice.TAB_ACTIVE
                tabHovered -> NineSlice.TAB_HOVER
                else -> NineSlice.TAB_INACTIVE
            }
            tabSlice.draw(graphics, tabX, tabTop, tabWidth, tabH)
            if (isActive && networkColor >= 0) {
                NineSlice.TAB_TRIM.drawTinted(graphics, tabX, tabTop, tabWidth, tabH, networkColor, alpha = 0.7f)
            }
            val textY = tabTop + (tabH - font.lineHeight) / 2 + 1
            graphics.drawString(font, name, tabX + 6, textY, textColor, false)

            // Draw ✕ for non-main tabs
            if (name != "main") {
                val closeX = tabX + tabWidth - 10
                graphics.drawString(font, "\u00D7", closeX, textY, 0xFF666666.toInt(), false)
            }

            tabX += tabWidth + 2
        }

        // [+] button if under max tabs
        if (scripts.size < 8) {
            val plusWidth = font.width("+") + 7
            val tabTop = tabBarY + 1
            val tabH = tabBarHeight - 1
            val plusHovered = mouseX >= tabX && mouseX < tabX + plusWidth && mouseY >= tabTop && mouseY < tabTop + tabH
            val plusSlice = if (plusHovered) NineSlice.TAB_HOVER else NineSlice.TAB_INACTIVE
            plusSlice.draw(graphics, tabX, tabTop, plusWidth, tabH)
            val textY = tabTop + (tabH - font.lineHeight) / 2 + 1
            graphics.drawString(font, "+", tabX + 4, textY, 0xFF888888.toInt(), false)
        }

        // Card & variable list header
        val cardStartY = topPos + topBarHeight + 6
        graphics.drawString(font, "Network:", leftPos + 8, cardStartY, 0xFFAAAAAA.toInt())

        // Build combined sidebar entries: cards + variables
        val entries = mutableListOf<SidebarEntry>()
        for (card in cards) {
            val type = card.capability.type
            val iconU = when (type) {
                "io" -> 0; "storage" -> 16; "redstone" -> 32; "observer" -> 64; else -> 0
            }
            val color = when (type) {
                "io" -> 0xFF83E086.toInt()
                "storage" -> 0xFFAA83E0.toInt()
                "redstone" -> 0xFFF53B68.toInt()
                "observer" -> 0xFFFFEB3B.toInt()
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
        val cardListBottom = topPos + imageHeight - 70
        val cardLineHeight = 11

        sidebarEntries = entries

        graphics.enableScissor(leftPos, cardListTop, leftPos + 75, cardListBottom)
        for ((i, entry) in entries.withIndex()) {
            val y = cardListTop + i * cardLineHeight - cardScrollOffset
            if (y + cardLineHeight < cardListTop) continue
            if (y > cardListBottom) break

            // Hover highlight
            val hovered = mouseX >= leftPos && mouseX < leftPos + 75 &&
                    mouseY >= y && mouseY < y + cardLineHeight
            if (hovered) {
                graphics.fill(leftPos + 5, y, leftPos + 75, y + cardLineHeight, 0x30FFFFFF.toInt())
            }

            // 8x8 icon cropped from center of 16x16 tile in atlas
            val icon = when (entry.type) {
                "card" -> when (entry.iconU) {
                    0 -> Icons.IO_CARD
                    16 -> Icons.STORAGE_CARD
                    32 -> Icons.REDSTONE_CARD
                    64 -> Icons.OBSERVER_CARD
                    else -> Icons.IO_CARD
                }

                "var" -> Icons.VARIABLE
                else -> Icons.IO_CARD
            }
            // Observer card art uses the full 16px width of its atlas cell, so the
            // standard 8×8 small-render crops its outer columns. Draw a 10×8 slice
            // (1px wider on each side) shifted left by 1px to keep it centred under
            // the same anchor as the other 8-wide card icons.
            if (entry.iconU == 64) icon.drawSmallWide(graphics, leftPos + 6, y + 1)
            else icon.drawSmall(graphics, leftPos + 7, y + 1)

            // Track hover state for scroll timing
            if (hovered) {
                if (sidebarHoverIndex != i) {
                    sidebarHoverIndex = i
                    sidebarHoverStart = System.currentTimeMillis()
                }
            }

            // Name — scroll if hovered and text is too long
            val nameX = leftPos + 18
            val maxNameW = leftPos + 75 - nameX
            val nameW = font.width(entry.name)
            if (hovered && nameW > maxNameW) {
                val elapsed = System.currentTimeMillis() - sidebarHoverStart
                val pause = 500L
                val scrollSpeed = 20.0 // pixels per second
                val totalScroll = nameW - maxNameW + 10
                val scrollPx = if (elapsed < pause) 0
                else ((elapsed - pause) / 1000.0 * scrollSpeed).coerceAtMost(totalScroll.toDouble()).toInt()
                graphics.enableScissor(nameX, y, leftPos + 75, y + cardLineHeight)
                graphics.drawString(font, entry.name, nameX - scrollPx, y + 1, entry.color)
                graphics.disableScissor()
            } else {
                graphics.drawString(font, entry.name, nameX, y + 1, entry.color)
            }
        }
        // Reset hover if mouse left the sidebar
        if (!(mouseX >= leftPos && mouseX < leftPos + 75 && mouseY >= cardListTop && mouseY < cardListBottom)) {
            sidebarHoverIndex = -1
        }
        graphics.disableScissor()

        // Scrollbar
        val scrollbarW = 6
        val sbX = leftPos + 76
        val maxVisibleCards = (cardListBottom - cardListTop) / cardLineHeight
        if (entries.size > maxVisibleCards) {
            val scrollbarHeight = cardListBottom - cardListTop
            val thumbHeight = maxOf(12, scrollbarHeight * maxVisibleCards / entries.size)
            val maxCardScroll = maxOf(1, (entries.size - maxVisibleCards) * cardLineHeight)
            cardScrollOffset = cardScrollOffset.coerceIn(0, maxCardScroll)
            val thumbY = cardListTop + (scrollbarHeight - thumbHeight) * cardScrollOffset / maxCardScroll
            NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, cardListTop, scrollbarW, scrollbarHeight)
            val thumbSlice = if (draggingSidebarScrollbar) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            thumbSlice.draw(graphics, sbX, thumbY, scrollbarW, thumbHeight)
        }

        // Auto-run toggle — centered on sidebar
        val sidebarW = cardPanelWidth + editorPadding - 3
        val toggleW = 56
        val toggleX = leftPos + (sidebarW - toggleW) / 2 + 3
        val toggleY = topPos + imageHeight - 38
        // Deepened background behind toggle area (expanded 3px each side, 2px taller)
        val deepenH = font.lineHeight + 5 + 16 + 3 + 6 + 2
        val recessX = toggleX - 3
        val recessY = toggleY - font.lineHeight - 5 - 3
        val recessW = toggleW + 6
        NineSlice.WINDOW_RECESSED.draw(graphics, recessX, recessY, recessW, deepenH)

        // Corner screws just outside the recessed area
        val screwU = Icons.SMALL_SCREW.u + 5f
        val screwV = Icons.SMALL_SCREW.v + 5f
        val ss = 6 // screw size
        val so = 1 // offset outside the recessed edge
        graphics.blit(Icons.ATLAS, recessX - ss - so, recessY - ss - so, screwU, screwV, ss, ss, 256, 256)
        graphics.blit(Icons.ATLAS, recessX + recessW + so, recessY - ss - so, screwU, screwV, ss, ss, 256, 256)
        graphics.blit(Icons.ATLAS, recessX - ss - so, recessY + deepenH + so, screwU, screwV, ss, ss, 256, 256)
        graphics.blit(Icons.ATLAS, recessX + recessW + so, recessY + deepenH + so, screwU, screwV, ss, ss, 256, 256)

        val labelText = "Autorun"
        graphics.drawString(
            font,
            labelText,
            toggleX + (toggleW - font.width(labelText)) / 2,
            toggleY - font.lineHeight,
            0xFFAAAAAA.toInt()
        )
        val toggleU = if (autoRun) 72f else 120f
        val toggleDrawX = toggleX + (toggleW - 48) / 2 - 1
        graphics.blit(NineSlice.GUI_ATLAS, toggleDrawX, toggleY + 2, toggleU, 64f, 48, 16, 256, 256)

        // Log panel
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val logX = leftPos + cardPanelWidth + editorPadding
        val logBottomPadding = 4
        val logY = topPos + imageHeight - effectiveLogHeight - logBottomPadding
        val logW = imageWidth - cardPanelWidth - editorPadding * 2

        // Log panel background (single panel for toggle bar + content)
        NineSlice.PANEL_INSET.draw(graphics, logX, logY, logW, effectiveLogHeight)
        // Separator / drag handle
        if (!logCollapsed) {
            val hovering = mouseX >= logX && mouseX <= logX + logW && mouseY >= logY - 4 && mouseY <= logY + 3
            // Grip dots — centered on separator
            val centerX = logX + logW / 2
            val dotColor = if (hovering || draggingLogPanel) 0xFF999999.toInt() else 0xFF666666.toInt()
            for (d in -3..3) {
                graphics.fill(centerX + d * 3, logY - 2, centerX + d * 3 + 1, logY - 1, dotColor)
            }
        }

        // Toggle icon + label
        val toggleBtnX = logX + 3
        val toggleBtnY = logY + 2
        val toggleBtnSize = 10
        val toggleHovered = mouseX >= logX && mouseX < logX + logW && mouseY >= logY && mouseY < logY + logCollapsedHeight
        if (logCollapsed) {
            val expandIcon = when {
                pressedButton == "toggle" -> Icons.EXPAND_PRESSED
                toggleHovered -> Icons.EXPAND_HOVER
                else -> Icons.EXPAND_IDLE
            }
            expandIcon.draw(graphics, toggleBtnX - 3, toggleBtnY - 3)
        } else {
            val collapseIcon = when {
                pressedButton == "toggle" -> Icons.COLLAPSE_PRESSED
                toggleHovered -> Icons.COLLAPSE_HOVER
                else -> Icons.COLLAPSE_IDLE
            }
            collapseIcon.draw(graphics, toggleBtnX - 3, toggleBtnY - 3)
        }
        graphics.drawString(font, "Output", logX + 14, logY + 2, 0xFF888888.toInt())

        if (!logCollapsed) {
            // Output toolbar buttons (only when expanded)
            val btnRenderSize = 10

            // Clear button (left of copy)
            val clearBtnX = logX + logW - btnRenderSize * 2 - 6
            val clearBtnY = logY + 5
            val clearHovered = mouseX >= clearBtnX && mouseX < clearBtnX + btnRenderSize &&
                    mouseY >= clearBtnY && mouseY < clearBtnY + btnRenderSize
            val clearIcon = when {
                pressedButton == "clear" -> Icons.TRASH_PRESSED; clearHovered -> Icons.TRASH_HOVER; else -> Icons.TRASH_IDLE
            }
            clearIcon.draw(graphics, clearBtnX - 3, clearBtnY - 3)

            // Copy button
            val copyBtnX = logX + logW - btnRenderSize - 3
            val copyBtnY = logY + 5
            val copyHovered = mouseX >= copyBtnX && mouseX < copyBtnX + btnRenderSize &&
                    mouseY >= copyBtnY && mouseY < copyBtnY + btnRenderSize
            val copyIcon = when {
                pressedButton == "copy" -> Icons.COPY_PRESSED; copyHovered -> Icons.COPY_HOVER; else -> Icons.COPY_IDLE
            }
            copyIcon.draw(graphics, copyBtnX - 3, copyBtnY - 3)
            // Log content area
            val logContentTop = logY + logCollapsedHeight
            val logContentBottom = logY + logPanelHeight - editorPadding

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
                    mouseX >= logX && mouseX < logX + logW
                ) {
                    val textW = font.width(line.text)
                    graphics.fill(
                        logX + 3,
                        entryY + font.lineHeight,
                        logX + 3 + textW,
                        entryY + font.lineHeight + 1,
                        0xAAFF5555.toInt()
                    )
                }
            }
            graphics.disableScissor()
        }

        // Content border — overlays everything, extends 3px up into top bar
        // val contentLeft = leftPos + cardPanelWidth + editorPadding - 3
        // val contentTop = topPos + topBarHeight - 3
        // val contentRight = leftPos + imageWidth
        // val contentBottom = topPos + imageHeight
        // NineSlice.CONTENT_BORDER.draw(
        //     graphics,
        //     contentLeft,
        //     contentTop,
        //     contentRight - contentLeft,
        //     contentBottom - contentTop
        // )

        // Re-draw top bar over everything
        NineSlice.drawTitleBar(
            graphics,
            font,
            Component.empty(),
            leftPos,
            topPos,
            imageWidth,
            topBarHeight,
            networkColor
        )

        // Status indicator on top of top bar
        val statusX = leftPos + 4
        val statusIconY = topPos + (topBarHeight - 16) / 2 + 1
        if (scriptRunning) {
            Icons.CRYSTAL_ACTIVE.draw(graphics, statusX, statusIconY)
        } else {
            Icons.CRYSTAL_INACTIVE.draw(graphics, statusX, statusIconY)
        }
        val statusText = if (scriptRunning) "Running" else "Stopped"
        val statusTextColor = if (scriptRunning) 0xFFD3FFFF.toInt() else 0xFF888888.toInt()
        val statusTextY = topPos + (topBarHeight - font.lineHeight) / 2 + 2
        graphics.drawString(font, statusText, statusX + 21, statusTextY, statusTextColor)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // Line number gutter
        renderLineNumbers(graphics)

        // Hover tooltip — single 9-sliced panel backed by LuaApiDocs when available,
        // falling back to the in-file methodSignatures table for symbols we haven't
        // documented yet. Hidden while the autocomplete popup or new-tab prompt is up
        // so popups don't stack. Active Hold-G takes precedence: we force-hide
        // autocomplete below so the tooltip (with its progress bar) stays readable.
        if (editor.isOpenDocsHoldActive() && autocomplete.visible) {
            autocomplete.hide()
        }
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
            NineSlice.INPUT_FIELD.draw(graphics, inputX - 2, inputY - 2, inputW + 4, inputH + 4)
            val displayText = if (newTabName.isEmpty()) "enter name..." else newTabName
            val displayColor = if (newTabName.isEmpty()) 0xFF666666.toInt() else 0xFFFFFFFF.toInt()
            graphics.drawString(font, displayText, inputX + 4, inputY + 6, displayColor, false)
            if (newTabName.isNotEmpty() || (net.minecraft.util.Util.getMillis() / 500) % 2 == 0L) {
                val cursorX = inputX + 4 + font.width(newTabName)
                graphics.fill(cursorX, inputY + 4, cursorX + 1, inputY + inputH - 4, 0xFFFFFFFF.toInt())
            }
        }

        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)
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
        "error" to "error(message: string) — throw an error",
        "clock" to "clock() → number (server tick count)",
        "tostring" to "tostring(value: any) → string",
        "tonumber" to "tonumber(value: any) → number?",
        "type" to "type(value: any) → string",
        "pairs" to "pairs(t: table) → iterator",
        "ipairs" to "ipairs(t: table) → iterator",
        "require" to "require(module: string) → table"
    )

    private fun renderTypeTooltip(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (mouseX < editorX || mouseX > editorX + editor.width ||
            mouseY < editorY || mouseY > editorY + editor.height
        ) return

        // One accumulated list of lines. Each line has its own colour so signatures
        // render yellow and descriptions render gray within the same 9-sliced panel.
        data class Line(val text: String, val color: Int)
        val accum = mutableListOf<Line>()

        // Prefer LuaApiDocs — type-aware, covers modules/methods/types via the shared
        // resolver (which already hops module → Type via `LuaApiDocs.moduleTypes` and
        // typed-local via the autocomplete symbol table).
        val doc = editor.resolveDocAt(mouseX, mouseY)
        val word = editor.getWordAt(mouseX.toDouble(), mouseY.toDouble())
        if (doc != null) {
            doc.signature?.let { accum.add(Line(it, COLOR_SIGNATURE)) }
            // Hard-wrap at [TOOLTIP_MAX_WIDTH_PX] so long descriptions don't run past
            // the screen. `StringSplitter.splitLines` returns `FormattedText` whose
            // `getString()` gives us the displayable text; going through `font.split`'s
            // `FormattedCharSequence` path earlier was wrong — `.toString()` on those
            // returns the Lambda class name, not the character stream.
            for (rawLine in doc.description.split('\n')) {
                for (part in font.splitter.splitLines(rawLine, TOOLTIP_MAX_WIDTH_PX, net.minecraft.network.chat.Style.EMPTY)) {
                    accum.add(Line(part.string, COLOR_DESCRIPTION))
                }
            }
        } else if (word != null) {
            // Fallback path for tokens LuaApiDocs doesn't cover: the in-file
            // methodSignatures map, user-defined function scanner, and the
            // autocomplete symbol table (for plain `word: Type` hints on locals).
            //
            // The symbol-table lookup uses the HOVER position as its scope anchor, not
            // the cursor. Otherwise hovering a function parameter (e.g. `from` in
            // `function getThings(from: { CardHandle })`) wouldn't resolve while the
            // cursor sits outside the function body — the param's scope would be closed
            // at cursor time but is still open at the hover line.
            val fallback = methodSignatures[word]
                ?: autocomplete.getFunctionSignature(word, editor.value)
                ?: run {
                    val hoverAnchor = editor.getHoverScopeAnchor(mouseX, mouseY)
                        ?: editor.getCursorPosition()
                    val clamped = hoverAnchor.coerceIn(0, editor.value.length)
                    val symbols = autocomplete.getSymbolTable(
                        editor.value,
                        editor.value.substring(0, clamped),
                    )
                    symbols[word]?.let { "$word: $it" }
                }
                ?: return
            accum.add(Line(fallback, COLOR_PLAIN))
        } else {
            return
        }

        // Hold-G progress footer — only when the current doc points at a guidebook
        // anchor. Always add a blank spacer above it so the bar sits below text.
        val showProgressFooter = doc?.guidebookRef != null
        if (showProgressFooter) {
            accum.add(Line("", 0))
        }

        // Compute panel size. Progress footer doesn't contribute width (pipe chars are
        // narrow, sized against the text lines above).
        val textWidth = accum.maxOf { font.width(it.text) }
        val lineHeight = font.lineHeight
        val progressLineHeight = if (showProgressFooter) lineHeight else 0
        val tooltipW = textWidth + 6
        val tooltipH = accum.size * lineHeight + progressLineHeight + 4

        // Position the tooltip above-right of the cursor by default, mirroring vanilla's
        // item-tooltip convention. When that would clip out of the screen we flip /
        // clamp — same playbook vanilla uses internally in `GuiGraphics.renderTooltip`:
        //   * Too far right → place to the left of the cursor instead.
        //   * Too far up → place below the cursor instead.
        //   * Still doesn't fit (tooltip wider/taller than screen) → clamp to the edge
        //     so at least the tooltip text stays on-screen even if it overlaps the
        //     cursor a bit.
        val gameW = this.width
        val gameH = this.height
        var tx = mouseX + 8
        var ty = mouseY - tooltipH - 2
        if (tx + tooltipW > gameW) tx = mouseX - tooltipW - 8
        if (ty < 0) ty = mouseY + 12
        // Final clamp in case flipping still isn't enough (e.g. giant tooltip, corner
        // cursor). Coerce so the panel stays fully visible.
        tx = tx.coerceIn(0, (gameW - tooltipW).coerceAtLeast(0))
        ty = ty.coerceIn(0, (gameH - tooltipH).coerceAtLeast(0))

        NineSlice.TOOLTIP.draw(graphics, tx - 1, ty - 1, tooltipW + 2, tooltipH + 2)
        for ((i, line) in accum.withIndex()) {
            graphics.drawString(font, line.text, tx + 3, ty + 2 + i * lineHeight, line.color)
        }

        if (showProgressFooter) {
            renderHoldGProgressFooter(
                graphics,
                tx + 3,
                ty + 2 + accum.size * lineHeight,
                tooltipW - 6,
                editor.getHoldProgressFraction(),
            )
        }
    }

    /** Draws the "Hold G for docs" affordance at the bottom of the tooltip. At zero
     *  progress it's plain text in dark gray; while the player is holding, the text is
     *  replaced by a bar of `|` characters that fills left-to-right as progress
     *  advances — same visual language GuideME uses on item tooltips. */
    private fun renderHoldGProgressFooter(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        fraction: Float,
    ) {
        if (fraction <= 0f) {
            graphics.drawString(font, "Hold G for docs", x, y, 0xFF555555.toInt())
            return
        }
        val charWidth = font.width("|")
        val totalChars = (width / charWidth).coerceAtLeast(1)
        val filled = (fraction * totalChars).toInt().coerceIn(0, totalChars)
        val filledStr = "|".repeat(filled)
        val emptyStr = "|".repeat(totalChars - filled)
        graphics.drawString(font, filledStr, x, y, 0xFFAAAAAA.toInt())
        graphics.drawString(font, emptyStr, x + filled * charWidth, y, 0xFF555555.toInt())
    }

    private fun renderLineNumbers(graphics: GuiGraphicsExtractor) {
        val text = editor.value
        val lineHeight = font.lineHeight

        val gutterX = editorX - lineNumberWidth
        val gutterTop = editorY
        val gutterBottom = editorY + editor.height

        // Gutter background — matches editor background, no separator
        graphics.fill(gutterX, gutterTop, editorX, gutterBottom, 0xFF0D0D0D.toInt())

        // Border around gutter (top, bottom, left)
        val borderColor = if (editor.isFocused) 0xFF555555.toInt() else 0xFF333333.toInt()
        graphics.fill(gutterX, gutterTop, editorX, gutterTop + 1, borderColor)           // top
        graphics.fill(gutterX, gutterBottom - 1, editorX, gutterBottom, borderColor)      // bottom
        graphics.fill(gutterX, gutterTop, gutterX + 1, gutterBottom, borderColor)         // left

        // Count total lines
        val totalLines = text.count { it == '\n' } + 1

        // Inner top of the editor, adjusted for scroll (matches ScriptEditor's padding)
        val innerTop = editor.y + 4 - editor.scrollY

        // Error highlight fades out over 2 seconds
        val highlightElapsed =
            if (errorHighlightLine >= 0) net.minecraft.util.Util.getMillis() - errorHighlightTime else Long.MAX_VALUE
        val highlightFadeDuration = 2000L
        val highlightAlpha = if (highlightElapsed < highlightFadeDuration) {
            ((1.0 - highlightElapsed.toDouble() / highlightFadeDuration) * 0x40).toInt().coerceIn(0, 0x40)
        } else 0

        graphics.enableScissor(gutterX, gutterTop, editorX - 1, gutterBottom)
        for (line in 1..totalLines) {
            // Use the editor's line-Y helper so gutter numbers align with text rows even
            // when decorations (recipe-icon hints) push specific lines down.
            val y = innerTop + editor.yTopOfLine(line - 1)
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

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
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
                        PlatformServices.clientNetworking.sendToServer(
                            CreateScriptTabPayload(
                                menu.getTerminalPos(),
                                newTabName
                            )
                        )
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
                return super.keyPressed(event)
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
                (keyCode == InputConstants.KEY_Y && (modifiers and 2) != 0)
            ) {
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
                autocomplete.update(
                    editor.value,
                    editor.getCursorPosition(),
                    editorX,
                    editorY,
                    forced = true,
                    editorScrollY = editor.scrollY,
                    editorScrollX = editor.scrollX,
                )
                return true
            }

            // Autocomplete navigation
            if (autocomplete.visible) {
                when (keyCode) {
                    InputConstants.KEY_UP -> {
                        autocomplete.moveUp(); return true
                    }

                    InputConstants.KEY_DOWN -> {
                        autocomplete.moveDown(); return true
                    }

                    InputConstants.KEY_RETURN, InputConstants.KEY_TAB -> {
                        val textBeforeAccept = editor.value
                        val cursorAtAccept = editor.getCursorPosition()
                        val afterCursor = textBeforeAccept.substring(cursorAtAccept)
                        val result = autocomplete.accept(afterCursor)
                        if (result != null) {
                            // Suppress autocomplete updates during insertion
                            suppressAutocomplete = true
                            // Delete the typed prefix by manipulating text directly. Also
                            // consume auto-pair chars following the cursor for full-block
                            // snippets (see AutocompletePopup.consumesAutoclose).
                            val text = textBeforeAccept
                            val cursorPos = cursorAtAccept
                            val deleteStart = cursorPos - result.deleteCount
                            val deleteEnd = (cursorPos + result.consumeAfter).coerceAtMost(text.length)
                            // Auto-import: if the suggestion carries a local-binding line
                            // (card or variable), prepend it to the script before doing the
                            // in-place replacement. Idempotent — if the exact line is
                            // already in the script we skip the prepend so accepting the
                            // same suggestion twice in a row doesn't produce duplicates.
                            val importPrefix = result.autoImportLine?.let { line ->
                                if (text.contains(line)) "" else "$line\n"
                            } ?: ""
                            // Snippet expansion preserves the surrounding indent. Multi-line
                            // snippet bodies are authored with their *own* relative indent
                            // (an empty body line + closing `end)` flush left), so when they
                            // land deep inside an existing block we have to glue the line's
                            // leading whitespace onto every internal newline. Without this
                            // fix the inserted body and `end)` clip back to column 0,
                            // forcing the user to manually re-indent every accept inside a
                            // for / function / if block.
                            val lineStart = text.lastIndexOf('\n', (deleteStart - 1).coerceAtLeast(0)) + 1
                            val lineLeading = text.substring(lineStart, deleteStart)
                                .takeWhile { it == ' ' || it == '\t' }
                            val (insertText, cursorOffset) =
                                if (lineLeading.isNotEmpty() && '\n' in result.insertText)
                                    indentSnippetLines(result.insertText, result.cursorOffset, lineLeading)
                                else result.insertText to result.cursorOffset
                            val newText = importPrefix +
                                text.substring(0, deleteStart) +
                                insertText +
                                text.substring(deleteEnd)
                            editor.setValueKeepScroll(newText, importPrefix.length + deleteStart + cursorOffset)
                            suppressAutocomplete = false
                            // Only re-trigger autocomplete when the accepted result lands the
                            // cursor *inside* the inserted text — e.g. a snippet with cursor in
                            // empty quotes, or `func(` auto-closed to `func()` with the cursor
                            // between the parens. Those are genuine "you might want another
                            // completion right here" moments. A plain value completion (cursor
                            // at end of insertion) should dismiss the popup and wait for the
                            // next keystroke, matching VSCode's behaviour — otherwise accepting
                            // `cobblestone` in `"$item:cobblestone|"` pops the list right back
                            // up with `cobblestone_slab` etc. because the prefix still matches.
                            if (cursorOffset < insertText.length) {
                                autocomplete.update(
                                    editor.value,
                                    editor.getCursorPosition(),
                                    editorX,
                                    editorY,
                                    editorScrollY = editor.scrollY
                                )
                            }
                            return true
                        }
                    }
                }
            }

            // Tab / Shift+Tab — VSCode-style block indent when the selection spans
            // multiple lines; otherwise fall back to inserting 4 spaces at the cursor.
            if (keyCode == InputConstants.KEY_TAB && !autocomplete.visible) {
                val shift = (modifiers and 1) != 0
                val text = editor.value
                val selStart = editor.selectionStart
                val selEnd = editor.selectionEnd
                val multiLineSelection = editor.hasSelection &&
                        text.substring(selStart, selEnd).contains('\n')

                if (shift) {
                    // Shift+Tab: always unindent. With no selection (or single-line sel),
                    // unindent just the cursor's line; with multi-line selection, unindent
                    // every covered line.
                    indentSelection(shift = true, allLinesEvenIfNoMultiSel = true)
                    return true
                }
                if (multiLineSelection) {
                    indentSelection(shift = false, allLinesEvenIfNoMultiSel = true)
                    return true
                }
                // Default: insert 4 spaces at cursor (replaces selection if any — same
                // as VSCode on single-line selections).
                val spaceEvent = CharacterEvent(' '.code)
                for (i in 0..3) {
                    editor.charTyped(spaceEvent)
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
                        autocomplete.update(
                            editor.value,
                            editor.getCursorPosition(),
                            editorX,
                            editorY,
                            editorScrollY = editor.scrollY
                        )
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
                        autocomplete.update(
                            editor.value,
                            editor.getCursorPosition(),
                            editorX,
                            editorY,
                            editorScrollY = editor.scrollY
                        )
                        return true
                    }
                }
            }

            editor.keyPressed(event)
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
                autocomplete.update(
                    editor.value,
                    editor.getCursorPosition(),
                    editorX,
                    editorY,
                    editorScrollY = editor.scrollY,
                    editorScrollX = editor.scrollX,
                )
            }
            // Always consume key events when editor is focused to prevent other mods from stealing them
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val codePoint = event.character
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

            // Block the space from Ctrl+Space — keyPressed already fired the autocomplete
            // trigger. 26.1's CharacterEvent carries only the codepoint (no modifier bits),
            // so query the keyboard directly via GLFW to detect Ctrl.
            if (codePoint == ' ' && isControlHeld()) {
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
                val newText = text.substring(0, selStart) + codePoint + text.substring(
                    selStart,
                    selEnd
                ) + closingChar + text.substring(selEnd)
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
                    editor.charTyped(event)
                }
            }
            autocomplete.update(
                editor.value,
                editor.getCursorPosition(),
                editorX,
                editorY,
                editorScrollY = editor.scrollY
            )
            // Always consume when editor is focused to prevent other mods stealing input
            return true
        }
        return super.charTyped(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Auto-run toggle click
        val sidebarW = cardPanelWidth + editorPadding - 3
        val toggleW = 56
        val toggleX = leftPos + (sidebarW - toggleW) / 2 + 3
        val toggleY = topPos + imageHeight - 38
        if (mx >= toggleX && mx < toggleX + toggleW && my >= toggleY && my < toggleY + 16) {
            autoRun = !autoRun
            PlatformServices.clientNetworking.sendToServer(ToggleAutoRunPayload(menu.getTerminalPos(), autoRun))
            minecraft?.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f)
            return true
        }

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

        // Sidebar scrollbar drag start
        val cardStartY = topPos + topBarHeight + 6
        val cardListTop = cardStartY + 12
        val cardListBottom = topPos + imageHeight - 70
        val cardLineHeight = 11
        val sbX = leftPos + 76
        if (mx >= sbX && mx < sbX + 6 && my >= cardListTop && my < cardListBottom) {
            draggingSidebarScrollbar = true
            return true
        }

        // Check sidebar click — insert reference at top of file
        if (mx >= leftPos && mx < leftPos + 75 && my >= cardListTop && my < cardListBottom) {
            val clickedIndex = (my - cardListTop + cardScrollOffset) / cardLineHeight
            if (clickedIndex in sidebarEntries.indices) {
                val entry = sidebarEntries[clickedIndex]
                // Lua identifiers can't contain spaces or punctuation, so a
                // card/variable named "iron ingots" or "iron-ingots" becomes
                // "ironIngots" on the left side. The string argument keeps the
                // original name verbatim — that's what the network looks up.
                // The fallback kicks in if the name has NO identifier chars
                // at all (e.g. "!@#$%"), so the inserted line still compiles.
                val ident = when (entry.type) {
                    "card" -> damien.nodeworks.script.LuaIdent.toLuaIdentifier(entry.name, "card")
                    "var" -> damien.nodeworks.script.LuaIdent.toLuaIdentifier(entry.name, "var")
                    else -> damien.nodeworks.script.LuaIdent.toLuaIdentifier(entry.name, "x")
                }
                val line = when (entry.type) {
                    "card" -> "local $ident = network:get(\"${entry.name}\")"
                    "var" -> "local $ident = network:var(\"${entry.name}\")"
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
        val tabBarStartX = leftPos + cardPanelWidth + editorPadding
        if (my >= tabBarY && my < tabBarY + tabBarHeight && mx >= tabBarStartX) {
            handleTabBarClick(mx, tabBarY, tabBarStartX)
            return true
        }

        // Check log toggle bar BEFORE widgets get the click
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val logX = leftPos + cardPanelWidth + editorPadding
        val logY = topPos + imageHeight - effectiveLogHeight - 4
        val logW = imageWidth - cardPanelWidth - editorPadding * 2
        // Output toolbar buttons
        val btnRenderSize = 10

        // Clear button
        val clearBtnX = logX + logW - btnRenderSize * 2 - 6
        val clearBtnY = logY + 5
        if (mx >= clearBtnX && mx < clearBtnX + btnRenderSize && my >= clearBtnY && my < clearBtnY + btnRenderSize) {
            pressedButton = "clear"
            TerminalLogBuffer.clear(menu.getTerminalPos())
            logScrollOffset = 0
            minecraft?.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f)
            return true
        }

        // Copy button
        val copyBtnX = logX + logW - btnRenderSize - 3
        val copyBtnY = logY + 5
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
                val errorLoc = getClickedErrorLocation(my, logContentTop, logW)
                if (errorLoc != null) {
                    // Switch to the correct tab if error is from a different script
                    val targetTab = errorLoc.scriptName
                    if (targetTab != null && targetTab != activeTab && targetTab in scripts) {
                        switchTab(targetTab)
                    }
                    jumpToLine(errorLoc.line)
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
            pressedButton = "toggle"
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

        return super.mouseClicked(event, doubleClick)
    }

    private fun handleTabBarClick(mx: Int, tabBarY: Int, tabBarStartX: Int): Boolean {
        var tabX = tabBarStartX + 3
        for (name in scripts.keys.toList()) {
            val tabWidth = font.width(name) + 12 + if (name != "main") 10 else 0
            if (mx >= tabX && mx < tabX + tabWidth) {
                if (name != "main" && mx >= tabX + tabWidth - 10) {
                    scripts[activeTab] = editor.value
                    PlatformServices.clientNetworking.sendToServer(
                        SaveScriptPayload(
                            menu.getTerminalPos(),
                            activeTab,
                            editor.value
                        )
                    )
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
            val plusWidth = font.width("+") + 7
            if (mx >= tabX && mx < tabX + plusWidth) {
                showNewTabInput = true
                newTabName = ""
                return true
            }
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (draggingSidebarScrollbar) {
            val cardStartY = topPos + topBarHeight + 6
            val cardListTop = cardStartY + 12
            val cardListBottom = topPos + imageHeight - 70
            val cardLineHeight = 11
            val scrollbarHeight = cardListBottom - cardListTop
            val maxVisibleCards = scrollbarHeight / cardLineHeight
            val maxCardScroll = maxOf(1, (sidebarEntries.size - maxVisibleCards) * cardLineHeight)
            val thumbHeight = maxOf(12, scrollbarHeight * maxVisibleCards / sidebarEntries.size)
            val scrollRange = scrollbarHeight - thumbHeight
            if (scrollRange > 0) {
                val relY = (mouseY.toInt() - cardListTop - thumbHeight / 2).toFloat() / scrollRange
                cardScrollOffset = (relY * maxCardScroll).toInt().coerceIn(0, maxCardScroll)
            }
            return true
        }
        if (draggingLogPanel) {
            val bottomY = topPos + imageHeight - 4 // account for bottom padding
            val minTop = topPos + topBarHeight + tabBarHeight + 40 // leave room for editor
            val newLogY = mouseY.toInt().coerceIn(minTop, bottomY - 30)
            logPanelHeight = (bottomY - newLogY).coerceIn(30, 200)
            rebuildWithText = editor.value
            rebind()
            return true
        }
        if (editor.isFocused && button == 0) {
            editor.mouseDragged(event, dragX, dragY)
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        pressedButton = null
        draggingSidebarScrollbar = false
        if (draggingLogPanel) {
            draggingLogPanel = false
            savedLogPanelHeight = logPanelHeight
            return true
        }
        return super.mouseReleased(event)
    }

    private fun switchTab(name: String) {
        if (name == activeTab) return
        // Save current tab
        scripts[activeTab] = editor.value
        PlatformServices.clientNetworking.sendToServer(
            SaveScriptPayload(
                menu.getTerminalPos(),
                activeTab,
                editor.value
            )
        )
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
            mouseY >= topPos + topBarHeight && mouseY <= topPos + imageHeight - 28
        ) {
            val cardListTop = topPos + topBarHeight + 18
            val cardListBottom = topPos + imageHeight - 70
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
        val logY = topPos + imageHeight - logPanelHeight - 4
        val logW = imageWidth - cardPanelWidth - editorPadding * 2
        if (mouseX >= logX && mouseX <= logX + logW && mouseY >= logY && mouseY <= topPos + imageHeight - 4) {
            val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
            val logLineHeight = font.lineHeight + 1
            val maxLogWidth = logW - 6
            // Count wrapped lines for scroll calculation
            var totalWrapped = 0
            for (entry in logs) {
                val split = font.splitter.splitLines(
                    "> " + entry.displayMessage,
                    maxLogWidth,
                    net.minecraft.network.chat.Style.EMPTY
                )
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

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Don't render default inventory labels
    }

    override fun onClose() {
        scripts[activeTab] = editor.value
        PlatformServices.clientNetworking.sendToServer(
            SaveScriptPayload(
                menu.getTerminalPos(),
                activeTab,
                editor.value
            )
        )
        super.onClose()
    }

    override fun removed() {
        // Persist the live editor buffer before the screen is swapped out. This fires
        // on ANY setScreen (unlike onClose, which only fires for true close-to-world).
        // Critical for the Hold-G path: opening the guidebook replaces our screen with
        // a GuideScreen, and when the player closes that guide (`returnToOnClose`
        // brings them back here), `init()` rebuilds the editor widget from scratch and
        // reads `rebuildWithText` / `scripts[activeTab]` — so if we don't save on the
        // way out, whatever they had typed since the last manual save is lost.
        if (::editor.isInitialized) {
            rebuildWithText = editor.value
            scripts[activeTab] = editor.value
        }
        super.removed()
    }

    private fun autoRunLabel(): Component {
        val state = if (autoRun) "\u00A7aON" else "\u00A77OFF"
        return Component.literal("Auto: $state")
    }

    override fun isPauseScreen(): Boolean = false
}
