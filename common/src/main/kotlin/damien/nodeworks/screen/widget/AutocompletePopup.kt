package damien.nodeworks.screen.widget

import damien.nodeworks.network.CardSnapshot
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

/**
 * Context-aware autocompletion popup for the Lua script editor.
 * Analyzes the text before the cursor and suggests completions.
 */
class AutocompletePopup(
    private val font: Font,
    private val cards: List<CardSnapshot>,
    private val itemTags: List<String> = emptyList(),
    private val variables: List<Pair<String, Int>> = emptyList(), // name to type ordinal (0=number, 1=string, 2=bool)
    private val processingOutputs: List<String> = emptyList(), // output item IDs from Processing API Cards
    private val scripts: () -> Map<String, String> = { emptyMap() }
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
    private var customPrefix: String? = null
    private var scrollOffset: Int = 0
    private val maxVisible: Int = 8

    /**
     * Update suggestions based on current editor state.
     * [forced] = true when triggered by Ctrl+Space (shows word completions).
     * [forced] = false on normal typing (only shows context completions like after `:` or `"`).
     */
    /**
     * @param editorScrollY the editor's current scroll offset in pixels (subtracted from Y position)
     */
    fun update(text: String, cursorPos: Int, editorX: Int, editorY: Int, forced: Boolean = false, editorScrollY: Int = 0) {
        // Clamp cursor to text length in case it's stale
        val cursor = minOf(cursorPos, text.length)
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
        prefix = customPrefix ?: extractPrefix(beforeCursor)
        customPrefix = null

        // Find which line and column the cursor is on
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

    private val knownTypes = listOf(
        Suggestion("ItemsHandle", "ItemsHandle — item reference from find/craft"),
        Suggestion("CardHandle", "CardHandle — IO/Storage card from network:get"),
        Suggestion("ProcessBuilder", "ProcessBuilder — from network:process(), chain with :connect()"),
        Suggestion("NumberVariableHandle", "NumberVariableHandle — number variable from network:var"),
        Suggestion("StringVariableHandle", "StringVariableHandle — string variable from network:var"),
        Suggestion("BoolVariableHandle", "BoolVariableHandle — bool variable from network:var")
    )

    private fun suggest(insertText: String, displayText: String = insertText) = Suggestion(insertText, displayText)

    /** Filter suggestions using fuzzy matching, or return all if query is empty. */
    private fun fuzzy(query: String, suggestions: List<Suggestion>): List<Suggestion> {
        return if (query.isEmpty()) suggestions else FuzzyMatch.filter(query, suggestions)
    }

    /** Filter simple string list using fuzzy matching, return as suggestions. */
    private fun fuzzyStrings(query: String, items: List<String>): List<Suggestion> {
        return if (query.isEmpty()) items.map { suggest(it) }
        else items.map { suggest(it) }.let { FuzzyMatch.filter(query, it) }
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

    data class AcceptResult(val deleteCount: Int, val insertText: String)

    /**
     * Accept the current suggestion. Returns how many characters to delete (the typed prefix)
     * and the full text to insert, or null if nothing selected.
     */
    fun accept(): AcceptResult? {
        if (!visible || suggestions.isEmpty()) return null
        val suggestion = suggestions[selectedIndex]
        val deleteCount = prefix.length
        hide()
        return AcceptResult(deleteCount, suggestion.insertText)
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

        // Type annotation context — only in these specific positions:
        // 1. Function parameter: (param: Type  or  (param: Type, param2: Type
        // 2. Local variable: local x: Type
        // 3. Function return type: function(...)): Type  or  function name(...)): Type
        val typeContextPattern = Regex("""(?:\(\s*(?:\w+\s*:\s*\w+\??\s*,\s*)*\w+\s*:\s*|\blocal\s+\w+\s*:\s*|\bfunction\s*\w*\s*\([^)]*\)\s*:\s*)""")

        val typeAnnotationMatch = Regex("""(?:\(\s*(?:\w+\s*:\s*\w+\??\s*,\s*)*\w+\s*:\s*|\blocal\s+\w+\s*:\s*|\bfunction\s*\w*\s*\([^)]*\)\s*:\s*)([A-Z]\w*)$""").find(trimmed)
        if (typeAnnotationMatch != null) {
            val partial = typeAnnotationMatch.groupValues[1]
            customPrefix = partial
            return fuzzy(partial, knownTypes)
        }
        // Also match when just the colon was typed with no partial yet
        val typeAnnotationEmpty = Regex("""(?:\(\s*(?:\w+\s*:\s*\w+\??\s*,\s*)*\w+\s*:\s*|\blocal\s+\w+\s*:\s*|\bfunction\s*\w*\s*\([^)]*\)\s*:\s*)$""").find(trimmed)
        if (typeAnnotationEmpty != null) {
            customPrefix = ""
            return knownTypes
        }

        // After network:get("partial or network:route("partial → suggest aliases with type hints
        val aliasContextMatch = Regex("""network:(?:get|route)\(\s*"(\w*)$""").find(trimmed)
        if (aliasContextMatch != null) {
            val partial = aliasContextMatch.groupValues[1]
            customPrefix = partial
            return cards
                .map { card -> card.effectiveAlias to card.capability.type }
                .distinct()
                .map { suggest(it.first, "${it.first} (${it.second})") }
                .let { FuzzyMatch.filter(partial, it) }
        }

        // After network:getAll("partial → suggest types
        val getAllTypeMatch = Regex("""network:getAll\(\s*"(\w*)$""").find(trimmed)
        if (getAllTypeMatch != null) {
            val partial = getAllTypeMatch.groupValues[1]
            customPrefix = partial
            val types = listOf("io", "storage")
            return fuzzyStrings(partial, types)
        }

        // After network:handle("partial → suggest Processing API Card names
        val handleMatch = Regex("""network:handle\(\s*"([\w]*)$""").find(trimmed)
        if (handleMatch != null) {
            val partial = handleMatch.groupValues[1]
            customPrefix = partial
            return fuzzyStrings(partial, processingOutputs)
        }

        // After network:var("partial → suggest variable names with type hints
        val varMatch = Regex("""network:var\(\s*"(\w*)$""").find(trimmed)
        if (varMatch != null) {
            val partial = varMatch.groupValues[1]
            customPrefix = partial
            val typeLabels = arrayOf("number", "string", "bool")
            val suggestions = variables.map { (name, typeOrd) ->
                val typeLabel = typeLabels.getOrElse(typeOrd) { "unknown" }
                suggest(name, "$name ($typeLabel)")
            }
            return FuzzyMatch.filter(partial, suggestions)
        }

        // After :face("partial → suggest face names
        val faceMatch = Regex(""":face\(\s*"(\w*)$""").find(trimmed)
        if (faceMatch != null) {
            val partial = faceMatch.groupValues[1]
            customPrefix = partial
            val faces = listOf("top", "bottom", "north", "south", "east", "west", "side")
            return fuzzyStrings(partial, faces)
        }

        // After network: or network:partial → suggest network methods
        val networkMatch = Regex("""network:(\w*)$""").find(trimmed)
        if (networkMatch != null) {
            val partial = networkMatch.groupValues[1]
            val methods = listOf(
                suggest("get(", "get(alias: string) → CardHandle"),
                suggest("getAll(", "getAll(type: string) → CardHandle[]"),
                suggest("find(", "find(filter: string) → ItemsHandle?"),
                suggest("findStack(", "findStack(filter: string) → ItemsHandle?"),
                suggest("findAll(", "findAll(filter: string) → ItemsHandle[]"),
                suggest("count(", "count(filter: string) → number"),
                suggest("insert(", "insert(items: ItemsHandle, count?: number) → number"),
                suggest("craft(", "craft(recipe: string, count?: number) → ItemsHandle?"),
                suggest("shapeless(", "shapeless(item: string, count?: number, ...) → ItemsHandle?"),
                suggest("route(", "route(alias: string, fn: function(ItemsHandle) → boolean)"),
                suggest("onInsert(", "onInsert(fn: function(ItemsHandle) → CardHandle?)"),
                suggest("var(", "var(name: string) → VariableHandle"),
                suggest("handle(", "handle(cardName: string, fn: function(job, ItemsHandle...))"),
                suggest("process(", "process(id, count?, ...):connect(fn(ItemsHandle...))")
            )
            return fuzzy(partial, methods)
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
            return fuzzy(partial, methods)
        }

        // After :face("..."):partial or :slots(...):partial → suggest card handle methods (chained call)
        val chainedMatch = Regex(""":(face\(\s*"[^"]*"\s*\)|slots\([^)]*\))\s*:(\w*)$""").find(trimmed)
        if (chainedMatch != null) {
            val partial = chainedMatch.groupValues[2]
            return cardHandleMethods(partial)
        }

        // ProcessBuilder method chain: after network:process(...): or on next line
        val processChainMatch = Regex("""network:process\([^)]*\)\s*:(\w*)$""").find(trimmed)
            ?: Regex("""network:process\([^)]*\)\s*\n\s*:(\w*)$""").find(trimmed)
        if (processChainMatch != null) {
            val partial = processChainMatch.groupValues[1]
            customPrefix = partial
            return fuzzy(partial, listOf(
                suggest("connect(", "connect(fn: function(ItemsHandle...)) — callback when done")
            ))
        }

        // After # or #partial → suggest item tags (must be before method match since #c: looks like a method call)
        val tagMatch = Regex("""#([\w:./]*)$""").find(trimmed)
        if (tagMatch != null) {
            val partial = tagMatch.groupValues[1]
            customPrefix = partial
            return fuzzyStrings(partial, itemTags).take(20)
        }

        // After someVar: or someVar:partial → suggest methods based on variable type
        val methodMatch = Regex("""(\w+):(\w*)$""").find(trimmed)
        if (methodMatch != null) {
            val varName = methodMatch.groupValues[1]
            val partial = methodMatch.groupValues[2]
            val type = resolveVariableType(varName, fullText)
            if (type != null) {
                return methodsForType(type, partial)
            }
            return emptyList()
        }

        // After someVar.partial → suggest properties based on variable type
        val propMatch = Regex("""(\w+)\.(\w*)$""").find(trimmed)
        if (propMatch != null) {
            val varName = propMatch.groupValues[1]
            val partial = propMatch.groupValues[2]
            val type = resolveVariableType(varName, fullText)
            if (type != null) {
                return propertiesForType(type, partial)
            }
            // Check if it's a require'd module (handled elsewhere, but don't fall through to empty)
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
            return fuzzy(partial, methods)
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
            return fuzzy(partial, methods)
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
            return fuzzy(partial, methods)
        }

        // After coroutine. or coroutine.partial → suggest coroutine library methods
        val coroutineMatch = Regex("""coroutine\.(\w*)$""").find(trimmed)
        if (coroutineMatch != null) {
            val partial = coroutineMatch.groupValues[1]
            val methods = listOf(
                suggest("create(", "create(fn: function) → thread"),
                suggest("resume(", "resume(co: thread, ...) → boolean, ..."),
                suggest("yield(", "yield(...) — pause coroutine"),
                suggest("status(", "status(co: thread) → string"),
                suggest("wrap(", "wrap(fn: function) → function"),
                suggest("isyieldable(", "isyieldable() → boolean")
            )
            return fuzzy(partial, methods)
        }

        // After require("partial → suggest available script names
        val requireMatch = Regex("""require\(\s*"(\w*)$""").find(trimmed)
        if (requireMatch != null) {
            val partial = requireMatch.groupValues[1]
            customPrefix = partial
            val scriptNames = scripts().keys.filter { it != "main" }.toList()
            return fuzzyStrings(partial, scriptNames)
        }

        // After moduleVar.partial → suggest exports from the required module
        val moduleDotMatch = Regex("""(\w+)\.(\w*)$""").find(trimmed)
        if (moduleDotMatch != null) {
            val varName = moduleDotMatch.groupValues[1]
            val partial = moduleDotMatch.groupValues[2]
            val moduleExports = getModuleExports(fullText, varName)
            if (moduleExports.isNotEmpty()) {
                return fuzzy(partial, moduleExports)
            }
        }

        // Don't autocomplete after `local ` — user is declaring a new variable name
        if (Regex("""local\s+\w*$""").containsMatchIn(trimmed)) {
            return emptyList()
        }

        val lastWord = extractPrefix(beforeCursor)
        if (lastWord.length >= 2 || forced) {
            val apiFunctions = listOf(
                suggest("scheduler", "scheduler"),
                suggest("network", "network"),
                suggest("print", "print(message: any)"),
                suggest("clock", "clock() → number"),
                suggest("string", "string library"),
                suggest("math", "math library"),
                suggest("table", "table library"),
                suggest("coroutine", "coroutine library"),
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
            val userFuncs = extractFunctionNames(fullText).map { suggest("$it(", "$it(...)") }
            val requireSuggest = if (scripts().size > 1) listOf(suggest("require", "require(module: string) → table")) else emptyList()
            val all = (apiFunctions + requireSuggest + keywords + userVars + userFuncs).distinctBy { it.insertText }
            val matches = FuzzyMatch.filter(lastWord, all).filter { it.insertText != lastWord }
            if (matches.isNotEmpty()) return matches
        }

        return emptyList()
    }

    /**
     * Resolves the type of a variable by checking:
     * 1. Explicit type annotations (Luau-style)
     * 2. Inference from assignment context (network:get, :find, etc.)
     * 3. Function return type annotations for call results
     */
    private fun resolveVariableType(varName: String, text: String): String? {
        // 1. Explicit annotation: local x: TypeName = ...
        val localAnnotation = Regex("""local\s+${Regex.escape(varName)}\s*:\s*(\w+)\??\s*=""").find(text)
        if (localAnnotation != null) return localAnnotation.groupValues[1]

        // 2. Function parameter annotation: function foo(varName: TypeName)
        val paramAnnotation = Regex("""\(\s*(?:\w+\s*:\s*\w+\??\s*,\s*)*${Regex.escape(varName)}\s*:\s*(\w+)\??""").find(text)
        if (paramAnnotation != null) return paramAnnotation.groupValues[1]

        // 3. Assignment from a function with a known return type: local x = myFunc(...)
        val funcCallMatch = Regex("""local\s+${Regex.escape(varName)}\s*=\s*(\w+)\s*\(""").find(text)
        if (funcCallMatch != null) {
            val funcName = funcCallMatch.groupValues[1]
            val returnType = extractFunctionReturnType(funcName, text)
            if (returnType != null) return returnType
        }

        // 4. Inference from assignment context
        if (varName in extractCardVariables(text)) return "CardHandle"
        if (varName in extractItemsHandleVariables(text)) return "ItemsHandle"
        if (Regex("""local\s+${Regex.escape(varName)}\s*=\s*network:process\s*\(""").containsMatchIn(text)) return "ProcessBuilder"
        val varType = resolveVariableHandleType(varName, text)
        if (varType != null) return varType

        return null
    }

    /** Extracts the return type annotation from a function definition. */
    private fun extractFunctionReturnType(funcName: String, text: String): String? {
        // function funcName(...): ReturnType
        val pattern = Regex("""function\s+${Regex.escape(funcName)}\s*\([^)]*\)\s*:\s*(\w+)\??""")
        val match = pattern.find(text)
        return match?.groupValues?.get(1)
    }

    /** Returns method suggestions for a known type. */
    private fun methodsForType(type: String, partial: String): List<Suggestion> {
        val methods = when (type) {
            "CardHandle" -> cardHandleMethods("")
            "ItemsHandle" -> itemsHandleMethods("")
            "ProcessBuilder" -> listOf(suggest("connect(", "connect(fn: function(ItemsHandle...)) — callback when done"))
            "VariableHandle" -> variableHandleMethods("")
            "NumberVariableHandle" -> numberVariableHandleMethods("")
            "StringVariableHandle" -> stringVariableHandleMethods("")
            "BoolVariableHandle" -> boolVariableHandleMethods("")
            else -> emptyList()
        }
        return fuzzy(partial, methods)
    }

    /** Returns property suggestions for a known type. */
    private fun propertiesForType(type: String, partial: String): List<Suggestion> {
        val props = when (type) {
            "ItemsHandle" -> itemsHandleProperties("")
            else -> emptyList()
        }
        return fuzzy(partial, props)
    }

    private fun cardHandleMethods(partial: String): List<Suggestion> {
        val methods = listOf(
            suggest("find(", "find(filter: string) → ItemsHandle?"),
            suggest("findStack(", "findStack(filter: string) → ItemsHandle?"),
            suggest("findAll(", "findAll(filter: string) → ItemsHandle[]"),
            suggest("insert(", "insert(items: ItemsHandle, count?: number) → number"),
            suggest("count(", "count(filter: string) → number"),
            suggest("face(", "face(side: string) → CardHandle"),
            suggest("slots(", "slots(...: number) → CardHandle")
        )
        return fuzzy(partial, methods)
    }

    private fun itemsHandleMethods(partial: String): List<Suggestion> {
        val methods = listOf(
            suggest("hasTag(", "hasTag(tag: string) → boolean"),
            suggest("matches(", "matches(filter: string) → boolean")
        )
        return fuzzy(partial, methods)
    }

    private fun itemsHandleProperties(partial: String): List<Suggestion> {
        val props = listOf(
            suggest("id", "id: string"),
            suggest("name", "name: string"),
            suggest("count", "count: number"),
            suggest("stackable", "stackable: boolean"),
            suggest("maxStackSize", "maxStackSize: number"),
            suggest("hasData", "hasData: boolean")
        )
        return fuzzy(partial, props)
    }

    private val baseVariableMethods = listOf(
        "get(" to "get() → value",
        "set(" to "set(value) — set variable value",
        "cas(" to "cas(expected, new) → boolean",
        "type(" to "type() → string",
        "name(" to "name() → string"
    )

    private fun variableHandleMethods(partial: String): List<Suggestion> {
        // Fallback: show all methods when type is unknown
        val methods = baseVariableMethods.map { suggest(it.first, it.second) } +
            listOf(
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
        return fuzzy(partial, methods)
    }

    private fun numberVariableHandleMethods(partial: String): List<Suggestion> {
        val methods = baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("increment(", "increment(n: number) → number"),
            suggest("decrement(", "decrement(n: number) → number"),
            suggest("min(", "min(n: number) → number"),
            suggest("max(", "max(n: number) → number")
        )
        return fuzzy(partial, methods)
    }

    private fun stringVariableHandleMethods(partial: String): List<Suggestion> {
        val methods = baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("append(", "append(s: string) → string"),
            suggest("length(", "length() → number"),
            suggest("clear(", "clear()")
        )
        return fuzzy(partial, methods)
    }

    private fun boolVariableHandleMethods(partial: String): List<Suggestion> {
        val methods = baseVariableMethods.map { suggest(it.first, it.second) } + listOf(
            suggest("toggle(", "toggle() → boolean"),
            suggest("tryLock(", "tryLock() → boolean"),
            suggest("unlock(", "unlock()")
        )
        return fuzzy(partial, methods)
    }

    /** Extracts all variable names from `local X = ...` declarations in the script. */
    private fun extractVariableNames(text: String): List<String> {
        val pattern = Regex("""local\s+(\w+)""")
        return pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    /** Extracts variable names that are assigned from network:get() or :face() calls. */
    private fun extractCardVariables(text: String): Set<String> {
        val result = mutableSetOf<String>()
        // local X = network:get(...)
        val getPattern = Regex("""local\s+(\w+)\s*=\s*network:get\s*\(""")
        getPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = something:face(...)
        val facePattern = Regex("""local\s+(\w+)\s*=\s*\w+:face\s*\(""")
        facePattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = something:slots(...)
        val slotsPattern = Regex("""local\s+(\w+)\s*=\s*\w+:slots\s*\(""")
        slotsPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        return result
    }

    /** Extracts variable names assigned from find(), craft(), or shapeless() — these are ItemsHandle. */
    private fun extractItemsHandleVariables(text: String): Set<String> {
        val result = mutableSetOf<String>()
        // local X = something:find(...) — but NOT findAll
        val findPattern = Regex("""local\s+(\w+)\s*=\s*\w+:find\s*\(""")
        findPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = something:findStack(...)
        val findStackPattern = Regex("""local\s+(\w+)\s*=\s*\w+:findStack\s*\(""")
        findStackPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = network:find(...)
        val netFindPattern = Regex("""local\s+(\w+)\s*=\s*network:find\s*\(""")
        netFindPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = network:findStack(...)
        val netFindStackPattern = Regex("""local\s+(\w+)\s*=\s*network:findStack\s*\(""")
        netFindStackPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = network:craft(...)
        val craftPattern = Regex("""local\s+(\w+)\s*=\s*network:craft\s*\(""")
        craftPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local X = network:shapeless(...)
        val shapelessPattern = Regex("""local\s+(\w+)\s*=\s*network:shapeless\s*\(""")
        shapelessPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        return result
    }

    /**
     * Resolves a variable assigned from network:var("name") to its typed handle.
     * Returns "NumberVariableHandle", "StringVariableHandle", "BoolVariableHandle", or null.
     */
    private fun resolveVariableHandleType(varName: String, text: String): String? {
        // Match: local varName = network:var("someName")
        val pattern = Regex("""local\s+${Regex.escape(varName)}\s*=\s*network:var\s*\(\s*"(\w+)"\s*\)""")
        val match = pattern.find(text) ?: return null
        val netVarName = match.groupValues[1]
        // Look up the variable's type from the known variables list
        val typeOrd = variables.firstOrNull { it.first == netVarName }?.second
        return when (typeOrd) {
            0 -> "NumberVariableHandle"
            1 -> "StringVariableHandle"
            2 -> "BoolVariableHandle"
            else -> "VariableHandle" // fallback: show all methods
        }
    }

    /** Extracts top-level and local function names from the script. */
    private fun extractFunctionNames(text: String): List<String> {
        val result = mutableListOf<String>()
        // function myFunc(...)
        val globalPattern = Regex("""^function\s+(\w+)\s*\(""", RegexOption.MULTILINE)
        globalPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        // local function myFunc(...)
        val localPattern = Regex("""local\s+function\s+(\w+)\s*\(""")
        localPattern.findAll(text).forEach { result.add(it.groupValues[1]) }
        return result.distinct()
    }

    /**
     * Gets exported functions/fields from a required module.
     * Looks for `local varName = require("module")` in the current script,
     * then parses the module script for `function m.foo()` and `m.bar = ...` patterns.
     */
    private fun getModuleExports(currentText: String, varName: String): List<Suggestion> {
        // Find which module this variable requires
        val requirePattern = Regex("""local\s+${Regex.escape(varName)}\s*=\s*require\(\s*"(\w+)"\s*\)""")
        val match = requirePattern.find(currentText) ?: return emptyList()
        val moduleName = match.groupValues[1]

        val moduleText = scripts()[moduleName] ?: return emptyList()

        val exports = mutableListOf<Suggestion>()

        // Find the table variable name: local m = {} or local M = {}
        val tableVarPattern = Regex("""local\s+(\w+)\s*=\s*\{\s*\}""")
        val tableVars = tableVarPattern.findAll(moduleText).map { it.groupValues[1] }.toSet()

        for (tableVar in tableVars) {
            // function m.foo(...)
            val funcPattern = Regex("""function\s+${Regex.escape(tableVar)}\.(\w+)\s*\(([^)]*)\)""")
            funcPattern.findAll(moduleText).forEach { m ->
                val funcName = m.groupValues[1]
                val params = m.groupValues[2].trim()
                exports.add(suggest("$funcName(", "$funcName($params)"))
            }

            // m.foo = value
            val fieldPattern = Regex("""${Regex.escape(tableVar)}\.(\w+)\s*=""")
            fieldPattern.findAll(moduleText).forEach { m ->
                val fieldName = m.groupValues[1]
                // Don't duplicate if already found as a function
                if (exports.none { it.insertText.startsWith("$fieldName(") }) {
                    exports.add(suggest(fieldName))
                }
            }
        }

        return exports.distinctBy { it.insertText }
    }

    private fun extractPrefix(beforeCursor: String): String {
        val lastNonWord = beforeCursor.indexOfLast { !it.isLetterOrDigit() && it != '_' }
        return if (lastNonWord >= 0) beforeCursor.substring(lastNonWord + 1) else beforeCursor
    }
}
