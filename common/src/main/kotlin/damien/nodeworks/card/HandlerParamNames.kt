package damien.nodeworks.card

/**
 * Pure-Kotlin name mangling for processing-handler parameter slots.
 *
 * Lifted out of [ProcessingSet]'s companion so consumers that want the same
 * deterministic identifiers (autocomplete, CPU executor, LuaDiagnostics) don't
 * have to load the `ProcessingSet` class, which extends MC's `Item` and pulls
 * the whole client/server registry chain into anything that touches it. The
 * test classpath in particular only has plain Kotlin on it, so referencing
 * `ProcessingSet.buildHandlerParamNames` from [LuaDiagnostics] would fail
 * with `NoClassDefFoundError: net/minecraft/world/item/Item` even though the
 * function itself is just string manipulation.
 *
 * Keeping the rule in one place guarantees the four surfaces that depend on
 * it (runtime `items` table keys, autocomplete suggestions for `items.<tab>`,
 * unused-input diagnostics, and the [ProcessingSet] companion delegations)
 * never drift.
 */
object HandlerParamNames {

    /**
     * Convert an item id into a camelCase Lua identifier:
     * `minecraft:copper_ingot` → `copperIngot`. Strips the namespace, splits
     * on `_`, lowercases the first chunk, and capitalises the leading char of
     * each later chunk before joining.
     */
    fun itemIdToParamName(itemId: String): String {
        val shortId = itemId.substringAfter(':')
        val parts = shortId.split('_')
        return parts.mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    /**
     * Build per-slot handler parameter names in grid order. Duplicate entries
     * get a numeric suffix on 2nd+ occurrence so the field list in the
     * handler's `items` table is a stable, lossless mapping from slot index
     * to identifier.
     *
     * Example: `[copper_ingot, gold_ingot, copper_ingot]` →
     *          `[copperIngot, goldIngot, copperIngot2]`.
     */
    fun build(inputs: List<Pair<String, Int>>): List<String> {
        val result = mutableListOf<String>()
        val occurrence = mutableMapOf<String, Int>()
        for ((itemId, _) in inputs) {
            val base = itemIdToParamName(itemId)
            val n = (occurrence[base] ?: 0) + 1
            occurrence[base] = n
            result.add(if (n == 1) base else "$base$n")
        }
        return result
    }
}
