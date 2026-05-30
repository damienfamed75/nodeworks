package damien.nodeworks.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

/**
 * Plain right-click hook for the Network Wrench. Default rotates the block,
 * override for any other behaviour. Shift-click is reserved by the wrench
 * for pipe / Focus Node management and is not routed here.
 */
interface Wrenchable {

    fun onWrenched(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult = rotate(state, level, pos)

    companion object {
        /** Cycle `FACING` through [Direction.entries] or rotate
         *  `HORIZONTAL_FACING` clockwise. PASS when the block has neither. */
        fun rotate(state: BlockState, level: Level, pos: BlockPos): InteractionResult {
            val rotated = when {
                state.hasProperty(BlockStateProperties.FACING) -> {
                    val cur = state.getValue(BlockStateProperties.FACING)
                    val next = Direction.entries[(cur.ordinal + 1) % Direction.entries.size]
                    state.setValue(BlockStateProperties.FACING, next)
                }
                state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) -> {
                    val cur = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    state.setValue(BlockStateProperties.HORIZONTAL_FACING, cur.clockWise)
                }
                else -> return InteractionResult.PASS
            }
            if (level.isClientSide) return InteractionResult.SUCCESS
            level.setBlock(pos, rotated, Block.UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.5f, 1f)
            return InteractionResult.SUCCESS
        }
    }
}
