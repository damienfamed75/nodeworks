package damien.nodeworks.block

import damien.nodeworks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.AmethystBlock
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids

/**
 * Budding Celestine — grows Celestine buds on its faces, identical to BuddingAmethystBlock behavior.
 */
class BuddingCelestineBlock(properties: Properties) : AmethystBlock(properties) {

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(5) != 0) return

        val direction = DIRECTIONS[random.nextInt(DIRECTIONS.size)]
        val targetPos = pos.relative(direction)
        val targetState = level.getBlockState(targetPos)
        val newBlock: Block?

        if (canClusterGrowAtState(targetState)) {
            newBlock = ModBlocks.SMALL_CELESTINE_BUD
        } else if (targetState.`is`(ModBlocks.SMALL_CELESTINE_BUD) && targetState.getValue(AmethystClusterBlock.FACING) == direction) {
            newBlock = ModBlocks.MEDIUM_CELESTINE_BUD
        } else if (targetState.`is`(ModBlocks.MEDIUM_CELESTINE_BUD) && targetState.getValue(AmethystClusterBlock.FACING) == direction) {
            newBlock = ModBlocks.LARGE_CELESTINE_BUD
        } else if (targetState.`is`(ModBlocks.LARGE_CELESTINE_BUD) && targetState.getValue(AmethystClusterBlock.FACING) == direction) {
            newBlock = ModBlocks.CELESTINE_CLUSTER
        } else {
            newBlock = null
        }

        if (newBlock != null) {
            val newState = newBlock.defaultBlockState()
                .setValue(AmethystClusterBlock.FACING, direction)
                .setValue(AmethystClusterBlock.WATERLOGGED, targetState.fluidState.type == Fluids.WATER)
            level.setBlockAndUpdate(targetPos, newState)
        }
    }

    companion object {
        private val DIRECTIONS = Direction.entries.toTypedArray()

        fun canClusterGrowAtState(state: BlockState): Boolean {
            return state.isAir || (state.`is`(Blocks.WATER) && state.fluidState.amount == 8)
        }
    }
}
