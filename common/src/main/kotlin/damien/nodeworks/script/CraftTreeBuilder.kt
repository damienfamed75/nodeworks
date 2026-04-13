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

            val children = ingredientCounts.flatMap { (ingId, ingCount) ->
                resolveIngredient(ingId, ingCount * count, level, snapshot, depth, visited, reserved)
            }

            visited.remove(itemId)
            return CraftTreeNode(itemId, itemName, count, "craft_template", alias, "", availableFromStorage, children)
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

            val children = api.inputs.flatMap { (ingId, ingCount) ->
                resolveIngredient(ingId, ingCount * count, level, snapshot, depth, visited, reserved)
            }

            val source = if (hasHandler) "process_template" else "process_no_handler"
            visited.remove(itemId)
            return CraftTreeNode(itemId, itemName, count, source, api.name, resolvedBy, availableFromStorage, children)
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

    private fun getItemName(itemId: String): String {
        val id = ResourceLocation.tryParse(itemId) ?: return itemId.substringAfter(':')
        val item = BuiltInRegistries.ITEM.get(id) ?: return itemId.substringAfter(':')
        return ItemStack(item).hoverName.string
    }
}
