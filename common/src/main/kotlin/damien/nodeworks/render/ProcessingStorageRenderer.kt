package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.ProcessingStorageBlock
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.resources.Identifier
import org.joml.Quaternionf

/**
 * Renders a small card icon over each filled card slot on the Processing Storage's
 * front face. The [CARD_TEXTURE] is a 4×2 image that maps onto the 8 slot cutouts
 * painted into `processing_storage_front.png` at the positions in [SLOT_POSITIONS].
 */
class ProcessingStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ProcessingStorageBlockEntity> {

    /** Tracks connection state per block position so we can trigger a chunk rebuild
     *  when the block connects/disconnects — same pattern as TerminalRenderer. */
    private val lastState = HashMap<BlockPos, Int>()

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

    override fun render(
        entity: ProcessingStorageBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        // Detect connection state changes → trigger chunk rebuild so the block color
        // provider re-evaluates the emissive tint (same pattern as TerminalRenderer).
        val reachable = NodeConnectionRenderer.isReachable(entity.blockPos)
        val connState = entity.getContainerSize() or (if (reachable) 0x10000 else 0)
        val prev = lastState.put(entity.blockPos, connState)
        if (prev != null && prev != connState) {
            val sx = SectionPos.blockToSectionCoord(entity.blockPos.x)
            val sy = SectionPos.blockToSectionCoord(entity.blockPos.y)
            val sz = SectionPos.blockToSectionCoord(entity.blockPos.z)
            Minecraft.getInstance().levelRenderer.setSectionDirtyWithNeighbors(sx, sy, sz)
        }

        val state = entity.blockState
        if (!state.hasProperty(ProcessingStorageBlock.FACING)) return
        val facing = state.getValue(ProcessingStorageBlock.FACING)

        var anyFilled = false
        for (i in 0 until ProcessingStorageBlockEntity.TOTAL_SLOTS) {
            if (!entity.getItem(i).isEmpty) { anyFilled = true; break }
        }
        if (!anyFilled) return

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        rotateToFace(poseStack, facing)

        val consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CARD_TEXTURE))
        val pose = poseStack.last()
        val z = 0.5f + Z_OFFSET

        for (i in 0 until ProcessingStorageBlockEntity.TOTAL_SLOTS) {
            if (entity.getItem(i).isEmpty) continue
            val (pxX, pxY) = SLOT_POSITIONS[i]

            val x1 = pxX / 16f - 0.5f
            val x2 = (pxX + CARD_W) / 16f - 0.5f
            val yBot = (16 - (pxY + CARD_H)) / 16f - 0.5f
            val yTop = (16 - pxY) / 16f - 0.5f

            consumer.addVertex(pose.pose(), x1, yBot, z)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 1f)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULLBRIGHT)
                .setNormal(pose, 0f, 0f, 1f)
            consumer.addVertex(pose.pose(), x2, yBot, z)
                .setColor(255, 255, 255, 255)
                .setUv(1f, 1f)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULLBRIGHT)
                .setNormal(pose, 0f, 0f, 1f)
            consumer.addVertex(pose.pose(), x2, yTop, z)
                .setColor(255, 255, 255, 255)
                .setUv(1f, 0f)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULLBRIGHT)
                .setNormal(pose, 0f, 0f, 1f)
            consumer.addVertex(pose.pose(), x1, yTop, z)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 0f)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULLBRIGHT)
                .setNormal(pose, 0f, 0f, 1f)
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
