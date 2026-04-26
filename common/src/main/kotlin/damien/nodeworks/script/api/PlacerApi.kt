package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Any
import damien.nodeworks.script.api.LuaType.Primitive.Boolean

/**
 * Spec for the `PlacerHandle` Lua type. Pulls one item from network storage and
 * places it as a block in front. Runtime impl in
 * [damien.nodeworks.script.PlacerHandle].
 */

val PlacerHandle: LuaType.Named = LuaTypes.type(
    name = "PlacerHandle",
    description = "A Placer device. Pulls one item from network storage and places it as a block in front.",
    capability = "placer",
    guidebookRef = "nodeworks:lua-api/placer-handle.md",
)

val PlacerHandleApi: ApiSurface = api(PlacerHandle) {
    method("place") {
        param("item", Any, description = "Item id (string) or an ItemsHandle. Strings accept any registered block-placeable item id.")
        returns(Boolean)
        description = "Pulls one of `item` from network storage and places it. Returns true on success, false if the source is empty, the target isn't replaceable, or the item isn't a block."
        guidebookRef = "nodeworks:lua-api/placer-handle.md#place"
    }

    method("block") {
        returns(BlockId)
        description = "Block id at the placer's facing position."
        guidebookRef = "nodeworks:lua-api/placer-handle.md#block"
    }

    method("isBlocked") {
        returns(Boolean)
        description = "True if the target position is non-replaceable, a place would fail."
        guidebookRef = "nodeworks:lua-api/placer-handle.md#isBlocked"
    }
}
