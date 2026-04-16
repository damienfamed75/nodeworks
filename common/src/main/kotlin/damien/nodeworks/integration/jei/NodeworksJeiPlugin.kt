package damien.nodeworks.integration.jei

import damien.nodeworks.network.SetInstructionGridPayload
import damien.nodeworks.network.SetProcessingApiDataPayload
import damien.nodeworks.network.SetProcessingApiSlotPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModItems
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.screen.InstructionSetScreenHandler
import damien.nodeworks.screen.ProcessingSetScreen
import damien.nodeworks.screen.ProcessingSetScreenHandler
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.handlers.IGhostIngredientHandler
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import mezz.jei.api.registration.IRecipeTransferRegistration
import net.minecraft.client.renderer.Rect2i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import java.util.Optional

@JeiPlugin
class NodeworksJeiPlugin : IModPlugin {

    override fun getPluginUid(): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("nodeworks", "jei_plugin")

    override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
        registration.addRecipeTransferHandler(
            InstructionSetTransferHandler(),
            RecipeTypes.CRAFTING
        )
        // Universal handler so the Processing Set's [+] button works for any recipe
        // category in JEI (crafting, smelting, blasting, modded machines, etc.).
        registration.addUniversalRecipeTransferHandler(ProcessingSetTransferHandler())
        registration.addRecipeTransferHandler(
            InventoryTerminalTransferHandler(),
            RecipeTypes.CRAFTING
        )
    }

    override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
        registration.addGhostIngredientHandler(
            ProcessingSetScreen::class.java,
            ProcessingSetGhostHandler()
        )

        // JEI item focus for Inventory Terminal handled via screen's getSlotUnderMouse override
    }

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(
            MilkySoulBallRecipeCategory(registration.jeiHelpers.guiHelper)
        )
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        registration.addRecipes(
            MilkySoulBallRecipeCategory.RECIPE_TYPE,
            listOf(
                MilkySoulBallRecipe(
                    ItemStack(Items.MILK_BUCKET),
                    ItemStack(Items.SOUL_SAND),
                    ItemStack(ModItems.MILKY_SOUL_BALL, 4)
                )
            )
        )
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        registration.addRecipeCatalyst(
            ItemStack(Items.MILK_BUCKET),
            MilkySoulBallRecipeCategory.RECIPE_TYPE
        )
        registration.addRecipeCatalyst(
            ItemStack(Items.SOUL_SAND),
            MilkySoulBallRecipeCategory.RECIPE_TYPE
        )
    }
}

// ── Inventory Terminal: recipe transfer (+) ──

class InventoryTerminalTransferHandler : IRecipeTransferHandler<damien.nodeworks.screen.InventoryTerminalMenu, RecipeHolder<CraftingRecipe>> {

    override fun getContainerClass(): Class<out damien.nodeworks.screen.InventoryTerminalMenu> =
        damien.nodeworks.screen.InventoryTerminalMenu::class.java

    override fun getMenuType(): Optional<MenuType<damien.nodeworks.screen.InventoryTerminalMenu>> =
        Optional.of(ModScreenHandlers.INVENTORY_TERMINAL)

    override fun getRecipeType(): RecipeType<RecipeHolder<CraftingRecipe>> =
        RecipeTypes.CRAFTING

    override fun transferRecipe(
        container: damien.nodeworks.screen.InventoryTerminalMenu,
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
                damien.nodeworks.network.InvTerminalCraftGridPayload(container.containerId, grid.toList())
            )
        }

        return null
    }
}

// ── Instruction Set: recipe transfer (+) ──

class InstructionSetTransferHandler : IRecipeTransferHandler<InstructionSetScreenHandler, RecipeHolder<CraftingRecipe>> {

    override fun getContainerClass(): Class<out InstructionSetScreenHandler> =
        InstructionSetScreenHandler::class.java

    override fun getMenuType(): Optional<MenuType<InstructionSetScreenHandler>> =
        Optional.of(ModScreenHandlers.INSTRUCTION_SET)

    override fun getRecipeType(): RecipeType<RecipeHolder<CraftingRecipe>> =
        RecipeTypes.CRAFTING

    override fun transferRecipe(
        container: InstructionSetScreenHandler,
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
                SetInstructionGridPayload(container.containerId, grid.toList())
            )
        }

        return null
    }
}

// ── Processing Set: recipe transfer (+) ──

class ProcessingSetTransferHandler : IUniversalRecipeTransferHandler<ProcessingSetScreenHandler> {

