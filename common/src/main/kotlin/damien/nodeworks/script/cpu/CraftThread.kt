package damien.nodeworks.script.cpu

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/**
 * One concurrent execution context belonging to a [CraftScheduler].
 * The Core provides thread 0; each Co-Processor adds another (Phase 3).
 *
 * A thread holds at most one active [CraftPlan] at a time. During execution it tracks
 * which ops have completed and which are ready to run this tick. Op timing is governed
 * by [CpuRules.opCost] — with the configurable throttle multiplier.
 *
 * Thread state survives world reload via NBT — serialize the full plan and completion set.
 */
class CraftThread(val index: Int) {

    var plan: CraftPlan? = null
        private set

    /** Completed op IDs within the current plan. */
    private val completed: MutableSet<Int> = HashSet()

    enum class State { IDLE, RUNNING, DONE, FAILED }

    var state: State = State.IDLE
        private set

    /** Reason for a FAILED state, if any. Player-facing. */
    var failureReason: String? = null
        private set

    /** Last tick any op on this thread became ready — used to schedule chained ops. */
    private var lastProgressTick: Long = 0L

    // =====================================================================
    // Assignment
    // =====================================================================

    /** Assign a new plan to this thread. Clears completion state. */
    fun assign(plan: CraftPlan, currentTick: Long) {
        this.plan = plan
        completed.clear()
        state = if (plan.ops.isEmpty()) State.DONE else State.RUNNING
        failureReason = null
        lastProgressTick = currentTick
    }

    /** Abort the current plan. Caller is responsible for unreserving items. */
    fun abort(reason: String) {
        state = State.FAILED
        failureReason = reason
    }

    /** Clear the current plan (called when the scheduler is fully done with this job). */
    fun clear() {
        plan = null
        completed.clear()
        state = State.IDLE
        failureReason = null
    }

    val isIdle: Boolean get() = state == State.IDLE
    val isRunning: Boolean get() = state == State.RUNNING
    val isDone: Boolean get() = state == State.DONE
    val isFailed: Boolean get() = state == State.FAILED

    // =====================================================================
    // Ready-op selection
    // =====================================================================

    /**
     * Returns all ops whose dependencies are satisfied and whose [Operation.readyAt]
     * is at or before [currentTick], in stable id order. Does NOT mutate state — the
     * scheduler executes returned ops and later calls [markCompleted] for each.
     */
    fun readyOps(currentTick: Long): List<Operation> {
        val p = plan ?: return emptyList()
        if (state != State.RUNNING) return emptyList()
        val out = mutableListOf<Operation>()
        for (op in p.ops) {
            if (op.id in completed) continue
            if (op.dependsOn.any { it !in completed }) continue
            if (op.readyAt < 0L) continue      // not yet scheduled
            if (op.readyAt > currentTick) continue
            out += op
        }
        return out
    }

    /**
     * Schedule any ops whose dependencies are freshly satisfied (and don't yet have
     * a readyAt). Assigns `readyAt = currentTick + opCost`. Call once per tick on
     * every thread after [markCompleted] has been called for newly-finished ops.
     */
    fun scheduleNewlyReadyOps(currentTick: Long, opCost: Int) {
        val p = plan ?: return
        for (op in p.ops) {
            if (op.id in completed) continue
            if (op.readyAt >= 0L) continue   // already scheduled
            if (op.dependsOn.all { it in completed }) {
                op.readyAt = currentTick + opCost
                lastProgressTick = currentTick
            }
        }
    }

    fun markCompleted(opId: Int) {
        val p = plan ?: return
        if (opId in completed) return
        completed.add(opId)
        if (p.terminalOpIds.all { it in completed }) {
            state = State.DONE
        }
    }

    fun isOpCompleted(opId: Int): Boolean = opId in completed

    val progress: Pair<Int, Int> get() {
        val total = plan?.ops?.size ?: 0
        return completed.size to total
    }

    // =====================================================================
    // Serialization
    // =====================================================================

    fun saveToNBT(tag: CompoundTag) {
        tag.putInt("index", index)
        tag.putString("state", state.name)
        failureReason?.let { tag.putString("failReason", it) }
        tag.putLong("lastProgress", lastProgressTick)
        val compSet = IntArray(completed.size)
        var i = 0
        for (id in completed) compSet[i++] = id
        tag.putIntArray("completed", compSet)
        plan?.let {
            val planTag = CompoundTag()
            it.saveToNBT(planTag)
            tag.put("plan", planTag)
        }
    }

    fun loadFromNBT(tag: CompoundTag) {
        state = runCatching { State.valueOf(tag.getString("state")) }.getOrDefault(State.IDLE)
        failureReason = tag.getString("failReason").takeIf { it.isNotEmpty() }
        lastProgressTick = tag.getLong("lastProgress")
        completed.clear()
        (tag.getIntArray("completed") ?: IntArray(0)).forEach { completed.add(it) }
        plan = if (tag.contains("plan", Tag.TAG_COMPOUND.toInt())) {
            CraftPlan.loadFromNBT(tag.getCompound("plan"))
        } else null
    }
}
