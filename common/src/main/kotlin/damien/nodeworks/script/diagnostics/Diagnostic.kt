package damien.nodeworks.script.diagnostics

/**
 * A single editor diagnostic produced by [LuaDiagnostics]. The rendering layer
 * (ScriptEditor) draws an underline for each diagnostic at [range] coloured per
 * [severity], and the existing hover-tooltip path surfaces [message] when the
 * cursor lands on the range.
 *
 * [code] is the rule identifier, e.g. `"unknown-identifier"`. Each rule has a
 * single code so [LuaDiagnostics.severityForRule] can dial the rule up or down
 * from one place without touching the rule's body.
 */
data class Diagnostic(
    val severity: Severity,
    val range: TextRange,
    val code: String,
    val message: String,
)

enum class Severity {
    /** Red squiggle. Reserved for things that will fail at runtime. */
    ERROR,
    /** Yellow squiggle. Probable mistake but the runtime tolerates it. */
    WARNING,
    /** Blue squiggle. Stylistic or advisory only, not necessarily wrong. */
    HINT,
}

/** Half-open character offset range into the script's text. */
data class TextRange(val start: Int, val end: Int) {
    init { require(start <= end) { "start ($start) must be <= end ($end)" } }

    operator fun contains(offset: Int): Boolean = offset in start until end

    /** True when [other] is entirely inside this range. */
    fun encloses(other: TextRange): Boolean = other.start >= start && other.end <= end
}
