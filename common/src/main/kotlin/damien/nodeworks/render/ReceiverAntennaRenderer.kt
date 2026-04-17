package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos

/**
 * Triggers chunk section rebuild when the Receiver Antenna's connection state changes,
 * so the block color provider re-evaluates the emissive tint. Same pattern as
 * [TerminalRenderer] / [VariableRenderer].
 */
class ReceiverAntennaRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ReceiverAntennaBlockEntity> {

    private val lastState = HashMap<BlockPos, Int>()

    override fun render(
        entity: ReceiverAntennaBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
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
