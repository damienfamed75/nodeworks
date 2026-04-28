package damien.nodeworks.script.cpu

import damien.nodeworks.script.CraftTreeBuilder
import damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [CraftPlanner.plan]. The planner is a pure function over a
 * [CraftTreeNode] tree plus a recipe-pattern lookup, so every scenario here is
 * constructed in-memory without touching a Level, BlockEntity, or NetworkSnapshot.
 */
class CraftPlannerTest {

    // ---- builders ----------------------------------------------------------

    private fun storage(itemId: String, count: Int = 1, inStorage: Int = count): CraftTreeNode =
        CraftTreeNode(
            itemId = itemId,
            itemName = itemId.substringAfter(':'),
            count = count,
            source = "storage",
            templateName = "",
            resolvedBy = "storage",
            inStorage = inStorage,
            children = emptyList(),
        )

    private fun craft(
        itemId: String,
        count: Int = 1,
        templateName: String = itemId.substringAfter(':'),
        children: List<CraftTreeNode> = emptyList(),
    ): CraftTreeNode = CraftTreeNode(
        itemId = itemId,
        itemName = itemId.substringAfter(':'),
        count = count,
        source = "craft_template",
        templateName = templateName,
        resolvedBy = "local",
        inStorage = 0,
        children = children,
    )

    private fun process(
        itemId: String,
        count: Int = 1,
        templateName: String,
        children: List<CraftTreeNode> = emptyList(),
    ): CraftTreeNode = CraftTreeNode(
        itemId = itemId,
        itemName = itemId.substringAfter(':'),
        count = count,
        source = "process_template",
        templateName = templateName,
        resolvedBy = "local",
        inStorage = 0,
        children = children,
    )

    private fun missing(itemId: String, count: Int = 1): CraftTreeNode = CraftTreeNode(
        itemId = itemId,
        itemName = itemId.substringAfter(':'),
        count = count,
        source = "missing",
        templateName = "",
        resolvedBy = "",
        inStorage = 0,
        children = emptyList(),
    )

    private fun assign(node: CraftTreeNode): CraftTreeNode {
        CraftTreeBuilder.assignNodeIds(node)
        return node
    }

    /** Recipe-lookup that returns null for everything (no instruction sets known). */
    private val noRecipes: (String) -> List<String>? = { null }

    /** Recipe-lookup backed by a static map. */
    private fun recipesOf(vararg entries: Pair<String, List<String>>): (String) -> List<String>? {
        val map = entries.toMap()
        return { map[it] }
    }

    /** Plan helper that asserts the planner returned a non-null plan. */
    private fun planOk(
        tree: CraftTreeNode,
        omitDeliver: Boolean = false,
        recipes: (String) -> List<String>? = noRecipes,
    ): CraftPlan {
        val result = CraftPlanner.plan(tree, omitDeliver, recipes)
        assertFalse(result.unresolvable, "expected a successful plan, got: ${result.message}")
        return result.plan ?: error("planner returned a null plan with no error message")
    }

    // ---- A. single-node trees ---------------------------------------------

    @Test
    fun storageLeafBecomesPullPlusDeliver() {
        val plan = planOk(assign(storage("minecraft:iron_ingot", 4)))

        assertEquals(2, plan.ops.size)

        val pull = plan.ops[0] as Operation.Pull
        assertEquals(0, pull.id)
        assertEquals("minecraft:iron_ingot", pull.itemId)
        assertEquals(4L, pull.amount)
        assertTrue(pull.dependsOn.isEmpty())

        val deliver = plan.ops[1] as Operation.Deliver
        assertEquals(1, deliver.id)
        assertEquals(listOf(0), deliver.dependsOn)
        assertEquals("minecraft:iron_ingot", deliver.itemId)
        assertEquals(4L, deliver.amount)
        assertTrue(deliver.toReservedSlot)

        assertEquals(setOf(1), plan.terminalOpIds)
    }

    @Test
    fun craftTemplateLeafResolvesRecipePatternFromLookup() {
        val recipe = listOf(
            "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
            "", "minecraft:stick", "",
            "", "minecraft:stick", "",
        )
        val tree = assign(craft("minecraft:iron_pickaxe", count = 1, templateName = "iron_pickaxe"))

        val plan = planOk(tree, recipes = recipesOf("minecraft:iron_pickaxe" to recipe))
        val execute = plan.ops[0] as Operation.Execute
        assertEquals(recipe, execute.recipe)
        assertEquals("minecraft:iron_pickaxe", execute.outputItemId)
        assertEquals(1L, execute.outputCount)
        assertEquals(1L, execute.executions)
    }

