package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CpuComponentBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.block.entity.SubstrateBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class SubstrateBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<SubstrateBlock> = simpleCodec(::SubstrateBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = SubstrateBlockEntity(pos, state)

    override fun affectNeighborsAfterRemoval(state: BlockState, level: net.minecraft.server.level.ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
        CpuComponentBlockEntity.requestRescanOnRemove(level, pos)
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        CpuComponentBlockEntity.requestRescanOnPlace(level, pos, state, oldState)
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighborBlock: Block, orientation: net.minecraft.world.level.redstone.Orientation?, movedByPiston: Boolean) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston)
        if (CraftingCoreBlockEntity.pushingStates) return
        level.getBlockEntity(pos) as? SubstrateBlockEntity ?: return
        CpuComponentBlockEntity.findConnectedCores(level, pos).forEach { it.requestRecalculate() }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide) {
            CpuComponentBlockEntity.findConnectedCores(level, pos).forEach { it.requestRecalculate() }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
