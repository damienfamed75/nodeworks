package damien.nodeworks.script.cpu

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.script.BufferSource
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.CraftingHelper
import damien.nodeworks.script.ItemsHandle
import damien.nodeworks.script.NetworkStorageHelper
import damien.nodeworks.script.ProcessingJob
import damien.nodeworks.script.ScriptEngine
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import org.luaj.vm2.LuaValue
import org.slf4j.LoggerFactory

/**
 * The Crafting Core's side of [CraftScheduler]. Translates abstract [Operation]s into
 * real effects on network storage, the CPU buffer, and processing-set handlers.
 *
 * Async state for in-progress [Operation.Process] ops is held in [processState] and
 * cleared on cancellation or plan completion. The scheduler re-invokes
 * [execute] each tick for in-progress ops — this class reports [OpResult.InProgress]
 * until the underlying pending job completes.
 *
 * Throttle is hardcoded to [DEFAULT_THROTTLE] for Phase 2 (produces op cost 0 — ops chain
 * within the same tick, preserving existing craft timing). Phase 4 replaces this with a
 * real computation from heat/cooling/substrate state.
 */
class CpuOpExecutor(private val cpu: CraftingCoreBlockEntity) : CraftScheduler.OpExecutor {

    private val logger = LoggerFactory.getLogger("nodeworks-cpu-executor")

    /** Per-op async state for Process ops currently waiting on their `job:pull` callbacks. */
    private data class ProcessState(
        val pending: CraftingHelper.PendingHandlerJob,
        val processingJob: damien.nodeworks.script.ProcessingJob,
        val handler: org.luaj.vm2.LuaFunction,
        val luaArgs: List<org.luaj.vm2.LuaValue>,
        val scheduler: damien.nodeworks.script.SchedulerImpl
    )
    private val processState = mutableMapOf<Int, ProcessState>()

    /** Cumulative handler retries per op — survives across [processState] entries being
     *  added/removed each retry cycle so the cap is on TOTAL attempts for the op. */
    private val processRetries = mutableMapOf<Int, Int>()

    /** Seconds between handler retries when inputs went unmoved. One second feels like
     *  "the CPU is waiting for something" without being laggy. */
    private val RETRY_BACKOFF_TICKS = 20L
    private val MAX_HANDLER_RETRIES = 50

    /** Caller-supplied completion listeners. Not persisted — on world reload resumed
     *  plans complete silently (the caller's session is gone anyway). */
    private val completionListeners = mutableMapOf<CraftPlan, (Boolean) -> Unit>()

    /** Called by [CraftingCoreBlockEntity.submitCraft] to register a completion callback.
     *  Fires exactly once when the plan reaches DONE or FAILED. */
    fun registerCompletionListener(plan: CraftPlan, onComplete: (Boolean) -> Unit) {
        completionListeners[plan] = onComplete
    }

    override val currentThrottle: Float get() = DEFAULT_THROTTLE

    override fun execute(op: Operation): CraftScheduler.OpResult {
        val lvl = cpu.level as? ServerLevel
            ?: return CraftScheduler.OpResult.Failed("No server level")

        // Rebuild snapshot each execute — cheap enough at our network scales, and always
        // reflects the latest storage state (other crafts may have moved items concurrently).
        val snapshot = NetworkDiscovery.discoverNetwork(lvl, cpu.blockPos)

        return try {
            when (op) {
                is Operation.Pull -> executePull(op, lvl, snapshot)
                is Operation.Process -> executeProcess(op, lvl, snapshot)
                is Operation.Execute -> executeExecute(op, lvl)
                is Operation.Deliver -> executeDeliver(op, lvl, snapshot)
            }
        } catch (e: Exception) {
            logger.warn("Op {} execution threw: {}", op, e.message, e)
            CraftScheduler.OpResult.Failed("Executor threw: ${e.message}")
        }
    }

    // =====================================================================
    // Pull — network storage → buffer
    // =====================================================================

