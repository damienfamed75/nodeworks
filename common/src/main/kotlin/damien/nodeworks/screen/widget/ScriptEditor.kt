package damien.nodeworks.screen.widget

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * Custom multi-line code editor widget with built-in syntax highlighting.
 * No reflection, no mixins — fully self-contained using only stable public APIs.
 */
class ScriptEditor(
    private val font: Font,
    x: Int, y: Int, width: Int, height: Int
) : AbstractWidget(x, y, width, height, Component.empty()) {

    companion object {
        // Syntax colors (One Dark theme)
        private const val KEYWORD_COLOR = 0xFFC678DD.toInt()
        private const val STRING_COLOR = 0xFF98C379.toInt()
        private const val COMMENT_COLOR = 0xFF5C6370.toInt()
        private const val NUMBER_COLOR = 0xFFD19A66.toInt()
        private const val FUNCTION_COLOR = 0xFF61AFEF.toInt()
        private const val DEFAULT_COLOR = 0xFFABB2BF.toInt()
        private const val SELECTION_BG = 0xFF264F78.toInt()
        private const val CURSOR_COLOR = 0xFFFFFFFF.toInt()
        private const val BG_COLOR = 0xFF0D0D0D.toInt()
        private const val CURSOR_BLINK_MS = 300L

        private val KEYWORDS = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "if", "in", "local", "nil", "not", "or", "repeat",
            "return", "then", "true", "until", "while"
        )
        private val BUILTINS = setOf("card", "scheduler", "print", "network", "clock", "require", "coroutine")
    }

    // Text state
    private val lines = mutableListOf("")
    var cursor = 0                      // absolute position in full text
    private var selectStart = -1       // -1 = no selection
    var scrollY = 0                    // pixels scrolled vertically
    var scrollX = 0                    // pixels scrolled horizontally
    private var cursorBlinkTime = System.currentTimeMillis()
    private var characterLimit = 32767

    // Callbacks
    private var valueListener: ((String) -> Unit)? = null

    // --- Public API (matches what TerminalScreen expects) ---

    var value: String
        get() {
            return lines.joinToString("\n")
        }
        set(text) {
            rebuildLines(text)
            cursor = cursor.coerceAtMost(text.length)
            selectStart = -1
            scrollY = 0
            scrollX = 0
        }

    /** Update lines without resetting cursor/selection/scroll — for internal edits. */
    private fun rebuildLines(text: String) {
        lines.clear()
        lines.addAll(if (text.isEmpty()) listOf("") else text.split("\n"))
    }

    /** Set text and cursor without resetting scroll — for autocomplete insertion. */
    fun setValueKeepScroll(text: String, newCursor: Int) {
        rebuildLines(text)
        cursor = newCursor.coerceIn(0, text.length)
        selectStart = -1
        ensureCursorVisible()
    }


    fun setCharacterLimit(limit: Int) { characterLimit = limit }
    fun setValueListener(listener: (String) -> Unit) { valueListener = listener }

    fun getCursorPosition(): Int = cursor

    val hasSelection: Boolean get() = selectStart >= 0 && selectStart != cursor
    val selectionStart: Int get() = if (hasSelection) minOf(selectStart, cursor) else cursor
    val selectionEnd: Int get() = if (hasSelection) maxOf(selectStart, cursor) else cursor

    // --- Coordinate helpers ---

    private val lineHeight get() = font.lineHeight
    private val padding = 4
    private val textLeft get() = x + padding
    private val textTop get() = y + padding
    private val visibleLines get() = (height - padding * 2) / lineHeight

    private fun totalTextLength(): Int = lines.sumOf { it.length } + (lines.size - 1) // +newlines

    /** Convert absolute cursor position to (line, column). */
    fun cursorToLineCol(pos: Int): Pair<Int, Int> {
        var remaining = pos
        for ((i, line) in lines.withIndex()) {
            if (remaining <= line.length) return i to remaining
            remaining -= line.length + 1 // +1 for newline
        }
        return (lines.size - 1) to lines.last().length
    }

    /** Convert (line, column) to absolute cursor position. */
    private fun lineColToCursor(line: Int, col: Int): Int {
        var pos = 0
        for (i in 0 until line.coerceAtMost(lines.size - 1)) {
            pos += lines[i].length + 1
        }
        return pos + col.coerceAtMost(lines[line.coerceAtMost(lines.size - 1)].length)
    }

    /** Convert screen coordinates to cursor position. */
    private fun screenToCursor(mx: Double, my: Double): Int {
        val relY = my - textTop + scrollY
        val lineIdx = (relY / lineHeight).toInt().coerceIn(0, lines.size - 1)
        val line = lines[lineIdx]
        val relX = mx - textLeft + scrollX

        // Find column by measuring character widths
        var col = 0
        var w = 0f
        for (ch in line) {
            val charW = font.width(ch.toString()).toFloat()
            if (w + charW / 2 > relX) break
            w += charW
            col++
        }
        return lineColToCursor(lineIdx, col)
    }

    // --- Rendering ---

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Background
        graphics.fill(x, y, x + width, y + height, BG_COLOR)

        // Border
        val borderColor = if (isFocused) 0xFF555555.toInt() else 0xFF333333.toInt()
        graphics.fill(x, y, x + width, y + 1, borderColor)
        graphics.fill(x, y + height - 1, x + width, y + height, borderColor)
        graphics.fill(x, y, x + 1, y + height, borderColor)
        graphics.fill(x + width - 1, y, x + width, y + height, borderColor)

        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1)

        val (curLine, curCol) = cursorToLineCol(cursor)
        var inBlockComment = false

        for (lineIdx in 0 until lines.size) {
            val lineY = textTop + lineIdx * lineHeight - scrollY
            if (lineY + lineHeight < y) {
                // Track block comment state for off-screen lines
                val tokens = tokenize(lines[lineIdx], inBlockComment)
                for (t in tokens) {
                    if (t.type == TokenType.BLOCK_COMMENT_START) inBlockComment = true
                    if (t.type == TokenType.BLOCK_COMMENT_END) inBlockComment = false
                }
                continue
            }
            if (lineY > y + height) break

            val line = lines[lineIdx]
            val lineStart = lineColToCursor(lineIdx, 0)

            // Selection highlight
            if (hasSelection) {
                val selS = selectionStart
                val selE = selectionEnd
                val lineEnd = lineStart + line.length
                if (selS < lineEnd && selE > lineStart) {
                    val hlStart = maxOf(selS - lineStart, 0)
                    val hlEnd = minOf(selE - lineStart, line.length)
                    val x0 = textLeft + font.width(line.substring(0, hlStart)) - scrollX
                    val x1 = textLeft + font.width(line.substring(0, hlEnd)) - scrollX
                    graphics.fill(x0, lineY, x1, lineY + lineHeight, SELECTION_BG)
                }
            }

            // Syntax-highlighted text
            val tokens = tokenize(line, inBlockComment)
            var tx = textLeft - scrollX
            for (token in tokens) {
                if (token.type == TokenType.BLOCK_COMMENT_START) inBlockComment = true
                if (token.type == TokenType.BLOCK_COMMENT_END) inBlockComment = false
                graphics.drawString(font, token.text, tx, lineY, token.color, false)
                tx += font.width(token.text)
            }
        }

        // Cursor
        if (isFocused) {
            val elapsed = System.currentTimeMillis() - cursorBlinkTime
            if ((elapsed / CURSOR_BLINK_MS) % 2 == 0L) {
                val cursorY = textTop + curLine * lineHeight - scrollY
                val cursorX = textLeft + font.width(lines[curLine].substring(0, curCol)) - scrollX
                graphics.fill(cursorX, cursorY, cursorX + 1, cursorY + lineHeight, CURSOR_COLOR)
            }
        }

        graphics.disableScissor()
    }

    // --- Input handling ---

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!isFocused) return false
        cursorBlinkTime = System.currentTimeMillis()

        val ctrl = (modifiers and 2) != 0
        val shift = (modifiers and 1) != 0
        val (line, col) = cursorToLineCol(cursor)

        when (keyCode) {
            // Arrow keys
            263 -> { // LEFT
                if (shift) startSelection()
                if (ctrl) moveCursorWordLeft() else moveCursorLeft()
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            262 -> { // RIGHT
                if (shift) startSelection()
                if (ctrl) moveCursorWordRight() else moveCursorRight()
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            265 -> { // UP
                if (shift) startSelection()
                if (line > 0) cursor = lineColToCursor(line - 1, col)
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            264 -> { // DOWN
                if (shift) startSelection()
                if (line < lines.size - 1) cursor = lineColToCursor(line + 1, col)
                else cursor = totalTextLength() // at last line, go to end
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            268 -> { // HOME
                if (shift) startSelection()
                cursor = lineColToCursor(line, 0)
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            269 -> { // END
                if (shift) startSelection()
                cursor = lineColToCursor(line, lines[line].length)
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }

            // Editing keys
            259 -> { // BACKSPACE
                if (hasSelection) { deleteSelection(); return true }
                if (cursor > 0) {
                    deleteAt(cursor - 1)
                    cursor--
                    onTextChanged()
                }
                ensureCursorVisible()
                return true
            }
            261 -> { // DELETE
                if (hasSelection) { deleteSelection(); return true }
                if (cursor < totalTextLength()) {
                    deleteAt(cursor)
                    onTextChanged()
                }
                return true
            }
            257, 335 -> { // ENTER / NUMPAD ENTER
                if (hasSelection) deleteSelection()
                insertText("\n")
                return true
            }
            258 -> { // TAB
                if (hasSelection) deleteSelection()
                insertText("  ")
                return true
            }

            // Ctrl shortcuts
            65 -> if (ctrl) { // Ctrl+A — select all
                selectStart = 0
                cursor = totalTextLength()
                return true
            }
            67 -> if (ctrl) { // Ctrl+C — copy
                copySelection()
                return true
            }
            88 -> if (ctrl) { // Ctrl+X — cut
                copySelection()
                deleteSelection()
                return true
            }
            86 -> if (ctrl) { // Ctrl+V — paste
                val clipboard = Minecraft.getInstance().keyboardHandler.clipboard ?: ""
                if (clipboard.isNotEmpty()) {
                    if (hasSelection) deleteSelection()
                    insertText(clipboard)
                }
                return true
            }
        }
        return false
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (!isFocused) return false
        if (codePoint < ' ' && codePoint != '\t') return false
        cursorBlinkTime = System.currentTimeMillis()

        if (hasSelection) deleteSelection()
        insertText(codePoint.toString())
        return true
    }

    override fun onClick(mouseX: Double, mouseY: Double) {
        val clickPos = screenToCursor(mouseX, mouseY)
        cursor = clickPos
        selectStart = clickPos  // set anchor for potential drag
        cursorBlinkTime = System.currentTimeMillis()
        ensureCursorVisible()
    }

    override fun onDrag(mouseX: Double, mouseY: Double, dragX: Double, dragY: Double) {
        cursor = screenToCursor(mouseX, mouseY)
        ensureCursorVisible()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        this.scrollY = (this.scrollY - (scrollY * lineHeight * 3).toInt())
            .coerceIn(0, maxOf(0, lines.size * lineHeight - (height - padding * 2)))
        return true
    }

    // --- Text manipulation ---

    private fun insertText(text: String) {
        val fullText = value
        if (fullText.length + text.length > characterLimit) return
        val newText = StringBuilder(fullText).insert(cursor, text).toString()
        rebuildLines(newText)
        cursor += text.length
        onTextChanged()
        ensureCursorVisible()
    }

    private fun deleteAt(pos: Int) {
        val fullText = value
        if (pos < 0 || pos >= fullText.length) return
        val newText = StringBuilder(fullText).deleteCharAt(pos).toString()
        rebuildLines(newText)
    }

    private fun deleteSelection() {
        if (!hasSelection) return
        val s = selectionStart
        val e = selectionEnd
        val fullText = value
        val newText = fullText.removeRange(s, e)
        cursor = s
        selectStart = -1
        rebuildLines(newText)
        onTextChanged()
        ensureCursorVisible()
    }

    private fun copySelection() {
        if (!hasSelection) return
        val text = value.substring(selectionStart, selectionEnd)
        Minecraft.getInstance().keyboardHandler.clipboard = text
    }

    private fun startSelection() {
        if (selectStart < 0) selectStart = cursor
    }

    private fun clearSelection() { selectStart = -1 }

    private fun moveCursorLeft() {
        if (hasSelection && selectStart >= 0) { cursor = selectionStart; return }
        if (cursor > 0) cursor--
    }

    private fun moveCursorRight() {
        if (hasSelection && selectStart >= 0) { cursor = selectionEnd; return }
        if (cursor < totalTextLength()) cursor++
    }

    private fun moveCursorWordLeft() {
        val text = value
        var pos = cursor - 1
        while (pos > 0 && !text[pos - 1].isLetterOrDigit()) pos--
        while (pos > 0 && text[pos - 1].isLetterOrDigit()) pos--
        cursor = maxOf(0, pos)
    }

    private fun moveCursorWordRight() {
        val text = value
        var pos = cursor
        while (pos < text.length && !text[pos].isLetterOrDigit()) pos++
        while (pos < text.length && text[pos].isLetterOrDigit()) pos++
        cursor = pos
    }

    private fun ensureCursorVisible() {
        val (line, col) = cursorToLineCol(cursor)

        // Vertical
        val cursorY = line * lineHeight
        val viewTop = scrollY
        val viewBottom = scrollY + height - padding * 2 - lineHeight
        if (cursorY < viewTop) scrollY = cursorY
        if (cursorY > viewBottom) scrollY = cursorY - (height - padding * 2 - lineHeight)
        scrollY = scrollY.coerceAtLeast(0)

        // Horizontal
        val cursorPixelX = font.width(lines[line].substring(0, col))
        val viewWidth = width - padding * 2
        val viewLeft = scrollX
        val viewRight = scrollX + viewWidth - 2 // small margin for cursor
        if (cursorPixelX < viewLeft) scrollX = maxOf(0, cursorPixelX - 8)
        if (cursorPixelX > viewRight) scrollX = cursorPixelX - viewWidth + 8
        scrollX = scrollX.coerceAtLeast(0)
    }

    private fun onTextChanged() {
        valueListener?.invoke(value)
    }

    // --- Narration (required by AbstractWidget) ---

    override fun updateWidgetNarration(output: NarrationElementOutput) {}

    // --- Syntax tokenizer (integrated from LuaSyntaxHighlighter) ---

    private enum class TokenType {
        KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, DEFAULT,
        BLOCK_COMMENT_START, BLOCK_COMMENT_END
    }

    private data class Token(val text: String, val color: Int, val type: TokenType = TokenType.DEFAULT)

    private fun tokenize(line: String, inBlockComment: Boolean): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var currentlyInBlock = inBlockComment

        if (currentlyInBlock) {
            val endIdx = line.indexOf("]]", i)
            if (endIdx >= 0) {
                tokens.add(Token(line.substring(i, endIdx + 2), COMMENT_COLOR, TokenType.BLOCK_COMMENT_END))
                i = endIdx + 2
            } else {
                tokens.add(Token(line, COMMENT_COLOR))
                return tokens
            }
        }

        while (i < line.length) {
            val ch = line[i]

            if (ch == '-' && i + 1 < line.length && line[i + 1] == '-') {
                if (i + 3 < line.length && line[i + 2] == '[' && line[i + 3] == '[') {
                    val endIdx = line.indexOf("]]", i + 4)
                    if (endIdx >= 0) {
                        tokens.add(Token(line.substring(i, endIdx + 2), COMMENT_COLOR))
                        i = endIdx + 2
                    } else {
                        tokens.add(Token(line.substring(i), COMMENT_COLOR, TokenType.BLOCK_COMMENT_START))
                        return tokens
                    }
                } else {
                    tokens.add(Token(line.substring(i), COMMENT_COLOR))
                    return tokens
                }
                continue
            }

            if (ch == '"') {
                val end = findStringEnd(line, i + 1, '"')
                tokens.add(Token(line.substring(i, end), STRING_COLOR))
                i = end
                continue
            }

            if (ch == '\'') {
                val end = findStringEnd(line, i + 1, '\'')
                tokens.add(Token(line.substring(i, end), STRING_COLOR))
                i = end
                continue
            }

            if (ch.isDigit() || (ch == '.' && i + 1 < line.length && line[i + 1].isDigit())) {
                val start = i
                while (i < line.length && (line[i].isDigit() || line[i] == '.' || line[i] == 'x' || line[i] in 'a'..'f' || line[i] in 'A'..'F')) i++
                tokens.add(Token(line.substring(start, i), NUMBER_COLOR))
                continue
            }

            if (ch.isLetter() || ch == '_') {
                val start = i
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                val word = line.substring(start, i)
                val color = when {
                    word in KEYWORDS -> KEYWORD_COLOR
                    word in BUILTINS -> FUNCTION_COLOR
                    i < line.length && line[i] == '(' -> FUNCTION_COLOR
                    else -> DEFAULT_COLOR
                }
                tokens.add(Token(word, color))
                continue
            }

            tokens.add(Token(ch.toString(), DEFAULT_COLOR))
            i++
        }

        return tokens
    }

    private fun findStringEnd(line: String, start: Int, quote: Char): Int {
        var i = start
        while (i < line.length) {
            if (line[i] == '\\') { i += 2; continue }
            if (line[i] == quote) return i + 1
            i++
        }
        return line.length
    }
}
