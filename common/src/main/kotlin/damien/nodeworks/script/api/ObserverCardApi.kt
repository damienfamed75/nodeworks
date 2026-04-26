package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Any

/**
 * Spec for the `ObserverCard` Lua type. Returned by `network:get` for a card with an
 * observer capability. Reads block id and properties at the watched position, plus
 * an onChange hook that fires when either changes.
 */

val ObserverCard: LuaType.Named = LuaTypes.type(
    name = "ObserverCard",
    description = "A card that reads the block at its facing position. Exposes block(), state(), and onChange() instead of inventory methods.",
    capability = "observer",
    guidebookRef = "nodeworks:lua-api/card-handle.md#observer-card",
)

val ObserverCardApi: ApiSurface = api(ObserverCard) {
    method("block") {
        returns(BlockId)
        description = "Block id at the watched position, e.g. `\"minecraft:diamond_ore\"`."
        guidebookRef = "nodeworks:lua-api/card-handle.md#block"
    }

    method("state") {
        returns(Any)
        description = "Property table for the watched block. Keys are property names, values are numbers, booleans, or lowercase strings."
        guidebookRef = "nodeworks:lua-api/card-handle.md#state"
    }

    callback("onChange") {
        fn {
            param("block", BlockId)
            param("state", Any)
            returns(LuaType.Primitive.Void)
        }
        returns(LuaType.Primitive.Void)
        description = "Fires whenever the watched block id or any state property changes. Replaces any prior handler on the same card."
        guidebookRef = "nodeworks:lua-api/card-handle.md#observer-onchange"
    }
}
