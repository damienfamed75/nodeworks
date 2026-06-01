package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the network-colour emissive overlay on the Craft Requester's
 * body. Geometry mirrors `nodeworks:block/craft_requester` via JSON parent
 * inheritance so the author can paint emissive content on any face of any
 * element in craft_requester_emissive.png and it just lights up. Same role
 * [StorageMeterEmissiveModel] / [BreakerEmissiveModel] / [UserEmissiveModel]
 * play for the other devices.
 */
object CraftRequesterEmissiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
