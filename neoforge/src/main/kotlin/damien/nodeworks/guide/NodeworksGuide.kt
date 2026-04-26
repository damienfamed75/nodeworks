package damien.nodeworks.guide

import guideme.Guide
import guideme.compiler.TagCompiler
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

    /** The built Guide. Populated by [register] on client init. `NodeworksGuidebookService`
     *  reads this to navigate programmatically from the Scripting Terminal's Hold-G. */
    var instance: Guide? = null
        private set

    /**
     * Build + register the guide. GuideME's builder registers the guide in its global
     * registry when `.build()` is called, no explicit `.register()` step.
     *
     * Call this from the client-setup phase, guides are client-only.
     */
    private val log = LoggerFactory.getLogger("nodeworks-guide")

    fun register() {
        // Matches AE2's pattern, `defaultNamespace` is derived from the ID's namespace,
        // and `startPage` defaults to `index.md`. `folder` points at our processed-
        // resources path (see neoforge/build.gradle.kts's processResources step).
        // Registering a TagCompiler under the same tag name as a default GuideME extension
        // causes PageCompiler's last-write-wins map to pick ours, so `<GameScene>` in any
        // Nodeworks page goes through NodeworksSceneTagCompiler, which adds per-side padding
        // attrs (paddingTop/Bottom/Left/Right) while keeping the default behaviour otherwise.
        val guide = Guide.builder(ID)
            .folder("nodeworksguide")
            .extension(TagCompiler.EXTENSION_POINT, NodeworksSceneTagCompiler())
            .extension(TagCompiler.EXTENSION_POINT, LuaCodeTagCompiler())
            .extension(TagCompiler.EXTENSION_POINT, CategoryIndexDescriptionsTagCompiler())
            // Custom RecipeType renderers, each mapping supplier maps a
            // Mojang RecipeType to an LytBlock factory. Add one per custom
            // recipe type we want GuideME to render.
            .extension(
                guideme.compiler.tags.RecipeTypeMappingSupplier.EXTENSION_POINT,
                SoulSandInfusionRecipeContribution(),
            )
            .build()
        instance = guide
        log.info("Registered guide id={} folder=nodeworksguide (expected assets path: assets/nodeworks/nodeworksguide/)", ID)
    }
}
