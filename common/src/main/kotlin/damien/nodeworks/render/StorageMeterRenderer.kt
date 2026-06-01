package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.math.Axis
import damien.nodeworks.block.StorageMeterBlock
import damien.nodeworks.block.entity.StorageMeterBlockEntity
import damien.nodeworks.client.StorageMeterEmissiveModel
import damien.nodeworks.client.StorageMeterRedstoneActiveModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Block Entity renderer for the Storage Meter. Three submit passes:
 *  1. Emissive overlay tinted with the network colour, baked quads from
 *     `storage_meter_emissive.json`. Skipped when disconnected.
 *  2. Redstone-active overlay, drawn when below threshold. Untinted.
 *  3. Target item icon on the front face. Count text is rendered on top by
 *     [NodeConnectionRenderer.renderStorageMeterText].
 */
class StorageMeterRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<StorageMeterBlockEntity, StorageMeterRenderer.MeterState>(context) {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    class MeterState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var isBelowThreshold: Boolean = false
        val itemRS: ItemStackRenderState = ItemStackRenderState()
        var hasItem: Boolean = false
    }

    companion object {
        // Overlay outset so the additive glow wins the LEQUAL depth test.
        private const val OVERLAY_OUTSET = 1.001f

        // All six directions + null for BlockStateModelPart.getQuads.
        private val DIRECTIONS_AND_NULL: Array<Direction?> = arrayOf(
            Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST,
            null,
        )
    }

    override fun createRenderState(): MeterState = MeterState()

    override fun extractConnectable(
        blockEntity: StorageMeterBlockEntity,
        state: MeterState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(StorageMeterBlock.FACING)
        state.color = resolveNetworkColor(blockEntity)
        state.isBelowThreshold = blockEntity.isBelowThreshold

        val target = blockEntity.target
        if (target.isEmpty) {
            state.hasItem = false
        } else {
            itemModelResolver.updateForTopItem(
                state.itemRS,
                target,
                ItemDisplayContext.GUI,
                blockEntity.level,
                null,
                0,
            )
            state.hasItem = true
        }
    }

    override fun submitConnectable(
        state: MeterState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val connected = state.color != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val emissive = StorageMeterEmissiveModel.get()
        if (connected && emissive != null) {
            submitOverlay(state.facing, poseStack, submitNodeCollector, emissive, state.color)
        }
        val redstone = StorageMeterRedstoneActiveModel.get()
        if (state.isBelowThreshold && redstone != null) {
            // White tint, PNG carries its own colour.
            submitOverlay(state.facing, poseStack, submitNodeCollector, redstone, 0xFFFFFF)
        }

        if (state.hasItem) {
            poseStack.pushPose()
            poseStack.translate(0.5, 0.5, 0.5)
            rotateIconToFace(poseStack, state.facing)
            poseStack.translate(0.0, 0.0, 0.5)
            poseStack.scale(1f, 1f, -1f)
            // -0.001 matches CraftRequesterRenderer.ICON_Z_BIAS so the count
            // text overlay (drawn at 0.002 outward) layers cleanly on top.
            poseStack.translate(0.0, 0.0, -0.001)
            poseStack.scale(0.34f, 0.34f, 0.001f)
            state.itemRS.submit(poseStack, submitNodeCollector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }
    }

    private fun submitOverlay(
        facing: Direction,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        part: BlockStateModelPart,
        tintColor: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        applyBodyRotation(poseStack, facing)
        poseStack.scale(OVERLAY_OUTSET, OVERLAY_OUTSET, OVERLAY_OUTSET)
        poseStack.translate(-0.5, -0.5, -0.5)

        val argb = ARGB.color(
            255,
            (tintColor shr 16) and 0xFF,
            (tintColor shr 8) and 0xFF,
            tintColor and 0xFF,
        )
        val quadInstance = QuadInstance().apply {
            for (vertex in 0..3) setColor(vertex, argb)
        }
        collector.submitCustomGeometry(poseStack, EmissiveCubeRenderer.BLOCK_ATLAS_RENDER_TYPE) { pose, vc ->
            for (dir in DIRECTIONS_AND_NULL) {
                for (quad in part.getQuads(dir)) {
                    vc.putBakedQuad(pose, quad, quadInstance)
                }
            }
        }

        poseStack.popPose()
    }

    // Mirrors the per-variant Y rotation in blockstates/storage_meter.json.
    // Negated because MC blockstate Y is clockwise from above.
    private fun applyBodyRotation(poseStack: PoseStack, facing: Direction) {
        when (facing) {
            Direction.NORTH -> Unit
            Direction.EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(-180f))
            Direction.WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-270f))
            else -> Unit
        }
    }

    // GUI-context items face +Z, so SOUTH is the identity.
    private fun rotateIconToFace(poseStack: PoseStack, face: Direction) {
        when (face) {
            Direction.SOUTH -> Unit
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            else -> Unit
        }
    }
}
