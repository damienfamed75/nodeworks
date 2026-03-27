package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
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
    private var scriptRunning: Boolean = menu.isRunning()
    private var autoRun: Boolean = menu.isAutoRun()

    // Layout constants
    private val cardPanelWidth = 80
    private val editorPadding = 4
    private val buttonHeight = 20
    private val topBarHeight = 24
    private val logPanelHeight = 50

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
        editorY = topPos + topBarHeight
        val editorW = imageWidth - cardPanelWidth - editorPadding * 2 - lineNumberWidth
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val editorH = imageHeight - topBarHeight - effectiveLogHeight - editorPadding

        editor = MultiLineEditBox.builder()
            .setX(editorX)
            .setY(editorY)
            .setShowBackground(true)
            .setShowDecorations(false)
            .setTextColor(0x00000000) // Fully transparent — syntax highlighter draws colored text on top
            .setTextShadow(false)
            .setCursorColor(0x00000000) // Hidden — we draw our own cursor on top of syntax highlighting
            .build(font, editorW, editorH, Component.literal("Script"))

        editor.setValue(rebuildWithText ?: menu.getScriptText())
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
            // Update autocomplete whenever text changes
            autocomplete.update(editor, editorX, editorY)
        }
        addRenderableWidget(editor)

        autocomplete = AutocompletePopup(font, cards, itemTags)

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

        // Run button
        addRenderableWidget(Button.builder(Component.literal("Run")) { _ ->
            PlatformServices.clientNetworking.sendToServer(RunScriptPayload(menu.getTerminalPos(), editor.value))
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
                        val insert = autocomplete.accept()
                        if (insert != null) {
                            // Insert the completion text
                            for (ch in insert) {
                                editor.charTyped(net.minecraft.client.input.CharacterEvent(ch.code, 0))
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
        // Check if click is on the log toggle bar
        val effectiveLogHeight = if (logCollapsed) logCollapsedHeight else logPanelHeight
        val logX = leftPos + cardPanelWidth + editorPadding
        val logY = topPos + imageHeight - effectiveLogHeight
        val logW = imageWidth - cardPanelWidth - editorPadding * 2
        if (event.x() >= logX && event.x() <= logX + logW && event.y() >= logY && event.y() <= logY + logCollapsedHeight) {
            logCollapsed = !logCollapsed
            // Rebuild to resize the editor
            rebuildWithText = editor.value
            rebind()
            return true
        }
        return super.mouseClicked(event, flag)
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
        PlatformServices.clientNetworking.sendToServer(SaveScriptPayload(menu.getTerminalPos(), editor.value))
        super.onClose()
    }

    private fun autoRunLabel(): Component {
        val state = if (autoRun) "\u00A7aON" else "\u00A77OFF"
        return Component.literal("Auto: $state")
    }

    override fun isPauseScreen(): Boolean = false
}