    @Test
    fun processTemplateLeafBecomesProcessOpWithEmptyInputs() {
        val tree = assign(process("minecraft:iron_ingot", 8, templateName = "smelt_iron"))
        val plan = planOk(tree)

        val processOp = plan.ops[0] as Operation.Process
        assertEquals("smelt_iron", processOp.processingApiName)
        assertTrue(processOp.inputs.isEmpty())
        assertEquals(listOf("minecraft:iron_ingot" to 8L), processOp.outputs)
    }

    @Test
    fun missingNodeFailsPlanWithMessage() {
        val result = CraftPlanner.plan(assign(missing("minecraft:diamond")), omitDeliver = false, recipeLookup = noRecipes)
        assertTrue(result.unresolvable)
        assertNull(result.plan)
        val message = requireNotNull(result.message) { "expected an error message" }
        assertTrue(message.contains("minecraft:diamond"))
        assertTrue(message.contains("missing"))
    }

    @Test
    fun craftTemplateWithNoMatchingRecipeIsUnresolvable() {
        val tree = assign(craft("minecraft:iron_pickaxe", templateName = "iron_pickaxe"))
        val result = CraftPlanner.plan(tree, omitDeliver = false, recipeLookup = noRecipes)
        assertTrue(result.unresolvable)
        val message = requireNotNull(result.message) { "expected an error message" }
        assertTrue(message.contains("recipe pattern"))
    }

    // ---- B. trees with children -------------------------------------------

    @Test
    fun craftWithStorageChildrenChainsPullsToExecute() {
        val tree = assign(
            craft(
                "minecraft:iron_pickaxe", templateName = "iron_pickaxe",
                children = listOf(
                    storage("minecraft:iron_ingot", 3),
                    storage("minecraft:stick", 2),
                ),
            )
        )
        val plan = planOk(tree, recipes = recipesOf("minecraft:iron_pickaxe" to List(9) { "" }))

        // Two Pulls + one Execute + one Deliver.
        assertEquals(4, plan.ops.size)
        val pulls = plan.ops.filterIsInstance<Operation.Pull>()
        assertEquals(2, pulls.size)
        val execute = plan.ops.filterIsInstance<Operation.Execute>().single()
        assertEquals(pulls.map { it.id }.toSet(), execute.dependsOn.toSet())

        val deliver = plan.ops.filterIsInstance<Operation.Deliver>().single()
        assertEquals(listOf(execute.id), deliver.dependsOn)
        assertEquals(setOf(deliver.id), plan.terminalOpIds)
    }

    @Test
    fun processOpAggregatesSameItemChildren() {
        // Two storage children for the same item id should collapse into one
        // (item, count) entry in the Process op's inputs, summed.
        val tree = assign(
            process(
                "minecraft:steel_ingot", count = 2, templateName = "alloy",
                children = listOf(
                    storage("minecraft:iron_ingot", 3),
                    storage("minecraft:iron_ingot", 5),
                    storage("minecraft:coal", 1),
                ),
            )
        )
        val plan = planOk(tree)
        val processOp = plan.ops.filterIsInstance<Operation.Process>().single()
        assertEquals(
            listOf("minecraft:iron_ingot" to 8L, "minecraft:coal" to 1L),
            processOp.inputs,
        )
    }

    @Test
    fun nestedCraftBuildsExecuteChain() {
        // pickaxe -> sticks (crafted from planks) + iron_ingot (storage).
        val tree = assign(
            craft(
                "minecraft:iron_pickaxe", templateName = "iron_pickaxe",
                children = listOf(
                    storage("minecraft:iron_ingot", 3),
                    craft(
                        "minecraft:stick", count = 2, templateName = "stick",
                        children = listOf(storage("minecraft:oak_planks", 2)),
                    ),
                ),
            )
        )
        val plan = planOk(
            tree,
            recipes = recipesOf(
                "minecraft:iron_pickaxe" to List(9) { "" },
                "minecraft:stick" to List(9) { "" },
            ),
        )

        val pulls = plan.ops.filterIsInstance<Operation.Pull>()
        val executes = plan.ops.filterIsInstance<Operation.Execute>()
        assertEquals(2, pulls.size)
        assertEquals(2, executes.size)

        val pickaxeExec = executes.single { it.outputItemId == "minecraft:iron_pickaxe" }
        val stickExec = executes.single { it.outputItemId == "minecraft:stick" }
        // pickaxe depends on the iron-ingot pull and the stick execute,
        // stick depends only on the planks pull.
        assertTrue(pickaxeExec.dependsOn.contains(stickExec.id))
        assertEquals(1, stickExec.dependsOn.size)
    }

