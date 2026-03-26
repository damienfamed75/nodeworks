package damien.nodeworks.screen.widget

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.components.MultilineTextField

/**
 * Renders syntax-highlighted Lua text over a MultiLineEditBox.
 * The editor's text color is set to transparent; this class draws colored text at the same positions.
 */
object LuaSyntaxHighlighter {

    private const val KEYWORD_COLOR = 0xFF569CD6.toInt()
    private const val STRING_COLOR = 0xFFCE9178.toInt()
    private const val COMMENT_COLOR = 0xFF6A9955.toInt()
    private const val NUMBER_COLOR = 0xFFB5CEA8.toInt()
    private const val FUNCTION_COLOR = 0xFFDCDCAA.toInt()
    private const val DEFAULT_COLOR = 0xFFD4D4D4.toInt()

    private val KEYWORDS = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "if", "in", "local", "nil", "not", "or", "repeat",
        "return", "then", "true", "until", "while"
    )

    private val BUILTINS = setOf("card", "scheduler", "print")

    private var innerLeftMethod: java.lang.reflect.Method? = null
    private var innerTopMethod: java.lang.reflect.Method? = null

    private fun getInnerLeft(editor: MultiLineEditBox): Int {
        if (innerLeftMethod == null) {
            innerLeftMethod = editor.javaClass.superclass?.getDeclaredMethod("getInnerLeft")
            innerLeftMethod?.isAccessible = true
        }
        return innerLeftMethod?.invoke(editor) as? Int ?: (editor.x + 4)
    }

    private fun getInnerTop(editor: MultiLineEditBox): Int {
        if (innerTopMethod == null) {
            innerTopMethod = editor.javaClass.superclass?.getDeclaredMethod("getInnerTop")
            innerTopMethod?.isAccessible = true
        }
        return innerTopMethod?.invoke(editor) as? Int ?: (editor.y + 4)
    }

    fun render(
        graphics: GuiGraphics,
        font: Font,
        editor: MultiLineEditBox,
        textField: MultilineTextField?,
        editorX: Int,
        editorY: Int
    ) {
        if (textField == null) return

        val text = textField.value()
        if (text.isEmpty()) return

        val lineHeight = font.lineHeight
        val scrollOffset = editor.scrollAmount().toInt()
        val textLeft = getInnerLeft(editor)
        val textTop = getInnerTop(editor)

        // Get line ranges
        val lines = mutableListOf<Pair<Int, Int>>()
        for (view in textField.iterateLines()) {
            val begin = view.javaClass.getMethod("beginIndex").invoke(view) as Int
            val end = view.javaClass.getMethod("endIndex").invoke(view) as Int
            lines.add(begin to end)
        }

        // Clip region matches the editor's visible area
        val clipTop = editor.y + 1
        val clipBottom = editor.y + editor.height - 1

        // Enable scissor to clip to editor bounds
        graphics.enableScissor(editor.x, clipTop, editor.x + editor.width, clipBottom)

        var inBlockComment = false

        for ((lineIdx, range) in lines.withIndex()) {
            val (begin, end) = range
            val lineText = text.substring(begin, end)
            val y = textTop + lineIdx * lineHeight - scrollOffset

            // Track block comment state even for off-screen lines
            val tokens = tokenize(lineText, inBlockComment)
            for (token in tokens) {
                if (token.type == TokenType.BLOCK_COMMENT_START) inBlockComment = true
                if (token.type == TokenType.BLOCK_COMMENT_END) inBlockComment = false
            }

            // Skip lines outside visible area (after tracking state)
            if (y + font.lineHeight < clipTop || y > clipBottom) continue

            // Draw colored text
            var x = textLeft
            for (token in tokens) {
                graphics.drawString(font, token.text, x, y, token.color, false)
                x += font.width(token.text)
            }
        }

        graphics.disableScissor()
    }

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
