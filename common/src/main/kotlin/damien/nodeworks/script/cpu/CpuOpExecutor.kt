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
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.slf4j.LoggerFactory

/**
 * The Crafting Core's side of [CraftScheduler]. Translates abstract [Operation]s into
 * real effects on network storage, the CPU buffer, and processing-set handlers.
 *
 * Async state for in-progress [Operation.Process] ops is held in [processState] and
 * cleared on cancellation or plan completion. The scheduler re-invokes
 * [execute] each tick for in-progress ops, this class reports [OpResult.InProgress]
 * until the underlying pending job completes.
 *
 * Throttle is hardcoded to [DEFAULT_THROTTLE] for Phase 2 (produces op cost 0, ops chain
 * within the same tick, preserving existing craft timing). Phase 4 replaces this with a
 * real computation from heat/cooling/substrate state.
 */
class CpuOpExecutor(private val cpu: CraftingCoreBlockEntity) : CraftScheduler.OpExecutor {

    private val logger = LoggerFactory.getLogger("nodeworks-cpu-executor")

    /** Per-op async state for Process ops currently waiting on their `job:pull` callbacks.
     *  [handler] and [luaArgs] are null/empty for resume-path entries that never invoke
     *  the handler (items are already in the machine, we only poll). */
    private data class ProcessState(
        val pending: CraftingHelper.PendingHandlerJob,
        val processingJob: damien.nodeworks.script.ProcessingJob,
        val handler: org.luaj.vm2.LuaFunction?,
        val luaArgs: List<org.luaj.vm2.LuaValue>,
        val scheduler: damien.nodeworks.script.SchedulerImpl
    )
    private val processState = mutableMapOf<Int, ProcessState>()

    /** Per-op batch progress for Process ops that represent multiple handler invocations
     *  (e.g. a "smelt 5 iron" op cycles through 5 single-batch invocations internally).
     *  Separate from [processState] because state gets rebuilt each invocation while this
     *  persists across them. */
    private val processBatchProgress = mutableMapOf<Int, BatchProgress>()

    /** Op IDs whose handler invocation is currently active (waiting on async pulls or
     *  mid-batch). Read by the GUI sync to highlight in-progress tree nodes. */
    val activeProcessOpIds: Set<Int> get() = processBatchProgress.keys
    private data class BatchProgress(
        val totalBatches: Long,
        var batchesDone: Long,
        /** Inputs consumed per batch (aggregated from the op's declared total inputs). */
        val perBatchInputs: List<Pair<String, Long>>,
        /** Output produced per batch (aggregated from api.outputs). */
        val outputItemId: String,
        val outputPerBatch: Long
    )

    /** Cumulative handler retries per op, survives across [processState] entries being
     *  added/removed each retry cycle so the cap is on TOTAL attempts for the op. */
    private val processRetries = mutableMapOf<Int, Int>()

    /** Seconds between handler retries when inputs went unmoved. One second feels like
     *  "the CPU is waiting for something" without being laggy. */
    private val RETRY_BACKOFF_TICKS = 20L

    /** Per-op retry cap. Read live from the Network Controller for this CPU's network so
     *  players can tune it in the Controller GUI without a server restart. Falls back to
     *  50 if no Controller is reachable (legacy default). */
    private fun maxHandlerRetries(): Int {
        val lvl = cpu.level ?: return 50
        return damien.nodeworks.render.NodeConnectionRenderer
            .findController(lvl, cpu.blockPos)?.handlerRetryLimit ?: 50
    }

    /** Caller-supplied completion listeners. Not persisted, on world reload resumed
     *  plans complete silently (the caller's session is gone anyway). */
    private val completionListeners = mutableMapOf<CraftPlan, (Boolean) -> Unit>()

    /** Called by [CraftingCoreBlockEntity.submitCraft] to register a completion callback.
     *  Fires exactly once when the plan reaches DONE or FAILED. */
    fun registerCompletionListener(plan: CraftPlan, onComplete: (Boolean) -> Unit) {
        completionListeners[plan] = onComplete
    }

    /** Read live from the CPU so heat/cooling changes (placing/breaking Stabilizers) take
     *  effect immediately. Falls back to [DEFAULT_THROTTLE] when the CPU isn't formed. */
    override val currentThrottle: Float
        get() = if (cpu.isFormed) cpu.throttle.coerceAtLeast(CpuRules.THROTTLE_FLOOR)
                else DEFAULT_THROTTLE

    /** Snapshot cached for the duration of one [CraftScheduler.tick]. Every op in the
     *  tick (and the post-loop completion / failure handlers) shares one discovery walk
     *  instead of redoing the BFS per call. The cache is null between ticks and outside
     *  the tick lifecycle (e.g. when [onPlanFailed] fires from [CraftScheduler.cancelAll]),
     *  in which case [snapshotForTick] falls back to a one-off discovery. */
    private var cachedSnapshot: damien.nodeworks.network.NetworkSnapshot? = null
    private var insideTick = false

    override fun onTickStart() {
        insideTick = true
        // Discovery is deferred to the first [snapshotForTick] call, idle ticks (state
        // != RUNNING and no completion handlers fire) pay nothing.
    }

