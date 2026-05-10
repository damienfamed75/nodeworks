package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the network-colour-tinted emissive overlay on the Breaker's
 * body. Geometry mirrors `nodeworks:block/breaker` (via JSON parent
 * inheritance) so the author can paint emissive content on any face of any
 * element via breaker_emissive.png and it just lights up. Mirrors the role
 * [UserEmissiveModel] / [PlacerEmissiveModel] play for the other directional
 * devices - see [UserEmissiveModel] for the loader-bridging rationale.
 */
object BreakerEmissiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
