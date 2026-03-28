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
        val supplier = object : BlockEntityType.BlockEntitySupplier<T> {
            override fun create(pos: BlockPos, state: BlockState): T = factory(pos, state)
        }
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        return BlockEntityType.Builder.of(supplier, *blocks).build(null)
    }
}
