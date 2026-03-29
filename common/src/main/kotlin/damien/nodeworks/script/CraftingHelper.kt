package damien.nodeworks.script

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.network.InstructionSetMatch
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import org.slf4j.LoggerFactory

/**
 * Handles crafting execution for `network:craft()`.
 * Uses a Crafting CPU's internal buffer to hold items during crafting,
 * preventing race conditions between concurrent craft operations.
 */
object CraftingHelper {

    private val logger = LoggerFactory.getLogger("nodeworks-crafting")

    data class CraftResult(
        val outputItemId: String,
        val outputName: String,
        val count: Int
    )

    /**
     * Craft items matching the given identifier.
     * Finds an available Crafting CPU, extracts ingredients into its buffer,
     * crafts, and inserts results into network storage.
     *
     * @param identifier The Instruction Set alias or output item ID
     * @param count Number of crafting operations to perform
     * @param level The server level
     * @param snapshot Network snapshot
     * @param depth Recursion depth guard
     * @param cache Optional inventory cache for UI updates
     * @param cpuPos Optional: force a specific CPU (for recursive calls that reuse the same CPU)
     */
    fun craft(
        identifier: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int = 0,
        cache: NetworkInventoryCache? = null,
        cpuPos: BlockPos? = null
    ): CraftResult? {
        if (depth > 20) {
            logger.warn("Crafting recursion depth exceeded for '{}'", identifier)
            return null
        }

        // Find a CPU — reuse the one passed in (recursive call) or find a new one
        val cpu: CraftingCoreBlockEntity
        if (cpuPos != null) {
            cpu = level.getBlockEntity(cpuPos) as? CraftingCoreBlockEntity ?: return null
        } else {
            val cpuSnapshot = snapshot.findAvailableCpu() ?: run {
                logger.debug("No available Crafting CPU on network")
                return null
            }
            cpu = level.getBlockEntity(cpuSnapshot.pos) as? CraftingCoreBlockEntity ?: return null
            if (!cpu.isFormed) {
                logger.debug("Crafting CPU is not formed (no storage blocks)")
                return null
            }
            cpu.setCrafting(true)
        }

        val match = snapshot.findInstructionSet(identifier) ?: run {
            if (cpuPos == null) finishCrafting(cpu, level, snapshot, cache)
            return null
        }
        val recipe = match.instructionSet.recipe

        var totalCrafted = 0
        for (i in 0 until count) {
            if (!craftOnce(recipe, match, level, snapshot, depth, cache, cpu)) break
            totalCrafted++
        }

        // Only the top-level call cleans up the CPU
        if (cpuPos == null) {
            finishCrafting(cpu, level, snapshot, cache)
        }

        if (totalCrafted == 0) return null

        val outputId = match.instructionSet.outputItemId
        val outputIdentifier = ResourceLocation.tryParse(outputId) ?: return null
        val outputItem = BuiltInRegistries.ITEM.get(outputIdentifier) ?: return null
        val outputName = ItemStack(outputItem).hoverName.string
        val outputPerCraft = getRecipeOutputCount(recipe, level)

        return CraftResult(outputId, outputName, totalCrafted * outputPerCraft)
    }

