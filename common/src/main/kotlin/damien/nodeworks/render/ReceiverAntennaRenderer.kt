package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * No-op BER. Paired-status visual is handled by the multipart blockstate
 * (`horn_on` vs `horn_off`). See [ControllerRenderer] for the broader
 * rationale on why these BERs remain as empty hooks.
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
