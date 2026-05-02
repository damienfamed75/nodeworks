package damien.nodeworks.script

import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Manages tick-based scheduling for Lua scripts.
 *
 * Two systems:
 * 1. Scheduled tasks, :tick(fn), :second(fn), :delay(ticks, fn), :cancel(id)
 * 2. Pending jobs, polling callbacks checked each tick (used by job:pull and network:process)
 */
class SchedulerImpl(
    /** Called when a scheduled task throws an error. The error is logged but execution continues. */
    private val onTaskError: ((String) -> Unit)? = null,
    /** Runs a Lua callback with a wall-clock soft-abort budget. ScriptEngine wires this to
     *  its [LuaExecGate] so per-task timeouts can evict the task from [tasks]. The default
     *  is "just run the body and report Ok" so non-engine schedulers ([ResumeScheduler])
     *  keep their current ungated behaviour without code changes. */
    private val runCallback: (String, () -> Unit) -> LuaExecGate.Outcome = { _, body ->
        body(); LuaExecGate.Outcome.Ok
    },
    /** Checks that registering one more scheduled task wouldn't exceed the
     *  per-kind callback cap, throwing a Lua error if it would. Wired by
     *  ScriptEngine to read from [ServerPolicy]; default is a no-op so
     *  [ResumeScheduler] (which has no script-side caller) is unaffected. */
    private val assertCanRegister: (currentSize: Int, kind: String) -> Unit = { _, _ -> },
) {

    private data class ScheduledTask(
        val id: Int,
        val callback: LuaFunction,
        val interval: Int,
        val nextRun: Long,
        val repeating: Boolean
    )

    data class PendingJob(
        val pollFn: () -> Boolean,
        var timeoutAt: Long = Long.MAX_VALUE,
        var onComplete: ((Boolean) -> Unit)? = null,
        var label: String = ""
    )

    private val tasks = mutableListOf<ScheduledTask>()
    private val pendingJobs = mutableListOf<PendingJob>()
    private var nextId = 1
    var currentTick: Long = 0
        private set

    fun tick(tickCount: Long, tickDeadlineNs: Long = Long.MAX_VALUE) {
        currentTick = tickCount

        val toRun = tasks.filter { tickCount >= it.nextRun }
        val toRemove = mutableListOf<Int>()
        for (task in toRun) {
            // Per-tick budget: if the engine's slice is gone, the remaining tasks
            // sit untouched. Their nextRun is in the past so the next tick picks
            // them up again, no execution is silently skipped, just deferred.
            if (System.nanoTime() >= tickDeadlineNs) break
            val outcome = runCallback("scheduler-task-${task.id}") { task.callback.call() }
            // Eviction: a callback that hit the wall-clock soft-abort gets removed
            // from [tasks] regardless of [repeating], so the bad task can't re-fire
            // every tick. Other tasks keep running. A regular Lua error is logged
            // but doesn't evict, mirroring the prior behaviour for non-timeout
            // failures so transient bugs don't unschedule legitimate handlers.
            when (outcome) {
                LuaExecGate.Outcome.TimedOut -> {
                    onTaskError?.invoke("Scheduled task #${task.id} took too long to run, task removed.")
                    toRemove.add(task.id)
                    continue
                }
                is LuaExecGate.Outcome.Errored -> {
                    onTaskError?.invoke(outcome.message)
                }
                is LuaExecGate.Outcome.Fatal -> {
                    // Engine has already been stopped by [runGatedCallback]'s
                    // fatal handler; bail out of the iteration so we don't keep
                    // ticking remaining tasks against a dead engine.
                    return
                }
                LuaExecGate.Outcome.Ok -> { /* no-op */ }
            }
            if (task.repeating) {
                val index = tasks.indexOf(task)
                if (index >= 0) tasks[index] = task.copy(nextRun = tickCount + task.interval)
            } else {
                toRemove.add(task.id)
            }
        }
        tasks.removeAll { it.id in toRemove }

        val doneJobs = mutableListOf<PendingJob>()
        for (job in pendingJobs.toList()) {
            if (tickCount >= job.timeoutAt) {
                doneJobs.add(job)
                job.onComplete?.invoke(false)
                continue
            }
            try {
                if (job.pollFn()) {
                    doneJobs.add(job)
                    job.onComplete?.invoke(true)
                }
            } catch (_: Exception) {
                doneJobs.add(job)
                job.onComplete?.invoke(false)
            }
        }
        pendingJobs.removeAll(doneJobs.toSet())
    }

    fun hasActiveTasks(): Boolean = tasks.isNotEmpty() || pendingJobs.isNotEmpty()

    fun addPendingJob(job: PendingJob) {
        pendingJobs.add(job)
    }

    /** Remove a pending poll registered via [addPendingJob]. Returns true if it was
     *  present. Called by the CPU to unwind `job:pull` side effects when the handler's
     *  inserts failed and we're about to retry the invocation. */
    fun removePendingJob(job: PendingJob): Boolean = pendingJobs.remove(job)

    fun initialize(startTick: Long) {
        currentTick = startTick
    }

    fun clear() {
        tasks.clear()
        pendingJobs.clear()
        nextId = 1
        currentTick = 0
    }

    // =====================================================================
    // Kotlin callable task registration
    //
    // These exist so native code (preset builders in particular) can schedule
    // repeated work without going through a Lua round trip. Internally each
    // helper wraps the Kotlin lambda in a thin [ZeroArgFunction] so the task
    // list still holds a uniform [LuaFunction] and the main [tick] loop's
    // error handling path keeps working.
    // =====================================================================

    private fun addTaskInternal(callback: () -> Unit, interval: Int, repeating: Boolean, firstRunDelay: Int = 0): Int {
        val id = nextId++
        val fn = object : ZeroArgFunction() {
            override fun call(): LuaValue {
                callback()
                return LuaValue.NIL
            }
        }
        val nextRun = if (repeating) 0L else currentTick + firstRunDelay
        tasks.add(ScheduledTask(id, fn, interval, nextRun, repeating))
        return id
    }

    /** Schedule a Kotlin callback every tick. Returns the task id for [cancelTaskById]. */
    fun addTick(callback: () -> Unit): Int = addTaskInternal(callback, interval = 1, repeating = true)

    /** Schedule a Kotlin callback every 20 ticks (once per second). Returns the task id. */
    fun addSecond(callback: () -> Unit): Int = addTaskInternal(callback, interval = 20, repeating = true)

    /** Schedule a Kotlin callback every [intervalTicks] ticks. Returns the task id. */
    fun addRepeating(intervalTicks: Int, callback: () -> Unit): Int {
        require(intervalTicks >= 1) { "intervalTicks must be >= 1" }
        return addTaskInternal(callback, interval = intervalTicks, repeating = true)
    }

    /** Schedule a one-shot Kotlin callback to run after [delayTicks] ticks. Pass `0`
     *  to fire on the next scheduler tick, useful for "defer this until the current
     *  Lua statement chain has finished evaluating," which is what `network:craft`'s
     *  default-to-store-after-connect-window relies on. Returns the task id for
     *  [cancelTaskById] in case the caller wants to abort before it fires. */
    fun runOnce(delayTicks: Int, callback: () -> Unit): Int {
        require(delayTicks >= 0) { "delayTicks must be >= 0" }
        return addTaskInternal(callback, interval = 1, repeating = false, firstRunDelay = delayTicks)
    }

    /** Cancel a scheduled task previously added via [addTick] / [addSecond] / [addRepeating].
     *  Returns true if a task with this id was present. */
    fun cancelTaskById(id: Int): Boolean {
        val before = tasks.size
        tasks.removeAll { it.id == id }
        return tasks.size < before
    }

    fun createLuaTable(): LuaTable {
        val table = LuaTable()

        table.setGuarded("Scheduler", "tick", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fn: LuaValue): LuaValue {
                assertCanRegister(tasks.size, "scheduler")
                val id = nextId++
                tasks.add(ScheduledTask(id, fn.checkfunction(), 1, 0, true))
                return LuaValue.valueOf(id)
            }
        })

        table.setGuarded("Scheduler", "second", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fn: LuaValue): LuaValue {
                assertCanRegister(tasks.size, "scheduler")
                val id = nextId++
                tasks.add(ScheduledTask(id, fn.checkfunction(), 20, 0, true))
                return LuaValue.valueOf(id)
            }
        })

        table.setGuarded("Scheduler", "delay", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, ticksArg: LuaValue, fn: LuaValue): LuaValue {
                assertCanRegister(tasks.size, "scheduler")
                val id = nextId++
                val delayTicks = ticksArg.checkint()
                tasks.add(ScheduledTask(id, fn.checkfunction(), 0, currentTick + delayTicks, false))
                return LuaValue.valueOf(id)
            }
        })

        table.setGuarded("Scheduler", "cancel", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, idArg: LuaValue): LuaValue {
                val id = idArg.checkint()
                tasks.removeAll { it.id == id }
                return LuaValue.NIL
            }
        })

        return table
    }
}
