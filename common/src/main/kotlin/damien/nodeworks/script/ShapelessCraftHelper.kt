package damien.nodeworks.script

import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.RecipeType
import org.slf4j.LoggerFactory

/**
 * Handles shapeless crafting for network:shapeless().
 * Takes a map of item IDs to counts, finds a matching vanilla recipe,
 * pulls ingredients from network storage, and inserts the result.
 */
object ShapelessCraftHelper {

    private val logger = LoggerFactory.getLogger("nodeworks-shapeless")

    data class CraftResult(
        val outputItemId: String,
        val outputName: String,
        val count: Int
    )

    /**
     * Craft using vanilla recipes by specifying ingredients.
     * Searches all crafting recipes to find one that matches the given ingredients.
     */
    fun craft(
        ingredients: Map<String, Int>,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache? = null
    ): CraftResult? {
        val recipeManager = level.getRecipeManager() ?: return null

        // Build the 3x3 crafting grid from the ingredients
        val gridItems = mutableListOf<ItemStack>()
        for ((itemId, count) in ingredients) {
            val identifier = Identifier.tryParse(itemId) ?: return null
            val item = BuiltInRegistries.ITEM.get(identifier) ?: return null
            repeat(count) { gridItems.add(ItemStack(item, 1)) }
        }

        if (gridItems.size > 9) {
            logger.debug("Too many ingredients for shapeless craft: {}", gridItems.size)
            return null
        }

        // Pad to 9 items
        while (gridItems.size < 9) {
            gridItems.add(ItemStack.EMPTY)
        }

        // Try the ingredients in the grid as-is first
        val craftingInput = CraftingInput.of(3, 3, gridItems)
        val recipeResult = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, craftingInput, level)
            .orElse(null)

        if (recipeResult == null) {
            // Try shuffling — for shaped recipes the position matters
            // Try placing items starting from top-left in order
            val result = tryAllPlacements(gridItems.filter { !it.isEmpty }, recipeManager, level)
            if (result == null) {
                logger.debug("No matching recipe found for ingredients: {}", ingredients)
                return null
            }
            return executeCraft(result.first, result.second, ingredients, level, snapshot, cache)
        }

        val resultStack = recipeResult.value().assemble(craftingInput, level.registryAccess())
        return executeCraft(resultStack, craftingInput, ingredients, level, snapshot, cache)
    }

    /**
     * Try different placements of ingredients in the 3x3 grid to match shaped recipes.
     */
    private fun tryAllPlacements(
        items: List<ItemStack>,
        recipeManager: RecipeManager,
        level: ServerLevel
    ): Pair<ItemStack, CraftingInput>? {
        // Try placing in different grid positions
        // For simple recipes (1-4 ingredients), try common placements
        if (items.size <= 4) {
            // Try top-left aligned
            val placements = listOf(
                // 1x1 at each position
                listOf(0), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(7), listOf(8),
                // 2x1 rows
                listOf(0, 1), listOf(1, 2), listOf(3, 4), listOf(4, 5), listOf(6, 7), listOf(7, 8),
                // 1x2 columns
                listOf(0, 3), listOf(1, 4), listOf(2, 5), listOf(3, 6), listOf(4, 7), listOf(5, 8),
                // 2x2
                listOf(0, 1, 3, 4), listOf(1, 2, 4, 5), listOf(3, 4, 6, 7), listOf(4, 5, 7, 8),
                // 3x1
                listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
                // 1x3
                listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
                // 3x3 (full grid)
                listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
            )

            for (positions in placements) {
                if (positions.size != items.size) continue
                val grid = Array(9) { ItemStack.EMPTY }
                for ((idx, pos) in positions.withIndex()) {
                    grid[pos] = items[idx]
                }
                val input = CraftingInput.of(3, 3, grid.toList())
                val result = recipeManager.getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null)
                if (result != null) {
                    return Pair(result.value().assemble(input, level.registryAccess()), input)
                }
            }
        }

        return null
    }

    private fun executeCraft(
        resultStack: ItemStack,
        craftingInput: CraftingInput,
        ingredients: Map<String, Int>,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache? = null
    ): CraftResult? {
        if (resultStack.isEmpty) return null

        // Check and extract ingredients from network storage
        for ((itemId, needed) in ingredients) {
            val available = NetworkStorageHelper.countItems(level, snapshot, itemId)
            if (available < needed) {
                logger.debug("Not enough {} in network storage: need {}, have {}", itemId, needed, available)
                return null
            }
        }

        // Extract ingredients
        for ((itemId, needed) in ingredients) {
            var remaining = needed.toLong()
            for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
                if (remaining <= 0) break
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                val removed = PlatformServices.storage.extractItems(storage, { CardHandle.matchesFilter(it, itemId) }, remaining)
                if (removed > 0) cache?.onExtracted(itemId, false, removed)
                remaining -= removed
            }
            if (remaining > 0) {
                logger.warn("Failed to extract all ingredients for shapeless craft")
                return null
            }
        }

        // Insert result into network storage
        val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, resultStack, cache)
        if (inserted == 0) {
            logger.debug("Network storage full, cannot insert crafted item")
            return null
        }

        val outputId = BuiltInRegistries.ITEM.getKey(resultStack.item)?.toString() ?: return null
        val outputName = resultStack.hoverName.string

        return CraftResult(outputId, outputName, resultStack.count)
    }
}
