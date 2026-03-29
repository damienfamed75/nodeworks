package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.TerminalBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos

/**
 * Renderer for Terminal blocks.
 * Detects connection changes and triggers chunk section rebuild for tint color updates.
 */
class TerminalRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<TerminalBlockEntity> {

    private val lastState = HashMap<BlockPos, Int>()

    override fun render(
        entity: TerminalBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        // Hash connection count + reachability to detect any change
        val reachable = NodeConnectionRenderer.isReachable(entity.blockPos)
        val state = entity.getConnections().size or (if (reachable) 0x10000 else 0)
        val last = lastState.put(entity.blockPos, state)
        if (last != null && last != state) {
            val sx = SectionPos.blockToSectionCoord(entity.blockPos.x)
            val sy = SectionPos.blockToSectionCoord(entity.blockPos.y)
            val sz = SectionPos.blockToSectionCoord(entity.blockPos.z)
            Minecraft.getInstance().levelRenderer.setSectionDirtyWithNeighbors(sx, sy, sz)
        }
    }
}
