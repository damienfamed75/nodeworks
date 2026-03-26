package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class NeoForgeBlockEntityService : BlockEntityService {
    override fun <T : BlockEntity> createBlockEntityType(
        factory: (BlockPos, BlockState) -> T,
        vararg blocks: Block
    ): BlockEntityType<T> {
        return BlockEntityType({ pos, state -> factory(pos, state) }, blocks.toSet())
    }
}
