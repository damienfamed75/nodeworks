package damien.nodeworks.script.cpu

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * Per-CPU craft driver. Owns one or more [CraftThread]s, dispatches operations to an
 * [OpExecutor], and advances state every server tick.
 *
 * The scheduler is deliberately **pure orchestration** — it does not know about
 * Minecraft item handlers, network storage, or block entities. All side effects
 * (grabbing items, invoking handlers, executing recipes) go through [OpExecutor],
 * which the Core implements.
 *
 * Behavior per [tick]:
 *   1. For every thread, schedule any ops whose deps just became satisfied
 *      (assigns `readyAt = currentTick + opCost(throttle)`).
 *   2. Execute ready ops across threads, fairly rotated, up to [CpuRules.OPS_PER_TICK_CAP].
 *      Executor returns [OpResult.Completed] (move on), [OpResult.InProgress] (try next tick),
 *      or [OpResult.Failed] (abort the thread).
 *   3. Chained ops (cost-0): after completing an op, the downstream ops' [readyAt] becomes
 *      the current tick, so the loop picks them up in the same pass — full-chain-in-one-tick
 *      at high throttle.
 *   4. Finalize done threads; pull in backlog plans.
 */
class CraftScheduler(
    private val threadCount: Int,
    private val executor: OpExecutor
) {

    sealed class OpResult {
        /** Op fully finished. Scheduler marks it completed and chains downstream. */
        object Completed : OpResult()
        /** Async op still running. Scheduler leaves it un-completed; re-invokes next tick. */
        object InProgress : OpResult()
        /** Op failed. Scheduler aborts the thread with [reason]. */
        data class Failed(val reason: String) : OpResult()
    }

    interface OpExecutor {
        /** Current throttle multiplier. Scheduler calls [CpuRules.opCost] on this. */
        val currentThrottle: Float

        /** Execute a single op. The scheduler only consults the return value — do not
         *  call any state methods on the thread directly. */
        fun execute(op: Operation): OpResult

        /** Called when a plan reaches the DONE state (all terminal ops completed). */
        fun onPlanCompleted(plan: CraftPlan) {}

        /** Called when a plan fails mid-execution. Implementer must release reserved items. */
        fun onPlanFailed(plan: CraftPlan, reason: String) {}
    }

    private val threads: List<CraftThread> = List(threadCount) { CraftThread(it) }
    private val backlog: ArrayDeque<CraftPlan> = ArrayDeque()

    val allThreads: List<CraftThread> get() = threads
    val queuedPlans: List<CraftPlan> get() = backlog.toList()

    /** Submit a craft. Runs immediately on an idle thread, else queues. */
    fun submit(plan: CraftPlan, currentTick: Long) {
        val idle = threads.firstOrNull { it.isIdle }
        if (idle != null) {
            idle.assign(plan, currentTick)
        } else {
            backlog.addLast(plan)
        }
    }

    /** Cancel everything. Caller is responsible for unreserving items. */
    fun cancelAll(reason: String) {
        for (t in threads) {
            if (t.isRunning) {
                val p = t.plan
                t.abort(reason)
                if (p != null) executor.onPlanFailed(p, reason)
            }
            t.clear()
        }
        while (backlog.isNotEmpty()) {
            val p = backlog.removeFirst()
            executor.onPlanFailed(p, reason)
        }
    }

    /** Drive the scheduler forward by one server tick. */
    fun tick(currentTick: Long) {
        val opCost = CpuRules.opCost(executor.currentThrottle)
        var opsThisTick = 0

        // Initial scheduling pass — any thread with fresh deps satisfied gets readyAt set.
        for (t in threads) t.scheduleNewlyReadyOps(currentTick, opCost)

        // Execute ops, rotating across threads. The outer `progress` loop enables cost-0
        // chaining within a single tick: completing an op schedules its downstreams at
        // `currentTick`, then the loop re-checks all threads and picks them up.
        var progress = true
        while (progress && opsThisTick < CpuRules.OPS_PER_TICK_CAP) {
            progress = false
            for (t in threads) {
                if (opsThisTick >= CpuRules.OPS_PER_TICK_CAP) break
                if (!t.isRunning) continue
                val ready = t.readyOps(currentTick)
                if (ready.isEmpty()) continue
                for (op in ready) {
                    if (opsThisTick >= CpuRules.OPS_PER_TICK_CAP) break
                    val result = executor.execute(op)
                    opsThisTick++
                    when (result) {
                        is OpResult.Completed -> {
                            op.inProgress = false
                            t.markCompleted(op.id)
                            progress = true
                        }
                        is OpResult.InProgress -> {
                            op.inProgress = true
                            // Don't increment progress — async op will check again next tick
                        }
                        is OpResult.Failed -> {
                            val p = t.plan
                            t.abort(result.reason)
                            if (p != null) executor.onPlanFailed(p, result.reason)
                            break
                        }
                    }
                }
                // Newly-ready downstream ops — schedule them for same-tick chaining
                t.scheduleNewlyReadyOps(currentTick, opCost)
            }
        }

        // Finalize threads: both DONE (success) and FAILED (abort) return to IDLE so
        // future crafts can be submitted. onPlanFailed already fired in-line in step 2
        // during abort — we don't re-fire it here.
        for (t in threads) {
            when {
                t.isDone -> {
                    val p = t.plan
                    t.clear()
                    if (p != null) executor.onPlanCompleted(p)
                    if (backlog.isNotEmpty()) t.assign(backlog.removeFirst(), currentTick)
                }
                t.isFailed -> {
                    t.clear()
                    if (backlog.isNotEmpty()) t.assign(backlog.removeFirst(), currentTick)
                }
            }
        }
    }

    // =====================================================================
    // Serialization
    // =====================================================================

    fun saveToNBT(tag: CompoundTag) {
        val tList = ListTag()
        for (t in threads) {
            val tt = CompoundTag()
            t.saveToNBT(tt)
            tList.add(tt)
        }
        tag.put("threads", tList)
        val bList = ListTag()
        for (plan in backlog) {
            val pp = CompoundTag()
            plan.saveToNBT(pp)
            bList.add(pp)
        }
        tag.put("backlog", bList)
    }

    fun loadFromNBT(tag: CompoundTag) {
        val tList = tag.getList("threads", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until minOf(tList.size, threads.size)) {
            threads[i].loadFromNBT(tList.getCompound(i))
        }
        backlog.clear()
        val bList = tag.getList("backlog", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until bList.size) {
            CraftPlan.loadFromNBT(bList.getCompound(i))?.let { backlog.addLast(it) }
        }
    }
}
