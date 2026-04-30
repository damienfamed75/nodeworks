package damien.nodeworks.script

import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType

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
        val children: List<CraftTreeNode>,
        /** Concretized 9-slot recipe pattern for craft_template nodes when the
         *  source [InstructionSet] had substitutions enabled and an ingredient
         *  was swapped for an alternative the network actually has. Null means
         *  "use the Instruction Set's stored recipe verbatim", which is also
         *  the default for substitution-disabled patterns. */
        val resolvedRecipe: List<String>? = null,
    ) {
        /** Stable identity within this tree, assigned by [assignNodeIds] after construction
         *  and serialized so the client can match server-side op activity to specific nodes
         *  (avoids the "both iron branches highlight" ambiguity of itemId-based matching). */
        var nodeId: Int = -1
    }

    /** Walk [root] depth-first and assign sequential [CraftTreeNode.nodeId] values. */
    fun assignNodeIds(root: CraftTreeNode) {
        var counter = 0
        val stack = ArrayDeque<CraftTreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            n.nodeId = counter++
            // Children pushed in reverse so the assigned order matches a normal pre-order walk.
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }

    fun buildCraftTree(
        itemId: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int = 0,
        visited: MutableSet<String> = mutableSetOf(),
        reserved: MutableMap<String, Int> = mutableMapOf()
    ): CraftTreeNode {
        val node = buildInternal(itemId, count, level, snapshot, depth, visited, reserved)
        if (depth == 0) assignNodeIds(node)
        return node
    }

    private fun buildInternal(
        itemId: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        visited: MutableSet<String>,
        reserved: MutableMap<String, Int>
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
            val info = instructionMatch.instructionSet
            val originalRecipe = info.recipe
            val alias = info.alias ?: info.outputItemId.substringAfter(':')

            // Recipes can yield >1 per craft (e.g. 1 ingot → 9 nuggets). Scale ingredient
            // demand by the number of crafts actually needed, and round the node's own
            // count up to a full batch, you can't craft fractions, so a request for 1
            // nugget via a 1→9 recipe actually produces (and delivers) 9.
            val perBatch = resolveRecipeOutputCount(originalRecipe, level).coerceAtLeast(1)
            val batches = (count + perBatch - 1) / perBatch
            val actualCount = batches * perBatch

            // Swap exemplar ingredients for in-stock alternatives when the
            // pattern allows substitutions. No-op when substitutions are off.
            val (concreteRecipe, swapped) = resolveSubstitutedRecipe(
                originalRecipe, info.allowSubstitutions, level, snapshot, batches, reserved,
            )

            val ingredientCounts = mutableMapOf<String, Int>()
            for (ingredient in concreteRecipe) {
                if (ingredient.isEmpty()) continue
                ingredientCounts[ingredient] = (ingredientCounts[ingredient] ?: 0) + 1
            }

            val children = ingredientCounts.flatMap { (ingId, ingCount) ->
                resolveIngredient(ingId, ingCount * batches, level, snapshot, depth, visited, reserved)
            }

            visited.remove(itemId)
            return CraftTreeNode(
                itemId, itemName, actualCount, "craft_template", alias, "", availableFromStorage, children,
                resolvedRecipe = if (swapped) concreteRecipe else null,
            )
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
            val handlerEngine = PlatformServices.modState.findProcessingEngine(
                level, searchPositions, api.name, apiMatch.apiStorage.remoteDimension
            )
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

        // 3. Fall back to storage, but only for the portion that isn't already reserved
        if (availableFromStorage >= count) {
            reserved[itemId] = reservedAmount + count
            visited.remove(itemId)
            return CraftTreeNode(itemId, itemName, count, "storage", "", "storage", availableFromStorage, emptyList())
        }

        // 4. No recipe and no (unreserved) storage, genuinely missing
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
                val crafted = buildInternal(ingId, toCraft, level, snapshot, depth + 1, visited, reserved)
                listOf(fromStorage, crafted)
            }
            else -> listOf(buildInternal(ingId, needed, level, snapshot, depth + 1, visited, reserved))
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

    /** Substitution-aware version of the 9-slot recipe: walks each grid slot
     *  and swaps the exemplar item for a stocked alternative the recipe's
     *  `Ingredient` accepts. Tracks running per-slot consumption so a single
     *  recipe with 8 plank slots can spread across "4 oak + 4 birch" rather
     *  than picking 8 of one type that only has 4 in stock. No-op when the
     *  pattern has substitutions disabled. */
    private fun resolveSubstitutedRecipe(
        recipe: List<String>,
        allowSubstitutions: Boolean,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        batches: Int,
        reserved: MutableMap<String, Int>,
    ): Pair<List<String>, Boolean> {
        if (!allowSubstitutions) return recipe to false

        val rm = level.recipeAccess() ?: return recipe to false
        val exemplarItems = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = Identifier.tryParse(itemId) ?: return recipe to false
                val item = BuiltInRegistries.ITEM.getValue(id) ?: return recipe to false
                ItemStack(item, 1)
            }
        }
        val input = CraftingInput.of(3, 3, exemplarItems)
        val holder = rm.getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null)
            ?: return recipe to false
        val placement = holder.value().placementInfo()
        // PlacementInfo de-duplicates ingredients (the chest's eight `#planks`
        // slots collapse to one entry), so we have to map slot index back to
        // its ingredient via [slotsToIngredientIndex].
        val slotsToIngredient = placement.slotsToIngredientIndex()
        val candidatesPerIngredient: List<List<String>> = placement.ingredients().map { ing ->
            @Suppress("DEPRECATION")
            ing.items().toList().mapNotNull { BuiltInRegistries.ITEM.getKey(it.value())?.toString() }
        }

        // Local reservation grows as we commit slots within this recipe and
        // composes with the outer [reserved] map so the same stock isn't
        // double-claimed across slots, sibling tree nodes, or both.
        val local = HashMap<String, Int>()
        fun availableFor(id: String): Int {
            val total = NetworkStorageHelper.countItems(level, snapshot, id).toInt()
            return maxOf(0, total - (reserved[id] ?: 0) - (local[id] ?: 0))
        }

        val concrete = recipe.toMutableList()
        var swapped = false
        for (slot in 0 until 9) {
            val originalId = concrete[slot]
            if (originalId.isEmpty()) continue
            val ingredientIdx = slotsToIngredient.getInt(slot)
            if (ingredientIdx < 0) continue
            val candidates = candidatesPerIngredient.getOrNull(ingredientIdx) ?: continue

            val choice = pickSlotCandidate(originalId, candidates, batches, ::availableFor) ?: continue
            if (choice != originalId) swapped = true
            concrete[slot] = choice
            local[choice] = (local[choice] ?: 0) + batches
        }
        return concrete to swapped
    }

    /** Pick a candidate item id with at least [needed] available. Prefers
     *  [preferred] when it still has stock, so authored recipes keep their
     *  intended item when nothing forces a swap. Otherwise picks whichever
     *  alternative has the most stock left, keeping the tree's unique-types
     *  count from growing more than necessary. */
    private fun pickSlotCandidate(
        preferred: String,
        candidates: List<String>,
        needed: Int,
        availableFor: (String) -> Int,
    ): String? {
        if (availableFor(preferred) >= needed) return preferred
        return candidates
            .filter { it != preferred }
            .maxByOrNull { availableFor(it) }
            ?.takeIf { availableFor(it) >= needed }
            ?: candidates.firstOrNull { availableFor(it) > 0 }
    }

    /** Assemble the 9-slot pattern against the vanilla RecipeManager and return the per-craft
     *  output count. Returns 1 if no matching recipe (safe default, planner will still fail
     *  downstream with a clearer error). */
    private fun resolveRecipeOutputCount(recipe: List<String>, level: ServerLevel): Int {
        val rm = level.recipeAccess() ?: return 1
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = Identifier.tryParse(itemId) ?: return 1
                val item = BuiltInRegistries.ITEM.getValue(id) ?: return 1
                ItemStack(item, 1)
            }
        }
        val input = CraftingInput.of(3, 3, items)
        val holder = rm.getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null)
            ?: return 1
        val result = holder.value().assemble(input)
        return if (result.isEmpty) 1 else result.getCount()
    }

    private fun getItemName(itemId: String): String {
        val id = Identifier.tryParse(itemId) ?: return itemId.substringAfter(':')
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return itemId.substringAfter(':')
        return ItemStack(item).hoverName.string
    }
}
