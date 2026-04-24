package damien.nodeworks.integration.jei

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient

/**
 * One entry in the Soul Sand Infusion JEI category.
 *
 * Sourced from [damien.nodeworks.recipe.SoulSandInfusionRecipe]s in the server's
 * RecipeManager — see [NodeworksJeiPlugin.registerRecipes]. Carries the held
 * [Ingredient] (so tag-based recipes expand to every matching item in JEI) and
 * the dropped [result] ItemStack. The target block is always soul sand for this
 * recipe type, so it's hardcoded in [MilkySoulBallRecipeCategory] rather than
 * stored per-recipe.
 */
data class MilkySoulBallRecipe(
    val held: Ingredient,
    val result: ItemStack,
)
