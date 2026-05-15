package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.String
import damien.nodeworks.script.api.LuaType.Primitive.Void

/**
 * Spec for `WidgetHandle`, returned by `network:get` for a Widget block name.
 * Runtime impl in [damien.nodeworks.script.WidgetHandle] (value / type / radio
 * / slider readers) plus `:on`, which [damien.nodeworks.script.ScriptEngine]
 * binds so the callback registers into the per-tick poller.
 *
 * A Widget block is a placed, GUI-configured input: button / switch / radio /
 * slider. Widgets are read-only from scripts, you read the live value or hook
 * `:on(fn)` to react to player interaction, but you don't drive them. One
 * handle type covers all four widget kinds, each method below notes its
 * per-kind behaviour.
 */

val WidgetHandle: LuaType.Named = LuaTypes.type(
    name = "WidgetHandle",
    description = "A handle to a Widget block returned by `network:get`. Read its value or hook an interaction callback.",
)

val WidgetHandleApi: ApiSurface = api(WidgetHandle, parent = NetworkHandle) {
    method("value") {
        returns(Number)
        description = "The widget's current value: button always 0, switch 0/1, radio the selected index (0-based), slider the number."
    }
    method("type") {
        returns(String)
        description = "The widget's configured kind: \"button\", \"switch\", \"radio\", or \"slider\"."
    }
    method("on") {
        param(
            "handler",
            function { param("value", Number); returns(Void) },
            description = "Called server-side with the widget's value every time a player interacts with it. For a button the value is always 0, the call itself is the event.",
        )
        returns(WidgetHandle)
        description = "Registers the interaction callback. Returns the handle so calls can chain."
    }
    method("option") {
        returns(String)
        description = "Radio only, the selected option's text. Empty string for other widget kinds."
    }
    method("options") {
        returns(String.list())
        description = "Radio only, the option list as a 1-indexed array of strings. Empty table for other kinds."
    }
    method("min") {
        returns(Number)
        description = "Slider only, the configured minimum value."
    }
    method("max") {
        returns(Number)
        description = "Slider only, the configured maximum value."
    }
    method("step") {
        returns(Number)
        description = "Slider only, the configured step size between values."
    }
}
