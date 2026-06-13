package damien.nodeworks.client.model

import damien.nodeworks.block.entity.StorageRepoBlockEntity
import damien.nodeworks.block.repo.RepoClusterRole
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.model.DynamicBlockStateModel

/**
 * Chunk-render-time model for Storage Repo blocks. Reads the BE's [RepoClusterRole]
 * at mesh time and delegates to one of four pre-baked variants ([standaloneModel],
 * [topModel], [bottomModel], [middleModel]). Falls back to [standaloneModel] when
 * the BE is missing (item display, break particles).
 *
 * The role check itself is cheap — [StorageRepoBlockEntity.getClusterRole] hits the
 * epoch-cached cluster info; the BFS only runs on the first read per epoch. The
 * cluster cache propagates across siblings (see [StorageRepoBlockEntity.ensureClusterComputed])
 * so a 27-block silo's first frame walks the cluster once, not 27 times.
 */
class StorageRepoBakedModel(
    private val standaloneModel: BlockStateModel,
    private val topPart: BlockStateModelPart,
    private val bottomPart: BlockStateModelPart,
    private val middlePart: BlockStateModelPart,
) : DynamicBlockStateModel {

    override fun collectParts(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
        output: MutableList<BlockStateModelPart>,
    ) {
        val be = level.getBlockEntity(pos) as? StorageRepoBlockEntity
        if (be == null) {
            standaloneModel.collectParts(level, pos, state, random, output)
            return
        }
        when (be.getClusterRole()) {
            RepoClusterRole.STANDALONE -> standaloneModel.collectParts(level, pos, state, random, output)
            RepoClusterRole.CAP_TOP -> output.add(topPart)
            RepoClusterRole.CAP_BOTTOM -> output.add(bottomPart)
            RepoClusterRole.MIDDLE -> output.add(middlePart)
        }
    }

    /** Context-free overload used for break particles, item-frame rendering, etc.
     *  Without level/pos we can't read cluster role, so emit the standalone look. */
    override fun collectParts(random: RandomSource, output: MutableList<BlockStateModelPart>) {
        standaloneModel.collectParts(random, output)
    }

    override fun particleMaterial(): Material.Baked = standaloneModel.particleMaterial()

    override fun materialFlags(): Int = standaloneModel.materialFlags()
}
