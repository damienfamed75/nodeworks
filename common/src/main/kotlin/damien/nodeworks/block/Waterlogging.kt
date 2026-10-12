package damien.nodeworks.block

import net.minecraft.core.BlockPos
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/** Carried by every Nodeworks block whose model doesn't fill its cube. */
val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED

fun BlockPlaceContext.placedInWater(): Boolean =
    level.getFluidState(clickedPos).type == Fluids.WATER

/** For multiblock parts, which are auto-placed with no placement context. */
fun isWaterAt(level: BlockGetter, pos: BlockPos): Boolean =
    level.getFluidState(pos).type == Fluids.WATER

/** Null when dry so callers fall back to `super`. */
fun waterloggedFluid(state: BlockState): FluidState? =
    if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else null