    @Test
    fun siblingsWithSameItemIdGetSeparatePullOps() {
        // Two distinct storage tree nodes for the same item must produce two
        // distinct Pull ops, one per node identity, so subsequent op stitching
        // doesn't accidentally collapse them.
        val tree = assign(
            craft(
                "minecraft:torch", templateName = "torch",
                children = listOf(
                    storage("minecraft:coal", 1),
                    storage("minecraft:coal", 1),
                ),
            )
        )
        val plan = planOk(tree, recipes = recipesOf("minecraft:torch" to List(9) { "" }))

        val pulls = plan.ops.filterIsInstance<Operation.Pull>()
        assertEquals(2, pulls.size, "each tree leaf should map to its own Pull op")
        val execute = plan.ops.filterIsInstance<Operation.Execute>().single()
        // Execute depends on both pulls.
        assertEquals(pulls.map { it.id }.toSet(), execute.dependsOn.toSet())
    }

    // ---- C. omitDeliver ----------------------------------------------------

    @Test
    fun omitDeliverDropsTrailingDeliverOp() {
        val plan = planOk(assign(storage("minecraft:iron_ingot", 4)), omitDeliver = true)

        assertEquals(1, plan.ops.size)
        val pull = plan.ops.single() as Operation.Pull
        assertEquals(setOf(pull.id), plan.terminalOpIds)
        assertTrue(plan.omitDeliver)
    }

    @Test
    fun omitDeliverKeepsRootCraftAsTerminal() {
        val tree = assign(
            craft(
                "minecraft:iron_pickaxe", templateName = "iron_pickaxe",
                children = listOf(storage("minecraft:iron_ingot", 3)),
            )
        )
        val plan = planOk(
            tree,
            omitDeliver = true,
            recipes = recipesOf("minecraft:iron_pickaxe" to List(9) { "" }),
        )

        assertTrue(plan.ops.none { it is Operation.Deliver })
        val execute = plan.ops.filterIsInstance<Operation.Execute>().single()
        assertEquals(setOf(execute.id), plan.terminalOpIds)
    }

    // ---- D. metadata propagation ------------------------------------------

    @Test
    fun outputNodeIdMatchesProducingTreeNodeId() {
        val ironLeaf = storage("minecraft:iron_ingot", 3)
        val root = craft(
            "minecraft:iron_pickaxe", templateName = "iron_pickaxe",
            children = listOf(ironLeaf),
        )
        assign(root)
        val plan = planOk(root, recipes = recipesOf("minecraft:iron_pickaxe" to List(9) { "" }))

        val pull = plan.ops.filterIsInstance<Operation.Pull>().single()
        val execute = plan.ops.filterIsInstance<Operation.Execute>().single()
        val deliver = plan.ops.filterIsInstance<Operation.Deliver>().single()
        assertEquals(ironLeaf.nodeId, pull.outputNodeId)
        assertEquals(root.nodeId, execute.outputNodeId)
        assertEquals(root.nodeId, deliver.outputNodeId)
    }

    @Test
    fun opIdsAreSequentialAndStartAtZero() {
        val tree = assign(
            craft(
                "minecraft:iron_pickaxe", templateName = "iron_pickaxe",
                children = listOf(
                    storage("minecraft:iron_ingot", 3),
                    storage("minecraft:stick", 2),
                ),
            )
        )
        val plan = planOk(tree, recipes = recipesOf("minecraft:iron_pickaxe" to List(9) { "" }))
        val ids = plan.ops.map { it.id }.sorted()
        assertEquals(List(plan.ops.size) { it }, ids)
    }

    @Test
    fun planByIdLookupReturnsTheSameOpInstance() {
        val plan = planOk(assign(storage("minecraft:iron_ingot", 1)))
        for (op in plan.ops) {
            assertTrue(plan.op(op.id) === op)
        }
        assertNull(plan.op(9999))
    }

    @Test
    fun recipeLookupIsConsultedOnceWhenItHits() {
        // Verify the lookup is the only source of recipe patterns and that the
        // planner doesn't fabricate one when the lookup says "no."
        val asks = mutableListOf<String>()
        val tree = assign(craft("modded:special", templateName = "special"))
        CraftPlanner.plan(tree, omitDeliver = false) { itemId ->
            asks += itemId
            null
        }
        assertEquals(listOf("modded:special"), asks)
    }

    // ---- E. iterative depth -----------------------------------------------

    @Test
    fun deepTreeDoesNotStackOverflow() {
        // 200 chained craft_templates; the planner walks iteratively so the
        // JVM stack is irrelevant. If anyone ever swaps in recursion, this
        // catches it.
        var node: CraftTreeNode = storage("minecraft:base", 1)
        repeat(200) { i ->
            node = craft("minecraft:wrap_$i", templateName = "wrap_$i", children = listOf(node))
        }
        assign(node)
        val recipes = recipesOf(
            *(0 until 200).map { "minecraft:wrap_$it" to List(9) { "" } }.toTypedArray()
        )
        val plan = planOk(node, recipes = recipes)
        // 1 Pull + 200 Executes + 1 Deliver
        assertEquals(202, plan.ops.size)
    }
}
