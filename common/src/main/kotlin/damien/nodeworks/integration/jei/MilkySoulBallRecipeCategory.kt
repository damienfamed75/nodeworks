package damien.nodeworks.integration.jei

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * TODO MC 26.1.2 / JEI 29.5 MIGRATION — rewrite against the new IRecipeCategory
 * contract.
 *
 * The draw(...) method now takes a `GuiGraphicsExtractor` (`net.minecraft.client
 * .gui.GuiGraphicsExtractor`) instead of `GuiGraphics`, and `getBackground()`
 * has been replaced by the category contributing to `draw(...)` directly (JEI
 * no longer takes a pre-rendered IDrawable for the background in 29.x — check
 * the new IRecipeCategory interface).
 *
 * Pre-migration body visible in git history. Rebuilds the "Soul Sand Infusion"
 * category: milk bucket + soul sand → 4× Milky Soul Ball, with an animated
 * filling-bucket arrow sprite from textures/gui/jei_bucket.png (top half =
 * filled, bottom half = empty, 60-tick fill animation).
 */
class MilkySoulBallRecipeCategory(guiHelper: IGuiHelper) : IRecipeCategory<MilkySoulBallRecipe> {

    companion object {
        val RECIPE_TYPE: RecipeType<MilkySoulBallRecipe> =
            RecipeType.create("nodeworks", "soul_sand_infusion", MilkySoulBallRecipe::class.java)

        private val BUCKET_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/jei_bucket.png")
    }

    override fun getRecipeType(): RecipeType<MilkySoulBallRecipe> = RECIPE_TYPE

    override fun getTitle(): Component = Component.translatable("jei.nodeworks.category.soul_sand_infusion")

    override fun getWidth(): Int = 120

    override fun getHeight(): Int = 42

    override fun getIcon(): IDrawable? = null

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: MilkySoulBallRecipe, focuses: IFocusGroup) {
        // TODO: restore slot layout once the draw() hook is ported.
    }
}
