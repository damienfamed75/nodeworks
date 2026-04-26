package damien.nodeworks.script.api

/**
 * Declarative API spec model. Each API surface (Network, Scheduler, CardHandle, etc.)
 * is described by a single [api] block whose methods, properties, and metadata feed
 * every editor-facing surface: hover tooltips, autocomplete, the guidebook tag, and
 * the runtime drift validator.
 *
 * This file holds the data classes + DSL builders. The runtime registry that consumes
 * specs and answers queries lives in [LuaApiRegistry].
 *
 * Mandatory fields (`description`, `returns`) are enforced by the builder, an API
 * symbol cannot be added without docs and a return type. Optional fields cover
 * versioning and editor-affordance polish ([since], [deprecatedFor], [example],
 * [snippetBody]).
 */

enum class ApiCategory { KEYWORD, MODULE, TYPE, FUNCTION, METHOD, PROPERTY }

/**
 * One documented API symbol. Built by the DSL, consumed read-only by the registry.
 * The `signature` string is GENERATED from [params] + [returnType], never written by
 * hand, that's the consolidation that makes the system robust against typos.
 */
data class ApiDoc(
    val key: String,
    val displayName: String,
    val category: ApiCategory,
    val signature: String,
    val description: String,
    val params: List<LuaType.Param> = emptyList(),
    val returnType: LuaType = LuaType.Primitive.Void,
    val guidebookRef: String? = null,
    val example: String? = null,
    val since: String? = null,
    val deprecatedFor: String? = null,
    val snippetBody: String? = null,
    val snippetCursorOffset: Int = 0,
)

/** A complete API surface: one TYPE or MODULE plus all its methods and properties.
 *  The registry stores these as the unit of registration so consumers can iterate
 *  "all methods on Network" without scanning a flat key map. */
data class ApiSurface(
    val type: LuaType.Named,
    val methods: List<ApiDoc>,
    val properties: List<ApiDoc>,
) {
    /** Top-level [Doc] entry for the surface itself. Used so the surface name resolves
     *  to a tooltip and the guidebook gets a per-type page. */
    val typeDoc: ApiDoc by lazy {
        ApiDoc(
            key = type.name,
            displayName = type.name,
            category = if (type.kind == LuaType.Named.Kind.MODULE) ApiCategory.MODULE else ApiCategory.TYPE,
            signature = type.name,
            description = type.description,
            guidebookRef = type.guidebookRef,
        )
    }

    /** Mirror [typeDoc] for the lowercase global key (`"scheduler"`) when the surface
     *  is a module. Both `scheduler` (the global) and `Scheduler` (the type) need to
     *  resolve to the same hover doc and guidebook link. */
    val moduleAliasDoc: ApiDoc? by lazy {
        if (type.kind != LuaType.Named.Kind.MODULE) return@lazy null
        val global = type.moduleGlobal ?: return@lazy null
        ApiDoc(
            key = global,
            displayName = global,
            category = ApiCategory.MODULE,
            signature = "$global: ${type.name}",
            description = type.description,
            guidebookRef = type.guidebookRef,
        )
    }
}

/** Entry point for declaring an API surface. The receiver type is passed as a
 *  [LuaType.Named] so all references to it elsewhere in the spec come from the same
 *  Kotlin object, no chance of a typo'd "Network" string sneaking through. */
fun api(receiver: LuaType.Named, init: ApiSurfaceBuilder.() -> Unit): ApiSurface {
    val builder = ApiSurfaceBuilder(receiver)
    builder.init()
    return builder.build()
}

/** Top-level [LuaType.Function] builder for callback-shaped parameters. Reads as
 *  `param("fn", function { param(...); returns(...) })`. The same builder is used
 *  inside [CallbackBuilder.fn] for declaring callback-method parameter shapes. */
fun function(init: FunctionTypeBuilder.() -> Unit): LuaType.Function {
    val fb = FunctionTypeBuilder()
    fb.init()
    return fb.build()
}

class ApiSurfaceBuilder internal constructor(private val receiver: LuaType.Named) {
    private val methods = mutableListOf<ApiDoc>()
    private val properties = mutableListOf<ApiDoc>()

    /** Declare a method on this surface. The [name] is the Lua-script-facing name
     *  and is the only string that must match the runtime binding, the validator
     *  catches drift between the two sides. */
    fun method(name: String, init: MethodBuilder.() -> Unit) {
        val mb = MethodBuilder(name)
        mb.init()
        methods.add(mb.build(receiver, ApiCategory.METHOD))
    }

    /** Sugar for callback-shaped methods like `network:onInsert(fn)`. The framework
     *  generates the editor snippet body from the function param's signature so
     *  accepting the autocomplete drops in a fully-formed `function(item, count) ... end`
     *  template with the cursor at the body. */
    fun callback(name: String, init: CallbackBuilder.() -> Unit) {
        val cb = CallbackBuilder(name)
        cb.init()
        methods.add(cb.build(receiver))
    }

    /** Declare a readable property exposed as a table field. */
    fun property(name: String, type: LuaType, init: PropertyBuilder.() -> Unit) {
        val pb = PropertyBuilder(name, type)
        pb.init()
        properties.add(pb.build(receiver))
    }

    fun build(): ApiSurface = ApiSurface(receiver, methods.toList(), properties.toList())
}

class MethodBuilder internal constructor(private val name: String) {
    private val params = mutableListOf<LuaType.Param>()
    private var _returnType: LuaType? = null
    private var _description: String? = null

    var description: String
        get() = _description ?: ""
        set(v) { _description = v }
    var guidebookRef: String? = null
    var example: String? = null
    var since: String? = null
    var deprecatedFor: String? = null

