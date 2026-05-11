package damien.nodeworks.recipe

import com.mojang.serialization.MapCodec
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.registry.ModDataComponents
import damien.nodeworks.registry.ModRecipeSerializers
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Custom shaped crafting recipe for the Covered Vacuum Pipe.
 *
 * Layout (3×3):
 * ```
 *   P P P
 *   P C P
 *   P P P
 * ```
 * where **P** is a Vacuum Pipe and **C** is any *full-block* item. The
 * camouflage block's [BlockState] is captured from the center item and
 * stamped onto the output via [ModDataComponents.CAMO_BLOCK_STATE]; the
 * recipe yields **8** Covered Pipes (one for each pipe consumed).
 *
 * The full-block check uses [Block.isShapeFullBlock] against the
 * candidate state's default collision shape. That excludes slabs,
 * stairs, fences, plants, fluids, etc. and accepts plain solid cubes
 * (stone, sandstone, planks, ores, glass, ...). Modded blocks pass as
 * long as their default state's collision shape is a full unit cube.
 *
 * No JSON fields - the recipe shape is fixed in code. The data-pack
 * file at `data/nodeworks/recipe/covered_pipe.json` just references the
 * serializer.
 */
class CoveredPipeRecipe(
    private val craftingCategory: CraftingBookCategory = CraftingBookCategory.MISC,
) : CustomRecipe() {

    override fun matches(input: CraftingInput, level: Level): Boolean {
        if (input.width() != 3 || input.height() != 3) return false
        for (y in 0..2) {
            for (x in 0..2) {
                val stack = input.getItem(x + y * 3)
                val isCenter = x == 1 && y == 1
                if (isCenter) {
                    if (!isValidCamoStack(stack)) return false
                } else {
                    if (stack.isEmpty || stack.item != ModBlocks.PIPE.asItem()) return false
                }
            }
        }
        return true
    }

    override fun assemble(input: CraftingInput): ItemStack {
        val centerStack = input.getItem(4)
        val blockItem = centerStack.item as? BlockItem ?: return ItemStack.EMPTY
        val camoState = blockItem.block.defaultBlockState()
        val result = ItemStack(ModBlocks.COVERED_PIPE.asItem(), 8)
        result.set(ModDataComponents.CAMO_BLOCK_STATE, camoState)
        return result
    }

    override fun category(): CraftingBookCategory = craftingCategory

    override fun getSerializer(): RecipeSerializer<out CustomRecipe> =
        ModRecipeSerializers.COVERED_PIPE_CRAFTING

    companion object {
        /** Public so the JEI integration / GuideME can reuse the predicate
         *  when describing which items are valid in the centre slot. */
        fun isValidCamoStack(stack: ItemStack): Boolean {
            if (stack.isEmpty) return false
            val blockItem = stack.item as? BlockItem ?: return false
            val state: BlockState = blockItem.block.defaultBlockState()
            if (state.isAir) return false
            // Full-cube test: the default-state collision shape must equal
            // the unit cube. Uses EmptyBlockGetter / BlockPos.ZERO since a
            // recipe doesn't have world context - blocks with state-driven
            // shape variations (slabs with type=double, etc.) won't pass
            // their full-shape variant through the default state anyway,
            // which we accept as the correct behavior.
            val shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            return Block.isShapeFullBlock(shape)
        }

        /** Map codec - no fields to serialize, the shape is hard-coded.
         *  `category` is read from the JSON so the recipe lands in the
         *  right tab of the recipe book; `unit(...)` would lose that. */
        val CODEC: MapCodec<CoveredPipeRecipe> = CraftingBookCategory.CODEC
            .fieldOf("category")
            .xmap(::CoveredPipeRecipe, CoveredPipeRecipe::craftingCategory)

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CoveredPipeRecipe> =
            StreamCodec.composite(
                CraftingBookCategory.STREAM_CODEC, CoveredPipeRecipe::craftingCategory,
                ::CoveredPipeRecipe,
            )
    }
}
