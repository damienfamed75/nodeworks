package damien.nodeworks.block.entity

import damien.nodeworks.compat.getStringOrNull
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

enum class DisplayElementType { BOX, TEXT, LINE, ITEM, ENTITY }

/**
 * One primitive in a Display's projected volume. Mutable so the Lua builder
 * ([damien.nodeworks.script.DisplayHandle]) can append the element and then
 * configure it through chained calls.
 *
 * The Display is a pure draw surface, all of these are output-only, there are
 * no interactive elements (interaction lives on the separate Widget block).
 *
 * **Coordinates are normalized**, OpenGL-NDC style: [x]/[y] in `-1..1` span the
 * whole display surface (`(-1, -1)` = bottom-left, `+y` up). For BOX/LINE
 * `(x, y)` is a corner / start point; for TEXT it's the anchor; for ITEM/ENTITY
 * the centre. [z] in `0..1` is depth outward from the face.
 */
class DisplayElement(
    val type: DisplayElementType,
    val id: Int,
) {
    var x: Float = 0f
    var y: Float = 0f
    var z: Float = 0f
    var w: Float = 0f
    var h: Float = 0f
    var d: Float = 0f

    /** Line end point, only meaningful for [DisplayElementType.LINE]. */
    var x2: Float = 0f
    var y2: Float = 0f

    var color: Int = DEFAULT_COLOR
    var color2: Int = DEFAULT_COLOR
    var gradient: Boolean = false

    /** 0 = solid fill; > 0 = outline only, this many "pixels" (1px = 1/128 block) thick. */
    var wireframe: Int = 0

    /** In-plane rotation in degrees, around the element's centre. */
    var rotation: Float = 0f

    /** TEXT content; ITEM / ENTITY registry id. */
    var label: String = ""
    var billboard: Boolean = false

    /** Font-size multiplier for TEXT; model-scale for ITEM/ENTITY. */
    var textSize: Float = 1f

    /** TEXT label rotation in degrees. */
    var textRotation: Float = 0f

    fun writeTo(o: ValueOutput) {
        o.putString("type", type.name)
        o.putInt("id", id)
        o.putFloat("x", x); o.putFloat("y", y); o.putFloat("z", z)
        o.putFloat("w", w); o.putFloat("h", h); o.putFloat("d", d)
        o.putFloat("x2", x2); o.putFloat("y2", y2)
        o.putInt("color", color)
        o.putInt("color2", color2)
        o.putBoolean("gradient", gradient)
        o.putInt("wireframe", wireframe)
        o.putFloat("rot", rotation)
        o.putString("label", label)
        o.putBoolean("billboard", billboard)
        o.putFloat("textSize", textSize)
        o.putFloat("textRot", textRotation)
    }

    companion object {
        /** Opaque mid-gray, the default for an un-`:color()`-ed element. */
        const val DEFAULT_COLOR: Int = 0xFF808080.toInt()

        fun readFrom(i: ValueInput): DisplayElement? {
            val typeName = i.getStringOrNull("type") ?: return null
            val type = runCatching { DisplayElementType.valueOf(typeName) }.getOrNull() ?: return null
            val id = i.getIntOr("id", -1)
            if (id < 0) return null
            return DisplayElement(type, id).apply {
                x = i.getFloatOr("x", 0f); y = i.getFloatOr("y", 0f); z = i.getFloatOr("z", 0f)
                w = i.getFloatOr("w", 0f); h = i.getFloatOr("h", 0f); d = i.getFloatOr("d", 0f)
                x2 = i.getFloatOr("x2", 0f); y2 = i.getFloatOr("y2", 0f)
                color = i.getIntOr("color", DEFAULT_COLOR)
                color2 = i.getIntOr("color2", color)
                gradient = i.getBooleanOr("gradient", false)
                wireframe = i.getIntOr("wireframe", 0)
                rotation = i.getFloatOr("rot", 0f)
                label = i.getStringOr("label", "")
                billboard = i.getBooleanOr("billboard", false)
                textSize = i.getFloatOr("textSize", 1f)
                textRotation = i.getFloatOr("textRot", 0f)
            }
        }
    }
}
