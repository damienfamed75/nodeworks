package damien.nodeworks.screen.widget

import damien.nodeworks.script.LuaBlockBalance
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sanity checks for [LuaBlockBalance.shouldInsertAutoEnd], the decision the
 * editor's Enter handler routes through. The auto-`end` insertion is easy to
 * get wrong (count too many openers, mis-pair a closer in a sibling block,
 * spuriously fire inside an already-balanced structure), so each shape we've
 * had to reason about gets its own pinned case here.
 */
class ScriptEditorBalanceTest {

    @Test
    fun shouldInsertAutoEndForBareIfThen() {
        // Cursor at end of "if true then" with nothing else, block is unclosed,
        // auto-end should fire.
        val text = "if true then"
        val curLine = "if true then"
        val col = curLine.length
        assertTrue(LuaBlockBalance.shouldInsertAutoEnd(curLine, col, text, cursorPos = text.length))
    }

    @Test
    fun shouldNotInsertAutoEndInsideAlreadyClosedIfElseif() {
        // Cursor at end of `if true then`, but the surrounding script already
        // has a matching `end` that closes this block, so auto-end MUST stay
        // out of the way.
        val text = "if true then\n    \n    print(\"hello\")\nelseif false then\n    \nend"
        val curLine = "if true then"
        val col = curLine.length
        val cursorPos = curLine.length
        assertFalse(
            LuaBlockBalance.shouldInsertAutoEnd(curLine, col, text, cursorPos),
            "balanced if/elseif/end shouldn't trigger auto-end",
        )
    }

    @Test
    fun shouldNotFireWhenInnerBlockIsClosedEvenIfOuterFunctionIsNot() {
        // Inner `if/end` is closed; the outer `function` has no matching end.
        // The cursor-relative walk only cares about whether THIS opener has a
        // closer ahead of it, the outer function's missing end is irrelevant
        // to the decision at the inner `if`.
        val text = "function foo()\n    if true then\n    end"
        val curLine = "    if true then"
        val col = curLine.length
        val cursorPos = "function foo()\n    if true then".length
        assertFalse(
            LuaBlockBalance.shouldInsertAutoEnd(curLine, col, text, cursorPos),
            "inner if has its own end already, don't add another",
        )
    }

    @Test
    fun shouldFireForOuterFunctionThatHasNoMatchingEnd() {
        // Symmetric case: cursor at end of the function declaration line. The
        // function isn't closed (no end at the bottom), so we DO want auto-end.
        val text = "function foo()\n    print('hi')"
        val curLine = "function foo()"
        val col = curLine.length
        val cursorPos = curLine.length
        assertTrue(LuaBlockBalance.shouldInsertAutoEnd(curLine, col, text, cursorPos))
    }

    @Test
    fun shouldInsertAutoEndForElseifInUnclosedBlock() {
        // Inverse case: `elseif false then` typed but no `end` exists yet.
        val text = "if true then\n    print(\"a\")\nelseif false then"
        val curLine = "elseif false then"
        val col = curLine.length
        assertTrue(LuaBlockBalance.shouldInsertAutoEnd(curLine, col, text, cursorPos = text.length))
    }

    @Test
    fun shouldNotInsertAutoEndMidLine() {
        // Cursor in the middle of `if true then` (e.g. between `true` and `then`)
        // shouldn't fire, we only auto-end when "completing" the opener at EOL.
        val curLine = "if true then"
        assertFalse(LuaBlockBalance.shouldInsertAutoEnd(curLine, 7, curLine, cursorPos = 7))
    }

    @Test
    fun shouldNotInsertAutoEndOnNonOpener() {
        // Plain `print(...)` line doesn't open anything.
        val text = "print('hello')"
        assertFalse(LuaBlockBalance.shouldInsertAutoEnd(text, text.length, text, cursorPos = text.length))
    }

    @Test
    fun shouldInsertAutoEndForFunctionDecl() {
        val text = "function foo()"
        assertTrue(LuaBlockBalance.shouldInsertAutoEnd(text, text.length, text, cursorPos = text.length))
    }

    @Test
    fun shouldFireForNewOpenerNestedInsideForLoopWithExistingEnd() {
        // The `for`'s `end` is at the for's indent; the user just typed an
        // `if` deeper inside. Lua's LIFO grammar would silently let that
        // existing `end` close the new `if`, leaving the for hanging. The
        // editor treats the shallower-indent closer as belonging to the
        // outer block and inserts a fresh `end` for the if.
        val text = "scheduler:second(function()\n    for _, breaker in breakers:list() do\n        if true then\n    end\nend)"
        val curLine = "        if true then"
        val cursorPos = "scheduler:second(function()\n    for _, breaker in breakers:list() do\n        if true then".length
        assertTrue(
            LuaBlockBalance.shouldInsertAutoEnd(curLine, curLine.length, text, cursorPos),
            "new opener nested deeper than next closer should still trigger auto-end",
        )
    }

    @Test
    fun shouldNotFireOnFullScriptWithTwoBalancedIfBlocks() {
        val fullText = """
            local input = network:get("input")
            local buffer = network:get("buffer")

            local fromBuf = buffer:find("*")
            local fromInput = input:find("*")

            if true then
                print("hello")
            elseif false then

            end

            if fromBuf then
                input:insert(fromBuf)
            elseif fromInput then
                buffer:insert(fromInput)
            end
        """.trimIndent()
        val curLine = "if true then"
        val cursorPos = fullText.indexOf("if true then") + curLine.length
        assertFalse(
            LuaBlockBalance.shouldInsertAutoEnd(curLine, curLine.length, fullText, cursorPos),
            "all blocks balanced, auto-end shouldn't fire",
        )
    }
}
