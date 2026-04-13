package damien.nodeworks.script

import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * Builds a craft dependency tree without actually crafting.
 * Pure read-only traversal of the recipe graph.
 */
object CraftTreeBuilder {

    data class CraftTreeNode(
        val itemId: String,
        val itemName: String,
        val count: Int,
        val source: String,       // "craft_template", "process_template", "storage", "missing"
        val templateName: String, // instruction set alias or processing set name
        val resolvedBy: String,   // "local", "subnet", "storage", ""
        val inStorage: Int,       // how many are currently in network storage
        val children: List<CraftTreeNode>
    )

    fun buildCraftTree(
        itemId: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int = 0,
        visited: MutableSet<String> = mutableSetOf(),
        reserved: MutableMap<String, Int> = mutableMapOf()
    ): CraftTreeNode {
        if (depth > 20) {
            return CraftTreeNode(itemId, getItemName(itemId), count, "missing", "", "recursion limit", 0, emptyList())
        }

        val itemName = getItemName(itemId)
        val inStorageTotal = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
        val reservedAmount = reserved[itemId] ?: 0
        val availableFromStorage = maxOf(0, inStorageTotal - reservedAmount)

        // Prevent infinite loops for circular recipes
        if (itemId in visited) {
            return CraftTreeNode(itemId, itemName, count, "storage", "", "circular", availableFromStorage, emptyList())
        }
        visited.add(itemId)

        // 1. Try Instruction Set (3x3 crafting)
        val instructionMatch = snapshot.findInstructionSet(itemId)
        if (instructionMatch != null) {
            val recipe = instructionMatch.instructionSet.recipe
            val alias = instructionMatch.instructionSet.alias ?: instructionMatch.instructionSet.outputItemId.substringAfter(':')

            // Count ingredients
            val ingredientCounts = mutableMapOf<String, Int>()
            for (ingredient in recipe) {
                if (ingredient.isEmpty()) continue
                ingredientCounts[ingredient] = (ingredientCounts[ingredient] ?: 0) + 1
            }

            // Recipes can yield >1 per craft (e.g. 1 ingot → 9 nuggets). Scale ingredient
            // demand by the number of crafts actually needed, and round the node's own
            // count up to a full batch — you can't craft fractions, so a request for 1
            // nugget via a 1→9 recipe actually produces (and delivers) 9.
            val perBatch = resolveRecipeOutputCount(recipe, level).coerceAtLeast(1)
            val batches = (count + perBatch - 1) / perBatch
            val actualCount = batches * perBatch

            val children = ingredientCounts.flatMap { (ingId, ingCount) ->
                resolveIngredient(ingId, ingCount * batches, level, snapshot, depth, visited, reserved)
            }

            visited.remove(itemId)
            return CraftTreeNode(itemId, itemName, actualCount, "craft_template", alias, "", availableFromStorage, children)
        }

        // 2. Try Processing Set
        val apiMatch = snapshot.findProcessingApi(itemId)
        if (apiMatch != null) {
            val api = apiMatch.api
            val isSubnet = apiMatch.apiStorage.remoteTerminalPositions != null
            val resolvedBy = if (isSubnet) {
                val subnetName = findSubnetName(level, apiMatch.apiStorage.pos)
                if (subnetName.isNotEmpty()) "subnet: $subnetName" else "subnet"
            } else "local"

            val searchPositions = apiMatch.apiStorage.remoteTerminalPositions ?: snapshot.terminalPositions
            val handlerEngine = PlatformServices.modState.findProcessingEngine(level, searchPositions, api.name)
            val hasHandler = handlerEngine != null

            // Processing APIs can yield >1 per batch (e.g. a smelting handler that produces
            // 9 nuggets per ingot). Round request up to a whole batch, same as Instruction Sets.
            val perBatch = api.outputs.firstOrNull { it.first == itemId }?.second?.coerceAtLeast(1) ?: 1
            val batches = (count + perBatch - 1) / perBatch
            val actualCount = batches * perBatch

            val children = api.inputs.flatMap { (ingId, ingCount) ->
                resolveIngredient(ingId, ingCount * batches, level, snapshot, depth, visited, reserved)
            }

            val source = if (hasHandler) "process_template" else "process_no_handler"
            visited.remove(itemId)
            return CraftTreeNode(itemId, itemName, actualCount, source, api.name, resolvedBy, availableFromStorage, children)
        }

        // 3. Fall back to storage — but only for the portion that isn't already reserved
        if (availableFromStorage >= count) {
            reserved[itemId] = reservedAmount + count
            visited.remove(itemId)
            return CraftTreeNode(itemId, itemName, count, "storage", "", "storage", availableFromStorage, emptyList())
        }

        // 4. No recipe and no (unreserved) storage — genuinely missing
        visited.remove(itemId)
        return CraftTreeNode(itemId, itemName, count, "missing", "", "", availableFromStorage, emptyList())
    }

