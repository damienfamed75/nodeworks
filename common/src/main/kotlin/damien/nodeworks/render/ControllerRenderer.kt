package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * The Network Controller's emissive overlay is driven entirely by the block
 * model's fullbright faces + a NetworkColorTintSource — there is no dynamic
 * per-entity rendering. This BER is an intentional no-op kept registered as a
 * hook in case dynamic rendering is reintroduced. Reachability-flip chunk
 * invalidation for every network-tinted block is handled centrally by
 * [NodeConnectionRenderer.refreshLosCache].
 */
class ControllerRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<NetworkControllerBlockEntity, BlockEntityRenderState> {

    override fun createRenderState(): BlockEntityRenderState = BlockEntityRenderState()

    override fun submit(
        state: BlockEntityRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
    }
}
