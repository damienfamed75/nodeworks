package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * TODO MC 26.1.2 BER REWRITE — stubbed.
 *
 * Pre-migration: renders per-monitor item icon + tracked count on node faces
 * that carry a Monitor card. Used ItemRenderer + Font via PoseStack /
 * MultiBufferSource. Needs full port to the extract-state pipeline: a
 * `MonitorRenderState` carrying the per-face (direction, itemId, displayCount)
 * tuples, extracted from `NodeBlockEntity.monitors`, then submitted in
 * `submit(...)` via the new SubmitNodeCollector. See git history for the
 * pre-migration body.
 */
class MonitorRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<NodeBlockEntity, BlockEntityRenderState> {

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
