package damien.nodeworks.script.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test

class LuaDiagnosticsTest {

    private class IdCase(
        val name: String,
        val script: String,
        val expectedUnknowns: List<String>,
        val symbols: Map<String, String> = emptyMap(),
    )

    @TestFactory
    fun unknownIdentifierFlagsTypos(): List<DynamicTest> {
        val cases = listOf(
            IdCase("clean script", "local x = 1\nprint(x)", emptyList()),
            IdCase("misspelled global", "netwrok:get('a')", listOf("netwrok")),
            IdCase("misspelled bare function", "prit('hi')", listOf("prit")),
            IdCase("undeclared local", "print(notDefined)", listOf("notDefined")),
            IdCase(
                "local declared then used is fine",
                "local x = 1\nprint(x)",
                emptyList(),
            ),
            IdCase(
                "function declaration adds the name",
                "function helper() end\nhelper()",
                emptyList(),
            ),
            IdCase(
                "function param is in scope",
                "function f(a) print(a) end",
                emptyList(),
            ),
            IdCase(
                "for-loop binding is in scope",
                "local xs = {1,2,3}\nfor _, v in xs do print(v) end",
                emptyList(),
            ),
            IdCase(
                "numeric for-loop binding is in scope",
                "for i=1, 5 do print(i) end",
                emptyList(),
            ),
            IdCase(
                "numeric for-loop with spaces around equals is in scope",
                "for i = 1, 5 do print(i) end",
                emptyList(),
            ),
            IdCase(
                "numeric for-loop with step is in scope",
                "for i=1, 10, 2 do print(i) end",
                emptyList(),
            ),
            IdCase(
                "nested unknown still flagged",
                "function f(a) print(typo) end",
                listOf("typo"),
            ),
            IdCase(
                "multiple unknowns flagged separately",
                "alpha = bet + gam",
                listOf("alpha", "bet", "gam"),
            ),
            IdCase(
                "module global recognised",
                "network:debug()",
                emptyList(),
            ),
            IdCase(
                "stdlib module recognised",
                "print(string.format('%d', 5))",
                emptyList(),
            ),
            IdCase(
                "type annotation is not a reference",
                "local items: ItemsHandle = network:find('coal')",
                emptyList(),
            ),
            IdCase(
                "function param annotation is not a reference",
                "function handle(card: CardHandle) print(card.name) end",
                emptyList(),
                symbols = mapOf("card" to "CardHandle"),
            ),
            IdCase(
                "comments are ignored",
                "-- prit is a typo, but inside a comment\nprint('ok')",
                emptyList(),
            ),
            IdCase(
                "string contents are ignored",
                "print('netwrok is fine inside a string')",
                emptyList(),
            ),
            IdCase(
                "keyword typo surfaces as unknown identifier",
                "funtcion f() end",
                // 'funtcion' is the typo; 'f' is then an undeclared name because
                // 'function' wasn't actually declared. Both flagged.
                listOf("funtcion", "f"),
            ),
        )

        return cases.map { c ->
            dynamicTest(c.name) {
                val diags = LuaDiagnostics.analyze(c.script, c.symbols)
                val unknowns = diags
                    .filter { it.code == "unknown-identifier" }
                    .map { c.script.substring(it.range.start, it.range.end) }
                assertEquals(c.expectedUnknowns, unknowns, "got: $diags")
            }
        }
    }

    @Test
    fun severityComesFromTheRuleTable() {
        val diags = LuaDiagnostics.analyze("missingName")
        val diag = diags.firstOrNull()
        assertNotNull(diag, "expected an unknown-identifier diagnostic")
        assertEquals(Severity.ERROR, diag!!.severity, "default severity is ERROR")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Member access (unknown-method, unknown-property)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun unknownMethodOnTypedReceiverFlags() {
        val script = "card:fnid()"
        val diags = LuaDiagnostics.analyze(script, mapOf("card" to "CardHandle"))
        val methodDiag = diags.firstOrNull { it.code == "unknown-method" }
        assertNotNull(methodDiag, "expected unknown-method diagnostic, got $diags")
        assertEquals("fnid", script.substring(methodDiag!!.range.start, methodDiag.range.end))
    }

    @Test
    fun knownMethodOnTypedReceiverIsClean() {
        val diags = LuaDiagnostics.analyze("card:find('coal')", mapOf("card" to "CardHandle"))
        assertTrue(
            diags.none { it.code == "unknown-method" || it.code == "unknown-property" },
            "expected no member diagnostics, got $diags",
        )
    }

    @Test
    fun unknownPropertyOnTypedReceiverFlags() {
        val script = "items.cuont"
        val diags = LuaDiagnostics.analyze(script, mapOf("items" to "ItemsHandle"))
        val diag = diags.firstOrNull { it.code == "unknown-property" }
        assertNotNull(diag)
        assertEquals("cuont", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableTypeStillResolvesForMembers() {
        // ItemsHandle? should still know its methods (we strip the ? before lookup)
        val diags = LuaDiagnostics.analyze("items.count", mapOf("items" to "ItemsHandle?"))
        assertTrue(
            diags.none { it.code == "unknown-method" || it.code == "unknown-property" },
            "got $diags",
        )
    }

    @Test
    fun unknownReceiverDoesNotFlagMember() {
        // We don't know what type 'whatever' is; skip the member check rather
        // than blame the member.
        val script = "whatever:method()"
        val diags = LuaDiagnostics.analyze(script)
        // 'whatever' is unknown-identifier (the bare receiver), but 'method'
        // is NOT flagged because we have no type info to validate it against.
        assertTrue(
            diags.none { it.code == "unknown-method" },
            "method should not be flagged when receiver type is unknown, got $diags",
        )
        assertTrue(
            diags.any { it.code == "unknown-identifier" },
            "receiver should be flagged as unknown identifier",
        )
    }

    @Test
    fun moduleMethodKnownAreClean() {
        val diags = LuaDiagnostics.analyze("network:debug()")
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun stdlibMethodKnownAreClean() {
        val diags = LuaDiagnostics.analyze("print(math.max(1, 2))")
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun stdlibMethodTypoFlags() {
        val script = "print(math.maxx(1, 2))"
        val diags = LuaDiagnostics.analyze(script)
        val diag = diags.firstOrNull { it.code == "unknown-method" }
        assertNotNull(diag)
        assertEquals("maxx", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun emptyScriptProducesNoDiagnostics() {
        assertEquals(emptyList<Diagnostic>(), LuaDiagnostics.analyze(""))
    }
}
