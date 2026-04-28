package damien.nodeworks.script

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.script.cpu.CpuFeasibility
import damien.nodeworks.script.cpu.CraftPlan
import damien.nodeworks.script.cpu.CraftPlanner
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.luaj.vm2.LuaFunction
import org.slf4j.LoggerFactory

/**
 * Entry point for `network:craft()` and the Inventory Terminal's craft button.
 *
 * Post-Phase-2: this class now PLANS a craft and submits it to a CPU's [CraftScheduler].
 * The scheduler drives the whole craft (pulling, processing, executing, delivering) via
 * [damien.nodeworks.script.cpu.CpuOpExecutor]. There is no recursion, [CraftPlanner]
 * walks the [CraftTreeBuilder] result iteratively.
 *
 * Callers still use the `CraftResult + currentPendingJob` protocol:
 *   - Returns a [CraftResult] with a [PendingHandlerJob] on successful submission.
 *     The caller registers `pending.onCompleteCallback` to react to completion.
 *   - Returns null with [lastFailReason] set on failure (no feasible CPU, missing
 *     ingredients, etc.). Callers check `result == null && currentPendingJob == null`.
 */
object CraftingHelper {

    private val logger = LoggerFactory.getLogger("nodeworks-crafting")

    /** Serial-handler coordination: some processing APIs forbid parallel invocations. */
    private val activeSerialJobs = mutableSetOf<String>()
    val activeSerialJobsView: Set<String> get() = activeSerialJobs
    fun addActiveSerialJob(name: String) { activeSerialJobs.add(name) }
    fun removeActiveSerialJob(name: String) { activeSerialJobs.remove(name) }

    data class CraftResult(
        val outputItemId: String,
        val outputName: String,
        val count: Int,
        val cpu: CraftingCoreBlockEntity? = null,
        val level: ServerLevel? = null,
        val snapshot: NetworkSnapshot? = null,
        val cache: NetworkInventoryCache? = null,
        val pendingJob: PendingHandlerJob? = null
    )

