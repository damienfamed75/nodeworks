package damien.nodeworks.integration.jei

import damien.nodeworks.network.SetInstructionGridPayload
import damien.nodeworks.network.SetProcessingApiDataPayload
import damien.nodeworks.network.SetProcessingApiSlotPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.screen.InstructionSetScreenHandler
import damien.nodeworks.screen.ProcessingSetScreen
import damien.nodeworks.screen.ProcessingSetScreenHandler
import damien.nodeworks.screen.BreakerScreen
import damien.nodeworks.screen.ExportChestScreen
import damien.nodeworks.screen.PlacerScreen
import damien.nodeworks.screen.StorageCardScreen
import damien.nodeworks.screen.UserScreen
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.builder.ITooltipBuilder
import mezz.jei.api.gui.handlers.IGhostIngredientHandler
import mezz.jei.api.gui.ingredient.IRecipeSlotView
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
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
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
        registration.addGhostIngredientHandler(
            StorageCardScreen::class.java,
            StorageCardGhostHandler()
        )
        registration.addGhostIngredientHandler(
            ExportChestScreen::class.java,
            ExportChestGhostHandler()
        )
        registration.addGhostIngredientHandler(
            UserScreen::class.java,
            UserGhostHandler()
        )
        registration.addGhostIngredientHandler(
            BreakerScreen::class.java,
            BreakerGhostHandler()
        )
        registration.addGhostIngredientHandler(
            PlacerScreen::class.java,
            PlacerGhostHandler()
        )
        registration.addGhostIngredientHandler(
            damien.nodeworks.screen.TerminalScreen::class.java,
            TerminalScriptEditorGhostHandler()
        )
        registration.addGhostIngredientHandler(
            damien.nodeworks.screen.StorageMeterScreen::class.java,
            StorageMeterGhostHandler()
        )
        registration.addGhostIngredientHandler(
            damien.nodeworks.screen.CraftRequesterScreen::class.java,
            CraftRequesterGhostHandler()
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

    override fun onRuntimeAvailable(runtime: mezz.jei.api.runtime.IJeiRuntime) {
        // Wire the search-bar bridge so the Inventory Terminal can mirror its
        // search box against JEI's filter text when the player has the sync
        // toggle on. Bridge stays installed for the lifetime of this runtime,
        // [onRuntimeUnavailable] clears it on resource reload / disconnect.
        val filter = runtime.ingredientFilter
        JeiSearchBridge.getter = { filter.filterText.orEmpty() }
        JeiSearchBridge.setter = { text -> filter.setFilterText(text) }
    }

    override fun onRuntimeUnavailable() {
        JeiSearchBridge.getter = null
        JeiSearchBridge.setter = null
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
        val inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)
        if (doTransfer) {
            // (kinda grabbed from AE2, thank you for having this, don't kill me :pray:)
            // Ship the recipe id and per-slot displayed-stack
            // fallback. Server re-resolves the recipe to get the full
            // tag-expanded `Ingredient` list, so we don't enumerate every
            // accepted item over the wire. RecipeHolder.id is a
            // `ResourceKey<Recipe<?>>` in 26.1, the server rebuilds the key
            // from the flattened identifier.
            val fallback = (0 until 9).map { idx ->
                val slot = inputSlots.getOrNull(idx) ?: return@map ""
                val displayed = slot.displayedIngredient.orElse(null)?.ingredient as? ItemStack
                if (displayed == null || displayed.isEmpty) "" else
                    BuiltInRegistries.ITEM.getKey(displayed.item)?.toString().orEmpty()
            }
            PlatformServices.clientNetworking.sendToServer(
                damien.nodeworks.network.InvTerminalCraftGridPayload(
                    container.containerId,
                    recipe.id.identifier(),
                    fallback,
                )
            )
        }

        // Probe missing on every call so the [+] button gets the cosmetic
        // red highlight on hover. Returning a COSMETIC error keeps the
        // button clickable, the player can still queue a partial transfer.
        val missing = findMissingInputs(container, player, inputSlots)
        return if (missing.isEmpty()) null else MissingIngredientsError(missing)
    }

    /** For each recipe input slot, decide whether any of its accepted items
     *  can be sourced from the player's inventory, the crafting grid the
     *  [+] would overwrite, or the network. Greedy first-fit with running
     *  availability so one stack doesn't satisfy multiple slots. */
    private fun findMissingInputs(
        menu: damien.nodeworks.screen.InventoryTerminalMenu,
        player: Player,
        inputSlots: List<IRecipeSlotView>,
    ): List<IRecipeSlotView> {
        if (inputSlots.isEmpty()) return emptyList()

        val available = HashMap<String, Long>()
        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            available.merge(id, stack.count.toLong(), Long::plus)
        }
        for (i in 0 until 9) {
            val stack = menu.craftingContainer.getItem(i)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            available.merge(id, stack.count.toLong(), Long::plus)
        }
        // Only query the repo for ids the recipe mentions, so a huge
        // network doesn't pay for every stored item id every probe.
        val screen = damien.nodeworks.screen.InventoryTerminalScreen.activeScreen
        if (screen != null && screen.menu === menu) {
            val sampled = HashSet<String>()
            for (slot in inputSlots) {
                slot.getItemStacks().forEach { stack ->
                    val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return@forEach
                    if (sampled.add(id)) {
                        val networkCount = screen.repo.countItem(id)
                        if (networkCount > 0) available.merge(id, networkCount, Long::plus)
                    }
                }
            }
        }

        val missing = mutableListOf<IRecipeSlotView>()
        for (slot in inputSlots) {
            val candidates = slot.getItemStacks().toList()
            if (candidates.isEmpty()) continue  // no-ingredient slot, doesn't count as missing
            var satisfied = false
            for (stack in candidates) {
                val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
                val have = available[id] ?: 0L
                if (have >= stack.count) {
                    available[id] = have - stack.count
                    satisfied = true
                    break
                }
            }
            if (!satisfied) missing.add(slot)
        }
        return missing
    }
}

