package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.Void

/**
 * Spec for the `importer` module + the `ImporterBuilder` it produces. Importer
 * builds item movers that periodically pull from one or more sources into one or
 * more destinations. Runtime impl in [damien.nodeworks.script.preset.Importer]
 * and [damien.nodeworks.script.preset.PresetBuilder].
 */

val Importer: LuaType.Named = LuaTypes.module(
    global = "importer",
    name = "Importer",
    description = "Builds item movers from a chained expression.",
    guidebookRef = "nodeworks:lua-api/importer.md",
)

val ImporterBuilder: LuaType.Named = LuaTypes.type(
    name = "ImporterBuilder",
    description = "A configured or running item mover.",
    guidebookRef = "nodeworks:lua-api/importer.md",
)

val ImporterApi: ApiSurface = api(Importer) {
    method("from") {
        param(
            "sources",
            InventoryCardAlias,
            description = "Source card alias (IO or Storage). Variadic at runtime, also accepts CardHandle values or `network` for the pool.",
        )
        returns(ImporterBuilder)
        description = "Starts an importer chain from one or more sources."
        guidebookRef = "nodeworks:lua-api/importer.md#from"
    }
}

val ImporterBuilderApi: ApiSurface = api(ImporterBuilder) {
    method("to") {
        param(
            "targets",
            InventoryCardAlias,
            description = "Destination card alias (IO or Storage). Variadic at runtime, also accepts CardHandle values or `network` for the pool.",
        )
        returns(ImporterBuilder)
        description = "Sets the destinations."
        guidebookRef = "nodeworks:lua-api/importer.md#to"
    }

    method("roundrobin") {
        param("step", Number.optional(), description = "Items per target per tick. Defaults to 1.")
        returns(ImporterBuilder)
        description = "Switch to round-robin distribution across the configured targets."
        guidebookRef = "nodeworks:lua-api/importer.md#roundrobin"
    }

    method("filter") {
        param("pattern", Filter, description = "Resource filter, defaults to `*` (everything).")
        returns(ImporterBuilder)
        description = "Narrows the mover to items matching the pattern."
        guidebookRef = "nodeworks:lua-api/importer.md#filter"
    }

    method("every") {
        param("ticks", Number, description = "Tick interval. Default 20 (one second).")
        returns(ImporterBuilder)
        description = "How often the importer runs."
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