    /**
     * Flush whatever's in the CPU buffer to network storage, dropping any overflow
     * in-world at the CPU's position so items aren't silently destroyed when storage
     * is full. Used both as the auto-store path for `network:craft` (with the new
     * [craft]-with-`omitDeliver` flow) and as a defensive cleanup for legacy callers
     * whose plans already ran a Deliver op (those find an empty buffer and skip).
     *
     * Drops surface through the CPU's own [CraftingCoreBlockEntity.lastFailureReason]
     * so the CPU GUI shows the red error banner, the same channel `Deliver` op
     * failures already use. The submitter (if known) gets a chat notification too,
     * matching the standard plan-failure flow.
     */
    fun releaseCraftResult(result: CraftResult, submitterUuid: java.util.UUID? = null) {
        val cpu = result.cpu ?: return
        val level = result.level ?: return
        val snapshot = result.snapshot ?: return
        val droppedTotals = mutableMapOf<String, Long>()
        if (cpu.bufferUsed > 0L) {
            val leftovers = cpu.clearBuffer()
            for ((itemId, count) in leftovers) {
                val id = Identifier.tryParse(itemId) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(id) ?: continue
                var remaining = count
                while (remaining > 0L) {
                    val batchSize = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
                    val stack = ItemStack(item, batchSize)
                    val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, result.cache)
                    if (inserted < batchSize) {
                        // Storage refused (full or partially full), drop the shortfall
                        // in-world at the CPU so items survive instead of being lost.
                        val dropCount = batchSize - inserted
                        val dropStack = ItemStack(item, dropCount)
                        net.minecraft.world.Containers.dropItemStack(
                            level,
                            cpu.blockPos.x + 0.5,
                            cpu.blockPos.y + 1.0,
                            cpu.blockPos.z + 0.5,
                            dropStack
                        )
                        droppedTotals.merge(itemId, dropCount.toLong(), Long::plus)
                        logger.warn(
                            "releaseCraftResult: storage refused {} x{}, dropped at CPU {}",
                            itemId, dropCount, cpu.blockPos
                        )
                    }
                    remaining -= batchSize.toLong()
                }
            }
        }
        if (droppedTotals.isNotEmpty()) {
            val summary = droppedTotals.entries.joinToString(", ") { (id, count) ->
                "$count × ${id.substringAfter(':').replace('_', ' ')}"
            }
            cpu.lastFailureReason = "Network storage full, dropped $summary at the CPU"
            // Mirror the chat-notify path used by Deliver-op failures (see
            // [damien.nodeworks.script.cpu.CpuOpExecutor.onPlanFailed]) so the player
            // who submitted the craft sees the error even if their CPU GUI is closed.
            if (submitterUuid != null) {
                val player = level.server.playerList.getPlayer(submitterUuid)
                player?.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "[Crafting CPU] ${cpu.lastFailureReason}"
                    ).withStyle(net.minecraft.ChatFormatting.RED)
                )
            }
        }
        if (cpu.isCrafting) {
            cpu.clearAllCraftState()
            cpu.setCrafting(false)
        }
    }

    /** Last-craft error reason, player-facing. */
    var lastFailReason: String? = null
        private set

    /** Set when [craft] submits an async craft to the scheduler. Callers await its completion. */
    var currentPendingJob: PendingHandlerJob? = null

    /** Source description for the last resolved processing handler. Retained for trace-log compat. */
    var lastHandlerSource: String? = null

    /** Bridges between the scheduler's completion listener and the caller's async-wait protocol. */
    class PendingHandlerJob {
        @Volatile var isComplete = false
            private set
        @Volatile var success = false
            private set
        var onCompleteCallback: ((Boolean) -> Unit)? = null

        fun complete(succeeded: Boolean) {
            success = succeeded
            isComplete = true
            onCompleteCallback?.invoke(succeeded)
        }
    }

    // =====================================================================
    // Craft entry point, plan + submit to a CPU's scheduler
    // =====================================================================

    /**
     * Plan a craft and submit it to a feasible CPU's scheduler.
     *
     * Most parameters are retained for API compatibility but are no longer used by the
     * scheduler-based path (handlers come from the network scan, no recursion means no
     * depth tracking needed).
     *
     * Returns a [CraftResult] carrying a [PendingHandlerJob] on success. The caller
     * should register `pending.onCompleteCallback` to react when the plan finishes.
     * Returns null with [lastFailReason] set on failure.
     */
    fun craft(
        identifier: String,
        count: Int,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int = 0,
        cache: NetworkInventoryCache? = null,
        cpuPos: BlockPos? = null,
        processingHandlers: Map<String, LuaFunction>? = null,
        callerScheduler: SchedulerImpl? = null,
        traceLog: ((String) -> Unit)? = null,
        submitterUuid: java.util.UUID? = null,
        omitDeliver: Boolean = false
    ): CraftResult? {
        lastFailReason = null
        currentPendingJob = null

        if (count <= 0) {
            lastFailReason = "Craft count must be positive"
            return null
        }

        // 1. Build the craft tree (iterative via CraftTreeBuilder, not recursive here).
        val tree = CraftTreeBuilder.buildCraftTree(identifier, count, level, snapshot)

        // 2. Select a CPU, explicit [cpuPos] for legacy resume paths, otherwise find
        //    any CPU on the network that can fit the craft (feasibility-aware).
        val cpu: CraftingCoreBlockEntity = if (cpuPos != null) {
            val entity = level.getBlockEntity(cpuPos) as? CraftingCoreBlockEntity
            if (entity == null) {
                lastFailReason = "No Crafting CPU at $cpuPos"
                return null
            }
            if (!entity.isFormed) {
                lastFailReason = "Crafting CPU at $cpuPos is not formed"
                return null
            }
            // Feasibility still enforced on the explicit CPU
            val feasibility = CpuFeasibility.check(tree, entity)
            if (!feasibility.ok) {
                lastFailReason = feasibility.reason
                return null
            }
            entity
        } else {
            selectFeasibleCpu(level, snapshot, tree) ?: return null
        }

        // 3. Plan the craft against the selected CPU.
        val planResult = CraftPlanner.plan(tree, snapshot, omitDeliver = omitDeliver)
        val plan = planResult.plan ?: run {
            lastFailReason = planResult.message ?: "Could not plan craft"
            return null
        }

        traceLog?.invoke(
            "[craft] Planned '$identifier' × $count: ${plan.ops.size} operations on CPU at ${cpu.blockPos}"
        )

        // 4. Save the tree snapshot for the diagnostic GUI, then submit to the scheduler.
        cpu.craftTreeSnapshot = tree
        val pending = PendingHandlerJob()
        val planWithSubmitter = if (submitterUuid != null) plan.copy(submitterUuid = submitterUuid) else plan
        cpu.submitCraft(planWithSubmitter, level.gameTime) { success ->
            pending.complete(success)
        }
        currentPendingJob = pending

        val outputName = resolveItemName(identifier)
        return CraftResult(
            outputItemId = identifier,
            outputName = outputName,
            count = tree.count,
            cpu = cpu,
            level = level,
            snapshot = snapshot,
            cache = cache,
            pendingJob = pending
        )
    }

    // =====================================================================
    // CPU selection, feasibility-aware across the entire network
    // =====================================================================

    /**
     * Iterate every CPU on the network, return the highest-capacity CPU that is:
     *   - formed (has at least one Buffer block)
     *   - has at least one idle scheduler thread (can accept a new craft *right now*,
     *     checked against live state, not the snapshot's stale `isBusy` flag)
     *   - feasible for the given craft tree (passes [CpuFeasibility.check])
     *
     * If no CPU satisfies all three, sets [lastFailReason] with the best available
     * diagnostic and returns null.
     */
    private fun selectFeasibleCpu(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        tree: CraftTreeBuilder.CraftTreeNode
    ): CraftingCoreBlockEntity? {
        val cpuSnaps = snapshot.cpus
        if (cpuSnaps.isEmpty()) {
            lastFailReason = "No Crafting CPU on the network."
            return null
        }

        // Highest-capacity first so the first rejection we record is the most informative.
        val ordered = cpuSnaps.sortedByDescending { it.bufferCapacity }

        var bestInfeasibleReason: String? = null
        var feasibleButBusy = false

        for (cpuSnap in ordered) {
            val cpu = level.getBlockEntity(cpuSnap.pos) as? CraftingCoreBlockEntity ?: continue
            if (!cpu.isFormed) continue

            val feasibility = CpuFeasibility.check(tree, cpu)
            if (!feasibility.ok) {
                if (bestInfeasibleReason == null) bestInfeasibleReason = feasibility.reason
                continue
            }

            // Live check against the scheduler, snapshot's isBusy may be stale for
            // crafts submitted between snapshot build and this check.
            if (cpu.scheduler.isIdle) return cpu
            feasibleButBusy = true
        }

        lastFailReason = when {
            feasibleButBusy -> "All Crafting CPUs that can fit this craft are busy. Wait for one to finish or add more CPUs."
            bestInfeasibleReason != null -> bestInfeasibleReason
            else -> "No Crafting CPU on the network has enough buffer capacity."
        }
        return null
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun resolveItemName(itemId: String): String {
        val id = Identifier.tryParse(itemId) ?: return itemId
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return itemId
        return ItemStack(item).hoverName.string
    }
}
