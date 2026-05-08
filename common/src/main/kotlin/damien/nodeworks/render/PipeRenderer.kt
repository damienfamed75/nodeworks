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
 * Inside-pipe network laser. Pulls the connected directions off the
 * blockstate, the colour and mode out of [NetworkSettingsRegistry], hands
 * everything to [PipeLaserBeam]. PipeBlockEntity.networkColor() is
 * hardcoded grey so the model stays neutral copper, the laser tint goes
 * through a direct registry lookup instead.
 */
class PipeRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<PipeBlockEntity, PipeRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var directions: EnumSet<Direction> = EnumSet.noneOf(Direction::class.java)
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var laserMode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY
        /** False on orphaned pipes (no controller). Suppresses the inner
         *  laser so disconnected cables read as inert hardware. */
        var hasNetwork: Boolean = false
        /** True when the pipe's networkId is a Processing Handler micro-net.
         *  Switches the laser texture to the hazard-stripe variant. */
        var isMicro: Boolean = false
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
        state.isMicro = MicroNetworkClientRegistry.isMicro(id)
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (!state.hasNetwork) return
        // Skip the centre cube on straight runs, the two half-beams form one
        // continuous line so there's no seam to mask.
        val drawCenterCore = state.directions.isNotEmpty() && !isStraightLine(state.directions)
        PipeLaserBeam.submit(
            poseStack, submitNodeCollector, state.pos, camera.pos,
            state.directions, state.color, state.laserMode, drawCenterCore,
            isMicro = state.isMicro,
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
