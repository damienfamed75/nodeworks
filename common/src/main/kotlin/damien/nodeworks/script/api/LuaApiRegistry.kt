package damien.nodeworks.script.api

/**
 * Single source of truth queried by every editor surface and the guidebook. Specs
 * register themselves at mod init via [register], then consumers ([AutocompletePopup],
 * [TerminalScreen] hover, the `<LuaCode>` guidebook tag, the runtime drift validator)
 * call the query helpers below.
 *
 * Registration is one-shot: surfaces must be registered before any consumer queries,
 * and re-registering the same key is rejected to catch accidental duplicates. This
 * is what keeps the registry deterministic, no surprise late additions changing
 * autocomplete behaviour mid-session.
 */
object LuaApiRegistry {

    private val typesByName = mutableMapOf<String, LuaType.Named>()
    private val surfacesByType = mutableMapOf<String, ApiSurface>()
    private val docsByKey = mutableMapOf<String, ApiDoc>()
    private val globalDocs = mutableMapOf<String, ApiDoc>()
    private val stringTypes = mutableMapOf<String, LuaType>()

    private var sealed = false

    /** Register an API surface. Idempotent only on identical content, throws if a
     *  conflicting surface for the same type was previously registered. */
    fun register(surface: ApiSurface) {
        check(!sealed) { "LuaApiRegistry already sealed, cannot register ${surface.type.name}" }
        val name = surface.type.name
        require(name !in surfacesByType) { "Duplicate API surface: $name" }
        surfacesByType[name] = surface
        typesByName[name] = surface.type
        docsByKey[name] = surface.typeDoc
        surface.moduleAliasDoc?.let {
            require(it.key !in docsByKey) { "Duplicate API doc key: ${it.key}" }
            docsByKey[it.key] = it
        }
        for (doc in surface.methods + surface.properties) {
            require(doc.key !in docsByKey) { "Duplicate API doc key: ${doc.key}" }
            docsByKey[doc.key] = doc
        }
    }

    /** Register a top-level global like `print`, `clock`, or a Lua keyword that needs
     *  hover docs but isn't part of any module surface. */
    fun registerGlobal(doc: ApiDoc) {
        check(!sealed) { "LuaApiRegistry already sealed, cannot register ${doc.key}" }
        require(doc.key !in globalDocs) { "Duplicate global doc: ${doc.key}" }
        globalDocs[doc.key] = doc
        docsByKey[doc.key] = doc
    }

    /** Register a string subtype ([LuaType.StringEnum] / [LuaType.StringDomain] /
     *  [LuaType.Union]). The validator looks up references against this collection
     *  alongside [typesByName], so a `param("filter", Filter)` with no matching
     *  registration fails at [seal].
     *
     *  Also synthesizes a TYPE-category [ApiDoc] for the registered name so hover
     *  tooltips on `local x: TagId` and the type-annotation autocomplete pick it up
     *  through the same query path used for [LuaType.Named] entries. */
    fun registerStringType(type: LuaType) {
        check(!sealed) { "LuaApiRegistry already sealed" }
        val (name, description) = when (type) {
            is LuaType.StringEnum -> type.name to type.description
            is LuaType.StringDomain -> type.name to type.description
            is LuaType.Union -> type.name to type.description
            else -> error("registerStringType expects StringEnum/StringDomain/Union, got ${type::class.simpleName}")
        }
        require(name !in stringTypes) { "Duplicate string type: $name" }
        require(name !in docsByKey) { "Duplicate doc key: $name" }
        stringTypes[name] = type
        docsByKey[name] = ApiDoc(
            key = name,
            displayName = name,
            category = ApiCategory.TYPE,
            signature = type.display,
            description = description,
        )
    }

    /** Mark registration complete. After this call [register] throws. The validator
     *  runs at this point so init-time misconfigurations fail fast. */
    fun seal() {
        if (sealed) return
        sealed = true
        val violations = LuaApiSpecValidator.validate()
        check(violations.isEmpty()) {
            "LuaApiRegistry validation failed:\n" + violations.joinToString("\n") { "  - $it" }
        }
    }

    /** Test-only reset, for use by the validator's own unit tests. NEVER call from
     *  production code, the registry is meant to be one-shot per process. */
    internal fun resetForTesting() {
        typesByName.clear()
        surfacesByType.clear()
        docsByKey.clear()
        globalDocs.clear()
        stringTypes.clear()
        sealed = false
    }

    // Query helpers, read-only after registration. Each one calls
    // [LuaApiBootstrap.ensureInitialized] so the first consumer to touch the registry
    // triggers spec registration. The bootstrap's `initializing` re-entrancy guard
    // ensures the validator's own reads during [seal] don't recurse.

    /** All registered TYPE entries (excluding MODULE). Drives the `local x: <Type>`
     *  autocomplete and the diagnostics analyzer's "is this a known type" check. */
    fun knownTypes(): List<LuaType.Named> {
        LuaApiBootstrap.ensureInitialized()
        return typesByName.values.filter { it.kind == LuaType.Named.Kind.TYPE }
    }

    /** All registered MODULE globals (network, scheduler, etc.). */
    fun knownModules(): List<LuaType.Named> {
        LuaApiBootstrap.ensureInitialized()
        return typesByName.values.filter { it.kind == LuaType.Named.Kind.MODULE }
    }

    /** All registered globals (top-level functions, keywords). */
    fun globals(): Map<String, ApiDoc> {
        LuaApiBootstrap.ensureInitialized()
        return globalDocs
    }

