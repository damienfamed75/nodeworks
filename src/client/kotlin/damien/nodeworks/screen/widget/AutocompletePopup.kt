package damien.nodeworks.screen.widget

import damien.nodeworks.network.CardSnapshot
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.components.MultilineTextField

/**
 * Context-aware autocompletion popup for the Lua script editor.
 * Analyzes the text before the cursor and suggests completions.
 */
class AutocompletePopup(
    private val font: Font,
    private val cards: List<CardSnapshot>
) {
    data class Suggestion(val insertText: String, val displayText: String)

    var visible: Boolean = false
        private set
    var suggestions: List<Suggestion> = emptyList()
        private set
    var selectedIndex: Int = 0
        private set

    private var popupX: Int = 0
    private var popupY: Int = 0
    private var prefix: String = ""
    private var lastFullText: String = ""
    private var scrollOffset: Int = 0
    private val maxVisible: Int = 8

    // Access the inner MultilineTextField via reflection (cached)
    private var textFieldAccessor: java.lang.reflect.Field? = null

    private fun getTextField(editor: MultiLineEditBox): MultilineTextField? {
        if (textFieldAccessor == null) {
            textFieldAccessor = MultiLineEditBox::class.java.getDeclaredField("textField")
            textFieldAccessor!!.isAccessible = true
        }
        return textFieldAccessor?.get(editor) as? MultilineTextField
    }

    /**
     * Update suggestions based on current editor state.
     * [forced] = true when triggered by Ctrl+Space (shows word completions).
     * [forced] = false on normal typing (only shows context completions like after `:` or `"`).
     */
    fun update(editor: MultiLineEditBox, editorX: Int, editorY: Int, forced: Boolean = false) {
        val textField = getTextField(editor) ?: run { hide(); return }
        val text = textField.value()
        // Use cursor position, but clamp to text length in case it's stale
        val cursor = minOf(textField.cursor(), text.length)
        lastFullText = text

        if (cursor <= 0) { hide(); return }

        val beforeCursor = text.substring(0, cursor)
        val newSuggestions = computeSuggestions(beforeCursor, forced)

        if (newSuggestions.isEmpty()) {
            hide()
            return
        }

        suggestions = newSuggestions
        selectedIndex = 0
        scrollOffset = 0
        visible = true
        prefix = extractPrefix(beforeCursor)

        // Position popup at the cursor's actual position
        val lineAtCursor = textField.getLineAtCursor()

        // Find the text on the current line before the cursor to calculate X offset
        var cursorXOffset = 0
        try {
            val lineView = textField.getLineView(lineAtCursor)
            val lineBegin = lineView.javaClass.getMethod("beginIndex").invoke(lineView) as Int
            val lineEnd = lineView.javaClass.getMethod("endIndex").invoke(lineView) as Int
            val cursorCol = (cursor - lineBegin).coerceIn(0, lineEnd - lineBegin)
            val lineTextBeforeCursor = text.substring(lineBegin, lineBegin + cursorCol)
            cursorXOffset = font.width(lineTextBeforeCursor)
        } catch (_: Exception) {}

        val scrollOffset = editor.scrollAmount().toInt()
        popupX = editorX + 4 + cursorXOffset
        popupY = editorY + (lineAtCursor + 1) * font.lineHeight + 4 - scrollOffset
    }

    fun hide() {
        visible = false
        suggestions = emptyList()
        selectedIndex = 0
    }

    private fun suggest(insertText: String, displayText: String = insertText) = Suggestion(insertText, displayText)

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

    /**
     * Accept the current suggestion. Returns the text to insert, or null if nothing selected.
     */
    fun accept(): String? {
        if (!visible || suggestions.isEmpty()) return null
        val suggestion = suggestions[selectedIndex]
        // Insert only the part after what's already typed
        val toInsert = suggestion.insertText.removePrefix(prefix)
        hide()
        return toInsert
    }

    fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (!visible || suggestions.isEmpty()) return

        val itemHeight = font.lineHeight + 2
        val visibleCount = minOf(suggestions.size, maxVisible)
        val popupWidth = suggestions.maxOf { font.width(it.displayText) } + 8
        val actualHeight = visibleCount * itemHeight + 4

        // Background
        graphics.fill(popupX, popupY, popupX + popupWidth, popupY + actualHeight, 0xEE1E1E1E.toInt())
        // Border
        graphics.fill(popupX, popupY, popupX + popupWidth, popupY + 1, 0xFF555555.toInt())
        graphics.fill(popupX, popupY + actualHeight - 1, popupX + popupWidth, popupY + actualHeight, 0xFF555555.toInt())
        graphics.fill(popupX, popupY, popupX + 1, popupY + actualHeight, 0xFF555555.toInt())
        graphics.fill(popupX + popupWidth - 1, popupY, popupX + popupWidth, popupY + actualHeight, 0xFF555555.toInt())

        // Scroll indicators
        if (scrollOffset > 0) {
            graphics.drawString(font, "\u25B2", popupX + popupWidth - 10, popupY + 1, 0xFF888888.toInt())
        }
        if (scrollOffset + visibleCount < suggestions.size) {
            graphics.drawString(font, "\u25BC", popupX + popupWidth - 10, popupY + actualHeight - font.lineHeight - 1, 0xFF888888.toInt())
        }

        for (i in 0 until visibleCount) {
            val suggestionIndex = scrollOffset + i
            val y = popupY + 2 + i * itemHeight
            if (suggestionIndex == selectedIndex) {
                graphics.fill(popupX + 1, y, popupX + popupWidth - 1, y + itemHeight, 0xFF3A5FCD.toInt())
            }
            val s = suggestions[suggestionIndex]
            val nameColor = if (suggestionIndex == selectedIndex) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
            val hintColor = if (suggestionIndex == selectedIndex) 0xFFBBBBBB.toInt() else 0xFF888888.toInt()
            // Draw insert text in bright color, parameter hint in dimmer color
            val nameWidth = font.width(s.insertText)
            graphics.drawString(font, s.insertText, popupX + 4, y + 1, nameColor)
            if (s.displayText != s.insertText) {
                val hint = s.displayText.removePrefix(s.insertText)
                graphics.drawString(font, hint, popupX + 4 + nameWidth, y + 1, hintColor)
            }
        }
    }

    private fun computeSuggestions(beforeCursor: String, forced: Boolean = false): List<Suggestion> {
        val trimmed = beforeCursor.trimEnd()
        val fullText = lastFullText

        // After card(" → suggest card types
        if (trimmed.endsWith("card(\"") || trimmed.endsWith("card( \"")) {
            return listOf(suggest("inventory"), suggest("energy"), suggest("fluid"))
        }

        // After card("inventory", " → suggest aliases
        val cardAliasPattern = Regex("""card\(\s*"(\w+)"\s*,\s*"$""")
        cardAliasPattern.find(trimmed)?.let { match ->
            val type = match.groupValues[1]
            return cards.filter { it.capability.type == type }
                .mapNotNull { it.alias }
                .distinct()
                .map { suggest(it) }
        }

        // After :face(" → suggest face names
        if (trimmed.endsWith(":face(\"") || trimmed.endsWith(":face( \"")) {
            return listOf("top", "bottom", "north", "south", "east", "west", "side").map { suggest(it) }
        }

        // After scheduler: or scheduler:partial → suggest scheduler methods
        val schedulerMatch = Regex("""scheduler:(\w*)$""").find(trimmed)
        if (schedulerMatch != null) {
            val partial = schedulerMatch.groupValues[1]
            val methods = listOf(
                suggest("tick(", "tick(fn: function) → number"),
                suggest("second(", "second(fn: function) → number"),
                suggest("delay(", "delay(ticks: number, fn: function) → number"),
                suggest("cancel(", "cancel(id: number)")
            )
            return if (partial.isEmpty()) methods else methods.filter { it.insertText.startsWith(partial) }
        }

        // After cardVar: or cardVar:partial → suggest card handle methods
        val methodMatch = Regex("""(\w+):(\w*)$""").find(trimmed)
        if (methodMatch != null) {
            val varName = methodMatch.groupValues[1]
            val partial = methodMatch.groupValues[2]
            val cardVars = extractCardVariables(fullText)
            if (varName in cardVars) {
                val methods = listOf(
                    suggest("move(", "move(dest: CardHandle, itemId: string, count: number) → number"),
                    suggest("count(", "count(itemId: string) → number"),
                    suggest("face(", "face(side: string) → CardHandle")
                )
                return if (partial.isEmpty()) methods else methods.filter { it.insertText.startsWith(partial) }
            }
            return emptyList()
        }

        // After string. or string.partial → suggest string library methods
        val stringMatch = Regex("""string\.(\w*)$""").find(trimmed)
        if (stringMatch != null) {
            val partial = stringMatch.groupValues[1]
            val methods = listOf(
                suggest("format(", "format(fmt: string, ...) → string"),
                suggest("len(", "len(s: string) → number"),
                suggest("sub(", "sub(s: string, i: number, j?: number) → string"),
                suggest("find(", "find(s: string, pattern: string) → number?"),
                suggest("match(", "match(s: string, pattern: string) → string?"),
                suggest("gmatch(", "gmatch(s: string, pattern: string) → function"),
                suggest("gsub(", "gsub(s: string, pattern: string, repl: string) → string"),
                suggest("rep(", "rep(s: string, n: number) → string"),
                suggest("reverse(", "reverse(s: string) → string"),
                suggest("upper(", "upper(s: string) → string"),
                suggest("lower(", "lower(s: string) → string"),
                suggest("byte(", "byte(s: string, i?: number) → number"),
                suggest("char(", "char(...: number) → string")
            )
            return if (partial.isEmpty()) methods else methods.filter { it.insertText.startsWith(partial) }
        }

        // After math. or math.partial → suggest math library methods
        val mathMatch = Regex("""math\.(\w*)$""").find(trimmed)
        if (mathMatch != null) {
            val partial = mathMatch.groupValues[1]
            val methods = listOf(
                suggest("floor(", "floor(x: number) → number"),
                suggest("ceil(", "ceil(x: number) → number"),
                suggest("abs(", "abs(x: number) → number"),
                suggest("max(", "max(x: number, ...) → number"),
                suggest("min(", "min(x: number, ...) → number"),
                suggest("sqrt(", "sqrt(x: number) → number"),
                suggest("random(", "random(m?: number, n?: number) → number"),
                suggest("pi", "pi: number"),
                suggest("huge", "huge: number"),
                suggest("sin(", "sin(x: number) → number"),
                suggest("cos(", "cos(x: number) → number"),
                suggest("fmod(", "fmod(x: number, y: number) → number")
            )
            return if (partial.isEmpty()) methods else methods.filter { it.insertText.startsWith(partial) }
        }

        // After table. or table.partial → suggest table library methods
        val tableMatch = Regex("""table\.(\w*)$""").find(trimmed)
        if (tableMatch != null) {
            val partial = tableMatch.groupValues[1]
            val methods = listOf(
                suggest("insert(", "insert(t: table, value: any)"),
                suggest("remove(", "remove(t: table, pos?: number) → any"),
                suggest("sort(", "sort(t: table, comp?: function)"),
                suggest("concat(", "concat(t: table, sep?: string) → string")
            )
            return if (partial.isEmpty()) methods else methods.filter { it.insertText.startsWith(partial) }
        }

        // Don't autocomplete after `local ` — user is declaring a new variable name
        if (Regex("""local\s+\w*$""").containsMatchIn(trimmed)) {
            return emptyList()
        }

        val lastWord = extractPrefix(beforeCursor)
        if (lastWord.length >= 2 || forced) {
            val apiFunctions = listOf(
                suggest("card", "card(type: string, alias: string) → CardHandle"),
                suggest("scheduler", "scheduler"),
                suggest("print", "print(message: any)"),
                suggest("clock", "clock() → number"),
                suggest("string", "string library"),
                suggest("math", "math library"),
                suggest("table", "table library"),
                suggest("tostring", "tostring(value: any) → string"),
                suggest("tonumber", "tonumber(value: any) → number?"),
                suggest("type", "type(value: any) → string"),
                suggest("pairs", "pairs(t: table) → function"),
                suggest("ipairs", "ipairs(t: table) → function"),
                suggest("select", "select(index: number, ...) → any"),
                suggest("unpack", "unpack(t: table) → ...")
            )
            val keywords = listOf("local", "function", "end",
                "if", "then", "else", "elseif", "for", "while", "do", "return",
                "true", "false", "nil", "not", "and", "or").map { suggest(it) }
            val userVars = extractVariableNames(fullText).map { suggest(it) }
            val all = (apiFunctions + keywords + userVars).distinctBy { it.insertText }
            val matches = all.filter { it.insertText.startsWith(lastWord) && it.insertText != lastWord }
            if (matches.isNotEmpty()) return matches
        }

        return emptyList()
    }

    /** Extracts all variable names from `local X = ...` declarations in the script. */
    private fun extractVariableNames(text: String): List<String> {
        val pattern = Regex("""local\s+(\w+)""")
        return pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    /** Extracts variable names that are assigned from card() or :face() calls. */
    private fun extractCardVariables(text: String): Set<String> {
        val result = mutableSetOf<String>()
        // local X = card(...)
        val cardPattern = Regex("""local\s+(\w+)\s*=\s*card\s*\(""")
        cardPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = something:face(...)
        val facePattern = Regex("""local\s+(\w+)\s*=\s*\w+:face\s*\(""")
        facePattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        return result
    }

    private fun extractPrefix(beforeCursor: String): String {
        val lastNonWord = beforeCursor.indexOfLast { !it.isLetterOrDigit() && it != '_' }
        return if (lastNonWord >= 0) beforeCursor.substring(lastNonWord + 1) else beforeCursor
    }
}
