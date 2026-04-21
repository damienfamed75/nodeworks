package damien.nodeworks.screen.widget

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
import damien.nodeworks.script.LuaApiDocs
import damien.nodeworks.script.LuaTokenizer
import damien.nodeworks.script.LuaTokenizer.Token
import damien.nodeworks.script.LuaTokenizer.TokenType
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
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
        // Syntax colours are sourced from [damien.nodeworks.script.LuaTokenizer] so the
        // editor, the overlay highlighter, and the guidebook's <LuaCode> tag all use the
        // same palette. Only FOLD_COLOR and editor-chrome colours (selection, cursor,
        // background) stay local since they're not syntax classifications.
        private const val FOLD_COLOR = damien.nodeworks.script.LuaTokenizer.COMMENT_COLOR
        private const val SELECTION_BG = 0xFF264F78.toInt()
        private const val CURSOR_COLOR = 0xFFFFFFFF.toInt()
        private const val BG_COLOR = 0xFF0D0D0D.toInt()
        private const val CURSOR_BLINK_MS = 300L

        /** Seconds of key-held time needed to trigger the guidebook open. Matches
         *  GuideME's own OpenGuideHotkey "~half a second" feel. */
        private const val HOLD_PROGRESS_TARGET = 0.5f

        /** Decay multiplier on release — partial progress drains out quickly so an
         *  interrupted hold doesn't linger visually. */
        private const val HOLD_PROGRESS_DECAY = 4.0f

        /** Minimum progress before we start eating the paired character events. Below
         *  this, the press is treated as a tap and the char flows through normally —
         *  so quickly pressing G while mouse-over a doc-bearing token still types `g`.
         *  A single render frame (~16ms) plus a small margin is enough to separate a
         *  tap from a deliberate hold. */
        private const val HOLD_TAP_GUARD = 0.05f
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

    /** Supplies the variable-type map used by [LuaApiDocs.resolveAt] so hover / G-key
     *  resolution can fall through from `local cards = card; cards:setPowered(…)` to the
     *  `Card:setPowered` doc entry. Default returns an empty map so out-of-terminal
     *  usage of this widget (e.g. unit tests, future embeddings) still gets the bare
     *  + qualified-literal lookups without extra wiring.
     *
     *  The terminal wires this to [AutocompletePopup.getSymbolTable], which already
     *  does full inference (explicit annotations, function-param types, chain resolution,
     *  network:get/var special cases). No parallel inference lives here.
     *
     *  [charPos] is the character offset in [value] to anchor the scope walk at — hover
     *  tooltips use the position of the *hovered token* so a local declared in a function
     *  resolves correctly when the cursor is outside that function. Autocomplete passes
     *  the cursor position. */
    var symbolTableProvider: (charPos: Int) -> Map<String, String> = { emptyMap() }

    /** Invoked when the player hits the open-docs keybind over a token whose doc entry
     *  carries a [LuaApiDocs.Doc.guidebookRef]. The wrapping screen is responsible for
     *  actually navigating — ScriptEditor only knows "this token wants to open this ref". */
    var openGuidebookRef: (ref: String) -> Unit = {}

    /** Polled each frame to decide whether to advance the Hold-G progress bar. Bypasses
     *  focus routing (implemented via raw GLFW `isKeyDown` on the loader side) so the
     *  editor can detect the key as held even while it itself has focus. Default is
     *  always-false — out-of-terminal usages get no progress bar unless wired.
     *
     *  Frame-polling rather than a keyPressed hook because MC auto-repeat fires the
     *  key-press event many times per second while held, which doesn't map cleanly to
     *  a smooth progress ramp. Polling each frame gives us a natural delta-time advance
     *  instead. */
    var isOpenDocsKeyHeld: () -> Boolean = { false }

    /** Cache of UNFOLDED tokens per line, populated each render pass. Used by the
     *  hover-tooltip lookup so we don't re-tokenise every frame — and don't have to redo
     *  the multi-line block-comment state tracking the render loop already does. */
    private val renderedLineTokens = HashMap<Int, List<Token>>()

    /** Last mouse position passed into [extractWidgetRenderState]. Needed by the G-key
     *  handler so it can resolve the hovered token at the moment of the press — KeyEvent
     *  doesn't carry mouse coords. */
    private var lastMouseX: Int = 0
    private var lastMouseY: Int = 0

    /** Char that [charTyped] deferred while the docs key is held over a doc-bearing
     *  token. We buffer instead of inserting so we can decide on release whether this
     *  was a tap (commit the buffered char) or a hold that completed (drop it). Null
     *  when nothing's pending. */
    private var pendingTapChar: String? = null

    /** Hold-G progress in frame units, capped at [HOLD_PROGRESS_TARGET]. Incremented per
     *  frame while [isOpenDocsKeyHeld] returns true AND the mouse is over a doc-bearing
     *  token; decays when the key is released so a partial-hold doesn't stick around. */
    private var holdProgress: Float = 0f

    /** Set after a full hold completes so we only fire [openGuidebookRef] once per
     *  hold — no second firing if the user keeps holding past completion. Reset on
     *  release. */
    private var holdCompleted: Boolean = false

    /** Nanotime of the previous render frame, used to compute delta so the progress
     *  ramp ends up frame-rate-independent. */
    private var lastFrameNanos: Long = 0L

    /**
     * A character range on a line that should render as a short replacement string when
     * the cursor isn't inside it. The buffer text itself is unchanged — folding is purely
     * a display layer. When the cursor falls inside [startCol, endCol), the fold is
     * automatically suppressed so the player can see and edit the underlying text.
     *
     * Both startCol and endCol are 0-based column positions on the same line (raw chars,
     * not display chars). `endCol` is exclusive.
     */
    data class Fold(val startCol: Int, val endCol: Int, val display: String)

    /** Folds that *might* apply to [lineIdx]. The editor automatically suppresses any
     *  fold whose range contains the current cursor (so the player can still type inside
     *  it). Default returns nothing — folding is opt-in by the caller. */
    var foldsForLine: (lineIdx: Int) -> List<Fold> = { emptyList() }


    /** How many extra pixels of vertical space to leave ABOVE [lineIdx] for a decoration
     *  (e.g. an inline recipe-icon hint). Default 0 — no decoration. Returning >0 for a
     *  given line shifts that line and everything after it downward by the returned px.
     *
     *  Setter invalidates the cumulative-Y cache so geometry picks up the new values
     *  on the next render. If the callback's return values depend on external state
     *  (e.g. the network's live recipe list), call [invalidateDecorationCache] to force
     *  a rebuild. */
    var decorationAboveLine: (lineIdx: Int) -> Int = { 0 }
        set(value) {
            field = value
            invalidateDecorationCache()
        }

    /** Draw the decoration that sits in the reserved space above [lineIdx]. Called once per
     *  visible line whose decoration height is > 0. (x, y) is the top-left of the
     *  reserved region; (w, h) is its size. The editor has already scissored to the
     *  editor bounds, so the callback can freely draw within (x, y, w, h) without
     *  worrying about the surrounding chrome. */
    var renderDecoration: (graphics: GuiGraphicsExtractor, lineIdx: Int, x: Int, y: Int, w: Int, h: Int) -> Unit =
        { _, _, _, _, _, _ -> }

    // --- Public API (matches what TerminalScreen expects) ---

    var value: String
        get() {
            return lines.joinToString("\n")
        }
        set(text) {
            // Normalise on the way in: Minecraft's Font draws raw `\r` / `\t` as literal
            // "CR" / "HT" glyph boxes, so anything loaded from disk (or pasted from an
            // editor with CRLF line endings + hard tabs) would render as a field of
            // control-char boxes. [LuaTokenizer.normalize] collapses to LF + soft tabs.
            val normalized = LuaTokenizer.normalize(text)
            rebuildLines(normalized)
            cursor = cursor.coerceAtMost(normalized.length)
            selectStart = -1
            scrollY = 0
            scrollX = 0
        }

    /** Update lines without resetting cursor/selection/scroll — for internal edits. */
    private fun rebuildLines(text: String) {
        lines.clear()
        lines.addAll(if (text.isEmpty()) listOf("") else text.split("\n"))
        invalidateDecorationCache()
    }

    /** Text of line [lineIdx], or empty string if the index is out of range. Used by
     *  decoration callbacks to inspect a specific line without re-splitting the whole
     *  buffer every time. */
    fun getLine(lineIdx: Int): String {
        if (lineIdx < 0 || lineIdx >= lines.size) return ""
        return lines[lineIdx]
    }

    /** Set text and cursor without resetting scroll — for autocomplete insertion. */
    fun setValueKeepScroll(text: String, newCursor: Int) {
        val normalized = LuaTokenizer.normalize(text)
        rebuildLines(normalized)
        cursor = newCursor.coerceIn(0, normalized.length)
        selectStart = -1
        ensureCursorVisible()
        onTextChanged()
    }


    fun setCharacterLimit(limit: Int) { characterLimit = limit }
    fun setValueListener(listener: (String) -> Unit) { valueListener = listener }

    fun getCursorPosition(): Int = cursor

    fun setSelection(start: Int, end: Int) {
        selectStart = start.coerceIn(0, totalTextLength())
        cursor = end.coerceIn(0, totalTextLength())
        ensureCursorVisible()
    }

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

    // --- Variable-height line layout ---
    //
    // Line indexing uses decorations so we don't lose cursor/mouse/scroll accuracy when
    // decorations push lines down. All geometry goes through these helpers; never
    // multiply by lineHeight directly.

    /** Cumulative Y position of each line's text-row top, i.e. `cumulativeY[i]` = y of
     *  the top of line i's text row (after any decoration above). Rebuilt lazily when
     *  [decorationCacheDirty] is true. `cumulativeY[lines.size]` = totalContentHeight. */
    private var cumulativeY: IntArray = IntArray(0)
    private var decorationCacheDirty: Boolean = true

    /** Mark the decoration cache dirty — call whenever the callback's return values
     *  may have changed (e.g. the network's recipe list updated). */
    fun invalidateDecorationCache() {
        decorationCacheDirty = true
    }

    private fun rebuildDecorationCache() {
        cumulativeY = IntArray(lines.size + 1)
        var y = 0
        for (i in lines.indices) {
            y += decorationAboveLine(i)
            cumulativeY[i] = y
            y += lineHeight
        }
        cumulativeY[lines.size] = y
        decorationCacheDirty = false
    }

    private fun ensureDecorationCache() {
        // Size change (line add/remove) invalidates implicitly via this check.
        if (decorationCacheDirty || cumulativeY.size != lines.size + 1) rebuildDecorationCache()
    }

    /** Y-offset (in content coordinates, before scroll) of the TOP of line [lineIdx]'s
     *  text row — i.e. immediately AFTER that line's decoration (if any).
     *
     *  Calling with `lineIdx == lines.size` is valid: it returns the total content
     *  height (= bottom of the last line). Callers that want "Y just past line N" pass
     *  `N + 1` and rely on this behavior. */
    fun yTopOfLine(lineIdx: Int): Int {
        ensureDecorationCache()
        val clamped = lineIdx.coerceIn(0, lines.size)
        return cumulativeY[clamped]
    }

    /** Convert a Y offset in content space (scroll already removed) to a line index.
     *  The decoration zone above a line resolves to that line's index. */
    fun lineAtContentY(contentY: Int): Int {
        if (contentY < 0) return 0
        ensureDecorationCache()
        // Binary search: find last line whose (decoration + body) still starts at or below contentY.
        // cumulativeY[i] = top of line i's body; (cumulativeY[i] - decorationAboveLine(i)) = top of decoration band.
        for (i in lines.indices) {
            val bodyBottom = cumulativeY[i] + lineHeight
            if (contentY < bodyBottom) return i
        }
        return lines.lastIndex
    }

    /** Total content height including all decorations and all line bodies. */
    fun totalContentHeight(): Int {
        ensureDecorationCache()
        return cumulativeY[lines.size]
    }

    /** Y-offset of the BOTTOM of line [lineIdx]'s text row — excludes any decoration
     *  band above the *next* line. Use this for things that want to anchor "directly
     *  under this line" without being pushed down by a following decoration row. */
    fun yBottomOfLine(lineIdx: Int): Int {
        return yTopOfLine(lineIdx) + lineHeight
    }

    // --- Fold helpers ---
    //
    // Cursor / mouse / render math goes through these instead of `font.width(line.substring(...))`
    // directly, so a folded range visually collapses to its display string while the
    // underlying buffer text stays intact. Folds containing the cursor are auto-suppressed
    // so editing inside one always reveals the raw text.

    /** Folds for [lineIdx] with any range containing the cursor removed (sorted by startCol). */
    private fun activeFoldsForLine(lineIdx: Int): List<Fold> {
        val all = foldsForLine(lineIdx)
        if (all.isEmpty()) return emptyList()
        val (curLine, curCol) = cursorToLineCol(cursor)
        val cursorOnThisLine = curLine == lineIdx
        // Also suppress when the selection spans into a fold so the player can see what
        // they're selecting.
        val (selOnLine, selStartCol, selEndCol) = if (hasSelection) {
            val s = cursorToLineCol(selectionStart)
            val e = cursorToLineCol(selectionEnd)
            if (s.first <= lineIdx && e.first >= lineIdx) {
                val sCol = if (s.first < lineIdx) 0 else s.second
                val eCol = if (e.first > lineIdx) Int.MAX_VALUE else e.second
                Triple(true, sCol, eCol)
            } else Triple(false, 0, 0)
        } else Triple(false, 0, 0)
        return all
            .filter { fold ->
                val cursorInside = cursorOnThisLine && curCol in fold.startCol..fold.endCol
                val selInside = selOnLine && selStartCol < fold.endCol && selEndCol > fold.startCol
                !cursorInside && !selInside
            }
            .sortedBy { it.startCol }
    }

    /** Display X (in line-local pixels, before scrollX) of column [col] on [lineIdx],
     *  accounting for active folds. If [col] falls inside a fold, returns the fold's
     *  start X (cursor visually pinned to the fold's leading edge). */
    private fun xOfCol(lineIdx: Int, col: Int): Int {
        val line = lines.getOrNull(lineIdx) ?: return 0
        val folds = activeFoldsForLine(lineIdx)
        var x = 0
        var c = 0
        for (fold in folds) {
            if (col <= fold.startCol) break
            if (col >= fold.endCol) {
                x += font.width(line.substring(c, fold.startCol))
                x += font.width(fold.display)
                c = fold.endCol
            } else {
                // col inside fold (shouldn't normally happen — fold would be suppressed —
                // but guard for safety): return fold's start X.
                x += font.width(line.substring(c, fold.startCol))
                return x
            }
        }
        x += font.width(line.substring(c, col.coerceAtMost(line.length)))
        return x
    }

    /** Reverse of [xOfCol]: given a display X position (line-local, scrollX removed),
     *  returns the raw column index. Halves of characters bias toward the next column,
     *  matching vanilla EditBox feel. Clicks landing on a fold's display string put the
     *  cursor at the fold's start so the player can begin editing inside it. */
    private fun colAtX(lineIdx: Int, relX: Int): Int {
        val line = lines.getOrNull(lineIdx) ?: return 0
        val folds = activeFoldsForLine(lineIdx)
        var px = 0
        var c = 0
        for (fold in folds) {
            // chars [c, fold.startCol) at raw widths
            for (i in c until fold.startCol) {
                val charW = font.width(line[i].toString())
                if (px + charW / 2 > relX) return i
                px += charW
            }
            val displayW = font.width(fold.display)
            // Click on the display: land cursor at fold start so typing reveals the fold.
            if (px + displayW / 2 > relX) return fold.startCol
            px += displayW
            c = fold.endCol
        }
        for (i in c until line.length) {
            val charW = font.width(line[i].toString())
            if (px + charW / 2 > relX) return i
            px += charW
        }
        return line.length
    }

    /** Return [tokens] with any active fold ranges substituted by a single display token
     *  in [FOLD_COLOR]. Token text outside fold ranges is preserved. Assumes tokens
     *  cover [line] in order with no overlaps (true for the editor's tokenizer). */
    private fun applyFolds(lineIdx: Int, tokens: List<Token>): List<Token> {
        val folds = activeFoldsForLine(lineIdx)
        if (folds.isEmpty()) return tokens
        val out = mutableListOf<Token>()
        var c = 0
        for (token in tokens) {
            val tokenStart = c
            val tokenEnd = c + token.text.length
            var local = 0
            for (fold in folds) {
                if (fold.endCol <= tokenStart || fold.startCol >= tokenEnd) continue
                val foldStartLocal = (fold.startCol - tokenStart).coerceAtLeast(0)
                val foldEndLocal = (fold.endCol - tokenStart).coerceAtMost(token.text.length)
                if (local < foldStartLocal) {
                    out.add(Token(token.text.substring(local, foldStartLocal), token.color, token.type))
                }
                out.add(Token(fold.display, FOLD_COLOR, token.type))
                local = foldEndLocal
            }
            if (local < token.text.length) {
                out.add(Token(token.text.substring(local), token.color, token.type))
            }
            c = tokenEnd
        }
        return out
    }

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

    /** Get the word under the given screen coordinates, or null if not over a word. */
    fun getWordAt(mx: Double, my: Double): String? {
        val relY = my - textTop + scrollY
        val lineIdx = lineAtContentY(relY.toInt())
        if (lineIdx < 0 || lineIdx >= lines.size) return null
        // Don't treat clicks on the decoration band as being on text.
        val lineBodyTop = yTopOfLine(lineIdx)
        if (relY < lineBodyTop) return null
        val line = lines[lineIdx]
        val relX = (mx - textLeft + scrollX).toInt()
        val col = colAtX(lineIdx, relX)

        if (col >= line.length) return null
        val ch = line[col]
        if (!ch.isLetterOrDigit() && ch != '_') return null

        // Expand to full word
        var start = col
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        var end = col
        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
        return line.substring(start, end)
    }

    /** Tokenise every line, threading block-comment state through, and stash the result
     *  in [renderedLineTokens]. Run once per frame at the top of
     *  [extractWidgetRenderState] so later frame-phase work (hover resolve, hold-G
     *  advance, actual drawing) all sees the same stable, fully-populated cache. */
    private fun populateTokenCache() {
        renderedLineTokens.clear()
        var inBlockComment = false
        for (lineIdx in lines.indices) {
            val tokens = tokenize(lines[lineIdx], inBlockComment)
            renderedLineTokens[lineIdx] = tokens
            for (t in tokens) {
                if (t.type == TokenType.BLOCK_COMMENT_START) inBlockComment = true
                if (t.type == TokenType.BLOCK_COMMENT_END) inBlockComment = false
            }
        }
    }

    /** Convert screen coordinates to cursor position. */
    private fun screenToCursor(mx: Double, my: Double): Int {
        val relY = my - textTop + scrollY
        val lineIdx = lineAtContentY(relY.toInt()).coerceIn(0, lines.size - 1)
        val relX = (mx - textLeft + scrollX).toInt()
        val col = colAtX(lineIdx, relX)
        return lineColToCursor(lineIdx, col)
    }

    // --- Rendering ---

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        lastMouseX = mouseX
        lastMouseY = mouseY

        // Pre-tokenise every line into the cache up-front, before the hold-progress
        // check needs to read it. Doing this inside the draw loop (as we used to)
        // leaves [renderedLineTokens] empty when [advanceHoldProgress] runs, which
        // silently breaks the Hold-G UX — the resolver never finds a doc under the
        // mouse because there are no tokens yet. The loop below reads from the cache
        // for rendering, so no re-tokenisation.
        populateTokenCache()

        advanceHoldProgress(mouseX, mouseY)

        // Background
        graphics.fill(x, y, x + width, y + height, BG_COLOR)

        // Border
        val borderColor = if (isFocused) 0xFF555555.toInt() else 0xFF333333.toInt()
        graphics.fill(x, y, x + width, y + 1, borderColor)
        graphics.fill(x, y + height - 1, x + width, y + height, borderColor)
        graphics.fill(x + width - 1, y, x + width, y + height, borderColor)

        graphics.enableScissor(x, y + 1, x + width - 1, y + height - 1)

        val (curLine, curCol) = cursorToLineCol(cursor)
        var inBlockComment = false

        for (lineIdx in 0 until lines.size) {
            val lineY = textTop + yTopOfLine(lineIdx) - scrollY
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

            // Draw any decoration sitting above this line's text row.
            val decoH = decorationAboveLine(lineIdx)
            if (decoH > 0) {
                val decoY = lineY - decoH
                if (decoY + decoH > y && decoY < y + height) {
                    renderDecoration(graphics, lineIdx, textLeft, decoY, width - padding * 2, decoH)
                }
            }

            val line = lines[lineIdx]
            val lineStart = lineColToCursor(lineIdx, 0)

            // Selection highlight (uses fold-aware xOfCol so selections across folds
            // align with the visually rendered text).
            if (hasSelection) {
                val selS = selectionStart
                val selE = selectionEnd
                val lineEnd = lineStart + line.length
                if (selS < lineEnd && selE > lineStart) {
                    val hlStart = maxOf(selS - lineStart, 0)
                    val hlEnd = minOf(selE - lineStart, line.length)
                    val x0 = textLeft + xOfCol(lineIdx, hlStart) - scrollX
                    val x1 = textLeft + xOfCol(lineIdx, hlEnd) - scrollX
                    graphics.fill(x0, lineY, x1, lineY + lineHeight, SELECTION_BG)
                }
            }

            // Syntax-highlighted text — fold-aware. applyFolds() splices the raw token
            // stream so any active fold renders as its short display string in FOLD_COLOR.
            // Raw tokens come from the pre-populated cache so hover lookups stay stable
            // across the frame (folded text is a visual shortcut — the logical token for
            // docs is still the original).
            val rawTokens = renderedLineTokens[lineIdx] ?: tokenize(line, inBlockComment)
            val tokens = applyFolds(lineIdx, rawTokens)
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
                val cursorY = textTop + yTopOfLine(curLine) - scrollY
                val cursorX = textLeft + xOfCol(curLine, curCol) - scrollX
                graphics.fill(cursorX, cursorY, cursorX + 1, cursorY + lineHeight, CURSOR_COLOR)
            }
        }

        graphics.disableScissor()

        // Hover-doc tooltip rendering is the hosting screen's job — it draws a 9-sliced
        // tooltip backed by [resolveDocAt] and [getHoldProgressFraction]. Keeping it
        // outside the editor widget lets the screen position the popup above its own
        // chrome and control z-order against the autocomplete popup etc.
    }

    /**
     * Resolve whichever token is under (mouseX, mouseY). Returns null if the mouse isn't
     * over a known-documented identifier. Shared between the hover tooltip renderer and
     * the G-key handler so both code paths see the same doc entry.
     */
    private fun resolveDocUnderMouse(mouseX: Int, mouseY: Int): LuaApiDocs.Doc? {
        if (!isMouseOver(mouseX.toDouble(), mouseY.toDouble())) return null

        // Y must land strictly inside a line's body row. [lineAtContentY] clamps to
        // the first/last line when we're above/below all text, and its contract also
        // returns the next line's index for anything in that line's decoration band —
        // both would cause hovers outside the text to register as hovers over a token.
        val relY = mouseY - textTop + scrollY
        if (relY < 0) return null
        val lineIdx = lineAtContentY(relY)
        if (lineIdx < 0 || lineIdx >= lines.size) return null
        val lineTop = yTopOfLine(lineIdx)
        val lineBottom = lineTop + lineHeight
        if (relY < lineTop || relY >= lineBottom) return null

        val tokens = renderedLineTokens[lineIdx] ?: return null

        // X matching via pixel extents, not col-space. Going through [colAtX] would
        // clamp mouse-left-of-all-text to col=0 and falsely report a hover on the
        // first token. Walking token widths means we only match when the mouse is
        // strictly inside a drawn glyph span.
        var tokenX = textLeft - scrollX
        for ((i, tok) in tokens.withIndex()) {
            val tokenW = font.width(tok.text)
            if (mouseX in tokenX until tokenX + tokenW) {
                // Anchor the symbol-table scope at the END of the hovered line. This makes
                // a hover tooltip inside `function f(item: ItemsHandle) item:matches() end`
                // resolve `item` correctly even when the cursor is outside the function —
                // we want the scope at the token's position, not the cursor's.
                val scopeAnchor = lines.take(lineIdx + 1).sumOf { it.length } + lineIdx
                return LuaApiDocs.resolveAt(tokens, i, symbolTableProvider(scopeAnchor))
            }
            tokenX += tokenW
        }
        return null
    }

    /** True when the token under (mouseX, mouseY) has a [LuaApiDocs] entry. */
    fun hasDocUnderMouse(mouseX: Int, mouseY: Int): Boolean =
        resolveDocUnderMouse(mouseX, mouseY) != null

    /** Public accessor for the hosting screen's tooltip renderer. Returns the doc entry
     *  for whatever token is under (mouseX, mouseY), or null. Uses the shared per-frame
     *  token cache + symbol-table provider, so resolution is identical to what the
     *  hold-G progress logic sees. */
    fun resolveDocAt(mouseX: Int, mouseY: Int): LuaApiDocs.Doc? =
        resolveDocUnderMouse(mouseX, mouseY)

    /** Current Hold-G progress as a [0, 1] fraction. The hosting screen uses this to
     *  render the progress bar in its tooltip footer. */
    fun getHoldProgressFraction(): Float =
        (holdProgress / HOLD_PROGRESS_TARGET).coerceIn(0f, 1f)

    /** True while a press of the docs keybind is either buffering (awaiting release)
     *  or actively advancing the hold timer. The hosting screen uses this to suppress
     *  autocomplete — when the player is holding to open docs, completion suggestions
     *  would just obscure the tooltip they're trying to read. */
    fun isOpenDocsHoldActive(): Boolean = pendingTapChar != null || holdProgress > 0f

    /**
     * Per-frame Hold-G progress update. Increments [holdProgress] while the docs key
     * is held AND the mouse is over a doc-bearing token; decays otherwise.
     *
     * When progress hits 1.0 we fire [openGuidebookRef] exactly once per hold (guarded
     * by [holdCompleted]) and keep progress pinned at full so the bar stays solid as
     * long as the key is held. Releasing the key resets both.
     *
     * While progress is > 0 we also flag [suppressNextCharTyped] so auto-repeat of the
     * held key doesn't spam chars into the editor buffer.
     */
    private fun advanceHoldProgress(mouseX: Int, mouseY: Int) {
        val now = System.nanoTime()
        val deltaSec = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        val held = isOpenDocsKeyHeld()
        val doc = if (held) resolveDocUnderMouse(mouseX, mouseY) else null
        val canAdvance = held && doc?.guidebookRef != null

        if (canAdvance) {
            holdProgress = (holdProgress + deltaSec).coerceAtMost(HOLD_PROGRESS_TARGET)
            if (!holdCompleted && holdProgress >= HOLD_PROGRESS_TARGET) {
                holdCompleted = true
                // Hold fully formed — the buffered char was never meant to be typed.
                pendingTapChar = null
                openGuidebookRef(doc!!.guidebookRef!!)
            }
        } else {
            if (!held) {
                // Key released. If progress didn't cross the tap-guard we treat this
                // as a quick tap and commit the buffered char into the editor so the
                // user's keystroke isn't lost. Past the guard means they held long
                // enough that we don't think they meant to type — drop it.
                val wasTap = holdProgress < HOLD_TAP_GUARD
                val buffered = pendingTapChar
                pendingTapChar = null
                if (wasTap && buffered != null) {
                    if (hasSelection) deleteSelection()
                    insertText(buffered)
                }
                holdCompleted = false
            }
            holdProgress = (holdProgress - deltaSec * HOLD_PROGRESS_DECAY).coerceAtLeast(0f)
        }
    }


    // --- Input handling ---

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
        if (!isFocused) return false
        cursorBlinkTime = System.currentTimeMillis()

        val ctrl = (modifiers and 2) != 0
        val shift = (modifiers and 1) != 0
        val (line, col) = cursorToLineCol(cursor)

        // Hold-G progress is advanced per-frame in [extractWidgetRenderState] — not
        // here. We intentionally don't consume key-presses for the docs keybind so the
        // base keyPressed logic (typing characters, etc.) stays intact. Char suppression
        // is flagged from the frame-update path when the user is actively holding to
        // open docs.

        when (keyCode) {
            // Arrow keys
            263 -> { // LEFT
                if (shift) startSelection()
                if (ctrl) moveCursorWordLeft()
                else if (shift) { if (cursor > 0) cursor-- }
                else moveCursorLeft()
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            262 -> { // RIGHT
                if (shift) startSelection()
                if (ctrl) moveCursorWordRight()
                else if (shift) { if (cursor < totalTextLength()) cursor++ }
                else moveCursorRight()
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
            266 -> { // PAGE UP
                if (shift) startSelection()
                val targetLine = maxOf(0, line - visibleLines)
                cursor = lineColToCursor(targetLine, col)
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }
            267 -> { // PAGE DOWN
                if (shift) startSelection()
                val targetLine = minOf(lines.size - 1, line + visibleLines)
                cursor = lineColToCursor(targetLine, col)
                if (!shift) clearSelection()
                ensureCursorVisible()
                return true
            }

            // Editing keys
            259 -> { // BACKSPACE
                if (hasSelection) { deleteSelection(); return true }
                if (cursor > 0) {
                    if (ctrl) {
                        deleteWordLeft()
                    } else {
                        deleteAt(cursor - 1)
                        cursor--
                    }
                    onTextChanged()
                }
                ensureCursorVisible()
                return true
            }
            261 -> { // DELETE
                if (hasSelection) { deleteSelection(); return true }
                if (ctrl) {
                    deleteWordRight()
                    onTextChanged()
                    ensureCursorVisible()
                    return true
                }
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

    override fun charTyped(event: CharacterEvent): Boolean {
        val codePoint = event.character
        val modifiers = 0
        if (!isFocused) return false

        // If the docs key is currently held AND the mouse is over a doc-bearing token,
        // we don't yet know if this is a tap (→ type the char) or a hold (→ open docs
        // without typing). Buffer the char and let [advanceHoldProgress] decide when
        // the key is released (tap: commit to buffer) or when progress completes (hold:
        // discard). Subsequent auto-repeat chars during the same hold are dropped — we
        // only keep the first.
        if (isOpenDocsKeyHeld() && resolveDocUnderMouse(lastMouseX, lastMouseY) != null) {
            if (pendingTapChar == null) {
                pendingTapChar = codePoint.toString()
            }
            return true
        }

        if (codePoint < ' ' && codePoint != '\t') return false
        cursorBlinkTime = System.currentTimeMillis()

        if (hasSelection) deleteSelection()
        insertText(codePoint.toString())
        return true
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val clickPos = screenToCursor(event.mouseX, event.mouseY)
        cursor = clickPos
        selectStart = -1  // clear selection on click
        cursorBlinkTime = System.currentTimeMillis()
        ensureCursorVisible()
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        val newPos = screenToCursor(event.mouseX, event.mouseY)
        if (newPos != cursor) {
            if (selectStart < 0) selectStart = cursor
            cursor = newPos
            ensureCursorVisible()
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        this.scrollY = (this.scrollY - (scrollY * lineHeight * 3).toInt())
            .coerceIn(0, maxOf(0, totalContentHeight() - (height - padding * 2)))
        return true
    }

    // --- Text manipulation ---

    private fun insertText(text: String) {
        // Normalise BEFORE length math — expanding `\t` to two spaces changes length, so
        // using `text.length` instead of `clean.length` would advance the cursor to the
        // wrong column after a paste containing tabs.
        val clean = LuaTokenizer.normalize(text)
        val fullText = value
        if (fullText.length + clean.length > characterLimit) return
        val newText = StringBuilder(fullText).insert(cursor, clean).toString()
        rebuildLines(newText)
        cursor += clean.length
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
        cursor = findWordBoundaryLeft(value, cursor)
    }

    private fun moveCursorWordRight() {
        cursor = findWordBoundaryRight(value, cursor)
    }

    /**
     * Find the word boundary to the left of [pos], VSCode-style.
     * Stops at: whitespace→non-whitespace, delimiter boundaries, word→non-word transitions.
     */
    private fun findWordBoundaryLeft(text: String, pos: Int): Int {
        if (pos <= 0) return 0
        var p = pos - 1
        // Skip spaces (but stop at newline)
        while (p > 0 && text[p] == ' ') p--
        if (p >= 0 && text[p] == '\n') return p
        return when {
            // At a delimiter — consume that one delimiter
            p >= 0 && text[p].isDelimiter() -> p
            // At a word char — consume the whole word
            p >= 0 && text[p].isWordChar() -> {
                while (p > 0 && text[p - 1].isWordChar()) p--
                p
            }
            else -> maxOf(0, p)
        }
    }

    private fun findWordBoundaryRight(text: String, pos: Int): Int {
        if (pos >= text.length) return text.length
        var p = pos
        // Skip spaces (but stop at newline)
        while (p < text.length && text[p] == ' ') p++
        if (p < text.length && text[p] == '\n') return p + 1
        return when {
            p < text.length && text[p].isDelimiter() -> p + 1
            p < text.length && text[p].isWordChar() -> {
                while (p < text.length && text[p].isWordChar()) p++
                p
            }
            else -> minOf(text.length, p + 1)
        }
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'
    private fun Char.isDelimiter(): Boolean = this in ".:;,\"'()[]{}=+-*/<>!&|#@"

    private fun deleteWordLeft() {
        val text = value
        val newPos = findWordBoundaryLeft(text, cursor)
        if (newPos < cursor) {
            val newText = text.removeRange(newPos, cursor)
            rebuildLines(newText)
            cursor = newPos
        }
    }

    private fun deleteWordRight() {
        val text = value
        val newPos = findWordBoundaryRight(text, cursor)
        if (newPos > cursor) {
            val newText = text.removeRange(cursor, newPos)
            rebuildLines(newText)
        }
    }

    private fun ensureCursorVisible() {
        val (line, col) = cursorToLineCol(cursor)

        // Vertical — use the cursor line's decorated top so scrolling also reveals the
        // decoration sitting above, not just the text row.
        val cursorY = yTopOfLine(line) - decorationAboveLine(line)
        val viewTop = scrollY
        val viewBottom = scrollY + height - padding * 2 - lineHeight
        if (cursorY < viewTop) scrollY = cursorY
        if (cursorY > viewBottom) scrollY = cursorY - (height - padding * 2 - lineHeight)
        scrollY = scrollY.coerceAtLeast(0)

        // Horizontal — fold-aware so scrolling tracks the cursor's actual on-screen X.
        val cursorPixelX = xOfCol(line, col)
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

    // --- Syntax tokenizer ---
    //
    // Delegates to the shared [damien.nodeworks.script.LuaTokenizer] so the editor, the
    // overlay highlighter, and the guidebook's <LuaCode> tag all produce identical token
    // streams. Local aliases keep existing editor code compiling unchanged — [Token] and
    // [TokenType] throughout this file are the shared public types.

    private fun tokenize(line: String, inBlockComment: Boolean): List<Token> =
        damien.nodeworks.script.LuaTokenizer.tokenize(line, inBlockComment)
}
