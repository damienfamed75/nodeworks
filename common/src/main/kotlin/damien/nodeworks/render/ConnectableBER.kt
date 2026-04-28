package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.network.Connectable
import net.minecraft.client.Minecraft
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
 * Base render state for every [ConnectableBER]. Holds the fields [ConnectionBeamRenderer]
 * needs (block pos + per-frame extracted beam list) so concrete BER states don't have to
 * redeclare them and wire them up by hand.
 */
open class ConnectableRenderState : BlockEntityRenderState() {
    var pos: BlockPos = BlockPos.ZERO
    var connectionBeams: List<ConnectionBeamRenderer.Beam> = emptyList()
}

/**
 * Abstract BER for every Connectable block. Captures the three pieces of shared laser
 * plumbing in one place so subclasses can't silently forget any of them:
 *
 *   1. Extract the outgoing connection-beam list once per frame (driven off the
 *      BE's own [Connectable.getConnections]).
 *   2. Submit beam geometry before any BE-specific geometry, so beams draw
 *      beneath / behind the block's own visual layers.
 *   3. Return `true` from [shouldRenderOffScreen], pairs with the neoforge subclass's
 *      `getRenderBoundingBox` override to keep the BER alive whenever any beam it draws
 *      is in frame, not just when the source block itself is.
 *
 * Subclasses implement [extractConnectable] / [submitConnectable] for their block-specific
 * logic, the top-level `extractRenderState` / `submit` overrides are `final` so the
 * scaffolding can't accidentally be bypassed.
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
        state.connectionBeams = ConnectionBeamRenderer.extract(blockEntity)
        extractConnectable(blockEntity, state, partialTicks, cameraPosition, breakProgress)
    }

    final override fun submit(
        state: S,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        ConnectionBeamRenderer.submit(state.connectionBeams, poseStack, submitNodeCollector, state.pos, camera.pos)
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

    override fun shouldRenderOffScreen(): Boolean = true

    /**
     * Look up the current network color for [blockEntity], falling back to the neutral
     * grey default when the block is unreachable in the live network.
     *
     * In GuideME scene renders the level isn't `Minecraft.getInstance().level` and the
     * reachability BFS has never run, in that case we skip the reachability gate and
     * always resolve through [NodeConnectionRenderer.findNetworkColor] so preview scenes
     * stay vibrant.
     */
    protected fun resolveNetworkColor(blockEntity: T): Int {
        val pos = blockEntity.getBlockPos()
        val level = blockEntity.level ?: return NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val isMainWorld = level === Minecraft.getInstance().level
        return if (isMainWorld && !NodeConnectionRenderer.isReachable(pos)) {
            NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        } else {
            NodeConnectionRenderer.findNetworkColor(level, pos)
        }
    }
}
