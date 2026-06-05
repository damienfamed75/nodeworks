package damien.nodeworks.api

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

/** Cosmetic metadata for a device shown in UI surfaces (the Diagnostic Tool's topology
 *  view, the Scripting Terminal sidebar). A null [DeviceType.displayInfo] means "this
 *  device is invisible in UI, scripts can still address it through `network:get`".
 *
 *  [icon] is a function rather than a stored stack so the SPI can be safely registered
 *  during mod construction, before items are fully baked. The function is called lazily
 *  when a UI surface needs to render the icon. */
data class DeviceDisplay(
    /** Human-readable name. Shown as the device label in tooltips and headers. Keep it
     *  short, the terminal sidebar truncates aggressively. */
    val displayName: String,

    /** ARGB tint used for the device's accent (text in the sidebar, the topology pill
     *  in the Diagnostic Tool). Pick a color that reads against both the white sidebar
     *  panel and the dark topology background. */
    val tintColor: Int,

    /** Icon item for picker UIs (the Diagnostic Tool's filter, the Scripting Terminal's
     *  sidebar fallback). Lazy because items may not exist at the moment a mod registers
     *  its DeviceType (NeoForge registry events fire in a specific order). */
    val icon: () -> ItemStack,

    /** Custom 8×8 render in the Scripting Terminal sidebar row. The lambda receives the
     *  graphics context and the top-left pixel of the icon slot. When null, the sidebar
     *  scales [icon] down to 8×8 instead, which usually looks fine but loses crispness
     *  for pixel-tuned art.
     *
     *  Built-in devices (Variable) and mods that want to match the atlas-sprite look
     *  should supply this and blit their own texture or atlas region. */
    val sidebarRender: ((GuiGraphicsExtractor, Int, Int) -> Unit)? = null,
)
