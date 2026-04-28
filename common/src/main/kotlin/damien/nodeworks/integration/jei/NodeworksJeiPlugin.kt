package damien.nodeworks.integration.jei

import damien.nodeworks.network.SetInstructionGridPayload
import damien.nodeworks.network.SetProcessingApiDataPayload
import damien.nodeworks.network.SetProcessingApiSlotPayload
import damien.nodeworks.platform.PlatformServices
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
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler
import mezz.jei.api.recipe.types.IRecipeType
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import mezz.jei.api.registration.IRecipeTransferRegistration
import mezz.jei.api.registration.ISubtypeRegistration
import net.minecraft.client.renderer.Rect2i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import java.util.Optional

/**
 * JEI 29.5 plugin. Three transfer handlers (Instruction Set, Processing Set,
 * Inventory Terminal), one ghost-ingredient handler on the Processing Set GUI,
 * and the Milky Soul Ball "Soul Sand Infusion" recipe category.
 *
 * 26.1 / JEI 29.5 API shifts vs the pre-migration 19.21 plugin:
 *   - `RecipeType<RecipeHolder<CraftingRecipe>>` → `IRecipeType<RecipeHolder<
 *     CraftingRecipe>>`. `RecipeTypes.CRAFTING` changed from `RecipeType<...>`
 *     to `IRecipeHolderType<CraftingRecipe>` (which extends `IRecipeType<
 *     RecipeHolder<CraftingRecipe>>`), so it's assignment-compatible.
 *   - `IGhostIngredientHandler.getTargetsTyped<I>` is unbounded in Java but
 *     Kotlin treats the generic as `I : Any` (it can't accept nullable
 *     ingredients). The bound is explicit on our override.
 */
@JeiPlugin
class NodeworksJeiPlugin : IModPlugin {

    override fun getPluginUid(): Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "jei_plugin")

    override fun registerItemSubtypes(registration: ISubtypeRegistration) {
        // Cards / sets write CUSTOM_DATA when their settings GUI closes (channel
        // colour, storage priority, recipe contents, etc.). JEI 29.5 treats any
        // components-modified ItemStack as a distinct "subtype" by default,
        // which makes the modified stack an unknown ingredient and drops the
        // JEI mod-name tooltip line. Returning null from the interpreter tells
        // JEI all variants of these items collapse to the same base ingredient,
        // which keeps the "Nodeworks" mod tag on the tooltip after a GUI cycle.
        val collapseSubtypes = ISubtypeInterpreter<ItemStack> { _, _ -> null }
        val items = listOf(
            damien.nodeworks.registry.ModItems.IO_CARD,
            damien.nodeworks.registry.ModItems.STORAGE_CARD,
            damien.nodeworks.registry.ModItems.REDSTONE_CARD,
            damien.nodeworks.registry.ModItems.OBSERVER_CARD,
            damien.nodeworks.registry.ModItems.INSTRUCTION_SET,
            damien.nodeworks.registry.ModItems.PROCESSING_SET,
        )
        for (item in items) {
            registration.registerSubtypeInterpreter(item, collapseSubtypes)
        }
    }

    override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
        registration.addRecipeTransferHandler(InstructionSetTransferHandler(), RecipeTypes.CRAFTING)
        // Universal handler so the Processing Set's [+] works for any recipe category
        //  (crafting / smelting / blasting / modded). We read the input/output roles
        //  via IRecipeSlotsView instead of pattern-matching on a concrete recipe class.
        registration.addUniversalRecipeTransferHandler(ProcessingSetTransferHandler())
        registration.addRecipeTransferHandler(InventoryTerminalTransferHandler(), RecipeTypes.CRAFTING)
    }

    override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
        registration.addGhostIngredientHandler(
            ProcessingSetScreen::class.java,
            ProcessingSetGhostHandler()
        )
    }

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(
            MilkySoulBallRecipeCategory(registration.jeiHelpers.guiHelper)
        )
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        // Read the Soul Sand Infusion recipe set from our client-side cache.
        // Vanilla 26.1 doesn't sync the full recipe list to clients, see
        // `SoulSandInfusionClientCache` and the `RecipesReceivedEvent` hook
        // in `NeoForgeClientSetup` for how the cache stays current. The cache
        // is populated BEFORE JEI's reload callback runs (HIGHEST priority on
        // our listener), so by the time this method executes it has whatever
        // recipes the server just synced, including any data-pack additions
        // without code changes.
        val recipes = damien.nodeworks.recipe.SoulSandInfusionClientCache.recipes()
            .map { holder ->
                val recipe = holder.value()
                MilkySoulBallRecipe(recipe.heldIngredient, recipe.result.create())
            }
        registration.addRecipes(MilkySoulBallRecipeCategory.RECIPE_TYPE, recipes)
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        registration.addRecipeCatalyst(ItemStack(Items.MILK_BUCKET), MilkySoulBallRecipeCategory.RECIPE_TYPE)
        registration.addRecipeCatalyst(ItemStack(Items.SOUL_SAND), MilkySoulBallRecipeCategory.RECIPE_TYPE)
    }
}

