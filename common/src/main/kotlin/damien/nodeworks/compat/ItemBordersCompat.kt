package damien.nodeworks.compat

import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

/**
 * Rarity borders from the Item Borders mod on items Nodeworks draws itself. The mod
 * hooks every real Slot through the container screen, so the crafting grid and the
 * player inventory already get borders. The network grid and the pinned row are drawn
 * without Slots, so they ask the mod directly, before the item, as the mod does for a
 * Slot. The mod applies its own settings, common items off, square corners, and so on.
 *
 * Client side. Nothing from the mod is touched unless it is loaded, and a failure inside
 * it switches the bridge off for the session rather than failing every frame.
 */
object ItemBordersCompat {
    private const val MOD_ID = "itemborders"

    private var enabled: Boolean? = null

    private fun enabled(): Boolean {
        enabled?.let { return it }
        val on = PlatformServices.modState.isModLoaded(MOD_ID) && try {
            Class.forName("com.anthonyhilyard.itemborders.ItemBorders")
            true
        } catch (_: Throwable) {
            false
        }
        enabled = on
        return on
    }

    /** Draws the border for [stack] at the item position [x], [y], when the mod is present. */
    fun renderBorder(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (stack.isEmpty || !enabled()) return
        try {
            Bridge.render(graphics, stack, x, y)
        } catch (_: Throwable) {
            enabled = false
        }
    }

    /** The only code that names the mod's class, so it loads only once [enabled] is true. */
    private object Bridge {
        fun render(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
            com.anthonyhilyard.itemborders.ItemBorders.renderBorder(graphics, stack, x, y)
        }
    }
}
