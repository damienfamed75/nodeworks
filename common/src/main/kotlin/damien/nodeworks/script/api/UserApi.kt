package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.String

/**
 * Spec for the `UserHandle` Lua type. The script-driven counterpart to the
 * redstone trigger on a User device. Runtime impl in
 * [damien.nodeworks.script.UserHandle].
 */

val UserHandle: LuaType.Named = LuaTypes.type(
    name = "UserHandle",
    description = "A User device. Pulls one item from network storage and 'right-clicks' with it on whatever's in front.",
    capability = "user",
    guidebookRef = "nodeworks:lua-api/user-handle.md",
)

val UserHandleApi: ApiSurface = api(UserHandle, parent = NetworkHandle) {
    method("filter") {
        returns(Filter)
        description = "Current filter pattern, the empty string when no filter is set."
        guidebookRef = "nodeworks:lua-api/user-handle.md#filter"
    }

    method("setFilter") {
        param("filter", Filter, description = "Storage Card filter syntax: bare id, `*`, `#tag`, `/regex/`, `\$item:` / `\$fluid:` prefix.")
        returns(LuaType.Primitive.Void)
        description = "Set the item the User pulls from storage. Cleared by passing `\"\"`."
        guidebookRef = "nodeworks:lua-api/user-handle.md#setFilter"
    }

    method("mode") {
        returns(UserMode)
        description = "Current trigger mode, `\"instant\"` or `\"hold\"`."
        guidebookRef = "nodeworks:lua-api/user-handle.md#mode"
    }

    method("setMode") {
        param("mode", UserMode)
        returns(LuaType.Primitive.Void)
        description = "Switch between `UserMode.INSTANT` and `UserMode.HOLD`. No-op when already in the requested mode."
        guidebookRef = "nodeworks:lua-api/user-handle.md#setMode"
    }

    method("facing") {
        returns(FaceName)
        description = "Direction name the User is pointing, drives the target probe."
        guidebookRef = "nodeworks:lua-api/user-handle.md#facing"
    }

    method("use") {
        returns(Boolean)
        description = "Fire one use (instant) or start a hold. False if cooldown, deny-list match, no item match, no controller, or already holding."
        guidebookRef = "nodeworks:lua-api/user-handle.md#use"
    }

    method("stop") {
        returns(Boolean)
        description = "Release an in-progress hold and return the held stack to network storage. False when the User is idle."
        guidebookRef = "nodeworks:lua-api/user-handle.md#stop"
    }

    method("isUsing") {
        returns(Boolean)
        description = "True while a hold is active."
        guidebookRef = "nodeworks:lua-api/user-handle.md#isUsing"
    }
}
