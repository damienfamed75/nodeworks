package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the network-colour-tinted emissive overlay on the Placer's
 * body. Geometry mirrors `nodeworks:block/placer` (via JSON parent
 * inheritance) so the author can paint emissive content on any face of any
 * element via placer_emissive.png and it just lights up. Mirrors the role
 * [UserEmissiveModel] plays for the User device - see that holder for the
 * loader-bridging rationale.
 */
object PlacerEmissiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
