package damien.nodeworks.script.api

/**
 * Type model for the Lua API surface. All references to Lua types in API specs flow
 * through these objects rather than raw strings, so renaming or restructuring a type
 * propagates as a Kotlin compile error rather than silent string drift.
 *
 * `display` renders the canonical Lua-script-facing form, e.g. `"ItemsHandle?"`,
 * `"{ ItemsHandle }"`, `"function(item: ItemsHandle, count: number) -> void"`. This is
 * the ONLY place those strings are produced. Hover tooltips, signatures, and the
 * guidebook all read this property.
 */
sealed class LuaType {

    abstract val display: String

    /** Wrap as nullable. Renders with a trailing `?`. */
    fun optional(): LuaType = if (this is Optional) this else Optional(this)

    /** Wrap as a Lua list of this type. Renders as `{ T }`. */
    fun list(): LuaType = ListOf(this)

    /** Built-in primitive Lua types. No registration needed, no methods. */
    sealed class Primitive(override val display: kotlin.String) : LuaType() {
        object Number : Primitive("number")
        object String : Primitive("string")
        object Boolean : Primitive("boolean")
        object Nil : Primitive("nil")

        /** Used for methods that return nothing the script can use. Distinct from [Nil]
         *  so chain resolvers know there's nothing to chain off of. */
        object Void : Primitive("void")

        /** Escape hatch for genuinely-untyped positions, `print(value: any)` etc.
         *  Avoid for new APIs, prefer concrete types or unions. */
        object Any : Primitive("any")
    }

    /** A registered API surface type: a module like `network`, or a value type like
     *  `ItemsHandle`. Created via [LuaTypes.module] / [LuaTypes.type], NEVER constructed
     *  inline at a method-spec call site, those references must flow through the registry
     *  so the validator can detect dangling references. */
    data class Named(
        val name: String,
        val kind: Kind,
        /** For modules, the global Lua name (`"network"`). For TYPE entries this is null. */
        val moduleGlobal: String? = null,
        /** For TYPE entries that map to a card capability string (`"redstone"` for
         *  RedstoneCard). The registry uses this to derive the bidirectional capability
         *  <-> type-name mapping with no scattered duplicates. */
        val capabilityType: String? = null,
        val description: String,
        /** Guidebook page (and optional anchor) the hover tooltip and `<LuaCode>` tag
         *  link to when this type or module is the resolved symbol. Format matches
         *  per-method [ApiDoc.guidebookRef], `nodeworks:lua-api/page.md` or
         *  `nodeworks:lua-api/page.md#anchor`. */
        val guidebookRef: String? = null,
    ) : LuaType() {
        override val display: String get() = name

        enum class Kind { TYPE, MODULE }
    }

    /** Nullable wrapper. `ItemsHandle?` means `ItemsHandle | nil`. The chain resolver
     *  treats the inner type as the chainable type, the diagnostics analyzer flags
     *  uses that don't nil-check first. */
    data class Optional(val inner: LuaType) : LuaType() {
        init {
            require(inner !is Optional) { "Double-nullable types not supported, found Optional<Optional<${inner.display}>>" }
        }

        override val display: String get() = "${inner.display}?"
    }

    /** List wrapper. `{ ItemsHandle }` is broadcastable: methods on the element type
     *  fan out across the list, and `list[i]:` chain resolves to the element type. */
    data class ListOf(val element: LuaType) : LuaType() {
        override val display: String get() = "{ ${element.display} }"
    }

    /** Function type for callback parameters. Renders as
     *  `"function(name: type, ...) → ret"`. Used by [api] specs to declare callback
     *  signatures, which the autocomplete then turns into editor snippets with the
     *  correct param names pre-filled. */
    data class Function(
        val params: kotlin.collections.List<Param>,
        val returnType: LuaType,
    ) : LuaType() {
        override val display: kotlin.String
            get() {
                val paramsStr = params.joinToString(", ") { "${it.name}: ${it.type.display}" }
                return "function($paramsStr) → ${returnType.display}"
            }
    }

