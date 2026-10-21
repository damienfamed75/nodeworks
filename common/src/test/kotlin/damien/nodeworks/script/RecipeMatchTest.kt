package damien.nodeworks.script

import damien.nodeworks.card.HandlerParamNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipeMatchTest {

    /** A stand-in ingredient: an id, the "tags" it carries, and whether it is a chance output. */
    private data class Ingr(val id: String, val tags: Set<String> = emptySet(), val optional: Boolean = false)

    private fun matchesRule(ingr: Ingr, rule: String): Boolean =
        if (rule.startsWith("#")) rule.removePrefix("#") in ingr.tags else rule == ingr.id

    private fun matches(
        inputs: List<Ingr>,
        outputs: List<Ingr>,
        allow: List<String>,
        deny: List<String> = emptyList(),
        outputRules: List<String>? = null,
    ): Boolean = RecipeMatch.matches(
        inputs, outputs,
        inputAllowed = { i -> allow.isEmpty() || allow.any { matchesRule(i, it) } },
        inputDenied = { i -> deny.any { matchesRule(i, it) } },
        outputAllowed = outputRules?.let { rules -> { o: Ingr -> rules.any { matchesRule(o, it) } } },
        isOptional = { it.optional },
    )

    private val rawIron = Ingr("minecraft:raw_iron", setOf("c:raw_materials"))
    private val ironIngot = Ingr("minecraft:iron_ingot", setOf("c:ingots"))
    private val nickelIngot = Ingr("mod:nickel_ingot", setOf("c:ingots"))
    private val coal = Ingr("minecraft:coal")
    private val steel = Ingr("mod:steel_ingot", setOf("c:ingots"))
    private val crushed = Ingr("mod:crushed_iron")

    @Test
    fun aSingleInputSetMatchesItsTag() {
        assertTrue(matches(listOf(rawIron), listOf(ironIngot), allow = listOf("#c:raw_materials")))
    }

    @Test
    fun everyInputMustMatchSoAnAlloyWithCoalIsLeftOut() {
        assertFalse(matches(listOf(ironIngot, coal), listOf(steel), allow = listOf("#c:ingots")))
    }

    @Test
    fun anAlloyOfOnlyMatchingInputsStillMatches() {
        assertTrue(matches(listOf(ironIngot, nickelIngot), listOf(steel), allow = listOf("#c:ingots")))
    }

    @Test
    fun anyInputHittingExceptLeavesTheSetOut() {
        assertFalse(
            matches(listOf(ironIngot, nickelIngot), listOf(steel), allow = listOf("#c:ingots"), deny = listOf("mod:nickel_ingot"))
        )
    }

    @Test
    fun outputsNarrowTheSameInputToOneMachine() {
        val smelt = listOf(ironIngot)
        val crush = listOf(crushed)
        val rules = listOf("#c:ingots")
        assertTrue(matches(listOf(rawIron), smelt, allow = listOf("#c:raw_materials"), outputRules = rules))
        assertFalse(matches(listOf(rawIron), crush, allow = listOf("#c:raw_materials"), outputRules = rules))
    }

    @Test
    fun aChanceOutputDoesNotDecideTheMatch() {
        val outputs = listOf(ironIngot, Ingr("mod:nugget", optional = true))
        assertTrue(matches(listOf(rawIron), outputs, allow = listOf("#c:raw_materials"), outputRules = listOf("#c:ingots")))
    }

    @Test
    fun outputRulesNeedAnOutputToCheck() {
        val onlyChance = listOf(Ingr("mod:nugget", optional = true))
        assertFalse(matches(listOf(rawIron), onlyChance, allow = emptyList(), outputRules = listOf("#c:ingots")))
    }

    @Test
    fun noRulesMatchesEverySetWithInputs() {
        assertTrue(matches(listOf(ironIngot, coal), listOf(steel), allow = emptyList()))
        assertFalse(matches(emptyList(), listOf(steel), allow = emptyList()))
    }

    @Test
    fun anItemNamedAllNeverHidesTheAllMethod() {
        assertEquals(listOf("all2", "rawIron", "all3"), HandlerParamNames.build(listOf("mod:all" to 1, "minecraft:raw_iron" to 1, "mod:all" to 1)))
    }
}
