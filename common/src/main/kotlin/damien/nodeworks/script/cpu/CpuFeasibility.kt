package damien.nodeworks.script.cpu

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.script.CraftTreeBuilder

/**
 * Pre-flight analysis of a craft job against a CPU's buffer capacity.
 *
 * Walks the craft tree iteratively (no recursion, safe for deep trees) and computes
 * conservative estimates for peak buffer demand. Rejects crafts that provably cannot fit
 * in the CPU's current buffer limits (count or types).
 *
 * Does NOT simulate scheduling. Catches obvious infeasibility only, future scheduler
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

        // Missing ingredients win over every other check, no point starting a craft that
        // can't finish. Report the first few unresolvable items by name.
        if (missing.isNotEmpty()) {
            val shortfalls = shortfalls(tree)
            val summary = shortfalls.take(MAX_LISTED).joinToString(", ") { "${it.count}× ${it.name}" }
            val extra = if (shortfalls.size > MAX_LISTED) " (+${shortfalls.size - MAX_LISTED} more)" else ""
            return Result(
                ok = false,
                reason = "Missing ingredients: $summary$extra. No recipe available and not enough in storage.",
                peakSingleTypeCount = peakSingleType,
                uniqueTypes = uniqueTypes.size
            )
        }

        // Processing recipes exist but neither a Terminal script nor a
        // Processing Handler block claims them. User-facing: tell them which
        // recipe is unhandled and the two ways to fix it.
        if (noHandler.isNotEmpty()) {
            val summary = noHandler.take(3).joinToString(", ") { it.itemName }
            val extra = if (noHandler.size > 3) " (+${noHandler.size - 3} more)" else ""
            return Result(
                ok = false,
                reason = "No handler for: $summary$extra. Bind a Processing Handler block to the recipe, " +
                    "or add a `network:handle(\"recipe\", ...)` call in a connected Terminal's script.",
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

    private const val MAX_LISTED = 4

    /** How many of one item a craft is short. */
    data class Shortfall(val itemId: String, val name: String, val count: Long)

    /**
     * What the craft is short of, one entry per item, most short first.
     *
     * A missing node carries the whole amount its branch asked for, not the gap, since a
     * branch storage cannot fully cover takes nothing from storage. So an item used in
     * several places shows up as several missing nodes, each with its full demand, while
     * some of its stock still sits unreserved. Per item that is the sum of its missing
     * demand less what storage still has free once every branch is built. The free
     * amount is the least any of the item's nodes saw: a missing node saw what was free
     * then, a storage node leaves what was free less what it took. Storage counts are by
     * item id, so this groups by item id too.
     */
    fun shortfalls(tree: CraftTreeBuilder.CraftTreeNode): List<Shortfall> {
        val demand = LinkedHashMap<String, Long>()
        val names = HashMap<String, String>()
        val free = HashMap<String, Long>()
        val stack = ArrayDeque<CraftTreeBuilder.CraftTreeNode>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val left: Long? = when {
                node.source == "missing" -> {
                    demand.merge(node.itemId, node.count.toLong(), Long::plus)
                    names.putIfAbsent(node.itemId, node.itemName)
                    node.inStorage.toLong()
                }
                // A circular stand-in reserves nothing, so it leaves what it saw.
                node.source == "storage" && node.resolvedBy == "circular" -> node.inStorage.toLong()
                node.source == "storage" -> (node.inStorage - node.count).toLong()
                else -> null
            }
            if (left != null) free.merge(node.itemId, left.coerceAtLeast(0L), ::minOf)
            for (child in node.children) stack.addLast(child)
        }
        return demand.map { (id, need) ->
            Shortfall(id, names[id] ?: id, (need - (free[id] ?: 0L)).coerceAtLeast(1L))
        }.sortedByDescending { it.count }
    }
}
