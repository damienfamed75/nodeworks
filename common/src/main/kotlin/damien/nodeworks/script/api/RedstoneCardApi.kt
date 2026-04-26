package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Any
import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.String

/**
 * Spec for the `RedstoneCard` Lua type. Returned by `network:get` for a card with a
 * redstone capability. Runtime impl in [damien.nodeworks.script.CardHandle], the
 * methods are bound after the base CardHandle methods are stripped.
 */

val RedstoneCard: LuaType.Named = LuaTypes.type(
    name = "RedstoneCard",
    description = "A card attached to a Redstone Card capability. Exposes redstone methods instead of inventory methods.",
    capability = "redstone",
    guidebookRef = "nodeworks:lua-api/card-handle.md",
)

val RedstoneCardApi: ApiSurface = api(RedstoneCard) {
    property("name", String) {
        description = "The card's alias as set in the Card Programmer."
        guidebookRef = "nodeworks:lua-api/card-handle.md#properties"
    }

    method("powered") {
        returns(Boolean)
        description = "True if the incoming redstone signal is greater than 0."
        guidebookRef = "nodeworks:lua-api/card-handle.md#powered"
    }

    method("strength") {
        returns(Number)
        description = "Current incoming redstone signal strength, 0 to 15."
        guidebookRef = "nodeworks:lua-api/card-handle.md#strength"
    }

    method("set") {
        param("value", Any, description = "Boolean (true=15, false=0) or number 0-15.")
        returns(LuaType.Primitive.Void)
        description = "Emits a redstone signal. Boolean maps to 15 or 0. Number is clamped to 0 to 15."
        guidebookRef = "nodeworks:lua-api/card-handle.md#set"
    }

    callback("onChange") {
        fn {
            param("strength", Number)
            returns(LuaType.Primitive.Void)
        }
        returns(LuaType.Primitive.Void)
        description = "Registers a callback fired whenever the incoming signal strength changes."
        guidebookRef = "nodeworks:lua-api/card-handle.md#onchange"
    }
}
