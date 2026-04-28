package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hover-resolution tests for type-name tokens in handler signatures and
 * elsewhere. Anchored on the registry-driven type docs so a regression in
 * registration (or in the resolveAt fall-through) shows up here before it
 * reaches the editor.
 */
class LuaApiDocsHoverTest {

    private fun tokenize(line: String): List<LuaTokenizer.Token> =
        LuaTokenizer.tokenize(line)

    @Test
    fun typeLiteralAfterAnnotationResolvesToTypeDoc() {
        val tokens = tokenize("function(job: Job, items: InputItems)")
        val jobIdx = tokens.indexOfFirst { it.text == "Job" }
        assertTrue(jobIdx >= 0, "expected a 'Job' token in the line")

        val doc = LuaApiDocs.resolveAt(tokens, jobIdx)
        val resolved = requireNotNull(doc) { "expected hover doc for 'Job'" }
        assertEquals("Job", resolved.signature)
        assertNotNull(resolved.guidebookRef, "Job's typeDoc should carry a guidebookRef")
        assertTrue(resolved.guidebookRef!!.contains("job"))
    }

    @Test
    fun inputItemsLiteralResolves() {
        val tokens = tokenize("function(job: Job, items: InputItems)")
        val idx = tokens.indexOfFirst { it.text == "InputItems" }
        assertTrue(idx >= 0)

        val doc = LuaApiDocs.resolveAt(tokens, idx)
        val resolved = requireNotNull(doc) { "expected hover doc for 'InputItems'" }
        assertNotNull(resolved.guidebookRef)
    }

    @Test
    fun bareUnknownIdentifierReturnsNull() {
        val tokens = tokenize("local frobnicate = 1")
        val idx = tokens.indexOfFirst { it.text == "frobnicate" }
        assertTrue(idx >= 0)
        assertNull(LuaApiDocs.resolveAt(tokens, idx))
    }

    @Test
    fun typeLiteralResolvesEvenWhenOwnerLocalIsTyped() {
        // Mirrors the editor: when hovering `Job` in `function(job: Job, ...)`
        // the symbol-table provider returns `{job=Job, items=InputItems}`. The
        // resolver must still fall through past the typed-owner branch to
        // `entries["Job"]` so the type literal itself shows its doc.
        val tokens = tokenize("function(job: Job, items: InputItems)")
        val idx = tokens.indexOfFirst { it.text == "Job" }
        val doc = LuaApiDocs.resolveAt(
            tokens,
            idx,
            variableTypes = mapOf("job" to "Job", "items" to "InputItems"),
        )
        val resolved = requireNotNull(doc) { "expected hover doc for 'Job' even with symbols" }
        assertNotNull(resolved.guidebookRef)
        assertTrue(resolved.description.isNotBlank(), "expected non-empty description, got '${resolved.description}'")
    }

    @Test
    fun paramVariableHoverInheritsTypeDescriptionAndGuidebookRef() {
        // Mirrors hovering the param NAME (`job`) instead of the type literal.
        // The variable-hover synthesiser at the bottom of resolveAt should pull
        // description + guidebookRef from the type's registered doc so the
        // tooltip isn't a bare `job: Job` line with nothing else.
        val tokens = tokenize("function(job: Job, items: InputItems)")
        val jobIdx = tokens.indexOfFirst { it.text == "job" }
        val doc = LuaApiDocs.resolveAt(
            tokens,
            jobIdx,
            variableTypes = mapOf("job" to "Job", "items" to "InputItems"),
        )
        val resolved = requireNotNull(doc) { "expected hover doc for 'job'" }
        assertEquals("job: Job", resolved.signature)
        assertTrue(
            resolved.description.isNotBlank(),
            "variable hover should inherit Job's description, got '${resolved.description}'",
        )
        assertNotNull(
            resolved.guidebookRef,
            "variable hover should inherit Job's guidebookRef so [G] navigation works",
        )
    }

    @Test
    fun directGetReturnsTypeDocWithDescriptionAndGuidebookRef() {
        // Sanity: confirm the registry actually populates entries["Job"] /
        // entries["InputItems"] with the expected fields. If this fails the
        // bootstrap or the toLegacyDoc conversion is broken upstream of
        // resolveAt and every other hover test is invalidated.
        val jobDoc = requireNotNull(LuaApiDocs.get("Job")) { "Job not registered" }
        assertTrue(jobDoc.description.isNotBlank(), "Job has empty description: '${jobDoc.description}'")
        assertNotNull(jobDoc.guidebookRef, "Job has null guidebookRef")

        val inputItemsDoc = requireNotNull(LuaApiDocs.get("InputItems")) { "InputItems not registered" }
        assertTrue(inputItemsDoc.description.isNotBlank())
        assertNotNull(inputItemsDoc.guidebookRef)
    }

    @Test
    fun userExactScriptScenarioJobHoverWorks() {
        // The exact script the user reported. Tokenise the handler line
        // (line 3, 0-indexed) and verify hovering each interesting token
        // returns a Doc with description + guidebookRef.
        val handlerLine =
            "network:handle(\"minecraft:raw_copper@1>>minecraft:copper_ingot@1\", " +
                    "function(job: Job, items: InputItems)"
        val tokens = tokenize(handlerLine)
        // Editor's symbolTableProvider would have populated this from the
        // function-param annotations during buildSymbolTable. Mirror that here.
        val symbols = mapOf(
            "copper" to "CardHandle",
            "iron" to "CardHandle",
            "job" to "Job",
            "items" to "InputItems",
        )

        for (target in listOf("job", "Job", "items", "InputItems")) {
            val idx = tokens.indexOfFirst { it.text == target }
            assertTrue(idx >= 0, "expected token '$target' in handler line")
            val doc = LuaApiDocs.resolveAt(tokens, idx, symbols)
            val resolved = requireNotNull(doc) { "expected hover doc for '$target'" }
            assertTrue(
                resolved.description.isNotBlank(),
                "'$target' description was blank: typeDoc lookup probably failed",
            )
            assertNotNull(
                resolved.guidebookRef,
                "'$target' guidebookRef was null: typeDoc lookup probably failed",
            )
        }
    }

    @Test
    fun networkHandleSignatureHoversBehaveLikeBareFunction() {
        // Exact shape the user reported: `network:handle(name, function(...))`
        // hovering each of the four interesting tokens.
        val line = "network:handle(\"x\", function(job: Job, items: InputItems)"
        val tokens = tokenize(line)
        val symbols = mapOf("job" to "Job", "items" to "InputItems")

        for (target in listOf("job", "Job", "items", "InputItems")) {
            val idx = tokens.indexOfFirst { it.text == target }
            assertTrue(idx >= 0, "expected token '$target' in '$line'")
            val doc = LuaApiDocs.resolveAt(tokens, idx, symbols)
            val resolved = requireNotNull(doc) { "expected hover doc for '$target'" }
            assertTrue(
                resolved.description.isNotBlank(),
                "'$target' hover should carry a description, got '${resolved.description}'",
            )
            assertNotNull(
                resolved.guidebookRef,
                "'$target' hover should carry a guidebookRef",
            )
        }
    }
}
