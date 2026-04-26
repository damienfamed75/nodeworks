package damien.nodeworks.render

import net.minecraft.util.Brightness

/** Shared constants and helpers for block-entity renderers in this package. */
object RenderUtils {
    /** Packed lightmap value for full sky + full block light. */
    val FULL_BRIGHT: Int = Brightness.FULL_BRIGHT.pack()
}
