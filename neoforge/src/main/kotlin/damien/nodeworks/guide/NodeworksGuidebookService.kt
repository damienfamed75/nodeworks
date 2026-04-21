package damien.nodeworks.guide

import damien.nodeworks.platform.GuidebookService
import guideme.PageAnchor
import guideme.internal.screen.GuideScreen
import net.minecraft.client.Minecraft

/**
 * NeoForge-side [GuidebookService] impl. Opens the registered Nodeworks guide at the
 * requested ref via GuideME's [GuideScreen.openNew]. Ref format:
 * `namespace:path#fragment` — [PageAnchor.parse] handles both the `#fragment`-less and
 * fragment-bearing forms.
 *
 * [GuideScreen.openNew] only CONSTRUCTS the screen (pushes to history + returns the
 * instance), it doesn't display it — so we also have to call `Minecraft.setScreen` to
 * actually show the guide. Easy trap to fall into: the progress bar fills, the screen
 * is built in memory, but nothing visibly happens.
 *
 * Wired into [damien.nodeworks.platform.PlatformServices.guidebook] at client-init time
 * in `NeoForgeClientSetup`. Guarded against being called before the guide finishes
 * building (unlikely — register runs synchronously during mod construction, well before
 * any Scripting Terminal screen opens — but safer than a lateinit NPE).
 */
object NodeworksGuidebookService : GuidebookService {
    override fun open(ref: String) {
        val guide = NodeworksGuide.instance ?: return
        val screen = GuideScreen.openNew(guide, PageAnchor.parse(ref))
        Minecraft.getInstance().setScreen(screen)
    }
}
