package damien.nodeworks.script.cpu

import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode

/**
 * Transforms a [CraftTreeNode] into a [CraftPlan], an ordered list of [Operation]s with
 * explicit dependencies. Everything is iterative (no recursion) so deep craft trees
 * cannot blow the JVM stack.
 *
 * The planner walks the tree in post-order (children-first), generating ops in dependency
 * order:
 *   - "storage" leaf → [Operation.Pull]
 *   - "process_template" node → [Operation.Process] (deps on input ops)
 *   - "craft_template" node → [Operation.Execute] (deps on input ops, carries the 3×3 recipe)
 *   - root of the plan → [Operation.Deliver] (final flush to network storage / reserved slot)
 *
 * "missing" and "process_no_handler" nodes should have been rejected upstream by
 * [CpuFeasibility.check], planning them now produces a failure result.
 */
object CraftPlanner {

    data class PlanResult(val plan: CraftPlan?, val unresolvable: Boolean, val message: String?)

    /**
     * Plan a craft tree.
     *
     * [snapshot] is needed to look up the 3×3 Instruction Set recipe pattern for
     * craft_template nodes, the tree only carries the template name.
     */
    fun plan(tree: CraftTreeNode, snapshot: NetworkSnapshot): PlanResult {
        val ops = mutableListOf<Operation>()
        var nextId = 0
        fun newId(): Int = nextId++

        // Each tree node that produces output gets mapped to the op id whose completion
        // means "that amount of that item is now in the buffer"
        val outputOpOf = HashMap<IdentityKey, Int>()

        // Post-order iterative walk
        val stack = ArrayDeque<TraversalFrame>()
        stack.addLast(TraversalFrame(tree, childrenProcessed = false))

        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.childrenProcessed) {
                frame.childrenProcessed = true
                for (child in frame.node.children) {
                    stack.addLast(TraversalFrame(child, false))
                }
                continue
            }
            stack.removeLast()
            val node = frame.node
            val nodeKey = IdentityKey(node)

            when (node.source) {
                "missing", "process_no_handler" -> {
                    return PlanResult(
                        null, true,
                        "Unresolvable node ${node.itemId} (source=${node.source}); CpuFeasibility should have rejected this craft."
                    )
                }
                "storage" -> {
                    val pullId = newId()
                    val op = Operation.Pull(
                        id = pullId,
                        dependsOn = emptyList(),
                        itemId = node.itemId,
                        amount = node.count.toLong()
                    )
                    op.outputNodeId = node.nodeId
                    ops += op
                    outputOpOf[nodeKey] = pullId
                }
                "process_template" -> {
                    // One Process op per tree node. The executor iterates the handler N times
                    // internally (N = ceil(totalNeeded / api.outputs-per-batch)), so parallelism
                    // comes from the tree having multiple distinct process_template nodes, not
                    // from splitting a single branch into competing ops. Same-item branches in
                    // different parts of the tree (e.g. two recipes each needing iron ingots)
                    // become two ops that co-processors can run concurrently.
                    val inputDeps = node.children.mapNotNull { outputOpOf[IdentityKey(it)] }
                    val inputs = aggregateByItem(node.children)
                    val processId = newId()
                    val op = Operation.Process(
                        id = processId,
                        dependsOn = inputDeps,
                        processingApiName = node.templateName,
                        inputs = inputs,
                        outputs = listOf(node.itemId to node.count.toLong())
                    )
                    op.outputNodeId = node.nodeId
                    ops += op
                    outputOpOf[nodeKey] = processId
                }
                "craft_template" -> {
                    val inputDeps = node.children.mapNotNull { outputOpOf[IdentityKey(it)] }
                    // Resolve the recipe pattern by looking up the Instruction Set that
                    // produces this item. Tree only carries the template name, here we
                    // fetch the actual 9-slot pattern so the Execute op is self-contained.
                    val recipe = resolveRecipePattern(node.itemId, snapshot)
                        ?: return PlanResult(
                            null, true,
                            "Could not resolve recipe pattern for ${node.itemId}"
                        )

                    val executeId = newId()
                    val op = Operation.Execute(
                        id = executeId,
                        dependsOn = inputDeps,
                        recipe = recipe,
                        outputItemId = node.itemId,
                        outputCount = node.count.toLong(),
                        executions = node.count.toLong()
                    )
                    op.outputNodeId = node.nodeId
                    ops += op
                    outputOpOf[nodeKey] = executeId
                }
                else -> {
                    // Unknown source, skip silently, feasibility check should have flagged.
                }
            }
        }

        val rootOpId = outputOpOf[IdentityKey(tree)]
            ?: return PlanResult(null, true, "Planner produced no root op.")

        val deliverId = newId()
        val deliverOp = Operation.Deliver(
            id = deliverId,
            dependsOn = listOf(rootOpId),
            itemId = tree.itemId,
            amount = tree.count.toLong(),
            toReservedSlot = true
        )
        // Deliver finishing means the root tree node is fully complete.
        deliverOp.outputNodeId = tree.nodeId
        ops += deliverOp

        return PlanResult(
            plan = CraftPlan(
                rootItemId = tree.itemId,
                rootCount = tree.count.toLong(),
                ops = ops,
                terminalOpIds = setOf(deliverId)
            ),
            unresolvable = false,
            message = null
        )
    }

    private fun resolveRecipePattern(itemId: String, snapshot: NetworkSnapshot): List<String>? {
        val match = snapshot.findInstructionSet(itemId) ?: return null
        return match.instructionSet.recipe
    }

    private fun aggregateByItem(children: List<CraftTreeNode>): List<Pair<String, Long>> {
        val map = LinkedHashMap<String, Long>()
        for (c in children) {
            map.merge(c.itemId, c.count.toLong()) { a, b -> a + b }
        }
        return map.entries.map { it.key to it.value }
    }

    private data class TraversalFrame(val node: CraftTreeNode, var childrenProcessed: Boolean)

    private class IdentityKey(private val node: CraftTreeNode) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.node === node
        override fun hashCode(): Int = System.identityHashCode(node)
    }
}