/** Cosmetic transfer error that highlights missing recipe slots in red
 *  without disabling the [+] button, mirrors vanilla's crafting-table UX. */
private class MissingIngredientsError(
    private val missingSlots: List<IRecipeSlotView>,
) : IRecipeTransferError {
    override fun getType(): IRecipeTransferError.Type = IRecipeTransferError.Type.COSMETIC

    override fun showError(
        guiGraphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        recipeSlotsView: IRecipeSlotsView,
        recipeX: Int,
        recipeY: Int,
    ) {
        // `drawHighlight` paints at the slot's recipe-local coordinates, so
        // we translate to the recipe origin first or every overlay lands at
        // (0, 0) of the window. Pop after so the shift doesn't leak.
        guiGraphics.pose().pushMatrix()
        guiGraphics.pose().translate(recipeX.toFloat(), recipeY.toFloat())
        for (slot in missingSlots) slot.drawHighlight(guiGraphics, MISSING_COLOR)
        guiGraphics.pose().popMatrix()
    }

    override fun getTooltip(tooltip: ITooltipBuilder) {
        tooltip.add(Component.translatable("tooltip.nodeworks.missing_ingredients"))
    }

    override fun getMissingCountHint(): Int = missingSlots.size

    companion object {
        /** Semi-transparent red wash, matches JEI's default error overlay. */
        private const val MISSING_COLOR: Int = 0x66FF0000.toInt()
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
                val (stack, count) = extractStackAndCount(inputSlots.getOrNull(index))
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(container.containerId, index, stack)
                )
                // Always reset the count, stale counts from previous recipes otherwise
                //  linger when the item changes.
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(container.containerId, "input", index, count)
                )
            }

            val outputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.OUTPUT)
            for (index in 0 until ProcessingSetScreenHandler.OUTPUT_SLOTS) {
                val (stack, count) = extractStackAndCount(outputSlots.getOrNull(index))
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiSlotPayload(
                        container.containerId,
                        ProcessingSetScreenHandler.INPUT_SLOTS + index,
                        stack
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
     * Extract (stack, count) from a JEI slot view. Returns (EMPTY, 1) if the
     * slot is empty or not an ItemStack. Component-bearing items (potions,
     * enchanted books, dyed armor) are passed through with their components
     * intact since Processing Sets now key recipes on the full DataComponents
     * patch, not just itemId.
     */
    private fun extractStackAndCount(
        slotView: mezz.jei.api.gui.ingredient.IRecipeSlotView?
    ): Pair<ItemStack, Int> {
        if (slotView == null) return ItemStack.EMPTY to 1
        val displayed = slotView.displayedIngredient
        if (!displayed.isPresent) return ItemStack.EMPTY to 1
        val ingredient = displayed.get().ingredient
        if (ingredient !is ItemStack || ingredient.isEmpty) return ItemStack.EMPTY to 1
        return ingredient.copyWithCount(1) to ingredient.count.coerceAtLeast(1)
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
            // Carry the full stack (components and all) so dragging a
            // Potion of Strength from JEI lands as the actual variant, not
            // a bare uncraftable potion. The server's setSlotFromStack
            // copies with count=1 to fit the ghost-slot contract.
            PlatformServices.clientNetworking.sendToServer(
                SetProcessingApiSlotPayload(gui.menu.containerId, slotIndex, ingredient.copyWithCount(1))
            )
        }
    }
}

