package damien.nodeworks.script

import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.network.InstructionSetMatch
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
 * Handles crafting execution for `network:craft()`.
 * Resolves recipes from Instruction Sets, pulls ingredients from network storage,
 * crafts the result, and inserts it into network storage.
 * Supports recursive crafting (auto-crafts missing ingredients).
 */
object CraftingHelper {

    private val logger = LoggerFactory.getLogger("nodeworks-crafting")

    data class CraftResult(
        val outputItemId: String,
        val outputName: String,
        val count: Int
    )

    /**
     * Craft items matching the given identifier (alias or output item ID).
     * @param identifier The Instruction Set alias or output item ID
     * @param count Number of crafting operations to perform
     * @param level The server level
     * @param snapshot Network snapshot for finding crafters and storage
     * @param depth Recursion depth guard
     * @return CraftResult if successful, null if failed
     */
    fun craft(
        identifier: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int = 0
    ): CraftResult? {
        if (depth > 20) {
            logger.warn("Crafting recursion depth exceeded for '{}'", identifier)
            return null
        }

        val match = snapshot.findInstructionSet(identifier) ?: return null
        val recipe = match.instructionSet.recipe

        var totalCrafted = 0
        for (i in 0 until count) {
            if (!craftOnce(recipe, match, level, snapshot, depth)) break
            totalCrafted++
        }

        if (totalCrafted == 0) return null

        val outputId = match.instructionSet.outputItemId
        val outputIdentifier = Identifier.tryParse(outputId) ?: return null
        val outputItem = BuiltInRegistries.ITEM.getValue(outputIdentifier) ?: return null
        val outputName = outputItem.getName(ItemStack(outputItem)).string

        // Get the actual output count per craft from the recipe manager
        val outputPerCraft = getRecipeOutputCount(recipe, level)

        return CraftResult(outputId, outputName, totalCrafted * outputPerCraft)
    }

    /**
     * Execute one crafting operation: check ingredients, pull from storage, craft, insert result.
     */
    private fun craftOnce(
        recipe: List<String>,
        match: InstructionSetMatch,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int
    ): Boolean {
        // Check each ingredient and ensure it's available (or can be crafted)
        val ingredientCounts = mutableMapOf<String, Int>()
        for (itemId in recipe) {
            if (itemId.isEmpty()) continue
            ingredientCounts[itemId] = (ingredientCounts[itemId] ?: 0) + 1
        }

        // For each ingredient, check availability and recursively craft if needed
        for ((itemId, needed) in ingredientCounts) {
            val available = NetworkStorageHelper.countItems(level, snapshot, itemId)
            if (available < needed) {
                val missing = needed - available.toInt()
                // Try to recursively craft the missing ingredients
                val subResult = craft(itemId, missing, level, snapshot, depth + 1)
                if (subResult == null || subResult.count < missing) {
                    logger.debug("Cannot craft '{}': missing {} of '{}'", match.instructionSet.outputItemId, missing, itemId)
                    return false
                }
            }
        }

        // Pull ingredients from network storage into a temporary holding
        // We verify they exist, then extract and craft
        val extractedItems = mutableListOf<Pair<String, Int>>()
        for ((itemId, needed) in ingredientCounts) {
            val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
            var remaining = needed
            for (card in storageCards) {
                if (remaining <= 0) break
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                val extracted = PlatformServices.storage.moveItems(
                    storage, storage, // "extract" by counting — we'll actually remove below
                    { false }, // don't actually move anything here
                    0
                )
                // Actually extract by moving to nowhere — we need a different approach
                // Since we can't extract to a void, we verify count and trust the operation
                remaining = 0 // trust that ingredients exist (we checked above)
            }
            extractedItems.add(itemId to needed)
        }

        // Build the crafting input and get the result
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = Identifier.tryParse(itemId) ?: return false
                val item = BuiltInRegistries.ITEM.getValue(id) ?: return false
                ItemStack(item, 1)
            }
        }
        val craftingInput = CraftingInput.of(3, 3, items)

        val recipeManager = level.recipeAccess() as? RecipeManager ?: return false
        val result = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, craftingInput, level)
            .map { it.value().assemble(craftingInput, level.registryAccess()) }
            .orElse(ItemStack.EMPTY)

        if (result.isEmpty) {
            logger.debug("No valid crafting recipe for instruction set")
            return false
        }

        // Remove ingredients from network storage
        for ((itemId, needed) in ingredientCounts) {
            var remaining = needed.toLong()
            for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
                if (remaining <= 0) break
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                // Create a temporary destination that discards items (extract from source)
                val removed = PlatformServices.storage.extractItems(storage, { CardHandle.matchesFilter(it, itemId) }, remaining)
                remaining -= removed
            }
            if (remaining > 0) {
                logger.warn("Failed to extract all ingredients for crafting")
                return false
            }
        }

        // Insert crafted result into network storage
        val resultItemId = BuiltInRegistries.ITEM.getKey(result.item)?.toString() ?: return false
        val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, result)
        if (inserted == 0) {
            logger.debug("Network storage full, cannot insert crafted item")
            return false
        }

        return true
    }

    private fun getRecipeOutputCount(recipe: List<String>, level: ServerLevel): Int {
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = Identifier.tryParse(itemId) ?: return 1
                val item = BuiltInRegistries.ITEM.getValue(id) ?: return 1
                ItemStack(item, 1)
            }
        }
        val input = CraftingInput.of(3, 3, items)
        val recipeManager = level.recipeAccess() as? RecipeManager ?: return 1
        return recipeManager
            .getRecipeFor(RecipeType.CRAFTING, input, level)
            .map { it.value().assemble(input, level.registryAccess()).count }
            .orElse(1)
    }
}
