package damien.nodeworks.screen.widget

import damien.nodeworks.network.CardSnapshot
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

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
    private val processableOutputs: List<String> = emptyList(),
    private val craftableOutputs: List<String> = emptyList(),
    private val localApis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo> = emptyList(),
    private val scripts: () -> Map<String, String> = { emptyMap() }
) {
    data class Suggestion(
        val insertText: String,
        val displayText: String,
        val snippetText: String? = null,
        val snippetCursor: Int = -1
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
    private var lastFullText: String = ""
    private var customPrefix: String? = null
    private var scrollOffset: Int = 0
    private val maxVisible: Int = 8

    // ========== Public API ==========

    fun update(text: String, cursorPos: Int, editorX: Int, editorY: Int, forced: Boolean = false, editorScrollY: Int = 0) {
        val cursor = minOf(cursorPos, text.length)
        lastFullText = text

        if (cursor <= 0) { hide(); return }

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

        popupX = editorX + 4 + cursorXOffset
        popupY = editorY + (lineAtCursor + 1) * font.lineHeight + 4 - editorScrollY
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

    data class AcceptResult(val deleteCount: Int, val insertText: String, val cursorOffset: Int = insertText.length)

    fun accept(): AcceptResult? {
        if (!visible || suggestions.isEmpty()) return null
        val suggestion = suggestions[selectedIndex]
        val deleteCount = prefix.length
        hide()
        if (suggestion.snippetText != null) {
            val cursorPos = if (suggestion.snippetCursor >= 0) suggestion.snippetCursor else suggestion.snippetText.length
            return AcceptResult(deleteCount, suggestion.snippetText, cursorPos)
        }
        // Auto-close parentheses: `func(` → `func()` with cursor between
        val text = suggestion.insertText
        if (text.endsWith("(")) {
            val closed = text + ")"
            return AcceptResult(deleteCount, closed, text.length) // cursor between ( and )
        }
        return AcceptResult(deleteCount, text, text.length)
    }

    fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (!visible || suggestions.isEmpty()) return

        val itemHeight = font.lineHeight + 2
        val visibleCount = minOf(suggestions.size, maxVisible)
        val popupWidth = suggestions.maxOf { font.width(it.displayText) } + 8
        val actualHeight = visibleCount * itemHeight + 4

        graphics.fill(popupX, popupY, popupX + popupWidth, popupY + actualHeight, 0xEE1E1E1E.toInt())
        graphics.fill(popupX, popupY, popupX + popupWidth, popupY + 1, 0xFF555555.toInt())
        graphics.fill(popupX, popupY + actualHeight - 1, popupX + popupWidth, popupY + actualHeight, 0xFF555555.toInt())
        graphics.fill(popupX, popupY, popupX + 1, popupY + actualHeight, 0xFF555555.toInt())
        graphics.fill(popupX + popupWidth - 1, popupY, popupX + popupWidth, popupY + actualHeight, 0xFF555555.toInt())

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
            val nameWidth = font.width(s.insertText)
            graphics.drawString(font, s.insertText, popupX + 4, y + 1, nameColor)
            if (s.displayText != s.insertText) {
                val hint = s.displayText.removePrefix(s.insertText)
                graphics.drawString(font, hint, popupX + 4 + nameWidth, y + 1, hintColor)
            }
        }
    }

    // ========== Helpers ==========

    private fun suggest(insertText: String, displayText: String = insertText) = Suggestion(insertText, displayText)

    private fun snippet(insertText: String, displayText: String, snippetText: String, cursorOffset: Int) =
        Suggestion(insertText, displayText, snippetText, cursorOffset)

    private fun fuzzy(query: String, suggestions: List<Suggestion>): List<Suggestion> {
        return if (query.isEmpty()) suggestions else FuzzyMatch.filter(query, suggestions)
    }

    private fun fuzzyStrings(query: String, items: List<String>): List<Suggestion> {
        return if (query.isEmpty()) items.map { suggest(it) }
        else items.map { suggest(it) }.let { FuzzyMatch.filter(query, it) }
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

    /** Check for type annotation context on the current line. */
    private fun findTypeAnnotationContext(line: String): CursorContext.TypeAnnotation? {
        // Pattern 1: `local varName: partial` or `local varName : partial`
        val localMatch = Regex("""\blocal\s+\w+\s*:\s*(\w*)$""").find(line)
        if (localMatch != null) return CursorContext.TypeAnnotation(localMatch.groupValues[1])

        // Pattern 2: `function(param: partial` or `function name(param: partial`
        // Also handles: `function name.method(param: partial` and `function(a: Type, b: partial`
        val funcParamMatch = Regex("""\bfunction\s*[\w.]*\s*\([^)]*\w+\s*:\s*(\w*)$""").find(line)
        if (funcParamMatch != null) return CursorContext.TypeAnnotation(funcParamMatch.groupValues[1])

        // Pattern 3: `function(...): partial` or `function name.method(...): partial`
        val returnMatch = Regex("""\bfunction\s*[\w.]*\s*\([^)]*\)\s*:\s*(\w*)$""").find(line)
        if (returnMatch != null) return CursorContext.TypeAnnotation(returnMatch.groupValues[1])

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

    /** Map of method names to their return types. Used for chain resolution and variable inference. */
    private val methodReturnTypes = mapOf(
        // CardHandle methods
        "face" to "CardHandle",
        "slots" to "CardHandle",
        "find" to "ItemsHandle",
        // Network methods
        "get" to "CardHandle",
        "craft" to "CraftBuilder",
        "shapeless" to "ItemsHandle",
        "var" to "VariableHandle"
    )

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

        return allReturnTypes[methodName]
    }

    /** Combined map of built-in + user-defined function return types. Rebuilt once per computeSuggestions call. */
    private var allReturnTypes: Map<String, String> = methodReturnTypes

    /** Build the combined return type map from built-in methods + all user function definitions. */
    private fun buildReturnTypeMap(fullText: String): Map<String, String> {
        val map = methodReturnTypes.toMutableMap()
        // Scan current script + all module scripts for function return type annotations
        val allTexts = mutableListOf(fullText)
        for ((_, scriptText) in scripts()) {
            allTexts.add(scriptText)
        }
        val funcPattern = Regex("""\bfunction\s+[\w.]*?(\w+)\s*\([^)]*\)\s*:\s*(\w+)""")
        for (text in allTexts) {
            for (match in funcPattern.findAll(text)) {
                map.putIfAbsent(match.groupValues[1], match.groupValues[2])
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

        // Simple `word.partial`
        val receiverMatch = Regex("""(\w+)$""").find(beforeDot) ?: return null
        return CursorContext.PropertyAccess(receiverMatch.groupValues[1], partial)
    }

    // ========== Symbol Table ==========

    private fun buildSymbolTable(fullText: String, beforeCursor: String): Map<String, String> {
        val symbols = mutableMapOf<String, String>()

        // 1. Explicit type annotations: local x: Type = ...
        Regex("""\blocal\s+(\w+)\s*:\s*(\w+)\??""").findAll(fullText).forEach {
            symbols[it.groupValues[1]] = it.groupValues[2]
        }

        // 2. Function parameter annotations — only from scopes the cursor is inside
        val funcPattern = Regex("""\bfunction\s*\w*\s*\(([^)]*)\)""")
        val endPattern = Regex("""\bend\b""")
        val scopeStack = mutableListOf<List<Pair<String, String>>>()
        for (line in beforeCursor.lines()) {
            for (match in funcPattern.findAll(line.trim())) {
                val paramTypes = mutableListOf<Pair<String, String>>()
                for (param in match.groupValues[1].split(",")) {
                    val parts = param.trim().split(":")
                    val name = parts[0].trim().split("\\s+".toRegex())[0]
                    val type = parts.getOrNull(1)?.trim()?.replace("?", "")
                    if (name.isNotEmpty() && type != null && type.isNotEmpty()) {
                        paramTypes.add(name to type)
                    }
                }
                scopeStack.add(paramTypes)
            }
            for (match in endPattern.findAll(line.trim())) {
                if (scopeStack.isNotEmpty()) scopeStack.removeLast()
            }
        }
        // Add params from currently open scopes
        for (scope in scopeStack) {
            for ((name, type) in scope) {
                symbols.putIfAbsent(name, type)
            }
        }

        // 3. Assignment inference via chain resolution (don't override explicit annotations)
        // Handles: network:get(), :face(), :slots(), :find(), network:craft(), etc.
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

        // General inference: local x = expr(...)
        Regex("""\blocal\s+(\w+)\s*=\s*(.+)""").findAll(fullText).forEach { match ->
            val varName = match.groupValues[1]
            if (varName !in symbols) {
                val rhs = match.groupValues[2].trim()
                if (rhs.endsWith(")")) {
                    // Try chain resolution first (handles method calls like :find, :face, etc.)
                    val chainType = resolveExpressionType(rhs)
                    if (chainType != null) {
                        symbols[varName] = chainType
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

        return symbols
    }

    // ========== Suggestion Generation ==========

    private fun computeSuggestions(beforeCursor: String, fullText: String, forced: Boolean): List<Suggestion> {
        allReturnTypes = buildReturnTypeMap(fullText)
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
            is CursorContext.ResolvedExports -> fuzzy(ctx.partial, ctx.exports)
            is CursorContext.MethodCall -> suggestMethodCall(ctx, symbols, fullText)
            is CursorContext.PropertyAccess -> suggestPropertyAccess(ctx, symbols, fullText)
            is CursorContext.Word -> suggestWord(ctx.partial, fullText, beforeCursor, symbols, forced)
            is CursorContext.None -> emptyList()
        }
    }

    private fun suggestStringArg(ctx: CursorContext.StringArg): List<Suggestion> {
        customPrefix = ctx.partial
        return when {
            ctx.funcExpr.endsWith("network:get") -> {
                val cardSuggestions = cards
                    .map { it.effectiveAlias to it.capability.type }
                    .distinct()
                    .map { suggest(it.first, "${it.first} (${it.second})") }
                FuzzyMatch.filter(ctx.partial, cardSuggestions)
            }
            ctx.funcExpr.endsWith("network:route") -> {
                val storageSuggestions = cards
                    .filter { it.capability.type == "storage" }
                    .map { it.effectiveAlias to it.capability.type }
                    .distinct()
                    .map { suggest(it.first, "${it.first} (storage)") }
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
                    suggest(name, "$name ($typeLabel)")
                }
                FuzzyMatch.filter(ctx.partial, suggestions)
            }
            ctx.funcExpr.endsWith(":face") -> {
                val faces = listOf("top", "bottom", "north", "south", "east", "west", "side")
                fuzzyStrings(ctx.partial, faces)
            }
            ctx.funcExpr.endsWith("network:handle") -> {
                fuzzyStrings(ctx.partial, localApiNames)
            }
            ctx.funcExpr.endsWith("require") -> {
                val scriptNames = scripts().keys.filter { it != "main" }.toList()
                fuzzyStrings(ctx.partial, scriptNames)
            }
            else -> emptyList()
        }
    }

    private val knownTypes = listOf(
        Suggestion("string", "string"),
        Suggestion("number", "number"),
        Suggestion("boolean", "boolean"),
        Suggestion("any", "any"),
        Suggestion("ItemsHandle", "ItemsHandle — item reference from find/craft"),
        Suggestion("CardHandle", "CardHandle — IO/Storage card from network:get"),
        Suggestion("Job", "Job — processing handler context from network:handle"),
        Suggestion("CraftBuilder", "CraftBuilder — from network:craft(), chain with :connect()"),
        Suggestion("NumberVariableHandle", "NumberVariableHandle — number variable from network:var"),
        Suggestion("StringVariableHandle", "StringVariableHandle — string variable from network:var"),
        Suggestion("BoolVariableHandle", "BoolVariableHandle — bool variable from network:var")
    )

    private fun suggestTypeAnnotation(partial: String): List<Suggestion> {
        // Don't suggest if the partial already exactly matches a known type
        if (knownTypes.any { it.insertText == partial }) return emptyList()
        customPrefix = partial
        return fuzzy(partial, knownTypes)
    }

    private fun suggestTag(partial: String): List<Suggestion> {
        customPrefix = partial
        return fuzzyStrings(partial, itemTags).take(20)
    }

    private fun suggestMethodCall(ctx: CursorContext.MethodCall, symbols: Map<String, String>, fullText: String): List<Suggestion> {
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

    private fun suggestPropertyAccess(ctx: CursorContext.PropertyAccess, symbols: Map<String, String>, fullText: String): List<Suggestion> {
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

    private fun suggestWord(partial: String, fullText: String, beforeCursor: String, symbols: Map<String, String>, forced: Boolean): List<Suggestion> {
        if (partial.length < 2 && !forced) return emptyList()

        val apiFunctions = listOf(
            suggest("scheduler", "scheduler"),
            suggest("network", "network"),
            suggest("print(", "print(message: any)"),
            suggest("clock(", "clock() → number"),
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
        val userVars = (extractVariableNames(fullText) + extractFunctionParams(beforeCursor)).distinct().map { name ->
            val type = symbols[name]
            if (type != null) suggest(name, "$name: $type") else suggest(name)
        }
        val userFuncs = extractFunctions(fullText).map { f ->
            val retStr = if (f.returnType != null) " → ${f.returnType}" else ""
            suggest("${f.name}(", "${f.name}(${f.params})$retStr")
        }
        val requireSuggest = if (scripts().size > 1) listOf(suggest("require(", "require(module: string) → table")) else emptyList()
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
            suggest("get(", "get(alias: string) → CardHandle"),
            suggest("getAll(", "getAll(type: string) → CardHandle[]"),
            suggest("find(", "find(filter: string) → ItemsHandle?"),
            suggest("findEach(", "findEach(filter: string) → ItemsHandle[]"),
            suggest("count(", "count(filter: string) → number"),
            suggest("insert(", "insert(items: ItemsHandle, count?: number) → number"),
            suggest("craft(", "craft(id: string, count?: number) → CraftBuilder"),
            suggest("shapeless(", "shapeless(item: string, count?: number, ...) → ItemsHandle?"),
            run {
                val body = "route(\"\", function(item: ItemsHandle)\n    return true\nend)"
                snippet("route(", "route(alias, fn(item) → boolean)", body, body.indexOf("\"\"") + 1)
            },
            suggest("var(", "var(name: string) → VariableHandle"),
            suggest("handle(", "handle(cardName: string, fn: function(job, ...))"),
            suggest("debug(", "debug() — print network topology")
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
            snippet("delay(", "delay(ticks: number, fn: function) → number", delayBody, delayBody.indexOf("\n    \n") + 5),
            suggest("cancel(", "cancel(id: number)")
        )
        return fuzzy(partial, methods)
    }

    // ========== Type-based methods and properties ==========

    private fun suggestMethodsForType(type: String, partial: String): List<Suggestion> {
        val methods = when (type) {
            "CardHandle" -> listOf(
                suggest("find(", "find(filter: string) → ItemsHandle?"),
                suggest("findEach(", "findEach(filter: string) → ItemsHandle[]"),
                suggest("insert(", "insert(items: ItemsHandle, count?: number) → number"),
                suggest("count(", "count(filter: string) → number"),
                suggest("face(", "face(side: string) → CardHandle"),
                suggest("slots(", "slots(...: number) → CardHandle")
            )
            "ItemsHandle" -> listOf(
                suggest("hasTag(", "hasTag(tag: string) → boolean"),
                suggest("matches(", "matches(filter: string) → boolean")
            )
            "Job" -> listOf(suggest("pull(", "pull(card: CardHandle, ...) — wait for outputs"))
            "CraftBuilder" -> {
                val connectBody = "connect(function(item: ItemsHandle)\n    \nend)"
                listOf(
                    snippet("connect(", "connect(fn(item: ItemsHandle))", connectBody, connectBody.indexOf("\n    \n") + 5),
                    suggest("store(", "store() — send result to network storage")
                )
            }
            "VariableHandle" -> variableHandleMethods()
            "NumberVariableHandle" -> numberVariableHandleMethods()
            "StringVariableHandle" -> stringVariableHandleMethods()
            "BoolVariableHandle" -> boolVariableHandleMethods()
            else -> emptyList()
        }
        return fuzzy(partial, methods)
    }

    private fun suggestPropertiesForType(type: String, partial: String): List<Suggestion> {
        val props = when (type) {
            "ItemsHandle" -> listOf(
                suggest("id", "id: string"),
                suggest("name", "name: string"),
                suggest("count", "count: number"),
                suggest("stackable", "stackable: boolean"),
                suggest("maxStackSize", "maxStackSize: number"),
                suggest("hasData", "hasData: boolean")
            )
            else -> emptyList()
        }
        return fuzzy(partial, props)
    }

    private val baseVariableMethods = listOf(
        "get(" to "get() → value",
        "set(" to "set(value) — set variable value",
        "cas(" to "cas(expected, new) → boolean",
        "type(" to "type() → string",
        "name(" to "name() → string"
    )

    private fun variableHandleMethods(): List<Suggestion> {
        return baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("increment(", "increment(n: number) → number"),
            suggest("decrement(", "decrement(n: number) → number"),
            suggest("min(", "min(n: number) → number"),
            suggest("max(", "max(n: number) → number"),
            suggest("append(", "append(s: string) → string"),
            suggest("length(", "length() → number"),
            suggest("clear(", "clear()"),
            suggest("toggle(", "toggle() → boolean"),
            suggest("tryLock(", "tryLock() → boolean"),
            suggest("unlock(", "unlock()")
        )
    }

    private fun numberVariableHandleMethods(): List<Suggestion> {
        return baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("increment(", "increment(n: number) → number"),
            suggest("decrement(", "decrement(n: number) → number"),
            suggest("min(", "min(n: number) → number"),
            suggest("max(", "max(n: number) → number")
        )
    }

    private fun stringVariableHandleMethods(): List<Suggestion> {
        return baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("append(", "append(s: string) → string"),
            suggest("length(", "length() → number"),
            suggest("clear(", "clear()")
        )
    }

    private fun boolVariableHandleMethods(): List<Suggestion> {
        return baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("toggle(", "toggle() → boolean"),
            suggest("tryLock(", "tryLock() → boolean"),
            suggest("unlock(", "unlock()")
        )
    }

    // ========== Library methods ==========

    private fun suggestStringMethods(partial: String): List<Suggestion> {
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
        return fuzzy(partial, methods)
    }

    private fun suggestMathMethods(partial: String): List<Suggestion> {
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
        return fuzzy(partial, methods)
    }

    private fun suggestTableMethods(partial: String): List<Suggestion> {
        val methods = listOf(
            suggest("insert(", "insert(t: table, value: any)"),
            suggest("remove(", "remove(t: table, pos?: number) → any"),
            suggest("sort(", "sort(t: table, comp?: function)"),
            suggest("concat(", "concat(t: table, sep?: string) → string")
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
        val cardName = handleFnMatch.groupValues[1]
        val partial = handleFnMatch.groupValues[2]
        customPrefix = partial
        val api = localApis.firstOrNull { it.name == cardName }
        val params = buildString {
            append("job: Job")
            if (api != null) {
                for ((itemId, _) in api.inputs) {
                    append(", ")
                    append(itemIdToParamName(itemId))
                    append(": ItemsHandle")
                }
            }
        }
        val body = "function($params)\n    \nend"
        val cursorPos = body.indexOf("\n    \n") + 5
        return listOf(snippet("function(", "function($params)", body, cursorPos))
    }

    // ========== Utility functions ==========

    private fun extractVariableNames(text: String): List<String> {
        val pattern = Regex("""\blocal\s+(\w+)""")
        return pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()
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
        // Match: function name(params): ReturnType  or  local function name(params): ReturnType
        val pattern = Regex("""\bfunction\s+(\w+)\s*\(([^)]*)\)\s*(?::\s*(\w+)\??\s*)?""")
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
        // function tableVar.method(params): ReturnType
        val funcPattern = Regex("""\bfunction\s+${Regex.escape(tableVar)}\.(\w+)\s*\(([^)]*)\)\s*(?::\s*(\w+)\??\s*)?""")
        funcPattern.findAll(text).forEach { m ->
            val funcName = m.groupValues[1]
            val params = m.groupValues[2].trim().split(",").joinToString(", ") { it.trim() }
            val retType = m.groupValues[3].ifEmpty { null }
            val retStr = if (retType != null) " → $retType" else ""
            exports.add(suggest("$funcName(", "$funcName($params)$retStr"))
        }
        // tableVar.field = value
        val fieldPattern = Regex("""${Regex.escape(tableVar)}\.(\w+)\s*=""")
        fieldPattern.findAll(text).forEach { m ->
            val fieldName = m.groupValues[1]
            if (exports.none { it.insertText.startsWith("$fieldName(") }) {
                exports.add(suggest(fieldName))
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

    private fun itemIdToParamName(itemId: String): String {
        val shortId = itemId.substringAfter(':')
        val parts = shortId.split('_')
        return parts.mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    private fun extractPrefix(beforeCursor: String): String {
        val lastNonWord = beforeCursor.indexOfLast { !it.isLetterOrDigit() && it != '_' }
        return if (lastNonWord >= 0) beforeCursor.substring(lastNonWord + 1) else beforeCursor
    }
}
