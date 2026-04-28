package damien.nodeworks.screen.widget

import damien.nodeworks.compat.drawString
import damien.nodeworks.script.LuaTokenizer
import damien.nodeworks.script.LuaTokenizer.TokenType
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.components.MultilineTextField

/**
 * Renders syntax-highlighted Lua text over a MultiLineEditBox. The editor's text colour
 * is set to transparent, this class draws coloured text at the same positions using the
 * shared [LuaTokenizer].
 *
 * The primary editor widget is now [ScriptEditor], which draws its own coloured text
 * directly without needing this overlay. This object remains as a lighter alternative
 * for screens that use the vanilla MultiLineEditBox and just want colouring layered on
 * top.
 */
object LuaSyntaxHighlighter {

    private const val SELECTION_COLOR = 0xFF0000FF.toInt()

    private fun getInnerLeft(editor: MultiLineEditBox): Int {
        return try {
            val m = editor.javaClass.superclass?.getDeclaredMethod("getInnerLeft")
            m?.isAccessible = true
            m?.invoke(editor) as? Int ?: (editor.x + 4)
        } catch (_: Exception) { editor.x + 4 }
    }

    private fun getInnerTop(editor: MultiLineEditBox): Int {
        return try {
            val m = editor.javaClass.superclass?.getDeclaredMethod("getInnerTop")
            m?.isAccessible = true
            m?.invoke(editor) as? Int ?: (editor.y + 4)
        } catch (_: Exception) { editor.y + 4 }
    }

    fun render(
        graphics: GuiGraphicsExtractor,
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
        val scrollOffset = 0.0.toInt()
        val textLeft = getInnerLeft(editor)
        val textTop = getInnerTop(editor)

        // Get selection range
        val hasSelection = textField.hasSelection()
        var selStart = 0
        var selEnd = 0
        if (hasSelection) {
            val selected = textField.getSelected()
            selStart = selected.javaClass.getMethod("beginIndex").invoke(selected) as Int
            selEnd = selected.javaClass.getMethod("endIndex").invoke(selected) as Int
            if (selStart > selEnd) {
                val tmp = selStart; selStart = selEnd; selEnd = tmp
            }
        }

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

            // Draw colored text, selected characters render in blue
            var x = textLeft
            var charIdx = begin
            for (token in tokens) {
                if (hasSelection && selStart < charIdx + token.text.length && selEnd > charIdx) {
                    // Token overlaps selection, draw char by char
                    for (ch in token.text) {
                        val color = if (hasSelection && charIdx >= selStart && charIdx < selEnd)
                            SELECTION_COLOR else token.color
                        graphics.drawString(font, ch.toString(), x, y, color, false)
                        x += font.width(ch.toString())
                        charIdx++
                    }
                } else {
                    graphics.drawString(font, token.text, x, y, token.color, false)
                    x += font.width(token.text)
                    charIdx += token.text.length
                }
            }
        }

        graphics.disableScissor()
    }

    private fun tokenize(line: String, inBlockComment: Boolean) =
        LuaTokenizer.tokenize(line, inBlockComment)
}
