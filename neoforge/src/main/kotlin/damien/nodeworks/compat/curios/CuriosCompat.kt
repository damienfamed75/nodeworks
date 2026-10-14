package damien.nodeworks.compat.curios

import net.neoforged.fml.ModList

/** Gate for the Curios slot. [CuriosPortableSlot] is only touched when this is true, so
 *  the Curios classes it imports are never loaded without the mod installed. The slot
 *  itself is data (`data/nodeworks/curios`), which Curios ignores when absent. */
object CuriosCompat {
    val isLoaded: Boolean by lazy { ModList.get().isLoaded("curios") }

    fun init() {
        if (isLoaded) CuriosPortableSlot.register()
    }
}
