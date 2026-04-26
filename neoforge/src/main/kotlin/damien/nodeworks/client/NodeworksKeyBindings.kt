package damien.nodeworks.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext

/**
 * Nodeworks-owned [KeyMapping] instances. Registered via
 * [RegisterKeyMappingsEvent] on the mod bus so they show up in the vanilla controls
 * menu and are fully user-rebindable.
 *
 * Everything exposed here is a raw `KeyMapping` reference rather than a
 * "is this pressed?" boolean accessor, the calling code (typically a widget's
 * `keyPressed`) gets the GLFW keyCode + scanCode from the event and asks the mapping
 * to match, which correctly accounts for rebinding, scancode-bound keys, etc.
 */
object NodeworksKeyBindings {
    /** Key shown in controls as "Open Docs on Hover". Default: G. Conflict context is
     *  [KeyConflictContext.GUI] so a rebinding conflict is only flagged against other
     *  GUI-scope keys, the binding only fires from the Scripting Terminal's editor
     *  widget anyway. Category is MISC, a custom category would need a registered
     *  translation entry and we're keeping the footprint small for now. */
    val openDocs: KeyMapping = KeyMapping(
        "key.nodeworks.open_docs",
        KeyConflictContext.GUI,
        InputConstants.Type.KEYSYM.getOrCreate(71), // GLFW_KEY_G (Key, not Type, bundles both)
        KeyMapping.Category.MISC,
    )

    fun register(modBus: IEventBus) {
        modBus.addListener { event: RegisterKeyMappingsEvent ->
            event.register(openDocs)
        }
    }

    /** Polls the current held state of the bound key directly via GLFW, bypasses focus
     *  routing so we see the key as held even while a text widget has focus. Matches the
     *  pattern GuideME's own OpenGuideHotkey uses to drive its item-tooltip progress bar. */
    fun openDocsKeyHeld(): () -> Boolean = {
        if (openDocs.isUnbound) {
            false
        } else {
            val keyCode = openDocs.key.value
            InputConstants.isKeyDown(Minecraft.getInstance().window, keyCode)
        }
    }
}
