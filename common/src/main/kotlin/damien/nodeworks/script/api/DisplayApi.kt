package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.String
import damien.nodeworks.script.api.LuaType.Primitive.Void

/**
 * Spec for the Display block's scripting API: the `DisplayHandle` returned by
 * `network:display(name)` and the freeform element builders (box / text / line
 * / item / entity). Runtime impl in [damien.nodeworks.script.DisplayHandle].
 *
 * `d:<thing>(...)` appends an element and returns a typed builder; chaining
 * cosmetic methods configures that element, then you go back to `d` for the
 * next. Coordinates are normalized (`-1..1`, `(-1,-1)` bottom-left, `+y` up).
 * `(x, y)` is a corner / start point. The Display is a pure draw surface, all
 * interactivity lives on the separate Widget block.
 */

val DisplayHandle: LuaType.Named = LuaTypes.type(
    name = "DisplayHandle",
    description = "A handle to a Display block (or multiblock cluster) returned by `network:display`. Builds and commits a UI frame.",
)

val DisplayBox: LuaType.Named = LuaTypes.type(
    name = "DisplayBox",
    description = "Builder for a box element. Flat by default, `:depth()` makes it a floating cuboid, `:wireframe()` makes it an outline.",
)

val DisplayText: LuaType.Named = LuaTypes.type(
    name = "DisplayText",
    description = "Builder for a text-label element.",
)

val DisplayLine: LuaType.Named = LuaTypes.type(
    name = "DisplayLine",
    description = "Builder for a line element between two points.",
)

val DisplayItem: LuaType.Named = LuaTypes.type(
    name = "DisplayItem",
    description = "Builder for an item element, a 3D item model rendered in the volume.",
)

val DisplayEntity: LuaType.Named = LuaTypes.type(
    name = "DisplayEntity",
    description = "Builder for an entity element, a 3D entity model rendered in the volume.",
)

val DisplayHandleApi: ApiSurface = api(DisplayHandle, parent = NetworkHandle) {
    method("clear") {
        returns(DisplayHandle)
        description = "Drops every accumulated element. Returns the handle."
    }
    method("box") {
        param("x", Number, description = "Left edge, -1..1.")
        param("y", Number, description = "Bottom edge, -1..1.")
        param("w", Number, description = "Width (2 = full surface).")
        param("h", Number, description = "Height (2 = full surface).")
        returns(DisplayBox)
        description = "Appends a box and returns its builder for chained styling."
    }
    method("text") {
        param("x", Number, description = "Anchor x, -1..1.")
        param("y", Number, description = "Anchor y, -1..1.")
        param("text", String)
        returns(DisplayText)
        description = "Appends a text label anchored at (x, y)."
    }
    method("line") {
        param("x1", Number, description = "Start x, -1..1.")
        param("y1", Number, description = "Start y, -1..1.")
        param("x2", Number, description = "End x, -1..1.")
        param("y2", Number, description = "End y, -1..1.")
        returns(DisplayLine)
        description = "Appends a line between two points. Diagonal-capable."
    }
    method("item") {
        param("itemId", ItemId, description = "Item registry id, e.g. \"minecraft:iron_ingot\".")
        param("x", Number, description = "Centre x, -1..1.")
        param("y", Number, description = "Centre y, -1..1.")
        returns(DisplayItem)
        description = "Appends a 3D item model. Chain `:z()` to float it, `:rot()` to spin it."
    }
    method("entity") {
        param("entityId", EntityId, description = "Entity-type id, e.g. \"minecraft:cow\".")
        param("x", Number, description = "Centre x, -1..1.")
        param("y", Number, description = "Centre y, -1..1.")
        returns(DisplayEntity)
        description = "Appends a 3D entity model. Chain `:z()` to float it, `:rot()` to spin it."
    }
    method("flush") {
        returns(Void)
        description = "Commits the accumulated elements to the Display, then clears the accumulator."
    }
    method("render") {
        returns(Void)
        description = "Alias for `flush`."
    }
}

// ---- shared builder method docs ----

private fun ApiSurfaceBuilder.colorMethods(self: LuaType.Named, gradient: kotlin.Boolean) {
    method("color") {
        param("dye", DyeColor, description = "Dye-color name, e.g. \"red\", \"lime\".")
        returns(self)
        description = "Sets the element's color."
    }
    method("opacity") {
        param("amount", Number, description = "0 (transparent) .. 1 (opaque).")
        returns(self)
        description = "Sets the element's opacity."
    }
    if (gradient) {
        method("gradient") {
            param("from", DyeColor)
            param("to", DyeColor)
            returns(self)
            description = "Fills the element with a gradient between two dye colors."
        }
    }
}

private fun ApiSurfaceBuilder.depthMethods(self: LuaType.Named) {
    method("z") {
        param("depth", Number, description = "Depth offset from the face, 0..1 (1 = 2 blocks out).")
        returns(self)
        description = "Pushes the element out from the display face."
    }
    method("billboard") {
        returns(self)
        description = "Makes the element always face the camera."
    }
}

val DisplayBoxApi: ApiSurface = api(DisplayBox) {
    colorMethods(DisplayBox, gradient = true)
    depthMethods(DisplayBox)
    method("depth") {
        param("amount", Number, description = "Element depth, 0..1. >0 makes it a floating cuboid.")
        returns(DisplayBox)
        description = "Gives the box depth, turning the flat quad into a cuboid."
    }
    method("wireframe") {
        param("thickness", Number.optional(), description = "Outline thickness in pixels (1px = 1/128 block). Defaults to 1.")
        returns(DisplayBox)
        description = "Draws the box as an outline instead of a solid fill."
    }
    method("rot") {
        param("degrees", Number)
        returns(DisplayBox)
        description = "Rotates the box around its centre, in the surface plane."
    }
}

val DisplayTextApi: ApiSurface = api(DisplayText) {
    colorMethods(DisplayText, gradient = false)
    depthMethods(DisplayText)
    method("size") {
        param("scale", Number, description = "Font-size multiplier, e.g. 2.")
        returns(DisplayText)
        description = "Scales the text."
    }
    method("rot") {
        param("degrees", Number)
        returns(DisplayText)
        description = "Rotates the text (e.g. 90 for vertical)."
    }
}

val DisplayLineApi: ApiSurface = api(DisplayLine) {
    colorMethods(DisplayLine, gradient = true)
    depthMethods(DisplayLine)
    method("thickness") {
        param("pixels", Number.optional(), description = "Stroke width in pixels (1px = 1/128 block). Defaults to 1.")
        returns(DisplayLine)
        description = "Sets the line's stroke width."
    }
    method("rot") {
        param("degrees", Number)
        returns(DisplayLine)
        description = "Rotates the line around its midpoint."
    }
}

val DisplayItemApi: ApiSurface = api(DisplayItem) {
    depthMethods(DisplayItem)
    method("size") {
        param("scale", Number, description = "Size multiplier, e.g. 1.5.")
        returns(DisplayItem)
        description = "Scales the item model."
    }
    method("rot") {
        param("degrees", Number)
        returns(DisplayItem)
        description = "Spins the item around its vertical axis."
    }
}

val DisplayEntityApi: ApiSurface = api(DisplayEntity) {
    depthMethods(DisplayEntity)
    method("size") {
        param("scale", Number, description = "Size multiplier. Large entities are auto-fit, this scales on top.")
        returns(DisplayEntity)
        description = "Scales the entity model."
    }
    method("rot") {
        param("degrees", Number)
        returns(DisplayEntity)
        description = "Spins the entity around its vertical axis."
    }
}
