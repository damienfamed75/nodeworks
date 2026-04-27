package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Any
import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number

/**
 * Spec for the `BreakerHandle` and `BreakBuilder` types. Breaker is a multi-tick
 * mining device, `:mine()` returns a builder that drops can be redirected through.
 */

val BreakerHandle: LuaType.Named = LuaTypes.type(
    name = "BreakerHandle",
    description = "A Breaker device. Diamond-pickaxe tier, break duration uses the wooden-pickaxe formula.",
    capability = "breaker",
    guidebookRef = "nodeworks:lua-api/breaker-handle.md",
)

val BreakBuilder: LuaType.Named = LuaTypes.type(
    name = "BreakBuilder",
    description = "Returned by `Breaker:mine()`. Configures how the drops route once the break completes.",
    guidebookRef = "nodeworks:lua-api/breaker-handle.md#breakbuilder",
)

val BreakerHandleApi: ApiSurface = api(BreakerHandle, parent = NetworkHandle) {
    method("mine") {
        returns(BreakBuilder)
        description = "Starts a multi-tick break of the block in front. Drops route to network storage by default, chain `:connect(fn)` to redirect."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#mine"
    }

    method("cancel") {
        returns(LuaType.Primitive.Void)
        description = "Aborts the in-flight break, if any. Safe to call when idle."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#cancel"
    }

    method("block") {
        returns(BlockId)
        description = "Block id at the breaker's facing position."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#block"
    }

    method("state") {
        returns(Any)
        description = "Property table for the block at the breaker's facing position."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#state"
    }

    method("isMining") {
        returns(Boolean)
        description = "True when a break is in progress."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#isMining"
    }

    method("progress") {
        returns(Number)
        description = "0..1 fraction of the current break's progress. 0 when idle."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#progress"
    }
}

val BreakBuilderApi: ApiSurface = api(BreakBuilder) {
    callback("connect") {
        fn {
            param("items", ItemsHandle)
            returns(LuaType.Primitive.Void)
        }
        returns(LuaType.Primitive.Void)
        description = "Redirects drops to a script handler instead of network storage. The handler receives one ItemsHandle per drop stack."
        guidebookRef = "nodeworks:lua-api/breaker-handle.md#connect"
    }
}