    override fun onTickEnd() {
        cachedSnapshot = null
        insideTick = false
    }

    /** Resolve the network snapshot for the current tick. Inside a tick the result is
     *  cached, every later call in the same tick reuses it. Outside the tick lifecycle
     *  (e.g. [onPlanFailed] from [CraftScheduler.cancelAll], which runs synchronously
     *  outside [tick]), we discover live each call so callers stay correct. */
    private fun snapshotForTick(lvl: ServerLevel): damien.nodeworks.network.NetworkSnapshot {
        if (insideTick) {
            cachedSnapshot?.let { return it }
            val fresh = NetworkDiscovery.discoverNetwork(lvl, cpu.blockPos)
            cachedSnapshot = fresh
            return fresh
        }
        return NetworkDiscovery.discoverNetwork(lvl, cpu.blockPos)
    }

    override fun execute(op: Operation): CraftScheduler.OpResult {
        val lvl = cpu.level as? ServerLevel
            ?: return CraftScheduler.OpResult.Failed("No server level")

        val snapshot = snapshotForTick(lvl)

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
    // Pull, network storage → buffer
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
                    // Buffer refused, this shouldn't happen if feasibility passed,
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
    // Execute, vanilla-style 3x3 crafting inside the buffer
    // =====================================================================

    private fun executeExecute(
        op: Operation.Execute,
        lvl: ServerLevel
    ): CraftScheduler.OpResult {
        val recipeManager = lvl.recipeAccess() ?: return CraftScheduler.OpResult.Failed("No recipe manager")

        val ingredientCounts = mutableMapOf<String, Int>()
        for (id in op.recipe) if (id.isNotEmpty()) ingredientCounts.merge(id, 1, Int::plus)

        val items = op.recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = Identifier.tryParse(itemId)
                    ?: return CraftScheduler.OpResult.Failed("Bad item id in recipe: $itemId")
                val item = BuiltInRegistries.ITEM.getValue(id)
                    ?: return CraftScheduler.OpResult.Failed("Unknown item in recipe: $itemId")
                ItemStack(item, 1)
            }
        }
        val craftingInput = CraftingInput.of(3, 3, items)
        val recipeHolder = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, craftingInput, lvl)
            .orElse(null)
            ?: return CraftScheduler.OpResult.Failed("No crafting recipe matched")
        val expected = recipeHolder.value().assemble(craftingInput)
        if (expected.isEmpty) {
            return CraftScheduler.OpResult.Failed("Recipe assemble returned empty")
        }
        val expectedItemId = BuiltInRegistries.ITEM.getKey(expected.item)?.toString()
            ?: return CraftScheduler.OpResult.Failed("Could not resolve output item id")

        // Derive executions from desired output count and this recipe's per-batch output.
        // Planner sets outputCount = requested total, we divide by expected.count (e.g. 9 for
        // ingot→nuggets) so a craft of 9 nuggets via a 1→9 recipe runs the recipe once, not
        // nine times.
        val perBatch = expected.getCount().coerceAtLeast(1).toLong()
        val desired = op.outputCount.coerceAtLeast(1L)
        val executions = ((desired + perBatch - 1) / perBatch).coerceAtLeast(1L)
        var done = 0L
        while (done < executions) {
            // Consume ingredients
            for ((ing, needed) in ingredientCounts) {
                val removed = cpu.removeFromBuffer(ing, needed.toLong())
                if (removed < needed) {
                    // Partial failure, put back what we already consumed this iteration, abort
                    cpu.addToBuffer(ing, removed)
                    return CraftScheduler.OpResult.Failed(
                        "Execute iteration underflow: $ing x$needed (had $removed)"
                    )
                }
            }
            val output = expected.copyWithCount(expected.getCount())
            if (!cpu.addToBuffer(expectedItemId, output.getCount().toLong())) {
                return CraftScheduler.OpResult.Failed(
                    "Buffer refused crafted $expectedItemId x${output.getCount()}"
                )
            }
            done++
        }
        return CraftScheduler.OpResult.Completed
    }

    // =====================================================================
    // Deliver, buffer → network storage / reserved slot
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
        val id = Identifier.tryParse(op.itemId)
            ?: return CraftScheduler.OpResult.Failed("Bad item id: ${op.itemId}")
        val item = BuiltInRegistries.ITEM.getValue(id)
            ?: return CraftScheduler.OpResult.Failed("Unknown item: ${op.itemId}")

        // Update the network's inventory cache as items land so the Inventory
        // Terminal's grid count reflects the post-Deliver storage immediately.
        // Without this, the cache only catches up via its periodic poll, and a
        // queued craft's reserved-row claim subtracted reservations from a
        // stale cache count - displaying 0 stock during the gap until the
        // poll caught up.
        val deliverCache = damien.nodeworks.script.NetworkInventoryCache.getOrCreate(lvl, cpu.blockPos)
        var remaining = removed
        var droppedCount = 0L
        while (remaining > 0L) {
            val batch = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
            val stack = ItemStack(item, batch)
            val inserted = NetworkStorageHelper.insertItemStack(lvl, snapshot, stack, deliverCache)
            if (inserted == 0) {
                // Network storage refused, drop the batch on the ground so the finished
                // items aren't destroyed, and track how many we had to drop. We still fail
                // the op after the loop so the player gets a visible error (same flow as
                // any other craft failure: onPlanFailed → cpu.lastFailureReason + chat).
                // The CPU goes back to IDLE and can accept new crafts, so the failure is
                // surfacing only, not locking the CPU.
                logger.warn(
                    "Deliver: network storage refused {}, dropping {} in-world at {}",
                    op.itemId, batch, cpu.blockPos
                )
                val dropStack = ItemStack(item, batch)
                net.minecraft.world.Containers.dropItemStack(
                    lvl, cpu.blockPos.x + 0.5, cpu.blockPos.y + 1.0, cpu.blockPos.z + 0.5, dropStack
                )
                droppedCount += batch.toLong()
                remaining -= batch.toLong()
            } else {
                remaining -= inserted.toLong()
            }
        }
        if (droppedCount > 0L) {
            val itemName = op.itemId.substringAfter(':').replace('_', ' ')
            return CraftScheduler.OpResult.Failed(
                "Network storage full, dropped $droppedCount × $itemName on ground"
            )
        }
        return CraftScheduler.OpResult.Completed
    }

    // =====================================================================
    // Process, invoke a processing-set Lua handler and await its pulls
    // =====================================================================

    /** Result of [resolveBatchProgress]: either a usable [BatchProgress] or
     *  the player-facing message to fail the op with when the buffer is short
     *  on declared inputs. */
    private sealed class BatchSetup {
        data class Ok(val progress: BatchProgress) : BatchSetup()
        data class Insufficient(val message: String) : BatchSetup()
    }

    /** Resolve (or build + cache) the per-op [BatchProgress] for a processing
     *  recipe. Shared by the Lua handler path ([executeProcess]) and the
     *  block handler path ([executeBlockHandler]) so the buffer pre-check
     *  and per-batch input scaling stay identical. */
    private fun resolveBatchProgress(
        op: Operation.Process,
        apiMatch: damien.nodeworks.network.ProcessingApiMatch,
        outputItemId: String,
    ): BatchSetup {
        processBatchProgress[op.id]?.let { return BatchSetup.Ok(it) }
        val totalInputs = op.inputs.groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.sum() }
        val outputTotal = op.outputs.first().second
        val outputPerBatch = apiMatch.api.outputs
            .firstOrNull { it.first == outputItemId }?.second?.toLong()?.coerceAtLeast(1L)
            ?: 1L
        for ((id, amount) in totalInputs) {
            if (cpu.getBufferCount(id) < amount) {
                return BatchSetup.Insufficient(
                    "Process input missing: $id x$amount (buffer has ${cpu.getBufferCount(id)})"
                )
            }
        }
        val batches = (outputTotal + outputPerBatch - 1) / outputPerBatch
        val progress = if (apiMatch.api.serial) {
            // Serial: one handler invocation per batch. Each invocation receives the
            // api's per-slot inputs exactly as declared (preserves order + duplicates).
            val perBatchInputs = apiMatch.api.inputs.map { (id, count) -> id to count.toLong() }
            BatchProgress(batches, 0L, perBatchInputs, outputItemId, outputPerBatch)
        } else {
            // Parallel: one handler invocation handles the whole demand. Scale each
            // slot's declared count by the number of batches needed to satisfy the
            // total output, preserving slot order + duplicates.
            val perBatchInputs = apiMatch.api.inputs.map { (id, count) -> id to (count.toLong() * batches) }
            BatchProgress(
                totalBatches = 1L,
                batchesDone = 0L,
                perBatchInputs = perBatchInputs,
                outputItemId = outputItemId,
                outputPerBatch = outputTotal,
            )
        }
        processBatchProgress[op.id] = progress
        return BatchSetup.Ok(progress)
    }

    private fun executeProcess(
        op: Operation.Process,
        lvl: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot
    ): CraftScheduler.OpResult {
        // If a handler invocation is currently active (awaiting its pending pulls), just poll.
        val existing = processState[op.id]
        if (existing != null) {
            if (existing.pending.isComplete) {
                val ok = existing.pending.success
                processState.remove(op.id)
                if (!ok) {
                    processRetries.remove(op.id)
                    processBatchProgress.remove(op.id)
                    return CraftScheduler.OpResult.Failed("Processing handler failed: ${op.processingApiName}")
                }
                // One batch done. Advance progress, if more batches remain, start the next one.
                val progress = processBatchProgress[op.id]
                if (progress != null) {
                    progress.batchesDone++
                    if (progress.batchesDone >= progress.totalBatches) {
                        processRetries.remove(op.id)
                        processBatchProgress.remove(op.id)
                        return CraftScheduler.OpResult.Completed
                    }
                }
                // Fall through to start the next batch invocation below.
            } else {
                return CraftScheduler.OpResult.InProgress
            }
        }

        val outputItemId = op.outputs.firstOrNull()?.first
            ?: return CraftScheduler.OpResult.Failed("Process op has no outputs declared")

        // RESUME PATH, checked FIRST and independently of handler resolution. On world
        // reload the terminal script may not have auto-started yet, so the handler isn't
        // registered. Resume doesn't need it: items are physically in machines, we just
        // poll the persisted target coords on the global ResumeScheduler (always ticking).
        cpu.opResumeInfo[op.id]?.let { return startResumePoll(op, it, lvl, outputItemId) }

        // FRESH PATH, needs the handler resolved.
        val apiMatch = snapshot.findProcessingApi(outputItemId)
            ?: return CraftScheduler.OpResult.Failed("No processing API for $outputItemId")
        val searchPositions = apiMatch.apiStorage.remoteTerminalPositions ?: snapshot.terminalPositions
        val handlerEngine = PlatformServices.modState
            .findProcessingEngine(lvl, searchPositions, apiMatch.api.name, apiMatch.apiStorage.remoteDimension) as? ScriptEngine
        val handler = handlerEngine?.processingHandlers?.get(apiMatch.api.name)
        if (handlerEngine == null || handler == null) {
            // No Lua handler. Before backing off, see if a Processing Handler
            // BLOCK is bound to this api on the recipe's home network. For a
            // local recipe that's the cpu's network; for a recipe pulled in
            // through a Receiver Antenna the handler is registered against the
            // PROVIDER network and lives in the provider's dimension. Mirrors
            // the Lua handler resolution above (which already passes
            // remoteDimension to findProcessingEngine).
            val handlerNetworkId = apiMatch.apiStorage.remoteNetworkId ?: snapshot.networkId
            val handlerLevel = apiMatch.apiStorage.remoteDimension
                ?.let { lvl.server.getLevel(it) } ?: lvl
            val blockHandlerPos = BlockHandlerRegistry.find(handlerNetworkId, apiMatch.api.name)
            val handlerBE = blockHandlerPos?.let {
                handlerLevel.getBlockEntity(it) as? damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
            }
            if (handlerBE != null && handlerBE.processingApiName == apiMatch.api.name) {
                // [executeBlockHandler] still receives the cpu's level - it
                // gets handed straight to [ProcessingJob] which uses it for
                // cpu-side network re-discovery on stale-job recovery. The
                // handler's own dimension is recovered inside
                // [BlockProcessingHandler.invoke] via [handlerBE.level].
                return executeBlockHandler(op, lvl, apiMatch, handlerBE, outputItemId)
            }
            return scheduleHandlerWaitRetry(op, "no handler for '${apiMatch.api.name}'")
        }
        val scheduler = handlerEngine.scheduler

        // FRESH INVOCATION PATH
        val progress = when (val setup = resolveBatchProgress(op, apiMatch, outputItemId)) {
            is BatchSetup.Ok -> setup.progress
            is BatchSetup.Insufficient -> return CraftScheduler.OpResult.Failed(setup.message)
        }

        if (apiMatch.api.serial && apiMatch.api.name in CraftingHelper.activeSerialJobsView) {
            return CraftScheduler.OpResult.InProgress
        }

        val pending = CraftingHelper.PendingHandlerJob()
        if (apiMatch.api.serial) {
            CraftingHelper.addActiveSerialJob(apiMatch.api.name)
            pending.onCompleteCallback = { CraftingHelper.removeActiveSerialJob(apiMatch.api.name) }
        }

        // For parallel APIs, override the job's expected output count to the full total so
        // `job:pull` waits for the whole batch rather than a single per-API unit.
        val bulkOutputOverride = if (!apiMatch.api.serial) {
            listOf(progress.outputItemId to progress.outputPerBatch.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } else null
        val job = ProcessingJob(apiMatch.api, cpu, lvl, scheduler, pending, bulkOutputOverride, op.id)
        val jobTable = job.toLuaTable()

        // Build the handler's single `items: InputItems` argument, a Lua table keyed by
        // per-slot parameter name (camelCase with numeric suffix on duplicates). Each
        // value is a full ItemsHandle bound to a per-slot BufferSource so per-slot
        // atomic extraction works correctly when the same item appears in multiple slots.
        //
        // perBatchInputs is authoritative for slot ORDER (row-major) and COUNT per slot.
        // apiMatch.api.inputs provides the same ordering, we use ProcessingSet's shared
        // naming helper to keep the autocomplete editor's view in perfect sync with what
        // the runtime actually binds.
        val paramNames = damien.nodeworks.card.ProcessingSet.buildHandlerParamNames(apiMatch.api.inputs)
        if (paramNames.size != progress.perBatchInputs.size) {
            return CraftScheduler.OpResult.Failed(
                "Processing handler arg mismatch: ${paramNames.size} param names vs " +
                "${progress.perBatchInputs.size} input slots"
            )
        }
        val itemsTable = LuaTable()
        for ((idx, slotData) in progress.perBatchInputs.withIndex()) {
            val (itemId, batchCount) = slotData
            val id = Identifier.tryParse(itemId)
                ?: return CraftScheduler.OpResult.Failed("Bad input item id: $itemId")
            val item = BuiltInRegistries.ITEM.getValue(id)
                ?: return CraftScheduler.OpResult.Failed("Unknown input item: $itemId")
            itemsTable.set(
                paramNames[idx],
                ItemsHandle.toLuaTable(
                    ItemsHandle(
                        itemId = itemId,
                        itemName = ItemStack(item).hoverName.string,
                        count = batchCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        maxStackSize = item.getDefaultMaxStackSize(),
                        hasData = false,
                        filter = itemId,
                        sourceStorage = { null },
                        level = lvl,
                        bufferSource = BufferSource(cpu, itemId, batchCount)
                    )
                )
            )
        }
        val luaArgs: List<LuaValue> = listOf(jobTable, itemsTable)

        cpu.setCrafting(true, outputItemId.substringAfter(':').replace('_', ' '))

        val state = ProcessState(
            pending = pending,
            processingJob = job,
            handler = handler,
            luaArgs = luaArgs,
            scheduler = scheduler
        )
        // Aggregate per-slot inputs by item id for the conservation check. With the new
        // per-slot format, duplicate entries like [cu@1, au@1, cu@1] collapse to
        // {cu: 2, au: 1}, the buffer-delta check only cares about totals per item.
        val perBatchInputMap = progress.perBatchInputs
            .groupBy { it.first }
            .mapValues { (_, pairs) -> pairs.sumOf { it.second } }
        return invokeHandlerAndAnalyze(op, state, perBatchInputMap, apiMatch.api.name, apiMatch.api.outputs)
    }

    /**
     * Invoke the handler once and inspect the side effects.
     *
     * The core safety guarantee: after this returns, the CPU buffer either
     *   (a) has the declared inputs fully consumed, or
     *   (b) has exactly as many of each input as it started with (handler made zero moves).
     * The partial case is a contract violation, we fail the op loudly and the buffer flushes
     * back to storage on onPlanFailed.
     *
     * For case (b), we cancel any `job:pull` calls the handler registered (they're now
     * watching for items that will never arrive) and re-run the op later via readyAt backoff.
     */
    private fun invokeHandlerAndAnalyze(
        op: Operation.Process,
        state: ProcessState,
        declaredInputs: Map<String, Long>,
        apiName: String,
        apiOutputs: List<Pair<String, Int>>,
    ): CraftScheduler.OpResult {
        val pre = declaredInputs.mapValues { (id, _) -> cpu.getBufferCount(id) }

        state.processingJob.resetInvocationPulls()
        val handler = state.handler
            ?: return CraftScheduler.OpResult.Failed("invokeHandlerAndAnalyze called with null handler")
        val result = try {
            when (state.luaArgs.size) {
                1 -> handler.call(state.luaArgs[0])
                2 -> handler.call(state.luaArgs[0], state.luaArgs[1])
                3 -> handler.call(state.luaArgs[0], state.luaArgs[1], state.luaArgs[2])
                else -> handler.invoke(LuaValue.varargsOf(state.luaArgs.toTypedArray())).arg1()
            }
        } catch (e: org.luaj.vm2.LuaError) {
            logger.warn("Processing handler error for '{}': {}", apiName, e.message)
            cancelInvocationPulls(state)
            val cleanMsg = damien.nodeworks.script.LuaExecGate.stripLuaTraceback(e.message) ?: "lua error"
            return CraftScheduler.OpResult.Failed("Handler ${formatHandlerLabel(apiName, apiOutputs)} threw: $cleanMsg")
        }

        // Explicit false return → immediate retry (handler opted into deferral)
        if (!result.isnil() && result.isboolean() && !result.toboolean()) {
            cancelInvocationPulls(state)
            return scheduleRetry(op, state, "handler returned false")
        }

        // Conservation check, diff buffer vs pre-invocation
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
            // Partial consumption, contract violation. Fail loudly, onPlanFailed flushes.
            cancelInvocationPulls(state)
            return CraftScheduler.OpResult.Failed(
                "Handler '$apiName' partially consumed input '$stranded', " +
                "all inputs must move together (use :hasSpaceFor or restructure the handler)."
            )
        }

        if (!allConsumed) {
            // Nothing moved. Unwind pulls and retry later.
            cancelInvocationPulls(state)
            return scheduleRetry(op, state, "destination unavailable")
        }

        // All inputs consumed. Handler must have registered at least one pull, otherwise
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

    /**
     * Block-handler dispatch path. Mirrors the Lua-handler shape (same
     * BatchProgress, serial gate, ProcessState bookkeeping, retry semantics)
     * but routes inputs through the Processing Handler block's per-channel
     * configuration instead of calling a Lua function.
     *
     * Lua wins on collision: this function is only reached when
     * [PlatformServices.modState.findProcessingEngine] resolves nothing for
     * the api, OR the resolved engine doesn't have it in its handler map.
     */
    private fun executeBlockHandler(
        op: Operation.Process,
        lvl: ServerLevel,
        apiMatch: damien.nodeworks.network.ProcessingApiMatch,
        handlerBE: damien.nodeworks.block.entity.ProcessingHandlerBlockEntity,
        outputItemId: String,
    ): CraftScheduler.OpResult {
        val progress = when (val setup = resolveBatchProgress(op, apiMatch, outputItemId)) {
            is BatchSetup.Ok -> setup.progress
            is BatchSetup.Insufficient -> return CraftScheduler.OpResult.Failed(setup.message)
        }

        // Serial gate is global across handler types - a serial recipe can
        // only run one invocation at a time regardless of whether the handler
        // is Lua or block.
        if (apiMatch.api.serial && apiMatch.api.name in CraftingHelper.activeSerialJobsView) {
            return CraftScheduler.OpResult.InProgress
        }

        val bulkOutputOverride = if (!apiMatch.api.serial) {
            listOf(progress.outputItemId to progress.outputPerBatch.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } else null

        cpu.setCrafting(true, outputItemId.substringAfter(':').replace('_', ' '))

        val invokeResult = BlockProcessingHandler.invoke(
            cpu = cpu,
            level = lvl,
            api = apiMatch.api,
            handlerBE = handlerBE,
            perBatchInputs = progress.perBatchInputs,
            bulkOutputOverride = bulkOutputOverride,
            opId = op.id,
        )
        return when (invokeResult) {
            is BlockProcessingHandler.InvokeResult.InProgress -> {
                val state = ProcessState(
                    pending = invokeResult.pending,
                    processingJob = invokeResult.job,
                    handler = null,           // null marks this as the block path
                    luaArgs = emptyList(),
                    scheduler = damien.nodeworks.script.ResumeScheduler.scheduler,
                )
                processState[op.id] = state
                CraftScheduler.OpResult.InProgress
            }
            is BlockProcessingHandler.InvokeResult.CompletedSync -> {
                if (invokeResult.pending.success) {
                    // One batch done. Match the Lua path's batch advancement.
                    progress.batchesDone++
                    if (progress.batchesDone >= progress.totalBatches) {
                        processRetries.remove(op.id)
                        processBatchProgress.remove(op.id)
                        CraftScheduler.OpResult.Completed
                    } else {
                        CraftScheduler.OpResult.InProgress
                    }
                } else {
                    CraftScheduler.OpResult.Failed("Block handler '${apiMatch.api.name}' completed sync with failure")
                }
            }
            is BlockProcessingHandler.InvokeResult.Retry -> {
                // Mirrors the Lua "destination unavailable" retry path. The
                // block path guarantees no buffer mutation on routing failure
                // (its atomic route + rollback) so no ProcessState exists to
                // clear - pass null.
                scheduleRetry(op, null, invokeResult.reason)
            }
            is BlockProcessingHandler.InvokeResult.Failed -> {
                processRetries.remove(op.id)
                processBatchProgress.remove(op.id)
                CraftScheduler.OpResult.Failed("Block handler '${apiMatch.api.name}' failed: ${invokeResult.reason}")
            }
        }
    }


    /**
     * Resume an in-flight Process op after world reload. The handler's items survived the
     * reload as physical machine contents (e.g. raw iron in a furnace), so we don't re-invoke
     * the handler, we just restart polling against the persisted target coordinates and let
     * the existing extract logic drain remaining outputs into the buffer.
     *
     * Polls run on the global [damien.nodeworks.script.ResumeScheduler.scheduler], independent
     * of any terminal/script engine, the terminal's auto-run can lag CPU ticking on world load.
     */
    private fun startResumePoll(
        op: Operation.Process,
        info: damien.nodeworks.block.entity.CraftingCoreBlockEntity.OpResumeInfo,
        lvl: ServerLevel,
        outputItemId: String
    ): CraftScheduler.OpResult {
        // BatchProgress as a single-batch, no-input bulk op (items already in machine).
        processBatchProgress.getOrPut(op.id) {
            val outputTotal = info.outputs.firstOrNull { it.first == outputItemId }?.second
                ?: op.outputs.first().second
            BatchProgress(
                totalBatches = 1L,
                batchesDone = 0L,
                perBatchInputs = emptyList(),
                outputItemId = outputItemId,
                outputPerBatch = outputTotal
            )
        }
        val scheduler = damien.nodeworks.script.ResumeScheduler.scheduler
        val pending = CraftingHelper.PendingHandlerJob()
        val syntheticApi = damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo(
            name = info.processingApiName,
            inputs = emptyList(),
            outputs = info.outputs.map {
                it.first to it.second.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            },
            timeout = 6000,
            serial = false
        )
        val resumeJob = ProcessingJob(syntheticApi, cpu, lvl, scheduler, pending, null, op.id)
        val getters = info.pullTargets.map { (pos, face) ->
            damien.nodeworks.script.CardHandle.StorageGetter {
                PlatformServices.storage.getItemStorage(lvl, pos, face)
            }
        }
        cpu.setCrafting(true, outputItemId.substringAfter(':').replace('_', ' '))
        resumeJob.startPoll(getters)

        // If startPoll completed synchronously (items already waiting in the machine when
        // we ticked), don't bother stashing state, collapse straight to Completed/Failed.
        if (pending.isComplete) {
            return if (pending.success) {
                processBatchProgress[op.id]?.let { it.batchesDone = it.totalBatches }
                CraftScheduler.OpResult.Completed
            } else {
                CraftScheduler.OpResult.Failed("Resume polling failed: ${info.processingApiName}")
            }
        }
        processState[op.id] = ProcessState(
            pending = pending,
            processingJob = resumeJob,
            handler = null,
            luaArgs = emptyList(),
            scheduler = scheduler
        )
        return CraftScheduler.OpResult.InProgress
    }

    private fun cancelInvocationPulls(state: ProcessState) {
        for (pull in state.processingJob.invocationPulls) {
            state.scheduler.removePendingJob(pull)
        }
        state.processingJob.invocationPulls.clear()
    }

    /** Back off and retry when neither a Lua nor a block handler is yet
     *  registered for the recipe, typical on world load before terminal
     *  auto-run finishes (Lua) or before a Handler block's chunk loads (block).
     *  Shares [processRetries] with the destination-full retry path so a
     *  truly-absent handler fails after the configured cap. */
    private fun scheduleHandlerWaitRetry(op: Operation.Process, reason: String): CraftScheduler.OpResult {
        val count = (processRetries[op.id] ?: 0) + 1
        processRetries[op.id] = count
        val cap = maxHandlerRetries()
        if (count > cap) {
            processRetries.remove(op.id)
            processBatchProgress.remove(op.id)
            return CraftScheduler.OpResult.Failed(
                "Handler '${op.processingApiName}' never appeared after $cap retries ($reason). " +
                "Bind a Processing Handler block to this recipe, or run a terminal script that registers it."
            )
        }
        val now = (cpu.level as? ServerLevel)?.gameTime ?: 0L
        op.readyAt = now + RETRY_BACKOFF_TICKS
        return CraftScheduler.OpResult.InProgress
    }

    /** Schedule a backoff for an op that was dispatched but couldn't make
     *  forward progress (Lua handler returned false, inputs didn't move,
     *  block-handler couldn't route through the micro-network). [state] is
     *  non-null for the Lua path (where a [ProcessState] was registered and
     *  needs clearing) and null for the block path (which fails before
     *  registering state). */
    private fun scheduleRetry(
        op: Operation.Process,
        state: ProcessState?,
        reason: String,
    ): CraftScheduler.OpResult {
        val count = (processRetries[op.id] ?: 0) + 1
        processRetries[op.id] = count
        val cap = maxHandlerRetries()
        if (count > cap) {
            processRetries.remove(op.id)
            processBatchProgress.remove(op.id)
            return CraftScheduler.OpResult.Failed(
                "Processing handler '${op.processingApiName}' couldn't progress for " +
                "$cap retries ($reason). Destinations may be permanently unavailable."
            )
        }
        val now = (cpu.level as? ServerLevel)?.gameTime ?: 0L
        op.readyAt = now + RETRY_BACKOFF_TICKS
        // Drop the registered state so the next dispatch re-invokes the
        // handler fresh instead of polling a never-completing pending.
        if (state != null) processState.remove(op.id)
        // Per-op resume info is only meaningful when items actually reached
        // the machine; on a failed-move retry we're starting fresh.
        cpu.clearOpResume(op.id)
        // Keep the GUI in "Crafting" through the backoff window so the status
        // line doesn't flicker to Idle between retries.
        val itemLabel = op.outputs.firstOrNull()?.first
            ?.substringAfter(':')?.replace('_', ' ') ?: cpu.currentCraftItem
        cpu.setCrafting(true, itemLabel)
        return CraftScheduler.OpResult.InProgress
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onPlanCompleted(plan: CraftPlan) {
        // Defensive: should be empty by now (each op cleans on completion), but a stuck
        // entry would leak across plans since op IDs reset to 0 each plan.
        val planOpIds = plan.ops.map { it.id }.toSet()
        processState.keys.retainAll { it !in planOpIds }
        processBatchProgress.keys.retainAll { it !in planOpIds }
        processRetries.keys.retainAll { it !in planOpIds }

        // Invalidate any still-registered resume polls on the global ResumeScheduler so
        // they stop trying to pour items into this (now-idle) CPU's buffer. Their next
        // tryExtract will see stale jobGeneration and route pulled items to network storage.
        cpu.invalidateInFlightPolls()
        // For omit-deliver plans (network:craft), the produced items are SUPPOSED to
        // still be in the buffer at this point so the script's `:connect(fn)` callback
        // can claim them via a buffer-backed handle. Skipping the flush here is what
        // makes that contract work, the completion listener (run below) takes
        // ownership of the buffer instead.
        if (!plan.omitDeliver) {
            flushBufferToStorage()
        }
        // A successful craft clears any lingering failure message from a previous failed run.
        if (cpu.lastFailureReason.isNotEmpty()) cpu.lastFailureReason = ""
        // omit-deliver: don't clear craft state yet, the runtime's `dropRemainingBuffer`
        // does it after the user's callback runs. Clearing here would also wipe
        // `originalCraftId` and `craftTreeSnapshot` which the script-side completion
        // path doesn't currently rely on, but keeping them consistent across the two
        // exit paths means future code can treat "in-buffer items" as "craft still alive".
        if (!plan.omitDeliver) {
            cpu.clearAllCraftState()
            cpu.setCrafting(false)
        }
        completionListeners.remove(plan)?.invoke(true)
    }

    override fun onPlanFailed(plan: CraftPlan, reason: String) {
        logger.warn("CPU at {}: plan for {} failed: {}", cpu.blockPos, plan.rootItemId, reason)
        val planOpIds = plan.ops.map { it.id }.toSet()
        processState.keys.retainAll { it !in planOpIds }
        processBatchProgress.keys.retainAll { it !in planOpIds }
        processRetries.keys.retainAll { it !in planOpIds }

        // Same rationale as onPlanCompleted, kill any still-active resume polls so they
        // don't keep dumping items into an idle CPU's buffer.
        cpu.invalidateInFlightPolls()
        flushBufferToStorage()
        // Store the reason on the CPU BE so the GUI can surface it. Cancellation by the
        // player isn't really a failure to debug, skip those.
        if (!reason.startsWith("Cancelled")) {
            val itemName = plan.rootItemId.substringAfter(':').replace('_', ' ')
            cpu.lastFailureReason = "$itemName × ${plan.rootCount}: $reason"
        }
        // Chat-notify the submitter if known and still online.
        val lvl = cpu.level
        val submitter = plan.submitterUuid
        if (!reason.startsWith("Cancelled") && submitter != null && lvl is ServerLevel) {
            val player = lvl.server.playerList.getPlayer(submitter)
            val networkLabel = formatNetworkLabel(lvl)
            player?.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                    "[Crafting CPU $networkLabel] ${plan.rootItemId} × ${plan.rootCount} failed: $reason"
                ).withStyle(net.minecraft.ChatFormatting.RED)
            )
        }
        cpu.clearAllCraftState()
        cpu.setCrafting(false)
        completionListeners.remove(plan)?.invoke(false)
    }

    /** Pick a short, player-facing identifier for a processing handler. Prefers
     *  the output items (what the player thinks of the handler as producing) over
     *  the api's internal `name` field, which is usually the card alias and means
     *  nothing in chat. Single-output handlers read as `iron_ingot × 4`, multi-
     *  output as `iron_ingot × 4 + xp_bottle × 1`. Falls back to the api name
     *  when outputs are empty (shouldn't happen in practice but keeps the
     *  formatter total). */
    private fun formatHandlerLabel(apiName: String, apiOutputs: List<Pair<String, Int>>): String {
        if (apiOutputs.isEmpty()) return "'$apiName'"
        val parts = apiOutputs.joinToString(" + ") { (id, count) ->
            "${id.substringAfter(':').replace('_', ' ')} × $count"
        }
        return "'$parts'"
    }

    /** Identify the network this CPU belongs to for chat-message context. Uses
     *  the controller's player-set [damien.nodeworks.block.entity.NetworkControllerBlockEntity.networkName]
     *  when non-empty, otherwise the controller's `(x y z)` so the player knows
     *  which CPU pinged them when several are online. Falls back to the CPU's
     *  own pos when the controller can't be reached (orphaned subnet / mid-load).
     *
     *  Resolves the controller through the snapshot rather than the client-side
     *  `NodeConnectionRenderer.findController` so it stays callable from
     *  [onPlanFailed] on dedicated servers without dragging in client classes. */
    private fun formatNetworkLabel(lvl: ServerLevel): String {
        val snapshot = snapshotForTick(lvl)
        val controllerPos = snapshot.controller?.pos
        if (controllerPos != null) {
            val controller = lvl.getBlockEntity(controllerPos)
                as? damien.nodeworks.block.entity.NetworkControllerBlockEntity
            if (controller != null) {
                val name = controller.networkName
                if (name.isNotEmpty()) return "\"$name\""
                return "@${controllerPos.x} ${controllerPos.y} ${controllerPos.z}"
            }
        }
        val p = cpu.blockPos
        return "cpu @${p.x} ${p.y} ${p.z}"
    }

    private fun flushBufferToStorage() {
        val lvl = cpu.level as? ServerLevel ?: return
        val snap = snapshotForTick(lvl)
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
        val id = Identifier.tryParse(itemId) ?: return
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return
        var remaining = count
        while (remaining > 0L) {
            val batch = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
            val stack = ItemStack(item, batch)
            val inserted = NetworkStorageHelper.insertItemStack(lvl, snapshot, stack, null)
            if (inserted == 0) {
                // Drop as item entity, better than deleting
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
        /** Phase 2 placeholder throttle, produces op cost 0, so ops chain within a single
         *  tick and existing craft timing is preserved. Phase 4 replaces this with a
         *  computation from heat/cooling/substrate state. */
        /** Fallback throttle when the CPU isn't formed (no buffer). High enough that
         *  [CpuRules.opCost] rounds to 0, crafts chain instantly through the scheduler. */
        const val DEFAULT_THROTTLE: Float = 10.0f
    }
}
