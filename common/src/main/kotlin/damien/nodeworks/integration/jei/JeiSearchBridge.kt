package damien.nodeworks.integration.jei

/**
 * Bridge between the Inventory Terminal's search box and JEI's ingredient
 * filter. The JEI plugin populates [getter] / [setter] on runtime-available;
 * everything else (screen code) calls through here without taking a JEI
 * compile dependency on the call site.
 *
 * When JEI is absent (or the runtime hasn't arrived yet) both accessors
 * silently no-op so the sync button still toggles, it just has nothing to
 * mirror against.
 */
object JeiSearchBridge {

    /** Returns JEI's current filter text, or null when JEI isn't loaded. */
    var getter: (() -> String)? = null

    /** Pushes [text] into JEI's filter bar. */
    var setter: ((String) -> Unit)? = null

    val isAvailable: Boolean get() = getter != null && setter != null

    fun read(): String? = getter?.invoke()

    fun write(text: String) {
        setter?.invoke(text)
    }
}
