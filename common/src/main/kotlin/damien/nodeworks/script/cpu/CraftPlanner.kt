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
     * Convenience overload that pulls Instruction Set recipe patterns out of a
     * [NetworkSnapshot]. Live callers in production go through this entry point.
     */
    fun plan(tree: CraftTreeNode, snapshot: NetworkSnapshot, omitDeliver: Boolean = false): PlanResult =
        plan(tree, omitDeliver) { itemId ->
            snapshot.findInstructionSet(itemId)?.instructionSet?.recipe
        }

    /**
     * Plan a craft tree.
     *
     * [recipeLookup] resolves the 9-slot Instruction Set recipe pattern for a
     * craft_template node's output item id. Returning null means "no recipe known
     * for this item," which surfaces as an unresolvable plan. The hook is here
     * (rather than baking [NetworkSnapshot] in) so the planner stays unit-testable
     * without spinning up a Level or BlockEntity.
     *
     * When [omitDeliver] is true, the trailing [Operation.Deliver] is left out and
     * the root output op becomes the plan's terminal op. The caller is then on the
     * hook for routing the items still sitting in the CPU buffer (auto-store, hand
     * off to a script callback, etc.). Used by `network:craft` so its `:connect(fn)`
     * handler receives a live reference to items still in the buffer rather than
     * items that were already pushed into network storage.
     */
    fun plan(
        tree: CraftTreeNode,
        omitDeliver: Boolean = false,
        recipeLookup: (String) -> List<String>?,
    ): PlanResult {
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
                    val recipe = recipeLookup(node.itemId)
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

        // With [omitDeliver], the plan stops at the root output op. The CPU buffer
        // holds the produced items at completion and the caller (e.g. network:craft)
        // handles routing.
        val terminalOpId: Int
        if (omitDeliver) {
            terminalOpId = rootOpId
        } else {
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
            terminalOpId = deliverId
        }

        return PlanResult(
            plan = CraftPlan(
                rootItemId = tree.itemId,
                rootCount = tree.count.toLong(),
                ops = ops,
                terminalOpIds = setOf(terminalOpId),
                omitDeliver = omitDeliver,
            ),
            unresolvable = false,
            message = null
        )
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
