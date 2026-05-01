package damien.nodeworks.script

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.DebugLib

/**
 * Wall-clock execution gate around Lua entry points. Mirrors CC: Tweaked's
 * safety model: a soft-abort throws a [TimeoutLuaError] from the bytecode hook
 * when a deadline is exceeded, propagating up through the standard error path.
 * No hard abort. The per-tick budget at [NeoForgeTerminalPackets.tickAll] is
 * the layered backstop for scripts that catch the soft-abort via `pcall` and
 * keep looping.
 *
 * Each Lua entry point (top-level chunk, scheduler tick callback,
 * redstone/observer onChange) runs with its own budget: enter the gate, set a
 * deadline relative to `nanoTime()`, run the body, clear the deadline on exit.
 * Idle scripts that just registered handlers consume no budget time because no
 * Lua bytecode is running.
 *
 * Mechanism: install a custom [DebugLib] subclass at [Globals.debuglib]
 * (bypassing the normal `g.load(DebugLib())` path so the `debug` global table
 * is not exposed to scripts). The bytecode interpreter calls
 * `globals.debuglib.onInstruction(...)` for every Lua bytecode; our subclass
 * increments a counter and checks the wall-clock deadline once every
 * [ServerSafetySettings.instructionsPerWallClockCheck] instructions. On
 * overrun the hook throws, the caller catches and routes to the appropriate
 * cleanup (autoRun-disable for top-level, eviction for callbacks).
 *
 * The hook only fires while a deadline is active (`deadlineNs > 0`), so calls
 * into Lua that happen outside a gated entry don't trip a stale deadline.
 */