    private fun executePull(
        op: Operation.Pull,
        lvl: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot
    ): CraftScheduler.OpResult {
        var remaining = op.amount
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L) break
            val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
            val extracted = PlatformServices.storage.extractItems(
                storage, { CardHandle.matchesFilter(it, op.itemId) }, remaining
            )
            if (extracted > 0L) {
                if (!cpu.addToBuffer(op.itemId, extracted)) {
                    // Buffer refused — this shouldn't happen if feasibility passed,
                    // but if it does, put the extracted back and fail cleanly.
                    tryReturnToStorage(op.itemId, extracted, lvl, snapshot)
                    return CraftScheduler.OpResult.Failed(
                        "Buffer refused $extracted ${op.itemId} (full)"
                    )
                }
                remaining -= extracted
            }
        }
        return if (remaining == 0L) {
            CraftScheduler.OpResult.Completed
        } else {
            CraftScheduler.OpResult.Failed(
                "Pulled ${op.amount - remaining} of ${op.itemId}, ${remaining} still missing"
            )
        }
    }

    // =====================================================================
    // Execute — vanilla-style 3x3 crafting inside the buffer
    // =====================================================================

    private fun executeExecute(
        op: Operation.Execute,
        lvl: ServerLevel
    ): CraftScheduler.OpResult {
        val recipeManager = lvl.recipeManager ?: return CraftScheduler.OpResult.Failed("No recipe manager")

        val ingredientCounts = mutableMapOf<String, Int>()
        for (id in op.recipe) if (id.isNotEmpty()) ingredientCounts.merge(id, 1, Int::plus)

        val items = op.recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = ResourceLocation.tryParse(itemId)
                    ?: return CraftScheduler.OpResult.Failed("Bad item id in recipe: $itemId")
                val item = BuiltInRegistries.ITEM.get(id)
                    ?: return CraftScheduler.OpResult.Failed("Unknown item in recipe: $itemId")
                ItemStack(item, 1)
            }
        }
        val craftingInput = CraftingInput.of(3, 3, items)
        val recipeHolder = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, craftingInput, lvl)
            .orElse(null)
            ?: return CraftScheduler.OpResult.Failed("No crafting recipe matched")
        val expected = recipeHolder.value().assemble(craftingInput, lvl.registryAccess())
        if (expected.isEmpty) {
            return CraftScheduler.OpResult.Failed("Recipe assemble returned empty")
        }
        val expectedItemId = BuiltInRegistries.ITEM.getKey(expected.item)?.toString()
            ?: return CraftScheduler.OpResult.Failed("Could not resolve output item id")

        // Derive executions from desired output count and this recipe's per-batch output.
        // Planner sets outputCount = requested total; we divide by expected.count (e.g. 9 for
        // ingot→nuggets) so a craft of 9 nuggets via a 1→9 recipe runs the recipe once, not
        // nine times.
        val perBatch = expected.count.coerceAtLeast(1).toLong()
        val desired = op.outputCount.coerceAtLeast(1L)
        val executions = ((desired + perBatch - 1) / perBatch).coerceAtLeast(1L)
        var done = 0L
        while (done < executions) {
            // Consume ingredients
            for ((ing, needed) in ingredientCounts) {
                val removed = cpu.removeFromBuffer(ing, needed.toLong())
                if (removed < needed) {
                    // Partial failure — put back what we already consumed this iteration, abort
                    cpu.addToBuffer(ing, removed)
                    return CraftScheduler.OpResult.Failed(
                        "Execute iteration underflow: $ing x$needed (had $removed)"
                    )
                }
            }
            val output = expected.copy().apply { count = expected.count }
            if (!cpu.addToBuffer(expectedItemId, output.count.toLong())) {
                return CraftScheduler.OpResult.Failed(
                    "Buffer refused crafted $expectedItemId x${output.count}"
                )
            }
            done++
        }
        return CraftScheduler.OpResult.Completed
    }

    // =====================================================================
    // Deliver — buffer → network storage / reserved slot
    // =====================================================================

    private fun executeDeliver(
        op: Operation.Deliver,
        lvl: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot
    ): CraftScheduler.OpResult {
        val inBuffer = cpu.getBufferCount(op.itemId)
        if (inBuffer < op.amount) {
            return CraftScheduler.OpResult.Failed(
                "Deliver wanted ${op.amount} of ${op.itemId} but buffer has $inBuffer"
            )
        }
        val removed = cpu.removeFromBuffer(op.itemId, op.amount)
        val id = ResourceLocation.tryParse(op.itemId)
            ?: return CraftScheduler.OpResult.Failed("Bad item id: ${op.itemId}")
        val item = BuiltInRegistries.ITEM.get(id)
            ?: return CraftScheduler.OpResult.Failed("Unknown item: ${op.itemId}")

        var remaining = removed
        while (remaining > 0L) {
            val batch = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
            val stack = ItemStack(item, batch)
            val inserted = NetworkStorageHelper.insertItemStack(lvl, snapshot, stack, null)
            if (inserted == 0) {
                // Storage refused — drop the rest in-world rather than failing the op.
                // Failing here would leave the thread FAILED and the CPU unable to accept
                // new crafts; dropping is consistent with how cancelJob / onPlanFailed handle
                // overflow, and keeps the craft technically successful.
                logger.warn(
                    "Deliver: network storage refused {}, dropping {} in-world at {}",
                    op.itemId, remaining, cpu.blockPos
                )
                val dropStack = ItemStack(item, batch)
                net.minecraft.world.Containers.dropItemStack(
                    lvl, cpu.blockPos.x + 0.5, cpu.blockPos.y + 1.0, cpu.blockPos.z + 0.5, dropStack
                )
                remaining -= batch.toLong()
            } else {
                remaining -= inserted.toLong()
            }
        }
        return CraftScheduler.OpResult.Completed
    }

    // =====================================================================
    // Process — invoke a processing-set Lua handler and await its pulls
    // =====================================================================

    private fun executeProcess(
        op: Operation.Process,
        lvl: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot
    ): CraftScheduler.OpResult {
        // If we've already invoked the handler, we're either polling the pending job OR
        // waiting out a retry backoff to re-invoke the handler.
        val existing = processState[op.id]
        if (existing != null) {
            if (existing.pending.isComplete) {
                val ok = existing.pending.success
                processState.remove(op.id)
                processRetries.remove(op.id)
                return if (ok) CraftScheduler.OpResult.Completed
                else CraftScheduler.OpResult.Failed("Processing handler failed: ${op.processingApiName}")
            }
            // Retry backoff expired? If op.readyAt was bumped forward we'd already be waiting;
            // this branch is the normal "still polling" case.
            return CraftScheduler.OpResult.InProgress
        }

        // -----------------------------------------------------------------
        // First invocation (or retry with re-resolved state): resolve handler + invoke + check
        // -----------------------------------------------------------------
        val outputItemId = op.outputs.firstOrNull()?.first
            ?: return CraftScheduler.OpResult.Failed("Process op has no outputs declared")
        val apiMatch = snapshot.findProcessingApi(outputItemId)
            ?: return CraftScheduler.OpResult.Failed("No processing API for $outputItemId")
        val searchPositions = apiMatch.apiStorage.remoteTerminalPositions ?: snapshot.terminalPositions
        val handlerEngine = PlatformServices.modState
            .findProcessingEngine(lvl, searchPositions, apiMatch.api.name) as? ScriptEngine
            ?: return CraftScheduler.OpResult.Failed(
                "No handler loaded for '${apiMatch.api.name}' — start the terminal script first"
            )
        val handler = handlerEngine.processingHandlers[apiMatch.api.name]
            ?: return CraftScheduler.OpResult.Failed("Handler not registered: ${apiMatch.api.name}")

        // Inputs arrive pre-aggregated by the planner, but defend against bugs: collapse duplicates.
        val aggregatedInputs = op.inputs.groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.sum() }
        for ((id, amount) in aggregatedInputs) {
            if (cpu.getBufferCount(id) < amount) {
                return CraftScheduler.OpResult.Failed(
                    "Process input missing: $id x$amount (buffer has ${cpu.getBufferCount(id)})"
                )
            }
        }

        // Serial mode: only one handler per named API runs concurrently across the whole server.
        if (apiMatch.api.serial && apiMatch.api.name in CraftingHelper.activeSerialJobsView) {
            return CraftScheduler.OpResult.InProgress
        }

        val pending = CraftingHelper.PendingHandlerJob()
        if (apiMatch.api.serial) {
            CraftingHelper.addActiveSerialJob(apiMatch.api.name)
            pending.onCompleteCallback = { CraftingHelper.removeActiveSerialJob(apiMatch.api.name) }
        }

        val scheduler = handlerEngine.scheduler
        val job = ProcessingJob(apiMatch.api, cpu, lvl, scheduler, pending)
        val jobTable = job.toLuaTable()

        // Build Lua args: job + one ItemsHandle per unique input (post-aggregation).
        val luaArgs = mutableListOf<LuaValue>(jobTable)
        for ((itemId, count) in aggregatedInputs) {
            val id = ResourceLocation.tryParse(itemId)
                ?: return CraftScheduler.OpResult.Failed("Bad input item id: $itemId")
            val item = BuiltInRegistries.ITEM.get(id)
                ?: return CraftScheduler.OpResult.Failed("Unknown input item: $itemId")
            luaArgs.add(
                ItemsHandle.toLuaTable(
                    ItemsHandle(
                        itemId = itemId,
                        itemName = ItemStack(item).hoverName.string,
                        count = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        maxStackSize = item.getDefaultMaxStackSize(),
                        hasData = false,
                        filter = itemId,
                        sourceStorage = { null },
                        level = lvl,
                        bufferSource = BufferSource(cpu, itemId, count)
                    )
                )
            )
        }

        cpu.setCrafting(true, outputItemId.substringAfter(':').replace('_', ' '))

        val state = ProcessState(
            pending = pending,
            processingJob = job,
            handler = handler,
            luaArgs = luaArgs,
            scheduler = scheduler
        )
        return invokeHandlerAndAnalyze(op, state, aggregatedInputs, apiMatch.api.name)
    }

    /**
     * Invoke the handler once and inspect the side effects.
     *
     * The core safety guarantee: after this returns, the CPU buffer either
     *   (a) has the declared inputs fully consumed, or
     *   (b) has exactly as many of each input as it started with (handler made zero moves).
     * The partial case is a contract violation — we fail the op loudly and the buffer flushes
     * back to storage on onPlanFailed.
     *
     * For case (b), we cancel any `job:pull` calls the handler registered (they're now
     * watching for items that will never arrive) and re-run the op later via readyAt backoff.
     */
    private fun invokeHandlerAndAnalyze(
        op: Operation.Process,
        state: ProcessState,
        declaredInputs: Map<String, Long>,
        apiName: String
    ): CraftScheduler.OpResult {
        val pre = declaredInputs.mapValues { (id, _) -> cpu.getBufferCount(id) }

        state.processingJob.resetInvocationPulls()
        val result = try {
            when (state.luaArgs.size) {
                1 -> state.handler.call(state.luaArgs[0])
                2 -> state.handler.call(state.luaArgs[0], state.luaArgs[1])
                3 -> state.handler.call(state.luaArgs[0], state.luaArgs[1], state.luaArgs[2])
                else -> state.handler.invoke(LuaValue.varargsOf(state.luaArgs.toTypedArray())).arg1()
            }
        } catch (e: org.luaj.vm2.LuaError) {
            logger.warn("Processing handler error for '{}': {}", apiName, e.message)
            cancelInvocationPulls(state)
            return CraftScheduler.OpResult.Failed("Handler threw: ${e.message}")
        }

        // Explicit false return → immediate retry (handler opted into deferral)
        if (!result.isnil() && result.isboolean() && !result.toboolean()) {
            cancelInvocationPulls(state)
            return scheduleRetry(op, state, "handler returned false")
        }

        // Conservation check — diff buffer vs pre-invocation
        var allConsumed = true
        var anyConsumed = false
        var stranded: String? = null
        for ((id, required) in declaredInputs) {
            val preCount = pre[id] ?: 0L
            val now = cpu.getBufferCount(id)
            val consumed = preCount - now  // items that actually left the buffer
            when {
                consumed == required -> anyConsumed = true
                consumed == 0L -> allConsumed = false
                else -> { // partial move for this input
                    stranded = id
                    allConsumed = false
                }
            }
        }

        if (stranded != null) {
            // Partial consumption — contract violation. Fail loudly; onPlanFailed flushes.
            cancelInvocationPulls(state)
            return CraftScheduler.OpResult.Failed(
                "Handler '$apiName' partially consumed input '$stranded' — " +
                "all inputs must move together (use :hasSpaceFor or restructure the handler)."
            )
        }

        if (!allConsumed) {
            // Nothing moved. Unwind pulls and retry later.
            cancelInvocationPulls(state)
            return scheduleRetry(op, state, "destination unavailable")
        }

        // All inputs consumed. Handler must have registered at least one pull — otherwise
        // the declared outputs have no path to the buffer.
        if (state.processingJob.invocationPulls.isEmpty() && !state.pending.isComplete) {
            return CraftScheduler.OpResult.Failed(
                "Handler '$apiName' consumed inputs without calling job:pull. " +
                "Outputs can never arrive."
            )
        }

        if (state.pending.isComplete) {
            return if (state.pending.success) CraftScheduler.OpResult.Completed
            else CraftScheduler.OpResult.Failed("Pending job failed synchronously: $apiName")
        }

        processState[op.id] = state
        return CraftScheduler.OpResult.InProgress
    }

    private fun cancelInvocationPulls(state: ProcessState) {
        for (pull in state.processingJob.invocationPulls) {
            state.scheduler.removePendingJob(pull)
        }
        state.processingJob.invocationPulls.clear()
    }

    private fun scheduleRetry(
        op: Operation.Process,
        state: ProcessState,
        reason: String
    ): CraftScheduler.OpResult {
        val count = (processRetries[op.id] ?: 0) + 1
        processRetries[op.id] = count
        if (count > MAX_HANDLER_RETRIES) {
            processRetries.remove(op.id)
            return CraftScheduler.OpResult.Failed(
                "Processing handler '${op.processingApiName}' couldn't progress for " +
                "$MAX_HANDLER_RETRIES retries ($reason). Destinations may be permanently unavailable."
            )
        }
        val lvl = cpu.level
        val now = if (lvl is ServerLevel) lvl.gameTime else 0L
        op.readyAt = now + RETRY_BACKOFF_TICKS
        // Explicitly remove processState so the next dispatch re-invokes the handler fresh
        // rather than polling a never-completing pending. state is discarded here — the retry
        // builds a fresh ProcessingJob + pending next time.
        processState.remove(op.id)
        return CraftScheduler.OpResult.InProgress
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onPlanCompleted(plan: CraftPlan) {
        // Any trailing buffer contents (shouldn't happen if Deliver ran) are flushed.
        flushBufferToStorage()
        cpu.clearAllCraftState()
        cpu.setCrafting(false)
        completionListeners.remove(plan)?.invoke(true)
    }

    /** Set by [damien.nodeworks.block.entity.CraftingCoreBlockEntity.cancelJob] so that
     *  onPlanFailed drops the buffer in-world instead of returning it to network storage.
     *  Without this, cancelling a craft whose Execute/Process ops already produced the final
     *  item would silently deliver that item into storage — a dupe from the player's POV. */
    var userCancelledDropInWorld: Boolean = false

    override fun onPlanFailed(plan: CraftPlan, reason: String) {
        logger.warn("CPU at {}: plan for {} failed: {}", cpu.blockPos, plan.rootItemId, reason)
        // Clear any lingering Process async state for this plan
        val planOpIds = plan.ops.map { it.id }.toSet()
        processState.keys.retainAll { it !in planOpIds }

        if (userCancelledDropInWorld) {
            dropBufferInWorld()
        } else {
            flushBufferToStorage()
        }
        cpu.clearAllCraftState()
        cpu.setCrafting(false)
        completionListeners.remove(plan)?.invoke(false)
    }

    private fun dropBufferInWorld() {
        val lvl = cpu.level as? ServerLevel ?: return
        val leftovers = cpu.clearBuffer()
        for ((itemId, count) in leftovers) {
            val id = ResourceLocation.tryParse(itemId) ?: continue
            val item = BuiltInRegistries.ITEM.get(id) ?: continue
            var remaining = count
            while (remaining > 0L) {
                val batch = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
                net.minecraft.world.Containers.dropItemStack(
                    lvl, cpu.blockPos.x + 0.5, cpu.blockPos.y + 1.0, cpu.blockPos.z + 0.5,
                    ItemStack(item, batch)
                )
                remaining -= batch.toLong()
            }
        }
    }

    private fun flushBufferToStorage() {
        val lvl = cpu.level as? ServerLevel ?: return
        val snap = NetworkDiscovery.discoverNetwork(lvl, cpu.blockPos)
        val leftovers = cpu.clearBuffer()
        for ((itemId, count) in leftovers) {
            tryReturnToStorage(itemId, count, lvl, snap)
        }
    }

    private fun tryReturnToStorage(
        itemId: String,
        count: Long,
        lvl: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot
    ) {
        val id = ResourceLocation.tryParse(itemId) ?: return
        val item = BuiltInRegistries.ITEM.get(id) ?: return
        var remaining = count
        while (remaining > 0L) {
            val batch = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
            val stack = ItemStack(item, batch)
            val inserted = NetworkStorageHelper.insertItemStack(lvl, snapshot, stack, null)
            if (inserted == 0) {
                // Drop as item entity — better than deleting
                net.minecraft.world.Containers.dropItemStack(
                    lvl,
                    cpu.blockPos.x + 0.5, cpu.blockPos.y + 1.0, cpu.blockPos.z + 0.5, stack
                )
                remaining -= batch.toLong()
            } else {
                remaining -= inserted.toLong()
            }
        }
    }

    companion object {
        /** Phase 2 placeholder throttle — produces op cost 0, so ops chain within a single
         *  tick and existing craft timing is preserved. Phase 4 replaces this with a
         *  computation from heat/cooling/substrate state. */
        const val DEFAULT_THROTTLE: Float = 10.0f
    }
}
