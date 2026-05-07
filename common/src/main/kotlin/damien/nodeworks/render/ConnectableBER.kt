package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.network.Connectable
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * Base render state for every [ConnectableBER]. Holds the source position
 * plus the per-frame extracted inter-block beam list. Only Focus Nodes
 * populate `getConnections`, so the cost is zero for every other Connectable.
 */
open class ConnectableRenderState : BlockEntityRenderState() {
    var pos: BlockPos = BlockPos.ZERO
    var connectionBeams: List<FocusBeamRenderer.Beam> = emptyList()
}

/**
 * Abstract BER for every Connectable block. Provides the shared scaffolding so concrete
 * subclasses don't have to duplicate the position-extract step. Subclasses implement
 * [extractConnectable] / [submitConnectable] for their block-specific logic, the top-level
 * `extractRenderState` / `submit` overrides are `final` so the scaffolding can't be bypassed.
 *
 * [resolveNetworkColor] is the shared network-color lookup that degrades correctly in
 * GuideME scene renders (where the main world's reachability BFS hasn't populated its
 * cache and an unchecked [NodeConnectionRenderer.isReachable] would flip every preview
 * to the grey default).
 */
abstract class ConnectableBER<T, S : ConnectableRenderState>(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<T, S> where T : BlockEntity, T : Connectable {

    final override fun extractRenderState(
        blockEntity: T,
        state: S,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        state.pos = blockEntity.getBlockPos()
        state.connectionBeams = FocusBeamRenderer.extract(blockEntity)
        extractConnectable(blockEntity, state, partialTicks, cameraPosition, breakProgress)
    }

    final override fun submit(
        state: S,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        FocusBeamRenderer.submit(state.connectionBeams, poseStack, submitNodeCollector, state.pos, camera.pos)
        submitConnectable(state, poseStack, submitNodeCollector, camera)
    }

    /** Block-specific extract. Base scaffolding ([BlockEntityRenderState.extractBase],
     *  `pos`, `connectionBeams`) has already run. */
    protected abstract fun extractConnectable(
        blockEntity: T,
        state: S,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    )

    /** Block-specific submit. Beams have already been submitted. */
    protected abstract fun submitConnectable(
        state: S,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    )

    /** Per-frame laser geometry stays inside the unit cube, so frustum culling
     *  is correct. Focus Nodes widen [getRenderBoundingBox] so the culler
     *  honours their long-range beams. */
    override fun shouldRenderOffScreen(): Boolean = false

    /** Network colour for [blockEntity]. Defers to [Connectable.networkColor], which
     *  trusts the propagated [Connectable.networkId]. GuideME scene blocks must set
     *  networkId at scene-construction time, otherwise they render grey. */
    protected fun resolveNetworkColor(blockEntity: T): Int = blockEntity.networkColor()
}
