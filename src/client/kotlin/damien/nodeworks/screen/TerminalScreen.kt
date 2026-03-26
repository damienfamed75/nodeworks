package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.screen.TerminalScreenHandler
import damien.nodeworks.screen.widget.AutocompletePopup
import damien.nodeworks.screen.widget.LuaSyntaxHighlighter
import net.minecraft.client.gui.components.MultilineTextField
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineEditBox
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
    private var scriptRunning: Boolean = menu.isRunning()
    private var autoRun: Boolean = menu.isAutoRun()

    // Layout constants
    private val cardPanelWidth = 80
    private val editorPadding = 4
    private val buttonHeight = 20
    private val topBarHeight = 24

    // Editor position (stored for autocomplete positioning)
    private var editorX = 0
    private var editorY = 0

    init {
        imageWidth = 320
        imageHeight = 220
    }

    override fun init() {
        super.init()

        editorX = leftPos + cardPanelWidth + editorPadding
        editorY = topPos + topBarHeight
        val editorW = imageWidth - cardPanelWidth - editorPadding * 2
        val editorH = imageHeight - topBarHeight - editorPadding

        editor = MultiLineEditBox.builder()
            .setX(editorX)
            .setY(editorY)
            .setShowBackground(true)
            .setShowDecorations(true)
            .setTextColor(0x00000000) // Fully transparent — syntax highlighter draws colored text on top
            .setTextShadow(false)
            .setCursorColor(0x00000000) // Hidden — we draw our own cursor on top of syntax highlighting
            .build(font, editorW, editorH, Component.literal("Script"))

        editor.setValue(menu.getScriptText())
        editor.setCharacterLimit(32767)
        editor.setValueListener { _ ->
            // Update autocomplete whenever text changes (typing, paste, delete)
            autocomplete.update(editor, editorX, editorY)
        }
        addRenderableWidget(editor)

        autocomplete = AutocompletePopup(font, cards)

        // Run button
        addRenderableWidget(Button.builder(Component.literal("Run")) { _ ->
            ClientPlayNetworking.send(TerminalPackets.RunScriptPayload(menu.getTerminalPos(), editor.value))
            scriptRunning = true
        }.bounds(leftPos + imageWidth - 90, topPos + 2, 40, buttonHeight).build())

        // Stop button
        addRenderableWidget(Button.builder(Component.literal("Stop")) { _ ->
            ClientPlayNetworking.send(TerminalPackets.StopScriptPayload(menu.getTerminalPos()))
            scriptRunning = false
        }.bounds(leftPos + imageWidth - 46, topPos + 2, 40, buttonHeight).build())

        // Auto-run toggle
        addRenderableWidget(Button.builder(autoRunLabel()) { btn ->
            autoRun = !autoRun
            ClientPlayNetworking.send(TerminalPackets.ToggleAutoRunPayload(menu.getTerminalPos(), autoRun))
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

        // Status indicator
        val statusX = leftPos + imageWidth - 150
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

        // Card entries
        for ((i, card) in cards.withIndex()) {
            val y = cardStartY + 12 + i * 11
            if (y + 11 > topPos + imageHeight - 28) break
            val alias = card.alias ?: "${card.capability.type}#${i + 1}"
            val color = when (card.capability.type) {
                "inventory" -> 0xFF83E086.toInt()
                "energy" -> 0xFFFFD700.toInt()
                "fluid" -> 0xFF55AAFF.toInt()
                else -> 0xFFAAAAAA.toInt()
            }
            graphics.drawString(font, alias, leftPos + 6, y, color)
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

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
            // Update autocomplete after any key that modifies text
            autocomplete.update(editor, editorX, editorY)
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

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default inventory labels
    }

    override fun onClose() {
        ClientPlayNetworking.send(TerminalPackets.SaveScriptPayload(menu.getTerminalPos(), editor.value))
        super.onClose()
    }

    private fun autoRunLabel(): Component {
        val state = if (autoRun) "\u00A7aON" else "\u00A77OFF"
        return Component.literal("Auto: $state")
    }

    override fun isPauseScreen(): Boolean = false
}
