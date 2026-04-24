package damien.nodeworks.recipe

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import damien.nodeworks.registry.ModRecipeSerializers
import damien.nodeworks.registry.ModRecipeTypes
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeBookCategories
import net.minecraft.world.item.crafting.RecipeBookCategory
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.Level

/**
 * "Right-click soul sand with X in hand → drop Y" recipe. The target block is
 * always soul sand — the mechanic is thematically tied to the block, so a
 * different target would be a different [RecipeType]. Fields:
 *
 *   * [heldIngredient] — the item in the player's hand that triggers the
 *     interaction. Taking an [Ingredient] rather than a raw item lets data
 *     packs use tags and support multi-item matches (e.g. "any milk-like
 *     bucket") without code changes.
 *   * [result] — the [ItemStackTemplate] materialized into an [ItemStack] at
 *     drop time via [ItemStackTemplate.create]. Using the template codec
 *     (rather than [ItemStack.CODEC]) lets the recipe parse during the data
 *     reload before mod-item components are fully resolved — `ItemStack.CODEC`
 *     eagerly validates components and fails with "Item ... does not have
 *     components yet" for custom items mid-reload.
 *
 * The held item's consumption behavior piggybacks on
 * [ItemStack.getCraftingRemainingItem] — milk / lava / water buckets return
 * an empty bucket automatically, and any modded item that sets a crafting
 * remainder participates without this recipe needing to know about it.
 *
 * Data-driven end-to-end: [damien.nodeworks.item.SoulSandInteraction] looks
 * recipes up via the [Level.recipeAccess] / recipe manager using a
 * [SingleRecipeInput] wrapping the held stack, so JEI, GuideME, and the
 * gameplay handler all read the same entries.
 */
class SoulSandInfusionRecipe(
    val heldIngredient: Ingredient,
    val result: ItemStackTemplate,
) : Recipe<SingleRecipeInput> {

    override fun matches(input: SingleRecipeInput, level: Level): Boolean =
        heldIngredient.test(input.item())

    override fun assemble(input: SingleRecipeInput): ItemStack = result.create()

    /** Not in any recipe book — this isn't a crafting-grid recipe. */
    override fun showNotification(): Boolean = false

    override fun group(): String = ""

    override fun getSerializer(): RecipeSerializer<out Recipe<SingleRecipeInput>> =
        ModRecipeSerializers.SOUL_SAND_INFUSION

    override fun getType(): RecipeType<out Recipe<SingleRecipeInput>> =
        ModRecipeTypes.SOUL_SAND_INFUSION

    /**
     * Single-ingredient placement info derived from [heldIngredient]. We
     * can't use [PlacementInfo.NOT_PLACEABLE] — vanilla's [RecipeManager]
     * logs "can't be placed due to empty ingredients and will be ignored"
     * and drops any non-[Recipe.isSpecial] recipe whose placement info is
     * `impossibleToPlace`. That drops us out of the client-visible recipe
     * set, which kills JEI + GuideME result lookups even though gameplay
     * can still find the recipe by the held stack.
     *
     * Using the held ingredient is the same shape as vanilla's
     * [SingleItemRecipe] (stonecutter / smelting / blasting inputs) and
     * accurately describes the only thing we require "placed" in the
     * input slot — the held item.
     */
    private val cachedPlacementInfo: PlacementInfo by lazy {
        PlacementInfo.create(heldIngredient)
    }
    override fun placementInfo(): PlacementInfo = cachedPlacementInfo

    /** Value is irrelevant; the recipe never appears in a recipe book.
     *  CRAFTING_MISC is the closest semantic fit if something ever reflects
     *  on this value for display. */
    override fun recipeBookCategory(): RecipeBookCategory =
        RecipeBookCategories.CRAFTING_MISC

    /**
     * Expose a single [net.minecraft.world.item.crafting.display.RecipeDisplay]
     * so result-based recipe searches can find this recipe. GuideME's
     * `<RecipeFor id="...">` tag scans `recipe.display()` and matches against
     * the result's `SlotDisplay`. Without this override the default empty
     * list makes the recipe invisible to those searches.
     *
     * The held and station displays are filled in for completeness (other
     * clients / future tooling may use them), but the load-bearing one for
     * GuideME is [SoulSandInfusionRecipeDisplay.resultSlot].
     */
    override fun display(): List<net.minecraft.world.item.crafting.display.RecipeDisplay> =
        listOf(
            SoulSandInfusionRecipeDisplay(
                heldSlot = heldIngredient.display(),
                resultSlot = net.minecraft.world.item.crafting.display.SlotDisplay.ItemStackSlotDisplay(result),
                stationSlot = net.minecraft.world.item.crafting.display.SlotDisplay.ItemSlotDisplay(
                    net.minecraft.world.level.block.Blocks.SOUL_SAND.asItem().builtInRegistryHolder(),
                ),
            )
        )

    companion object {
        val CODEC: MapCodec<SoulSandInfusionRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Ingredient.CODEC.fieldOf("held").forGetter(SoulSandInfusionRecipe::heldIngredient),
                ItemStackTemplate.CODEC.fieldOf("result").forGetter(SoulSandInfusionRecipe::result),
            ).apply(instance, ::SoulSandInfusionRecipe)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SoulSandInfusionRecipe> =
            StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, SoulSandInfusionRecipe::heldIngredient,
                ItemStackTemplate.STREAM_CODEC, SoulSandInfusionRecipe::result,
                ::SoulSandInfusionRecipe,
            )
    }
}
