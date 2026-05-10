package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the network-colour-tinted emissive overlay on the Variable
 * block's body. Geometry mirrors `nodeworks:block/variable` (via JSON
 * parent inheritance) so the author can paint emissive content on any face
 * of any element via variable_emissive.png and it just lights up. Mirrors
 * the role [UserEmissiveModel] / [PlacerEmissiveModel] / [BreakerEmissiveModel]
 * play for the directional devices - see [UserEmissiveModel] for the
 * loader-bridging rationale.
 */
object VariableEmissiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