class LuaExecGate(
    /** Settings provider, called on each gated entry so live `/reload` of
     *  `serverconfig/nodeworks-server.toml` is picked up by in-flight engines on
     *  their next call into Lua. Defaults to [ServerPolicy.current] in production
     *  paths, tests can pass a fixed lambda for deterministic budgets. */
    private val settingsProvider: () -> ServerSafetySettings = { ServerPolicy.current },
) {

    /** Active deadline in [System.nanoTime] units. 0 means no active gate. The hook
     *  reads this on every Nth instruction, so writes from the gate's body must
     *  happen-before any execution that the hook will see. Which is guaranteed
     *  because both run on the same thread (Lua executes on the server tick thread,
     *  no coroutines exposed). */
    @Volatile
    private var deadlineNs: Long = 0L

    /** Snapshot of the current gated entry's instructions-per-check budget. Read
     *  by the hook every instruction; written once per [runWithDeadline] call from
     *  the active settings. Stored as a field rather than a local so the inner
     *  [GuardedDebugLib.onInstruction] override can read it without thread/context
     *  plumbing. */
    private var instructionsPerCheck: Int = ServerSafetySettings.Defaults.instructionsPerWallClockCheck

    /** Counter for spreading wall-clock checks across instructions. Reset to 0
     *  whenever the hook actually performs a check. */
    private var checkCounter: Int = 0

    /** Subclassed [DebugLib] installed at [Globals.debuglib]. Inherits the call /
     *  return / line tracking machinery (so error tracebacks still work) and adds
     *  a wall-clock budget check on each instruction. */
    private inner class GuardedDebugLib : DebugLib() {
        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            super.onInstruction(pc, v, top)
            // Cheap fast-path when no deadline is set (idle script), avoids even
            // reading nanoTime when no gate is active.
            if (deadlineNs == 0L) return
            if (++checkCounter < instructionsPerCheck) return
            checkCounter = 0
            if (System.nanoTime() > deadlineNs) {
                // Throw a typed subclass so [isTimeoutError] can detect timeouts
                // by exception identity rather than message-string matching. LuaJ's
                // interpreter prepends "source:line " to LuaError messages before
                // they propagate to our catch site, so a startsWith check on the
                // message would fail and timeouts would silently be misclassified
                // as regular errors (no autoRun-disable, no callback eviction).
                throw TimeoutLuaError("Script took too long to run and was stopped.")
            }
        }
    }

    /** Sentinel [LuaError] subclass thrown by the wall-clock hook on soft-abort.
     *  LuaJ rethrows the same exception instance after fillTraceback, so type
     *  identity survives the round-trip, `e is TimeoutLuaError` is the reliable
     *  detection path even though `e.message` will have a `"source:line "` prefix
     *  added by the interpreter. */
    class TimeoutLuaError(reason: String) : LuaError(reason)

    /** Sentinel [LuaError] subclass for "fatal" script-side faults that should
     *  stop the engine rather than evict-and-continue: the per-kind callback
     *  cap being hit, primarily. Distinguished from [TimeoutLuaError] (which
     *  evicts only the offending callback) because a registry-full error
     *  re-throws *every tick* the same callback fire, dedup'ing via
     *  eviction wouldn't help because the OTHER callbacks driving registration
     *  keep running. The fatal classification kills the engine outright + locks
     *  the terminal so the player has to fix the script before it runs again.
     *
     *  [cleanReason] preserves the original message free of LuaJ's `source:line`
     *  prefix and stack-traceback append. LuaJ overwrites `message` during
     *  fillTraceback, so this is the only way to get the original text back
     *  after the exception's been through the interpreter. */
    class FatalScriptError(val cleanReason: String) : LuaError(cleanReason)

    /** Check the active deadline from outside the bytecode hook. Throws
     *  [TimeoutLuaError] if the current gated call has overrun its budget.
     *
     *  The bytecode hook only fires while Lua is executing Lua. When a Lua loop
     *  calls into a Kotlin function (e.g. `while true do print() end`), the time
     *  spent inside Kotlin is invisible to the hook, only re-entering Lua
     *  triggers another hook check. For Kotlin functions that may be called
     *  many times in a loop (or that themselves do non-trivial work) this
     *  method gives them a way to assert the budget proactively, so the user's
     *  pathological case `while true do print() end` aborts close to its
     *  configured budget rather than running for many seconds while the hook
     *  starves.
     *
     *  Cheap when no gate is active (single volatile read) and cheap on the
     *  hot path (one nanoTime call + branch). Idempotent, call freely. */
    fun checkDeadline() {
        if (deadlineNs == 0L) return
        if (System.nanoTime() > deadlineNs) {
            throw TimeoutLuaError("Script took too long to run and was stopped.")
        }
    }

    /** Install this gate on [globals]. Must be called once after the Globals are
     *  created and BEFORE any Lua code runs. Mirrors what `g.load(DebugLib())`
     *  would do internally (assigning to [Globals.debuglib] so the bytecode
     *  interpreter routes per-instruction hooks here) but without exposing the
     *  `debug` global table to scripts (which would let them call `debug.sethook`
     *  to override our hook). */
    fun installOn(globals: Globals) {
        val lib = GuardedDebugLib()
        // DebugLib.call(modname, env) sets `globals = env.checkglobals()` AND
        // `globals.debuglib = this`, plus populates a `debug` table on env. We
        // pipe through call() to satisfy the globals-field initialisation, then
        // strip the exposed `debug` global immediately so scripts can't reach it.
        lib.call(org.luaj.vm2.LuaValue.valueOf("debug"), globals)
        globals.set("debug", org.luaj.vm2.LuaValue.NIL)
    }

    /** Run [body] under the top-level wall-clock budget. Throws [LuaError] on Lua
     *  errors AND on timeout. The caller is expected to catch [LuaError], detect
     *  whether it was a timeout via [isTimeoutError], and on timeout disable the
     *  terminal's `autoRun` so the bad script doesn't re-fire on world reload. */
    fun <T> runTopLevel(label: String, body: () -> T): T {
        val s = settingsProvider()
        return runWithDeadline(label, s.topLevelSoftAbortMs * 1_000_000L, s.instructionsPerWallClockCheck, body)
    }

    /** Run [body] under the per-callback wall-clock budget. Returns [Outcome.Ok]
     *  on success, [Outcome.TimedOut] when the soft-abort fires (caller should
     *  evict the callback from its registry), or [Outcome.Errored] for a regular
     *  Lua error. The body's exceptions are caught here so engines can keep
     *  running other callbacks after one misbehaves. */
    fun runCallback(label: String, body: () -> Unit): Outcome {
        val s = settingsProvider()
        return try {
            runWithDeadline(label, s.callbackSoftAbortMs * 1_000_000L, s.instructionsPerWallClockCheck, body)
            Outcome.Ok
        } catch (e: TimeoutLuaError) {
            Outcome.TimedOut
        } catch (e: FatalScriptError) {
            // Don't downgrade to Errored, the caller (runGatedCallback) needs to
            // see this as Fatal so it can stop the engine outright. Use
            // [cleanReason] (the original message we threw) so the log skips
            // LuaJ's source:line prefix + stack traceback.
            Outcome.Fatal(e.cleanReason)
        } catch (e: LuaError) {
            Outcome.Errored(e.message ?: "lua error")
        } catch (e: Exception) {
            Outcome.Errored(e.message ?: e.javaClass.simpleName)
        }
    }

    private inline fun <T> runWithDeadline(
        @Suppress("UNUSED_PARAMETER") label: String,
        budgetNs: Long,
        instructionsPerCheckSnapshot: Int,
        body: () -> T,
    ): T {
        // Re-entrancy: if a gated call fires another gated call, the inner
        // call's deadline replaces the outer for the inner's duration. We
        // restore the outer on exit. In practice re-entrancy is rare (Lua
        // entry points don't recurse through the gate), but the restore keeps
        // the contract clean if it ever happens. [label] is currently unused
        // here but kept on the signature so callers can supply a diagnostic
        // tag, the throw site can pick it up if we ever want to surface it.
        val outerDeadline = deadlineNs
        val outerCounter = checkCounter
        val outerInstructionsPerCheck = instructionsPerCheck
        deadlineNs = System.nanoTime() + budgetNs
        checkCounter = 0
        instructionsPerCheck = instructionsPerCheckSnapshot
        return try {
            body()
        } finally {
            deadlineNs = outerDeadline
            checkCounter = outerCounter
            instructionsPerCheck = outerInstructionsPerCheck
        }
    }

    /** Was this LuaError raised by our soft-abort hook? Identifies via the typed
     *  exception class so it survives LuaJ's `source:line` prefixing of
     *  `e.message` during traceback fill. Used by callers that need to
     *  distinguish timeouts from other Lua errors (e.g. top-level
     *  autoRun-disable only fires on timeouts). */
    fun isTimeoutError(e: LuaError): Boolean = e is TimeoutLuaError

    /** Outcome of a gated callback execution.
     *
     *  - [Ok]: completed cleanly.
     *  - [TimedOut]: wall-clock soft-abort fired; caller should evict the
     *    callback from its registry AND lock the terminal (so it can't
     *    auto-restart into the same broken state).
     *  - [Errored]: regular Lua error; log and continue. Other callbacks in
     *    the same engine are unaffected.
     *  - [Fatal]: script-level fault severe enough to stop the entire engine
     *    (e.g. the callback registry hit its cap). The caller must abort the
     *    rest of this tick's dispatch and stop the engine. Eviction alone
     *    isn't enough because peer callbacks driving the registration
     *    pressure would just re-fire next tick. */
    sealed class Outcome {
        object Ok : Outcome()
        object TimedOut : Outcome()
        data class Errored(val message: String) : Outcome()
        data class Fatal(val message: String) : Outcome()
    }
}
