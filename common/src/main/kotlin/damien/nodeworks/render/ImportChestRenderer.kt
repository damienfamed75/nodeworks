package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ImportChestBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3

/**
 * Minimal BER for the Import Chest, only here so [ConnectableBER]'s laser-beam
 * pipeline runs against the chest's outgoing connections. The block model
 * itself paints normally through the standard chunk-mesh path, so this class
 * has nothing to draw beyond beams.
 */
open class ImportChestRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<ImportChestBlockEntity, ConnectableRenderState>(context) {

    override fun createRenderState(): ConnectableRenderState = ConnectableRenderState()

    override fun extractConnectable(
        blockEntity: ImportChestBlockEntity,
        state: ConnectableRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        // Beams are extracted by the base class via `connectionBeams`. Nothing
        // chest-specific to record here.
    }

    override fun submitConnectable(
        state: ConnectableRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // Block model handled by chunk meshing, beams already submitted by base.
    }
}
