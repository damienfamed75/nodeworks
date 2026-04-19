package damien.nodeworks.integration.jei

import damien.nodeworks.registry.ModItems
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.drawable.IDrawableAnimated
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.recipe.types.IRecipeType
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * JEI category for the in-world "Soul Sand Infusion" interaction: right-clicking
 * a milk bucket on soul sand produces 4 Milky Soul Balls.
 *
 * 26.1 / JEI 29.5 notes:
 *   - `IRecipeCategory.getRecipeType()` now returns [IRecipeType]; `RecipeType`
 *     still implements it so nothing downstream changes.
 *   - `IRecipeCategory.getBackground()` was removed — JEI lays out the category
 *     using getWidth/getHeight and provides the border itself. The blank
 *     background drawable from pre-migration is dropped.
 *   - `IRecipeCategory.draw(...)` takes [GuiGraphicsExtractor] instead of
 *     `GuiGraphics`; same for [IDrawable.draw] (see the bucket-fill drawables
 *     below).
 */
class MilkySoulBallRecipeCategory(guiHelper: IGuiHelper) : IRecipeCategory<MilkySoulBallRecipe> {

    companion object {
        val RECIPE_TYPE: RecipeType<MilkySoulBallRecipe> =
            RecipeType.create("nodeworks", "soul_sand_infusion", MilkySoulBallRecipe::class.java)

        private const val W = 120
        private const val H = 42

        private val BUCKET_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/jei_bucket.png")
    }

    private val icon: IDrawable = guiHelper.createDrawableItemStack(ItemStack(ModItems.MILKY_SOUL_BALL))
    // Empty bucket arrow — bottom half of the 24x34 atlas.
    private val arrowBg: IDrawable = guiHelper.drawableBuilder(BUCKET_TEXTURE, 0, 17, 24, 17)
        .setTextureSize(24, 34)
        .build()
    // Filled bucket arrow — top half; animated to fill left-to-right over 60 ticks.
    private val arrowFill: IDrawableAnimated = guiHelper.drawableBuilder(BUCKET_TEXTURE, 0, 0, 24, 17)
        .setTextureSize(24, 34)
        .buildAnimated(60, IDrawableAnimated.StartDirection.LEFT, false)

    override fun getRecipeType(): IRecipeType<MilkySoulBallRecipe> = RECIPE_TYPE

    override fun getTitle(): Component = Component.translatable("jei.nodeworks.category.soul_sand_infusion")

    override fun getIcon(): IDrawable = icon

    override fun getWidth(): Int = W

    override fun getHeight(): Int = H

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: MilkySoulBallRecipe, focuses: IFocusGroup) {
        // Milk bucket — top-left input
        builder.addSlot(RecipeIngredientRole.INPUT, 1, 1).addItemStack(recipe.milk)
        // Soul sand — bottom-left input
        builder.addSlot(RecipeIngredientRole.INPUT, 1, 21).addItemStack(recipe.soulSand)
        // 4× Milky Soul Ball — right output
        builder.addSlot(RecipeIngredientRole.OUTPUT, 99, 12).addItemStack(recipe.result)
    }

    override fun draw(
        recipe: MilkySoulBallRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphicsExtractor,
        mouseX: Double,
        mouseY: Double
    ) {
        // Empty bucket arrow, then the filled overlay animated left-to-right.
        arrowBg.draw(graphics, 46, 12)
        arrowFill.draw(graphics, 46, 12)
    }
}
