package damien.nodeworks.script

/**
 * Shared Lua tokenizer used by the in-game [damien.nodeworks.screen.widget.ScriptEditor]
 * editor, the overlay [damien.nodeworks.screen.widget.LuaSyntaxHighlighter] path, and the
 * guidebook `<LuaCode>` tag compiler.
 *
 * Extracted into the common module so all three surfaces tokenise identically — one set
 * of colours, one keyword list, one recovery rule for multi-line block comments. If Lua
 * syntax awareness needs to grow later (e.g. distinguishing `self.foo` from `Foo.bar`),
 * changes here propagate everywhere automatically.
 *
 * Tokeniser is intentionally shallow: it classifies for colouring and docs-lookup
 * purposes, not for AST construction. No attempt at full Lua parsing.
 */
object LuaTokenizer {

    /** Classification of a tokenised slice. Drives colouring AND is inspected by
     *  [LuaApiDocs.resolveAt] to decide whether a token is eligible for a hover docstring
     *  (identifiers get docs; comments / strings / numbers don't). */
    enum class TokenType {
        KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, DEFAULT,
        BLOCK_COMMENT_START, BLOCK_COMMENT_END,
    }

    data class Token(
        val text: String,
        val color: Int,
        val type: TokenType = TokenType.DEFAULT,
    )

    // ==== Colour palette (argb ints, opaque) ====
    // Same hues the in-game editor has shipped with — keep in sync with the editor's
    // expected palette so copying a page's <LuaCode> block into the terminal looks
    // identical.

    const val KEYWORD_COLOR: Int = 0xFFC678DD.toInt()     // purple
    const val STRING_COLOR: Int = 0xFF98C379.toInt()      // green
    const val COMMENT_COLOR: Int = 0xFF5C6370.toInt()     // grey
    const val NUMBER_COLOR: Int = 0xFFD19A66.toInt()      // orange
    const val FUNCTION_COLOR: Int = 0xFF61AFEF.toInt()    // blue
    const val DEFAULT_COLOR: Int = 0xFFABB2BF.toInt()     // light grey

    val KEYWORDS: Set<String> = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "if", "in", "local", "nil", "not", "or", "repeat",
        "return", "then", "true", "until", "while",
    )

    /** Top-level globals the editor should highlight as function-coloured even when
     *  they're referenced without a trailing call-parenthesis. Extend via [LuaApiDocs]
     *  entries whose key has no `:` or `.` — those automatically get included here. */
    val BUILTINS: Set<String> = setOf(
        "card", "scheduler", "print", "network", "clock", "require",
    )

    /**
     * Tokenise a single line of Lua source. [inBlockComment] is the state flag produced
     * by the previous line's tokenisation — pass `false` for the first line, then feed
     * the result of `endsInBlockComment(previous)` into each subsequent call.
     *
     * Output preserves the original text exactly (concatenating all token `text` fields
     * reproduces the input), so it's safe to use this for rendering: every character in
     * the source shows up in some token.
     */
    fun tokenize(line: String, inBlockComment: Boolean = false): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        if (inBlockComment) {
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

            // Line and block comments start with `--`.
            if (ch == '-' && i + 1 < line.length && line[i + 1] == '-') {
                if (i + 3 < line.length && line[i + 2] == '[' && line[i + 3] == '[') {
                    // Block comment.
                    val endIdx = line.indexOf("]]", i + 4)
                    if (endIdx >= 0) {
                        tokens.add(Token(line.substring(i, endIdx + 2), COMMENT_COLOR))
                        i = endIdx + 2
                    } else {
                        tokens.add(Token(line.substring(i), COMMENT_COLOR, TokenType.BLOCK_COMMENT_START))
                        return tokens
                    }
                } else {
                    // Line comment — rest of the line.
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
                val type = when {
                    word in KEYWORDS -> TokenType.KEYWORD
                    color == FUNCTION_COLOR -> TokenType.FUNCTION
                    else -> TokenType.DEFAULT
                }
                tokens.add(Token(word, color, type))
                continue
            }

            tokens.add(Token(ch.toString(), DEFAULT_COLOR))
            i++
        }

        return tokens
    }

    /**
     * Clean up raw source text before it hits Minecraft's font renderer. Minecraft
     * draws unprintable control characters as their Unicode replacement glyph — little
     * boxes reading "CR" (carriage return) or "HT" (horizontal tab) — which is useless
     * noise inside a code block. We collapse line endings to LF and expand tabs to two
     * spaces. Safe to call repeatedly; already-normalized text is a fixed point.
     *
     * Used at the surface (guidebook's `<LuaCode src="…">` load, editor's `insertText`)
     * rather than inside [tokenize], because the tokenizer runs per-line and shouldn't
     * know about multi-line line endings, and so its output stays 1:1 with the input
     * (token `text` concatenation reproduces the input line).
     */
    fun normalize(source: String): String =
        source
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\t", "  ")

    /**
     * Tokenise a multi-line source, threading block-comment state through lines. Returns
     * one token list per line. Convenience wrapper for the guidebook tag and any other
     * non-editor caller that doesn't maintain its own cross-line state.
     */
    fun tokenizeLines(source: String): List<List<Token>> {
        val lines = source.split('\n')
        val result = ArrayList<List<Token>>(lines.size)
        var inBlock = false
        for (line in lines) {
            val toks = tokenize(line, inBlock)
            for (t in toks) {
                if (t.type == TokenType.BLOCK_COMMENT_START) inBlock = true
                if (t.type == TokenType.BLOCK_COMMENT_END) inBlock = false
            }
            result.add(toks)
        }
        return result
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