    override fun getContainerClass(): Class<out ProcessingSetScreenHandler> =
        ProcessingSetScreenHandler::class.java

    override fun getMenuType(): Optional<MenuType<ProcessingSetScreenHandler>> =
        Optional.of(ModScreenHandlers.PROCESSING_SET)

    override fun transferRecipe(
        container: ProcessingSetScreenHandler,
        recipe: Any,
        recipeSlots: IRecipeSlotsView,
        player: Player,
        maxTransfer: Boolean,
        doTransfer: Boolean
    ): IRecipeTransferError? {
        if (doTransfer) {
            // Write every input role slot into the grid, up to INPUT_SLOTS. Works for
            // any recipe category because we rely on the RecipeIngredientRole view JEI
            // produces, not on the recipe's concrete Java type.
            val inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)
            for (index in 0 until ProcessingSetScreenHandler.INPUT_SLOTS) {
                val (itemId, count) = extractItemAndCount(inputSlots.getOrNull(index))
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(container.containerId, index, itemId)
                )
                // Always reset the count to match the recipe — otherwise stale counts
                // from a previously-filled grid linger even when the item is replaced.
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(container.containerId, "input", index, count)
                )
            }

            // Write every output role slot, up to OUTPUT_SLOTS. Furnace/smelting has 1
            // output; crafting has 1; custom multi-output recipes can fill all three.
            val outputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.OUTPUT)
            for (index in 0 until ProcessingSetScreenHandler.OUTPUT_SLOTS) {
                val (itemId, count) = extractItemAndCount(outputSlots.getOrNull(index))
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(
                        container.containerId,
                        ProcessingSetScreenHandler.INPUT_SLOTS + index,
                        itemId
                    )
                )
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(container.containerId, "output", index, count)
                )
            }
        }

        return null
    }

    /** Extract (itemId, count) from a JEI slot view. Returns ("", 1) if the slot is
     *  empty or not an ItemStack ingredient. Count is clamped to at least 1 so the
     *  Processing Set's coerceAtLeast(1) invariant is preserved. */
    private fun extractItemAndCount(
        slotView: mezz.jei.api.gui.ingredient.IRecipeSlotView?
    ): Pair<String, Int> {
        if (slotView == null) return "" to 1
        val displayed = slotView.displayedIngredient
        if (!displayed.isPresent) return "" to 1
        val ingredient = displayed.get().ingredient
        if (ingredient !is ItemStack || ingredient.isEmpty) return "" to 1
        val id = BuiltInRegistries.ITEM.getKey(ingredient.item)?.toString() ?: ""
        return id to ingredient.count.coerceAtLeast(1)
    }
}

// ── Processing Set: ghost ingredient drag from JEI ──

class ProcessingSetGhostHandler : IGhostIngredientHandler<ProcessingSetScreen> {

    override fun <I> getTargetsTyped(
        gui: ProcessingSetScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        val targets = mutableListOf<IGhostIngredientHandler.Target<I>>()

        // Only accept ItemStack ingredients
        val itemIngredient = ingredient.ingredient
        if (itemIngredient !is ItemStack) return targets

        val menu = gui.menu

        // Input slots (0-8)
        for (i in 0 until ProcessingSetScreenHandler.INPUT_SLOTS) {
            val slot = menu.slots[i]
            val screenX = gui.getLeft() + slot.x
            val screenY = gui.getTop() + slot.y
            targets.add(GhostTarget(gui, i, Rect2i(screenX, screenY, 16, 16)))
        }

        // Output slots (9-11)
        for (i in 0 until ProcessingSetScreenHandler.OUTPUT_SLOTS) {
            val slotIndex = ProcessingSetScreenHandler.INPUT_SLOTS + i
            val slot = menu.slots[slotIndex]
            val screenX = gui.getLeft() + slot.x
            val screenY = gui.getTop() + slot.y
            targets.add(GhostTarget(gui, slotIndex, Rect2i(screenX, screenY, 16, 16)))
        }

        return targets
    }

    override fun onComplete() {
        // Nothing to do
    }

    private class GhostTarget<I>(
        private val gui: ProcessingSetScreen,
        private val slotIndex: Int,
        private val area: Rect2i
    ) : IGhostIngredientHandler.Target<I> {

        override fun getArea(): Rect2i = area

        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            val itemId = BuiltInRegistries.ITEM.getKey(ingredient.item)?.toString() ?: return
            PlatformServices.clientNetworking.sendToServer(
                SetProcessingApiSlotPayload(gui.menu.containerId, slotIndex, itemId)
            )
        }
    }
}

