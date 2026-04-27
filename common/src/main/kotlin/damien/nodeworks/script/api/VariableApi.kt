package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Any
import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.String

/**
 * Spec for `VariableHandle` and its three typed variants. Returned by `network:get`
 * for a variable name. Runtime impl in [damien.nodeworks.script.VariableHandle], the
 * variant types share the base methods plus their type-specific helpers.
 *
 * Each variant declares `parent = VariableHandle` so [LuaApiRegistry.methodsOf] /
 * `propertiesOf` walks up to merge in the base surface's `:get`/`:set`/`:cas`/...
 * core. The autocomplete picks the variant when the symbol table has inferred a
 * typed handle (NumberVariableHandle / StringVariableHandle / BoolVariableHandle
 * from the variable's declared type), and falls back to the generic VariableHandle
 * otherwise. Lua is duck-typed at runtime, so this isn't real inheritance, just a
 * registry-level surface merge.
 */

val VariableHandle: LuaType.Named = LuaTypes.type(
    name = "VariableHandle",
    description = "A handle to a network variable returned by `network:get`.",
    guidebookRef = "nodeworks:lua-api/variable-handle.md",
)

val NumberVariableHandle: LuaType.Named = LuaTypes.type(
    name = "NumberVariableHandle",
    description = "A `VariableHandle` with numeric-specific helpers.",
    guidebookRef = "nodeworks:lua-api/variable-handle.md",
)

val StringVariableHandle: LuaType.Named = LuaTypes.type(
    name = "StringVariableHandle",
    description = "A `VariableHandle` with string-specific helpers.",
    guidebookRef = "nodeworks:lua-api/variable-handle.md",
)

val BoolVariableHandle: LuaType.Named = LuaTypes.type(
    name = "BoolVariableHandle",
    description = "A `VariableHandle` with a boolean-specific helper.",
    guidebookRef = "nodeworks:lua-api/variable-handle.md",
)

val VariableHandleApi: ApiSurface = api(VariableHandle, parent = NetworkHandle) {
    method("get") {
        returns(Any)
        description = "Returns the variable's current value."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#get--set"
    }

    method("set") {
        param("value", Any, description = "New value, must match the variable's declared type.")
        returns(LuaType.Primitive.Void)
        description = "Sets the variable's value. Must match the variable's declared type."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#get--set"
    }

    method("cas") {
        param("expected", Any, description = "Expected current value.")
        param("new", Any, description = "Value to set on success.")
        returns(Boolean)
        description = "Compare and swap. Sets the variable to `new` only if its current value equals `expected`. Returns true on success."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#cas"
    }

    method("type") {
        returns(String)
        description = "Returns the variable's declared type as a string."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#type"
    }

    method("tryLock") {
        returns(Boolean)
        description = "Attempts to acquire the variable's lock. Returns true on success, false if another script holds it."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#trylock--unlock"
    }

    method("unlock") {
        returns(LuaType.Primitive.Void)
        description = "Releases a lock acquired via `:tryLock`."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#trylock--unlock"
    }
}

val NumberVariableHandleApi: ApiSurface = api(NumberVariableHandle, parent = VariableHandle) {
    method("increment") {
        param("by", Number.optional(), description = "Amount to add. Defaults to 1.")
        returns(LuaType.Primitive.Void)
        description = "Adds `by` to the variable atomically."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#increment--decrement"
    }

    method("decrement") {
        param("by", Number.optional(), description = "Amount to subtract. Defaults to 1.")
        returns(LuaType.Primitive.Void)
        description = "Subtracts `by` from the variable atomically."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#increment--decrement"
    }

    method("min") {
        param("other", Number)
        returns(LuaType.Primitive.Void)
        description = "Sets the variable to `min(current, other)` atomically."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#min--max"
    }

    method("max") {
        param("other", Number)
        returns(LuaType.Primitive.Void)
        description = "Sets the variable to `max(current, other)` atomically."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#min--max"
    }
}

val StringVariableHandleApi: ApiSurface = api(StringVariableHandle, parent = VariableHandle) {
    method("append") {
        param("str", String)
        returns(LuaType.Primitive.Void)
        description = "Appends `str` to the current value."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#append--length--clear"
    }

    method("length") {
        returns(Number)
        description = "Length of the current string value."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#append--length--clear"
    }

    method("clear") {
        returns(LuaType.Primitive.Void)
        description = "Sets the variable to the empty string."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#append--length--clear"
    }
}

val BoolVariableHandleApi: ApiSurface = api(BoolVariableHandle, parent = VariableHandle) {
    method("toggle") {
        returns(LuaType.Primitive.Void)
        description = "Flips the boolean value."
        guidebookRef = "nodeworks:lua-api/variable-handle.md#toggle"
    }
}
