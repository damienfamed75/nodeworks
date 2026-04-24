package damien.nodeworks.guide

import damien.nodeworks.recipe.SoulSandInfusionRecipe
import damien.nodeworks.registry.ModRecipeTypes
import guideme.compiler.tags.RecipeTypeMappingSupplier
import guideme.document.LytSize
import guideme.document.block.AlignItems
import guideme.document.block.LytGuiSprite
import guideme.document.block.LytHBox
import guideme.document.block.LytSlotGrid
import guideme.document.block.recipes.LytStandardRecipeBox
import guideme.render.GuiAssets
import net.minecraft.resources.Identifier
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.block.Blocks

/**
 * Registers a GuideME renderer for [ModRecipeTypes.SOUL_SAND_INFUSION] so
 * pages with `<RecipeFor id="..." />` (or any tag that resolves by result
 * item) produce a recipe box showing [held + soul sand → result].
 *
 * GuideME's [RecipeTypeMappingSupplier] is an [guideme.extensions.Extension]
 * wired into the mod's [NodeworksGuide] builder via `.extension(
 * RecipeTypeMappingSupplier.EXTENSION_POINT, ...)`. When GuideME compiles a
 * page and encounters a recipe reference, it dispatches through every
 * registered supplier's [collect] method; the first one whose [RecipeType]
 * matches is the one that gets its factory called.
 *
 * Layout choices:
 *   * Icon: the soul sand block — thematically identifies the recipe.
 *   * Title: the literal string "Soul Sand Infusion" (matches the JEI
 *     category title for recognizability across the two tools).
 *   * Body: hand-built via [LytStandardRecipeBox.Builder.customBody] so we
 *     can swap the default [GuiAssets.ARROW] for our milk-bucket sprite,
 *     matching the JEI category's bucket-arrow visual. The builder's
 *     standard input/output API hardcodes [GuiAssets.ARROW] with no
 *     override hook, so `customBody` is the only escape hatch for swapping
 *     the separator.
 */
class SoulSandInfusionRecipeContribution : RecipeTypeMappingSupplier {

    override fun collect(mappings: RecipeTypeMappingSupplier.RecipeTypeMappings) {
        mappings.add(ModRecipeTypes.SOUL_SAND_INFUSION) { holder ->
            val recipe: SoulSandInfusionRecipe = holder.value()

            val inputs = LytSlotGrid.rowFromIngredients(
                listOf(recipe.heldIngredient, Ingredient.of(Blocks.SOUL_SAND)),
                false,
            )
            val output = LytSlotGrid(1, 1).apply {
                setItem(0, 0, recipe.result)
            }

            // Mirrors LytStandardRecipeBox's internal grid row: HBox gap=2,
            // center-aligned, no wrap — but with the bucket sprite standing
            // in for the default arrow. 24×17 matches the vanilla arrow
            // footprint so the recipe box sizes the same as any other.
            val body = LytHBox().apply {
                setGap(2)
                alignItems = AlignItems.CENTER
                setWrap(false)
                append(inputs)
                append(
                    LytGuiSprite(
                        GuiAssets.sprite(
                            Identifier.fromNamespaceAndPath("nodeworks", "soul_sand_infusion_arrow"),
                        ),
                        LytSize(24, 17),
                    ),
                )
                append(output)
            }

            LytStandardRecipeBox.builder()
                .icon(Blocks.SOUL_SAND)
                .title("Soul Sand Infusion")
                .customBody(body)
                .build(holder)
        }
    }
}
