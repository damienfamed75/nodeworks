package damien.nodeworks.integration.jei

import damien.nodeworks.network.SetRecipeGridPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.screen.RecipeCardScreenHandler
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler
import mezz.jei.api.recipe.types.IRecipeType
import mezz.jei.api.registration.IRecipeTransferRegistration
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import java.util.Optional

@JeiPlugin
class NodeworksJeiPlugin : IModPlugin {

    override fun getPluginUid(): Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "jei_plugin")

    override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
        registration.addRecipeTransferHandler(
            RecipeCardTransferHandler(),
            RecipeTypes.CRAFTING
        )
    }
}

class RecipeCardTransferHandler : IRecipeTransferHandler<RecipeCardScreenHandler, RecipeHolder<CraftingRecipe>> {

    override fun getContainerClass(): Class<out RecipeCardScreenHandler> =
        RecipeCardScreenHandler::class.java

    override fun getMenuType(): Optional<MenuType<RecipeCardScreenHandler>> =
        Optional.of(ModScreenHandlers.RECIPE_CARD)

    override fun getRecipeType(): IRecipeType<RecipeHolder<CraftingRecipe>> =
        RecipeTypes.CRAFTING

    override fun transferRecipe(
        container: RecipeCardScreenHandler,
        recipe: RecipeHolder<CraftingRecipe>,
        recipeSlots: IRecipeSlotsView,
        player: Player,
        maxTransfer: Boolean,
        doTransfer: Boolean
    ): IRecipeTransferError? {
        if (doTransfer) {
            val grid = Array(9) { "" }
            val inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)

            for ((index, slotView) in inputSlots.withIndex()) {
                if (index >= 9) break
                val displayed = slotView.displayedIngredient
                if (displayed.isPresent) {
                    val ingredient = displayed.get().ingredient
                    if (ingredient is ItemStack && !ingredient.isEmpty) {
                        grid[index] = BuiltInRegistries.ITEM.getKey(ingredient.item)?.toString() ?: ""
                    }
                }
            }

            PlatformServices.clientNetworking.sendToServer(
                SetRecipeGridPayload(container.containerId, grid.toList())
            )
        }

        // Always allow — this sets a pattern, no real items needed
        return null
    }
}
