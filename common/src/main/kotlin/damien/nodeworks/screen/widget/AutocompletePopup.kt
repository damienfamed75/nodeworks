package damien.nodeworks.screen.widget

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.screen.Icons
import damien.nodeworks.script.LuaApiDocs
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Context-aware autocompletion popup for the Lua script editor.
 * Uses a lexical cursor-context parser and symbol table for type-aware suggestions.
 */
class AutocompletePopup(
    private val font: Font,
    private val cards: List<CardSnapshot>,
    private val itemTags: List<String> = emptyList(),
    private val variables: List<Pair<String, Int>> = emptyList(),
    private val localApiNames: List<String> = emptyList(),
    private val craftableOutputs: List<String> = emptyList(),
    private val localApis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo> = emptyList(),
    private val itemIds: List<String> = emptyList(),
    private val fluidIds: List<String> = emptyList(),
    private val fluidTags: List<String> = emptyList(),
    private val scripts: () -> Map<String, String> = { emptyMap() }
) {
    /**
     * VSCode-style category tag for rendering a colored badge next to the suggestion.
     * Each kind carries its own badge letter + background color. Choose based on what
     * the suggestion *is*, not what it inserts — e.g. `network` is a MODULE even though
     * it inserts a plain identifier.
     */
    enum class Kind(val letter: String, val color: Int) {
        MODULE("M", 0xFF8AB4F8.toInt()), // blue
        FUNCTION("F", 0xFFB389F9.toInt()), // purple
        METHOD("M", 0xFFB389F9.toInt()), // purple (same family as function)
        VARIABLE("V", 0xFF9CCC65.toInt()), // green
        PROPERTY("P", 0xFFFFD54F.toInt()), // amber
        KEYWORD("K", 0xFFFF8A65.toInt()), // orange
        SNIPPET("S", 0xFFE57373.toInt()), // red
        TYPE("T", 0xFF4DB6AC.toInt()), // teal
        STRING("s", 0xFFBDBDBD.toInt()), // gray
        TAG("#", 0xFFBDBDBD.toInt()); // gray
    }

    data class Suggestion(
        val insertText: String,
        val displayText: String,
        val snippetText: String? = null,
        val snippetCursor: Int = -1,
        /** If true, the apply logic should also consume any auto-paired characters
         *  following the cursor (e.g. the `")` from typing `handle("` with auto-pair).
         *  Use for full-block snippets that provide their own closing punctuation. */
        val consumesAutoclose: Boolean = false,
        val kind: Kind = Kind.VARIABLE
    )

    var visible: Boolean = false
        private set
    var suggestions: List<Suggestion> = emptyList()
        private set
    var selectedIndex: Int = 0
        private set

    private var popupX: Int = 0
    private var popupY: Int = 0
    private var prefix: String = ""
    private var customPrefix: String? = null
    private var scrollOffset: Int = 0
    private val maxVisible: Int = 8

    /** Optional resolver for the on-screen Y of a line's BOTTOM (relative to the editor's
     *  top-left), accounting for decoration heights above each line. Set by the caller
     *  after constructing the editor; without this the popup falls back to uniform line
     *  height and lands above the cursor whenever decorations push lines down. */
    var lineBottomYResolver: ((lineIdx: Int) -> Int)? = null

    // ========== Public API ==========

    fun update(
        text: String,
        cursorPos: Int,
        editorX: Int,
        editorY: Int,
        forced: Boolean = false,
        editorScrollY: Int = 0,
        editorScrollX: Int = 0,
    ) {
        val cursor = minOf(cursorPos, text.length)

        if (cursor <= 0) {
            hide(); return
        }

        // VSCode parity: don't auto-trigger when the cursor is in the middle of a word
        // (char immediately after is a word char). Typing inside "conn|ection" shouldn't
        // pop the menu — the user is editing an existing identifier, not completing one.
        // Explicit Ctrl+Space (forced=true) bypasses this so the user can still request it.
        if (!forced && cursor < text.length) {
            val nextCh = text[cursor]
            if (nextCh.isLetterOrDigit() || nextCh == '_') {
                hide(); return
            }
        }

        val beforeCursor = text.substring(0, cursor)
        val newSuggestions = computeSuggestions(beforeCursor, text, forced)

        if (newSuggestions.isEmpty()) {
            hide()
            return
        }

        suggestions = newSuggestions
        selectedIndex = 0
        scrollOffset = 0
        visible = true
        prefix = customPrefix ?: extractPrefix(beforeCursor)
        customPrefix = null

        val textBeforeCursor = text.substring(0, cursor)
        val lineAtCursor = textBeforeCursor.count { it == '\n' }
        val lastNewline = textBeforeCursor.lastIndexOf('\n')
        val lineText = textBeforeCursor.substring(lastNewline + 1)
        val cursorXOffset = font.width(lineText)

        // Subtract editorScrollX so the popup follows the cursor's on-screen X when the
        // editor is scrolled horizontally. Without this the popup anchors to the
        // cursor's *logical* column and appears to drift rightward as the user scrolls.
        popupX = editorX + 4 + cursorXOffset - editorScrollX
        // Use the editor's variable-height line layout when available so the popup lands
        // just below the cursor's text row even when recipe-hint decorations push lines
        // down. Resolver path uses a 1-px gap (the resolver already returns content-Y
        // accounting for the editor's internal textTop padding); fallback path keeps the
        // legacy 4-px gap to match historical behavior on callers without a resolver.
        val resolver = lineBottomYResolver
        popupY = if (resolver != null) {
            editorY + resolver(lineAtCursor) + 1 - editorScrollY
        } else {
            editorY + (lineAtCursor + 1) * font.lineHeight + 4 - editorScrollY
        }
    }

    fun hide() {
        visible = false
        suggestions = emptyList()
        selectedIndex = 0
    }

    fun moveUp() {
        if (suggestions.isNotEmpty()) {
            selectedIndex = (selectedIndex - 1 + suggestions.size) % suggestions.size
            ensureVisible()
        }
    }

    fun moveDown() {
        if (suggestions.isNotEmpty()) {
            selectedIndex = (selectedIndex + 1) % suggestions.size
            ensureVisible()
        }
    }

    private fun ensureVisible() {
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex
        } else if (selectedIndex >= scrollOffset + maxVisible) {
            scrollOffset = selectedIndex - maxVisible + 1
        }
    }

    data class AcceptResult(
        val deleteCount: Int,
        val insertText: String,
        val cursorOffset: Int = insertText.length,
        /** How many chars AFTER the cursor to also delete (to absorb auto-paired `")`
         *  when a full-block snippet provides its own closing punctuation). */
        val consumeAfter: Int = 0
    )

    fun accept(textAfterCursor: String = ""): AcceptResult? {
        if (!visible || suggestions.isEmpty()) return null
        val suggestion = suggestions[selectedIndex]
        val deleteCount = prefix.length
        hide()
        if (suggestion.snippetText != null) {
            val cursorPos =
                if (suggestion.snippetCursor >= 0) suggestion.snippetCursor else suggestion.snippetText.length
            val consume = if (suggestion.consumesAutoclose) countAutocloseChars(textAfterCursor) else 0
            return AcceptResult(deleteCount, suggestion.snippetText, cursorPos, consume)
        }
        // Auto-close parentheses: `func(` → `func()` with cursor between
        val text = suggestion.insertText
        if (text.endsWith("(")) {
            val closed = text + ")"
            return AcceptResult(deleteCount, closed, text.length) // cursor between ( and )
        }
        return AcceptResult(deleteCount, text, text.length)
    }

    /** Count leading auto-pair chars we'd redundantly preserve otherwise. Matches the
     *  editor's auto-pair rules: `"`, `)`, `]`, `}`. Stops at the first non-match. */
    private fun countAutocloseChars(afterCursor: String): Int {
        var n = 0
        while (n < afterCursor.length && afterCursor[n] in "\")]}'") n++
        return n.coerceAtMost(4)
    }

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!visible || suggestions.isEmpty()) return

        val itemHeight = font.lineHeight + 2
        val visibleCount = minOf(suggestions.size, maxVisible)
        // Each row now has a Kind badge to the left of the text. Badge occupies
        // BADGE_SIZE px from the left edge of content area, then BADGE_GAP before text.
        val popupWidth = suggestions.maxOf { font.width(it.displayText) } + 8 + BADGE_SIZE + BADGE_GAP
        val actualHeight = visibleCount * itemHeight + 4

        // Keep the popup within the game window — same flip-and-clamp policy the hover
        // tooltip uses. Flipping happens first (popup above the cursor line if there's no
        // room below, left of the anchor if there's no room right) before the final clamp,
        // so a dropdown that barely overflows the right edge slides left instead of getting
        // pinned to the edge with its text cut off.
        val window = net.minecraft.client.Minecraft.getInstance().window
        val gameW = window.guiScaledWidth
        val gameH = window.guiScaledHeight
        var renderX = popupX
        var renderY = popupY
        if (renderX + popupWidth > gameW) renderX = popupX - popupWidth - 4
        if (renderY + actualHeight > gameH) renderY = popupY - actualHeight - font.lineHeight - 4
        renderX = renderX.coerceIn(0, (gameW - popupWidth).coerceAtLeast(0))
        renderY = renderY.coerceIn(0, (gameH - actualHeight).coerceAtLeast(0))

        graphics.fill(renderX, renderY, renderX + popupWidth, renderY + actualHeight, 0xEE1E1E1E.toInt())
        graphics.fill(renderX, renderY, renderX + popupWidth, renderY + 1, 0xFF555555.toInt())
        graphics.fill(renderX, renderY + actualHeight - 1, renderX + popupWidth, renderY + actualHeight, 0xFF555555.toInt())
        graphics.fill(renderX, renderY, renderX + 1, renderY + actualHeight, 0xFF555555.toInt())
        graphics.fill(renderX + popupWidth - 1, renderY, renderX + popupWidth, renderY + actualHeight, 0xFF555555.toInt())

        if (scrollOffset > 0) {
            graphics.drawString(font, "\u25B2", renderX + popupWidth - 10, renderY + 1, 0xFF888888.toInt())
        }
        if (scrollOffset + visibleCount < suggestions.size) {
            graphics.drawString(
                font,
                "\u25BC",
                renderX + popupWidth - 10,
                renderY + actualHeight - font.lineHeight - 1,
                0xFF888888.toInt()
            )
        }

        val textX = renderX + 4 + BADGE_SIZE + BADGE_GAP
        for (i in 0 until visibleCount) {
            val suggestionIndex = scrollOffset + i
            val y = renderY + 2 + i * itemHeight
            if (suggestionIndex == selectedIndex) {
                graphics.fill(renderX + 1, y, renderX + popupWidth - 1, y + itemHeight, 0xFF3A5FCD.toInt())
            }
            val s = suggestions[suggestionIndex]

            // Kind badge: blit the shared 9×9 white badge sprite ([Icons.BADGE]) tinted
            // with the kind's color, then draw the single-letter label centered inside.
            // MC's font.width() includes a 1px trailing space after each glyph, so the
            // visible letter is (letterW - 1) pixels wide — subtract that to get a
            // symmetric X offset. Vertically, capital letters render at rows 1..7 of the
            // 9px line box, so the visible glyph height is 7; pad 1px top + 1px bottom.
            val badgeX = renderX + 4
            val badgeY = y + (itemHeight - BADGE_SIZE) / 2
            Icons.BADGE.drawTopLeftTinted(graphics, badgeX, badgeY, BADGE_SIZE, BADGE_SIZE, s.kind.color)
            val visualLetterW = (font.width(s.kind.letter) - 1).coerceAtLeast(1)
            val visualLetterH = 7
            graphics.drawString(
                font, s.kind.letter,
                badgeX + (BADGE_SIZE - visualLetterW) / 2,
                badgeY + (BADGE_SIZE - visualLetterH) / 2,
                0xFF1E1E1E.toInt(),
                false
            )

            val nameColor = if (suggestionIndex == selectedIndex) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
            val hintColor = if (suggestionIndex == selectedIndex) 0xFFBBBBBB.toInt() else 0xFF888888.toInt()
            val nameWidth = font.width(s.insertText)
            graphics.drawString(font, s.insertText, textX, y + 1, nameColor)
            if (s.displayText != s.insertText) {
                val hint = s.displayText.removePrefix(s.insertText)
                graphics.drawString(font, hint, textX + nameWidth, y + 1, hintColor)
            }
        }
    }

    companion object {
        /** Size of the square Kind badge drawn in each row (px). Chosen so the single-letter
         *  glyph centers neatly with the default font. */
        private const val BADGE_SIZE = 9

        /** Gap between the badge and the suggestion text. */
        private const val BADGE_GAP = 4

        /** Matches aliases produced by the Card Programmer's `_N` auto-suffixing, capturing
         *  the stable prefix. e.g. `cobblestone_0` → `cobblestone`. Used to detect groups
         *  of related cards worth offering a `_*` wildcard for in `network:route` completions.
         *  Anchored start-to-end so a mid-alias digit-suffix like `chest2_part1` doesn't
         *  accidentally get split. */
        private val CARD_SUFFIX_REGEX = Regex("^(.+)_\\d+$")
    }

    // ========== Helpers ==========

    private fun suggest(insertText: String, displayText: String = insertText, kind: Kind = Kind.VARIABLE) =
        Suggestion(insertText, displayText, kind = kind)

    private fun snippet(
        insertText: String,
        displayText: String,
        snippetText: String,
        cursorOffset: Int,
        kind: Kind = Kind.SNIPPET
    ) =
        Suggestion(insertText, displayText, snippetText, cursorOffset, kind = kind)

    private fun fuzzy(query: String, suggestions: List<Suggestion>): List<Suggestion> {
        return if (query.isEmpty()) suggestions else FuzzyMatch.filter(query, suggestions)
    }

    private fun fuzzyStrings(query: String, items: List<String>, kind: Kind = Kind.STRING): List<Suggestion> {
        return if (query.isEmpty()) items.map { suggest(it, kind = kind) }
        else items.map { suggest(it, kind = kind) }.let { FuzzyMatch.filter(query, it) }
    }

    // ========== Cursor Context Parser ==========

    /**
     * The context at the cursor position, determined by scanning backwards from the cursor.
     */
    private sealed interface CursorContext {
        /** `var:partial` — method call on a variable */
        data class MethodCall(val receiver: String, val partial: String) : CursorContext

        /** `var.partial` — property access on a variable */
        data class PropertyAccess(val receiver: String, val partial: String) : CursorContext

        /** Inside a string argument: `func("partial` */
        data class StringArg(val funcExpr: String, val partial: String) : CursorContext

        /** Type annotation context: `local x: partial` or `function(a: partial` */
        data class TypeAnnotation(val partial: String) : CursorContext

        /** `#partial` — item tag filter */
        data class TagFilter(val partial: String) : CursorContext

        /** Method call where the receiver type has been resolved from a chain */
        data class ResolvedMethodCall(val resolvedType: String, val partial: String) : CursorContext

        /** Property access where the receiver type has been resolved from a chain */
        data class ResolvedPropertyAccess(val resolvedType: String, val partial: String) : CursorContext

        /** `<outerVar>.<field>.<partial>` — chain through a table-like typed variable.
         *  Symbols are resolved in [computeSuggestions]; for InputItems the field is
         *  an ItemsHandle, so the partial completes ItemsHandle properties. */
        data class ChainedPropertyAccess(val outerVar: String, val field: String, val partial: String) : CursorContext

        /** `<outerVar>.<field>:<partial>` — chained method call. Same resolution rule
         *  as [ChainedPropertyAccess] but produces method suggestions. */
        data class ChainedMethodCall(val outerVar: String, val field: String, val partial: String) : CursorContext

        /** `xs[idx].<partial>` — property access on the element type of an indexed container.
         *  Receiver type comes from the symbol table (serialized `{ T }` / `{ [K]: V }`).
         *  The index expression itself is ignored — we only need the receiver var. */
        data class IndexedPropertyAccess(val receiver: String, val partial: String) : CursorContext

        /** `xs[idx]:<partial>` — method call on the element type of an indexed container. */
        data class IndexedMethodCall(val receiver: String, val partial: String) : CursorContext

        /** Resolved exports from a module (require or local table) */
        data class ResolvedExports(val exports: List<Suggestion>, val partial: String) : CursorContext

        /** Plain word at cursor — global completions */
        data class Word(val partial: String) : CursorContext

        /** Nothing useful at cursor */
        data object None : CursorContext
    }

    /**
     * Parse the cursor context from the current line (text before cursor on its line).
     * Also takes the full beforeCursor for multi-line patterns like craft builder chains.
     */
    private fun parseCursorContext(currentLine: String, beforeCursor: String): CursorContext {
        val line = currentLine.trimStart()
        if (line.isEmpty()) return CursorContext.None

        // Never autocomplete inside a comment. Line comments (`--`) terminate at EOL so
        // we can decide from just the current line. Block comments (`--[[ … ]]`) are
        // detected by a span-count scan over [beforeCursor] — count openings vs closings;
        // an odd balance means the cursor is inside an open block.
        if (isInsideComment(line, beforeCursor)) return CursorContext.None

        // Check for tag filter: #partial
        if (line.contains('#')) {
            val hashIdx = line.lastIndexOf('#')
            val afterHash = line.substring(hashIdx + 1)
            if (afterHash.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '/' || it == '_' }) {
                return CursorContext.TagFilter(afterHash)
            }
        }

        // Check if we're inside a string argument: scan back for unclosed "
        val inString = findStringArgContext(line)
        if (inString != null) return inString

        // Check for type annotation: `local x: partial` or `function(...param: partial`
        val typeCtx = findTypeAnnotationContext(line)
        if (typeCtx != null) return typeCtx

        // Check for method call: find the last `word:partial` pattern
        // This works regardless of nesting depth: print(foo:pull(bar:
        val colonCtx = findColonContext(line, beforeCursor)
        if (colonCtx != null) return colonCtx

        // Check for property access: find the last `word.partial` pattern
        val dotCtx = findDotContext(line)
        if (dotCtx != null) return dotCtx

        // Don't autocomplete after `local ` — user is naming a new variable
        if (Regex("""\blocal\s+\w*$""").containsMatchIn(line)) return CursorContext.None

        // Fall back to word completion
        val wordMatch = Regex("""(\w+)$""").find(line)
        if (wordMatch != null) {
            return CursorContext.Word(wordMatch.groupValues[1])
        }

        return CursorContext.None
    }

    /** Check if cursor is inside a string argument like `network:get("partial` */
    private fun findStringArgContext(line: String): CursorContext.StringArg? {
        // Find the last unmatched " — we're inside a string if quote count is odd
        var quoteCount = 0
        var lastQuoteIdx = -1
        for (i in line.indices) {
            if (line[i] == '"' && (i == 0 || line[i - 1] != '\\')) {
                quoteCount++
                lastQuoteIdx = i
            }
        }
        if (quoteCount % 2 == 0) return null // quotes are balanced, not inside a string

        // We're inside a string. Extract the partial (text after the last opening quote)
        val partial = line.substring(lastQuoteIdx + 1)

        // Find the function expression before the opening paren that contains this string
        // Scan back from the quote to find `funcExpr(`
        val beforeQuote = line.substring(0, lastQuoteIdx).trimEnd()
        // The opening paren for this string arg
        val parenIdx = findMatchingContext(beforeQuote)
        if (parenIdx >= 0) {
            val funcExpr = beforeQuote.substring(0, parenIdx).trimEnd()
            // Extract the function name/expression (e.g., "network:get", "network:craft", ":face")
            val funcMatch = Regex("""([\w:]+)\s*$""").find(funcExpr)
            if (funcMatch != null) {
                return CursorContext.StringArg(funcMatch.groupValues[1], partial)
            }
        }

        return CursorContext.StringArg("", partial)
    }

    /** Find the position of the `(` that opens the current argument context. */
    private fun findMatchingContext(text: String): Int {
        // Scan backwards to find the `(` considering nesting
        var depth = 0
        for (i in text.lastIndex downTo 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
        }
        return -1
    }

    /** True when the cursor sits inside a Lua comment — either a `--` line comment on
     *  the current line, or anywhere inside an unclosed `--[[ … ]]` block comment in the
     *  entire script-prefix up to the cursor.
     *
     *  For the line-comment case, we walk the line tracking string delimiters so a `--`
     *  inside `"foo--bar"` doesn't falsely mark the cursor as commented out. For the
     *  block-comment case we simply match `--[[` / `]]` pairs across [beforeCursor];
     *  anything with an odd open-count is currently inside a block. */
    private fun isInsideComment(currentLine: String, beforeCursor: String): Boolean {
        // Line-comment: scan the current line for `--` outside of string literals.
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < currentLine.length) {
            val ch = currentLine[i]
            val escaped = i > 0 && currentLine[i - 1] == '\\'
            when {
                !escaped && !inSingle && ch == '"' -> inDouble = !inDouble
                !escaped && !inDouble && ch == '\'' -> inSingle = !inSingle
                !inSingle && !inDouble && ch == '-' && i + 1 < currentLine.length && currentLine[i + 1] == '-' ->
                    return true
            }
            i++
        }

        // Block-comment: count `--[[` openings minus `]]` closings in the text-so-far.
        val blockOpens = Regex("""--\[\[""").findAll(beforeCursor).count()
        val blockCloses = Regex("""]]""").findAll(beforeCursor).count()
        return blockOpens > blockCloses
    }

    /**
     * Split a function-parameter list on top-level commas only — commas nested inside a
     * `{ … }` container-type annotation stay with their parameter. Without this,
     * `from: { [string]: V }, rest: any` would naively split on every comma and break
     * the map-type annotation in half.
     */
    private fun splitParamList(raw: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in raw.indices) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) {
                    result.add(raw.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(raw.substring(start))
        return result.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
    }

    /**
     * Parse a single `name: Type` / `name: { T }` / `name: { [K]: V }` param annotation
     * into `(name, rawTypeString)`. The scalar-annotation parser (`split(":")`) can't
     * handle the map form because the brace carries its own `:` internally; we split on
     * the FIRST top-level `:` only, treating any `:` inside a `{ … }` as content.
     */
    private fun splitParamAnnotation(param: String): Pair<String, String>? {
        var depth = 0
        var colonIdx = -1
        for (i in param.indices) {
            when (param[i]) {
                '{' -> depth++
                '}' -> depth--
                ':' -> if (depth == 0) { colonIdx = i; break }
            }
        }
        if (colonIdx < 0) return null
        val name = param.substring(0, colonIdx).trim().split("\\s+".toRegex()).firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null
        val type = param.substring(colonIdx + 1).trim().removeSuffix("?").trim()
        if (type.isEmpty()) return null
        return name to type
    }

    /** Check for type annotation context on the current line. Covers scalar forms
     *  (`: Type`) and container forms (`: { Element }`, `: { [K]: V }`) for locals,
     *  function params, and function return types. Inside a `{ … }` brace the partial
     *  completes the element type — for both arrays and maps, the element type is what
     *  users care about most when annotating. */
    private fun findTypeAnnotationContext(line: String): CursorContext.TypeAnnotation? {
        // Pattern 1a: `local varName: partial` (scalar)
        val localScalarMatch = Regex("""\blocal\s+\w+\s*:\s*(\w*)$""").find(line)
        if (localScalarMatch != null) return CursorContext.TypeAnnotation(localScalarMatch.groupValues[1])

        // Pattern 1b: `local varName: { partial` or `local varName: { [string]: partial` (container)
        val localContainerMatch = Regex("""\blocal\s+\w+\s*:\s*\{(?:[^}]*?[:=]\s*)?\s*(\w*)$""").find(line)
        if (localContainerMatch != null) return CursorContext.TypeAnnotation(localContainerMatch.groupValues[1])

        // Pattern 2a: `function(param: partial` (scalar param)
        val funcParamScalarMatch = Regex("""\bfunction\s*[\w.]*\s*\([^)]*\w+\s*:\s*(\w*)$""").find(line)
        if (funcParamScalarMatch != null) return CursorContext.TypeAnnotation(funcParamScalarMatch.groupValues[1])

        // Pattern 2b: `function(param: { partial` (container param)
        val funcParamContainerMatch = Regex("""\bfunction\s*[\w.]*\s*\([^)]*\w+\s*:\s*\{(?:[^}]*?[:=]\s*)?\s*(\w*)$""").find(line)
        if (funcParamContainerMatch != null) return CursorContext.TypeAnnotation(funcParamContainerMatch.groupValues[1])

        // Pattern 3a: `function(...): partial` (scalar return)
        val returnScalarMatch = Regex("""\bfunction\s*[\w.]*\s*\([^)]*\)\s*:\s*(\w*)$""").find(line)
        if (returnScalarMatch != null) return CursorContext.TypeAnnotation(returnScalarMatch.groupValues[1])

        // Pattern 3b: `function(...): { partial` (container return)
        val returnContainerMatch = Regex("""\bfunction\s*[\w.]*\s*\([^)]*\)\s*:\s*\{(?:[^}]*?[:=]\s*)?\s*(\w*)$""").find(line)
        if (returnContainerMatch != null) return CursorContext.TypeAnnotation(returnContainerMatch.groupValues[1])

        return null
    }

    /** Find `receiver:partial` context, handling nested expressions and method chains. */
    private fun findColonContext(line: String, beforeCursor: String): CursorContext? {
        val colonMatch = Regex(""":(\w*)$""").find(line) ?: return null
        val partial = colonMatch.groupValues[1]
        val beforeColon = line.substring(0, colonMatch.range.first)
        val trimBefore = beforeColon.trimEnd()
        if (trimBefore.isEmpty()) return null

        // CraftBuilder chain on next line: `network:craft(...)\n  :partial`
        val craftChainMultiline = Regex("""network:craft\([^)]*\)\s*\n\s*:(\w*)$""").find(beforeCursor.trimEnd())
        if (craftChainMultiline != null) {
            return CursorContext.ResolvedMethodCall("CraftBuilder", partial)
        }

        // If `)` before `:` — resolve the full chain type (exclude non-chainable methods)
        if (trimBefore.endsWith(")")) {
            val chainType = resolveExpressionType(trimBefore, forChaining = true)
            if (chainType != null) {
                return CursorContext.ResolvedMethodCall(chainType, partial)
            }
        }

        // Indexed receiver `<receiver>[index]:partial`. `<receiver>` can be either a bare
        // variable (resolved against the symbol table by
        // [CursorContext.IndexedMethodCall]) or a container-returning call chain (resolved
        // inline via [resolveExpressionType] + [elementTypeOf] so we can emit a direct
        // ResolvedMethodCall with the element type).
        if (trimBefore.endsWith("]")) {
            val beforeBracket = stripIndexBrackets(trimBefore)
            if (beforeBracket != null) {
                val bareVar = Regex("""(\w+)$""").matchEntire(beforeBracket.trimEnd())
                if (bareVar != null) {
                    return CursorContext.IndexedMethodCall(bareVar.groupValues[1], partial)
                }
                if (beforeBracket.trimEnd().endsWith(")")) {
                    val chainRt = resolveExpressionReturnType(beforeBracket.trimEnd())
                    if (chainRt != null && chainRt.container != LuaApiDocs.Container.NONE) {
                        return CursorContext.ResolvedMethodCall(chainRt.type, partial)
                    }
                }
            }
        }

        // Chain method call: `<outerVar>.<field>:<partial>` (e.g. `items.copperIngot:count`).
        // For InputItems, `field` is an ItemsHandle — resolve to ItemsHandle method list.
        // The outerVar's type is in the symbol table, but buildSymbolTable runs in
        // computeSuggestions; we detect the shape here and defer resolution via a
        // dedicated ChainedMethodCall context.
        val chainMatch = Regex("""(\w+)\.(\w+)$""").find(trimBefore)
        if (chainMatch != null) {
            return CursorContext.ChainedMethodCall(
                outerVar = chainMatch.groupValues[1],
                field = chainMatch.groupValues[2],
                partial = partial
            )
        }

        // Simple `word:partial`
        val receiverMatch = Regex("""(\w+)$""").find(trimBefore)
        if (receiverMatch != null) {
            return CursorContext.MethodCall(receiverMatch.groupValues[1], partial)
        }

        return null
    }

    /** Scan backwards from end of string to find matching `(`, returns (textBeforeParen, contentInside) or null. */
    private fun findMatchingParenBackward(text: String): Pair<String, String>? {
        if (!text.endsWith(")")) return null
        var depth = 0
        for (i in text.lastIndex downTo 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(0, i) to text.substring(i + 1, text.lastIndex)
                    }
                }
            }
        }
        return null
    }

    /**
     * Unwrap a container type string (as stored in the symbol table: `{ T }` for arrays,
     * `{ [K]: V }` for maps) into the element type T / V. Returns null when [type] is
     * null or isn't in container form — callers use that to skip indexed-access
     * completion on a scalar typed var. Single parser shared with LuaApiDocs.
     */
    private fun elementTypeOf(type: String?): String? {
        if (type == null) return null
        val rt = LuaApiDocs.parseReturnType("() → $type") ?: return null
        return if (rt.container != LuaApiDocs.Container.NONE) rt.type else null
    }

    /**
     * Strip a balanced trailing `[…]` from [text], returning the prefix. Depth tracks
     * nested brackets so `xs[ys[0]]` unwraps to `xs`. Returns null when the text doesn't
     * actually end in a matched index expression.
     */
    private fun stripIndexBrackets(text: String): String? {
        if (!text.endsWith("]")) return null
        var depth = 0
        for (i in text.lastIndex downTo 0) {
            when (text[i]) {
                ']' -> depth++
                '[' -> {
                    depth--
                    if (depth == 0) return text.substring(0, i)
                }
            }
        }
        return null
    }

    /**
     * Resolve the full [LuaApiDocs.ReturnType] (not just scalar) of a call-ending
     * expression like `network:getAll("storage")`. Parallels [resolveExpressionType]
     * but preserves container information so indexed access on a call result (e.g.
     * `network:getAll("storage")[0]`) can pull out the element type.
     */
    private fun resolveExpressionReturnType(expr: String): LuaApiDocs.ReturnType? {
        val trimmed = expr.trimEnd()
        if (!trimmed.endsWith(")")) return null
        val paren = findMatchingParenBackward(trimmed) ?: return null
        val beforeParen = paren.first.trimEnd()
        val methodName = Regex("""(\w+)$""").find(beforeParen)?.groupValues?.get(1) ?: return null
        return LuaApiDocs.methodReturnType(methodName)
            ?: userFunctionReturnType(methodName, cachedFullText)
    }

    /** Scalar return type lookup driving both chain resolution and `local x = fn(...)`
     *  inference. Delegates to [LuaApiDocs.methodReturnType] so the doc signatures are
     *  the single source of truth — no parallel hardcoded map to drift. Container-typed
     *  returns (`{ Type… }`, `{ [K]: V }`) return null here because chaining off an array
     *  value makes no sense; the for-loop inference path uses
     *  [LuaApiDocs.methodReturnType] directly to pull element types. */
    private fun scalarReturnTypeOf(methodName: String): String? {
        val rt = LuaApiDocs.methodReturnType(methodName) ?: return null
        return if (rt.container == LuaApiDocs.Container.NONE) rt.type else null
    }

    /** Methods that return non-chainable values (arrays, primitives). No method/property suggestions after these. */
    private val nonChainableMethods = setOf("shapeless", "count", "insert", "hasTag", "matches")

    /**
     * Resolve the return type of the rightmost method/function call in an expression ending with `)`.
     * When [forChaining] is true, non-chainable methods (returning arrays/primitives) return null.
     * Uses [allReturnTypes] which combines built-in and user-defined function return types.
     */
    private fun resolveExpressionType(expr: String, forChaining: Boolean = false): String? {
        val trimmed = expr.trimEnd()
        if (!trimmed.endsWith(")")) return null

        val parenResult = findMatchingParenBackward(trimmed) ?: return null
        val beforeParen = parenResult.first.trimEnd()

        val methodMatch = Regex("""(\w+)$""").find(beforeParen) ?: return null
        val methodName = methodMatch.groupValues[1]

        if (forChaining && methodName in nonChainableMethods) return null

        // Built-in scalars come from LuaApiDocs; user-defined functions override via
        // [allReturnTypes]. Container-typed built-ins (arrays/maps) intentionally fall
        // through to null here — you can't chain `.foo` off the array value itself.
        allReturnTypes[methodName]?.let { return it }
        return scalarReturnTypeOf(methodName)
    }

    /** User-defined function return types (scalar only). Rebuilt once per computeSuggestions call. */
    private var allReturnTypes: Map<String, String> = emptyMap()

    /** Most recent full-script text stashed by [computeSuggestions] so suggestion helpers
     *  that don't get fullText as a parameter can still inspect the script (e.g. to find
     *  already-registered handler API names). */
    private var cachedFullText: String = ""

    /** Processing API whose handler body contains the cursor, or null if the cursor is
     *  not inside a `network:handle("...", function(...) ... end)` callback. Computed
     *  once per [computeSuggestions] call so field suggestions for `items.<tab>` can
     *  resolve the correct recipe's per-slot parameter names. */
    private var enclosingHandlerApi:
            damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo? = null

    /** Pull the set of API names already registered via `network:handle("X", ...)` in [text]. */
    private fun handledApiNames(text: String): Set<String> {
        val pattern = Regex("""network:handle\s*\(\s*"([^"]+)"""")
        return pattern.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /** User-defined function return types pulled from `function name(...): Type` annotations
     *  in the current script + every module script. Scalar-only; container annotations
     *  (`{ Type }`, `{ [K]: V }`) still parse but are handled separately by
     *  [userFunctionReturnType] so for-loop inference can pick them up. */
    private fun buildReturnTypeMap(fullText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for ((name, retType) in allUserFunctions(fullText)) {
            if (LuaApiDocs.parseReturnType("() → $retType")?.container == LuaApiDocs.Container.NONE) {
                map.putIfAbsent(name, retType)
            }
        }
        return map
    }

    /** Return type for a user-defined function with a `: Type` annotation, or null. Used
     *  by for-loop inference to resolve `for _, v in myFn() do` to the element type when
     *  `myFn`'s annotation is a container like `: { CardHandle }`. */
    private fun userFunctionReturnType(funcName: String, fullText: String): LuaApiDocs.ReturnType? {
        val retType = allUserFunctions(fullText)[funcName] ?: return null
        return LuaApiDocs.parseReturnType("() → $retType")
    }

    /** Scan every in-scope script for `function name(...): ReturnType` annotations,
     *  returning the raw ReturnType string (unparsed so container notations survive). */
    private fun allUserFunctions(fullText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val allTexts = mutableListOf(fullText)
        for ((_, scriptText) in scripts()) {
            allTexts.add(scriptText)
        }
        // Return type can be a bare identifier or a brace-delimited container — capture
        // everything after the `:` up to the first newline so `{ CardHandle }` parses.
        val funcPattern = Regex("""\bfunction\s+[\w.]*?(\w+)\s*\([^)]*\)\s*:\s*([^\n]+)""")
        for (text in allTexts) {
            for (match in funcPattern.findAll(text)) {
                map.putIfAbsent(match.groupValues[1], match.groupValues[2].trim())
            }
        }
        return map
    }

    /** Find `receiver.partial` context, including after method chains. */
    private fun findDotContext(line: String): CursorContext? {
        val dotMatch = Regex("""\.(\w*)$""").find(line) ?: return null
        val partial = dotMatch.groupValues[1]
        val beforeDot = line.substring(0, dotMatch.range.first).trimEnd()
        if (beforeDot.isEmpty()) return null

        // If `)` before `.` — check for require("module"). or resolve chain type
        if (beforeDot.endsWith(")")) {
            // require("module").partial → suggest module exports
            val requireMatch = Regex("""require\(\s*"(\w+)"\s*\)$""").find(beforeDot)
            if (requireMatch != null) {
                val moduleName = requireMatch.groupValues[1]
                val moduleText = scripts()[moduleName]
                if (moduleText != null) {
                    // Find table vars in the module and extract their members
                    val tableVarPattern = Regex("""\blocal\s+(\w+)\s*=\s*\{\s*\}""")
                    val tableVars = tableVarPattern.findAll(moduleText).map { it.groupValues[1] }.toSet()
                    val exports = mutableListOf<Suggestion>()
                    for (tv in tableVars) {
                        exports.addAll(extractTableMembers(moduleText, tv))
                    }
                    if (exports.isNotEmpty()) {
                        return CursorContext.ResolvedExports(exports.distinctBy { it.insertText }, partial)
                    }
                }
            }

            val chainType = resolveExpressionType(beforeDot, forChaining = true)
            if (chainType != null) {
                return CursorContext.ResolvedPropertyAccess(chainType, partial)
            }
        }

        // Indexed receiver `<receiver>[index].partial` — property counterpart of the `:`
        // indexed-method logic in [findColonContext]. Handles both bare-var and
        // chain-call receivers.
        if (beforeDot.endsWith("]")) {
            val beforeBracket = stripIndexBrackets(beforeDot)
            if (beforeBracket != null) {
                val bareVar = Regex("""(\w+)$""").matchEntire(beforeBracket.trimEnd())
                if (bareVar != null) {
                    return CursorContext.IndexedPropertyAccess(bareVar.groupValues[1], partial)
                }
                if (beforeBracket.trimEnd().endsWith(")")) {
                    val chainRt = resolveExpressionReturnType(beforeBracket.trimEnd())
                    if (chainRt != null && chainRt.container != LuaApiDocs.Container.NONE) {
                        return CursorContext.ResolvedPropertyAccess(chainRt.type, partial)
                    }
                }
            }
        }

        // Chain access: `<outerVar>.<field>.<partial>` (e.g. `items.copperIngot.count`).
        // Symbol lookup happens later in computeSuggestions where the symbol table is
        // already built; we only parse the shape here.
        val chainMatch = Regex("""(\w+)\.(\w+)$""").find(beforeDot)
        if (chainMatch != null) {
            return CursorContext.ChainedPropertyAccess(
                outerVar = chainMatch.groupValues[1],
                field = chainMatch.groupValues[2],
                partial = partial
            )
        }

        // Simple `word.partial`
        val receiverMatch = Regex("""(\w+)$""").find(beforeDot) ?: return null
        return CursorContext.PropertyAccess(receiverMatch.groupValues[1], partial)
    }

    // ========== Symbol Table ==========

    /** Public access to the symbol table for hover tooltips. */
    fun getSymbolTable(fullText: String, beforeCursor: String): Map<String, String> =
        buildSymbolTable(fullText, beforeCursor)

    /** Get function signature for a given function name. Checks current script and all module scripts. */
    fun getFunctionSignature(funcName: String, fullText: String): String? {
        val allTexts = mutableListOf(fullText)
        for ((_, scriptText) in scripts()) allTexts.add(scriptText)
        // Return type can be a scalar (`CardHandle`, `CardHandle?`) or a brace-delimited
        // container (`{ CardHandle }`, `{ [string]: V }`). The alternation covers both;
        // without it a function declared `function f(): { T }` would hover without
        // its return-type annotation.
        val pattern = Regex("""\bfunction\s+([\w.]*${Regex.escape(funcName)})\s*\(([^)]*)\)\s*(?::\s*(\w+\??|\{[^}]*}))?""")
        for (text in allTexts) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues[1]
            val params = match.groupValues[2].trim().split(",").joinToString(", ") { it.trim() }
            val retType = match.groupValues[3].ifEmpty { null }
            val retStr = if (retType != null) " → $retType" else ""
            return "$name($params)$retStr"
        }
        return null
    }

    private fun buildSymbolTable(fullText: String, beforeCursor: String): Map<String, String> {
        val symbols = mutableMapOf<String, String>()
        // Container-typed locals. Populated by both explicit `local xs: { T }` annotations
        // and inferred `local xs = fn()` assignments where fn returns a container. Tracked
        // separately from [symbols] (which holds scalar types) so for-loop inference can
        // resolve element types without the rest of the code having to distinguish.
        val containerVars = mutableMapOf<String, Pair<String, LuaApiDocs.Container>>()

        // 1a. Explicit scalar type annotations: local x: Type = ...
        Regex("""\blocal\s+(\w+)\s*:\s*(\w+)\??\s*(?:=|\n|$)""").findAll(fullText).forEach {
            symbols[it.groupValues[1]] = it.groupValues[2]
        }

        // 1b. Explicit container type annotations: local xs: { T } = ... or local xs: { [K]: V } = ...
        // Uses [LuaApiDocs.parseReturnType]'s brace grammar so user annotations and doc
        // signatures share one parser — keeps the two surfaces from drifting.
        Regex("""\blocal\s+(\w+)\s*:\s*(\{[^}]*})""").findAll(fullText).forEach { match ->
            val varName = match.groupValues[1]
            val annotation = match.groupValues[2]
            val rt = LuaApiDocs.parseReturnType("() → $annotation")
            if (rt != null && rt.container != LuaApiDocs.Container.NONE) {
                containerVars[varName] = rt.type to rt.container
            }
        }

        // 2. Function parameter annotations — only from scopes the cursor is inside.
        // Allow container types like `from: { CardHandle }` in the capture by matching
        // the content between `(` and `)` greedily over non-`)` chars; the post-split
        // phase handles both scalar `x: Type` and brace-delimited `x: { T }` forms.
        val funcPattern = Regex("""\bfunction\s*\w*\s*\(([^)]*)\)""")
        val endPattern = Regex("""\bend\b""")
        val scopeStack = mutableListOf<List<Pair<String, String>>>()
        for (line in beforeCursor.lines()) {
            for (match in funcPattern.findAll(line.trim())) {
                val paramTypes = mutableListOf<Pair<String, String>>()
                for (param in splitParamList(match.groupValues[1])) {
                    val (name, type) = splitParamAnnotation(param) ?: continue
                    paramTypes.add(name to type)
                }
                scopeStack.add(paramTypes)
            }
            for (match in endPattern.findAll(line.trim())) {
                if (scopeStack.isNotEmpty()) scopeStack.removeLast()
            }
        }
        // Add params from currently open scopes to [symbols] AND to [containerVars] when
        // their annotation parses as a container. The containerVars entry lets a
        // for-loop over a param (e.g. `for _, v in from do` where `from: { CardHandle }`)
        // infer the element type without the user having to assign the param locally.
        for (scope in scopeStack) {
            for ((name, type) in scope) {
                symbols.putIfAbsent(name, type)
                val rt = LuaApiDocs.parseReturnType("() → $type")
                if (rt != null && rt.container != LuaApiDocs.Container.NONE) {
                    containerVars.putIfAbsent(name, rt.type to rt.container)
                }
            }
        }

        // 3. Assignment inference via chain resolution (don't override explicit annotations)
        // Special case: network:get("alias") — check if alias refers to a redstone card
        Regex("""\blocal\s+(\w+)\s*=\s*network:get\s*\(\s*"([\w]+)"\s*\)""").findAll(fullText).forEach {
            val varName = it.groupValues[1]
            val alias = it.groupValues[2]
            if (varName !in symbols) {
                val card = cards.firstOrNull { c -> c.effectiveAlias == alias }
                val type = if (card?.capability?.type == "redstone") "RedstoneCard" else "CardHandle"
                symbols[varName] = type
            }
        }
        // Special case: network:var("name") needs argument to determine specific type
        Regex("""\blocal\s+(\w+)\s*=\s*network:var\s*\(\s*"(\w+)"\s*\)""").findAll(fullText).forEach {
            val varName = it.groupValues[1]
            val netVarName = it.groupValues[2]
            val typeOrd = variables.firstOrNull { v -> v.first == netVarName }?.second
            val type = when (typeOrd) {
                0 -> "NumberVariableHandle"
                1 -> "StringVariableHandle"
                2 -> "BoolVariableHandle"
                else -> "VariableHandle"
            }
            symbols.putIfAbsent(varName, type)
        }

        // Build function return type map for user-defined functions
        val funcReturnTypes = mutableMapOf<String, String>()
        extractFunctions(fullText).forEach { f ->
            if (f.returnType != null) funcReturnTypes[f.name] = f.returnType
        }

        // General inference: local x = expr
        // (`containerVars` was initialized above and may already carry explicit-annotation
        // entries; RHS-inferred container types are merged in below without overwriting
        // the explicit user annotation, which is always authoritative.)
        Regex("""\blocal\s+(\w+)\s*=\s*(.+)""").findAll(fullText).forEach { match ->
            val varName = match.groupValues[1]
            if (varName !in symbols) {
                val rhs = match.groupValues[2].trim()
                // Literal inference
                val literalType = when {
                    rhs == "true" || rhs == "false" -> "boolean"
                    rhs.startsWith("\"") || rhs.startsWith("'") || rhs.startsWith("[[") -> "string"
                    rhs.firstOrNull()?.let { it.isDigit() || (it == '-' && rhs.length > 1) } == true -> "number"
                    else -> null
                }
                if (literalType != null) {
                    symbols[varName] = literalType
                } else if (rhs.endsWith(")")) {
                    // Try chain resolution (handles method calls like :find, :face, etc.)
                    val chainType = resolveExpressionType(rhs)
                    if (chainType != null) {
                        symbols[varName] = chainType
                    } else {
                        // Container-returning call? Record element type + container kind
                        // so for-loops over this var can resolve without a wrapper.
                        val methodName = Regex("""(\w+)\s*\(""").findAll(rhs).lastOrNull()?.groupValues?.get(1)
                        val rt = methodName?.let {
                            LuaApiDocs.methodReturnType(it) ?: userFunctionReturnType(it, fullText)
                        }
                        if (rt != null && rt.container != LuaApiDocs.Container.NONE) {
                            containerVars.putIfAbsent(varName, rt.type to rt.container)
                        } else {
                            // Try user-defined function return type: local x = funcName(...)
                            val funcCallMatch = Regex("""^(\w+)\s*\(""").find(rhs)
                            if (funcCallMatch != null) {
                                val funcName = funcCallMatch.groupValues[1]
                                val retType = funcReturnTypes[funcName]
                                if (retType != null) symbols[varName] = retType
                            }
                        }
                    }
                }
            }
        }

        // Expose container-typed vars to the scalar [symbols] map using their serialized
        // form (`{ T }` for arrays, `{ [string]: T }` for maps). The hover-tooltip fallback
        // in TerminalScreen reads directly from [getSymbolTable], so this gives
        // `local specificCards: { CardHandle } = …` a proper `specificCards: { CardHandle }`
        // hover — matching how plain `local n = 0` renders `n: number`. No collision with
        // property/method completion: those only match known scalar type keys, and
        // `"{ CardHandle }"` isn't one.
        for ((name, container) in containerVars) {
            if (name in symbols) continue
            val (element, kind) = container
            symbols[name] = when (kind) {
                LuaApiDocs.Container.ARRAY -> "{ $element }"
                LuaApiDocs.Container.MAP -> "{ [string]: $element }"
                LuaApiDocs.Container.NONE -> element
            }
        }

        // For-loop element inference: `for _, v in EXPR do` / `for v in EXPR do` where
        // EXPR returns a container. Handles three shapes:
        //   for _, v in fn() do            -- direct container-returning call
        //   for _, v in ipairs(fn()) do    -- explicit ipairs, any container-returning expr inside
        //   for _, v in vars do            -- bare var that holds a container (from local inference)
        //   for _, v in ipairs(vars) do    -- explicit wrapper around a container-holding var
        // All four unwrap to the same element-type lookup.
        val forPattern = Regex("""\bfor\s+(\w+)(?:\s*,\s*(\w+))?\s+in\s+(.+?)\s+do\b""")
        for (match in forPattern.findAll(fullText)) {
            val keyName = match.groupValues[1]
            val valName = match.groupValues[2].takeIf { it.isNotEmpty() }
            val rawExpr = match.groupValues[3].trim()
            val iterKind = containerFromIterExpr(rawExpr, fullText, containerVars) ?: continue
            val (elementType, container) = iterKind

            // `for k, v in ...`: k = key, v = element.
            // `for v in ...`: v = key (first return of the iterator — index for ipairs,
            // string for pairs). Only when two names are present does the element type
            // get bound.
            if (valName != null) {
                if (valName !in symbols) symbols[valName] = elementType
                if (keyName !in symbols && keyName != "_") {
                    val keyType = when (container) {
                        LuaApiDocs.Container.ARRAY -> "number"
                        LuaApiDocs.Container.MAP -> "string"
                        else -> null
                    }
                    if (keyType != null) symbols[keyName] = keyType
                }
            }
        }

        return symbols
    }

    /**
     * Given the expression after `in` in a `for ... in EXPR do` — with or without an
     * `ipairs(…)` / `pairs(…)` wrapper — return the element type and container kind.
     * Resolves three shapes:
     *   * a function/method call (`fn()`, `card:find(…)`) → via [LuaApiDocs.methodReturnType]
     *   * a bare identifier (`xs`) → via [containerVars] built from earlier `local` scans
     *   * either of the above inside `ipairs(…)` / `pairs(…)`
     *
     * Wrapper choice on the user's side is authoritative when present — `ipairs(xs)` will
     * narrow the container kind to ARRAY even if `xs` is typed as MAP; that mirrors what
     * Lua actually does at runtime and keeps key inference (`i: number` vs `k: string`)
     * honest. Returns null if the expression can't be resolved to a container.
     */
    private fun containerFromIterExpr(
        expr: String,
        fullText: String,
        containerVars: Map<String, Pair<String, LuaApiDocs.Container>>,
    ): Pair<String, LuaApiDocs.Container>? {
        val forcedWrapper = when {
            expr.startsWith("ipairs(") && expr.endsWith(")") -> LuaApiDocs.Container.ARRAY
            expr.startsWith("pairs(") && expr.endsWith(")") -> LuaApiDocs.Container.MAP
            else -> null
        }
        val unwrapped = when (forcedWrapper) {
            LuaApiDocs.Container.ARRAY -> expr.removePrefix("ipairs(").removeSuffix(")").trim()
            LuaApiDocs.Container.MAP -> expr.removePrefix("pairs(").removeSuffix(")").trim()
            else -> expr
        }

        // Bare identifier: look up the container var table built during `local` inference.
        if (Regex("""^\w+$""").matches(unwrapped)) {
            val entry = containerVars[unwrapped] ?: return null
            val (elementType, container) = entry
            return elementType to (forcedWrapper ?: container)
        }

        // Function/method call at the tail — resolve via LuaApiDocs or user fn annotations.
        if (!unwrapped.endsWith(")")) return null
        val paren = findMatchingParenBackward(unwrapped) ?: return null
        val beforeParen = paren.first.trimEnd()
        val methodName = Regex("""(\w+)$""").find(beforeParen)?.groupValues?.get(1) ?: return null

        val rt = LuaApiDocs.methodReturnType(methodName)
            ?: userFunctionReturnType(methodName, fullText)
            ?: return null
        if (rt.container == LuaApiDocs.Container.NONE) return null
        return rt.type to (forcedWrapper ?: rt.container)
    }

    // ========== Suggestion Generation ==========

    private fun computeSuggestions(beforeCursor: String, fullText: String, forced: Boolean): List<Suggestion> {
        allReturnTypes = buildReturnTypeMap(fullText)
        cachedFullText = fullText
        enclosingHandlerApi = findEnclosingHandlerApi(beforeCursor)
        val currentLine = beforeCursor.substringAfterLast('\n')

        // Special case: network:handle("name", partial → function snippet
        val handleSnippet = checkHandleSnippetContext(beforeCursor)
        if (handleSnippet != null) return handleSnippet

        val ctx = parseCursorContext(currentLine, beforeCursor)
        val symbols = buildSymbolTable(fullText, beforeCursor)

        return when (ctx) {
            is CursorContext.StringArg -> suggestStringArg(ctx)
            is CursorContext.TypeAnnotation -> suggestTypeAnnotation(ctx.partial)
            is CursorContext.TagFilter -> suggestTag(ctx.partial)
            is CursorContext.ResolvedMethodCall -> suggestMethodsForType(ctx.resolvedType, ctx.partial)
            is CursorContext.ResolvedPropertyAccess -> suggestPropertiesForType(ctx.resolvedType, ctx.partial)
            is CursorContext.ChainedPropertyAccess -> suggestChainedPropertyAccess(ctx, symbols)
            is CursorContext.ChainedMethodCall -> suggestChainedMethodCall(ctx, symbols)
            is CursorContext.IndexedPropertyAccess -> {
                val element = elementTypeOf(symbols[ctx.receiver]) ?: return emptyList()
                suggestPropertiesForType(element, ctx.partial)
            }
            is CursorContext.IndexedMethodCall -> {
                val element = elementTypeOf(symbols[ctx.receiver]) ?: return emptyList()
                suggestMethodsForType(element, ctx.partial)
            }
            is CursorContext.ResolvedExports -> fuzzy(ctx.partial, ctx.exports)
            is CursorContext.MethodCall -> suggestMethodCall(ctx, symbols, fullText)
            is CursorContext.PropertyAccess -> suggestPropertyAccess(ctx, symbols, fullText)
            is CursorContext.Word -> suggestWord(ctx.partial, fullText, beforeCursor, symbols, forced)
            // Ctrl+Space with cursor at an empty position (start of line, after a space,
            // after a punctuation char that doesn't trigger method/property/string context)
            // falls through as None. When the user explicitly requested autocomplete
            // (forced), treat it as an empty-prefix word completion so they see the full
            // menu of available keywords, APIs, user vars, and user functions. Without
            // this, Ctrl+Space on a blank line silently does nothing.
            is CursorContext.None -> if (forced) suggestWord(
                "",
                fullText,
                beforeCursor,
                symbols,
                forced = true
            ) else emptyList()
        }
    }

    private fun suggestStringArg(ctx: CursorContext.StringArg): List<Suggestion> {
        customPrefix = ctx.partial
        return when {
            ctx.funcExpr.endsWith("network:get") -> {
                val cardSuggestions = cards
                    .map { it.effectiveAlias to it.capability.type }
                    .distinct()
                    .map { suggest(it.first, "${it.first} (${it.second})", Kind.STRING) }
                FuzzyMatch.filter(ctx.partial, cardSuggestions)
            }

            ctx.funcExpr.endsWith("network:route") -> {
                val storageAliases = cards
                    .filter { it.capability.type == "storage" }
                    .map { it.effectiveAlias }
                    .distinct()

                // Group aliases sharing the Card Programmer's `_N` suffix convention so we
                // can offer a single `<prefix>_*` completion per group. Singletons don't
                // count — a group of one card collapses back into a literal alias.
                val suffixGroups = storageAliases
                    .mapNotNull { alias ->
                        val match = CARD_SUFFIX_REGEX.matchEntire(alias) ?: return@mapNotNull null
                        match.groupValues[1] to alias
                    }
                    .groupBy({ it.first }, { it.second })
                    .filterValues { it.size >= 2 }

                // Hide the numbered cards from a grouped set by default — the `_*` wildcard
                // represents the user's likely intent, and listing ten `cobblestone_N`
                // entries alongside it is just noise. A user who wants one specific numbered
                // card types the digit suffix themselves (`cobblestone_2`), which flips the
                // group into "disambiguation mode" and the individual cards surface again.
                val hiddenAliases = mutableSetOf<String>()
                for ((prefix, aliases) in suffixGroups) {
                    val stem = "${prefix}_"
                    val disambiguating = ctx.partial.startsWith(stem) &&
                        ctx.partial.length > stem.length &&
                        ctx.partial[stem.length].isDigit()
                    if (!disambiguating) hiddenAliases.addAll(aliases)
                }

                val storageSuggestions = mutableListOf<Suggestion>()
                // Wildcards first so they surface above any remaining literal cards in the
                // fuzzy-matched output — they're the recommended choice for grouped cards.
                for ((prefix, aliases) in suffixGroups) {
                    val wildcard = "${prefix}_*"
                    val preview = aliases.sorted().take(3).joinToString(", ") +
                        if (aliases.size > 3) ", …" else ""
                    storageSuggestions.add(
                        suggest(wildcard, "$wildcard (${aliases.size} cards: $preview)", Kind.STRING)
                    )
                }
                for (alias in storageAliases) {
                    if (alias in hiddenAliases) continue
                    storageSuggestions.add(suggest(alias, "$alias (storage)", Kind.STRING))
                }
                FuzzyMatch.filter(ctx.partial, storageSuggestions)
            }

            ctx.funcExpr.endsWith("network:getAll") -> {
                fuzzyStrings(ctx.partial, listOf("io", "storage"))
            }

            ctx.funcExpr.endsWith("network:craft") -> {
                fuzzyStrings(ctx.partial, craftableOutputs)
            }

            ctx.funcExpr.endsWith("network:var") -> {
                val typeLabels = arrayOf("number", "string", "bool")
                val suggestions = variables.map { (name, typeOrd) ->
                    val typeLabel = typeLabels.getOrElse(typeOrd) { "unknown" }
                    suggest(name, "$name ($typeLabel)", Kind.STRING)
                }
                FuzzyMatch.filter(ctx.partial, suggestions)
            }

            ctx.funcExpr.endsWith(":face") -> {
                val faces = listOf("top", "bottom", "north", "south", "east", "west", "side")
                fuzzyStrings(ctx.partial, faces)
            }

            ctx.funcExpr.endsWith("network:handle") -> {
                // Full-block snippet: accepting a suggestion inserts the whole handle()
                // call — closing quote, comma, function signature with typed per-slot
                // parameters, empty body, and matching closing `end)`. Cursor lands on
                // the indented body line so the player can start typing logic immediately.
                // See docs/design/processing-set-handler-ux.md Phase B.
                val suggestions = localApis.map { api ->
                    buildHandleFullSnippet(api)
                }
                FuzzyMatch.filter(ctx.partial, suggestions)
            }

            ctx.funcExpr.endsWith("require") -> {
                val scriptNames = scripts().keys.filter { it != "main" }.toList()
                fuzzyStrings(ctx.partial, scriptNames)
            }

            isResourceFilterFunc(ctx.funcExpr) -> suggestResourceFilter(ctx.partial)

            else -> emptyList()
        }
    }

    /** Lua functions whose string argument is a resource-id filter (items + fluids).
     *  `:insert` / `:tryInsert` are NOT resource-filter funcs — their first arg is an
     *  ItemsHandle, so suggesting resource ids there would be actively misleading.
     *  `:matches` goes through the same `CardHandle.matchesFilter` logic as `:find`, so it
     *  gets the same id/tag/regex completions. */
    private fun isResourceFilterFunc(funcExpr: String): Boolean =
        funcExpr.endsWith(":find") ||
        funcExpr.endsWith(":findEach") ||
        funcExpr.endsWith(":count") ||
        funcExpr.endsWith(":matches") ||
        funcExpr == "find" || funcExpr == "findEach" || funcExpr == "count" || funcExpr == "matches"

    /**
     * Resource-filter strings accept:
     *  - bare item ids (`minecraft:iron_ingot`)
     *  - `${'$'}item:<id>` / `${'$'}fluid:<id>` kind-qualified ids
     *  - `*`, `<mod>:*` wildcards
     *  - `#<tag>` tag matches (handled separately by TagFilter context)
     *
     * Suggest sigils when the user is just starting, and pivot to id completion once a
     * kind prefix is committed.
     */
    private fun suggestResourceFilter(partial: String): List<Suggestion> {
        customPrefix = partial
        return when {
            partial.startsWith("\$item:") -> {
                val inner = partial.removePrefix("\$item:")
                val hits = FuzzyMatch.filter(inner, itemIds.map { Suggestion(it, it, kind = Kind.STRING) })
                hits.map { s -> s.copy(insertText = "\$item:${s.insertText}", displayText = "\$item:${s.displayText}") }
            }
            partial.startsWith("\$fluid:") -> {
                val inner = partial.removePrefix("\$fluid:")
                val hits = FuzzyMatch.filter(inner, fluidIds.map { Suggestion(it, it, kind = Kind.STRING) })
                hits.map { s -> s.copy(insertText = "\$fluid:${s.insertText}", displayText = "\$fluid:${s.displayText}") }
            }
            partial.startsWith("\$") -> {
                val sigils = listOf(
                    Suggestion("\$item:", "\$item: — match items only", kind = Kind.STRING),
                    Suggestion("\$fluid:", "\$fluid: — match fluids only", kind = Kind.STRING)
                )
                FuzzyMatch.filter(partial, sigils)
            }
            else -> {
                // No prefix: fuzzy-match across sigils + item ids + fluid ids. Fluid entries
                // are inserted as `$fluid:<id>` so accepting one commits to the fluid kind —
                // bare `minecraft:water` would resolve to an item-side lookup first and mislead
                // the user, so this forces explicit qualification on accept.
                val idSuggestions = itemIds.map { Suggestion(it, it, kind = Kind.STRING) }
                val fluidSuggestions = fluidIds.map {
                    Suggestion("\$fluid:$it", "\$fluid:$it", kind = Kind.STRING)
                }
                val sigils = listOf(
                    Suggestion("\$item:", "\$item: — match items only", kind = Kind.STRING),
                    Suggestion("\$fluid:", "\$fluid: — match fluids only", kind = Kind.STRING)
                )
                FuzzyMatch.filter(partial, sigils + idSuggestions + fluidSuggestions).take(20)
            }
        }
    }

    private val knownTypes = listOf(
        Suggestion("string", "string", kind = Kind.TYPE),
        Suggestion("number", "number", kind = Kind.TYPE),
        Suggestion("boolean", "boolean", kind = Kind.TYPE),
        Suggestion("any", "any", kind = Kind.TYPE),
        Suggestion("InputItems", "InputItems — handler input bag; access slot handles by name", kind = Kind.TYPE),
        Suggestion("ItemsHandle", "ItemsHandle — item reference from find/craft", kind = Kind.TYPE),
        Suggestion("CardHandle", "CardHandle — IO/Storage card from network:get", kind = Kind.TYPE),
        Suggestion("RedstoneCard", "RedstoneCard — redstone card from network:get", kind = Kind.TYPE),
        Suggestion("Job", "Job — processing handler context from network:handle", kind = Kind.TYPE),
        Suggestion("CraftBuilder", "CraftBuilder — from network:craft(), chain with :connect()", kind = Kind.TYPE),
        Suggestion("NumberVariableHandle", "NumberVariableHandle — number variable from network:var", kind = Kind.TYPE),
        Suggestion("StringVariableHandle", "StringVariableHandle — string variable from network:var", kind = Kind.TYPE),
        Suggestion("BoolVariableHandle", "BoolVariableHandle — bool variable from network:var", kind = Kind.TYPE)
    )

    private fun suggestTypeAnnotation(partial: String): List<Suggestion> {
        // Don't suggest if the partial already exactly matches a known type
        if (knownTypes.any { it.insertText == partial }) return emptyList()
        customPrefix = partial
        return fuzzy(partial, knownTypes)
    }

    private fun suggestTag(partial: String): List<Suggestion> {
        customPrefix = partial
        // Union item + fluid tags, deduped — `#c:water` is valid for both kinds and users
        // shouldn't need to know which registry owns it.
        val union = (itemTags + fluidTags).distinct()
        return fuzzyStrings(partial, union, Kind.TAG).take(20)
    }

    private fun suggestMethodCall(
        ctx: CursorContext.MethodCall,
        symbols: Map<String, String>,
        fullText: String
    ): List<Suggestion> {
        val receiver = ctx.receiver
        val partial = ctx.partial

        // Built-in objects
        if (receiver == "network") return suggestNetworkMethods(partial, fullText)
        if (receiver == "scheduler") return suggestSchedulerMethods(partial)

        // Look up receiver type in symbol table
        val type = symbols[receiver]
        if (type != null) return suggestMethodsForType(type, partial)

        return emptyList()
    }

    private fun suggestPropertyAccess(
        ctx: CursorContext.PropertyAccess,
        symbols: Map<String, String>,
        fullText: String
    ): List<Suggestion> {
        val receiver = ctx.receiver
        val partial = ctx.partial

        // Built-in modules
        if (receiver == "string") return suggestStringMethods(partial)
        if (receiver == "math") return suggestMathMethods(partial)
        if (receiver == "table") return suggestTableMethods(partial)

        // Check if it's a required module
        val moduleExports = getModuleExports(fullText, receiver)
        if (moduleExports.isNotEmpty()) return fuzzy(partial, moduleExports)

        // Check type for property access (e.g., ItemsHandle.id)
        val type = symbols[receiver]
        if (type != null) return suggestPropertiesForType(type, partial)

        return emptyList()
    }

    private fun suggestWord(
        partial: String,
        fullText: String,
        beforeCursor: String,
        symbols: Map<String, String>,
        forced: Boolean
    ): List<Suggestion> {
        // Require at least one typed character before auto-triggering, matching VSCode.
        // `forced` (Ctrl+Space) still allows empty-prefix completion.
        if (partial.isEmpty() && !forced) return emptyList()

        val apiFunctions = listOf(
            suggest("scheduler", "scheduler — module", Kind.MODULE),
            suggest("network", "network — module", Kind.MODULE),
            suggest("print(", "print(message: any)", Kind.FUNCTION),
            suggest("error(", "error(message: string) — throw an error", Kind.FUNCTION),
            suggest("clock(", "clock() → number", Kind.FUNCTION),
            suggest("string", "string library", Kind.MODULE),
            suggest("math", "math library", Kind.MODULE),
            suggest("table", "table library", Kind.MODULE),
            suggest("tostring", "tostring(value: any) → string", Kind.FUNCTION),
            suggest("tonumber", "tonumber(value: any) → number?", Kind.FUNCTION),
            suggest("type", "type(value: any) → string", Kind.FUNCTION),
            suggest("pairs", "pairs(t: table) → function", Kind.FUNCTION),
            suggest("ipairs", "ipairs(t: table) → function", Kind.FUNCTION),
            suggest("select", "select(index: number, ...) → any", Kind.FUNCTION),
            suggest("unpack", "unpack(t: table) → ...", Kind.FUNCTION)
        )
        val keywords = listOf(
            "local", "function", "end",
            "if", "then", "else", "elseif", "for", "while", "do", "return",
            "true", "false", "nil", "not", "and", "or"
        ).map { suggest(it, kind = Kind.KEYWORD) }
        // Variables are scoped: only surface names declared BEFORE the cursor. Using
        // fullText would offer `myVar` in `myVa|\nlocal myVar = 0`, suggesting code that
        // isn't yet valid. For-loop bindings + function params follow the same rule
        // because [extractVariableNames] / [extractFunctionParams] both take the
        // `beforeCursor` slice.
        //
        // Functions stay global (scanned from fullText) because declaring a function later
        // in the file doesn't affect whether the IDE should suggest it — common Lua
        // convention puts helper definitions at the bottom, and blocking those from
        // autocomplete would be more annoying than useful.
        val userVars = (extractVariableNames(beforeCursor) + extractFunctionParams(beforeCursor)).distinct().map { name ->
            val type = symbols[name]
            if (type != null) suggest(name, "$name: $type", Kind.VARIABLE) else suggest(name, kind = Kind.VARIABLE)
        }
        val userFuncs = extractFunctions(fullText).map { f ->
            val retStr = if (f.returnType != null) " → ${f.returnType}" else ""
            suggest("${f.name}(", "${f.name}(${f.params})$retStr", Kind.FUNCTION)
        }
        val requireSuggest = if (scripts().size > 1) listOf(
            suggest(
                "require(",
                "require(module: string) → table",
                Kind.FUNCTION
            )
        ) else emptyList()
        val all = (apiFunctions + requireSuggest + keywords + userVars + userFuncs).distinctBy { it.insertText }
        val matches = FuzzyMatch.filter(partial, all).filter { it.insertText != partial }
        return matches
    }

    // ========== Network methods ==========

    private fun suggestNetworkMethods(partial: String, fullText: String): List<Suggestion> {
        // Check for handle snippet context: `network:handle("name", partial`
        // This is checked here because the cursor context sees `network:handle` as MethodCall
        // but we need to check if we're in the second argument position
        val currentLine = fullText.substringBeforeLast('\n', fullText).let {
            // Actually use the beforeCursor's current line
            fullText // we'll use a different approach
        }

        val methods = listOf(
            suggest("get(", "get(alias: string) → CardHandle", Kind.METHOD),
            suggest("getAll(", "getAll(type: string) → CardHandle[]", Kind.METHOD),
            suggest("find(", "find(filter: string) → ItemsHandle?", Kind.METHOD),
            suggest("findEach(", "findEach(filter: string) → ItemsHandle[]", Kind.METHOD),
            suggest("count(", "count(filter: string) → number", Kind.METHOD),
            suggest("insert(", "insert(items: ItemsHandle, count?: number) → boolean (atomic)", Kind.METHOD),
            suggest("tryInsert(", "tryInsert(items: ItemsHandle, count?: number) → number (best-effort)", Kind.METHOD),
            suggest("craft(", "craft(id: string, count?: number) → CraftBuilder", Kind.METHOD),
            suggest("shapeless(", "shapeless(item: string, count?: number, ...) → ItemsHandle?", Kind.METHOD),
            run {
                val body = "route(\"\", function(item: ItemsHandle)\n    return true\nend)"
                snippet("route(", "route(alias, fn(item) → boolean)", body, body.indexOf("\"\"") + 1)
            },
            suggest("var(", "var(name: string) → VariableHandle", Kind.METHOD),
            suggest("handle(", "handle(cardName: string, fn: function(job, ...))", Kind.METHOD),
            suggest("debug(", "debug() — print network topology", Kind.METHOD)
        )
        return fuzzy(partial, methods)
    }

    private fun suggestSchedulerMethods(partial: String): List<Suggestion> {
        val tickBody = "tick(function()\n    \nend)"
        val secondBody = "second(function()\n    \nend)"
        val delayBody = "delay(20, function()\n    \nend)"
        val methods = listOf(
            snippet("tick(", "tick(fn: function) → number", tickBody, tickBody.indexOf("\n    \n") + 5),
            snippet("second(", "second(fn: function) → number", secondBody, secondBody.indexOf("\n    \n") + 5),
            snippet(
                "delay(",
                "delay(ticks: number, fn: function) → number",
                delayBody,
                delayBody.indexOf("\n    \n") + 5
            ),
            suggest("cancel(", "cancel(id: number)", Kind.METHOD)
        )
        return fuzzy(partial, methods)
    }

    // ========== Type-based methods and properties ==========

    private fun suggestMethodsForType(type: String, partial: String): List<Suggestion> {
        val methods = when (type) {
            "CardHandle" -> listOf(
                suggest("find(", "find(filter: string) → ItemsHandle?", Kind.METHOD),
                suggest("findEach(", "findEach(filter: string) → ItemsHandle[]", Kind.METHOD),
                suggest("insert(", "insert(items: ItemsHandle, count?: number) → boolean (atomic)", Kind.METHOD),
                suggest(
                    "tryInsert(",
                    "tryInsert(items: ItemsHandle, count?: number) → number (best-effort)",
                    Kind.METHOD
                ),
                suggest("count(", "count(filter: string) → number", Kind.METHOD),
                suggest("face(", "face(side: string) → CardHandle", Kind.METHOD),
                suggest("slots(", "slots(...: number) → CardHandle", Kind.METHOD)
            )

            "RedstoneCard" -> {
                val onChangeBody = "onChange(function(strength: number)\n    \nend)"
                listOf(
                    suggest("powered(", "powered() → boolean", Kind.METHOD),
                    suggest("strength(", "strength() → number", Kind.METHOD),
                    suggest("set(", "set(boolean | number)", Kind.METHOD),
                    snippet(
                        "onChange(",
                        "onChange(fn(strength: number))",
                        onChangeBody,
                        onChangeBody.indexOf("\n    \n") + 5
                    ),
                    suggest("face(", "face(side: string) → RedstoneCard", Kind.METHOD)
                )
            }

            "ItemsHandle" -> listOf(
                suggest("hasTag(", "hasTag(tag: string) → boolean", Kind.METHOD),
                suggest("matches(", "matches(filter: string) → boolean", Kind.METHOD)
            )

            "Job" -> listOf(suggest("pull(", "pull(card: CardHandle, ...) — wait for outputs", Kind.METHOD))
            "CraftBuilder" -> {
                val connectBody = "connect(function(item: ItemsHandle)\n    \nend)"
                listOf(
                    snippet(
                        "connect(",
                        "connect(fn(item: ItemsHandle))",
                        connectBody,
                        connectBody.indexOf("\n    \n") + 5
                    ),
                    suggest("store(", "store() — send result to network storage", Kind.METHOD)
                )
            }

            "VariableHandle", "NumberVariableHandle", "StringVariableHandle", "BoolVariableHandle" ->
                variableHandleMethods(type)

            else -> emptyList()
        }
        return fuzzy(partial, methods)
    }

    private fun suggestPropertiesForType(type: String, partial: String): List<Suggestion> {
        val props = when (type) {
            "CardHandle" -> listOf(
                suggest("name", "name: string (card's alias)", Kind.PROPERTY)
            )

            "ItemsHandle" -> listOf(
                suggest("id", "id: string", Kind.PROPERTY),
                suggest("name", "name: string", Kind.PROPERTY),
                suggest("count", "count: number (items: units, fluids: mB)", Kind.PROPERTY),
                suggest("kind", "kind: \"item\" | \"fluid\"", Kind.PROPERTY),
                suggest("stackable", "stackable: boolean", Kind.PROPERTY),
                suggest("maxStackSize", "maxStackSize: number", Kind.PROPERTY),
                suggest("hasData", "hasData: boolean", Kind.PROPERTY)
            )

            "InputItems" -> {
                // Fields are dynamic — derived from the enclosing `network:handle(...)`
                // recipe. When the cursor is outside any handler body, offer no fields
                // (the handler's items table isn't meaningful in that scope).
                val api = enclosingHandlerApi ?: return emptyList()
                val paramNames = damien.nodeworks.card.ProcessingSet.buildHandlerParamNames(api.inputs)
                paramNames.mapIndexed { idx, name ->
                    val (itemId, count) = api.inputs[idx]
                    val shortId = itemId.substringAfter(':')
                    suggest(name, "$name: ItemsHandle ($shortId × $count)", Kind.PROPERTY)
                }
            }

            else -> emptyList()
        }
        return fuzzy(partial, props)
    }

    /**
     * Resolve chained property access like `items.copperIngot.<partial>`. Only meaningful
     * when the outer variable is typed `InputItems` — its fields are all ItemsHandle,
     * so the partial completes ItemsHandle properties. Other table-like types aren't
     * supported yet (would need their own field-type map).
     */
    private fun suggestChainedPropertyAccess(
        ctx: CursorContext.ChainedPropertyAccess,
        symbols: Map<String, String>
    ): List<Suggestion> {
        val fieldType = resolveChainedFieldType(ctx.outerVar, ctx.field, symbols) ?: return emptyList()
        return suggestPropertiesForType(fieldType, ctx.partial)
    }

    private fun suggestChainedMethodCall(
        ctx: CursorContext.ChainedMethodCall,
        symbols: Map<String, String>
    ): List<Suggestion> {
        val fieldType = resolveChainedFieldType(ctx.outerVar, ctx.field, symbols) ?: return emptyList()
        return suggestMethodsForType(fieldType, ctx.partial)
    }

    /**
     * Resolve the type of `<outerVar>.<field>` for chain access. Only InputItems is
     * currently supported — its fields are all ItemsHandle, pulled from the enclosing
     * handler's recipe. Validates the field exists so typos don't silently succeed.
     * Returns null if unresolvable (unknown type, missing field, no handler context).
     */
    private fun resolveChainedFieldType(
        outerVar: String,
        field: String,
        symbols: Map<String, String>
    ): String? {
        val outerType = symbols[outerVar] ?: return null
        return when (outerType) {
            "InputItems" -> {
                val api = enclosingHandlerApi ?: return null
                val paramNames = damien.nodeworks.card.ProcessingSet.buildHandlerParamNames(api.inputs)
                if (field !in paramNames) null else "ItemsHandle"
            }

            else -> null
        }
    }

    /**
     * Walk [beforeCursor] to find the innermost open `network:handle("<id>", function(...)`
     * whose body contains the cursor. Returns the matching [ProcessingApiInfo] from
     * [localApis] if a live recipe matches the id, else null.
     *
     * Implementation:
     * - Build a timeline of `function` opens and `end` closes over the whole beforeCursor.
     * - At each `function` open, look back ~200 chars for `network:handle\s*\(\s*"([^"]+)"\s*,\s*$`.
     *   If it matches, record the id on the new scope; otherwise push null.
     * - At each `end` close, pop.
     * - After processing, walk the stack from innermost outward for the first non-null id.
     *
     * Multi-line `network:handle("<id>",\n    function(...)` is handled because `\s` in
     * the regex matches newlines. `function` tokens inside string literals or block
     * comments are not filtered out — acceptable edge case for a best-effort heuristic.
     */
    private fun findEnclosingHandlerApi(
        beforeCursor: String
    ): damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo? {
        data class Event(val pos: Int, val isFuncOpen: Boolean)

        val events = mutableListOf<Event>()
        val funcPattern = Regex("""\bfunction\s*\w*\s*\(""")
        val endPattern = Regex("""\bend\b""")
        for (m in funcPattern.findAll(beforeCursor)) events.add(Event(m.range.first, true))
        for (m in endPattern.findAll(beforeCursor)) events.add(Event(m.range.first, false))
        events.sortBy { it.pos }

        val scopeStack = mutableListOf<String?>()
        val handleLookback = Regex("""network:handle\s*\(\s*"([^"]+)"\s*,\s*$""")
        for (event in events) {
            if (event.isFuncOpen) {
                // Lookback must comfortably fit a full canonical recipe id (which can run
                // hundreds of chars for recipes with many slots — 9 inputs + 3 outputs
                // with modded namespaces can hit ~600 chars). 4096 covers any realistic
                // recipe and keeps regex cost bounded.
                val lookbackStart = (event.pos - 4096).coerceAtLeast(0)
                val lookback = beforeCursor.substring(lookbackStart, event.pos)
                val match = handleLookback.find(lookback)
                scopeStack.add(match?.groupValues?.get(1))
            } else {
                if (scopeStack.isNotEmpty()) scopeStack.removeLast()
            }
        }

        val innermostId = scopeStack.asReversed().firstOrNull { it != null } ?: return null
        return localApis.firstOrNull { it.name == innermostId }
    }

    private val baseVariableMethods = listOf(
        "get(" to "get() → value",
        "set(" to "set(value) — set variable value",
        "cas(" to "cas(expected, new) → boolean",
        "type(" to "type() → string",
        "name(" to "name() → string"
    )

    private val numberMethods = listOf(
        "increment(" to "increment(n: number) → number",
        "decrement(" to "decrement(n: number) → number",
        "min(" to "min(n: number) → number",
        "max(" to "max(n: number) → number"
    )
    private val stringMethods = listOf(
        "append(" to "append(s: string) → string",
        "length(" to "length() → number",
        "clear(" to "clear()"
    )
    private val boolMethods = listOf(
        "toggle(" to "toggle() → boolean",
        "tryLock(" to "tryLock() → boolean",
        "unlock(" to "unlock()"
    )

    private fun variableHandleMethods(kind: String): List<Suggestion> {
        val extra = when (kind) {
            "NumberVariableHandle" -> numberMethods
            "StringVariableHandle" -> stringMethods
            "BoolVariableHandle" -> boolMethods
            else -> numberMethods + stringMethods + boolMethods // VariableHandle fallback: show all
        }
        return (baseVariableMethods + extra).map { suggest(it.first, it.second, Kind.METHOD) }
    }

    // ========== Library methods ==========

    private fun suggestStringMethods(partial: String): List<Suggestion> {
        val methods = listOf(
            suggest("format(", "format(fmt: string, ...) → string", Kind.METHOD),
            suggest("len(", "len(s: string) → number", Kind.METHOD),
            suggest("sub(", "sub(s: string, i: number, j?: number) → string", Kind.METHOD),
            suggest("find(", "find(s: string, pattern: string) → number?", Kind.METHOD),
            suggest("match(", "match(s: string, pattern: string) → string?", Kind.METHOD),
            suggest("gmatch(", "gmatch(s: string, pattern: string) → function", Kind.METHOD),
            suggest("gsub(", "gsub(s: string, pattern: string, repl: string) → string", Kind.METHOD),
            suggest("rep(", "rep(s: string, n: number) → string", Kind.METHOD),
            suggest("reverse(", "reverse(s: string) → string", Kind.METHOD),
            suggest("upper(", "upper(s: string) → string", Kind.METHOD),
            suggest("lower(", "lower(s: string) → string", Kind.METHOD),
            suggest("byte(", "byte(s: string, i?: number) → number", Kind.METHOD),
            suggest("char(", "char(...: number) → string", Kind.METHOD)
        )
        return fuzzy(partial, methods)
    }

    private fun suggestMathMethods(partial: String): List<Suggestion> {
        val methods = listOf(
            suggest("floor(", "floor(x: number) → number", Kind.METHOD),
            suggest("ceil(", "ceil(x: number) → number", Kind.METHOD),
            suggest("abs(", "abs(x: number) → number", Kind.METHOD),
            suggest("max(", "max(x: number, ...) → number", Kind.METHOD),
            suggest("min(", "min(x: number, ...) → number", Kind.METHOD),
            suggest("sqrt(", "sqrt(x: number) → number", Kind.METHOD),
            suggest("random(", "random(m?: number, n?: number) → number", Kind.METHOD),
            suggest("pi", "pi: number", Kind.PROPERTY),
            suggest("huge", "huge: number", Kind.PROPERTY),
            suggest("sin(", "sin(x: number) → number", Kind.METHOD),
            suggest("cos(", "cos(x: number) → number", Kind.METHOD),
            suggest("fmod(", "fmod(x: number, y: number) → number", Kind.METHOD)
        )
        return fuzzy(partial, methods)
    }

    private fun suggestTableMethods(partial: String): List<Suggestion> {
        val methods = listOf(
            suggest("insert(", "insert(t: table, value: any)", Kind.METHOD),
            suggest("remove(", "remove(t: table, pos?: number) → any", Kind.METHOD),
            suggest("sort(", "sort(t: table, comp?: function)", Kind.METHOD),
            suggest("concat(", "concat(t: table, sep?: string) → string", Kind.METHOD)
        )
        return fuzzy(partial, methods)
    }

    // ========== Handle snippet context ==========

    /**
     * Special check for `network:handle("cardName", partial` — needs to suggest function snippet.
     * Called from suggestNetworkMethods but also checked in MethodCall context.
     */
    private fun checkHandleSnippetContext(beforeCursor: String): List<Suggestion>? {
        val currentLine = beforeCursor.substringAfterLast('\n')
        val handleFnMatch = Regex("""network:handle\(\s*"([^"]+)"\s*,\s*(\w*)$""").find(currentLine) ?: return null
        val partial = handleFnMatch.groupValues[2]
        customPrefix = partial
        // Uniform handler signature — `job: Job, items: InputItems` regardless of recipe.
        // Per-slot field access happens via `items.<name>` inside the body, where the
        // editor resolves valid field names from the enclosing handle's recipe.
        val body = "function(job: Job, items: InputItems)\n    \nend"
        val cursorPos = body.indexOf("\n    \n") + 5
        return listOf(snippet("function(", "function(job: Job, items: InputItems)", body, cursorPos))
    }

    // ========== Utility functions ==========

    private fun extractVariableNames(text: String): List<String> {
        val names = mutableListOf<String>()
        // `local` declarations
        Regex("""\blocal\s+(\w+(?:\s*,\s*\w+)*)""").findAll(text).forEach { match ->
            for (part in match.groupValues[1].split(',')) names.add(part.trim())
        }
        // For-loop bindings: generic `for k [, v] in …` and numeric `for i = …, …`.
        // Leading `_` is ignored because `_` is the conventional throwaway binding and
        // suggesting it back to the user adds noise.
        Regex("""\bfor\s+(\w+(?:\s*,\s*\w+)?)\s+in\b""").findAll(text).forEach { match ->
            for (part in match.groupValues[1].split(',')) {
                val name = part.trim()
                if (name.isNotEmpty() && name != "_") names.add(name)
            }
        }
        Regex("""\bfor\s+(\w+)\s*=""").findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name != "_") names.add(name)
        }
        return names.distinct()
    }

    /** Extract function parameters that are in scope at the cursor position. */
    private fun extractFunctionParams(beforeCursor: String): List<String> {
        // Find which function bodies the cursor is inside by tracking function/end nesting
        // We scan beforeCursor and track open function scopes
        val scopeStack = mutableListOf<List<String>>() // stack of param lists
        val funcPattern = Regex("""\bfunction\s*\w*\s*\(([^)]*)\)""")
        val endPattern = Regex("""\bend\b""")

        // Simple approach: scan line by line, track function opens and end closes
        for (line in beforeCursor.lines()) {
            val trimLine = line.trim()
            // Check for function definition (could be multiple per line but rare)
            for (match in funcPattern.findAll(trimLine)) {
                val params = mutableListOf<String>()
                for (param in match.groupValues[1].split(",")) {
                    val name = param.trim().split(":")[0].trim().split("\\s+".toRegex())[0]
                    if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) {
                        params.add(name)
                    }
                }
                scopeStack.add(params)
            }
            // Check for `end` — closes the most recent scope
            for (match in endPattern.findAll(trimLine)) {
                if (scopeStack.isNotEmpty()) scopeStack.removeLast()
            }
        }

        // All params from currently open scopes are in scope
        return scopeStack.flatten().distinct()
    }

    data class FunctionInfo(val name: String, val params: String, val returnType: String?)

    private fun extractFunctions(text: String): List<FunctionInfo> {
        val result = mutableListOf<FunctionInfo>()
        // Match: function name(params): ReturnType  or  local function name(params): ReturnType.
        // Return type can be scalar (`T`, `T?`) or container (`{ T }`, `{ [K]: V }`).
        val pattern = Regex("""\bfunction\s+(\w+)\s*\(([^)]*)\)\s*(?::\s*(\w+\??|\{[^}]*}))?""")
        for (match in pattern.findAll(text)) {
            val name = match.groupValues[1]
            val rawParams = match.groupValues[2].trim()
            val returnType = match.groupValues[3].ifEmpty { null }
            // Keep type annotations in display: "from: CardHandle" stays as-is
            val displayParams = rawParams.split(",").joinToString(", ") { it.trim() }
            result.add(FunctionInfo(name, displayParams, returnType))
        }
        return result.distinctBy { it.name }
    }

    /** Extract functions and fields defined on a table variable in the given text. */
    private fun extractTableMembers(text: String, tableVar: String): List<Suggestion> {
        val exports = mutableListOf<Suggestion>()
        // function tableVar.method(params): ReturnType. Return type can be scalar or
        // brace-delimited container (`{ T }`, `{ [K]: V }`), mirroring [extractFunctions].
        // Without the brace alternation, a module exporting
        // `function a.getAllThings(...): { ItemsHandle }` would lose its return annotation
        // in the autocomplete display even though the hover tooltip (via a separate path)
        // renders it correctly.
        val funcPattern =
            Regex("""\bfunction\s+${Regex.escape(tableVar)}\.(\w+)\s*\(([^)]*)\)\s*(?::\s*(\w+\??|\{[^}]*}))?""")
        funcPattern.findAll(text).forEach { m ->
            val funcName = m.groupValues[1]
            val params = m.groupValues[2].trim().split(",").joinToString(", ") { it.trim() }
            val retType = m.groupValues[3].ifEmpty { null }
            val retStr = if (retType != null) " → $retType" else ""
            exports.add(suggest("$funcName(", "$funcName($params)$retStr", Kind.FUNCTION))
        }
        // tableVar.field = value
        val fieldPattern = Regex("""${Regex.escape(tableVar)}\.(\w+)\s*=""")
        fieldPattern.findAll(text).forEach { m ->
            val fieldName = m.groupValues[1]
            if (exports.none { it.insertText.startsWith("$fieldName(") }) {
                exports.add(suggest(fieldName, kind = Kind.PROPERTY))
            }
        }
        return exports.distinctBy { it.insertText }
    }

    private fun getModuleExports(currentText: String, varName: String): List<Suggestion> {
        // Check for require'd module: local varName = require("module")
        val requirePattern = Regex("""\blocal\s+${Regex.escape(varName)}\s*=\s*require\(\s*"(\w+)"\s*\)""")
        val requireMatch = requirePattern.find(currentText)
        if (requireMatch != null) {
            val moduleName = requireMatch.groupValues[1]
            val moduleText = scripts()[moduleName] ?: return emptyList()
            // In required modules, scan all table vars for exports
            val tableVarPattern = Regex("""\blocal\s+(\w+)\s*=\s*\{\s*\}""")
            val tableVars = tableVarPattern.findAll(moduleText).map { it.groupValues[1] }.toSet()
            val results = mutableListOf<Suggestion>()
            for (tv in tableVars) {
                results.addAll(extractTableMembers(moduleText, tv))
            }
            return results.distinctBy { it.insertText }
        }

        // Check for local table: local varName = {}
        val localTablePattern = Regex("""\blocal\s+${Regex.escape(varName)}\s*=\s*\{""")
        if (localTablePattern.containsMatchIn(currentText)) {
            return extractTableMembers(currentText, varName)
        }

        return emptyList()
    }

    /**
     * Build the full-block snippet inserted when the player accepts a recipe at
     * `network:handle("...`. Inserts the canonical id + closing quote + comma +
     * function signature + empty body + `end)`. Cursor lands on the body line.
     *
     * Indentation is a static 4-space / 8-space scheme regardless of the caller's
     * current line indent — good enough for most scripts; the player can tab-shift
     * the block if their indent convention differs.
     */
    private fun buildHandleFullSnippet(
        api: damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo
    ): Suggestion {
        val canonicalId = api.name
        // Uniform 2-arg signature. The editor folds the canonical id to "..." when the
        // cursor isn't inside the string, so keeping `function(...)` on the same line as
        // `network:handle(` produces a clean one-liner header:
        //
        //     network:handle("...", function(job: Job, items: InputItems)
        //         <cursor>
        //     end)
        val beforeCursor = "$canonicalId\", function(job: Job, items: InputItems)\n    "
        val afterCursor = "\nend)"
        val fullSnippet = beforeCursor + afterCursor
        return Suggestion(
            insertText = canonicalId,
            displayText = canonicalId,
            snippetText = fullSnippet,
            snippetCursor = beforeCursor.length,
            consumesAutoclose = true
        )
    }

    private fun extractPrefix(beforeCursor: String): String {
        val lastNonWord = beforeCursor.indexOfLast { !it.isLetterOrDigit() && it != '_' }
        return if (lastNonWord >= 0) beforeCursor.substring(lastNonWord + 1) else beforeCursor
    }
}