    fun param(name: String, type: LuaType, description: String? = null) {
        params.add(LuaType.Param(name, type, description))
    }

    fun returns(type: LuaType) {
        _returnType = type
    }

    internal fun build(receiver: LuaType.Named, category: ApiCategory): ApiDoc {
        val desc = _description ?: error("${receiver.name}:$name missing required `description`")
        val ret = _returnType ?: error("${receiver.name}:$name missing required `returns(...)`")
        val (snippetBody, snippetCursor) = buildSnippet(name, params)
        return ApiDoc(
            key = "${receiver.name}:$name",
            displayName = name,
            category = category,
            signature = renderSignature(name, params, ret),
            description = desc,
            params = params.toList(),
            returnType = ret,
            guidebookRef = guidebookRef,
            example = example,
            since = since,
            deprecatedFor = deprecatedFor,
            snippetBody = snippetBody,
            snippetCursorOffset = snippetCursor,
        )
    }
}

/** Generate a snippet for accepted method completions when one of two shapes
 *  applies. Returns (null, 0) for everything else, the caller falls back to a
 *  plain `name(` insertion.
 *
 *  Single-fn-param: full callback template with the cursor in the function body.
 *  This is what Scheduler:tick / Scheduler:second / CraftBuilder:connect etc. get.
 *
 *  String-first-param: inserts `name("")` with the cursor between the quotes so
 *  the string-position autocomplete (card aliases, item ids, dye colors) fires
 *  immediately. The user picks a value and continues typing the rest of the
 *  call, the per-arg string-position dispatch handles each arg as they're typed.
 *
 *  Multi-arg with no string first param: no snippet, user types each arg
 *  themselves so the per-arg autocomplete fires naturally. */
private fun buildSnippet(name: String, params: List<LuaType.Param>): Pair<String?, Int> {
    if (params.isEmpty()) return null to 0

    if (params.size == 1) {
        val fnType = params[0].type as? LuaType.Function ?: return null to 0
        val fnParamList = fnType.params.joinToString(", ") { "${it.name}: ${it.type.display}" }
        val body = "$name(function($fnParamList)\n    \nend)"
        val cursor = body.indexOf("\n    \n") + 5
        return body to cursor
    }

    if (LuaType.isStringLike(params[0].type)) {
        val body = "$name(\"\")"
        val cursor = name.length + 2
        return body to cursor
    }

    return null to 0
}

class CallbackBuilder internal constructor(private val name: String) {
    private var _fnType: LuaType.Function? = null
    private var _description: String? = null
    private var _returnType: LuaType = LuaType.Primitive.Void

    var description: String
        get() = _description ?: ""
        set(v) { _description = v }
    var guidebookRef: String? = null
    var example: String? = null
    var since: String? = null
    var deprecatedFor: String? = null

    /** Declare the shape of the callback this method takes. The framework uses this
     *  to render the parameter signature AND to build the editor snippet body. */
    fun fn(init: FunctionTypeBuilder.() -> Unit) {
        val fb = FunctionTypeBuilder()
        fb.init()
        _fnType = fb.build()
    }

    fun returns(type: LuaType) {
        _returnType = type
    }

    internal fun build(receiver: LuaType.Named): ApiDoc {
        val desc = _description ?: error("${receiver.name}:$name missing required `description`")
        val fnType = _fnType ?: error("${receiver.name}:$name missing required `fn { ... }`")

        val params = listOf(LuaType.Param("fn", fnType))
        val sig = renderSignature(name, params, _returnType)

        val fnParamList = fnType.params.joinToString(", ") { "${it.name}: ${it.type.display}" }
        val body = "$name(function($fnParamList)\n    \nend)"
        val cursor = body.indexOf("\n    \n") + 5

        return ApiDoc(
            key = "${receiver.name}:$name",
            displayName = name,
            category = ApiCategory.METHOD,
            signature = sig,
            description = desc,
            params = params,
            returnType = _returnType,
            guidebookRef = guidebookRef,
            example = example,
            since = since,
            deprecatedFor = deprecatedFor,
            snippetBody = body,
            snippetCursorOffset = cursor,
        )
    }
}

class FunctionTypeBuilder internal constructor() {
    private val params = mutableListOf<LuaType.Param>()
    private var _returnType: LuaType = LuaType.Primitive.Void

    fun param(name: String, type: LuaType, description: String? = null) {
        params.add(LuaType.Param(name, type, description))
    }

    fun returns(type: LuaType) { _returnType = type }

    internal fun build(): LuaType.Function = LuaType.Function(params.toList(), _returnType)
}

class PropertyBuilder internal constructor(private val name: String, private val type: LuaType) {
    private var _description: String? = null
    var description: String
        get() = _description ?: ""
        set(v) { _description = v }
    var guidebookRef: String? = null
    var example: String? = null
    var since: String? = null
    var deprecatedFor: String? = null

    internal fun build(receiver: LuaType.Named): ApiDoc {
        val desc = _description ?: error("${receiver.name}.$name missing required `description`")
        return ApiDoc(
            key = "${receiver.name}.$name",
            displayName = name,
            category = ApiCategory.PROPERTY,
            signature = "$name: ${type.display}",
            description = desc,
            returnType = type,
            guidebookRef = guidebookRef,
            example = example,
            since = since,
            deprecatedFor = deprecatedFor,
        )
    }
}

private fun renderSignature(name: String, params: List<LuaType.Param>, returnType: LuaType): String {
    val paramsStr = params.joinToString(", ") { "${it.name}: ${it.type.display}" }
    val retSuffix = if (returnType == LuaType.Primitive.Void) "" else " → ${returnType.display}"
    return "$name($paramsStr)$retSuffix"
}