// ── Storage Card: ghost-ingredient drag from JEI onto the rule panel ──

/**
 * Lets a player drag an item from JEI anywhere onto the Storage Card's
 * filter rule panel. The dropped item's id (e.g. `minecraft:oak_planks`) is
 * appended as a new rule. Per the design, the entire panel is one drop
 * target rather than one per row, so the player doesn't have to aim for a
 * specific empty row to add a filter.
 */
class StorageCardGhostHandler : IGhostIngredientHandler<StorageCardScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: StorageCardScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val rect = gui.rulePanelDropArea() ?: return emptyList()
        return listOf(StorageCardRuleTarget(gui, Rect2i(rect[0], rect[1], rect[2], rect[3])))
    }

    override fun onComplete() {}

    private class StorageCardRuleTarget<I : Any>(
        private val gui: StorageCardScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {
        override fun getArea(): Rect2i = area
        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            gui.acceptGhostStack(ingredient)
        }
    }
}

/**
 * Mirror of [StorageCardGhostHandler] for the Export Chest GUI: a single drop
 * area covering the rule list panel appends the dropped item's id as a new
 * filter rule.
 */
class ExportChestGhostHandler : IGhostIngredientHandler<ExportChestScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: ExportChestScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val rect = gui.rulePanelDropArea() ?: return emptyList()
        return listOf(ExportChestRuleTarget(gui, Rect2i(rect[0], rect[1], rect[2], rect[3])))
    }

    override fun onComplete() {}

    private class ExportChestRuleTarget<I : Any>(
        private val gui: ExportChestScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {
        override fun getArea(): Rect2i = area
        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            gui.acceptGhostStack(ingredient)
        }
    }
}

/**
 * Ghost-ingredient handler for the User device GUI. The filter row (icon + EditBox)
 * is the single drop target; dropping replaces the filter with the dropped item's id.
 */
class UserGhostHandler : IGhostIngredientHandler<UserScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: UserScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val rect = gui.filterDropArea() ?: return emptyList()
        return listOf(UserFilterTarget(gui, Rect2i(rect[0], rect[1], rect[2], rect[3])))
    }

    override fun onComplete() {}

    private class UserFilterTarget<I : Any>(
        private val gui: UserScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {
        override fun getArea(): Rect2i = area
        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            gui.acceptGhostStack(ingredient)
        }
    }
}

/**
 * Mirror of [UserGhostHandler] for the Breaker GUI: a single drop area
 * covering the filter row replaces the Breaker's filter with the dropped
 * item's id (the registry id of the block that drops the item, in most
 * cases - users typing block ids directly remains the explicit path).
 */
class BreakerGhostHandler : IGhostIngredientHandler<BreakerScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: BreakerScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val rect = gui.filterDropArea() ?: return emptyList()
        return listOf(BreakerFilterTarget(gui, Rect2i(rect[0], rect[1], rect[2], rect[3])))
    }

    override fun onComplete() {}

    private class BreakerFilterTarget<I : Any>(
        private val gui: BreakerScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {
        override fun getArea(): Rect2i = area
        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            gui.acceptGhostStack(ingredient)
        }
    }
}

/**
 * Mirror of [BreakerGhostHandler] for the Placer GUI.
 */
class PlacerGhostHandler : IGhostIngredientHandler<PlacerScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: PlacerScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val rect = gui.filterDropArea() ?: return emptyList()
        return listOf(PlacerFilterTarget(gui, Rect2i(rect[0], rect[1], rect[2], rect[3])))
    }

    override fun onComplete() {}

    private class PlacerFilterTarget<I : Any>(
        private val gui: PlacerScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {
        override fun getArea(): Rect2i = area
        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            gui.acceptGhostStack(ingredient)
        }
    }
}

