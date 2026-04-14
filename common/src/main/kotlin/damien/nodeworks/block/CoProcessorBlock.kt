package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CoProcessorBlockEntity
import damien.nodeworks.block.entity.CpuComponentBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Co-Processor — place adjacent to a Crafting Core (or any CPU component) to add
 * one parallel craft thread. Notification walks the whole component chain so cores
 * several blocks away still recalculate.
 */
class CoProcessorBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<CoProcessorBlock> = simpleCodec(::CoProcessorBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return CoProcessorBlockEntity(pos, state)
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighborBlock: Block, neighborPos: BlockPos, movedByPiston: Boolean) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston)
        level.getBlockEntity(pos) as? CoProcessorBlockEntity ?: return
        CpuComponentBlockEntity.findConnectedCores(level, pos).forEach { it.recalculateCapacity() }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide) {
            CpuComponentBlockEntity.findConnectedCores(level, pos).forEach { it.recalculateCapacity() }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
