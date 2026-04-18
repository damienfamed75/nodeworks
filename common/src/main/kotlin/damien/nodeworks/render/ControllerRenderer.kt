package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * Renders an emissive overlay on the Network Controller (AE2-style glowing lines).
 *
 * TODO MC 26.1.2 BER REWRITE — stubbed.
 *
 * MC 26.1 replaces the old
 *     render(T blockEntity, float partialTick, PoseStack pose,
 *            MultiBufferSource buffer, int light, int overlay)
 * with a two-phase extract/submit pipeline:
 *     createRenderState(): S
 *     extractRenderState(blockEntity, state, partialTick, camera, breakProgress)
 *     submit(state, poseStack, submitNodeCollector, camera)
 * driven by `BlockEntityRenderer<T, S extends BlockEntityRenderState>`.
 *
 * The pre-migration body (a manual quad-mesh for an emissive cube overlay using
 * `RenderType.eyes(tex)` + `bufferSource.getBuffer(...)`) lives in git history as
 * `renderLegacy(...)`. It's also *currently dead* — a comment notes the emissive
 * overlay was moved to the block model's `neoforge_data` fullbright faces — so
 * keeping the BER as a compile-clean shell that does nothing is acceptable
 * until/unless dynamic per-entity rendering is reintroduced.
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
        // Intentionally empty — emissive overlay rendered via block model fullbright faces.
    }
}
