package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.InstructionStorageBlock
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf

/**
 * Renders a small card icon over each filled slot on the Instruction Storage's
 * front face. The card PNG is 4×1 and maps onto the 12 slot cutouts painted into
 * `instruction_storage_front.png` at the positions in [SLOT_POSITIONS].
 */
class InstructionStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<InstructionStorageBlockEntity> {

    private val lastState = HashMap<BlockPos, Int>()

    companion object {
        private val CARD_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "nodeworks", "textures/block/instruction_storage_card.png"
        )

        private const val CARD_W = 4
        private const val CARD_H = 1

        private val SLOT_POSITIONS = arrayOf(
            3 to 1,  9 to 1,
            3 to 3,  9 to 3,
            3 to 5,  9 to 5,
            3 to 7,  9 to 7,
            3 to 9,  9 to 9,
            3 to 11, 9 to 11
        )

        private const val Z_OFFSET = 0.001f
        private const val FULLBRIGHT = 15728880
    }

    override fun render(
        entity: InstructionStorageBlockEntity,
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
        if (!state.hasProperty(InstructionStorageBlock.FACING)) return
        val facing = state.getValue(InstructionStorageBlock.FACING)

        var anyFilled = false
        for (i in 0 until InstructionStorageBlockEntity.TOTAL_SLOTS) {
            if (!entity.getItem(i).isEmpty) { anyFilled = true; break }
        }
        if (!anyFilled) return

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        rotateToFace(poseStack, facing)

        val consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CARD_TEXTURE))
        val pose = poseStack.last()
        val z = 0.5f + Z_OFFSET

        for (i in 0 until InstructionStorageBlockEntity.TOTAL_SLOTS) {
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
