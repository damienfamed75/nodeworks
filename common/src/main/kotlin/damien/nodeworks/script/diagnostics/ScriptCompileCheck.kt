package damien.nodeworks.script.diagnostics

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.compiler.LuaC

/**
 * Compiles every tab of a program without running any of it, so syntax errors with line
 * numbers show up before a save. Safe on the client: nothing is executed and no library
 * is loaded.
 */
object ScriptCompileCheck {

    data class Problem(val tab: String, val line: Int, val message: String)

    private val LOCATION = Regex("""^(?:\[string "[^"]*"\]|[^:\n]*):(\d+):\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun check(tabs: Map<String, String>): List<Problem> {
        val globals = Globals()
        LuaC.install(globals)
        val out = ArrayList<Problem>()
        for ((tab, text) in tabs) {
            try {
                globals.load(text, "=$tab")
            } catch (e: LuaError) {
                out += parse(tab, e.message ?: "syntax error")
            }
        }
        return out
    }

    fun parse(tab: String, raw: String): Problem {
        val m = LOCATION.find(raw.trim())
        return if (m != null) Problem(tab, m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2].trim())
        else Problem(tab, 1, raw.trim())
    }
}
