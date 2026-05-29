package damien.nodeworks.block.entity

import net.minecraft.util.StringRepresentable

/**
 * The four configurable behaviours of a Widget block. BUTTON is momentary
 * (no persisted state, just fires its callback on each press); SWITCH,
 * RADIO, and SLIDER are stateful, their value lives on the block and
 * survives reloads.
 *
 *  * BUTTON, value is always 0, every press bumps the interaction generation.
 *  * SWITCH, value is 0 or 1.
 *  * RADIO, value is the selected option index, right-click cycles it.
 *  * SLIDER, value is a number in `[min, max]` snapped to `step`.
 *
 * Doubles as a blockstate enum property so the chunk renderer can pick a
 * per-type custom model. [getSerializedName] is the value used in the
 * blockstate JSON's `widget_type=...` keys.
 */
enum class WidgetType : StringRepresentable {
    BUTTON,
    SWITCH,
    RADIO,
    SLIDER;

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        fun fromOrdinal(ordinal: Int): WidgetType = entries.getOrElse(ordinal) { BUTTON }
    }
}