// ── Inventory Terminal: crafting-recipe transfer (+) ──

class InventoryTerminalTransferHandler :
    IRecipeTransferHandler<damien.nodeworks.screen.InventoryTerminalMenu, RecipeHolder<CraftingRecipe>> {

    override fun getContainerClass(): Class<out damien.nodeworks.screen.InventoryTerminalMenu> =
        damien.nodeworks.screen.InventoryTerminalMenu::class.java

    override fun getMenuType(): Optional<MenuType<damien.nodeworks.screen.InventoryTerminalMenu>> =
        Optional.of(ModScreenHandlers.INVENTORY_TERMINAL)

    override fun getRecipeType(): IRecipeType<RecipeHolder<CraftingRecipe>> = RecipeTypes.CRAFTING

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

// ── Instruction Set: crafting-recipe transfer (+) ──

class InstructionSetTransferHandler :
    IRecipeTransferHandler<InstructionSetScreenHandler, RecipeHolder<CraftingRecipe>> {

    override fun getContainerClass(): Class<out InstructionSetScreenHandler> =
        InstructionSetScreenHandler::class.java

    override fun getMenuType(): Optional<MenuType<InstructionSetScreenHandler>> =
        Optional.of(ModScreenHandlers.INSTRUCTION_SET)

    override fun getRecipeType(): IRecipeType<RecipeHolder<CraftingRecipe>> = RecipeTypes.CRAFTING

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

// ── Processing Set: universal recipe transfer (+), any recipe category ──

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
            // Input role slots → the Processing Set's input section. We read roles via
            //  IRecipeSlotsView so this works for any recipe category (crafting / smelting
            //  / modded machine), not just CraftingRecipe.
            val inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)
            for (index in 0 until ProcessingSetScreenHandler.INPUT_SLOTS) {
                val (itemId, count) = extractItemAndCount(inputSlots.getOrNull(index))
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(container.containerId, index, itemId)
                )
                // Always reset the count, stale counts from previous recipes otherwise
                //  linger when the item changes.
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(container.containerId, "input", index, count)
                )
            }

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

    /**
     * Extract (itemId, count) from a JEI slot view. Returns ("", 1) if the slot is
     * empty, not an ItemStack, or carries non-default data components (potion
     * contents, enchantments, stew effects). Skipping those avoids placing
     * misleading "Uncraftable Potion" / blank-enchanted placeholders into the grid
     *, the Processing Set only keeps `itemId:count`, so anything component-
     * dependent can't round-trip.
     *
     * Count is clamped to at least 1 to preserve the set's invariant.
     */
    private fun extractItemAndCount(
        slotView: mezz.jei.api.gui.ingredient.IRecipeSlotView?
    ): Pair<String, Int> {
        if (slotView == null) return "" to 1
        val displayed = slotView.displayedIngredient
        if (!displayed.isPresent) return "" to 1
        val ingredient = displayed.get().ingredient
        if (ingredient !is ItemStack || ingredient.isEmpty) return "" to 1
        if (!ingredient.componentsPatch.isEmpty) return "" to 1
        val id = BuiltInRegistries.ITEM.getKey(ingredient.item)?.toString() ?: ""
        return id to ingredient.count.coerceAtLeast(1)
    }
}

// ── Processing Set: ghost-ingredient drag from JEI into slot ──

class ProcessingSetGhostHandler : IGhostIngredientHandler<ProcessingSetScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: ProcessingSetScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        val targets = mutableListOf<IGhostIngredientHandler.Target<I>>()

        val itemIngredient = ingredient.ingredient
        if (itemIngredient !is ItemStack) return targets

        val menu = gui.menu
        // Input slots (0..INPUT_SLOTS-1)
        for (i in 0 until ProcessingSetScreenHandler.INPUT_SLOTS) {
            val slot = menu.slots[i]
            val screenX = gui.getLeft() + slot.x
            val screenY = gui.getTop() + slot.y
            targets.add(GhostTarget(gui, i, Rect2i(screenX, screenY, 16, 16)))
        }
        // Output slots (INPUT_SLOTS .. INPUT_SLOTS + OUTPUT_SLOTS - 1)
        for (i in 0 until ProcessingSetScreenHandler.OUTPUT_SLOTS) {
            val slotIndex = ProcessingSetScreenHandler.INPUT_SLOTS + i
            val slot = menu.slots[slotIndex]
            val screenX = gui.getLeft() + slot.x
            val screenY = gui.getTop() + slot.y
            targets.add(GhostTarget(gui, slotIndex, Rect2i(screenX, screenY, 16, 16)))
        }
        return targets
    }

    override fun onComplete() {}

    private class GhostTarget<I : Any>(
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
