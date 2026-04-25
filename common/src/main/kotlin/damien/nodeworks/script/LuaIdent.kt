package damien.nodeworks.script

/**
 * Convert raw card / variable names to Lua-safe identifiers.
 *
 * Used in two places:
 *   * The Scripting Terminal sidebar's click handler turns "iron ingot" into
 *     `local ironIngot = network:get("iron ingot")`.
 *   * The autocomplete popup offers the same identifier as a suggestion when
 *     the player types a prefix that matches it, and auto-imports the card
 *     when the suggestion is accepted.
 *
 * Both surfaces must produce the SAME identifier for a given alias so the
 * auto-import line matches whatever local the player would have typed by
 * hand. Centralising the rule here is the only sane way to keep them in sync.
 */
object LuaIdent {
    /**
     * Examples:
     *   "iron ingots"   → "ironIngots"
     *   "1st item"      → "_1stItem"
     *   "a b c"         → "aBC"
     *   "!@#$%"         → fallback
     *   "already"       → "already"
     *
     * Rules: split on every run of non-identifier characters, lowercase the
     * first word as-is, capitalise the first letter of every following word,
     * concatenate. If the result starts with a digit (Lua identifiers can't),
     * prepend `_`. If the alias contains no identifier characters at all,
     * return [fallback].
     */
    fun toLuaIdentifier(name: String, fallback: String): String {
        val parts = name.split(Regex("[^a-zA-Z0-9_]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return fallback
        val rest = parts.drop(1).joinToString("") { word ->
            word[0].uppercaseChar() + word.substring(1)
        }
        val ident = parts[0] + rest
        return if (ident[0].isDigit()) "_$ident" else ident
    }
}