    /**
     * Resolve a single ingredient request: split into a "from storage" node and/or a
     * recursive "to craft" subtree, respecting the reservation map to prevent double-counting
     * the same storage items across sibling ingredient requests.
     */
    private fun resolveIngredient(
        ingId: String,
        needed: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        visited: MutableSet<String>,
        reserved: MutableMap<String, Int>
    ): List<CraftTreeNode> {
        val ingInStorage = NetworkStorageHelper.countItems(level, snapshot, ingId).toInt()
        val ingReserved = reserved[ingId] ?: 0
        val ingAvailable = maxOf(0, ingInStorage - ingReserved)

        return when {
            ingAvailable >= needed -> {
                reserved[ingId] = ingReserved + needed
                listOf(CraftTreeNode(ingId, getItemName(ingId), needed, "storage", "", "storage", ingAvailable, emptyList()))
            }
            ingAvailable > 0 -> {
                reserved[ingId] = ingReserved + ingAvailable
                val fromStorage = CraftTreeNode(ingId, getItemName(ingId), ingAvailable, "storage", "", "storage", ingAvailable, emptyList())
                val toCraft = needed - ingAvailable
                val crafted = buildCraftTree(ingId, toCraft, level, snapshot, depth + 1, visited, reserved)
                listOf(fromStorage, crafted)
            }
            else -> listOf(buildCraftTree(ingId, needed, level, snapshot, depth + 1, visited, reserved))
        }
    }

    /** Find the network name of the subnet that a broadcast antenna belongs to. */
    private fun findSubnetName(level: ServerLevel, broadcastPos: net.minecraft.core.BlockPos): String {
        // The broadcast antenna is adjacent to processing storage on the provider subnet.
        // Walk adjacent blocks to find a connectable, then discover that network for its controller name.
        for (dir in net.minecraft.core.Direction.entries) {
            val adjPos = broadcastPos.relative(dir)
            if (!level.isLoaded(adjPos)) continue
            val connectable = damien.nodeworks.network.NodeConnectionHelper.getConnectable(level, adjPos)
            if (connectable != null) {
                val subnetSnapshot = NetworkDiscovery.discoverNetwork(level, adjPos)
                val controller = subnetSnapshot.controller
                if (controller != null) {
                    val controllerEntity = level.getBlockEntity(controller.pos) as? damien.nodeworks.block.entity.NetworkControllerBlockEntity
                    if (controllerEntity != null && controllerEntity.networkName.isNotEmpty()) {
                        return controllerEntity.networkName
                    }
                }
                break
            }
        }
        return ""
    }

    /** Assemble the 9-slot pattern against the vanilla RecipeManager and return the per-craft
     *  output count. Returns 1 if no matching recipe (safe default — planner will still fail
     *  downstream with a clearer error). */
    private fun resolveRecipeOutputCount(recipe: List<String>, level: ServerLevel): Int {
        val rm = level.recipeManager ?: return 1
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = ResourceLocation.tryParse(itemId) ?: return 1
                val item = BuiltInRegistries.ITEM.get(id) ?: return 1
                ItemStack(item, 1)
            }
        }
        val input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, items)
        val holder = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level).orElse(null)
            ?: return 1
        val result = holder.value().assemble(input, level.registryAccess())
        return if (result.isEmpty) 1 else result.count
    }

    private fun getItemName(itemId: String): String {
        val id = ResourceLocation.tryParse(itemId) ?: return itemId.substringAfter(':')
        val item = BuiltInRegistries.ITEM.get(id) ?: return itemId.substringAfter(':')
        return ItemStack(item).hoverName.string
    }
}
