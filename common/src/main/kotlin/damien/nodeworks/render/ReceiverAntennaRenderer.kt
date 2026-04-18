package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * TODO MC 26.1.2 BER REWRITE — stubbed.
 *
 * Pre-migration: renders the paired-status glow on the receiver antenna's
 * horn segment when a valid broadcast link is active. Much of that visual
 * moved into the multipart blockstate (`horn_on` vs `horn_off` models), so
 * this BER may end up near-empty when fully restored.
 */
class ReceiverAntennaRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ReceiverAntennaBlockEntity, BlockEntityRenderState> {

    override fun createRenderState(): BlockEntityRenderState = BlockEntityRenderState()

    override fun submit(
        state: BlockEntityRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        // Intentionally empty — see TODO above.
    }
}
