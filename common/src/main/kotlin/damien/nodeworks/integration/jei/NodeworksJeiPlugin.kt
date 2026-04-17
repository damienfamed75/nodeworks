package damien.nodeworks.integration.jei

import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import net.minecraft.resources.Identifier

/**
 * TODO MC 26.1.2 / JEI 29.5 MIGRATION — full plugin rewrite needed.
 *
 * The JEI API had significant reshuffling between our old version (19.21 for
 * 1.21.1) and the 29.5.x line for 26.1.2:
 *   - `IRecipeTransferHandler.getRecipeType()` now returns `IRecipeHolderType<T>`
 *     instead of `RecipeType<RecipeHolder<T>>`.
 *   - `IGhostIngredientHandler.Target` — methods renamed (getTargetsTyped) and
 *     type bounds are stricter (was `<T>`, now `<T : Any>` in Kotlin terms).
 *   - Constants like `RecipeTypes.CRAFTING` are under `IRecipeHolderType` rather
 *     than the old `RecipeType<RecipeHolder<CraftingRecipe>>` shape.
 *
 * The full pre-migration plugin is preserved in git history at the commit
 * before the 26.1.2 upgrade. It registered:
 *   - InstructionSetTransferHandler (crafting-category [+] into Instruction Set)
 *   - ProcessingSetTransferHandler (universal [+] into Processing Set — any
 *     recipe category, not just crafting)
 *   - InventoryTerminalTransferHandler (crafting-category [+] from terminal)
 *   - ProcessingSetGhostHandler (drag-JEI-ingredient-to-slot support)
 *   - MilkySoulBall recipe category + wither-effect visual
 *
 * For now the plugin registers only its identifier so JEI doesn't complain
 * about a missing plugin UID when the mod is loaded alongside JEI.
 */
@JeiPlugin
class NodeworksJeiPlugin : IModPlugin {

    override fun getPluginUid(): Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "jei_plugin")
}