    /**
     * Finish crafting: return any leftover buffer items to network storage and mark CPU idle.
     */
    private fun finishCrafting(
        cpu: CraftingCoreBlockEntity,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache?
    ) {
        // Return leftover buffer contents to network storage
        val leftovers = cpu.clearBuffer()
        for ((itemId, count) in leftovers) {
            val id = ResourceLocation.tryParse(itemId) ?: continue
            val item = BuiltInRegistries.ITEM.get(id) ?: continue
            var remaining = count
            while (remaining > 0) {
                val batchSize = minOf(remaining, item.getDefaultMaxStackSize())
                val stack = ItemStack(item, batchSize)
                val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, cache)
                remaining -= inserted
                if (inserted == 0) {
                    logger.warn("Could not return {} x{} from CPU buffer to storage", itemId, remaining)
                    break
                }
            }
        }
        cpu.setCrafting(false)
    }

    /**
     * Execute one crafting operation using the CPU buffer.
     */
    private fun craftOnce(
        recipe: List<String>,
        match: InstructionSetMatch,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        cache: NetworkInventoryCache?,
        cpu: CraftingCoreBlockEntity
    ): Boolean {
        // Count ingredients needed
        val ingredientCounts = mutableMapOf<String, Int>()
        for (itemId in recipe) {
            if (itemId.isEmpty()) continue
            ingredientCounts[itemId] = (ingredientCounts[itemId] ?: 0) + 1
        }

        // For each ingredient, check buffer + storage. Recursively craft prerequisites if needed.
        for ((itemId, needed) in ingredientCounts) {
            val inBuffer = cpu.getBufferCount(itemId)
            val inStorage = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            var have = inBuffer + inStorage

            while (have < needed) {
                // Try to recursively craft the missing ingredient (reuse same CPU)
                val subResult = craft(itemId, 1, level, snapshot, depth + 1, cache, cpu.blockPos)
                if (subResult == null || subResult.count == 0) {
                    logger.debug("Cannot craft '{}': unable to craft prerequisite '{}'",
                        match.instructionSet.outputItemId, itemId)
                    return false
                }
                // Re-check: sub-craft results go into storage, so check both buffer + storage
                have = cpu.getBufferCount(itemId) + NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            }
        }

        // Extract ingredients from storage into CPU buffer
        for ((itemId, needed) in ingredientCounts) {
            // First use what's already in the buffer
            val inBuffer = cpu.getBufferCount(itemId)
            val fromStorage = maxOf(0, needed - inBuffer)

            if (fromStorage > 0) {
                // Extract from network storage into buffer
                var remaining = fromStorage.toLong()
                for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
                    if (remaining <= 0) break
                    val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                    val extracted = PlatformServices.storage.extractItems(
                        storage, { CardHandle.matchesFilter(it, itemId) }, remaining
                    )
                    if (extracted > 0) {
                        cpu.addToBuffer(itemId, extracted.toInt())
                        cache?.onExtracted(itemId, false, extracted)
                        remaining -= extracted
                    }
                }
                if (remaining > 0) {
                    logger.warn("Failed to extract all '{}' for crafting", itemId)
                    return false
                }
            }
        }

        // Consume ingredients from buffer
        for ((itemId, needed) in ingredientCounts) {
            val removed = cpu.removeFromBuffer(itemId, needed)
            if (removed < needed) {
                logger.warn("Buffer underflow: needed {} of '{}' but only had {}", needed, itemId, removed)
                return false
            }
        }

        // Build the crafting input and get the result
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = ResourceLocation.tryParse(itemId) ?: return false
                val item = BuiltInRegistries.ITEM.get(id) ?: return false
                ItemStack(item, 1)
            }
        }
        val craftingInput = CraftingInput.of(3, 3, items)

        val recipeManager = level.getRecipeManager() ?: return false
        val result = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, craftingInput, level)
            .map { it.value().assemble(craftingInput, level.registryAccess()) }
            .orElse(ItemStack.EMPTY)

        if (result.isEmpty) {
            logger.debug("No valid crafting recipe for instruction set")
            return false
        }

        // Insert crafted result into network storage
        val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, result, cache)
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
                val id = ResourceLocation.tryParse(itemId) ?: return 1
                val item = BuiltInRegistries.ITEM.get(id) ?: return 1
                ItemStack(item, 1)
            }
        }
        val input = CraftingInput.of(3, 3, items)
        val recipeManager = level.getRecipeManager() ?: return 1
        return recipeManager
            .getRecipeFor(RecipeType.CRAFTING, input, level)
            .map { it.value().assemble(input, level.registryAccess()).count }
            .orElse(1)
    }
}
