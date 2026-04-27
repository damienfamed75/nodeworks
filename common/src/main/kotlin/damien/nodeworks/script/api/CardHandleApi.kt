package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number

/**
 * Spec for the `CardHandle` Lua type. This is the generic IO/Storage card handle.
 * Specialised cards (RedstoneCard, ObserverCard, Breaker/Placer) live in their own
 * spec files. The shared `.name` property is inherited from [NetworkHandle], not
 * redeclared here. Runtime impl in [damien.nodeworks.script.CardHandle].
 */

val CardHandle: LuaType.Named = LuaTypes.type(
    name = "CardHandle",
    description = "A card on the network accessed by alias.",
    guidebookRef = "nodeworks:lua-api/card-handle.md",
)

val CardHandleApi: ApiSurface = api(CardHandle, parent = NetworkHandle) {
    method("face") {
        param("name", FaceName, description = "Face name to pin the handle to.")
        returns(CardHandle)
        description = "Returns a new handle pinned to a specific face of the adjacent block."
        guidebookRef = "nodeworks:lua-api/card-handle.md#face"
    }

    method("slots") {
        param("indices", Number.list(), description = "1-based slot indices to filter to.")
        returns(CardHandle)
        description = "Returns a new handle filtered to specific slot indices. Indices are 1-based."
        guidebookRef = "nodeworks:lua-api/card-handle.md#slots"
    }

    method("find") {
        param("filter", Filter, description = "Same filter syntax as `network:find`.")
        returns(ItemsHandle.optional())
        description = "Like `network:find`, scoped to this card's inventory."
        guidebookRef = "nodeworks:lua-api/card-handle.md#find"
    }

    method("findEach") {
        param("filter", Filter, description = "Same filter syntax as `network:find`.")
        returns(ItemsHandle.list())
        description = "Returns one handle per distinct resource on this card matching the filter."
        guidebookRef = "nodeworks:lua-api/card-handle.md#findEach"
    }

    method("insert") {
        param("items", ItemsHandle, description = "Resource to move from its source into this card.")
        param("count", Number.optional(), description = "Optional count limit, defaults to the items handle's full count.")
        returns(Boolean)
        description = "Moves the full count from the handle's source into this card, or moves nothing. Returns true on success."
        guidebookRef = "nodeworks:lua-api/card-handle.md#insert"
    }

    method("tryInsert") {
        param("items", ItemsHandle, description = "Resource to move from its source into this card.")
        param("count", Number.optional(), description = "Optional count limit, defaults to the items handle's full count.")
        returns(Number)
        description = "Best-effort move into this card. Returns the count actually moved."
        guidebookRef = "nodeworks:lua-api/card-handle.md#tryInsert"
    }

    method("count") {
        param("filter", Filter, description = "Filter using the same syntax as `network:find`.")
        returns(Number)
        description = "Total quantity on this card matching the filter."
        guidebookRef = "nodeworks:lua-api/card-handle.md#count"
    }
}
