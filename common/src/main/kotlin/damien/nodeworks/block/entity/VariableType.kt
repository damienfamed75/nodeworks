package damien.nodeworks.block.entity

enum class VariableType(val defaultValue: String) {
    NUMBER("0"),
    STRING(""),
    BOOL("false");

    fun validate(value: String): Boolean = when (this) {
        NUMBER -> value.toDoubleOrNull() != null
        STRING -> value.length <= 256
        BOOL -> value == "true" || value == "false"
    }

    fun sanitize(value: String): String = when (this) {
        NUMBER -> (value.toDoubleOrNull() ?: 0.0).let {
            if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
        }
        STRING -> value.take(256)
        BOOL -> if (value == "true") "true" else "false"
    }

    companion object {
        fun fromOrdinal(ordinal: Int): VariableType = entries.getOrElse(ordinal) { NUMBER }
    }
}
