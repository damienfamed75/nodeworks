package damien.nodeworks.script.cpu

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * Per-CPU craft driver. Holds at most one active [CraftPlan] plus a backlog of
 * submitted-but-not-yet-running plans, and executes ready ops every server tick.
 *
 * **Threading model (Phase 3, Option A — shared ready queue):**
 * The scheduler does NOT assign ops to named threads. Instead, [threadCount]
 * acts as a parallelism budget: per tick we start up to [threadCount] ready ops,
 * minus any currently-[inProgress] async ops (which still hold a slot). Any
 * available execution slot can pick up any ready op — co-processors don't
 * "own" branches. This is strictly more parallelism-friendly than per-thread
 * plan assignment, and keeps the state machine centralized here.
 *
 * [threadCount] is updated externally whenever the CPU's multiblock is
 * recalculated ([damien.nodeworks.block.entity.CraftingCoreBlockEntity.recalculateCapacity]).
 * Growing is immediate; shrinking takes effect on the next tick — in-flight async
 * ops always finish, so removing a Co-Processor mid-craft never strands state.
 *
 * All scheduler state lives on the server thread (driven by [BlockEntityTicker]).
 * No locks needed: the CPU BE is only ticked by the server's main thread.
 */
class CraftScheduler(
    @Volatile var threadCount: Int,
    private val executor: OpExecutor
) {

    sealed class OpResult {
        /** Op fully finished. Scheduler marks it completed and chains downstream. */
        object Completed : OpResult()
        /** Async op still running. Scheduler leaves it pending; re-invokes next tick. */
        object InProgress : OpResult()
        /** Op failed. Scheduler aborts the plan with [reason]. */
        data class Failed(val reason: String) : OpResult()
    }

    interface OpExecutor {
        /** Current throttle multiplier. Scheduler calls [CpuRules.opCost] on this. */
        val currentThrottle: Float

        /** Execute a single op. The scheduler only consults the return value. */
        fun execute(op: Operation): OpResult

        /** Called when a plan reaches the DONE state (all terminal ops completed). */
        fun onPlanCompleted(plan: CraftPlan) {}

        /** Called when a plan fails mid-execution. Implementer must release reserved items. */
        fun onPlanFailed(plan: CraftPlan, reason: String) {}
    }

    enum class State { IDLE, RUNNING, DONE, FAILED }

    private var plan: CraftPlan? = null
    private var state: State = State.IDLE
    private var failureReason: String? = null
    /** Completed op IDs within the current plan. */
    private val completed: MutableSet<Int> = HashSet()
    /** Op IDs currently being executed asynchronously (hold a slot until they resolve). */
    private val inProgressIds: MutableSet<Int> = HashSet()

    private val backlog: ArrayDeque<CraftPlan> = ArrayDeque()

    // --- Public query API ---

    val isIdle: Boolean get() = state == State.IDLE
    val currentPlan: CraftPlan? get() = plan
    val queuedPlans: List<CraftPlan> get() = backlog.toList()
    val progress: Pair<Int, Int> get() = completed.size to (plan?.ops?.size ?: 0)
    val currentState: State get() = state
    val currentFailureReason: String? get() = failureReason
    /** Read-only view of completed op IDs in the current plan. Used by the GUI to map ops
     *  back to tree nodes for "this branch is finished" highlighting. */
    val completedOpIds: Set<Int> get() = completed

    /** Submit a craft. Runs immediately if idle, else queues. */
    fun submit(plan: CraftPlan, currentTick: Long) {
        if (state == State.IDLE) {
            assignPlan(plan, currentTick)
        } else {
            backlog.addLast(plan)
        }
    }

    /** Cancel everything — active plan and backlog. Caller releases reserved items via onPlanFailed. */
    fun cancelAll(reason: String) {
        val active = plan
        if (active != null && state == State.RUNNING) {
            state = State.FAILED
            failureReason = reason
            executor.onPlanFailed(active, reason)
        }
        plan = null
        completed.clear()
        inProgressIds.clear()
        state = State.IDLE
        while (backlog.isNotEmpty()) {
            executor.onPlanFailed(backlog.removeFirst(), reason)
        }
    }

    // --- Per-tick driver ---

    /** Drive the scheduler forward by one server tick. Call from the BE's ticker. */
    fun tick(currentTick: Long) {
        val throttle = executor.currentThrottle
        var opsThisTick = 0

        if (state == State.RUNNING) {
            scheduleNewlyReadyOps(currentTick, throttle)

            // Parallel-execution loop. We keep looping as long as ready ops became
            // available (cost-0 chaining) AND there's slot budget AND we haven't hit
            // the per-tick cap (which exists to protect TPS against pathological crafts).
            var progress = true
            // threadCount caps parallelism PER ROUND — up to N ops can start together.
            // Chained cost-0 downstream ops become ready mid-round and picked up next round,
            // so a long linear chain still finishes in one tick at high throttle.
            while (progress && opsThisTick < CpuRules.OPS_PER_TICK_CAP && state == State.RUNNING) {
                progress = false
                var opsThisRound = 0
                // Prefer distinct processing APIs when multiple Process ops are ready, so
                // a co-processor running alongside the core doesn't double up on the same
                // machine. E.g. if (iron, iron, copper) are all ready with threadCount=2,
                // pick (iron, copper) not (iron, iron) — the two same-type handlers would
                // just collide on the single furnace anyway and waste retry budget.
                val ready = pickDispatchableOps(readyOps(currentTick), threadCount)
                if (ready.isEmpty()) break

                for (op in ready) {
                    if (opsThisTick >= CpuRules.OPS_PER_TICK_CAP) break
                    val result = executor.execute(op)
                    opsThisTick++
                    opsThisRound++
                    when (result) {
                        is OpResult.Completed -> {
                            op.inProgress = false
                            markCompleted(op.id)
                            progress = true
                        }
                        is OpResult.InProgress -> {
                            op.inProgress = true
                            // Op stays in readyOps for future ticks; slot was spent here.
                        }
                        is OpResult.Failed -> {
                            val p = plan
                            state = State.FAILED
                            failureReason = result.reason
                            if (p != null) executor.onPlanFailed(p, result.reason)
                            break
                        }
                    }
                }
                scheduleNewlyReadyOps(currentTick, throttle)
            }
        }

        // Finalize: return to IDLE and pull in backlog. onPlanFailed already fired in-line
        // during a Failed op result; onPlanCompleted fires here for the success path.
        when (state) {
            State.DONE -> {
                val finished = plan
                reset()
                if (finished != null) executor.onPlanCompleted(finished)
                if (backlog.isNotEmpty()) assignPlan(backlog.removeFirst(), currentTick)
            }
            State.FAILED -> {
                reset()
                if (backlog.isNotEmpty()) assignPlan(backlog.removeFirst(), currentTick)
            }
            else -> { /* still running or idle */ }
        }
    }

    // --- Internal op-graph helpers ---

    private fun assignPlan(p: CraftPlan, currentTick: Long) {
        plan = p
        completed.clear()
        inProgressIds.clear()
        failureReason = null
        // Reset per-op ephemeral state so a resumed plan doesn't inherit stale flags
        for (op in p.ops) {
            op.readyAt = -1L
            op.inProgress = false
        }
        state = if (p.ops.isEmpty()) State.DONE else State.RUNNING
    }

    private fun reset() {
        plan = null
        completed.clear()
        inProgressIds.clear()
        state = State.IDLE
        failureReason = null
    }

    private fun markCompleted(opId: Int) {
        val p = plan ?: return
        if (opId in completed) return
        completed.add(opId)
        if (p.terminalOpIds.all { it in completed }) {
            state = State.DONE
        }
    }

    /** Ops whose dependencies are met and whose readyAt ≤ currentTick.
     *  Already-in-progress async ops are INCLUDED — they're re-dispatched each tick so
     *  their `execute` call can poll the async state. Each dispatch counts as one op
     *  against the parallelism budget (threadCount). */
    private fun readyOps(currentTick: Long): List<Operation> {
        val p = plan ?: return emptyList()
        val out = mutableListOf<Operation>()
        for (op in p.ops) {
            if (op.id in completed) continue
            if (op.dependsOn.any { it !in completed }) continue
            if (op.readyAt < 0L) continue
            if (op.readyAt > currentTick) continue
            out += op
        }
        return out
    }

    /**
     * Pick ops to dispatch this round.
     *
     * Sync ops (Pull / Execute / Deliver) always go — they're cheap buffer/storage work and
     * there's no reason to rate-limit them; doing so causes the "grabs copper first, then
     * waits for copper to cook before grabbing iron" phenomenon players see. Async ops
     * (Process — holds a slot across ticks while a machine cooks) consume the [limit] budget
     * and prefer distinct processing APIs so a co-processor doesn't pile up on the same
     * machine as the core.
     */
    private fun pickDispatchableOps(ready: List<Operation>, limit: Int): List<Operation> {
        if (ready.isEmpty()) return emptyList()
        val picked = ArrayList<Operation>()
        val syncOps = ArrayList<Operation>()
        val processOps = ArrayList<Operation.Process>()
        for (op in ready) {
            if (op is Operation.Process) processOps += op else syncOps += op
        }
        // All sync ops upfront.
        picked += syncOps
        if (limit <= 0) return picked
        // Process ops bounded by limit, preferring distinct APIs.
        val usedApis = HashSet<String>()
        for (op in processOps) {
            if (picked.size - syncOps.size >= limit) break
            if (op.processingApiName !in usedApis) {
                picked += op
                usedApis += op.processingApiName
            }
        }
        if (picked.size - syncOps.size < limit) {
            for (op in processOps) {
                if (picked.size - syncOps.size >= limit) break
                if (op !in picked) picked += op
            }
        }
        return picked
    }

    /** Assign [Operation.readyAt] for any op whose dependencies just became satisfied.
     *  Uses the op's own [Operation.baseCost] so Execute ops (expensive) wait longer than
     *  item-movement ops (cheap). Scales down with throttle^2 via [CpuRules.opCost]. */
    private fun scheduleNewlyReadyOps(currentTick: Long, throttle: Float) {
        val p = plan ?: return
        for (op in p.ops) {
            if (op.id in completed) continue
            if (op.readyAt >= 0L) continue
            if (op.dependsOn.all { it in completed }) {
                op.readyAt = currentTick + CpuRules.opCost(throttle, op.baseCost)
            }
        }
    }

    // --- Serialization ---

    // TODO MC 26.1.2 NBT MIGRATION: rewrite against the new CompoundTag API.
    //  See git history for the full pre-migration body.
    //  Must preserve: state (enum name), failureReason, completed (IntArray),
    //  inProgressIds (IntArray), plan (sub-compound), backlog (ListTag of CraftPlan tags).
    //  Must also handle the legacy pre-MultiThread "threads" ListTag format on load
    //  (take the first thread's plan+completed, re-derive inProgress from plan flags).
    fun saveToNBT(tag: CompoundTag) {
    }

    fun loadFromNBT(tag: CompoundTag) {
        state = State.IDLE
        failureReason = null
        completed.clear()
        inProgressIds.clear()
        plan = null
        backlog.clear()
    }
}
