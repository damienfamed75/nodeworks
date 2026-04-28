package damien.nodeworks.script

/**
 * Registry of broadcast-safe method names per scriptable handle type.
 *
 * `HandleList<T>` (returned by `network:getAll(type)` / `Channel:getAll(type)`)
 * exposes a fan-out version of every method listed here for the relevant T,
 * calling `:set(true)` on a `HandleList<RedstoneCard>` invokes `:set(true)` on
 * each member. Only "write" methods belong here: ones whose call site doesn't
 * depend on the return value, so discarding return values across N members
 * still does what the user wanted.
 *
 * **REQUIRED: any new card or device type added to the scripting API must
 * update this registry.** If you add Breaker/Placer/PlayerSensor/etc.:
 *
 *   1. Add a new entry to [byCapabilityType] (cards) or [byHandleType] (variables
 *      and other non-card handles).
 *   2. List every method that should fan out across HandleList members.
 *   3. Anything not listed is silently absent from `HandleList<T>`, only
 *      `:list()` and `:count()` are universal.
 *
 * Read methods (powered, strength, block, state, get, find, count, length…)
 * are deliberately NOT broadcast: their return value is the whole point of
 * calling them, and silently dropping return values across N members is a
 * footgun. Reads always go through `:list()` + per-member access.
 *
 * The runtime read of this registry happens in
 * `ScriptEngine.createHandleListTable`. Do not bypass it, letting one site
 * inline its own list of broadcast methods recreates the drift this registry
 * exists to prevent.
 */
object HandleListMethods {

    /**
     * For card handles, keyed by `SideCapability.type` (the same string the
     * snapshot exposes). Every key here must match a `cardType` declared on a
     * `NodeCard` subclass, if there's no entry the cards still flow through
     * `network:get(...)` and `:list()` works, but no broadcast methods install.
     */
    val byCapabilityType: Map<String, Set<String>> = mapOf(
        "io" to setOf("insert", "tryInsert"),
        "storage" to setOf("insert", "tryInsert"),
        "redstone" to setOf("set", "onChange"),
        "observer" to setOf("onChange"),
        // Devices, same registry, keyed off the device-type string used by
        // network:getAll / Channel:getAll. `:mine` returns a BreakBuilder when
        // called on a single Breaker but the broadcast wrapper drops the return
        // value (default network-storage routing applies to every member).
        // `:mine` was chosen instead of `:break` (reserved Lua keyword, would
        // be a syntax error) and over `:destroy` (suggests drops are deleted).
        "breaker" to setOf("mine", "cancel"),
        "placer" to setOf("place"),
    )

    /**
     * For non-card handles addressable by name (variables today, future
     * connectables when they grow scriptable methods). Keyed by the type name
     * a script would see in autocomplete (e.g. `"VariableHandle"`,
     * `"NumberVariableHandle"`).
     *
     * Variables split by declared type because the methods differ, a
     * `BoolVariableHandle` has no `:increment`. The shared methods (`set`,
     * `cas`) live under `"VariableHandle"` and inherit into the typed
     * variants via [methodsForHandleType].
     */
    val byHandleType: Map<String, Set<String>> = mapOf(
        "VariableHandle" to setOf("set", "cas"),
        "NumberVariableHandle" to setOf("increment", "decrement", "min", "max"),
        "StringVariableHandle" to setOf("append", "clear"),
        "BoolVariableHandle" to setOf("toggle", "unlock"),
    )

    /**
     * Resolve every broadcast-safe method for [handleType], merging the
     * shared `VariableHandle` methods into each typed variant. Handles whose
     * type isn't declared in the registry resolve to an empty set, meaning
     * the corresponding `HandleList<T>` exposes only `:list()` / `:count()`.
     */
    fun methodsForHandleType(handleType: String): Set<String> {
        val direct = byHandleType[handleType] ?: emptySet()
        return when (handleType) {
            "NumberVariableHandle", "StringVariableHandle", "BoolVariableHandle" ->
                direct + (byHandleType["VariableHandle"] ?: emptySet())
            else -> direct
        }
    }

    /** Convenience: methods for a card capability type (`"redstone"`, etc.). */
    fun methodsForCapabilityType(capabilityType: String): Set<String> =
        byCapabilityType[capabilityType] ?: emptySet()
}
