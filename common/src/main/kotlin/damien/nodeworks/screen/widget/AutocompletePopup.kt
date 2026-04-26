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
    /** Effective aliases of every Breaker on the network (auto-alias `breaker_N` or
     *  GUI-set name). Used to narrow `network:get("name")` to BreakerHandle. */
    private val breakerAliases: List<String> = emptyList(),
    /** Effective aliases of every Placer on the network. */
    private val placerAliases: List<String> = emptyList(),
    private val scripts: () -> Map<String, String> = { emptyMap() }
) {
    /**
     * VSCode-style category tag for rendering a colored badge next to the suggestion.
     * Each kind carries its own badge letter + background color. Choose based on what
     * the suggestion *is*, not what it inserts, e.g. `network` is a MODULE even though
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
        val kind: Kind = Kind.VARIABLE,
        /** Optional `local <name> = network:get("...")` line that
         *  should be prepended to the script when this suggestion is accepted. Set
         *  on auto-import suggestions for cards / variables the player hasn't yet
         *  bound to a local. The terminal's accept handler is responsible for
         *  inserting it (with a duplicate guard) before applying [insertText]. */
        val autoImport: String? = null
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
     *  after constructing the editor, without this the popup falls back to uniform line
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
        // pop the menu, the user is editing an existing identifier, not completing one.
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
        // accounting for the editor's internal textTop padding), fallback path keeps the
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
        val consumeAfter: Int = 0,
        /** Optional `local NAME = network:get("...")` line for auto-import. Forwarded
         *  from [Suggestion.autoImport]. The terminal's accept handler prepends this
         *  to the script (idempotent, duplicate import lines are skipped). */
        val autoImportLine: String? = null
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
            return AcceptResult(deleteCount, suggestion.snippetText, cursorPos, consume, autoImportLine = suggestion.autoImport)
        }
        // Auto-close parentheses: `func(` → `func()` with cursor between
        val text = suggestion.insertText
        if (text.endsWith("(")) {
            val closed = text + ")"
            return AcceptResult(deleteCount, closed, text.length, autoImportLine = suggestion.autoImport)
        }
        return AcceptResult(deleteCount, text, text.length, autoImportLine = suggestion.autoImport)
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

        // Keep the popup within the game window, same flip-and-clamp policy the hover
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
            // visible letter is (letterW - 1) pixels wide, subtract that to get a
            // symmetric X offset. Vertically, capital letters render at rows 1..7 of the
            // 9px line box, so the visible glyph height is 7, pad 1px top + 1px bottom.
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
        /** `var:partial`, method call on a variable */
        data class MethodCall(val receiver: String, val partial: String) : CursorContext

        /** `var.partial`, property access on a variable */
        data class PropertyAccess(val receiver: String, val partial: String) : CursorContext

        /** Inside a string argument: `func("partial`. [argIndex] is the 0-based position of
         *  this string among the call's comma-separated arguments so position-sensitive
         *  suggestions can distinguish first-arg from later-arg (e.g. importer:from's
         *  first arg is a filter, subsequent args are card names). */
        /** [precedingText] is the line content from the start of the line up to the
         *  opening quote of the string literal, used by the typed-assignment dispatch
         *  to detect `local x: T = "..."` patterns when there's no enclosing function. */
        data class StringArg(
            val funcExpr: String,
            val partial: String,
            val argIndex: Int = 0,
            val precedingText: String = "",
        ) : CursorContext

        /** Type annotation context: `local x: partial` or `function(a: partial` */
        data class TypeAnnotation(val partial: String) : CursorContext

        /** `#partial`, item tag filter */
        data class TagFilter(val partial: String) : CursorContext

        /** Method call where the receiver type has been resolved from a chain. [chainExpr]
         *  is the source text of the chain that produced [resolvedType] (e.g.
         *  `"stocker:ensure(\"minecraft:iron_ingot\")"`), suggestion filters use it to
         *  hide methods that don't apply to specific factory paths, `:ensure` / `:craft`
         *  pre-set the filter, so `:filter(...)` shouldn't be offered after them. */
        data class ResolvedMethodCall(
            val resolvedType: String,
            val partial: String,
            val chainExpr: String? = null,
        ) : CursorContext

        /** Property access where the receiver type has been resolved from a chain */
        data class ResolvedPropertyAccess(val resolvedType: String, val partial: String) : CursorContext

        /** `<outerVar>.<field>.<partial>`, chain through a table-like typed variable.
         *  Symbols are resolved in [computeSuggestions], for InputItems the field is
         *  an ItemsHandle, so the partial completes ItemsHandle properties. */
        data class ChainedPropertyAccess(val outerVar: String, val field: String, val partial: String) : CursorContext

        /** `<outerVar>.<field>:<partial>`, chained method call. Same resolution rule
         *  as [ChainedPropertyAccess] but produces method suggestions. */
        data class ChainedMethodCall(val outerVar: String, val field: String, val partial: String) : CursorContext

        /** `xs[idx].<partial>`, property access on the element type of an indexed container.
         *  Receiver type comes from the symbol table (serialized `{ T }` / `{ [K]: V }`).
         *  The index expression itself is ignored, we only need the receiver var. */
        data class IndexedPropertyAccess(val receiver: String, val partial: String) : CursorContext

        /** `xs[idx]:<partial>`, method call on the element type of an indexed container. */
        data class IndexedMethodCall(val receiver: String, val partial: String) : CursorContext

        /** Resolved exports from a module (require or local table) */
        data class ResolvedExports(val exports: List<Suggestion>, val partial: String) : CursorContext

        /** Plain word at cursor, global completions */
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
        // detected by a span-count scan over [beforeCursor], count openings vs closings,
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

        // Don't autocomplete after `local `, user is naming a new variable
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
        // Find the last unmatched ", we're inside a string if quote count is odd
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
            // Count top-level commas between the `(` and the opening quote, that's how
            // many arguments came before this one. Skips commas inside nested parens and
            // string literals so `f("a,b", "c|")` correctly reads as argIndex=1.
            val argList = line.substring(parenIdx + 1, lastQuoteIdx)
            val argIndex = countTopLevelCommas(argList)

            val funcExpr = beforeQuote.substring(0, parenIdx).trimEnd()
            // Extract the function name/expression (e.g., "network:get", "network:craft",
            // ":face", "test.Filter"). Includes `.` so module-qualified calls reach the
            // module-aware dispatch path with their full receiver-and-name.
            val funcMatch = Regex("""([\w.:]+)\s*$""").find(funcExpr)
            if (funcMatch != null) {
                return CursorContext.StringArg(funcMatch.groupValues[1], partial, argIndex, beforeQuote)
            }
        }

        return CursorContext.StringArg("", partial, precedingText = beforeQuote)
    }

    /** Count commas at bracket/brace/string depth 0. Used to figure out which argument
     *  position the cursor sits in for context-aware string-arg suggestions. */
    private fun countTopLevelCommas(s: String): Int {
        var depth = 0
        var inString = false
        var count = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val escaped = i > 0 && s[i - 1] == '\\'
            when {
                !escaped && c == '"' -> inString = !inString
                !inString -> when (c) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            i++
        }
        return count
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

    /** True when the cursor sits inside a Lua comment, either a `--` line comment on
     *  the current line, or anywhere inside an unclosed `--[[ … ]]` block comment in the
     *  entire script-prefix up to the cursor.
     *
     *  For the line-comment case, we walk the line tracking string delimiters so a `--`
     *  inside `"foo--bar"` doesn't falsely mark the cursor as commented out. For the
     *  block-comment case we simply match `--[[` / `]]` pairs across [beforeCursor],
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
     * Split a function-parameter list on top-level commas only, commas nested inside a
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
     * handle the map form because the brace carries its own `:` internally, we split on
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
     *  completes the element type, for both arrays and maps, the element type is what
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
        var trimBefore = beforeColon.trimEnd()

        // Multi-line chain: the current line is just whitespace + `:`, so the
        // expression that `partial` is being called on lives on prior lines.
        // Walk backward collecting every continuation line (one that starts with
        // `:` after leading whitespace) up through the first line that doesn't
        // continue a chain, that's the chain's root receiver. Concatenate the
        // lot into a single logical expression so [resolveExpressionType] and
        // [extractReceiverType] can resolve it the same way as a single-line
        // chain, including receiver-aware method-return lookup. Covers:
        //
        //   importer                   -> `importer:<partial>`
        //       :<partial>
        //
        //   stocker:from(network)       -> `stocker:from(network):to("foo"):<partial>`
        //       :to("foo")
        //       :<partial>
        //
        //   stocker
        //       :from(network)          -> `stocker:from(network):to(x):<partial>`
        //       :to(x)
        //       :<partial>
        if (trimBefore.isEmpty()) {
            val priorText = beforeCursor.substring(0, beforeCursor.length - line.length).trimEnd()
            if (priorText.isEmpty()) return null
            trimBefore = collectChainExpression(priorText) ?: return null
        }

        if (trimBefore.isEmpty()) return null

        // CraftBuilder chain on next line: `network:craft(...)\n  :partial`
        val craftChainMultiline = Regex("""network:craft\([^)]*\)\s*\n\s*:(\w*)$""").find(beforeCursor.trimEnd())
        if (craftChainMultiline != null) {
            return CursorContext.ResolvedMethodCall("CraftBuilder", partial)
        }

        // If `)` before `:`, resolve the full chain type (exclude non-chainable methods)
        if (trimBefore.endsWith(")")) {
            val chainType = resolveExpressionType(trimBefore, forChaining = true)
            if (chainType != null) {
                return CursorContext.ResolvedMethodCall(chainType, partial, chainExpr = trimBefore)
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
        // For InputItems, `field` is an ItemsHandle, resolve to ItemsHandle method list.
        // The outerVar's type is in the symbol table, but buildSymbolTable runs in
        // computeSuggestions, we detect the shape here and defer resolution via a
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

    /** Strip a trailing `[…]` from [expr] and resolve the resulting prefix to its
     *  element type. Used by both the symbol-table inference for
     *  `local x = chain[N]` and the property-access inference for `chain[N].field`.
     *  Returns null when the prefix doesn't resolve to a container. */
    private fun resolveIndexedElementType(expr: String, symbols: Map<String, String>): String? {
        val prefix = stripIndexBrackets(expr)?.trimEnd() ?: return null
        val rt = if (prefix.endsWith(")")) {
            resolveExpressionReturnType(prefix)
        } else {
            val containerType = symbols[prefix] ?: return null
            LuaApiDocs.parseReturnType("() → $containerType")
        }
        return if (rt != null && rt.container != LuaApiDocs.Container.NONE) rt.type else null
    }

    /** Look up the declared type of a property on a registered TYPE. Tries the
     *  migrated registry first (returnType.display gives canonical form), falls
     *  back to parsing the legacy `Type.field` entry's signature for unmigrated
     *  surfaces. Returns null when the property isn't documented anywhere, the
     *  caller leaves the symbol untyped in that case. */
    private fun lookupPropertyType(typeName: String, fieldName: String): String? {
        val registryProp = damien.nodeworks.script.api.LuaApiRegistry
            .propertiesOf(typeName)
            .firstOrNull { it.displayName == fieldName }
        if (registryProp != null) return registryProp.returnType.display

        val legacySig = LuaApiDocs.get("$typeName.$fieldName")?.signature ?: return null
        val colonIdx = legacySig.indexOf(':')
        if (colonIdx < 0) return null
        return legacySig.substring(colonIdx + 1).trim()
    }

    /**
     * Unwrap a container type string (as stored in the symbol table: `{ T }` for arrays,
     * `{ [K]: V }` for maps) into the element type T / V. Returns null when [type] is
     * null or isn't in container form, callers use that to skip indexed-access
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
        val receiverType = extractReceiverType(beforeParen, methodName)

        // Network:getAll("type") and Channel:getAll("type") narrowing, both return a
        // `HandleList<T>`. We expose the iteration of `:list()` separately, so for
        // the for-loop / indexed-access path here we surface the *element* type with
        // ARRAY container kind. That keeps `for _, c in handleList:list() do c:set(true)`
        // resolving c as RedstoneCard. The wrapper-aware HandleList<T> string is what
        // [resolveExpressionType] returns for the scalar local-binding case.
        if ((receiverType == "Channel" || receiverType == "Network") && methodName == "getAll") {
            val arg = paren.second.trim().trim('"', '\'')
            channelElementType(arg)?.let {
                return LuaApiDocs.ReturnType(it, LuaApiDocs.Container.ARRAY)
            }
        }
        if (receiverType == "Channel" && methodName == "getFirst") {
            val arg = paren.second.trim().trim('"', '\'')
            channelElementType(arg)?.let {
                return LuaApiDocs.ReturnType(it, LuaApiDocs.Container.NONE)
            }
        }
        // HandleList:list(), unwrap the parameterised type and report the element
        // type so `for _, c in handleList:list() do` knows what `c` is.
        if (receiverType?.startsWith("HandleList<") == true && methodName == "list") {
            handleListElement(receiverType)?.let {
                return LuaApiDocs.ReturnType(it, LuaApiDocs.Container.ARRAY)
            }
        }

        return LuaApiDocs.methodReturnType(methodName, receiverType)
            ?: userFunctionReturnType(methodName, cachedFullText)
    }

    /** Walk backward through [priorText] collecting every continuation line
     *  (one whose first non-whitespace character is `:`) up through the first
     *  line that doesn't continue a chain. The first non-continuation line is
     *  the chain's root receiver. Result is the lines joined into a single
     *  logical expression with no newlines between them so the single-line
     *  resolution path in [findColonContext] and [resolveExpressionType] can
     *  operate on it unmodified. Returns null when [priorText] has no
     *  continuation lines or the root line itself is empty. */
    private fun collectChainExpression(priorText: String): String? {
        val lines = priorText.split('\n')
        // Iterate backward looking for continuation lines. When we hit a line
        // that doesn't start with `:` after stripping leading whitespace, that's
        // the root receiver and we stop. If the immediately prior line isn't a
        // continuation AND doesn't read as a bare expression, bail, we don't
        // want to stitch unrelated code together.
        val collected = ArrayDeque<String>()
        for (i in lines.indices.reversed()) {
            val stripped = lines[i].trimStart()
            if (stripped.isEmpty()) continue
            collected.addFirst(stripped)
            if (!stripped.startsWith(":")) break
        }
        if (collected.isEmpty()) return null
        // Join with no separator, `partial`'s leading `:` is still on the
        // current line and isn't part of what we're resolving.
        return collected.joinToString("")
    }

    /** Extract the receiver's type name from an expression like `importer:from` or
     *  `importer:from(...):to`. Used to qualify [LuaApiDocs.methodReturnType] so sibling
     *  types with matching short method names resolve to the right return type.
     *
     *  Supports three shapes:
     *    * Bare module identifier: `importer:from` → "Importer" (via `moduleTypeFor`)
     *    * Chained call: `importer:from(...):to` → "ImporterBuilder" (via recursive chain resolve)
     *    * Symbol-table variable: `myImp:to` → whatever `myImp` was inferred as
     *
     *  Returns null if none of those resolve (expression uses something the resolver
     *  doesn't track yet). The caller falls back to bare-name lookup, so a null here
     *  just means "no disambiguation available."
     */
    private fun extractReceiverType(beforeParen: String, methodName: String): String? {
        val colonIdx = beforeParen.lastIndexOf(':')
        if (colonIdx <= 0) return null
        val receiverExpr = beforeParen.substring(0, colonIdx).trimEnd()

        // Chain: `importer:from(...):to`, recursively resolve the call's return type.
        if (receiverExpr.endsWith(")")) {
            return resolveExpressionType(receiverExpr, forChaining = true)
        }

        // Bare identifier: either a module global (importer, stocker, network, scheduler)
        // or a user variable we can identify.
        val bareMatch = Regex("""(\w+)$""").matchEntire(receiverExpr)
        if (bareMatch != null) {
            val name = bareMatch.groupValues[1]
            LuaApiDocs.moduleTypeFor(name)?.let { return it }
            // Channel-typed local: lets `ch:first("observer")` route through the
            // arg-aware narrowing in [resolveExpressionType] without needing the
            // full per-variable [symbols] map threaded through every chain call.
            // Future similar shapes (e.g. ImporterBuilder vars) will need their own
            // membership sets here.
            if (name in channelLocals) return "Channel"
            // HandleList<T> local, return the parameterised wrapper so chained
            // `:list()` can unwrap it via [handleListElement].
            handleListLocals[name]?.let { return it }
            // Other user-variable types (CardHandle locals, etc.) are still
            // resolved at higher levels where the symbol table is available.
        }
        return null
    }

    /** Scalar return type lookup driving both chain resolution and `local x = fn(...)`
     *  inference. Delegates to [LuaApiDocs.methodReturnType] so the doc signatures are
     *  the single source of truth, no parallel hardcoded map to drift. Container-typed
     *  returns (`{ Type… }`, `{ [K]: V }`) return null here because chaining off an array
     *  value makes no sense, the for-loop inference path uses
     *  [LuaApiDocs.methodReturnType] directly to pull element types. */
    private fun scalarReturnTypeOf(methodName: String, receiverType: String? = null): String? {
        val rt = LuaApiDocs.methodReturnType(methodName, receiverType) ?: return null
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

        // Built-in scalars come from LuaApiDocs, user-defined functions override via
        // [allReturnTypes]. Container-typed built-ins (arrays/maps) intentionally fall
        // through to null here, you can't chain `.foo` off the array value itself.
        allReturnTypes[methodName]?.let { return it }
        val receiverType = extractReceiverType(beforeParen, methodName)

        // Channel:getFirst("type") narrows the scalar return on the literal arg so
        // `local x = ch:getFirst("observer")` resolves x as ObserverCard rather
        // than the static `Channel:getFirst → CardHandle | nil`. Receiver-typed
        // (`Channel`) so unrelated `:getFirst` calls on other types pass through.
        if (receiverType == "Channel" && methodName == "getFirst") {
            val arg = parenResult.second.trim().trim('"', '\'')
            channelElementType(arg)?.let { return it }
        }

        // Network:getAll("type") and Channel:getAll("type") return a HandleList<T>.
        // Encode the parameterised type as a literal string `"HandleList<T>"` so the
        // method-list dispatch (which keys off the symbol's type string) can match
        // both the wrapper and the element. `containerFromIterExpr` decides separately
        // whether iteration unwraps the wrapper, so storing the wrapper here doesn't
        // break `for _, c in cards do …` loops over the underlying members.
        if ((receiverType == "Network" || receiverType == "Channel") && methodName == "getAll") {
            val arg = parenResult.second.trim().trim('"', '\'')
            channelElementType(arg)?.let { return "HandleList<$it>" }
        }

        return scalarReturnTypeOf(methodName, receiverType)
    }

    /** Map a `Channel:getFirst("type")` / `:getAll("type")` literal argument to the
     *  typed card class. Centralised so the chain resolver, the for-loop element
     *  resolver, and any future caller all agree on the same dispatch table. */
    private fun channelElementType(typeArg: String): String? = when (typeArg) {
        "redstone" -> "RedstoneCard"
        "observer" -> "ObserverCard"
        "variable" -> "VariableHandle"
        "breaker" -> "BreakerHandle"
        "placer" -> "PlacerHandle"
        "io", "storage" -> "CardHandle"
        else -> null
    }

    /** Pull the element type out of a `HandleList<T>` symbol. Returns null when
     *  the input isn't a parameterised handle-list. */
    private fun handleListElement(type: String?): String? {
        if (type == null) return null
        val match = Regex("""^HandleList<(\w+)>$""").matchEntire(type) ?: return null
        return match.groupValues[1]
    }

    /** User-defined function return types (scalar only). Rebuilt once per computeSuggestions call. */
    private var allReturnTypes: Map<String, String> = emptyMap()

    /** Names of locals bound to a Channel via `local x = network:channel(...)` in the
     *  current script, refreshed each [computeSuggestions] / [buildSymbolTable] call.
     *  Lets [resolveExpressionType] / [resolveExpressionReturnType] recognise that the
     *  bare receiver in `ch:first("observer")` is a Channel without needing the
     *  full per-variable symbol table threaded through every chain-resolver call. */
    private var channelLocals: Set<String> = emptySet()

    /** Names of locals bound to a HandleList<T> via `local x = network:getAll(...)`
     *  or `local x = ch:getAll(...)`, mapped to the parameterised type string
     *  (e.g. `"HandleList<RedstoneCard>"`). Lets [extractReceiverType] surface the
     *  wrapper type for `pistons:list()` chains where `pistons` is a bare ident. */
    private var handleListLocals: Map<String, String> = emptyMap()

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
     *  in the current script + every module script. Scalar-only, container annotations
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
        // Return type can be a bare identifier or a brace-delimited container, capture
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

        // If `)` before `.`, check for require("module"). or resolve chain type
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

        // Indexed receiver `<receiver>[index].partial`, property counterpart of the `:`
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
        // already built, we only parse the shape here.
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
        // container (`{ CardHandle }`, `{ [string]: V }`). The alternation covers both,
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

        // Pre-pass: collect every local bound to `network:channel(...)`. The chain
        // resolvers below ([resolveExpressionType] / [resolveExpressionReturnType])
        // need this set to recognise `ch:first("observer")` as a Channel call when
        // `ch` is a bare ident (the receiver-type extractor can't see [symbols]).
        // Cheap to scan once up front rather than re-deriving inside every resolver
        // invocation.
        channelLocals = Regex("""\blocal\s+(\w+)\s*=\s*network:channel\s*\(""")
            .findAll(fullText)
            .map { it.groupValues[1] }
            .toSet()

        // Pre-pass: collect HandleList<T> locals so `pistons:list()` resolves the
        // receiver as the parameterised wrapper. Two shapes get matched:
        //   * `local x = network:getAll("type")`
        //   * `local x = <channelVar>:getAll("type")`  (and the chained inline form)
        // Anything that doesn't pattern-match here just means autocomplete won't
        // unwrap that particular HandleList, the runtime call still works.
        run {
            val pairs = mutableMapOf<String, String>()
            val pattern = Regex(
                """\blocal\s+(\w+)\s*=\s*(?:[\w:.()"]+:)?getAll\s*\(\s*"(\w+)"\s*\)"""
            )
            for (m in pattern.findAll(fullText)) {
                val varName = m.groupValues[1]
                val element = when (m.groupValues[2]) {
                    "redstone" -> "RedstoneCard"
                    "observer" -> "ObserverCard"
                    "variable" -> "VariableHandle"
                    "io", "storage" -> "CardHandle"
                    else -> null
                } ?: continue
                pairs[varName] = "HandleList<$element>"
            }
            handleListLocals = pairs
        }

        // 1a. Explicit scalar type annotations: local x: Type = ...
        Regex("""\blocal\s+(\w+)\s*:\s*(\w+)\??\s*(?:=|\n|$)""").findAll(fullText).forEach {
            symbols[it.groupValues[1]] = it.groupValues[2]
        }

        // 1b. Explicit container type annotations: local xs: { T } = ... or local xs: { [K]: V } = ...
        // Uses [LuaApiDocs.parseReturnType]'s brace grammar so user annotations and doc
        // signatures share one parser, keeps the two surfaces from drifting.
        Regex("""\blocal\s+(\w+)\s*:\s*(\{[^}]*})""").findAll(fullText).forEach { match ->
            val varName = match.groupValues[1]
            val annotation = match.groupValues[2]
            val rt = LuaApiDocs.parseReturnType("() → $annotation")
            if (rt != null && rt.container != LuaApiDocs.Container.NONE) {
                containerVars[varName] = rt.type to rt.container
            }
        }

        // 2. Function parameter annotations, only from scopes the cursor is inside.
        // Allow container types like `from: { CardHandle }` in the capture by matching
        // the content between `(` and `)` greedily over non-`)` chars, the post-split
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
        // Special case: network:get("name"), variables now resolve through this same
        // method, so we check both the card list and the variable list. Cards win
        // on a name collision (matching the runtime behaviour in
        // [damien.nodeworks.script.ScriptEngine], `findByAlias` is consulted before
        // `findVariable`). Falls through to plain `CardHandle` only when neither
        // surface knows the name, which is also what the user sees as a runtime
        // "Not found" error.
        Regex("""\blocal\s+(\w+)\s*=\s*network:get\s*\(\s*"([\w]+)"\s*\)""").findAll(fullText).forEach {
            val varName = it.groupValues[1]
            val alias = it.groupValues[2]
            if (varName !in symbols) {
                val card = cards.firstOrNull { c -> c.effectiveAlias == alias }
                if (card != null) {
                    symbols[varName] = when (card.capability.type) {
                        "redstone" -> "RedstoneCard"
                        "observer" -> "ObserverCard"
                        else -> "CardHandle"
                    }
                    return@forEach
                }
                val typeOrd = variables.firstOrNull { v -> v.first == alias }?.second
                if (typeOrd != null) {
                    symbols[varName] = when (typeOrd) {
                        0 -> "NumberVariableHandle"
                        1 -> "StringVariableHandle"
                        2 -> "BoolVariableHandle"
                        else -> "VariableHandle"
                    }
                    return@forEach
                }
                if (alias in breakerAliases) {
                    symbols[varName] = "BreakerHandle"
                    return@forEach
                }
                if (alias in placerAliases) {
                    symbols[varName] = "PlacerHandle"
                    return@forEach
                }
                // Nothing matched, fall back to CardHandle so chained methods at
                // least resolve against the most general card surface.
                symbols[varName] = "CardHandle"
            }
        }
        // (network:getAll("type") inference dropped, the arg-aware chain resolver
        // in [resolveExpressionType] now returns `HandleList<T>`, and the general
        // `local x = expr` inference pass below routes through it. Keeping the old
        // `{ T }` regex pass here would shadow the HandleList typing because
        // `putIfAbsent` runs before the general inference fills in the wrapper.)

        // (Chained `network:channel("color"):getFirst("type")` and
        // `:getAll("type")` narrowing now flows through the arg-aware
        // [resolveExpressionType] / [resolveExpressionReturnType] in the general
        // inference pass below, so no dedicated regex pre-passes are needed.)

        // Special case: network:channel("color"), narrow the local to `Channel` so
        // chained `:getFirst("observer")` / `:getAll(...)` resolve against the channel's
        // method list rather than CardHandle's. Negative lookahead `(?!\s*:)` keeps
        // the bare-channel form from competing with the chained-first / chained-all
        // patterns above when both could match the same source line.
        Regex("""\blocal\s+(\w+)\s*=\s*network:channel\s*\(\s*"(\w+)"\s*\)(?!\s*:)""")
            .findAll(fullText).forEach {
                val varName = it.groupValues[1]
                symbols.putIfAbsent(varName, "Channel")
            }

        // (Channel split-line access, `local x = ch:first("observer")`, is now
        // handled uniformly by the arg-aware narrowing in [resolveExpressionType] and
        // [resolveExpressionReturnType], which the general-inference pass below routes
        // through. No dedicated regex pass needed here.)

        // (network:var inference dropped, variables resolve through the unified
        // `network:get(name)` path above which checks the variable list when no
        // card alias matches.)

        // Build function return type map for user-defined functions
        val funcReturnTypes = mutableMapOf<String, String>()
        extractFunctions(fullText).forEach { f ->
            if (f.returnType != null) funcReturnTypes[f.name] = f.returnType
        }

        // For-loop element inference. Runs twice (before and after the general
        // `local x = expr` pass below) so dependencies in either direction
        // converge:
        //   `for _, val in fn() do local a = val.id end`  needs the for-loop
        //     resolved BEFORE the inner local pass to type `a`
        //   `local xs = fn(); for _, x in xs do end`      needs the local pass
        //     resolved BEFORE the for-loop to type `x`
        // putIfAbsent makes the second run cheap and idempotent.
        val forPattern = Regex("""\bfor\s+(\w+)(?:\s*,\s*(\w+))?\s+in\s+(.+?)\s+do\b""")
        val runForLoopPass = {
            for (match in forPattern.findAll(fullText)) {
                val keyName = match.groupValues[1]
                val valName = match.groupValues[2].takeIf { it.isNotEmpty() }
                val rawExpr = match.groupValues[3].trim()
                val iterKind = containerFromIterExpr(rawExpr, fullText, containerVars) ?: continue
                val (elementType, container) = iterKind
                if (valName != null) {
                    symbols.putIfAbsent(valName, elementType)
                    if (keyName != "_") {
                        val keyType = when (container) {
                            LuaApiDocs.Container.ARRAY -> "number"
                            LuaApiDocs.Container.MAP -> "string"
                            else -> null
                        }
                        if (keyType != null) symbols.putIfAbsent(keyName, keyType)
                    }
                }
            }
        }
        runForLoopPass()

        // General inference: local x = expr
        // (`containerVars` was initialized above and may already carry explicit-annotation
        // entries, RHS-inferred container types are merged in below without overwriting
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
                } else if (rhs.endsWith("]")) {
                    // Indexed RHS like `findEach(...)[0]` or `myList[i]`. Strip the
                    // trailing `[…]`, resolve the prefix, and pull out the element
                    // type if the prefix is a container. Falls through silently
                    // when the prefix isn't a container so a non-indexable typo
                    // doesn't pollute the symbol table.
                    val elementType = resolveIndexedElementType(rhs, symbols)
                    if (elementType != null) symbols[varName] = elementType
                } else if (Regex(""".+\.\w+$""").containsMatchIn(rhs)) {
                    // Property access at end like `chain.id` or `chain[0].id`. Resolve
                    // the prefix's type, then look up the field's declared type on it.
                    // Routes through both the migrated registry and the legacy entries
                    // so unmigrated types still infer as long as their property docs
                    // exist under the legacy `Type.field` key.
                    val dotIdx = rhs.lastIndexOf('.')
                    val prefix = rhs.substring(0, dotIdx).trimEnd()
                    val field = rhs.substring(dotIdx + 1).trim()
                    val prefixType = when {
                        prefix.endsWith("]") -> resolveIndexedElementType(prefix, symbols)
                        prefix.endsWith(")") -> resolveExpressionType(prefix)
                        else -> symbols[prefix]
                    }
                    if (prefixType != null) {
                        val fieldType = lookupPropertyType(prefixType, field)
                        if (fieldType != null) symbols[varName] = fieldType
                    }
                } else if (rhs.endsWith(")")) {
                    // Try chain resolution (handles method calls like :find, :face, etc.)
                    val chainType = resolveExpressionType(rhs)
                    if (chainType != null) {
                        symbols[varName] = chainType
                    } else {
                        // Container-returning call? Record element type + container kind
                        // so for-loops over this var can resolve without a wrapper.
                        // Prefer [resolveExpressionReturnType] so arg-aware narrowing
                        // (e.g. `Channel:all("redstone")` → `{ RedstoneCard }`) flows
                        // through the same path as plain methodReturnType lookups.
                        val rt = resolveExpressionReturnType(rhs) ?: run {
                            val methodName = Regex("""(\w+)\s*\(""").findAll(rhs).lastOrNull()?.groupValues?.get(1)
                            methodName?.let {
                                LuaApiDocs.methodReturnType(it) ?: userFunctionReturnType(it, fullText)
                            }
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

        // Second for-loop pass picks up loop variables whose iter expressions are
        // locals declared by the general inference pass above. See the closure's
        // header comment for the bidirectional dependency.
        runForLoopPass()

        // Expose container-typed vars to the scalar [symbols] map using their serialized
        // form (`{ T }` for arrays, `{ [string]: T }` for maps). The hover-tooltip fallback
        // in TerminalScreen reads directly from [getSymbolTable], so this gives
        // `local specificCards: { CardHandle } = …` a proper `specificCards: { CardHandle }`
        // hover, matching how plain `local n = 0` renders `n: number`. No collision with
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

        return symbols
    }

    /**
     * Given the expression after `in` in a `for ... in EXPR do`, with or without an
     * `ipairs(…)` / `pairs(…)` wrapper, return the element type and container kind.
     * Resolves three shapes:
     *   * a function/method call (`fn()`, `card:find(…)`) → via [LuaApiDocs.methodReturnType]
     *   * a bare identifier (`xs`) → via [containerVars] built from earlier `local` scans
     *   * either of the above inside `ipairs(…)` / `pairs(…)`
     *
     * Wrapper choice on the user's side is authoritative when present, `ipairs(xs)` will
     * narrow the container kind to ARRAY even if `xs` is typed as MAP, that mirrors what
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

        // Function/method call at the tail, resolve via LuaApiDocs or user fn annotations.
        if (!unwrapped.endsWith(")")) return null
        val paren = findMatchingParenBackward(unwrapped) ?: return null
        val beforeParen = paren.first.trimEnd()
        val methodName = Regex("""(\w+)$""").find(beforeParen)?.groupValues?.get(1) ?: return null

        // Arg-aware narrowing for `network:getAll("type")` and `Channel:getAll("type")`.
        // Both return `HandleList<T>` whose `:list()` is what for-loops iterate, we
        // surface the underlying element type with ARRAY container kind so the body
        // resolves `c` as the right typed card.
        if (methodName == "getAll") {
            val arg = paren.second.trim().trim('"', '\'')
            channelElementType(arg)?.let {
                return it to (forcedWrapper ?: LuaApiDocs.Container.ARRAY)
            }
        }
        // `for _, c in handleList:list() do`, the receiver before `:list` carries
        // the parameterised wrapper (`HandleList<RedstoneCard>`), unwrap it.
        // Tries the bare-ident lookup in [handleListLocals] first (covers the
        // common `local pistons = network:getAll(...), for _, p in pistons:list() do`
        // shape) and falls back to the chain resolver for inline expressions like
        // `network:getAll("redstone"):list()`.
        if (methodName == "list") {
            val beforeColon = beforeParen.substringBeforeLast(':', "").trimEnd()
            val bareReceiver = Regex("""(\w+)$""").matchEntire(beforeColon)?.groupValues?.get(1)
            val wrapperType = bareReceiver?.let { handleListLocals[it] }
                ?: resolveExpressionType(beforeColon)
            handleListElement(wrapperType)?.let {
                return it to (forcedWrapper ?: LuaApiDocs.Container.ARRAY)
            }
        }

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

        // Build the symbol table FIRST so [parseCursorContext] (which calls
        // [resolveExpressionType] → [extractReceiverType]) can see [channelLocals]
        // and any other type-inference state populated by buildSymbolTable. Without
        // this ordering a chain like `channel:getAll("redstone"):` resolves the
        // receiver as null because `channel` isn't yet known to be Channel-typed.
        val symbols = buildSymbolTable(fullText, beforeCursor)
        val ctx = parseCursorContext(currentLine, beforeCursor)

        return when (ctx) {
            is CursorContext.StringArg -> suggestStringArg(ctx, symbols, fullText)
            is CursorContext.TypeAnnotation -> suggestTypeAnnotation(ctx.partial)
            is CursorContext.TagFilter -> suggestTag(ctx.partial)
            is CursorContext.ResolvedMethodCall -> {
                val raw = suggestMethodsForType(ctx.resolvedType, ctx.partial)
                // `:ensure()` and `:craft()` factory paths set the filter to the concrete
                // item id, so `:filter(...)` on the resulting builder is a footgun (would
                // silently override the auto-set filter). Hide it from suggestions when
                // the chain came in through one of those entry points.
                if (ctx.resolvedType == "StockerBuilder" &&
                    ctx.chainExpr?.let { it.contains(":ensure(") || it.contains(":craft(") } == true
                ) raw.filter { !it.insertText.startsWith("filter(") } else raw
            }
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

    private fun suggestStringArg(
        ctx: CursorContext.StringArg,
        symbols: Map<String, String>,
        fullText: String,
    ): List<Suggestion> {
        customPrefix = ctx.partial

        // Try registry-driven dispatch first. When the receiver + method resolve to a
        // migrated spec, the param's declared type drives the completions, no
        // funcExpr-string special case needed. Falls through to the legacy switch
        // when the spec isn't migrated yet so unmigrated surfaces keep working.
        trySuggestViaRegistry(ctx, symbols)?.let { return it }

        // User-defined function with annotated params. Parse the function declaration
        // out of the script, extract the param at this arg index, dispatch via the
        // same type→completions logic the registry path uses. Lets the user write
        // `function findTag(tag: TagId)` and have `findTag("...")` autocomplete tags
        // without registering anything explicitly.
        trySuggestViaUserFunction(ctx, fullText)?.let { return it }

        // Typed local assignment, `local x: TagId = "..."` or reassignment of a
        // previously-typed local `x = "..."`. Dispatches the same way as a function
        // arg whose declared type is the local's annotation.
        trySuggestViaTypedAssignment(ctx, symbols)?.let { return it }

        return when {
            ctx.funcExpr.endsWith("network:get") -> {
                // Cards first, variables next, devices last, matches the runtime
                // priority in `network:get` so the string most likely to resolve
                // appears at the top of the popup. The label suffix in parens
                // (`(io)`, `(number)`, `(breaker)`) tells the player which surface
                // they're targeting at a glance.
                val typeLabels = arrayOf("number", "string", "bool")
                val cardSuggestions = cards
                    .map { it.effectiveAlias to it.capability.type }
                    .distinct()
                    .map { suggest(it.first, "${it.first} (${it.second})", Kind.STRING) }
                val varSuggestions = variables.map { (name, typeOrd) ->
                    val label = typeLabels.getOrElse(typeOrd) { "variable" }
                    suggest(name, "$name ($label)", Kind.STRING)
                }
                val breakerSuggestions = breakerAliases.map {
                    suggest(it, "$it (breaker)", Kind.STRING)
                }
                val placerSuggestions = placerAliases.map {
                    suggest(it, "$it (placer)", Kind.STRING)
                }
                FuzzyMatch.filter(
                    ctx.partial,
                    cardSuggestions + varSuggestions + breakerSuggestions + placerSuggestions,
                )
            }

            ctx.funcExpr.endsWith("network:route") -> {
                val storageAliases = cards
                    .filter { it.capability.type == "storage" }
                    .map { it.effectiveAlias }
                    .distinct()

                // Group aliases sharing the Card Programmer's `_N` suffix convention so we
                // can offer a single `<prefix>_*` completion per group. Singletons don't
                // count, a group of one card collapses back into a literal alias.
                val suffixGroups = storageAliases
                    .mapNotNull { alias ->
                        val match = CARD_SUFFIX_REGEX.matchEntire(alias) ?: return@mapNotNull null
                        match.groupValues[1] to alias
                    }
                    .groupBy({ it.first }, { it.second })
                    .filterValues { it.size >= 2 }

                // Hide the numbered cards from a grouped set by default, the `_*` wildcard
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
                // fuzzy-matched output, they're the recommended choice for grouped cards.
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
                fuzzyStrings(ctx.partial, listOf("io", "storage", "redstone", "observer", "variable", "breaker", "placer"))
            }

            ctx.funcExpr.endsWith("network:channel") -> {
                // 16 vanilla dye color names. Stable lowercase form matches both
                // DyeColor.byName() at runtime and the script-side `channels()` output.
                fuzzyStrings(ctx.partial, listOf(
                    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
                ))
            }

            // Inside `:getFirst("…")` / `:getAll("…")` on a `Channel` receiver, same
            // set of capability-type hints we offer to `network:getAll`. We can't easily
            // know the receiver type here without re-resolving the chain, so we surface
            // the hints whenever the funcExpr ends with `:getFirst` or `:getAll` and
            // accept a small false-positive surface (these strings on other receivers).
            ctx.funcExpr.endsWith(":getFirst") || ctx.funcExpr.endsWith(":getAll") -> {
                fuzzyStrings(ctx.partial, listOf("io", "storage", "redstone", "observer", "variable", "breaker", "placer"))
            }

            ctx.funcExpr.endsWith("network:craft") -> {
                fuzzyStrings(ctx.partial, craftableOutputs)
            }

            // (network:var was removed, variable names now surface alongside card
            // aliases inside the `network:get` string-arg suggestion below, where the
            // variable type label keeps them distinguishable from cards.)

            ctx.funcExpr.endsWith(":face") -> {
                val faces = listOf("top", "bottom", "north", "south", "east", "west", "side")
                fuzzyStrings(ctx.partial, faces)
            }

            ctx.funcExpr.endsWith("network:handle") -> {
                // Full-block snippet: accepting a suggestion inserts the whole handle()
                // call, closing quote, comma, function signature with typed per-slot
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

            // `network:shapeless("id1", count1, "id2", count2, ...)`, the odd-positioned
            // string args are specific ingredient item IDs. Unlike `:find` this isn't a
            // resource filter: tags, regex, and `$item:` / `$fluid:` sigils are all
            // invalid here, so we suggest only plain item IDs (no fluids either, shapeless
            // recipes don't consume fluids). Every string-arg position gets the same
            // suggestions because vanilla doesn't care about ingredient order, there's no
            // "flip-flop" to resolve in the completion layer itself.
            ctx.funcExpr.endsWith("network:shapeless") -> {
                val suggestions = itemIds.map { Suggestion(it, it, kind = Kind.STRING) }
                FuzzyMatch.filter(ctx.partial, suggestions).take(20)
            }

            // `placer:place("|")` and chained / broadcast variants
            // (`network:getAll("placer"):place`, `placers:place`, etc.). The arg
            // is a single concrete item id, wildcards / sigils / tags don't
            // make sense here because Placer can only place a specific block,
            // not "any item." Use the same plain-itemIds path as :shapeless.
            ctx.funcExpr.endsWith(":place") -> {
                val suggestions = itemIds.map { Suggestion(it, it, kind = Kind.STRING) }
                FuzzyMatch.filter(ctx.partial, suggestions).take(20)
            }

            isResourceFilterFunc(ctx.funcExpr) -> suggestResourceFilter(ctx.partial)

            // Importer / Stocker presets. `:from` and `:to` take card aliases (or the
            // `network` sentinel), `:filter` takes a resource filter, `:ensure` /
            // `:craft` take a concrete item id.
            //
            // Matches both the unchained form (`importer:from(`) and the chained form
            // where funcExpr collapses to just `:from` after a prior method call like
            // `importer:<call>():from(`.
            ctx.funcExpr.endsWithPresetMethod(":from") ||
            ctx.funcExpr.endsWithPresetMethod(":to") -> {
                suggestCardAliasString(ctx.partial)
            }

            ctx.funcExpr.endsWithPresetMethod(":filter") -> {
                suggestResourceFilter(ctx.partial)
            }

            ctx.funcExpr.endsWithPresetMethod(":ensure") ||
            ctx.funcExpr.endsWithPresetMethod(":craft") && !ctx.funcExpr.endsWith("network:craft") -> {
                fuzzyStrings(ctx.partial, craftableOutputs)
            }

            else -> emptyList()
        }
    }

    /** Match a method-name suffix whether the funcExpr is qualified (`importer:from`)
     *  or collapsed to just `:from` via a chain (`importer:x():from`). Colon kept so
     *  this doesn't accidentally match fragments like `someFrom`. */
    private fun String.endsWithPresetMethod(suffix: String): Boolean =
        this == suffix || this.endsWith("importer$suffix") || this.endsWith("stocker$suffix")

    /** Card alias suggestions used by preset :from / :to string args. Lists every
     *  alias on the network annotated with its capability kind, plus a
     *  `<prefix>_*` wildcard completion whenever two or more cards share the Card
     *  Programmer's `_N` numbered-suffix convention. Mirrors the grouping
     *  behaviour of `network:route` so `io_1`, `io_2`, `io_3` all collapse into
     *  a single `io_*` suggestion. Same numbered cards are hidden by default
     *  (the wildcard is the usual intent) unless the player is disambiguating by
     *  typing the digit suffix themselves. */
    private fun suggestCardAliasString(partial: String): List<Suggestion> {
        // Collect (alias, capability type) pairs, keeping the first capability type
        // we see per alias so `{"io_1" to "io", "io_1" to "storage"}` (unlikely but
        // possible) doesn't produce duplicates in the popup.
        val aliasToType = LinkedHashMap<String, String>()
        for (card in cards) {
            aliasToType.putIfAbsent(card.effectiveAlias, card.capability.type)
        }

        // Group by the prefix before `_N`. Singletons don't count, a lone card
        // stays a literal alias in the suggestion list.
        val suffixGroups = aliasToType.keys
            .mapNotNull { alias ->
                val match = CARD_SUFFIX_REGEX.matchEntire(alias) ?: return@mapNotNull null
                match.groupValues[1] to alias
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }

        // Hide numbered cards from grouped sets unless the user is disambiguating.
        val hiddenAliases = mutableSetOf<String>()
        for ((prefix, aliases) in suffixGroups) {
            val stem = "${prefix}_"
            val disambiguating = partial.startsWith(stem) &&
                partial.length > stem.length &&
                partial[stem.length].isDigit()
            if (!disambiguating) hiddenAliases.addAll(aliases)
        }

        val suggestions = mutableListOf<Suggestion>()
        // Wildcards first so they surface above literal cards in fuzzy-matched output.
        for ((prefix, aliases) in suffixGroups) {
            val wildcard = "${prefix}_*"
            val preview = aliases.sorted().take(3).joinToString(", ") +
                if (aliases.size > 3) ", …" else ""
            suggestions.add(
                suggest(wildcard, "$wildcard (${aliases.size} cards: $preview)", Kind.STRING)
            )
        }
        for ((alias, type) in aliasToType) {
            if (alias in hiddenAliases) continue
            suggestions.add(suggest(alias, "$alias ($type)", Kind.STRING))
        }
        return FuzzyMatch.filter(partial, suggestions)
    }

    /** Resolve a string-arg context through the API registry. Parses the funcExpr to
     *  extract receiver + method, looks up the method's declared param type, then
     *  dispatches the type to the appropriate completion handler. Returns null if
     *  the receiver, method, or arg index don't resolve to a typed param so the
     *  caller can fall back to legacy dispatch. */
    private fun trySuggestViaRegistry(
        ctx: CursorContext.StringArg,
        symbols: Map<String, String>,
    ): List<Suggestion>? {
        // network:handle has a per-processing-set full-snippet UX in the legacy
        // dispatcher that the simpler card-alias completion can't reproduce yet.
        // Bail out so the legacy branch fires and produces typed-per-recipe templates.
        if (ctx.funcExpr == "network:handle") return null
        if (ctx.funcExpr.isEmpty()) return null

        // Two shapes resolve to a (receiverType, methodName) pair:
        //   `receiver:method` — direct call, receiver is a module global or a typed local
        //   `:method`         — chained call where the receiver is the chain expression in
        //                       precedingText, e.g. `importer:from(...):to("|"` collapses
        //                       to funcExpr=":to" with the chain on the left.
        val (receiverType, methodName) = if (ctx.funcExpr.startsWith(":")) {
            val method = ctx.funcExpr.substring(1)
            if (method.isBlank()) return null
            val callMarker = ":$method("
            val callIdx = ctx.precedingText.lastIndexOf(callMarker)
            if (callIdx < 0) return null
            val chainExpr = ctx.precedingText.substring(0, callIdx).trimEnd()
            val type = resolveExpressionType(chainExpr) ?: return null
            type to method
        } else {
            val colon = ctx.funcExpr.indexOf(':')
            if (colon <= 0) return null
            val receiver = ctx.funcExpr.substring(0, colon)
            val method = ctx.funcExpr.substring(colon + 1)
            val type = damien.nodeworks.script.api.LuaApiRegistry.moduleType(receiver)?.name
                ?: symbols[receiver]
                ?: return null
            type to method
        }

        val methodDoc = damien.nodeworks.script.api.LuaApiRegistry
            .methodsOf(receiverType)
            .firstOrNull { it.displayName == methodName }
            ?: return null

        val paramType = methodDoc.params.getOrNull(ctx.argIndex)?.type ?: return null
        val baseSuggestions = suggestionsForType(paramType, ctx.partial) ?: return null

        // When the method's shape is `(<string>, <function>)` and we're inside the
        // string arg, transform each suggestion into a full-snippet that includes
        // the function template. Accepting a suggestion drops in the entire call
        // (`route("alias", function(items: ItemsHandle) ... end)`) with the cursor
        // in the function body, ready for the user to start writing the predicate.
        // Only fires for the exact 2-param shape so methods with extra trailing
        // args don't get their tail truncated.
        val nextParam = methodDoc.params.getOrNull(ctx.argIndex + 1)
        if (
            ctx.argIndex == 0 &&
            methodDoc.params.size == 2 &&
            nextParam?.type is damien.nodeworks.script.api.LuaType.Function
        ) {
            val fnType = nextParam.type as damien.nodeworks.script.api.LuaType.Function
            val fnParamList = fnType.params.joinToString(", ") { "${it.name}: ${it.type.display}" }
            return baseSuggestions.map { s ->
                val before = "${s.insertText}\", function($fnParamList)\n    "
                val after = "\nend)"
                Suggestion(
                    insertText = s.insertText,
                    displayText = s.displayText,
                    snippetText = before + after,
                    snippetCursor = before.length,
                    consumesAutoclose = true,
                    kind = s.kind,
                )
            }
        }

        return baseSuggestions
    }

    /** Resolve a string-arg context to a user-defined function's param type and
     *  dispatch via [suggestionsForType]. Handles three call shapes:
     *
     *  1. Bare function in the same script, `findTag("...")`.
     *  2. Module-qualified call into a `require`'d script,
     *     `local m = require("mod"); m.fn("...")`.
     *  3. Methods on registered receivers go through [trySuggestViaRegistry], not
     *     here, this path explicitly bails on `:` to avoid double-matching.
     *
     *  Returns null when the function can't be located or its param isn't typed
     *  with a registered string subtype, the caller falls through to legacy. */
    private fun trySuggestViaUserFunction(
        ctx: CursorContext.StringArg,
        fullText: String,
    ): List<Suggestion>? {
        if (ctx.funcExpr.contains(':')) return null
        if (ctx.funcExpr.isEmpty()) return null

        val func = if (ctx.funcExpr.contains('.')) {
            findRequiredModuleFunction(ctx.funcExpr, fullText) ?: return null
        } else {
            extractFunctions(fullText).firstOrNull { it.name == ctx.funcExpr } ?: return null
        }
        val typeName = parseParamType(func.params, ctx.argIndex) ?: return null
        val resolvedType = damien.nodeworks.script.api.LuaApiRegistry.stringTypeOf(typeName) ?: return null
        return suggestionsForType(resolvedType, ctx.partial)
    }

    /** Resolve `<localVar>.<funcName>` to a [FunctionInfo] when [localVar] was
     *  declared as `local <var> = require("<mod>")` and the module's source defines
     *  `function <tableVar>.<funcName>(...)`. Cross-script param-type inference,
     *  the analog of [extractFunctions] but reaching into [scripts] to load the
     *  module text by name. */
    private fun findRequiredModuleFunction(funcExpr: String, fullText: String): FunctionInfo? {
        val dotIdx = funcExpr.indexOf('.')
        if (dotIdx <= 0) return null
        val localVar = funcExpr.substring(0, dotIdx)
        val funcName = funcExpr.substring(dotIdx + 1)
        if (funcName.contains('.')) return null

        val requirePattern = Regex("""\blocal\s+${Regex.escape(localVar)}\s*=\s*require\(\s*"(\w+)"\s*\)""")
        val moduleName = requirePattern.find(fullText)?.groupValues?.get(1) ?: return null

        val moduleText = scripts()[moduleName] ?: return null
        val funcPattern = Regex("""\bfunction\s+\w+\.${Regex.escape(funcName)}\s*\(([^)]*)\)\s*(?::\s*(\w+\??|\{[^}]*}))?""")
        val match = funcPattern.find(moduleText) ?: return null
        val params = match.groupValues[1].trim().split(",").joinToString(", ") { it.trim() }
        val returnType = match.groupValues[2].ifEmpty { null }
        return FunctionInfo(funcName, params, returnType)
    }

    /** Resolve a typed assignment to the local's declared type and dispatch via
     *  [suggestionsForType]. Two patterns are recognised:
     *
     *  1. `local <name>: <Type> = "..."`, type comes straight out of the line text
     *     since the declaration sits to the left of the cursor.
     *  2. `<name> = "..."` for a previously-declared local, type comes from the
     *     symbol table that the buildSymbolTable pass populated.
     *
     *  Returns null when neither pattern matches or the resolved type isn't a
     *  registered string subtype, the caller falls through to the legacy switch. */
    private fun trySuggestViaTypedAssignment(
        ctx: CursorContext.StringArg,
        symbols: Map<String, String>,
    ): List<Suggestion>? {
        val text = ctx.precedingText
        if (text.isBlank()) return null

        val typeName = parseTypedAssignmentType(text, symbols) ?: return null
        val resolved = damien.nodeworks.script.api.LuaApiRegistry.stringTypeOf(typeName) ?: return null
        return suggestionsForType(resolved, ctx.partial)
    }

    /** Match `local <name>: <Type> =`, `local <name>: { <Type> } = { ..., "|"`,
     *  or `<name> =` against the symbol table. Returned name has the trailing `=`
     *  stripped and any `?` (nullable) trimmed, matching the format
     *  [LuaApiRegistry.stringTypeOf] expects.
     *
     *  The second pattern handles array-literal assignments. The cursor is on an
     *  element of the array, so the resolved type is the element T (not the
     *  container `{ T }`), which the caller then dispatches to T's source. */
    private fun parseTypedAssignmentType(precedingText: String, symbols: Map<String, String>): String? {
        val trimmed = precedingText.trimEnd().removeSuffix("=").trimEnd()
        val localMatch = Regex("""\blocal\s+\w+\s*:\s*(\w+)\??$""").find(trimmed)
        if (localMatch != null) return localMatch.groupValues[1]

        val arrayElement = Regex("""\blocal\s+\w+\s*:\s*\{\s*(\w+)\s*\}\s*=\s*\{[^}]*$""")
            .find(precedingText)
        if (arrayElement != null) return arrayElement.groupValues[1]

        val reassignMatch = Regex("""\b(\w+)$""").find(trimmed)
        if (reassignMatch != null) {
            val name = reassignMatch.groupValues[1]
            return symbols[name]?.trimEnd('?')
        }
        return null
    }

    /** Pull the type name out of one position in a [FunctionInfo.params] string.
     *  The params come pre-formatted as `"a: TagId, b: number, c: ItemsHandle?"`,
     *  this strips comma-separated segments, finds the colon, and returns the
     *  type token (with `?` suffix removed for nullable). Returns null if the
     *  segment lacks an annotation, the caller treats that as "untyped, no
     *  completions to offer". */
    private fun parseParamType(paramsStr: String, argIndex: Int): String? {
        if (paramsStr.isBlank()) return null
        val segments = paramsStr.split(",")
        val seg = segments.getOrNull(argIndex) ?: return null
        val colonIdx = seg.indexOf(':')
        if (colonIdx < 0) return null
        return seg.substring(colonIdx + 1).trim().trimEnd('?')
    }

    /** Recursively dispatch a [damien.nodeworks.script.api.LuaType] to its completion
     *  source. Returns null when the type isn't string-shaped (param expects a typed
     *  value, not a string literal, so no string-position completions apply). */
    private fun suggestionsForType(
        type: damien.nodeworks.script.api.LuaType,
        partial: String,
    ): List<Suggestion>? {
        val unwrapped = damien.nodeworks.script.api.LuaType.unwrap(type)
        return when (unwrapped) {
            is damien.nodeworks.script.api.LuaType.StringEnum ->
                fuzzyStrings(partial, unwrapped.values)
            is damien.nodeworks.script.api.LuaType.StringDomain ->
                suggestionsForDomain(unwrapped.sourceKey, partial)
            is damien.nodeworks.script.api.LuaType.Union ->
                unwrapped.parts
                    .flatMap { suggestionsForType(it, partial) ?: emptyList() }
                    .distinctBy { it.insertText }
            else -> null
        }
    }

    /** Resolve a [damien.nodeworks.script.api.LuaType.StringDomain] source key to
     *  the popup's local data. Each case here is the bridge from a declared domain
     *  to the popup's actual data feed, adding a new domain means: declare it in
     *  [damien.nodeworks.script.api.LuaStringTypes], add a case here, that's it. */
    private fun suggestionsForDomain(sourceKey: String, partial: String): List<Suggestion> = when (sourceKey) {
        "item-id" -> fuzzyStrings(partial, itemIds)
        "fluid-id" -> fuzzyStrings(partial, fluidIds)
        "tag-id" -> fuzzyStrings(partial, (itemTags + fluidTags).distinct())
        "block-id" -> emptyList()
        "craftable" -> fuzzyStrings(partial, craftableOutputs)
        "card-alias" -> {
            val labels = cards.map { it.effectiveAlias to it.capability.type }.distinct()
            FuzzyMatch.filter(
                partial,
                labels.map { (alias, type) -> suggest(alias, "$alias ($type)", Kind.STRING) },
            )
        }
        "storage-card-alias" -> suggestCardAliasesWithWildcards(
            partial,
            cards.filter { it.capability.type == "storage" }
        )
        "inventory-card-alias" -> suggestCardAliasesWithWildcards(
            partial,
            cards.filter { it.capability.type == "io" || it.capability.type == "storage" }
        )
        "breaker-alias" -> FuzzyMatch.filter(
            partial,
            breakerAliases.map { suggest(it, "$it (breaker)", Kind.STRING) },
        )
        "placer-alias" -> FuzzyMatch.filter(
            partial,
            placerAliases.map { suggest(it, "$it (placer)", Kind.STRING) },
        )
        "variable-name" -> {
            val typeLabels = arrayOf("number", "string", "bool")
            FuzzyMatch.filter(
                partial,
                variables.map { (name, typeOrd) ->
                    val label = typeLabels.getOrElse(typeOrd) { "variable" }
                    suggest(name, "$name ($label)", Kind.STRING)
                },
            )
        }
        "filter" -> suggestResourceFilter(partial)
        else -> emptyList()
    }

    /** Card-alias autocomplete with wildcard grouping. Cards whose aliases share
     *  a `<prefix>_<digit>` shape group under a `<prefix>_*` wildcard suggestion
     *  that matches every numbered sibling at runtime, restoring the legacy UX
     *  where typing `cobblestone` first surfaces `cobblestone_*` instead of
     *  every individual card.
     *
     *  Numbered cards stay hidden until the user starts disambiguating with the
     *  digit suffix (`cobblestone_2`), at which point the individual entries
     *  surface alongside the wildcard. Singletons (only one numbered card with
     *  the prefix) collapse back to a literal alias since the wildcard would
     *  match exactly one thing.
     *
     *  Each suggestion's hint shows the card's capability type, so a mixed list
     *  (e.g. `inventory-card-alias` covering both IO and Storage) tells the
     *  player which kind they're picking. */
    private fun suggestCardAliasesWithWildcards(
        partial: String,
        scopedCards: List<CardSnapshot>,
    ): List<Suggestion> {
        val aliasToType = scopedCards
            .map { it.effectiveAlias to it.capability.type }
            .distinct()
        val aliases = aliasToType.map { it.first }
        val typeOf: Map<String, String> = aliasToType.toMap()

        val suffixGroups = aliases
            .mapNotNull { alias ->
                val match = CARD_SUFFIX_REGEX.matchEntire(alias) ?: return@mapNotNull null
                match.groupValues[1] to alias
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }

        val hiddenAliases = mutableSetOf<String>()
        for ((prefix, members) in suffixGroups) {
            val stem = "${prefix}_"
            val disambiguating = partial.startsWith(stem) &&
                partial.length > stem.length &&
                partial[stem.length].isDigit()
            if (!disambiguating) hiddenAliases.addAll(members)
        }

        val out = mutableListOf<Suggestion>()
        for ((prefix, members) in suffixGroups) {
            val wildcard = "${prefix}_*"
            val preview = members.sorted().take(3).joinToString(", ") +
                if (members.size > 3) ", …" else ""
            out += suggest(wildcard, "$wildcard (${members.size} cards: $preview)", Kind.STRING)
        }
        for (alias in aliases) {
            if (alias in hiddenAliases) continue
            val type = typeOf[alias] ?: continue
            out += suggest(alias, "$alias ($type)", Kind.STRING)
        }
        return FuzzyMatch.filter(partial, out)
    }

    /** Lua functions whose string argument is a resource-id filter (items + fluids).
     *  `:insert` / `:tryInsert` are NOT resource-filter funcs, their first arg is an
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
                    Suggestion("\$item:", "\$item:  match items only", kind = Kind.STRING),
                    Suggestion("\$fluid:", "\$fluid:  match fluids only", kind = Kind.STRING)
                )
                FuzzyMatch.filter(partial, sigils)
            }
            else -> {
                // No prefix: fuzzy-match across sigils + item ids + fluid ids. Fluid entries
                // are inserted as `$fluid:<id>` so accepting one commits to the fluid kind,
                // bare `minecraft:water` would resolve to an item-side lookup first and mislead
                // the user, so this forces explicit qualification on accept.
                val idSuggestions = itemIds.map { Suggestion(it, it, kind = Kind.STRING) }
                val fluidSuggestions = fluidIds.map {
                    Suggestion("\$fluid:$it", "\$fluid:$it", kind = Kind.STRING)
                }
                val sigils = listOf(
                    Suggestion("\$item:", "\$item:  match items only", kind = Kind.STRING),
                    Suggestion("\$fluid:", "\$fluid:  match fluids only", kind = Kind.STRING)
                )
                FuzzyMatch.filter(partial, sigils + idSuggestions + fluidSuggestions).take(20)
            }
        }
    }

    /** Type-annotation autocomplete pool. Lua primitives stay hand-listed at the
     *  top, every TYPE-category entry the registry knows about (Named types like
     *  CardHandle and string subtypes like TagId / Filter) is appended at lazy-init.
     *  The legacy hand-list below is for types not yet migrated to the DSL, when a
     *  type moves to a spec its entry can be removed from the legacy block. */
    private val knownTypes: List<Suggestion> by lazy {
        val primitives = listOf(
            Suggestion("string", "string", kind = Kind.TYPE),
            Suggestion("number", "number", kind = Kind.TYPE),
            Suggestion("boolean", "boolean", kind = Kind.TYPE),
            Suggestion("any", "any", kind = Kind.TYPE),
        )
        val fromRegistry = damien.nodeworks.script.api.LuaApiRegistry.allDocs().values
            .filter { it.category == damien.nodeworks.script.api.ApiCategory.TYPE }
            .map { Suggestion(it.displayName, "${it.displayName}  ${it.description}", kind = Kind.TYPE) }
        val registryNames = fromRegistry.map { it.insertText }.toSet()
        val legacy = listOf(
            Suggestion("InputItems", "InputItems  handler input bag, access slot handles by name", kind = Kind.TYPE),
            Suggestion("RedstoneCard", "RedstoneCard  redstone card from network:get", kind = Kind.TYPE),
            Suggestion("ObserverCard", "ObserverCard  observer card from network:get", kind = Kind.TYPE),
            Suggestion("BreakerHandle", "BreakerHandle  Breaker device from network:get", kind = Kind.TYPE),
            Suggestion("BreakBuilder", "BreakBuilder  returned by Breaker:mine() for drop routing", kind = Kind.TYPE),
            Suggestion("PlacerHandle", "PlacerHandle  Placer device from network:get", kind = Kind.TYPE),
            Suggestion("Channel", "Channel  dye-color group from network:channel", kind = Kind.TYPE),
            Suggestion("HandleList", "HandleList  broadcast list from network:getAll / Channel:getAll", kind = Kind.TYPE),
            Suggestion("Job", "Job  processing handler context from network:handle", kind = Kind.TYPE),
            Suggestion("CraftBuilder", "CraftBuilder  from network:craft(), chain with :connect()", kind = Kind.TYPE),
            Suggestion("NumberVariableHandle", "NumberVariableHandle  number variable from network:get", kind = Kind.TYPE),
            Suggestion("StringVariableHandle", "StringVariableHandle  string variable from network:get", kind = Kind.TYPE),
            Suggestion("BoolVariableHandle", "BoolVariableHandle  bool variable from network:get", kind = Kind.TYPE),
        ).filter { it.insertText !in registryNames }
        primitives + fromRegistry + legacy
    }

    private fun suggestTypeAnnotation(partial: String): List<Suggestion> {
        // Don't suggest if the partial already exactly matches a known type
        if (knownTypes.any { it.insertText == partial }) return emptyList()
        customPrefix = partial
        return fuzzy(partial, knownTypes)
    }

    private fun suggestTag(partial: String): List<Suggestion> {
        customPrefix = partial
        // Union item + fluid tags, deduped, `#c:water` is valid for both kinds and users
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

        // Built-in objects: route through the registry-backed type-method query so
        // signatures, snippet templates, and chain return types stay sourced from
        // the spec. The legacy hand-rolled `suggestNetworkMethods` /
        // `suggestSchedulerMethods` paths remain in this file for reference but are
        // no longer reachable, the migrated specs cover their full method sets.
        if (receiver == "network") return suggestMethodsForType("Network", partial)
        if (receiver == "scheduler") return suggestMethodsForType("Scheduler", partial)
        if (receiver == "importer") return suggestMethodsForType("Importer", partial)
        if (receiver == "stocker") return suggestMethodsForType("Stocker", partial)

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

        // If the partial is a complete Lua keyword, the user has typed a syntactic
        // marker (`do`, `then`, `end`), not an identifier prefix. Suppress the
        // popup entirely so Enter falls through to inserting a newline instead
        // of accepting a fuzzy-matched card-import suggestion. Sourced from the
        // registry so the keyword set stays in sync with [LuaGlobalsApi].
        if (
            damien.nodeworks.script.api.LuaApiRegistry.docFor(partial)?.category ==
            damien.nodeworks.script.api.ApiCategory.KEYWORD
        ) return emptyList()

        // Registry-driven: every module + bare function + Lua keyword comes from
        // the same single-source spec ([LuaGlobalsApi] for keywords + bare globals,
        // each module's spec file for the module aliases). Lowercase-key filter on
        // module docs picks the script-callable alias (`network`) and skips the
        // capitalised type entry (`Network`) which is for type-annotation use.
        val registryGlobals = damien.nodeworks.script.api.LuaApiRegistry.allDocs().values
        val moduleSuggestions = registryGlobals
            .filter {
                it.category == damien.nodeworks.script.api.ApiCategory.MODULE &&
                    it.displayName.firstOrNull()?.isLowerCase() == true
            }
            .map { suggest(it.displayName, it.signature, Kind.MODULE) }
        val globalFunctionSuggestions = registryGlobals
            .filter { it.category == damien.nodeworks.script.api.ApiCategory.FUNCTION }
            .map { suggest("${it.displayName}(", it.signature, Kind.FUNCTION) }
        val apiFunctions = moduleSuggestions + globalFunctionSuggestions

        val keywords = registryGlobals
            .filter { it.category == damien.nodeworks.script.api.ApiCategory.KEYWORD }
            .map { suggest(it.displayName, kind = Kind.KEYWORD) }
        // Variables are scoped: only surface names declared BEFORE the cursor. Using
        // fullText would offer `myVar` in `myVa|\nlocal myVar = 0`, suggesting code that
        // isn't yet valid. For-loop bindings + function params follow the same rule
        // because [extractVariableNames] / [extractFunctionParams] both take the
        // `beforeCursor` slice.
        //
        // Functions stay global (scanned from fullText) because declaring a function later
        // in the file doesn't affect whether the IDE should suggest it, common Lua
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

        // Auto-import suggestions: every card and variable on the network shows up
        // as a Lua-safe identifier (via the same naming the sidebar click handler
        // uses), and accepting one prepends `local NAME = network:get("alias")`
        // (or `:var(...)`) to the script. Skip aliases whose identifier is already
        // declared as a local in the script, those will surface as plain user vars.
        val declared = (extractVariableNames(beforeCursor) + extractFunctionParams(beforeCursor)).toSet()
        val cardImports = cards
            .map { it.effectiveAlias to it.capability.type }
            .distinct()
            .mapNotNull { (alias, type) ->
                val ident = damien.nodeworks.script.LuaIdent.toLuaIdentifier(alias, "card")
                if (ident in declared) return@mapNotNull null
                Suggestion(
                    insertText = ident,
                    displayText = "$ident  $alias ($type)",
                    kind = Kind.VARIABLE,
                    autoImport = "local $ident = network:get(\"$alias\")"
                )
            }
        val variableImports = variables
            .mapNotNull { (name, _) ->
                val ident = damien.nodeworks.script.LuaIdent.toLuaIdentifier(name, "var")
                if (ident in declared) return@mapNotNull null
                Suggestion(
                    insertText = ident,
                    displayText = "$ident  $name (variable)",
                    kind = Kind.VARIABLE,
                    // Variables now ride the unified `network:get` accessor instead
                    // of the legacy `network:var` (which has been removed).
                    autoImport = "local $ident = network:get(\"$name\")"
                )
            }

        val all = (apiFunctions + requireSuggest + keywords + userVars + userFuncs + cardImports + variableImports)
            .distinctBy { it.insertText }
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
            suggest("channel(", "channel(color: string) → Channel", Kind.METHOD),
            suggest("channels(", "channels() → string[]", Kind.METHOD),
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
            // (network:var was removed, variables resolve through `network:get(name)`.)
            suggest("handle(", "handle(cardName: string, fn: function(job, ...))", Kind.METHOD),
            suggest("debug(", "debug()  print network topology", Kind.METHOD)
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

    /** Build method suggestions for [type] from the API registry. Returns null when
     *  the type isn't registered so the legacy hand-rolled switch can take over,
     *  this is the migration shim that lets us move types over one at a time. */
    private fun suggestionsFromRegistryMethods(type: String, partial: String): List<Suggestion>? {
        val methods = damien.nodeworks.script.api.LuaApiRegistry.methodsOf(type)
        if (methods.isEmpty()) return null
        val out = methods.map { doc ->
            val sigSuffix = doc.signature.substringAfter(doc.displayName)
            if (doc.snippetBody != null) {
                snippet("${doc.displayName}(", "${doc.displayName}$sigSuffix", doc.snippetBody, doc.snippetCursorOffset)
            } else {
                suggest("${doc.displayName}(", "${doc.displayName}$sigSuffix", Kind.METHOD)
            }
        }
        return fuzzy(partial, out)
    }

    /** Property-side equivalent of [suggestionsFromRegistryMethods]. */
    private fun suggestionsFromRegistryProperties(type: String, partial: String): List<Suggestion>? {
        val props = damien.nodeworks.script.api.LuaApiRegistry.propertiesOf(type)
        if (props.isEmpty()) return null
        val out = props.map { doc ->
            suggest(doc.displayName, doc.signature, Kind.PROPERTY)
        }
        return fuzzy(partial, out)
    }

    private fun suggestMethodsForType(type: String, partial: String): List<Suggestion> {
        // Registry-first: types migrated to the new spec system get their methods
        // from there so signatures, parameter types, and snippet templates stay in
        // sync with hover tooltips and the guidebook. Falls through to the legacy
        // switch when the type hasn't migrated yet.
        suggestionsFromRegistryMethods(type, partial)?.let { return it }

        // HandleList<T>, the parameterised wrapper. Always exposes the universal
        // `:list()` / `:count()` plus the broadcast (write-only) methods for T,
        // sourced from [HandleListMethods] so the registry stays the single point
        // of truth. Unknown T (mixed-type lists from `channel:getAll()` with no
        // arg) gets only the universal pair.
        handleListElement(type)?.let { elementType ->
            val methods = mutableListOf(
                suggest("list(", "list() → { $elementType }", Kind.METHOD),
                suggest("count(", "count() → number", Kind.METHOD),
            )
            // Map element type → registry key. Cards use capability type strings
            // ("redstone", "io", …), variables use the typed handle name.
            val capabilityType = when (elementType) {
                "RedstoneCard" -> "redstone"
                "ObserverCard" -> "observer"
                "BreakerHandle" -> "breaker"
                "PlacerHandle" -> "placer"
                "CardHandle" -> "io"  // io and storage share methods, pick io
                else -> null
            }
            val broadcastNames = when {
                capabilityType != null ->
                    damien.nodeworks.script.HandleListMethods.methodsForCapabilityType(capabilityType)
                else ->
                    damien.nodeworks.script.HandleListMethods.methodsForHandleType(elementType)
            }
            // Reuse the underlying element-type method's signature & description so
            // the broadcast version reads identically to the per-member call. The
            // hover tooltip resolution path (LuaApiDocs.resolveAt) does the same
            // lookup at hover time, so popup labels and tooltips stay in sync.
            for (name in broadcastNames) {
                val sourceDoc = LuaApiDocs.get("${elementType}:${name}")
                val display = sourceDoc?.signature ?: "$name(...)  broadcast to every member"
                methods.add(suggest("$name(", display, Kind.METHOD))
            }
            return fuzzy(partial, methods)
        }
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
                    )
                )
            }

            "ObserverCard" -> {
                val onChangeBody = "onChange(function(block: string, state: { [string]: any })\n    \nend)"
                listOf(
                    suggest("block(", "block() → string", Kind.METHOD),
                    suggest("state(", "state() → { [string]: any }", Kind.METHOD),
                    snippet(
                        "onChange(",
                        "onChange(fn(block: string, state: { [string]: any }))",
                        onChangeBody,
                        onChangeBody.indexOf("\n    \n") + 5
                    )
                )
            }

            "BreakerHandle" -> listOf(
                suggest("mine(", "mine() → BreakBuilder", Kind.METHOD),
                suggest("cancel(", "cancel()", Kind.METHOD),
                suggest("block(", "block() → string", Kind.METHOD),
                suggest("state(", "state() → { [string]: any }", Kind.METHOD),
                suggest("isMining(", "isMining() → boolean", Kind.METHOD),
                suggest("progress(", "progress() → number", Kind.METHOD),
            )

            "BreakBuilder" -> {
                val connectBody = "connect(function(items: ItemsHandle)\n    \nend)"
                listOf(
                    snippet(
                        "connect(",
                        "connect(fn(items: ItemsHandle))",
                        connectBody,
                        connectBody.indexOf("\n    \n") + 5
                    )
                )
            }

            "PlacerHandle" -> listOf(
                suggest("place(", "place(item: string | ItemsHandle) → boolean", Kind.METHOD),
                suggest("block(", "block() → string", Kind.METHOD),
                suggest("isBlocked(", "isBlocked() → boolean", Kind.METHOD),
            )

            "Channel" -> listOf(
                suggest("getFirst(", "getFirst(type: string) → CardHandle?", Kind.METHOD),
                suggest("getAll(", "getAll(type: string?) → HandleList", Kind.METHOD),
                suggest("get(", "get(alias: string) → CardHandle", Kind.METHOD),
            )

            "ItemsHandle" -> listOf(
                suggest("hasTag(", "hasTag(tag: string) → boolean", Kind.METHOD),
                suggest("matches(", "matches(filter: string) → boolean", Kind.METHOD)
            )

            "Job" -> listOf(suggest("pull(", "pull(card: CardHandle, ...)  wait for outputs", Kind.METHOD))
            "CraftBuilder" -> {
                val connectBody = "connect(function(item: ItemsHandle)\n    \nend)"
                listOf(
                    snippet(
                        "connect(",
                        "connect(fn(item: ItemsHandle))",
                        connectBody,
                        connectBody.indexOf("\n    \n") + 5
                    ),
                    suggest("store(", "store()  send result to network storage", Kind.METHOD)
                )
            }

            "VariableHandle", "NumberVariableHandle", "StringVariableHandle", "BoolVariableHandle" ->
                variableHandleMethods(type)

            "Importer" -> listOf(
                suggest(
                    "from(",
                    "from(...sources: string | CardHandle | network) → ImporterBuilder",
                    Kind.METHOD
                )
            )

            "ImporterBuilder" -> presetBuilderMethods("ImporterBuilder") + listOf(
                suggest(
                    "to(",
                    "to(...targets: string | CardHandle | network) → ImporterBuilder",
                    Kind.METHOD
                ),
                suggest(
                    "roundrobin(",
                    "roundrobin(step: number?) → ImporterBuilder",
                    Kind.METHOD
                ),
                suggest("filter(", "filter(pattern: string) → ImporterBuilder", Kind.METHOD)
            )

            "Stocker" -> listOf(
                suggest(
                    "from(",
                    "from(...sources: string | CardHandle | network) → StockerBuilder",
                    Kind.METHOD
                ),
                suggest("ensure(", "ensure(itemId: string) → StockerBuilder", Kind.METHOD),
                suggest("craft(", "craft(itemId: string) → StockerBuilder", Kind.METHOD)
            )

            "StockerBuilder" -> presetBuilderMethods("StockerBuilder") + listOf(
                suggest(
                    "to(",
                    "to(target: string | CardHandle | network) → StockerBuilder",
                    Kind.METHOD
                ),
                suggest("keep(", "keep(amount: number) → StockerBuilder", Kind.METHOD),
                suggest("batch(", "batch(size: number) → StockerBuilder", Kind.METHOD),
                suggest("filter(", "filter(pattern: string) → StockerBuilder", Kind.METHOD)
            )

            else -> emptyList()
        }
        return fuzzy(partial, methods)
    }

    /** Shared lifecycle methods on every preset builder (ImporterBuilder, StockerBuilder). */
    private fun presetBuilderMethods(builderType: String): List<Suggestion> = listOf(
        suggest("every(", "every(ticks: number) → $builderType", Kind.METHOD),
        suggest("start(", "start()", Kind.METHOD),
        suggest("stop(", "stop()", Kind.METHOD),
        suggest("isRunning(", "isRunning() → boolean", Kind.METHOD)
    )

    private fun suggestPropertiesForType(type: String, partial: String): List<Suggestion> {
        // Registry-first dispatch, mirrors [suggestMethodsForType].
        suggestionsFromRegistryProperties(type, partial)?.let { return it }

        val props = when (type) {
            // CardHandle and RedstoneCard share the same underlying Lua table (same
            // `.name` binding from CardHandle.create), the two entries just exist so
            // method autocomplete can show the appropriate method set per card kind.
            "CardHandle", "RedstoneCard", "ObserverCard" -> listOf(
                suggest("name", "name: string (card's alias)", Kind.PROPERTY)
            )

            // VariableHandle and its typed variants share the same `.name` binding
            // (set once at handle creation from the variable's declared name).
            "VariableHandle", "NumberVariableHandle", "StringVariableHandle", "BoolVariableHandle" -> listOf(
                suggest("name", "name: string (variable's declared name)", Kind.PROPERTY)
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
                // Fields are dynamic, derived from the enclosing `network:handle(...)`
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
     * when the outer variable is typed `InputItems`, its fields are all ItemsHandle,
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
     * currently supported, its fields are all ItemsHandle, pulled from the enclosing
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
     *   If it matches, record the id on the new scope, otherwise push null.
     * - At each `end` close, pop.
     * - After processing, walk the stack from innermost outward for the first non-null id.
     *
     * Multi-line `network:handle("<id>",\n    function(...)` is handled because `\s` in
     * the regex matches newlines. `function` tokens inside string literals or block
     * comments are not filtered out, acceptable edge case for a best-effort heuristic.
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
                // hundreds of chars for recipes with many slots, 9 inputs + 3 outputs
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
        "set(" to "set(value), set variable value",
        "cas(" to "cas(expected, new) → boolean",
        "type(" to "type() → string"
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
     * Special check for `network:handle("cardName", partial`, needs to suggest function snippet.
     * Called from suggestNetworkMethods but also checked in MethodCall context.
     */
    private fun checkHandleSnippetContext(beforeCursor: String): List<Suggestion>? {
        val currentLine = beforeCursor.substringAfterLast('\n')
        val handleFnMatch = Regex("""network:handle\(\s*"([^"]+)"\s*,\s*(\w*)$""").find(currentLine) ?: return null
        val partial = handleFnMatch.groupValues[2]
        customPrefix = partial
        // Uniform handler signature, `job: Job, items: InputItems` regardless of recipe.
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
            // Check for `end`, closes the most recent scope
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
     * current line indent, good enough for most scripts, the player can tab-shift
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
