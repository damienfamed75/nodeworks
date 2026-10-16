package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Number

/**
 * Spec for the `EnergyInfo` Lua type, what `ObserverCard:energy()` returns for a block
 * with energy storage. A named type rather than a bare table so the editor completes
 * its fields and warns when it is read before a nil check.
 */

val EnergyInfo: LuaType.Named = LuaTypes.type(
    name = "EnergyInfo",
    description = "A block's energy storage at the moment it was read.",
    guidebookRef = "nodeworks:lua-api/card-handle.md#energy",
)

val EnergyInfoApi: ApiSurface = api(EnergyInfo) {
    property("stored", Number) {
        description = "Energy currently stored, in the loader's energy unit (FE)."
        guidebookRef = "nodeworks:lua-api/card-handle.md#energy"
    }

    property("capacity", Number) {
        description = "Most energy the block can hold, in the same unit."
        guidebookRef = "nodeworks:lua-api/card-handle.md#energy"
    }
}
