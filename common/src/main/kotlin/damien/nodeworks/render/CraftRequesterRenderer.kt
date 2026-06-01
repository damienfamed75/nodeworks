package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.math.Axis
import damien.nodeworks.block.CoveredPipeBlock
import damien.nodeworks.block.CraftRequesterBlock
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.block.entity.CraftRequesterBlockEntity
import damien.nodeworks.client.CraftRequesterEmissiveModel
import damien.nodeworks.client.CraftRequesterRedstoneActiveModel
import damien.nodeworks.network.Connectable
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
 * Block Entity renderer for the Craft Requester. Three submit passes:
 *  1. Emissive overlay tinted with the network colour, baked quads from
 *     `craft_requester_emissive.json`. Skipped when disconnected.
 *  2. Redstone-active overlay, drawn while `signalActive`. Untinted.
 *  3. Target item icon on the facing face, in the model's 4×4 px pad.
 *     Hidden when the face is culled or a pipe is connecting on that side.
 */
class CraftRequesterRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<CraftRequesterBlockEntity, CraftRequesterRenderer.RequesterState>(context) {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    class RequesterState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var signalActive: Boolean = false
        val itemRS: ItemStackRenderState = ItemStackRenderState()
        var hasItem: Boolean = false
        var iconVisible: Boolean = false
    }

    companion object {
        // Overlay outset so the additive glow wins the LEQUAL depth test.
        private const val OVERLAY_OUTSET = 1.001f

        // 0.25 = 4 px, matches the model's authored 4×4 face pad.
        private const val ICON_SCALE = 0.25f

        // Outward bias to dodge z-fight with the model face underneath.
        private const val ICON_Z_BIAS = 0.001f

        // All six directions + null for BlockStateModelPart.getQuads.
        private val DIRECTIONS_AND_NULL: Array<Direction?> = arrayOf(
            Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST,
            null,
        )

        /** Whether the facing face would show the target icon. False when
         *  culled by a sturdy neighbour or claimed by an active pipe
         *  connection. Shared with the batch-text pass so they hide together. */
        fun isFacingFaceVisible(blockEntity: CraftRequesterBlockEntity): Boolean {
            val level = blockEntity.level ?: return false
            val facing = blockEntity.blockState.getValue(CraftRequesterBlock.FACING)
            val pos = blockEntity.blockPos
            val neighborPos = pos.relative(facing)
            val neighborState = level.getBlockState(neighborPos)
            if (neighborState.isFaceSturdy(level, neighborPos, facing.opposite)) return false
            if (blockEntity.getConnections().contains(neighborPos)) return false
            val nb = neighborState.block
            if (nb is PipeBlock || nb is CoveredPipeBlock) {
                val neighborConn = level.getBlockEntity(neighborPos) as? Connectable
                val pipeBlocksMe = neighborConn?.forcedPipeBlocked(facing.opposite) == true
                val iBlockPipe = blockEntity.forcedPipeBlocked(facing)
                if (!pipeBlocksMe && !iBlockPipe) return false
            }
            return true
        }
    }

    override fun createRenderState(): RequesterState = RequesterState()

    override fun extractConnectable(
        blockEntity: CraftRequesterBlockEntity,
        state: RequesterState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(CraftRequesterBlock.FACING)
        state.color = resolveNetworkColor(blockEntity)
        state.signalActive = blockEntity.signalActive

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

        state.iconVisible = state.hasItem && isFacingFaceVisible(blockEntity)
    }

    override fun submitConnectable(
        state: RequesterState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val connected = state.color != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val emissive = CraftRequesterEmissiveModel.get()
        if (connected && emissive != null) {
            submitOverlay(state.facing, poseStack, submitNodeCollector, emissive, state.color)
        }
        val redstone = CraftRequesterRedstoneActiveModel.get()
        if (state.signalActive && redstone != null) {
            // White tint, PNG carries its own colour.
            submitOverlay(state.facing, poseStack, submitNodeCollector, redstone, 0xFFFFFF)
        }

        if (state.hasItem && state.iconVisible) {
            submitFaceIcon(state.itemRS, state.facing, poseStack, submitNodeCollector)
        }
    }

    private fun submitFaceIcon(
        itemRS: ItemStackRenderState,
        face: Direction,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        rotateIconToFace(poseStack, face)
        poseStack.translate(0.0, 0.0, 0.5)
        poseStack.scale(1f, 1f, -1f)
        poseStack.translate(0.0, 0.0, -ICON_Z_BIAS.toDouble())
        poseStack.scale(ICON_SCALE, ICON_SCALE, 0.001f)
        itemRS.submit(poseStack, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
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

    // Mirrors the per-variant Y rotation in blockstates/craft_requester.json.
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

}
