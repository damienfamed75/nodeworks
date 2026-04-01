package damien.nodeworks.script

import net.minecraft.core.BlockPos

/**
 * Server-side ring buffer of recent errors per terminal.
 * Used to populate the Diagnostic Tool's Jobs tab on open.
 */
object NetworkErrorBuffer {
    data class ErrorRecord(val terminalPos: BlockPos, val message: String, val tickTime: Long)

    private val errors = mutableListOf<ErrorRecord>()
    private const val MAX_ERRORS = 100

    fun addError(terminalPos: BlockPos, message: String, tickTime: Long) {
        errors.add(0, ErrorRecord(terminalPos, message, tickTime))
        if (errors.size > MAX_ERRORS) errors.removeLast()
    }

    /** Get recent errors for terminals at the given positions. */
    fun getRecentErrors(terminalPositions: Set<BlockPos>, limit: Int = 10, currentTick: Long): List<ErrorRecord> {
        return errors.filter { it.terminalPos in terminalPositions }.take(limit)
    }

    fun clear() {
        errors.clear()
    }
}
