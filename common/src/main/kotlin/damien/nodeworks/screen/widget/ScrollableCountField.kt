package damien.nodeworks.screen.widget

import damien.nodeworks.compat.hasControlDownCompat
import damien.nodeworks.compat.hasShiftDownCompat
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * Integer-only [EditBox] with scroll-wheel increment/decrement support. Used
 * across device GUIs that need a numeric count input (Storage Meter threshold,
 * Craft Requester batch size, future shared use in Processing Set slot
 * counts).
 *
 * Inputs:
 *  * Scroll up / down → ±1, ±10 with Shift held, ±64 with Ctrl held.
 *  * Click + type → standard EditBox text entry, non-digit characters are
 *    silently rejected by the responder.
 *
 * The host adds this as a screen widget like any other [EditBox]. [onChange]
 * fires whenever the value changes (via scroll or typing) with the clamped
 * integer value.
 */
class ScrollableCountField(
    font: Font,
    x: Int, y: Int, width: Int, height: Int,
    initialValue: Int,
    private val onChange: (Int) -> Unit,
) : EditBox(font, x, y, width, height, Component.literal("Count")) {

    var minValue: Int = 0
        private set
    var maxValue: Int = Int.MAX_VALUE
        private set

    init {
        setMaxLength(10)
        setBordered(true)
        value = initialValue.toString()
        setResponder { text ->
            val parsed = text.toIntOrNull() ?: return@setResponder
            val clamped = parsed.coerceIn(minValue, maxValue)
            onChange(clamped)
        }
    }

    /** Current value parsed from the text buffer, clamped to bounds. Returns
     *  [minValue] when the buffer is empty or unparseable. */
    val currentValue: Int
        get() = value.toIntOrNull()?.coerceIn(minValue, maxValue) ?: minValue

    /** Push a server-confirmed value into the field. */
    fun setCurrentValue(v: Int) {
        val clamped = v.coerceIn(minValue, maxValue)
        if (clamped.toString() != value) {
            value = clamped.toString()
        }
    }

    fun setValueBounds(min: Int, max: Int) {
        minValue = min
        maxValue = max
        val cur = currentValue
        if (cur < min || cur > max) setCurrentValue(cur)
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double,
    ): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        val direction = if (scrollY > 0) 1 else -1
        val step = when {
            hasControlDownCompat() -> 64
            hasShiftDownCompat() -> 10
            else -> 1
        }
        setCurrentValue(currentValue + direction * step)
        return true
    }
}
