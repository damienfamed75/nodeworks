package damien.nodeworks.script

import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Manages tick-based scheduling for Lua scripts.
 *
 * Two systems:
 * 1. Scheduled tasks — :tick(fn), :second(fn), :delay(ticks, fn), :cancel(id)
 * 2. Pending jobs — polling callbacks checked each tick (used by job:pull and network:process)
 */
class SchedulerImpl(
    /** Called when a scheduled task throws an error. The error is logged but execution continues. */
    private val onTaskError: ((String) -> Unit)? = null
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

    fun tick(tickCount: Long) {
        currentTick = tickCount

        val toRun = tasks.filter { tickCount >= it.nextRun }
        val toRemove = mutableListOf<Int>()
        for (task in toRun) {
            try {
                task.callback.call()
            } catch (e: org.luaj.vm2.LuaError) {
                onTaskError?.invoke("${e.message}")
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

    /** Cancel a scheduled task previously added via [addTick] / [addSecond] / [addRepeating].
     *  Returns true if a task with this id was present. */
    fun cancelTaskById(id: Int): Boolean {
        val before = tasks.size
        tasks.removeAll { it.id == id }
        return tasks.size < before
    }

    fun createLuaTable(): LuaTable {
        val table = LuaTable()

        table.set("tick", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fn: LuaValue): LuaValue {
                val id = nextId++
                tasks.add(ScheduledTask(id, fn.checkfunction(), 1, 0, true))
                return LuaValue.valueOf(id)
            }
        })

        table.set("second", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fn: LuaValue): LuaValue {
                val id = nextId++
                tasks.add(ScheduledTask(id, fn.checkfunction(), 20, 0, true))
                return LuaValue.valueOf(id)
            }
        })

        table.set("delay", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, ticksArg: LuaValue, fn: LuaValue): LuaValue {
                val id = nextId++
                val delayTicks = ticksArg.checkint()
                tasks.add(ScheduledTask(id, fn.checkfunction(), 0, currentTick + delayTicks, false))
                return LuaValue.valueOf(id)
            }
        })

        table.set("cancel", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, idArg: LuaValue): LuaValue {
                val id = idArg.checkint()
                tasks.removeAll { it.id == id }
                return LuaValue.NIL
            }
        })

        return table
    }
}
