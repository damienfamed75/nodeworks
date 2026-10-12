package damien.nodeworks.compat.ae2

import net.neoforged.fml.ModList

/** Gate for the AE2 bridge. [Ae2Storage] is only touched when this is true, so
 *  the appeng classes it imports are never loaded without the mod installed. */
object Ae2Compat {
    val isLoaded: Boolean by lazy { ModList.get().isLoaded("ae2") }
}
