package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the redstone-active overlay on the Craft Requester's body.
 * Drawn alongside [CraftRequesterEmissiveModel] while the input signal is
 * high; the two textures don't overlap in pixel space so they stack without
 * fighting. Geometry mirrors `nodeworks:block/craft_requester` via JSON
 * parent inheritance.
 */
object CraftRequesterRedstoneActiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
