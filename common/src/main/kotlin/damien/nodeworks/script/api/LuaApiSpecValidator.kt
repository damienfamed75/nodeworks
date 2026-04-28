package damien.nodeworks.script.api

/**
 * Drift checker for the API registry. Runs at [LuaApiRegistry.seal] time so init-order
 * mistakes fail fast in dev, and is also exposed for CI tests that assert the spec is
 * internally consistent.
 *
 * This pass validates the SPEC against itself: dangling type references, malformed
 * keys, ambiguous capability mappings. The runtime-binding drift check (does a doc
 * named `Network:foo` actually have a Lua binding?) lives in
 * [LuaApiBindingValidator] and runs once the auto-marshalling layer is wired up, it
 * needs a live [damien.nodeworks.script.ScriptEngine] sandbox to introspect.
 */
object LuaApiSpecValidator {

    fun validate(): List<Violation> {
        val v = mutableListOf<Violation>()
        v += checkCapabilityMappings()
        v += checkTypeReferences()
        v += checkKeyShapes()
        v += checkUnionParts()
        v += checkParentChains()
        return v
    }

    /** Every [ApiSurface.parent] must point at a registered TYPE, and the chain must
     *  not loop. Catches typos in the inheritance wiring at seal time, same way
     *  [checkTypeReferences] catches them in method/property type refs. */
    private fun checkParentChains(): List<Violation> {
        val out = mutableListOf<Violation>()
        val knownTypeNames = LuaApiRegistry.knownTypes().map { it.name }.toSet()
        for (surface in LuaApiRegistry.allSurfaces().values) {
            val parent = surface.parent ?: continue
            if (parent.name !in knownTypeNames) {
                out += Violation.UnknownParent(surface.type.name, parent.name)
            }
        }
        // Cycle detection: walk each chain, stop on revisit.
        for (surface in LuaApiRegistry.allSurfaces().values) {
            if (surface.parent == null) continue
            val seen = mutableSetOf(surface.type.name)
            var cur: String? = surface.parent.name
            while (cur != null) {
                if (!seen.add(cur)) {
                    out += Violation.CyclicParent(surface.type.name, cur)
                    break
                }
                cur = LuaApiRegistry.allSurfaces()[cur]?.parent?.name
            }
        }
        return out
    }

    /** Two TYPE entries declaring the same capability string would silently shadow
     *  each other in [LuaApiRegistry.typeForCapabilityType]. Flag both, name the
     *  collision so the fix is obvious. */
    private fun checkCapabilityMappings(): List<Violation> {
        val byCap = LuaApiRegistry.knownTypes()
            .mapNotNull { t -> t.capabilityType?.let { it to t.name } }
            .groupBy({ it.first }, { it.second })
        return byCap.filter { it.value.size > 1 }.map { (cap, types) ->
            Violation.AmbiguousCapability(cap, types)
        }
    }

    /** Every type referenced by a doc's params or return type must be either a
     *  primitive or a registered Named/string subtype. Catches typos like
     *  `ItemHandle` vs `ItemsHandle` at init time, which is what eliminates the
     *  string-drift class of bug entirely once specs are migrated. */
    private fun checkTypeReferences(): List<Violation> {
        val knownNamed = (LuaApiRegistry.knownTypes() + LuaApiRegistry.knownModules()).map { it.name }.toSet()
        val knownStringTypes = LuaApiRegistry.allStringTypes().keys
        val knownNames = knownNamed + knownStringTypes
        val out = mutableListOf<Violation>()

        for (doc in LuaApiRegistry.allDocs().values) {
            val refs = collectReferencedNames(doc.returnType) + doc.params.flatMap { collectReferencedNames(it.type) }
            for (ref in refs) {
                if (ref !in knownNames) out += Violation.UnknownTypeRef(doc.key, ref)
            }
        }
        return out
    }

    private fun collectReferencedNames(type: LuaType): List<String> = when (type) {
        is LuaType.Named -> listOf(type.name)
        is LuaType.Optional -> collectReferencedNames(type.inner)
        is LuaType.ListOf -> collectReferencedNames(type.element)
        is LuaType.Function ->
            type.params.flatMap { collectReferencedNames(it.type) } + collectReferencedNames(type.returnType)
        is LuaType.Primitive -> emptyList()
        is LuaType.StringEnum -> listOf(type.name)
        is LuaType.StringDomain -> listOf(type.name)
        is LuaType.Union -> listOf(type.name)
    }

    /** [LuaType.Union] parts must all be string-shaped, mixing in non-string parts
     *  would make the autocomplete dispatcher's "what to suggest in this string
     *  position" decision ambiguous. Checked here rather than in the data class so
     *  the failure surfaces with the rest of the spec violations at seal time, in
     *  one batch, not as a thrown exception during registration. */
    private fun checkUnionParts(): List<Violation> {
        val out = mutableListOf<Violation>()
        for ((name, t) in LuaApiRegistry.allStringTypes()) {
            if (t !is LuaType.Union) continue
            for (part in t.parts) {
                if (!LuaType.isStringLike(part)) {
                    out += Violation.NonStringUnionPart(name, part.display)
                }
            }
        }
        return out
    }

    /** METHOD keys must be `<Type>:<name>`, PROPERTY keys `<Type>.<name>`, MODULE/TYPE
     *  keys bare names. Catches programmer mistakes in custom doc constructors more
     *  than DSL output, which already enforces shape. */
    private fun checkKeyShapes(): List<Violation> {
        val out = mutableListOf<Violation>()
        for (doc in LuaApiRegistry.allDocs().values) {
            val ok = when (doc.category) {
                ApiCategory.METHOD -> doc.key.contains(":") && !doc.key.contains(".")
                ApiCategory.PROPERTY -> doc.key.contains(".") && !doc.key.contains(":")
                ApiCategory.MODULE, ApiCategory.TYPE, ApiCategory.FUNCTION, ApiCategory.KEYWORD ->
                    !doc.key.contains(":") && !doc.key.contains(".")
            }
            if (!ok) out += Violation.MalformedKey(doc.key, doc.category)
        }
        return out
    }

    sealed class Violation {
        abstract override fun toString(): String

        data class AmbiguousCapability(val capability: String, val types: List<String>) : Violation() {
            override fun toString() =
                "Capability `$capability` is mapped by multiple types: ${types.joinToString(", ")}"
        }

        data class UnknownTypeRef(val docKey: String, val ref: String) : Violation() {
            override fun toString() = "$docKey references unknown type `$ref`"
        }

        data class MalformedKey(val key: String, val category: ApiCategory) : Violation() {
            override fun toString() = "Doc key `$key` does not match shape required for $category"
        }

        data class NonStringUnionPart(val unionName: String, val partDisplay: String) : Violation() {
            override fun toString() = "Union `$unionName` includes non-string part `$partDisplay`"
        }

        data class UnknownParent(val typeName: String, val parentRef: String) : Violation() {
            override fun toString() = "Type `$typeName` declares parent `$parentRef` which is not a registered type"
        }

        data class CyclicParent(val typeName: String, val loopAt: String) : Violation() {
            override fun toString() = "Type `$typeName`'s parent chain loops at `$loopAt`"
        }
    }
}
