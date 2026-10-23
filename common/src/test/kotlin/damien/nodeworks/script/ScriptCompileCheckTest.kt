package damien.nodeworks.script

import damien.nodeworks.script.diagnostics.ScriptCompileCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptCompileCheckTest {

    @Test
    fun `clean tabs produce no problems`() {
        val problems = ScriptCompileCheck.check(
            mapOf(
                "main" to "local m = require(\"helpers\")\nprint(m.x)\n",
                "helpers" to "return { x = 1 }\n",
            ),
        )
        assertTrue(problems.isEmpty()) { "expected no problems, got $problems" }
    }

    @Test
    fun `syntax error reports the tab and line`() {
        val problems = ScriptCompileCheck.check(
            mapOf(
                "main" to "print(1)\n",
                "helpers" to "local a = 1\nlocal b = \nreturn a\n",
            ),
        )
        assertEquals(1, problems.size)
        val p = problems.single()
        assertEquals("helpers", p.tab)
        assertTrue(p.line in 2..3) { "line was ${p.line}" }
        assertTrue(p.message.isNotBlank())
    }

    @Test
    fun `nothing runs during the check`() {
        val problems = ScriptCompileCheck.check(mapOf("main" to "error(\"should not run\")\n"))
        assertTrue(problems.isEmpty())
    }
}
