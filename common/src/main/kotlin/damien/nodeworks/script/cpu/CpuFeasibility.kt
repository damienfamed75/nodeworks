package damien.nodeworks.script.cpu

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.script.CraftTreeBuilder

/**
 * Pre-flight analysis of a craft job against a CPU's buffer capacity.
 *
 * Walks the craft tree iteratively (no recursion — safe for deep trees) and computes
 * conservative estimates for peak buffer demand. Rejects crafts that provably cannot fit
 * in the CPU's current buffer limits (count or types).
 *
 * Does NOT simulate scheduling. Catches obvious infeasibility only — future scheduler
 * work may refine this with tighter bounds. Never over-rejects: if this returns ok,
 * the craft CAN fit (assuming items are available).
 */
object CpuFeasibility {

    data class Result(
        val ok: Boolean,
        val reason: String? = null,
        val peakSingleTypeCount: Long = 0L,
        val uniqueTypes: Int = 0
    )

    /**
     * Analyze a craft tree against a CPU's buffer capacity. Returns [Result.ok] if the
     * craft fits, otherwise a player-facing [Result.reason] explaining which limit
     * the craft would exceed.
     */
    fun check(tree: CraftTreeBuilder.CraftTreeNode, cpu: CraftingCoreBlockEntity): Result {
        val uniqueTypes = mutableSetOf<String>()
        var peakSingleType = 0L
        val missing = mutableListOf<CraftTreeBuilder.CraftTreeNode>()
        val noHandler = mutableListOf<CraftTreeBuilder.CraftTreeNode>()

        // Iterative BFS so deep trees don't blow the stack
        val stack = ArrayDeque<CraftTreeBuilder.CraftTreeNode>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node.source) {
                "missing" -> missing.add(node)
                "process_no_handler" -> noHandler.add(node)
                else -> {
                    uniqueTypes.add(node.itemId)
                    val nodeCount = node.count.toLong()
                    if (nodeCount > peakSingleType) peakSingleType = nodeCount
                }
            }
            for (child in node.children) stack.addLast(child)
        }

        // Missing ingredients win over every other check — no point starting a craft that
        // can't finish. Report the first few unresolvable items by name.
        if (missing.isNotEmpty()) {
            val summary = missing.take(3).joinToString(", ") { "${it.count}× ${it.itemName}" }
            val extra = if (missing.size > 3) " (+${missing.size - 3} more)" else ""
            return Result(
                ok = false,
                reason = "Missing ingredients: $summary$extra. No recipe available and not enough in storage.",
                peakSingleTypeCount = peakSingleType,
                uniqueTypes = uniqueTypes.size
            )
        }

        // Processing recipes exist but no Terminal on the network has a
        // `network:handle("recipe_id", ...)` registered for them. User-facing: tell them
        // which recipe needs a handler and where to go to add one.
        if (noHandler.isNotEmpty()) {
            val summary = noHandler.take(3).joinToString(", ") { it.itemName }
            val extra = if (noHandler.size > 3) " (+${noHandler.size - 3} more)" else ""
            return Result(
                ok = false,
                reason = "No handler registered for: $summary$extra. Add a `network:handle(\"recipe\", ...)` call in a connected Terminal's script.",
                peakSingleTypeCount = peakSingleType,
                uniqueTypes = uniqueTypes.size
            )
        }

        if (uniqueTypes.size > cpu.bufferTypesCapacity) {
            return Result(
                ok = false,
                reason = "Craft needs ${uniqueTypes.size} unique item types, CPU only holds ${cpu.bufferTypesCapacity}. Add more Buffer blocks or upgrade tiers.",
                peakSingleTypeCount = peakSingleType,
                uniqueTypes = uniqueTypes.size
            )
        }

        if (peakSingleType > cpu.bufferCapacity) {
            return Result(
                ok = false,
                reason = "Craft requires up to $peakSingleType items of one type in buffer, CPU only holds ${cpu.bufferCapacity}. Upgrade Buffer tiers or add more blocks.",
                peakSingleTypeCount = peakSingleType,
                uniqueTypes = uniqueTypes.size
            )
        }

        return Result(ok = true, peakSingleTypeCount = peakSingleType, uniqueTypes = uniqueTypes.size)
    }
}
