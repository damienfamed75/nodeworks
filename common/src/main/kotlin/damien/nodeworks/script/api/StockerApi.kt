package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.Void

/**
 * Spec for the `stocker` module + the `StockerBuilder` it produces. Stocker
 * builds level maintainers that keep a configured destination stocked at a
 * target count, pulling from cards or crafting as configured. Runtime impl in
 * [damien.nodeworks.script.preset.Stocker] and the shared
 * [damien.nodeworks.script.preset.PresetBuilder].
 */

val Stocker: LuaType.Named = LuaTypes.module(
    global = "stocker",
    name = "Stocker",
    description = "Builds level maintainers from a chained expression.",
    guidebookRef = "nodeworks:lua-api/stocker.md",
)

val StockerBuilder: LuaType.Named = LuaTypes.type(
    name = "StockerBuilder",
    description = "A configured or running level maintainer.",
    guidebookRef = "nodeworks:lua-api/stocker.md",
)

val StockerApi: ApiSurface = api(Stocker) {
    method("from") {
        param(
            "sources",
            InventoryCardAlias,
            description = "Source card alias (IO or Storage). Variadic at runtime, also accepts CardHandle values or `network` for the pool.",
        )
        returns(StockerBuilder)
        description = "Pull from cards or the pool to maintain a level. Never crafts."
        guidebookRef = "nodeworks:lua-api/stocker.md#from"
    }

    method("ensure") {
        param("itemId", Craftable, description = "Item to keep stocked.")
        returns(StockerBuilder)
        description = "Pull from the pool first, craft the rest if short."
        guidebookRef = "nodeworks:lua-api/stocker.md#ensure"
    }

    method("craft") {
        param("itemId", Craftable, description = "Item the network can plan a recipe for.")
        returns(StockerBuilder)
        description = "Always craft to maintain the level. Never pulls."
        guidebookRef = "nodeworks:lua-api/stocker.md#craft"
    }
}

val StockerBuilderApi: ApiSurface = api(StockerBuilder) {
    method("to") {
        param(
            "target",
            InventoryCardAlias,
            description = "Destination card alias (IO or Storage). Also accepts a CardHandle or `network` for the pool.",
        )
        returns(StockerBuilder)
        description = "Sets the destination."
        guidebookRef = "nodeworks:lua-api/stocker.md#to"
    }

    method("keep") {
        param("amount", Number, description = "Target stock level.")
        returns(StockerBuilder)
        description = "Target stock level. Never extracts above this."
        guidebookRef = "nodeworks:lua-api/stocker.md#keep"
    }

    method("batch") {
        param("size", Number, description = "Coalesced craft batch size. Default 0 (no batching).")
        returns(StockerBuilder)
        description = "Coalesce craft requests into this batch size."
        guidebookRef = "nodeworks:lua-api/stocker.md#batch"
    }

    method("filter") {
        param("pattern", Filter, description = "Resource filter, defaults to `*`.")
        returns(StockerBuilder)
        description = "Pattern counted toward `:keep(n)` in the target."
        guidebookRef = "nodeworks:lua-api/stocker.md#filter"
    }

    method("every") {
        param("ticks", Number, description = "Tick interval. Default 20 (one second).")
        returns(StockerBuilder)
        description = "How often the stocker checks."
        guidebookRef = "nodeworks:lua-api/presets.md#every"
    }

    method("start") {
        returns(Void)
        description = "Validates the chain and begins ticking."
        guidebookRef = "nodeworks:lua-api/presets.md#start"
    }

    method("stop") {
        returns(Void)
        description = "Stops ticking. Restart with `:start()`."
        guidebookRef = "nodeworks:lua-api/presets.md#stop"
    }

    method("isRunning") {
        returns(Boolean)
        description = "True if the preset is currently scheduled."
        guidebookRef = "nodeworks:lua-api/presets.md#isrunning"
    }
}
