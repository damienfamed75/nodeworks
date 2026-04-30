package damien.nodeworks.worldgen

import com.mojang.serialization.Codec
import damien.nodeworks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

/**
 * Drops a 2-4 block vein of [ModBlocks.CELESTINE_ORE] into deepslate-replaceable
 * stone, then decorates each placed ore's exposed (air-adjacent) faces with a
 * random celestine bud / cluster block.
 *
 * Acts as an early-game on-ramp for celestine: players naturally encounter the
 * ore while strip-mining without having to locate a geode. The decorative buds
 * are static (no random-tick growth, unlike geode budding_celestine) so the ore
 * never converts into a regrowable supply, only the geode does that.
 *
 * Drives the `nodeworks:celestine_ore` configured-feature JSON, which the
 * `add_celestine_ore` biome modifier injects into every overworld biome's
 * `underground_ores` step.
 */
class CelestineOreFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val origin = context.origin()

        // 2..4 blocks placed via short random walk. Walk steps stay axis-aligned so
        // veins look like clusters rather than diagonal threads. The replaceable-tag
        // check lets natural deepslate / tuff host the vein but skips air pockets,
        // so a vein origin in a cave just generates fewer blocks instead of
        // hanging blocks in midair.
        val veinSize = 2 + random.nextInt(3)
        val placed = mutableListOf<BlockPos>()
        var cursor: BlockPos = origin
        for (i in 0 until veinSize) {
            val state = level.getBlockState(cursor)
            if (state.`is`(BlockTags.DEEPSLATE_ORE_REPLACEABLES) ||
                state.`is`(BlockTags.STONE_ORE_REPLACEABLES)
            ) {
                level.setBlock(cursor, ModBlocks.CELESTINE_ORE.defaultBlockState(), Block.UPDATE_CLIENTS)
                placed.add(cursor)
            }
            cursor = cursor.relative(Direction.entries[random.nextInt(Direction.entries.size)])
        }

        if (placed.isEmpty()) return false

        // Bud-decorate exposed faces. 75% per face leans the look toward
        // visibly bud-encrusted: most cave-exposed veins read as crystal-coated
        // boulders rather than plain ore with the occasional sparkle. The bud
        // kind is uniformly chosen across the four sizes so a player sees the
        // full family naturally over time.
        val budOptions = arrayOf(
            ModBlocks.SMALL_CELESTINE_BUD,
            ModBlocks.MEDIUM_CELESTINE_BUD,
            ModBlocks.LARGE_CELESTINE_BUD,
            ModBlocks.CELESTINE_CLUSTER,
        )
        for (orePos in placed) {
            for (face in Direction.entries) {
                if (random.nextFloat() > 0.75f) continue
                val budPos = orePos.relative(face)
                val existing = level.getBlockState(budPos)
                // Allow placement into air OR pure water. A flooded cavern
                // breaking against the vein produces waterlogged buds rather
                // than dry crystal jutting through a water column. Anything
                // else (other ore, lava, plants, etc.) is skipped.
                val isWater = existing.fluidState.`is`(FluidTags.WATER)
                if (!existing.isAir && !isWater) continue
                val bud = budOptions[random.nextInt(budOptions.size)]
                val budState = bud.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, face)
                    .setValue(BlockStateProperties.WATERLOGGED, isWater)
                level.setBlock(budPos, budState, Block.UPDATE_CLIENTS)
            }
        }
        return true
    }
}
