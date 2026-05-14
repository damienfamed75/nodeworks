package damien.nodeworks.script

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Represents a processing job context passed as the first argument to handler functions.
 *
 * job:pull(card, ...) checks if outputs are available. If ready, extracts atomically
 * into the CPU buffer and signals completion. If not, registers a pending poll on the scheduler.
 *
 * Items stay in the CPU buffer, the caller (releaseCraftResult / finishCrafting) is
 * responsible for flushing them to network storage and releasing the CPU.
 */
class ProcessingJob(
    private val api: ProcessingStorageBlockEntity.ProcessingApiInfo,
    private val cpu: CraftingCoreBlockEntity,
    private val level: ServerLevel,
    private val scheduler: SchedulerImpl,
    private val pendingResult: CraftingHelper.PendingHandlerJob,
    /** Override of how many output items this invocation should collect before completing.
     *  Defaults to the API's per-batch output count. Parallel (non-serial) APIs pass the
     *  full bulk count here so one handler invocation can wait for the full batch worth.
     *  Each entry pairs a recipe ingredient (carrying the variant identity) with a
     *  Long count, so component-bearing outputs survive the override and tryExtract
     *  can filter storage for the specific variant.
     */
    overrideOutputs: List<Pair<damien.nodeworks.script.RecipeIngredient, Long>>? = null,
    /** ID of the [damien.nodeworks.script.cpu.Operation.Process] this handler invocation is
     *  serving. -1 for non-scheduler callers (legacy / scripted resume). When set, the CPU
     *  records per-op resume info so a world reload can pick up the polls without re-invoking
     *  the handler (which would attempt to re-insert items already in machines). */
    private val opId: Int = -1
) {
    /** One pending output: itemId for the filter, componentsHash for variant
     *  matching, template stack for component-aware buffer insertion, and a
     *  mutable remaining count. */
    private data class OutputState(
        val itemId: String,
        val componentsHash: String,
        val template: net.minecraft.world.item.ItemStack,
        var amount: Long,
    )

    private val remaining: MutableList<OutputState> = run {
        val src = overrideOutputs ?: api.outputs.map { it to it.count.toLong() }
        src.map { (ingr, ct) ->
            OutputState(
                itemId = ingr.itemId,
                componentsHash = damien.nodeworks.script.BufferKey.componentsHash(ingr.stack),
                template = ingr.stack.copyWithCount(1),
                amount = ct,
            )
        }.toMutableList()
    }
    private val startGeneration = cpu.jobGeneration

    /** Pulls registered during the most recent handler invocation. The CPU reads this
     *  to cancel them if the handler failed to move its inputs (see
     *  [damien.nodeworks.script.cpu.CpuOpExecutor.executeProcess] retry path). */
    val invocationPulls: MutableList<SchedulerImpl.PendingJob> = mutableListOf()

    /** Called by the CPU at the start of each handler invocation so the pull list
     *  only reflects *this* invocation's registrations (not previous attempts). */
    fun resetInvocationPulls() { invocationPulls.clear() }

    fun toLuaTable(): LuaTable {
        val table = LuaTable()

        val outputsTable = LuaTable()
        for ((i, ingr) in api.outputs.withIndex()) {
            val entry = LuaTable()
            entry.set("id", ingr.itemId)
            entry.set("count", ingr.count)
            outputsTable.set(i + 1, entry)
        }
        table.set("outputs", outputsTable)

        table.set("pull", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val getters = mutableListOf<CardHandle.StorageGetter>()
                val pullTargets = mutableListOf<Pair<net.minecraft.core.BlockPos, net.minecraft.core.Direction>>()
                for (i in 2..args.narg()) {
                    val arg = args.arg(i)
                    if (!arg.istable()) throw LuaError("job:pull() expects CardHandle arguments")
                    val ref = arg.get("_getStorage")
                    if (ref is CardHandle.StorageGetter) {
                        getters.add(ref)
                    } else {
                        throw LuaError("job:pull() expects CardHandle arguments from network:get()")
                    }
                    // Capture target coordinates for persistence
                    val posVal = arg.get("_targetPos")
                    val faceVal = arg.get("_targetFace")
                    if (!posVal.isnil() && !faceVal.isnil()) {
                        pullTargets.add(
                            net.minecraft.core.BlockPos.of(posVal.todouble().toLong()) to
                                net.minecraft.core.Direction.values()[faceVal.toint()]
                        )
                    }
                }
                if (getters.isEmpty()) throw LuaError("job:pull() requires at least one card argument")

                // Try immediate extraction
                if (tryExtract(getters)) {
                    pendingResult.complete(true)
                    return LuaValue.TRUE
                }

                // Async, persist pull targets on CPU for resume after restart.
                // Per-op resume (preferred) lets the new scheduler restart polling on the
                // exact op that was in-flight, the legacy global addPendingOp is still called
                // for backwards compat with the legacy resume path.
                cpu.addPendingOp(
                    api.outputs.map { it.stack.copyWithCount(1) to it.count.toLong() },
                    pullTargets,
                )
                if (opId >= 0) {
                    cpu.setOpResume(opId, CraftingCoreBlockEntity.OpResumeInfo(
                        processingApiName = api.name,
                        outputs = remaining.map { it.template.copyWithCount(1) to it.amount },
                        pullTargets = pullTargets
                    ))
                }

                // Register for polling each tick
                val timeoutTicks = if (api.timeout > 0) api.timeout.toLong() else 6000L
                val pending = SchedulerImpl.PendingJob(
                    pollFn = { tryExtract(getters) },
                    timeoutAt = scheduler.currentTick + timeoutTicks,
                    onComplete = { success -> pendingResult.complete(success) },
                    label = "pull:${api.name}"
                )
                scheduler.addPendingJob(pending)
                invocationPulls.add(pending)

                return LuaValue.NIL
            }
        })

        return table
    }

    private val isStale: Boolean get() = cpu.jobGeneration != startGeneration

    /**
     * Incremental: each poll grabs whatever's currently available for each declared output
     * and decrements the remaining count. Only returns true (job complete) once every
     * declared count has been fully collected. For a 4-ingot smelt on a furnace that emits
     * one ingot every few seconds, this means we pull each ingot as it arrives instead of
     * waiting for all 4 to pile up and blocking subsequent smelts.
     */
    private fun tryExtract(getters: List<CardHandle.StorageGetter>): Boolean {
        val stale = isStale
        val snapshot = if (stale) damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, cpu.blockPos) else null

        val iter = remaining.listIterator()
        while (iter.hasNext()) {
            val out = iter.next()
            var stillNeeded = out.amount
            // Variant-aware filter: pull only stacks whose itemId AND
            // componentsHash match the declared output. Without this a
            // poison potion sitting in the output card would satisfy a
            // strength-potion output because both share `minecraft:potion`.
            val stackFilter: (net.minecraft.world.item.ItemStack) -> Boolean = { stack ->
                val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                sid == out.itemId &&
                    damien.nodeworks.script.BufferKey.componentsHash(stack) == out.componentsHash
            }
            for (getter in getters) {
                if (stillNeeded <= 0L) break
                val storage = getter.getStorage() ?: continue
                val extractedStacks = PlatformServices.storage.extractStacksByPredicate(
                    storage, stackFilter, stillNeeded
                )
                for (stack in extractedStacks) {
                    if (stack.isEmpty) continue
                    val amount = stack.count.toLong()
                    if (stale) {
                        // Stale-recovery path: the CPU's job context outlived the
                        // network it was on, so we route the recovered stack back
                        // into the live network as-is (components preserved).
                        NetworkStorageHelper.insertItemStack(level, snapshot!!, stack, null)
                    } else {
                        cpu.addToBuffer(stack, amount)
                    }
                    stillNeeded -= amount
                }
            }
            if (stillNeeded <= 0L) {
                iter.remove()
            } else {
                out.amount = stillNeeded
            }
        }

        if (remaining.isEmpty()) {
            cpu.completePendingOp()
            cpu.clearOpResume(opId)
            return true
        }
        // Update per-op resume info so a save mid-stream resumes with the right remaining count.
        if (opId >= 0 && cpu.opResumeInfo[opId] != null) {
            val existing = cpu.opResumeInfo[opId]!!
            cpu.setOpResume(opId, existing.copy(
                outputs = remaining.map { it.template.copyWithCount(1) to it.amount }
            ))
        }
        return false
    }

    /**
     * Public entry for resume / block-handler polling, bypasses the Lua API.
     *
     * Non-empty [pullTargets] (the block-handler path) persists the same
     * `addPendingOp` + `setOpResume` resume state the Lua `job:pull` path
     * records, so a reload mid-process restarts the poll instead of
     * re-invoking the handler against an already-drained buffer. The resume
     * path passes none: its [opResumeInfo] entry already exists on disk.
     */
    fun startPoll(
        getters: List<CardHandle.StorageGetter>,
        pullTargets: List<Pair<net.minecraft.core.BlockPos, net.minecraft.core.Direction>> = emptyList(),
    ) {
        if (tryExtract(getters)) {
            pendingResult.complete(true)
            return
        }
        if (pullTargets.isNotEmpty()) {
            cpu.addPendingOp(
                api.outputs.map { it.stack.copyWithCount(1) to it.count.toLong() },
                pullTargets,
            )
            if (opId >= 0) {
                cpu.setOpResume(opId, CraftingCoreBlockEntity.OpResumeInfo(
                    processingApiName = api.name,
                    outputs = remaining.map { it.template.copyWithCount(1) to it.amount },
                    pullTargets = pullTargets,
                ))
            }
        }
        val timeoutTicks = if (api.timeout > 0) api.timeout.toLong() else 6000L
        scheduler.addPendingJob(SchedulerImpl.PendingJob(
            pollFn = { tryExtract(getters) },
            timeoutAt = scheduler.currentTick + timeoutTicks,
            onComplete = { success -> pendingResult.complete(success) },
            label = "resume:${api.name}"
        ))
    }
}
