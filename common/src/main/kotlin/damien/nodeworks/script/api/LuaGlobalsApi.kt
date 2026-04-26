package damien.nodeworks.script.api

/**
 * Spec for Lua keywords + top-level globals available in every script: the
 * statement keywords (`local`, `function`, `for`, `if`, etc.), the runtime
 * helpers we expose on the global table (`print`, `clock`, `error`, `require`),
 * and the Lua stdlib modules + functions (`string`, `math`, `table`, plus bare
 * `tostring`, `tonumber`, `type`, `pairs`, `ipairs`, `select`, `unpack`).
 *
 * Registered as flat globals on [LuaApiRegistry], which makes them resolve in
 * hover tooltips, the guidebook tag, and the autocomplete `knownTypes` /
 * `apiFunctions` queries through the same single-source path as the migrated
 * surfaces above.
 */
internal val LUA_KEYWORDS: List<ApiDoc> = listOf(
    keyword("local", "local <name> = <value>", "Declares a variable scoped to the enclosing block."),
    keyword(
        "function",
        "function <name>(<params>) ... end",
        "Declares a function. Use `local function` to keep it block-scoped."
    ),
    keyword("if", "if <cond> then ... [elseif ...] [else ...] end", "Conditional branch."),
    keyword(
        "for",
        "for i = start, stop[, step] do ... end  |  for k, v in <iterable> do ... end",
        "Numeric or generic loop."
    ),
    keyword("while", "while <cond> do ... end", "Loops while the condition is truthy."),
    keyword("return", "return [values...]", "Returns from the current function, optionally yielding values."),
    keyword("break", "break", "Exits the innermost enclosing loop."),
    keyword("end", "end", "Closes a function, conditional, or loop block."),
    keyword("do", "do ... end", "Opens an explicit block, also paired with `for` and `while`."),
    keyword("then", "then", "Opens the consequent of an `if` / `elseif`."),
    keyword("else", "else", "Opens the fallback branch of an `if`."),
    keyword("elseif", "elseif <cond> then", "Branches after an `if`'s `then` block."),
    keyword("nil", "nil", "The absence of a value."),
    keyword("true", "true", "Boolean literal."),
    keyword("false", "false", "Boolean literal."),
    keyword("not", "not <value>", "Logical negation."),
    keyword("and", "<a> and <b>", "Short-circuit logical and. Returns `a` if falsy, else `b`."),
    keyword("or", "<a> or <b>", "Short-circuit logical or. Returns `a` if truthy, else `b`."),
)

internal val LUA_GLOBAL_FUNCTIONS: List<ApiDoc> = listOf(
    globalFunction(
        name = "print",
        signature = "print(...)",
        description = "Writes its arguments to the terminal log, space separated.",
    ),
    globalFunction(
        name = "require",
        signature = "require(modName: string) -> module",
        description = "Loads and returns a Lua module from another script tab in this terminal.",
    ),
    globalFunction(
        name = "clock",
        signature = "clock() -> number",
        description = "Fractional seconds since this script started running.",
    ),
    globalFunction(
        name = "error",
        signature = "error(message: string)",
        description = "Throws an error with the given message. Halts the current call.",
    ),
    globalFunction(
        name = "assert",
        signature = "assert(value: any, message?: string) -> any",
        description = "Throws if `value` is falsy, otherwise returns `value`. Use the optional `message` to label the failure.",
    ),
    globalFunction(
        name = "tostring",
        signature = "tostring(value: any) -> string",
        description = "Converts the value to its string representation.",
    ),
    globalFunction(
        name = "tonumber",
        signature = "tonumber(value: any) -> number?",
        description = "Parses the value as a number, or returns nil if not parseable.",
    ),
    globalFunction(
        name = "type",
        signature = "type(value: any) -> string",
        description = "Returns the value's Lua type name as a string (`nil`, `boolean`, `number`, `string`, `table`, `function`, `userdata`, `thread`).",
    ),
    globalFunction(
        name = "pairs",
        signature = "pairs(t: table) -> function",
        description = "Iterator over all key-value pairs in a table.",
    ),
    globalFunction(
        name = "ipairs",
        signature = "ipairs(t: table) -> function",
        description = "Iterator over the contiguous integer-keyed entries of a table.",
    ),
    globalFunction(
        name = "select",
        signature = "select(index: number | string, ...) -> any",
        description = "Returns the values from the index-th argument onward. `select('#', ...)` returns the number of varargs.",
    ),
    globalFunction(
        name = "unpack",
        signature = "unpack(t: table) -> ...",
        description = "Returns the elements of a table as multiple values.",
    ),
)

internal val LUA_STDLIB_MODULES: List<ApiDoc> = listOf(
    globalModule(
        name = "string",
        description = "Lua's string library. `string.format`, `string.sub`, `string.find`, etc.",
    ),
    globalModule(
        name = "math",
        description = "Lua's math library. `math.floor`, `math.random`, `math.pi`, etc.",
    ),
    globalModule(
        name = "table",
        description = "Lua's table library. `table.insert`, `table.remove`, `table.concat`, etc.",
    ),
)
