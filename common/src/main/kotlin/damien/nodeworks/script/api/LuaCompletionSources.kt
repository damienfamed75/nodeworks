package damien.nodeworks.script.api

/**
 * Runtime-resolved completion sources for [LuaType.StringDomain] types. Decouples the
 * type declaration ("this string is an ItemId") from the source that produces the
 * actual list of valid values ("read the live MC item registry"), so type specs stay
 * declarative while the lookup remains dynamic.
 *
 * Sources are registered at mod init by callers that have the runtime context the
 * source needs (network state, MC registries, scripting-side state). The autocomplete
 * dispatcher calls [completionsFor] when the cursor is inside a string literal whose
 * declared type is a [LuaType.StringDomain] or a [LuaType.Union] containing one.
 *
 * Sources are pure functions of [Context], no internal state is held by the registry,
 * so a source returning stale data must be re-registered to refresh.
 */
object LuaCompletionSources {

    /** Context the completion source receives at lookup time. Carries everything the
     *  autocomplete already knows, fields are filled by the dispatcher and may be
     *  blank when not applicable. */
    data class Context(
        /** What the user has typed so far inside the string literal, used for fuzzy
         *  matching when the source returns more candidates than fit on screen. */
        val partial: String,
    )

    /** One suggestion produced by a source. The autocomplete consumer wraps these in
     *  its own `Suggestion` shape (with kind icon, snippet support) at use site. */
    data class StringCompletion(
        val insert: String,
        val display: String = insert,
        /** Optional descriptor like `"(storage)"` or `"#minecraft:logs (12 items)"`. */
        val description: String? = null,
    )

    private val sources = mutableMapOf<String, (Context) -> List<StringCompletion>>()

    /** Register a source for a [LuaType.StringDomain.sourceKey]. Repeat registration
     *  with the same key replaces the previous source, useful for tests but
     *  application code should register exactly once at init. */
    fun register(sourceKey: String, source: (Context) -> List<StringCompletion>) {
        sources[sourceKey] = source
    }

    /** Resolve completions for a [sourceKey]. Returns an empty list when no source
     *  has been registered yet, autocomplete callers should treat that as "nothing
     *  to suggest" rather than an error so a missing registration doesn't break
     *  the editor. */
    fun completionsFor(sourceKey: String, context: Context): List<StringCompletion> =
        sources[sourceKey]?.invoke(context) ?: emptyList()

    /** True if a source is registered for this key. The validator uses this to
     *  flag [LuaType.StringDomain] declarations whose source key never gets
     *  registered, catching dead-domain declarations at init. */
    fun hasSource(sourceKey: String): Boolean = sourceKey in sources

    /** Test-only reset for unit tests that exercise the source registry. */
    internal fun resetForTesting() {
        sources.clear()
    }
}
