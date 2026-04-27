package damien.nodeworks.script

/**
 * Decides whether the editor's Enter handler should drop a matching `end` after
 * a block-opening line. Lives outside [damien.nodeworks.screen.widget.ScriptEditor]
 * so unit tests can pin the decision down without dragging in Minecraft's `Font`
 * / `Component` (which the editor widget needs to even class-load).
 */
object LuaBlockBalance {

    /** True when the cursor is at the end of a line that opens an end-closed
     *  block AND there's no matching closer in the text after the cursor.
     *
     *  Walking forward from the cursor (rather than counting global balance)
     *  is what makes this safe inside otherwise-balanced code that lives
     *  inside an unclosed surrounding block. The outer block's missing
     *  closer doesn't cause us to spuriously add a closer for the inner
     *  opener. */
    fun shouldInsertAutoEnd(
        curLineText: String,
        col: Int,
        fullText: String,
        cursorPos: Int,
    ): Boolean {
        if (col < curLineText.length) return false
        val prefix = curLineText.substring(0, col).trimEnd()
        if (!lineOpensEndBlock(prefix)) return false
        return !isOpenerClosedAfter(fullText, cursorPos)
    }

    /** True when [trimmedPrefix] ends with a keyword that opens a block needing
     *  an `end` to close it. `else` / `elseif` start a new branch but share
     *  the surrounding `if`'s `end`, so they don't qualify. `repeat` is closed
     *  by `until`, not `end`, so it's also excluded. */
    private fun lineOpensEndBlock(trimmedPrefix: String): Boolean {
        if (trimmedPrefix.isEmpty()) return false
        val commentIdx = trimmedPrefix.indexOf("--")
        val code = if (commentIdx >= 0) trimmedPrefix.substring(0, commentIdx).trimEnd() else trimmedPrefix
        if (code.isEmpty()) return false
        if (Regex("""(^|\W)(then|do)\s*$""").containsMatchIn(code)) return true
        if (Regex("""\bfunction\s*[\w_.:]*\s*\([^)]*\)\s*$""").containsMatchIn(code)) return true
        return false
    }

    /** Walk forward from [cursorPos] in [fullText], starting at depth 1 for the
     *  block the cursor's line just opened. Returns true when we encounter a
     *  matching closer (`end` / `until`) before exhausting the text, meaning
     *  this opener already has its `end` later in the script and the editor
     *  shouldn't auto-insert another one.
     *
     *  Walks the [LuaTokenizer] rather than raw text so keywords inside
     *  strings or comments don't skew the count. `do` is intentionally NOT
     *  counted as an opener here: `for ... do` and `while ... do` already
     *  incremented for `for`/`while`, and standalone `do ... end` blocks are
     *  rare enough that under-counting them is preferable to double-counting
     *  the common case. */
    private fun isOpenerClosedAfter(fullText: String, cursorPos: Int): Boolean {
        if (cursorPos >= fullText.length) return false
        val after = fullText.substring(cursorPos)
        var depth = 1
        for (line in LuaTokenizer.tokenizeLines(after)) {
            for (t in line) {
                if (t.type != LuaTokenizer.TokenType.KEYWORD) continue
                when (t.text) {
                    "if", "for", "while", "repeat", "function" -> depth++
                    "end", "until" -> {
                        depth--
                        if (depth == 0) return true
                    }
                }
            }
        }
        return false
    }
}
