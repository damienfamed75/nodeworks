package damien.nodeworks.script.api

/**
 * Spec for the `Job` type. Job is the first argument to a `network:handle` callback,
 * scripts use `:pull(card)` to draw outputs from a CPU buffer card. Runtime impl is in
 * [damien.nodeworks.script.ScriptEngine] inside the handler-table builder.
 */

val Job: LuaType.Named = LuaTypes.type(
    name = "Job",
    description = "The first argument to a `network:handle` callback. Represents the in-flight processing job.",
    guidebookRef = "nodeworks:lua-api/job.md",
)

val JobApi: ApiSurface = api(Job) {
    method("pull") {
        param("card", CardHandle, description = "Card to pull from. Variadic in practice, additional cards are passed positionally.")
        returns(LuaType.Primitive.Void)
        description = "Pulls an output from the given card so the Crafting CPU can collect it."
        guidebookRef = "nodeworks:lua-api/job.md#pull"
    }
}