    /** Methods declared on a type or module, including any inherited from
     *  [ApiSurface.parent]. Own methods come first, then inherited ones, with later
     *  duplicates suppressed so a subtype can override a base method by re-declaring
     *  it under the same name. Empty list if the receiver isn't registered, callers
     *  should treat that as "unknown receiver", not an error. */
    fun methodsOf(typeName: String): List<ApiDoc> {
        LuaApiBootstrap.ensureInitialized()
        return mergeWithParent(typeName) { it.methods }
    }

    /** Properties declared on a type, including any inherited from [ApiSurface.parent]. */
    fun propertiesOf(typeName: String): List<ApiDoc> {
        LuaApiBootstrap.ensureInitialized()
        return mergeWithParent(typeName) { it.properties }
    }

    /** Resolve the return type of a method on a type, for the autocomplete chain
     *  resolver. Walks the parent chain so a method defined on the base type is
     *  resolvable from a chained call on the subtype. Returns null if the method
     *  isn't registered anywhere up the chain. */
    fun methodReturnType(typeName: String, methodName: String): LuaType? {
        LuaApiBootstrap.ensureInitialized()
        var t: String? = typeName
        val seen = mutableSetOf<String>()
        while (t != null && seen.add(t)) {
            docsByKey["$t:$methodName"]?.returnType?.let { return it }
            t = surfacesByType[t]?.parent?.name
        }
        return null
    }

    /** Walk the [ApiSurface.parent] chain starting at [typeName], collecting whatever
     *  [select] returns from each surface. Own entries appear first so subtypes can
     *  shadow base entries by name; duplicates are dropped on the way. */
    private fun mergeWithParent(
        typeName: String,
        select: (ApiSurface) -> List<ApiDoc>,
    ): List<ApiDoc> {
        val out = mutableListOf<ApiDoc>()
        val seenNames = mutableSetOf<String>()
        val seenSurfaces = mutableSetOf<String>()
        var t: String? = typeName
        while (t != null && seenSurfaces.add(t)) {
            val surface = surfacesByType[t] ?: break
            for (doc in select(surface)) {
                if (seenNames.add(doc.displayName)) out.add(doc)
            }
            t = surface.parent?.name
        }
        return out
    }

    /** Look up a doc by its qualified key (`Network:find`, `ItemsHandle.id`, `print`). */
    fun docFor(key: String): ApiDoc? {
        LuaApiBootstrap.ensureInitialized()
        return docsByKey[key]
    }

    /** Resolve a Lua-side capability string (`"redstone"`) to its registered TYPE
     *  (`RedstoneCard`). Used by the autocomplete to figure out what type
     *  `network:get("redstone-card")` returns. */
    fun typeForCapabilityType(capabilityString: String): LuaType.Named? {
        LuaApiBootstrap.ensureInitialized()
        return typesByName.values.firstOrNull { it.capabilityType == capabilityString }
    }

    /** Inverse of [typeForCapabilityType]. Used by handle-list broadcast logic that
     *  wants to know "what capability does CardHandle map to?". */
    fun capabilityTypeFor(typeName: String): String? {
        LuaApiBootstrap.ensureInitialized()
        return typesByName[typeName]?.capabilityType
    }

    /** True if the given module name resolves to a registered MODULE. Used by the
     *  symbol table to know whether `network:foo` should look up `Network:foo` docs. */
    fun moduleType(globalName: String): LuaType.Named? {
        LuaApiBootstrap.ensureInitialized()
        return typesByName.values.firstOrNull { it.moduleGlobal == globalName }
    }

    /** Snapshot of every registered doc, keyed by its qualified key. The legacy
     *  [damien.nodeworks.script.LuaApiDocs.resolveAt] consumers can read from this
     *  during the migration window before they're cut over to direct registry queries. */
    fun allDocs(): Map<String, ApiDoc> {
        LuaApiBootstrap.ensureInitialized()
        return docsByKey
    }

    /** Look up a registered string subtype by name. Returns null when the name isn't
     *  a registered [LuaType.StringEnum] / [LuaType.StringDomain] / [LuaType.Union]. */
    fun stringTypeOf(name: String): LuaType? {
        LuaApiBootstrap.ensureInitialized()
        return stringTypes[name]
    }

    /** Snapshot of every registered string subtype. The validator iterates these to
     *  check that all referenced names resolve and that [LuaType.StringDomain] keys
     *  point at known sources. */
    fun allStringTypes(): Map<String, LuaType> {
        LuaApiBootstrap.ensureInitialized()
        return stringTypes
    }

    /** Snapshot of every registered surface, keyed by its type name. Exposed for the
     *  validator's parent-chain walk. */
    fun allSurfaces(): Map<String, ApiSurface> {
        LuaApiBootstrap.ensureInitialized()
        return surfacesByType
    }
}

/**
 * Convenience so spec files can declare a TYPE/MODULE inline without going through
 * [LuaType.Named]'s constructor every time. The returned [LuaType.Named] is what
 * other spec entries reference, so renaming a type means one Kotlin field rename
 * and the IDE updates every call site.
 */
object LuaTypes {

    fun module(global: String, name: String, description: String, guidebookRef: String? = null): LuaType.Named =
        LuaType.Named(
            name = name,
            kind = LuaType.Named.Kind.MODULE,
            moduleGlobal = global,
            description = description,
            guidebookRef = guidebookRef,
        )

    fun type(name: String, description: String, capability: String? = null, guidebookRef: String? = null): LuaType.Named =
        LuaType.Named(
            name = name,
            kind = LuaType.Named.Kind.TYPE,
            capabilityType = capability,
            description = description,
            guidebookRef = guidebookRef,
        )
}
