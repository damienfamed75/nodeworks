package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.ProcessingStorageBlock
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Renders a small card icon over each filled card slot on the Processing Storage's
 * front face. The [CARD_TEXTURE] is a 4×2 image that maps onto the 8 slot cutouts
 * painted into `processing_storage_front.png` at the positions in [SLOT_POSITIONS].
 */
class ProcessingStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ProcessingStorageBlockEntity, ProcessingStorageRenderer.RenderState> {

    class RenderState : BlockEntityRenderState() {
        val filled: BooleanArray = BooleanArray(ProcessingStorageBlockEntity.TOTAL_SLOTS)
        var facing: Direction? = null
        var anyFilled: Boolean = false
    }

    /** Tracks connection state per block position so we can trigger a chunk rebuild
     *  when the block's reachability flips — the BlockTintSource needs a section
     *  invalidation for the color provider to re-evaluate the emissive tint.
     *  NetworkSettingsRegistry.onChanged covers color/glow changes, but LOS-based
     *  reachability flips don't go through the registry so we still track it here. */
    private val lastReachable = HashMap<BlockPos, Boolean>()

    companion object {
        private val CARD_TEXTURE = Identifier.fromNamespaceAndPath(
            "nodeworks", "textures/block/processing_storage_card.png"
        )

        private const val CARD_W = 4
        private const val CARD_H = 2

        private val SLOT_POSITIONS = arrayOf(
            3 to 1,  9 to 1,
            3 to 4,  9 to 4,
            3 to 7,  9 to 7,
            3 to 10, 9 to 10
        )

        private const val Z_OFFSET = 0.001f
        private const val FULLBRIGHT = 15728880
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractRenderState(
        blockEntity: ProcessingStorageBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)

        val blockState = blockEntity.blockState
        state.facing = if (blockState.hasProperty(ProcessingStorageBlock.FACING))
            blockState.getValue(ProcessingStorageBlock.FACING) else null

        var any = false
        for (i in 0 until ProcessingStorageBlockEntity.TOTAL_SLOTS) {
            val filled = !blockEntity.getItem(i).isEmpty
            state.filled[i] = filled
            if (filled) any = true
        }
        state.anyFilled = any

        // Section-dirty trigger on reachability flip — keeps the network-color emissive
        // tint in sync with LOS changes that don't themselves go through
        // NetworkSettingsRegistry.
        val pos = blockEntity.blockPos
        val reachable = NodeConnectionRenderer.isReachable(pos)
        val prev = lastReachable.put(pos, reachable)
        if (prev != null && prev != reachable) {
            val mc = Minecraft.getInstance()
            mc.levelRenderer.setSectionDirtyWithNeighbors(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.y),
                SectionPos.blockToSectionCoord(pos.z)
            )
        }
    }

    override fun submit(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val facing = state.facing ?: return
        if (!state.anyFilled) return

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        rotateToFace(poseStack, facing)

        // 26.1 renamed entityCutoutNoCull → entityCutout (no-cull is the default;
        //  entityCutoutCull is the new cull variant).
        val renderType = RenderTypes.entityCutout(CARD_TEXTURE)
        val z = 0.5f + Z_OFFSET

        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            for (i in 0 until ProcessingStorageBlockEntity.TOTAL_SLOTS) {
                if (!state.filled[i]) continue
                val (pxX, pxY) = SLOT_POSITIONS[i]

                val x1 = pxX / 16f - 0.5f
                val x2 = (pxX + CARD_W) / 16f - 0.5f
                val yBot = (16 - (pxY + CARD_H)) / 16f - 0.5f
                val yTop = (16 - pxY) / 16f - 0.5f

                vc.addVertex(pose, x1, yBot, z).setColor(255, 255, 255, 255).setUv(0f, 1f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT).setNormal(pose, 0f, 0f, 1f)
                vc.addVertex(pose, x2, yBot, z).setColor(255, 255, 255, 255).setUv(1f, 1f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT).setNormal(pose, 0f, 0f, 1f)
                vc.addVertex(pose, x2, yTop, z).setColor(255, 255, 255, 255).setUv(1f, 0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT).setNormal(pose, 0f, 0f, 1f)
                vc.addVertex(pose, x1, yTop, z).setColor(255, 255, 255, 255).setUv(0f, 0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULLBRIGHT).setNormal(pose, 0f, 0f, 1f)
            }
        }

        poseStack.popPose()
    }

    private fun rotateToFace(poseStack: PoseStack, face: Direction) {
        when (face) {
            Direction.SOUTH -> {}
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST  -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST  -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            else -> {}
        }
    }
}
