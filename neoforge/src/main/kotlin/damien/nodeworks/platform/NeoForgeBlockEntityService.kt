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
        // 26.1: the old `BlockEntityType.Builder.of(factory, *blocks).build(null)`
        //  fluent form is gone, `Builder` isn't public anymore. The public
        //  2-arg ctor takes the factory directly + the Block[] of valid blocks.
        val supplier = object : BlockEntityType.BlockEntitySupplier<T> {
            override fun create(pos: BlockPos, state: BlockState): T = factory(pos, state)
        }
        return BlockEntityType(supplier, *blocks)
    }
}
