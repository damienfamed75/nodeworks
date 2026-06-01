package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the network-colour emissive overlay on the Storage Meter's body.
 * Geometry mirrors `nodeworks:block/storage_meter` (via JSON parent
 * inheritance) so the author can paint emissive content on any face of any
 * element via storage_meter_emissive.png and it just lights up. Same role
 * [BreakerEmissiveModel] / [UserEmissiveModel] / [PlacerEmissiveModel] play
 * for the other devices.
 */
object StorageMeterEmissiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
