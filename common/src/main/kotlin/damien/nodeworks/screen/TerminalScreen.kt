package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.*
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.TerminalScreenHandler
import damien.nodeworks.screen.widget.AutocompletePopup
import damien.nodeworks.screen.widget.LuaSyntaxHighlighter
import net.minecraft.client.gui.components.MultilineTextField

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.components.SpriteIconButton
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class TerminalScreen(
    menu: TerminalScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TerminalScreenHandler>(menu, playerInventory, title) {

    private lateinit var editor: MultiLineEditBox
    private lateinit var autocomplete: AutocompletePopup
    private val cards: List<CardSnapshot> = menu.getCards()
    private val itemTags: List<String> = menu.getItemTags()
    private val variableNames: List<String> = menu.getVariableNames()
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
    private val logPanelHeight = 50

    // New tab input state
    private var showNewTabInput = false
    private var newTabName = ""

    // Editor position (stored for autocomplete positioning)
    private var editorX = 0
    private var editorY = 0
    private val lineNumberWidth = 28 // gutter width for line numbers

    // Card panel scroll state
    private var cardScrollOffset = 0

    // Log scroll state
    private var logScrollOffset = 0
    private var logAutoScroll = true
    private var logCollapsed = false
    private val logCollapsedHeight = 12 // just enough for the toggle bar

    // Used to preserve editor text across layout changes
    private var rebuildWithText: String? = null
    // Suppresses autocomplete updates during programmatic text insertion
    private var suppressAutocomplete = false

    // Undo/redo stacks
    private data class UndoState(val text: String, val cursor: Int)
    private val undoStack = ArrayDeque<UndoState>(50)
    private val redoStack = ArrayDeque<UndoState>(50)
    private var lastSavedText = ""
    private var undoInProgress = false

    private fun applyUndoState(state: UndoState, previousScroll: Double) {
        editor.setValue(state.text)
        val tf = getTextField()
        if (tf != null) {
            tf.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, state.cursor.coerceIn(0, state.text.length))
        }
        // Restore scroll position — setValue resets it, so set it back
        editor.setScrollAmount(previousScroll.coerceAtMost(editor.maxScrollAmount().toDouble()))
        lastSavedText = state.text
    }

    private fun rebind() {
        clearWidgets()
        init()
    }

    // Layout presets
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

        editor = MultiLineEditBox.builder()
            .setX(editorX)
            .setY(editorY)
            .setShowBackground(true)
            .setShowDecorations(false)
            .setTextColor(0x00000000) // Fully transparent — syntax highlighter draws colored text on top
            .setTextShadow(false)
            .setCursorColor(0x00000000) // Hidden — we draw our own cursor on top of syntax highlighting
            .build(font, editorW, editorH, Component.literal("Script"))

        editor.setValue(rebuildWithText ?: scripts[activeTab] ?: "")
        rebuildWithText = null
        editor.setCharacterLimit(32767)

        lastSavedText = editor.value
        editor.setValueListener { newText ->
            // Push undo state when text changes (but not during undo/redo itself)
            if (!undoInProgress && newText != lastSavedText) {
                val cursorPos = getTextField()?.cursor() ?: 0
                undoStack.addLast(UndoState(lastSavedText, cursorPos))
                if (undoStack.size > 50) undoStack.removeFirst()
                redoStack.clear()
                lastSavedText = newText
            }
            // Update autocomplete whenever text changes (unless suppressed during programmatic insertion)
            if (!suppressAutocomplete) {
                autocomplete.update(editor, editorX, editorY)
            }
        }
        addRenderableWidget(editor)

        autocomplete = AutocompletePopup(font, cards, itemTags, variableNames) { scripts }

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
            .sprite(Identifier.fromNamespaceAndPath("nodeworks", currentLayout.spriteName), 16, 16)
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

        // Card list header
        val cardStartY = topPos + topBarHeight + 6
        graphics.drawString(font, "Cards:", leftPos + 6, cardStartY, 0xFFAAAAAA.toInt())

        // Card entries (scrollable)
        val cardListTop = cardStartY + 12
        val cardListBottom = topPos + imageHeight - 28
        val cardLineHeight = 11

        graphics.enableScissor(leftPos, cardListTop, leftPos + cardPanelWidth, cardListBottom)
        for ((i, card) in cards.withIndex()) {
            val y = cardListTop + i * cardLineHeight - cardScrollOffset
            if (y + cardLineHeight < cardListTop) continue
            if (y > cardListBottom) break
            val alias = card.effectiveAlias
            val color = when (card.capability.type) {
                "io" -> 0xFF83E086.toInt()
                "storage" -> 0xFFAA83E0.toInt()
                "energy" -> 0xFFFFD700.toInt()
                "fluid" -> 0xFF55AAFF.toInt()
                else -> 0xFFAAAAAA.toInt()
            }
            graphics.drawString(font, alias, leftPos + 6, y, color)
        }
        graphics.disableScissor()

        // Scroll indicator if there are more cards
        val maxVisibleCards = (cardListBottom - cardListTop) / cardLineHeight
        if (cards.size > maxVisibleCards) {
            val scrollbarHeight = cardListBottom - cardListTop
            val thumbHeight = maxOf(8, scrollbarHeight * maxVisibleCards / cards.size)
            val maxCardScroll = maxOf(1, (cards.size - maxVisibleCards) * cardLineHeight)
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
        // Separator line
        graphics.fill(logX, logY, logX + logW, logY + 1, 0xFF555555.toInt())
        // Toggle label with arrow
        val arrow = if (logCollapsed) "\u25B6" else "\u25BC"
        graphics.drawString(font, "$arrow Output", logX + 3, logY + 2, 0xFF888888.toInt())

        if (!logCollapsed) {
            // Log content area
            val logContentTop = logY + logCollapsedHeight
            val logContentBottom = logY + logPanelHeight - editorPadding
            graphics.fill(logX, logContentTop, logX + logW, logContentBottom, 0xFF1E1E1E.toInt())

            // Log entries with scrolling
            val logs = TerminalLogBuffer.getLogs(menu.getTerminalPos())
            val logLineHeight = font.lineHeight + 1
            val logTextAreaHeight = logContentBottom - logContentTop
            val maxVisibleLines = logTextAreaHeight / logLineHeight

            if (logAutoScroll && logs.isNotEmpty()) {
                logScrollOffset = maxOf(0, logs.size - maxVisibleLines)
            }

            graphics.enableScissor(logX, logContentTop, logX + logW, logContentBottom)
            for (i in 0 until maxVisibleLines) {
                val logIdx = logScrollOffset + i
                if (logIdx >= logs.size) break
                val entry = logs[logIdx]
                val entryY = logContentTop + i * logLineHeight
                val color = if (entry.isError) 0xFFFF5555.toInt() else 0xFF999999.toInt()
                graphics.drawString(font, "> " + entry.message, logX + 3, entryY, color)
            }
            graphics.disableScissor()
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Line number gutter
        renderLineNumbers(graphics)

        // Syntax highlighting draws colored text over the editor's transparent text
        LuaSyntaxHighlighter.render(graphics, font, editor, getTextField(), editorX, editorY)

        // Redraw cursor on top of highlighted text
        if (editor.isFocused) {
            renderCursor(graphics)
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
            if (newTabName.isNotEmpty() || (net.minecraft.util.Util.getMillis() / 500) % 2 == 0L) {
                val cursorX = inputX + 4 + font.width(newTabName)
                graphics.fill(cursorX, inputY + 4, cursorX + 1, inputY + inputH - 4, 0xFFFFFFFF.toInt())
            }
        }

        renderTooltip(graphics, mouseX, mouseY)
    }

    private fun renderLineNumbers(graphics: GuiGraphics) {
        val textField = getTextField() ?: return
        val text = textField.value()
        val scrollPixels = editor.scrollAmount().toInt()
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

        // Inner top of the editor (accounts for padding)
        val innerTop = try {
            val m = editor.javaClass.superclass?.getDeclaredMethod("getInnerTop")
            m?.isAccessible = true
            m?.invoke(editor) as? Int ?: (editor.y + 4)
        } catch (_: Exception) { editor.y + 4 }

        graphics.enableScissor(gutterX, gutterTop, editorX - 1, gutterBottom)
        for (line in 1..totalLines) {
            val y = innerTop + (line - 1) * lineHeight - scrollPixels
            if (y + lineHeight < gutterTop) continue
            if (y > gutterBottom) break
            val numStr = line.toString()
            val numWidth = font.width(numStr)
            graphics.drawString(font, numStr, editorX - 4 - numWidth, y, 0xFF555555.toInt(), false)
        }
        graphics.disableScissor()
    }

    private fun renderCursor(graphics: GuiGraphics) {
        val textField = getTextField() ?: return
        val text = textField.value()
        val cursor = textField.cursor()

        // Blink: visible for 300ms, hidden for 300ms
        val elapsed = net.minecraft.util.Util.getMillis() - cursorBlinkStart
        if ((elapsed / 300) % 2 != 0L) return

        val scrollOffset = editor.scrollAmount().toInt()
        val innerLeft = try {
            val m = editor.javaClass.superclass?.getDeclaredMethod("getInnerLeft")
            m?.isAccessible = true
            m?.invoke(editor) as? Int ?: (editor.x + 4)
        } catch (_: Exception) { editor.x + 4 }

        val innerTop = try {
            val m = editor.javaClass.superclass?.getDeclaredMethod("getInnerTop")
            m?.isAccessible = true
            m?.invoke(editor) as? Int ?: (editor.y + 4)
        } catch (_: Exception) { editor.y + 4 }

        // Find which line the cursor is on and the x offset
        var charsSoFar = 0
        var cursorLine = 0
        var cursorCol = 0
        for (view in textField.iterateLines()) {
            val begin = view.javaClass.getMethod("beginIndex").invoke(view) as Int
            val end = view.javaClass.getMethod("endIndex").invoke(view) as Int
            if (cursor >= begin && cursor <= end) {
                cursorCol = cursor - begin
                break
            }
            cursorLine++
        }

        val lineText = if (cursorLine < textField.getLineCount()) {
            val view = textField.getLineView(cursorLine)
            val begin = view.javaClass.getMethod("beginIndex").invoke(view) as Int
            val end = view.javaClass.getMethod("endIndex").invoke(view) as Int
            text.substring(begin, minOf(begin + cursorCol, end))
        } else ""

        val cursorX = innerLeft + font.width(lineText)
        val cursorY = innerTop + cursorLine * font.lineHeight - scrollOffset

        // Draw cursor line
        graphics.enableScissor(editor.x, editor.y, editor.x + editor.width, editor.y + editor.height)
        graphics.fill(cursorX, cursorY - 1, cursorX + 1, cursorY + font.lineHeight + 1, 0xFFD4D4D4.toInt())
        graphics.disableScissor()
    }

    private var cursorBlinkStart = net.minecraft.util.Util.getMillis()

    private var textFieldAccessor: java.lang.reflect.Field? = null
    private fun getTextField(): MultilineTextField? {
        if (textFieldAccessor == null) {
            textFieldAccessor = MultiLineEditBox::class.java.getDeclaredField("textField")
            textFieldAccessor!!.isAccessible = true
        }
        return textFieldAccessor?.get(editor) as? MultilineTextField
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        cursorBlinkStart = net.minecraft.util.Util.getMillis()

        // Handle new tab name input
        if (showNewTabInput) {
            when (keyEvent.key()) {
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
            if (keyEvent.key() == InputConstants.KEY_ESCAPE) {
                if (autocomplete.visible) {
                    autocomplete.hide()
                    return true
                }
                return super.keyPressed(keyEvent)
            }

            // Ctrl+Z = undo
            if (keyEvent.key() == InputConstants.KEY_Z && (keyEvent.modifiers() and 2) != 0 && (keyEvent.modifiers() and 1) == 0) {
                if (undoStack.isNotEmpty()) {
                    undoInProgress = true
                    val cursorPos = getTextField()?.cursor() ?: 0
                    val scrollPos = editor.scrollAmount()
                    redoStack.addLast(UndoState(editor.value, cursorPos))
                    val prev = undoStack.removeLast()
                    applyUndoState(prev, scrollPos)
                    undoInProgress = false
                }
                return true
            }

            // Ctrl+Shift+Z or Ctrl+Y = redo
            if ((keyEvent.key() == InputConstants.KEY_Z && (keyEvent.modifiers() and 3) == 3) ||
                (keyEvent.key() == InputConstants.KEY_Y && (keyEvent.modifiers() and 2) != 0)) {
                if (redoStack.isNotEmpty()) {
                    undoInProgress = true
                    val cursorPos = getTextField()?.cursor() ?: 0
                    val scrollPos = editor.scrollAmount()
                    undoStack.addLast(UndoState(editor.value, cursorPos))
                    val next = redoStack.removeLast()
                    applyUndoState(next, scrollPos)
                    undoInProgress = false
                }
                return true
            }

            // Ctrl+Space triggers autocomplete
            if (keyEvent.key() == InputConstants.KEY_SPACE && (keyEvent.modifiers() and 2) != 0) {
                autocomplete.update(editor, editorX, editorY, forced = true)
                return true
            }

            // Autocomplete navigation
            if (autocomplete.visible) {
                when (keyEvent.key()) {
                    InputConstants.KEY_UP -> { autocomplete.moveUp(); return true }
                    InputConstants.KEY_DOWN -> { autocomplete.moveDown(); return true }
                    InputConstants.KEY_RETURN, InputConstants.KEY_TAB -> {
                        val result = autocomplete.accept()
                        if (result != null) {
                            val tf = getTextField()
                            if (tf != null) {
                                // Suppress autocomplete updates during insertion
                                suppressAutocomplete = true
                                // Delete the typed prefix
                                for (i in 0 until result.deleteCount) {
                                    tf.deleteText(-1)
                                }
                                // Insert the full suggestion
                                for (ch in result.insertText) {
                                    editor.charTyped(net.minecraft.client.input.CharacterEvent(ch.code, 0))
                                }
                                suppressAutocomplete = false
                                autocomplete.hide()
                            }
                            return true
                        }
                    }
                }
            }

            // Tab inserts 4 spaces (when autocomplete not visible)
            if (keyEvent.key() == InputConstants.KEY_TAB && !autocomplete.visible) {
                for (i in 0..3) {
                    editor.charTyped(net.minecraft.client.input.CharacterEvent(' '.code, 0))
                }
                return true
            }

            val handled = editor.keyPressed(keyEvent)
            // Update autocomplete only for keys that modify text, not navigation
            val isNavOrModifierKey = keyEvent.key() in setOf(
                InputConstants.KEY_UP, InputConstants.KEY_DOWN,
                InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT,
                InputConstants.KEY_HOME, InputConstants.KEY_END,
                InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN,
                InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT,
                InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL,
                InputConstants.KEY_LALT, InputConstants.KEY_RALT
            )
            if (!isNavOrModifierKey) {
                autocomplete.update(editor, editorX, editorY)
            }
            return handled
        }
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(charEvent: net.minecraft.client.input.CharacterEvent): Boolean {
        if (showNewTabInput) {
            val c = charEvent.codepoint().toChar()
            if (c.isLetterOrDigit() || c == '_') {
                val candidate = newTabName + c.lowercaseChar()
                if (candidate.length <= 20 && TerminalBlockEntity.SCRIPT_NAME_REGEX.matches(candidate)) {
                    newTabName = candidate
                }
            }
            return true
        }
        if (editor.isFocused) {
            // Block the space from Ctrl+Space — it was already handled in keyPressed
            if (charEvent.codepoint() == ' '.code && (charEvent.modifiers() and 2) != 0) {
                return true
            }
            val handled = editor.charTyped(charEvent)
            autocomplete.update(editor, editorX, editorY)
            return handled
        }
        return super.charTyped(charEvent)
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, flag: Boolean): Boolean {
        // event.x()/y() are already in scaled GUI coordinates
        val mx = event.x().toInt()
        val my = event.y().toInt()


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
        if (mx >= logX && mx <= logX + logW && my >= logY && my <= logY + logCollapsedHeight) {
            logCollapsed = !logCollapsed
            rebuildWithText = editor.value
            rebind()
            return true
        }

        // Also check if click is in the line number gutter area (don't let editor capture it)
        val gutterX = editorX - lineNumberWidth
        if (mx >= gutterX && mx < editorX && my >= editorY && my < editorY + editor.height) {
            return true
        }

        return super.mouseClicked(event, flag)
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
            val maxVisibleLines = (logPanelHeight - 14) / logLineHeight
            val maxScroll = maxOf(0, logs.size - maxVisibleLines)

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
