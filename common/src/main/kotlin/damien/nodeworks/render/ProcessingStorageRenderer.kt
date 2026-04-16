package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.ProcessingStorageBlock
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf

/**
 * Renders a small card icon over each filled card slot on the Processing Storage's
 * front face. The [CARD_TEXTURE] is a 4×2 image that maps onto the 8 slot cutouts
 * painted into `processing_storage_front.png` at the positions in [SLOT_POSITIONS].
 */
class ProcessingStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ProcessingStorageBlockEntity> {

    companion object {
        private val CARD_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "nodeworks", "textures/block/processing_storage_card.png"
        )

        /** Card PNG dimensions. */
        private const val CARD_W = 4
        private const val CARD_H = 2

        /**
         * Top-left texture-pixel coord (in a 16×16 front face) for each of the 8 slots.
         * Layout matches [ProcessingStorageScreenHandler] — row-major, 2 cols × 4 rows.
         * Edit these if the front texture is repainted.
         */
        private val SLOT_POSITIONS = arrayOf(
            3 to 1,  9 to 1,    // row 0
            3 to 4,  9 to 4,    // row 1
            3 to 7,  9 to 7,    // row 2
            3 to 10, 9 to 10    // row 3
        )

        private const val Z_OFFSET = 0.001f  // push card slightly in front of the face to avoid z-fight
        private const val FULLBRIGHT = 15728880  // sky=15, block=15 packed — keep cards readable in dim rooms
    }

    override fun render(
        entity: ProcessingStorageBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val state = entity.blockState
        if (!state.hasProperty(ProcessingStorageBlock.FACING)) return
        val facing = state.getValue(ProcessingStorageBlock.FACING)

        // Any filled slot? Early-out to keep the common-path cheap.
        var anyFilled = false
        for (i in 0 until ProcessingStorageBlockEntity.TOTAL_SLOTS) {
            if (!entity.getItem(i).isEmpty) { anyFilled = true; break }
        }
        if (!anyFilled) return

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        rotateToFace(poseStack, facing)
        // After rotation: +Z now points out of the block's front face. Face surface is at z = 0.5.

        val consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CARD_TEXTURE))
        val pose = poseStack.last()
        val z = 0.5f + Z_OFFSET

        for (i in 0 until ProcessingStorageBlockEntity.TOTAL_SLOTS) {
            if (entity.getItem(i).isEmpty) continue
            val (pxX, pxY) = SLOT_POSITIONS[i]

            // Pixel box (pxX, pxY) — (pxX+CARD_W, pxY+CARD_H) on a 16×16 face.
            // Model y flips (texture v=0 at top, model y=0 at bottom, face centered at 0).
            val x1 = pxX / 16f - 0.5f
            val x2 = (pxX + CARD_W) / 16f - 0.5f
            val yBot = (16 - (pxY + CARD_H)) / 16f - 0.5f
            val yTop = (16 - pxY) / 16f - 0.5f

            // CCW winding when viewed from +Z so the card faces outward with normal (0,0,1).
            // UV: (0,0) = top-left of the card PNG, (1,1) = bottom-right.
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

    /** Rotate the pose so +Z points out of [face]. Matches [MonitorRenderer.rotateToFace] convention. */
    private fun rotateToFace(poseStack: PoseStack, face: Direction) {
        when (face) {
            Direction.SOUTH -> {}
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST  -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST  -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            else -> {} // horizontal-only facing, other directions shouldn't happen
        }
    }
}
