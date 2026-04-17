package damien.nodeworks.script.cpu

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * A single discrete unit of CPU work. Crafts decompose into a DAG of operations that
 * the scheduler executes with tick-level timing.
 *
 * Every operation carries:
 *   - a unique [id] assigned by the planner (stable within a single [CraftPlan])
 *   - a list of [dependsOn] op IDs that must complete before this op becomes ready
 *   - a [readyAt] tick assigned by the scheduler when all dependencies complete
 *     (equal to `currentTick + opCost(throttle)`; can be 0 for chained ops)
 *
 * Ops are **data only** — the scheduler interprets them and calls into the Core's
 * [CraftScheduler.OpExecutor] to perform real work (item extraction, recipe execution, etc.).
 *
 * All counts are [Long] for billions-of-items robustness. Op types closely mirror the four
 * real phases of a craft:
 *   - [Pull]    network storage → buffer
 *   - [Process] invoke a processing-set handler (async); outputs end up in buffer
 *   - [Execute] run a vanilla-style crafting-table recipe (buffer → buffer)
 *   - [Deliver] buffer → network storage (or reserved slot)
 */
sealed class Operation {
    abstract val id: Int
    abstract val dependsOn: List<Int>

    /** Per-op base tick cost at throttle 1.0×. [CpuRules.opCost] scales this down as the
     *  CPU gets better-cooled. Different op types have different base costs — see
     *  [CpuRules] for the rationale. */
    abstract val baseCost: Int

    /** The tick at which this op becomes ready to execute. -1 = deps not yet all satisfied. */
    var readyAt: Long = -1L

    /** Async ops set this to true after [CraftScheduler.OpExecutor.execute] returns IN_PROGRESS,
     *  so subsequent ticks re-invoke the executor rather than treating the op as fresh. */
    var inProgress: Boolean = false

    /** Tree node ID this op produces output for. -1 if op doesn't correspond to a tree node
     *  (e.g. the root [Deliver]). Lets the GUI translate active-op IDs back to tree nodes
     *  for accurate per-node highlighting (vs. ambiguous itemId matching). */
    var outputNodeId: Int = -1

    /** Extract [amount] of [itemId] from network storage into the CPU buffer.
     *  Atomic reservation on the network happens immediately; scheduler gates consumption. */
    data class Pull(
        override val id: Int,
        override val dependsOn: List<Int>,
        val itemId: String,
        val amount: Long
    ) : Operation() {
        override val baseCost: Int get() = CpuRules.PULL_BASE_COST
    }

    /**
     * Invoke a Processing-Set handler (Lua) with the listed inputs, and asynchronously
     * wait for the listed outputs to arrive back in the buffer. The Core's executor
     * returns IN_PROGRESS until the handler's [job:pull] polls resolve.
     *
     * [inputs] are consumed from the buffer (the handler will insert them into the
     * destination it chose). [outputs] are what the scheduler expects to see land in
     * the buffer when the handler's pulls finish.
     */
    data class Process(
        override val id: Int,
        override val dependsOn: List<Int>,
        val processingApiName: String,
        val inputs: List<Pair<String, Long>>,
        val outputs: List<Pair<String, Long>>
    ) : Operation() {
        override val baseCost: Int get() = CpuRules.PROCESS_BASE_COST
    }

    /** Run a vanilla-style 3x3 crafting recipe. Ingredients are consumed from the buffer;
     *  the output lands back in the buffer. [executions] allows bulk crafting in one op. */
    data class Execute(
        override val id: Int,
        override val dependsOn: List<Int>,
        /** 9-slot recipe pattern: each slot is an item id or empty string. */
        val recipe: List<String>,
        val outputItemId: String,
        val outputCount: Long,
        val executions: Long
    ) : Operation() {
        override val baseCost: Int get() = CpuRules.EXECUTE_BASE_COST
    }

    /** Move [amount] of [itemId] from the CPU buffer to network storage (or a reserved slot). */
    data class Deliver(
        override val id: Int,
        override val dependsOn: List<Int>,
        val itemId: String,
        val amount: Long,
        val toReservedSlot: Boolean
    ) : Operation() {
        override val baseCost: Int get() = CpuRules.DELIVER_BASE_COST
    }

    // =====================================================================
    // Serialization
    // =====================================================================

    // TODO MC 26.1.2 NBT MIGRATION: rewrite against the new CompoundTag API.
    //  See git history for the full pre-migration body. Discriminates on a "kind"
    //  string tag to reconstruct the correct Pull/Process/Execute/Deliver subclass.
    //  Must preserve: id, readyAt, inProgress, outputNodeId, dependsOn (IntArray),
    //  plus subclass-specific fields (itemId, amount, processingApiName, inputs/outputs,
    //  recipe ListTag, outputItemId, outputCount, executions, toReservedSlot).
    fun saveToNBT(tag: CompoundTag) {
    }

    companion object {
        fun loadFromNBT(tag: CompoundTag): Operation? = null
    }
}
