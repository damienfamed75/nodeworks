package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntryReuseTest {

    private class Entry(val key: String, var count: Long)

    @Test
    fun entriesAreBuiltOnceAndReusedAcrossCompletedPasses() {
        val reuse = EntryReuse<String, Entry>()
        var builds = 0
        val first = reuse.startPass(2).let { pass ->
            val e = pass.get("stone") { builds++; Entry("stone", 1) }
            pass.complete()
            e
        }
        val second = reuse.startPass(2).let { pass ->
            val e = pass.get("stone") { builds++; Entry("stone", 1) }
            pass.complete()
            e
        }
        assertSame(first, second)
        assertEquals(1, builds)
    }

    @Test
    fun keysMissingFromAPassAreDroppedOnComplete() {
        val reuse = EntryReuse<String, Entry>()
        reuse.startPass(2).apply { get("stone") { Entry("stone", 1) }; get("dirt") { Entry("dirt", 1) }; complete() }
        assertEquals(2, reuse.size)
        reuse.startPass(1).apply { get("stone") { Entry("stone", 1) }; complete() }
        assertEquals(1, reuse.size)

        var rebuilt = false
        reuse.startPass(1).apply { get("dirt") { rebuilt = true; Entry("dirt", 1) }; complete() }
        assertTrue(rebuilt, "a key that left should be rebuilt, not resurrected")
    }

    @Test
    fun abandonedPassKeepsThePreviousReuseSet() {
        val reuse = EntryReuse<String, Entry>()
        val original = reuse.startPass(2).let { pass ->
            val e = pass.get("stone") { Entry("stone", 1) }
            pass.get("dirt") { Entry("dirt", 1) }
            pass.complete()
            e
        }
        // A pass that only got partway, then was abandoned.
        reuse.startPass(2).get("stone") { Entry("stone", 1) }
        assertEquals(2, reuse.size)

        val again = reuse.startPass(2).let { pass -> pass.get("stone") { Entry("stone", 9) }.also { pass.complete() } }
        assertSame(original, again)
    }

    @Test
    fun newKeysJoinTheReuseSet() {
        val reuse = EntryReuse<String, Entry>()
        reuse.startPass(1).apply { get("stone") { Entry("stone", 1) }; complete() }
        reuse.startPass(2).apply { get("stone") { Entry("stone", 1) }; get("gold") { Entry("gold", 1) }; complete() }
        assertEquals(2, reuse.size)
    }
}
