package damien.nodeworks.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.settings.KeyModifier
import damien.nodeworks.network.OpenPortableTerminalPayload
import damien.nodeworks.platform.PlatformServices

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
    /** Own section in the controls screen, labelled via key.category.nodeworks.nodeworks.
     *  Only constructed here, [RegisterKeyMappingsEvent.registerCategory] registers it.
     *  Declared first, the mappings below reference it during object init. */
    val category: KeyMapping.Category = KeyMapping.Category(Identifier.fromNamespaceAndPath("nodeworks", "nodeworks"))

    /** Key shown in controls as "Open Docs on Hover". Default: G. Conflict context is
     *  [KeyConflictContext.GUI] so a rebinding conflict is only flagged against other
     *  GUI-scope keys, the binding only fires from the Scripting Terminal's editor
     *  widget anyway. */
    val openDocs: KeyMapping = KeyMapping(
        "key.nodeworks.open_docs",
        KeyConflictContext.GUI,
        InputConstants.Type.KEYSYM.getOrCreate(71), // GLFW_KEY_G (Key, not Type, bundles both)
        category,
    )

    /** Key shown in controls as "Open Portable Inventory Terminal". Default: Ctrl+E.
     *  The server opens the first Portable found by inventory slot order. */
    val openPortableTerminal: KeyMapping = KeyMapping(
        "key.nodeworks.open_portable_terminal",
        KeyConflictContext.IN_GAME,
        KeyModifier.CONTROL,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_E,
        category,
    )

    fun register(modBus: IEventBus) {
        modBus.addListener { event: RegisterKeyMappingsEvent ->
            event.registerCategory(category)
            event.register(openDocs)
            event.register(openPortableTerminal)
        }
    }

    /** Runs before vanilla handles keybinds for the tick. In-game, a held modifier
     *  does not suppress unmodified bindings, so with the Ctrl+E default the
     *  inventory key fires too. Eating its pending click here, before vanilla
     *  sees it, means the Portable opens directly instead of after an inventory flash. */
    fun tickOpenPortable() {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.screen != null) return
        var fired = false
        while (openPortableTerminal.consumeClick()) fired = true
        if (!fired) return
        val inventoryKey = mc.options.keyInventory
        if (inventoryKey.key == openPortableTerminal.key) {
            while (inventoryKey.consumeClick()) { /* paired press, swallowed */ }
        }
        PlatformServices.clientNetworking.sendToServer(OpenPortableTerminalPayload)
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
