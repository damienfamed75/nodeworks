package damien.nodeworks.guide

import guideme.Guide
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * Registers Nodeworks' in-game guidebook with GuideME.
 *
 * Content lives at [`guidebook/`](../../../../../../../../guidebook) at the repo root and is
 * copied into `assets/nodeworks/nodeworksguide/` at build time by `processResources` in
 * [neoforge/build.gradle.kts](../../../../../../../../neoforge/build.gradle.kts). See
 * [docs/authoring.md](../../../../../../../../docs/authoring.md) for the authoring workflow.
 */
object NodeworksGuide {
    val ID: Identifier = Identifier.fromNamespaceAndPath("nodeworks", "guide")

    /**
     * Build + register the guide. GuideME's builder registers the guide in its global
     * registry when `.build()` is called — no explicit `.register()` step.
     *
     * Call this from the client-setup phase; guides are client-only.
     */
    private val log = LoggerFactory.getLogger("nodeworks-guide")

    fun register() {
        // Matches AE2's pattern — `defaultNamespace` is derived from the ID's namespace,
        // and `startPage` defaults to `index.md`. `folder` points at our processed-
        // resources path (see neoforge/build.gradle.kts's processResources step).
        val guide = Guide.builder(ID)
            .folder("nodeworksguide")
            .build()
        log.info("Registered guide id={} folder=nodeworksguide (expected assets path: assets/nodeworks/nodeworksguide/)", ID)
    }
}
