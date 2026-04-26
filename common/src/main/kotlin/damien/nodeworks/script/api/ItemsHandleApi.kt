package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.String

/**
 * Spec for the `ItemsHandle` Lua type. ItemsHandle is the unified item/fluid wrapper
 * returned by [Network]/[CardHandle] find calls and surfaced through autocomplete
 * chain resolution. Runtime impl lives in
 * [damien.nodeworks.script.ItemsHandle.toLuaTable].
 */

val ItemsHandle: LuaType.Named = LuaTypes.type(
    name = "ItemsHandle",
    description = "A snapshot of items or fluids matching some filter.",
    guidebookRef = "nodeworks:lua-api/items-handle.md",
)

val ItemsHandleApi: ApiSurface = api(ItemsHandle) {
    property("id", String) {
        description = "Resource id, `minecraft:diamond` for items or `minecraft:water` for fluids."
        guidebookRef = "nodeworks:lua-api/items-handle.md#id"
    }

    property("name", String) {
        description = "Display name as the player sees it in tooltips."
        guidebookRef = "nodeworks:lua-api/items-handle.md#name"
    }

    property("count", Number) {
        description = "Quantity available. Items are in stack units, fluids are in mB."
        guidebookRef = "nodeworks:lua-api/items-handle.md#count"
    }

    property("kind", ItemsHandleKind) {
        description = "Either `\"item\"` or `\"fluid\"` so scripts can branch on resource kind."
        guidebookRef = "nodeworks:lua-api/items-handle.md#kind"
    }

    property("stackable", Boolean) {
        description = "True if this resource has a max stack size greater than 1. Fluids are never stackable."
        guidebookRef = "nodeworks:lua-api/items-handle.md#stackable"
    }

    property("maxStackSize", Number) {
        description = "Largest stack the resource can occupy in a single inventory slot."
        guidebookRef = "nodeworks:lua-api/items-handle.md#maxStackSize"
    }

    property("hasData", Boolean) {
        description = "True if this stack carries non-default NBT (enchantments, custom names, etc.)."
        guidebookRef = "nodeworks:lua-api/items-handle.md#hasData"
    }

    method("hasTag") {
        param("tag", TagId, description = "Tag id with or without the leading `#`, e.g. `minecraft:logs`.")
        returns(Boolean)
        description = "True if this resource is a member of the given tag."
        guidebookRef = "nodeworks:lua-api/items-handle.md#hasTag"
    }

    method("matches") {
        param("filter", Filter, description = "Filter using the same syntax as `network:find`.")
        returns(Boolean)
        description = "True if this resource matches the filter."
        guidebookRef = "nodeworks:lua-api/items-handle.md#matches"
    }
}