    /** Closed enumeration of string literal values. Render as the type's [name]. Used
     *  for finite-domain string fields like `ItemsHandleKind = "item" | "fluid"`,
     *  hypothetical Direction or Pose enums, etc. The autocomplete in any string
     *  position typed as this enum offers exactly [values], the diagnostics analyzer
     *  can flag string comparisons against literals not in [values] as hard errors. */
    data class StringEnum(
        val name: kotlin.String,
        val values: kotlin.collections.List<kotlin.String>,
        val description: kotlin.String,
    ) : LuaType() {
        override val display: kotlin.String get() = name
    }

    /** Open string subtype tied to a runtime-resolved completion source. Used for
     *  domains whose valid values come from a registry that changes over time:
     *  `ItemId` (vanilla + modded item registry), `TagId` (current tag registry),
     *  `CardAlias` (the controller's currently-known cards), etc. The [sourceKey] is
     *  the lookup into [LuaCompletionSources], the actual list is fetched at
     *  autocomplete time so the suggestions are always fresh. Diagnostics on
     *  comparisons to unknown literals are soft warnings, never errors, since modded
     *  values may be present at runtime that the analyzer can't observe. */
    data class StringDomain(
        val name: kotlin.String,
        val description: kotlin.String,
        val sourceKey: kotlin.String,
    ) : LuaType() {
        override val display: kotlin.String get() = name
    }

    /** Union of string-like types. Render as the type's [name]. The autocomplete in
     *  any string position typed as this union merges suggestions from each part.
     *  Used for things like `Filter = ItemId | TagId | FilterSyntax`, the parts are
     *  themselves [StringEnum] / [StringDomain] / [Union] so the dispatch is
     *  uniformly recursive. Restricted to string-like parts: enforced by the
     *  validator, mixing non-string parts here would make autocomplete dispatch
     *  ambiguous. */
    data class Union(
        val name: kotlin.String,
        val parts: kotlin.collections.List<LuaType>,
        val description: kotlin.String,
    ) : LuaType() {
        override val display: kotlin.String get() = name
    }

    /** A typed parameter, name + type. Used by both [Function] and method specs. */
    data class Param(val name: kotlin.String, val type: LuaType, val description: kotlin.String? = null)

    companion object {
        /** Strip [Optional] and [ListOf] wrappers down to the underlying type. The
         *  chain resolver and diagnostics analyzer both want the "what is this really"
         *  answer, e.g. `{ ItemsHandle }` → `ItemsHandle`, `ItemsHandle?` →
         *  `ItemsHandle`. Returns the same object for already-unwrapped types. */
        tailrec fun unwrap(type: LuaType): LuaType = when (type) {
            is Optional -> unwrap(type.inner)
            is ListOf -> unwrap(type.element)
            else -> type
        }

        /** True if scripts can chain (`:method`) off a value of this type. Primitives
         *  and Void can't, [Named] TYPE/MODULE entries can, [Optional]/[ListOf] decide
         *  by their inner type. String subtypes ([StringEnum], [StringDomain], [Union])
         *  are not chainable, they're string values with no methods of their own. */
        fun isChainable(type: LuaType): kotlin.Boolean = when (unwrap(type)) {
            is Named -> true
            is Function -> false
            is Primitive -> false
            is StringEnum -> false
            is StringDomain -> false
            is Union -> false
            else -> false
        }

        /** True if the underlying type is string-shaped: a [Primitive.String], or a
         *  string subtype ([StringEnum], [StringDomain], or a [Union] of these).
         *  Used by the autocomplete dispatcher to decide whether a string-position
         *  cursor should be offered string-domain completions vs nothing. */
        fun isStringLike(type: LuaType): kotlin.Boolean = when (val u = unwrap(type)) {
            is Primitive -> u == Primitive.String
            is StringEnum -> true
            is StringDomain -> true
            is Union -> u.parts.all { isStringLike(it) }
            else -> false
        }
    }
}
