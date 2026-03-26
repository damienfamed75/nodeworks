package damien.nodeworks.script

import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Manages tick-based scheduling for Lua scripts.
 * Supports :tick(fn), :second(fn), :delay(ticks, fn), :cancel(id).
 */
class SchedulerImpl {

    private data class ScheduledTask(
        val id: Int,
        val callback: LuaFunction,
        val interval: Int,       // 0 = one-shot (delay)
        val nextRun: Long,
        val repeating: Boolean
    )

    private val tasks = mutableListOf<ScheduledTask>()
    private var nextId = 1

    fun tick(tickCount: Long) {
        val toRun = tasks.filter { tickCount >= it.nextRun }
        val toRemove = mutableListOf<Int>()

        for (task in toRun) {
            task.callback.call()
            if (task.repeating) {
                // Reschedule
                val index = tasks.indexOf(task)
                if (index >= 0) {
                    tasks[index] = task.copy(nextRun = tickCount + task.interval)
                }
            } else {
                toRemove.add(task.id)
            }
        }

        tasks.removeAll { it.id in toRemove }
    }

    fun hasActiveTasks(): Boolean = tasks.isNotEmpty()

    fun clear() {
        tasks.clear()
        nextId = 1
    }

    fun createLuaTable(): LuaTable {
        val table = LuaTable()

        // scheduler:tick(fn) — runs every game tick (50ms)
        // Lua `:` call passes self as arg1, fn as arg2
        table.set("tick", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fn: LuaValue): LuaValue {
                val id = nextId++
                tasks.add(ScheduledTask(id, fn.checkfunction(), 1, 0, true))
                return LuaValue.valueOf(id)
            }
        })

        // scheduler:second(fn) — runs every 20 ticks (1 second)
        table.set("second", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fn: LuaValue): LuaValue {
                val id = nextId++
                tasks.add(ScheduledTask(id, fn.checkfunction(), 20, 0, true))
                return LuaValue.valueOf(id)
            }
        })

        // scheduler:delay(ticks, fn) — runs once after N ticks
        table.set("delay", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, ticksArg: LuaValue, fn: LuaValue): LuaValue {
                val id = nextId++
                val delayTicks = ticksArg.checkint()
                tasks.add(ScheduledTask(id, fn.checkfunction(), 0, delayTicks.toLong(), false))
                return LuaValue.valueOf(id)
            }
        })

        // scheduler:cancel(id)
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
