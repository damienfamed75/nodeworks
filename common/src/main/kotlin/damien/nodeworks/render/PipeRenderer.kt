package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.block.entity.PipeBlockEntity
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * Renders the network-tinted laser inside Pipe blocks. Geometry comes from
 * [PipeLaserBeam], one half-beam per connected face emanating from the block
 * centre. Pipe.networkColor() is hardcoded to grey on purpose so the body
 * texture stays neutral copper, the laser tint comes from a direct registry
 * lookup against the propagated networkId.
 */
class PipeRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<PipeBlockEntity, PipeRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var directions: EnumSet<Direction> = EnumSet.noneOf(Direction::class.java)
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var laserMode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY
        /** False when the pipe has no controller (orphan or pre-network).
         *  Suppresses the inner laser entirely so disconnected cables read as
         *  inert hardware rather than glowing-but-grey. */
        var hasNetwork: Boolean = false
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: PipeBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.directions.clear()
        val bs = blockEntity.blockState
        if (bs.block is PipeBlock) {
            for (dir in Direction.entries) {
                if (bs.getValue(PipeBlock.propFor(dir))) state.directions.add(dir)
            }
        }
        val id = blockEntity.networkId
        val settings = NetworkSettingsRegistry.get(id)
        state.color = settings.color
        state.laserMode = settings.laserMode
        state.hasNetwork = id != null
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // No laser at all on orphaned pipes. The pipe body itself is still
        // rendered by the JSON model.
        if (!state.hasNetwork) return
        // Centre-core mask is only useful where multiple half-beams meet.
        // A clean straight pipe (two opposite directions) draws as a single
        // continuous line so the centre cube would just stick out for no
        // reason, skip it there.
        val drawCenterCore = state.directions.isNotEmpty() && !isStraightLine(state.directions)
        PipeLaserBeam.submit(
            poseStack, submitNodeCollector, state.pos, camera.pos,
            state.directions, state.color, state.laserMode, drawCenterCore,
        )
    }

    private fun isStraightLine(dirs: Set<Direction>): Boolean {
        if (dirs.size != 2) return false
        val it = dirs.iterator()
        val first = it.next()
        val second = it.next()
        return first.opposite == second
    }
}