/**
 * Ghost-ingredient handler for the Scripting Terminal. The script editor's
 * rendered region is the drop area; dropping inserts a quoted Lua string
 * literal with the canonical `id[components]` filter form at the cursor so a
 * player can author `network:find(<dropped>)` etc. without typing the
 * variant string by hand.
 *
 * Suppresses JEI's default full-area green tint via [shouldHighlightTargets]
 * `= false`. The terminal screen draws its own small "+" marker at the caret
 * while the drag is active, and we tell the screen to skip text-selection
 * drags so dragging a JEI ingredient over the editor doesn't accidentally
 * select script text.
 */
class TerminalScriptEditorGhostHandler : IGhostIngredientHandler<damien.nodeworks.screen.TerminalScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: damien.nodeworks.screen.TerminalScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val rect = gui.editorDropArea() ?: return emptyList()
        if (doStart) gui.setJeiDragging(true)
        return listOf(TerminalEditorTarget(gui, Rect2i(rect[0], rect[1], rect[2], rect[3])))
    }

    override fun shouldHighlightTargets(): Boolean = false

    override fun onComplete() {
        // JEI only hands us back a `Screen` here on older versions, so we
        // can't query the active terminal directly. Instead the screen owns
        // its own flag and clears it on render-end / mouseRelease. Belt and
        // braces: also clear via the active-screen singleton if exposed.
        damien.nodeworks.screen.TerminalScreen.clearJeiDraggingOnActive()
    }

    private class TerminalEditorTarget<I : Any>(
        private val gui: damien.nodeworks.screen.TerminalScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {
        override fun getArea(): Rect2i = area
        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            gui.acceptGhostStack(ingredient)
            gui.setJeiDragging(false)
        }
    }
}

// ── Storage Meter: ghost-ingredient drag from JEI onto the target slot ──

class StorageMeterGhostHandler : IGhostIngredientHandler<damien.nodeworks.screen.StorageMeterScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: damien.nodeworks.screen.StorageMeterScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean,
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val slotScreenX = gui.getLeft() + damien.nodeworks.screen.StorageMeterScreen.SLOT_X
        val slotScreenY = gui.getTop() + damien.nodeworks.screen.StorageMeterScreen.SLOT_Y
        return listOf(GhostTarget(gui, Rect2i(slotScreenX, slotScreenY, 16, 16)))
    }

    override fun onComplete() {}

    private class GhostTarget<I : Any>(
        private val gui: damien.nodeworks.screen.StorageMeterScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {

        override fun getArea(): Rect2i = area

        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            PlatformServices.clientNetworking.sendToServer(
                damien.nodeworks.network.SetStorageMeterTargetPayload(
                    gui.menu.containerId,
                    ingredient.copyWithCount(1),
                ),
            )
        }
    }
}

// ── Craft Requester: ghost-ingredient drag from JEI onto the target slot ──

class CraftRequesterGhostHandler : IGhostIngredientHandler<damien.nodeworks.screen.CraftRequesterScreen> {

    override fun <I : Any> getTargetsTyped(
        gui: damien.nodeworks.screen.CraftRequesterScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean,
    ): List<IGhostIngredientHandler.Target<I>> {
        if (ingredient.ingredient !is ItemStack) return emptyList()
        val slotScreenX = gui.getLeft() + damien.nodeworks.screen.CraftRequesterScreen.SLOT_X
        val slotScreenY = gui.getTop() + damien.nodeworks.screen.CraftRequesterScreen.SLOT_Y
        return listOf(GhostTarget(gui, Rect2i(slotScreenX, slotScreenY, 16, 16)))
    }

    override fun onComplete() {}

    private class GhostTarget<I : Any>(
        private val gui: damien.nodeworks.screen.CraftRequesterScreen,
        private val area: Rect2i,
    ) : IGhostIngredientHandler.Target<I> {

        override fun getArea(): Rect2i = area

        override fun accept(ingredient: I) {
            if (ingredient !is ItemStack || ingredient.isEmpty) return
            PlatformServices.clientNetworking.sendToServer(
                damien.nodeworks.network.SetCraftRequesterTargetPayload(
                    gui.menu.containerId,
                    ingredient.copyWithCount(1),
                ),
            )
        }
    }
}
