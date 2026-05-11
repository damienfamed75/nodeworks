package damien.nodeworks.client.model

import damien.nodeworks.block.entity.CoveredPipeBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.model.DynamicBlockStateModel

/**
 * Chunk-render-time model for the Covered Vacuum Pipe. Implements
 * NeoForge's [DynamicBlockStateModel] so chunk meshing handles all the
 * heavy lifting (per-vertex AO, smooth lighting from corner neighbours,
 * proper face-direction lighting) by routing our face quads through the
 * standard `BlockModelLighter`. We just hand it the right quads.
 *
 * Per call to [collectParts]:
 *  1. **Camo body** - look up the wrapped block's [BlockStateModel] via
 *     the model manager and delegate to its `collectParts(level, pos, ...)`.
 *     That walks neighbour-aware models (mossy variants, fences, etc.)
 *     identically to how the chunk mesher would render them at this pos.
 *  2. **Indicator overlay** - append the [indicatorPart] (loaded at bake
 *     time, see [CoveredPipeBakedModel.bake]). The indicator's quads
 *     declare `render_type: cutout` in their JSON so chunk meshing routes
 *     them through the cutout sheet on top of the camo's solid quads -
 *     transparent indicator-texture pixels punch through to the camo
 *     beneath cleanly.
 *
 * Camo state is read from the [CoveredPipeBlockEntity] at the queried
 * position. Falls back to the `defaultCamo` (the indicator-only model)
 * when there's no BE - happens during the very first frame after world
 * load, on broken placement attempts (creative `/setblock`), and during
 * the breaking-particle pass.
 */
class CoveredPipeBakedModel(
    /** Indicator overlay model loaded by the standalone-model registry.
     *  Its parts are appended to every [collectParts] output. */
    private val indicatorPart: BlockStateModelPart,
    /** Fallback for [collectParts] calls without level/pos context
     *  (particle pass, etc.). The vanilla baseline `covered_pipe` model -
     *  also indicator-only - keeps the block visible. */
    private val defaultCamo: BlockStateModel,
) : DynamicBlockStateModel {

    override fun collectParts(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
        output: MutableList<BlockStateModelPart>,
    ) {
        val be = level.getBlockEntity(pos) as? CoveredPipeBlockEntity
        val camoState = be?.camoBlockState
        if (camoState != null) {
            val camoModel = Minecraft.getInstance().modelManager.blockStateModelSet.get(camoState)
            camoModel.collectParts(level, pos, camoState, random, output)
        } else {
            defaultCamo.collectParts(random, output)
        }
        output.add(indicatorPart)
    }

    /** Context-free overload used by break particles and item-frame
     *  rendering. We can't read the BE here so just emit the baseline +
     *  indicator. */
    override fun collectParts(random: RandomSource, output: MutableList<BlockStateModelPart>) {
        defaultCamo.collectParts(random, output)
        output.add(indicatorPart)
    }

    override fun particleMaterial(): Material.Baked = defaultCamo.particleMaterial()

    override fun materialFlags(): Int = defaultCamo.materialFlags()

    /** Context-aware particle material so break particles match the
     *  visible camo rather than the indicator. */
    override fun particleMaterial(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
    ): Material.Baked {
        val be = level.getBlockEntity(pos) as? CoveredPipeBlockEntity
        val camoState = be?.camoBlockState ?: return defaultCamo.particleMaterial()
        val camoModel = Minecraft.getInstance().modelManager.blockStateModelSet.get(camoState)
        return camoModel.particleMaterial(level, pos, camoState)
    }
}
