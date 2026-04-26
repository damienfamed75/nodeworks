package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Number

/**
 * Spec for the `scheduler` Lua module. Drives autocomplete, hover tooltips, and the
 * guidebook's `<LuaCode>` tag. Runtime impls live in
 * [damien.nodeworks.script.SchedulerImpl.createLuaTable], the validator (Phase 1.6)
 * cross-checks that every method declared here has a binding there.
 *
 * Migration notes for whoever migrates the next surface: this file is the template.
 * One [api] block per receiver, declarative method/property/callback entries, no
 * hand-written signature strings, type references via Kotlin symbols.
 */

val Scheduler: LuaType.Named = LuaTypes.module(
    global = "scheduler",
    name = "Scheduler",
    description = "Tick-based scheduler for repeating and delayed tasks. Each script gets one.",
    guidebookRef = "nodeworks:lua-api/scheduler.md",
)

val SchedulerApi: ApiSurface = api(Scheduler) {
    callback("tick") {
        fn {
            returns(LuaType.Primitive.Void)
        }
        returns(Number)
        description = "Run [fn] every game tick (20 times per second). Returns a task id you can pass to [cancel]."
        guidebookRef = "nodeworks:lua-api/scheduler.md#tick"
        example = """
            local id = scheduler:tick(function()
                print("tick")
            end)
        """.trimIndent()
    }

    callback("second") {
        fn {
            returns(LuaType.Primitive.Void)
        }
        returns(Number)
        description = "Run [fn] once per second. Returns a task id you can pass to [cancel]."
        guidebookRef = "nodeworks:lua-api/scheduler.md#second"
    }

    method("delay") {
        param("ticks", Number, description = "Number of game ticks to wait before [fn] runs.")
        param("fn", function { returns(LuaType.Primitive.Void) }, description = "Callback to run once after the delay.")
        returns(Number)
        description = "Run [fn] once after [ticks] game ticks. Returns a task id you can pass to [cancel]."
        guidebookRef = "nodeworks:lua-api/scheduler.md#delay"
    }

    method("cancel") {
        param("id", Number, description = "Task id returned by [tick], [second], or [delay].")
        returns(LuaType.Primitive.Void)
        description = "Cancel a previously-scheduled task. No effect if the id has already finished or was cancelled."
        guidebookRef = "nodeworks:lua-api/scheduler.md#cancel"
    }
}
