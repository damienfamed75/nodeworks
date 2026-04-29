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
     *  opener.
     *
     *  Indentation guards against the LIFO trap: when the user types a new
     *  opener inside an existing nested block (e.g. an `if` inside a `for`
     *  whose `end` is below), Lua's grammar would silently re-pair that
     *  outer `end` with the new opener, leaving the outer block hanging.
     *  We treat a closer that lives at a SHALLOWER indent than the new
     *  opener as belonging to the outer block, not this one, so the editor
     *  still drops in a fresh `end` matching the new opener's depth. */
    fun shouldInsertAutoEnd(
        curLineText: String,
        col: Int,
        fullText: String,
        cursorPos: Int,
    ): Boolean {
        if (col < curLineText.length) return false
        val prefix = curLineText.substring(0, col).trimEnd()
        if (!lineOpensEndBlock(prefix)) return false
        val openerIndent = curLineText.takeWhile { it == ' ' }.length
        return !isOpenerClosedAfter(fullText, cursorPos, openerIndent)
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
     *  A depth-0 closer that sits at a strictly shallower indent than
     *  [openerIndent] is treated as NOT closing this opener: the user typed
     *  it for some outer block whose closer would otherwise get cannibalised
     *  by the new inner opener under Lua's LIFO matching.
     *
     *  Walks the [LuaTokenizer] rather than raw text so keywords inside
     *  strings or comments don't skew the count. `do` is intentionally NOT
     *  counted as an opener here: `for ... do` and `while ... do` already
     *  incremented for `for`/`while`, and standalone `do ... end` blocks are
     *  rare enough that under-counting them is preferable to double-counting
     *  the common case. */
    private fun isOpenerClosedAfter(fullText: String, cursorPos: Int, openerIndent: Int): Boolean {
        if (cursorPos >= fullText.length) return false
        val after = fullText.substring(cursorPos)
        val sourceLines = after.split('\n')
        val tokenLines = LuaTokenizer.tokenizeLines(sourceLines)
        var depth = 1
        for ((lineIdx, lineTokens) in tokenLines.withIndex()) {
            for (t in lineTokens) {
                if (t.type != LuaTokenizer.TokenType.KEYWORD) continue
                when (t.text) {
                    "if", "for", "while", "repeat", "function" -> depth++
                    "end", "until" -> {
                        depth--
                        if (depth == 0) {
                            val closerIndent = sourceLines[lineIdx].takeWhile { it == ' ' }.length
                            return closerIndent >= openerIndent
                        }
                    }
                }
            }
        }
        return false
    }
}
