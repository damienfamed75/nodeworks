package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.String

/**
 * Spec for `NetworkHandle`, the abstract base every value returned by `network:get(name)`
 * inherits from. It carries the shared `.name` property so any handle type the script
 * may receive (CardHandle, RedstoneCard, ObserverCard, VariableHandle, BreakerHandle,
 * PlacerHandle) can be queried for its identifying string without each subtype having
 * to redeclare the same property.
 *
 * Lua is duck-typed at runtime, so this is purely a registry-level surface merge: the
 * subtypes set `parent = NetworkHandle` in their `api(...)` declaration and
 * [LuaApiRegistry.methodsOf] / `propertiesOf` walks the chain. No runtime
 * `NetworkHandle` class exists.
 */

val NetworkHandle: LuaType.Named = LuaTypes.type(
    name = "NetworkHandle",
    description = "Abstract base for any handle returned by `network:get(name)`. " +
            "Carries the shared `.name` property; subtypes (cards, devices, variables) " +
            "add their type-specific methods on top.",
    guidebookRef = "nodeworks:lua-api/network-handle.md",
)

val NetworkHandleApi: ApiSurface = api(NetworkHandle) {
    property("name", String) {
        description = "The declared variable alias"
        guidebookRef = "nodeworks:lua-api/network-handle.md#name"
    }

    property("kind", NetworkAccessorType) {
        description = "Kind of handle this is"
        guidebookRef = "nodeworks:lua-api/network-handle.md#kind"
    }
}
