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
class SchedulerImpl {

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
            task.callback.call()
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

    fun initialize(startTick: Long) {
        currentTick = startTick
    }

    fun clear() {
        tasks.clear()
        pendingJobs.clear()
        nextId = 1
        currentTick = 0
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
