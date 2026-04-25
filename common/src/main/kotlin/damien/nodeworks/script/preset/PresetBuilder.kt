package damien.nodeworks.script.preset

import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.script.ScriptEngine
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Shared plumbing for Lua preset builders (Importer, Stocker).
 *
 * Holds the common state and the fluent methods `:every / :build / :run /
 * :start / :stop / :isRunning` so subclasses can't silently diverge on
 * lifecycle semantics. Subclasses add their own configuration methods
 * (`:from`, `:to`, etc.) via [populateMethods] and the per-tick logic
 * via [tickOnce].
 *
 * Lifecycle: a builder registers itself with the engine at construction
 * (so even "dangling" builders that the user never calls `:start()` on get
 * torn down when the script stops). `ScriptEngine.stop()` iterates every
 * registered preset and calls [stop] on it before wiping the scheduler.
 *
 * Card resolution caching: subclasses check [lastSnapshotSeen] inside
 * [tickOnce] to decide when to re-resolve card names. The base class
 * invokes [onSnapshotChanged] and updates the reference automatically,
 * so subclasses just need to rebuild their cached lookups in that hook.
 */
abstract class PresetBuilder<TSelf : PresetBuilder<TSelf>>(
    protected val engine: ScriptEngine,
) {
    protected var intervalTicks: Int = 20
    private var taskId: Int? = null
    private var isRunningFlag: Boolean = false

    /** The [NetworkSnapshot] seen at the most recent [safeTick]. Subclasses
     *  compare against `engine.currentSnapshot()` to detect topology churn. */
    protected var lastSnapshotSeen: NetworkSnapshot? = null
        private set

    /** Index in the engine's presets registry. Set by [ScriptEngine.registerPreset]. */
    internal var registryIndex: Int = -1

    private var cachedTable: LuaTable? = null

    /** The per-interval work. Called with `lastSnapshotSeen` already refreshed
     *  and [onSnapshotChanged] already invoked on topology change. */
    protected abstract fun tickOnce()

    /** Throw [LuaError] if the builder isn't configured enough to run. */
    protected abstract fun validate()

    /** Subclass registration point for its own chain methods (`:from`, `:to`, ...).
     *  The common methods have already been installed on [t] by the time this is called. */
    protected abstract fun populateMethods(t: LuaTable)

    /** Short label used in log messages. */
    protected abstract val presetName: String

    /** Hook for subclasses to refresh any cached card resolutions when the
     *  network topology changes. [lastSnapshotSeen] is still the previous
     *  snapshot when this is called; the base class updates it afterwards. */
    protected open fun onSnapshotChanged(snapshot: NetworkSnapshot) {}

    @Suppress("UNCHECKED_CAST")
    protected fun self(): TSelf = this as TSelf

    fun every(ticks: Int): TSelf {
        require(ticks >= 1) { "$presetName:every(ticks) requires ticks >= 1" }
        intervalTicks = ticks
        if (isRunningFlag) {
            cancelTask()
            scheduleTask()
        }
        return self()
    }

    /** Validate the chain and begin ticking. Idempotent: calling [start] on an
     *  already-running builder is a no op, so it's safe to call repeatedly.
     *  Re-validates on every call so misconfigured builders (`:from` without `:to`,
     *  Stocker without `:keep`, etc.) fail at the `:start()` call site. */
    fun start(): TSelf {
        if (isRunningFlag) return self()
        validate()
        scheduleTask()
        isRunningFlag = true
        return self()
    }

    fun stop(): TSelf {
        if (!isRunningFlag) return self()
        cancelTask()
        isRunningFlag = false
        return self()
    }

    fun isRunning(): Boolean = isRunningFlag

    private fun scheduleTask() {
        taskId = when (intervalTicks) {
            1 -> engine.scheduler.addTick { safeTick() }
            20 -> engine.scheduler.addSecond { safeTick() }
            else -> engine.scheduler.addRepeating(intervalTicks) { safeTick() }
        }
    }

    private fun cancelTask() {
        taskId?.let { engine.scheduler.cancelTaskById(it) }
        taskId = null
    }

    private fun safeTick() {
        val snap = engine.currentSnapshot() ?: return
        if (snap !== lastSnapshotSeen) {
            onSnapshotChanged(snap)
            lastSnapshotSeen = snap
        }
        try {
            tickOnce()
        } catch (e: LuaError) {
            engine.logError("[$presetName] ${e.message}")
        } catch (e: Exception) {
            engine.logError("[$presetName] ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Memoised Lua table. First call materialises the shared + subclass methods;
     *  every chained call like `builder:to(...):every(...)` returns the same table
     *  so the handle is stable. */
    fun toLuaTable(): LuaTable {
        cachedTable?.let { return it }
        val t = LuaTable()
        populateCommonMethods(t)
        populateMethods(t)
        cachedTable = t
        return t
    }

    private fun populateCommonMethods(t: LuaTable) {
        val selfRef = this
        t.set("every", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, ticksArg: LuaValue): LuaValue {
                selfRef.every(ticksArg.checkint())
                return selfRef.toLuaTable()
            }
        })
        // :start() and :stop() are terminal lifecycle ops, not chain-continuing
        // configuration. Returning nil prevents `:start():every(20)` style mistakes
        // where extra chain calls after going live would silently re-configure
        // a running preset, and matches how typical Lua "go" / "halt" methods read.
        t.set("start", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                selfRef.start()
                return LuaValue.NIL
            }
        })
        t.set("stop", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                selfRef.stop()
                return LuaValue.NIL
            }
        })
        t.set("isRunning", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                return LuaValue.valueOf(selfRef.isRunning())
            }
        })
    }
}
