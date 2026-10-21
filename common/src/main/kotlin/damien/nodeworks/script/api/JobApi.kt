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
    property("name", LuaType.Primitive.String) {
        description = "The Processing Set's name, the same one `network:handle` takes."
        guidebookRef = "nodeworks:lua-api/job.md#recipe"
    }

    property("inputs", LuaType.Primitive.Any.list()) {
        description = "The set's inputs in slot order, each a table with `id`, `count`, and `tag` when it takes a whole tag."
        guidebookRef = "nodeworks:lua-api/job.md#recipe"
    }

    property("outputs", LuaType.Primitive.Any.list()) {
        description = "The set's outputs, each a table with `id`, `count`, and `optional` for a chance output."
        guidebookRef = "nodeworks:lua-api/job.md#recipe"
    }

    method("pull") {
        param("card", CardHandle, description = "Card to pull from. Variadic in practice, additional cards are passed positionally.")
        returns(LuaType.Primitive.Void)
        description = "Pulls an output from the given card so the Crafting CPU can collect it."
        guidebookRef = "nodeworks:lua-api/job.md#pull"
    }
}
