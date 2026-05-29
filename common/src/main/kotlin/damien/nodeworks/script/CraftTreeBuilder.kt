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
        /** Component patch identifying the requested variant. Empty for
         *  plain items (the common case). Carries through to the planner so
         *  Pull ops can filter storage by component, e.g. asking specifically
         *  for a Strength Potion rather than "any potion".
         *
         *  Nullable for the default value to avoid eagerly loading
         *  [net.minecraft.core.component.DataComponentPatch] when a
         *  [CraftTreeNode] is constructed without specifying a patch (which
         *  is what the unit tests in `:common` do, since their runtime
         *  classpath is MC-free). Read sites should fall back to
         *  [net.minecraft.core.component.DataComponentPatch.EMPTY] when
         *  null. */
        val componentsPatch: net.minecraft.core.component.DataComponentPatch? = null,
        /** Component-aware buffer key (itemId + componentsHash). Convenience
         *  accessor for the planner's variant-keyed aggregation. The default
         *  expression branches on [componentsPatch] being null to avoid
         *  loading MC classes when no patch was supplied (test contexts). */
        val bufferKey: damien.nodeworks.script.BufferKey.Key =
            damien.nodeworks.script.BufferKey.Key(
                itemId,
                if (componentsPatch == null) ""
                else damien.nodeworks.script.BufferKey.componentsHash(componentsPatch),
            ),
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
        visited: MutableSet<damien.nodeworks.script.BufferKey.Key> = mutableSetOf(),
        reserved: MutableMap<String, Int> = mutableMapOf(),
        /** Component patch of the top-level requested variant. Empty for
         *  plain crafts (the common case). Used by the recipe lookup so
         *  multiple Processing Set recipes producing the same base item
         *  (different potion types all output `minecraft:potion`) can be
         *  disambiguated. */
        componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ): CraftTreeNode {
        val node = buildInternal(itemId, count, level, snapshot, depth, visited, reserved, componentsPatch)
        if (depth == 0) assignNodeIds(node)
        return node
    }

    private fun buildInternal(
        itemId: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        visited: MutableSet<damien.nodeworks.script.BufferKey.Key>,
        reserved: MutableMap<String, Int>,
        componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ): CraftTreeNode {
        if (depth > 20) {
            return CraftTreeNode(itemId, getItemName(itemId, componentsPatch), count, "missing", "", "recursion limit", 0, emptyList(), componentsPatch = componentsPatch)
        }

        val itemName = getItemName(itemId, componentsPatch)
        val inStorageTotal = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
        val reservedAmount = reserved[itemId] ?: 0
        val availableFromStorage = maxOf(0, inStorageTotal - reservedAmount)

        // Prevent infinite loops for circular recipes. Key by (itemId,
        // componentsHash) so a potion-processing chain where one variant
        // crafts another (Strength <- Awkward) isn't flagged circular just
        // because they share `minecraft:potion`.
        val variantKey = damien.nodeworks.script.BufferKey.Key(
            itemId, damien.nodeworks.script.BufferKey.componentsHash(componentsPatch),
        )
        if (variantKey in visited) {
            return CraftTreeNode(itemId, itemName, count, "storage", "", "circular", availableFromStorage, emptyList(), componentsPatch = componentsPatch)
        }
        visited.add(variantKey)

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

            visited.remove(variantKey)
            return CraftTreeNode(
                itemId, itemName, actualCount, "craft_template", alias, "", availableFromStorage, children,
                resolvedRecipe = if (swapped) concreteRecipe else null,
                componentsPatch = componentsPatch,
            )
        }

        // 2. Try Processing Set. When the request carries a component patch
        //    use the component-aware lookup so a Strength-potion request
        //    doesn't get routed to the Fire-resistance recipe. When multiple
        //    recipes produce the same variant (e.g. raw_iron → strength and
        //    awkward_potion → strength both on the network), pick the one
        //    whose handler is bound AND whose inputs are reachable. The
        //    naive "first match" routinely picked an unhandled or
        //    unsatisfiable recipe and surfaced confusing errors.
        val apiMatch = if (componentsPatch.size() > 0) {
            pickBestRecipe(
                snapshot.findAllProcessingApisByOutput(itemId, componentsPatch),
                level, snapshot,
            )
        } else {
            snapshot.findProcessingApi(itemId)
        }
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
            // A recipe is considered "handled" when EITHER a Lua handler is
            // registered on a connected Terminal OR a Processing Handler
            // block is bound to it on the network the recipe lives on. For a
            // local API that's [snapshot.networkId]; for a subnet API the
            // handler is registered against the provider network's id, which
            // [ProcessingApiSnapshot.remoteNetworkId] carries through from
            // the broadcast antenna's discovery.
            val handlerNetworkId = if (isSubnet) apiMatch.apiStorage.remoteNetworkId else snapshot.networkId
            val hasBlockHandler =
                damien.nodeworks.script.cpu.BlockHandlerRegistry.find(handlerNetworkId, api.name) != null
            val hasHandler = handlerEngine != null || hasBlockHandler

            // Processing APIs can yield >1 per batch (e.g. a smelting handler that produces
            // 9 nuggets per ingot). Round request up to a whole batch, same as Instruction Sets.
            val matchingOutput = api.outputs.firstOrNull { it.itemId == itemId }
            val perBatch = matchingOutput?.count?.coerceAtLeast(1) ?: 1
            val batches = (count + perBatch - 1) / perBatch
            val actualCount = batches * perBatch

            val children = api.inputs.flatMap { ingr ->
                // Pass through each ingredient's componentsPatch so the
                // recursive resolveIngredient call (which generates Pull op
                // sources) can ask for the specific variant. Without this, a
                // recipe taking 1 Swiftness + 1 Fire Resistance would aggregate
                // both as "2 minecraft:potion" and pull two random potions.
                resolveIngredient(
                    ingr.itemId, ingr.count * batches, level, snapshot, depth, visited, reserved,
                    ingredientPatch = ingr.stack.componentsPatch,
                )
            }

            // Prefer the caller's patch when supplied. Otherwise inherit the
            // recipe's matching output patch so a request for plain
            // "minecraft:potion" still shows the produced variant (e.g.
            // Strength) on the tree root.
            val rootPatch = if (componentsPatch.size() > 0) componentsPatch
                else matchingOutput?.stack?.componentsPatch ?: net.minecraft.core.component.DataComponentPatch.EMPTY
            val rootName = if (componentsPatch.size() > 0) itemName else getItemName(itemId, rootPatch)
            val source = if (hasHandler) "process_template" else "process_no_handler"
            visited.remove(variantKey)
            return CraftTreeNode(
                itemId, rootName, actualCount, source, api.name, resolvedBy, availableFromStorage, children,
                componentsPatch = rootPatch,
            )
        }

        // 3. Fall back to storage, but only for the portion that isn't already reserved
        if (availableFromStorage >= count) {
            reserved[itemId] = reservedAmount + count
            visited.remove(variantKey)
            return CraftTreeNode(
                itemId, itemName, count, "storage", "", "storage", availableFromStorage, emptyList(),
                componentsPatch = componentsPatch,
            )
        }

        // 4. No recipe and no (unreserved) storage, genuinely missing
        visited.remove(variantKey)
        return CraftTreeNode(
            itemId, itemName, count, "missing", "", "", availableFromStorage, emptyList(),
            componentsPatch = componentsPatch,
        )
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
        visited: MutableSet<damien.nodeworks.script.BufferKey.Key>,
        reserved: MutableMap<String, Int>,
        /** Component patch the ingredient declares. Empty = match any variant
         *  by itemId only (the plain-recipe path).
         *  When non-empty, the storage count and the reservation tracker key
         *  on the full [BufferKey.Key] so two ingredient slots demanding
         *  different variants of the same itemId (e.g. swiftness + fire-res
         *  potion) don't double-count or double-reserve the same storage items. */
        ingredientPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ): List<CraftTreeNode> {
        val componentsHash = damien.nodeworks.script.BufferKey.componentsHash(ingredientPatch)
        // Reservation key embeds the components hash so different variants of
        // one itemId carve independent reservation buckets. Plain ingredients
        // (empty hash) keep the old `itemId` key so existing reservation
        // counts roll forward without migration.
        val reservedKey = if (componentsHash.isEmpty()) ingId else "$ingId#$componentsHash"
        val ingInStorage = if (componentsHash.isEmpty()) {
            NetworkStorageHelper.countItems(level, snapshot, ingId).toInt()
        } else {
            NetworkStorageHelper.countVariantAcrossNetwork(level, snapshot, ingId, ingredientPatch).toInt()
        }
        val ingReserved = reserved[reservedKey] ?: 0
        val ingAvailable = maxOf(0, ingInStorage - ingReserved)

        return when {
            ingAvailable >= needed -> {
                reserved[reservedKey] = ingReserved + needed
                listOf(CraftTreeNode(
                    ingId, getItemName(ingId, ingredientPatch), needed, "storage", "", "storage", ingAvailable, emptyList(),
                    componentsPatch = ingredientPatch,
                ))
            }
            ingAvailable > 0 -> {
                reserved[reservedKey] = ingReserved + ingAvailable
                val fromStorage = CraftTreeNode(
                    ingId, getItemName(ingId, ingredientPatch), ingAvailable, "storage", "", "storage", ingAvailable, emptyList(),
                    componentsPatch = ingredientPatch,
                )
                val toCraft = needed - ingAvailable
                val crafted = buildInternal(
                    ingId, toCraft, level, snapshot, depth + 1, visited, reserved, ingredientPatch,
                )
                listOf(fromStorage, crafted)
            }
            else -> listOf(buildInternal(
                ingId, needed, level, snapshot, depth + 1, visited, reserved, ingredientPatch,
            ))
        }
    }

    /** Rank competing recipes that all produce the same requested variant.
     *  Returns the best candidate, or null if [candidates] is empty.
     *
     *  Score (higher is better):
     *   - +10 if a handler is bound for this recipe on its hosting network
     *   - +1  per recipe input that storage can supply
     *
     *  Ties are broken by network walk order (first match wins). This avoids
     *  the "two recipes both produce strength potion, planner picks the
     *  unhandled or unsatisfiable one and surfaces a confusing missing-input
     *  / missing-handler error" failure mode. When all candidates are
     *  equally bad, returns the first so the resulting error message is at
     *  least deterministic. */
    private fun pickBestRecipe(
        candidates: List<damien.nodeworks.network.ProcessingApiMatch>,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
    ): damien.nodeworks.network.ProcessingApiMatch? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        var best: damien.nodeworks.network.ProcessingApiMatch? = null
        var bestScore = Int.MIN_VALUE
        for (match in candidates) {
            val api = match.api
            val isSubnet = match.apiStorage.remoteTerminalPositions != null
            val handlerNetworkId = if (isSubnet) match.apiStorage.remoteNetworkId else snapshot.networkId
            val hasLuaHandler = PlatformServices.modState.findProcessingEngine(
                level,
                match.apiStorage.remoteTerminalPositions ?: snapshot.terminalPositions,
                api.name,
                match.apiStorage.remoteDimension,
            ) != null
            val hasBlockHandler = damien.nodeworks.script.cpu.BlockHandlerRegistry.find(handlerNetworkId, api.name) != null
            var score = 0
            if (hasLuaHandler || hasBlockHandler) score += 10
            for (ingr in api.inputs) {
                val available = if (ingr.componentsHash.isEmpty()) {
                    NetworkStorageHelper.countItems(level, snapshot, ingr.itemId)
                } else {
                    NetworkStorageHelper.countVariantAcrossNetwork(level, snapshot, ingr.itemId, ingr.stack.componentsPatch)
                }
                if (available >= ingr.count.toLong()) score += 1
            }
            if (score > bestScore) {
                bestScore = score
                best = match
            }
        }
        return best ?: candidates.first()
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
            // Map the slot's authored item to its ingredient by candidate
            // membership. Indexing by slot doesn't work, the recipe's
            // [PlacementInfo] is laid out over its own bounding box, not the
            // user's 3×3 grid, so a single-ingredient recipe authored in
            // anywhere but slot 0 would otherwise miss its ingredient.
            val ingredientIdx = candidatesPerIngredient.indexOfFirst { it.contains(originalId) }
            if (ingredientIdx < 0) continue
            val candidates = candidatesPerIngredient[ingredientIdx]

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

    private fun getItemName(
        itemId: String,
        componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ): String {
        val id = Identifier.tryParse(itemId) ?: return itemId.substringAfter(':')
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return itemId.substringAfter(':')
        val stack = ItemStack(item).apply {
            if (componentsPatch.size() > 0) applyComponents(componentsPatch)
        }
        return stack.hoverName.string
    }
}
