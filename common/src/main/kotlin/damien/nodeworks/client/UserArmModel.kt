package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Bridge between :common's [damien.nodeworks.render.UserRenderer] and
 * :neoforge's standalone-model registration in `NeoForgeClientSetup`.
 *
 * The renderer needs the baked [BlockStateModelPart] for the User's arm
 * at draw time, but the actual model registration uses NeoForge's
 * `StandaloneModelKey` API which lives in the loader-specific module.
 * This holder exposes a [fetcher] lambda that the loader populates after
 * the standalone model is registered, keeping the renderer loader-agnostic.
 *
 * `null` until the model bakes (resource pack reload) and on loaders that
 * haven't wired up the fetcher yet (Fabric, future loaders) -- in either
 * case the renderer falls back gracefully and skips drawing the arm.
 */
object UserArmModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}

/**
 * Holder for the network-colour-tinted emissive overlay on the User's main
 * body. Geometry mirrors `nodeworks:block/user` (via JSON parent
 * inheritance) so the user.json author can paint emissive content on any
 * face of any element via user_emissive.png and it just lights up. The
 * baked model carries the same per-face UV regions as user.json, so the
 * BER's overlay aligns with the body's texture mapping (rather than
 * stretching one image across each face like single-tile pattern would).
 */
object UserEmissiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
