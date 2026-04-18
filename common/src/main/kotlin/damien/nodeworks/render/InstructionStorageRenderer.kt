package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.InstructionStorageBlock
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
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
 * Renders a small card icon over each filled slot on the Instruction Storage's
 * front face. The card PNG is 4×1 and maps onto the 12 slot cutouts painted into
 * `instruction_storage_front.png` at the positions in [SLOT_POSITIONS].
 */
class InstructionStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<InstructionStorageBlockEntity, InstructionStorageRenderer.RenderState> {

    class RenderState : BlockEntityRenderState() {
        val filled: BooleanArray = BooleanArray(InstructionStorageBlockEntity.TOTAL_SLOTS)
        var facing: Direction? = null
        var anyFilled: Boolean = false
    }

    /** See ProcessingStorageRenderer.lastReachable for rationale. */
    private val lastReachable = HashMap<BlockPos, Boolean>()

    companion object {
        private val CARD_TEXTURE = Identifier.fromNamespaceAndPath(
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

    override fun createRenderState(): RenderState = RenderState()

    override fun extractRenderState(
        blockEntity: InstructionStorageBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)

        val blockState = blockEntity.blockState
        state.facing = if (blockState.hasProperty(InstructionStorageBlock.FACING))
            blockState.getValue(InstructionStorageBlock.FACING) else null

        var any = false
        for (i in 0 until InstructionStorageBlockEntity.TOTAL_SLOTS) {
            val filled = !blockEntity.getItem(i).isEmpty
            state.filled[i] = filled
            if (filled) any = true
        }
        state.anyFilled = any

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

        // 26.1: entityCutoutNoCull → entityCutout (no-cull is the default now).
        val renderType = RenderTypes.entityCutout(CARD_TEXTURE)
        val z = 0.5f + Z_OFFSET

        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            for (i in 0 until InstructionStorageBlockEntity.TOTAL_SLOTS) {
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
