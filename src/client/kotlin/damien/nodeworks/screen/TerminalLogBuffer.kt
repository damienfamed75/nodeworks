package damien.nodeworks.screen

import net.minecraft.core.BlockPos

/**
 * Client-side buffer for terminal log messages received from the server.
 * The terminal screen reads from this when rendering.
 */
object TerminalLogBuffer {

    data class LogEntry(val message: String, val isError: Boolean)

    private val logs = mutableMapOf<BlockPos, MutableList<LogEntry>>()
    private const val MAX_ENTRIES = 100

    fun addLog(terminalPos: BlockPos, message: String, isError: Boolean) {
        val entries = logs.getOrPut(terminalPos) { mutableListOf() }
        entries.add(LogEntry(message, isError))
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    fun getLogs(terminalPos: BlockPos): List<LogEntry> {
        return logs[terminalPos] ?: emptyList()
    }

    fun clear(terminalPos: BlockPos) {
        logs.remove(terminalPos)
    }
}
