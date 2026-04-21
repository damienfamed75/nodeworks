package damien.nodeworks.guide

import damien.nodeworks.platform.GuidebookService
import guideme.PageAnchor
import guideme.internal.GuideMEClient
import net.minecraft.client.Minecraft

/**
 * NeoForge-side [GuidebookService] impl. Opens the registered Nodeworks guide at the
 * requested ref via GuideME's public [GuideMEClient.openGuideAtAnchor] entry point.
 *
 * ## Why this and not `GuideScreen.openNew` directly
 *
 * `openGuideAtAnchor` routes through `GuideNavigation.navigateTo`, which:
 *   * Short-circuits when we're already inside a GuideScreen for the same guide and just
 *     scrolls / navigates in-place.
 *   * Computes `returnToOnClose` correctly regardless of whether the current screen is
 *     the terminal, an already-open guide, or the search screen.
 *   * Calls `Minecraft.setScreen` itself, so the screen transition lands in the engine's
 *     expected place in the tick cycle.
 *
 * Driving the transition by hand from our keybind callback (via raw `GuideScreen.openNew`
 * + `Minecraft.setScreen`) was timing-sensitive — we fire the callback inside the editor
 * widget's render pass, and flipping `mc.screen` mid-render sometimes landed us in a
 * state where the guide screen was attached but its layout wasn't fully initialised
 * before the next frame's background blur was already drawing. Using the official API
 * lets GuideME handle that transition the way its own hotkey path does.
 *
 * Wired into [damien.nodeworks.platform.PlatformServices.guidebook] at client-init time
 * in `NeoForgeClientSetup`.
 */
object NodeworksGuidebookService : GuidebookService {
    override fun open(ref: String) {
        val guide = NodeworksGuide.instance ?: return
        // Defer the transition to the next main-thread tick. `Minecraft.execute`
        // enqueues onto the main thread's task queue; since our callback fires mid-
        // render (ScriptEditor.extractWidgetRenderState), running the setScreen
        // inline can race against the frame that's still in flight. Queuing gives us
        // a clean frame boundary before the guide screen takes over.
        Minecraft.getInstance().execute {
            GuideMEClient.openGuideAtAnchor(guide, PageAnchor.parse(ref))
        }
    }
}
