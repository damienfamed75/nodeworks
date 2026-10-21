package damien.nodeworks.script

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/** Shortest a machine must be quiet before a job short on a guaranteed output borrows a
 *  bonus copy back from storage, see [ProcessingJob.borrowShortfall]. The wait follows the
 *  machine's own pace and has no ceiling, a slow machine is never judged early. */
private const val MIN_QUIET_TICKS = 20L

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

    private val declaredOutputs: List<Pair<RecipeIngredient, Long>> =
        overrideOutputs ?: api.outputs.map { it to it.count.toLong() }

    private fun stateOf(ingr: RecipeIngredient, ct: Long) = OutputState(
        itemId = ingr.itemId,
        componentsHash = damien.nodeworks.script.BufferKey.componentsHash(ingr.stack),
        template = ingr.stack.copyWithCount(1),
        amount = ct,
    )

    /** One state per item on one side, two slots of the same item fold into one so
     *  neither is left waiting for what the other collected. */
    private fun merged(optional: Boolean): MutableList<OutputState> {
        val out = LinkedHashMap<damien.nodeworks.script.BufferKey.Key, OutputState>()
        for ((ingr, ct) in declaredOutputs) {
            if (ingr.optional != optional) continue
            val prev = out[ingr.bufferKey()]
            if (prev == null) out[ingr.bufferKey()] = stateOf(ingr, ct) else prev.amount += ct
        }
        return out.values.toMutableList()
    }

    /** Guaranteed outputs still to collect. The job completes when this is empty. */
    private val remaining: MutableList<OutputState> = merged(optional = false)

    /** Outputs the machine only sometimes makes. Taken when present, up to the declared
     *  count, never waited for, and sent to network storage rather than the CPU buffer so
     *  the craft is never held up by luck. */
    private val optional: MutableList<OutputState> = merged(optional = true)

    /** One item the job collects, with its guaranteed and optional sides together. A bonus
     *  copy of the main output (an 80% chance of a second dust) shares the guaranteed one's
     *  group, and the two are told apart per poll: the machine makes an operation's outputs
     *  in one tick, so a poll finding more than the guaranteed count per operation found
     *  the bonus too. [perOpRequired] and [perOpOptional] are the per-operation counts. */
    private class Group(
        val required: OutputState?,
        val optional: OutputState?,
        val perOpRequired: Long,
        val perOpOptional: Long,
    ) {
        /** Bonus copies of the guaranteed item already sent to storage. */
        var optionalSent: Long = 0L
    }

    private val groups: List<Group> = run {
        val keys = LinkedHashSet<damien.nodeworks.script.BufferKey.Key>()
        for ((ingr, _) in declaredOutputs) keys += ingr.bufferKey()
        keys.map { key ->
            val match: (OutputState) -> Boolean = { it.itemId == key.itemId && it.componentsHash == key.componentsHash }
            val perOp: (Boolean) -> Long = { opt ->
                declaredOutputs.filter { it.first.optional == opt && it.first.bufferKey() == key }.sumOf { it.first.count.toLong() }
            }
            Group(remaining.firstOrNull(match), optional.firstOrNull(match), perOp(false), perOp(true))
        }
    }
    private val startGeneration = cpu.jobGeneration

    /** Last tick anything was collected, guaranteed or optional. Starts at the job's
     *  start, so the wait for the first output counts as the first gap. */
    private var lastProgressTick: Long = scheduler.currentTick

    /** Longest gap seen between this machine's outputs, the start to the first output
     *  included since that is about one operation. Twice this is how long it must be
     *  quiet before the job treats it as finished, whatever its speed. */
    private var maxGapTicks: Long = 0L

    /** Last tick a borrow-back was attempted, so a failed one isn't retried every tick. */
    private var lastBorrowTick: Long = Long.MIN_VALUE

    private val quietTicks: Long get() = maxOf(MIN_QUIET_TICKS, 2L * maxGapTicks)

    private fun noteProgress() {
        val now = scheduler.currentTick
        maxGapTicks = maxOf(maxGapTicks, now - lastProgressTick)
        lastProgressTick = now
    }

    /** When the pending poll times out, so an optional wait never runs into it. */
    private var deadlineTick: Long = Long.MAX_VALUE

    /** Pulls registered during the most recent handler invocation. The CPU reads this
     *  to cancel them if the handler failed to move its inputs (see
     *  [damien.nodeworks.script.cpu.CpuOpExecutor.executeProcess] retry path). */
    val invocationPulls: MutableList<SchedulerImpl.PendingJob> = mutableListOf()

    /** Every poll this job registered, invocation or resume, so a hold can move their
     *  deadlines along with [deadlineTick]. */
    private val pulls: MutableList<SchedulerImpl.PendingJob> = mutableListOf()

    /** Called by the CPU at the start of each handler invocation so the pull list
     *  only reflects *this* invocation's registrations (not previous attempts). */
    fun resetInvocationPulls() { invocationPulls.clear() }

    fun toLuaTable(): LuaTable {
        val table = LuaTable()

        // What the set is, so one handler for many sets can tell them apart.
        table.set("name", api.name)
        val inputsTable = LuaTable()
        for ((i, ingr) in api.inputs.withIndex()) {
            val entry = LuaTable()
            entry.set("id", ingr.itemId)
            entry.set("count", ingr.count)
            ingr.tag?.let { entry.set("tag", it) }
            inputsTable.set(i + 1, entry)
        }
        table.set("inputs", inputsTable)

        val outputsTable = LuaTable()
        for ((i, ingr) in api.outputs.withIndex()) {
            val entry = LuaTable()
            entry.set("id", ingr.itemId)
            entry.set("count", ingr.count)
            entry.set("optional", LuaValue.valueOf(ingr.optional))
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
                    api.outputs.filter { !it.optional }.map { it.stack.copyWithCount(1) to it.count.toLong() },
                    pullTargets,
                )
                if (opId >= 0) {
                    cpu.setOpResume(opId, resumeInfo(pullTargets))
                }

                // Register for polling each tick
                val timeoutTicks = if (api.timeout > 0) api.timeout.toLong() else 6000L
                deadlineTick = scheduler.currentTick + timeoutTicks
                val pending = SchedulerImpl.PendingJob(
                    pollFn = { tryExtract(getters) },
                    timeoutAt = scheduler.currentTick + timeoutTicks,
                    onComplete = { success -> pendingResult.complete(success) },
                    label = "pull:${api.name}"
                )
                scheduler.addPendingJob(pending)
                invocationPulls.add(pending)
                pulls.add(pending)

                return LuaValue.NIL
            }
        })

        return table
    }

    private val isStale: Boolean get() = cpu.jobGeneration != startGeneration

    /** A CPU that is not formed, its controller pulled or part of its structure gone,
     *  has no buffer to take outputs into, and pulling them would void them. Everything
     *  stays in the machine and the clocks stand still, so the job carries on unchanged
     *  once the CPU is back. True while holding. */
    private fun holdWhileUnformed(): Boolean {
        if (cpu.isRemoved) {
            // The CPU is broken or unloaded. Its poll must not keep pulling outputs into a
            // buffer nobody will read, so every pull expires next tick and anything it
            // reserved is let go. A reload resumes from the op state saved with the CPU.
            val now = scheduler.currentTick
            deadlineTick = now
            for (pull in pulls) pull.timeoutAt = now
            return true
        }
        if (cpu.isFormed) return false
        if (deadlineTick != Long.MAX_VALUE) deadlineTick++
        for (pull in pulls) if (pull.timeoutAt != Long.MAX_VALUE) pull.timeoutAt++
        lastProgressTick++
        lastBorrowTick++
        return true
    }

    /** Keeps [n] of [stack] with the craft. A buffer that is full takes it anyway and
     *  the Core tells the player, since the only alternatives are pushing it back into a
     *  machine or losing it, and neither happens. */
    private fun holdInBuffer(stack: net.minecraft.world.item.ItemStack, n: Long, networkFull: Boolean) {
        if (n <= 0L) return
        if (!cpu.addToBuffer(stack, n)) cpu.addToBufferForced(stack, n, networkFull)
    }

    /**
     * Incremental: each poll grabs whatever's currently available for each declared output
     * and decrements the remaining count. Only returns true (job complete) once every
     * guaranteed count has been fully collected. For a 4-ingot smelt on a furnace that emits
     * one ingot every few seconds, this means we pull each ingot as it arrives instead of
     * waiting for all 4 to pile up and blocking subsequent smelts. Optional outputs found
     * along the way go to network storage and never hold the job up.
     */
    private fun tryExtract(getters: List<CardHandle.StorageGetter>): Boolean {
        if (holdWhileUnformed()) return false
        val stale = isStale
        var snapshot: damien.nodeworks.network.NetworkSnapshot? =
            if (stale) damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, cpu.blockPos) else null
        fun network(): damien.nodeworks.network.NetworkSnapshot =
            snapshot ?: damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, cpu.blockPos).also { snapshot = it }

        for (group in groups) {
            val required = group.required?.takeIf { it.amount > 0L }
            val optional = if (stale) null else group.optional?.takeIf { it.amount > 0L }
            if (required == null && optional == null) continue
            val reqLeft = required?.amount ?: 0L
            val optLeft = optional?.amount ?: 0L
            val state = required ?: optional!!
            // Variant-aware filter: pull only stacks whose itemId AND
            // componentsHash match the declared output. Without this a
            // poison potion sitting in the output card would satisfy a
            // strength-potion output because both share `minecraft:potion`.
            val stackFilter: (net.minecraft.world.item.ItemStack) -> Boolean = { stack ->
                val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                sid == state.itemId &&
                    damien.nodeworks.script.BufferKey.componentsHash(stack) == state.componentsHash
            }
            var want = reqLeft + optLeft
            val found = ArrayList<Pair<net.minecraft.world.item.ItemStack, damien.nodeworks.platform.ItemStorageHandle>>()
            for (getter in getters) {
                if (want <= 0L) break
                val storage = getter.getStorage() ?: continue
                val extracted = try {
                    PlatformServices.storage.extractStacksByPredicate(storage, stackFilter, want)
                } catch (_: Exception) { emptyList() }
                for (stack in extracted) {
                    if (stack.isEmpty) continue
                    want -= stack.count.toLong()
                    found += stack to storage
                }
            }
            val k = found.sumOf { it.first.count.toLong() }
            if (k <= 0L) continue
            noteProgress()

            // Split this poll's haul. Both sides present: the machine finished
            // ceil(k / perOp) operations this tick, each of which made its guaranteed
            // count, so that many are guaranteed and the rest are bonuses.
            val toRequired = when {
                required == null -> 0L
                optional == null -> minOf(k, reqLeft)
                else -> {
                    val perOp = group.perOpRequired + group.perOpOptional
                    val ops = (k + perOp - 1) / perOp
                    minOf(k, ops * group.perOpRequired, reqLeft)
                }
            }
            val toOptional = minOf(k - toRequired, optLeft)
            // Anything past both counts stays with the craft, the buffer flush returns it.
            var keep = k - toOptional
            for ((stack, source) in found) {
                val amount = stack.count.toLong()
                val kept = minOf(amount, keep)
                keep -= kept
                if (kept > 0L) {
                    val part = stack.copyWithCount(kept.toInt())
                    if (stale) {
                        // Stale-recovery path: the CPU's job context outlived the
                        // network it was on, so we route the recovered stack back
                        // into the live network as-is (components preserved).
                        NetworkStorageHelper.insertItemStack(level, network(), part, null)
                    } else {
                        holdInBuffer(part, kept, networkFull = false)
                    }
                }
                if (amount > kept) routeOptional(stack.copyWithCount((amount - kept).toInt()), network(), source)
            }
            required?.let { it.amount = reqLeft - toRequired }
            optional?.let { it.amount = optLeft - toOptional }
            if (required != null && optional != null) group.optionalSent += toOptional
        }
        remaining.removeAll { it.amount <= 0L }

        if (remaining.isEmpty() || (!stale && borrowShortfall(::network))) {
            cpu.completePendingOp()
            cpu.clearOpResume(opId)
            return true
        }
        // Update per-op resume info so a save mid-stream resumes with the right remaining count.
        if (opId >= 0 && cpu.opResumeInfo[opId] != null) {
            val existing = cpu.opResumeInfo[opId]!!
            cpu.setOpResume(opId, resumeInfo(existing.pullTargets))
        }
        return false
    }

    /** What a reload needs to carry this job on: every output still outstanding, with its
     *  per-operation count so a bonus copy of the main output is still told apart. */
    private fun resumeInfo(
        pullTargets: List<Pair<net.minecraft.core.BlockPos, net.minecraft.core.Direction>>,
    ): CraftingCoreBlockEntity.OpResumeInfo {
        fun perOp(state: OutputState): Int {
            val group = groups.firstOrNull { it.required === state || it.optional === state } ?: return 1
            val n = if (group.required === state) group.perOpRequired else group.perOpOptional
            return n.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
        return CraftingCoreBlockEntity.OpResumeInfo(
            processingApiName = api.name,
            outputs = remaining.map { it.template.copyWithCount(1) to it.amount },
            pullTargets = pullTargets,
            outputsPerOp = remaining.map { perOp(it) },
            optionals = optional.filter { it.amount > 0L }.map {
                CraftingCoreBlockEntity.ResumeOutput(it.template.copyWithCount(1), it.amount, perOp(it))
            },
        )
    }

    /** Two operations finishing in one tick with no bonus look like one with a bonus, so a
     *  guaranteed copy can be filed as optional and sent to storage. If that is the only
     *  thing holding the job up and the machine has gone quiet, take the shortfall back from
     *  storage into the buffer and finish. True when the job may complete. */
    private fun borrowShortfall(network: () -> damien.nodeworks.network.NetworkSnapshot): Boolean {
        val short = groups.filter { (it.required?.amount ?: 0L) > 0L }
        if (short.isEmpty()) return true
        if (short.any { it.optionalSent < it.required!!.amount }) return false
        val now = scheduler.currentTick
        val quiet = now - lastProgressTick >= quietTicks
        if (!quiet && now + 1 < deadlineTick) return false
        // Borrowing walks every storage card, so a failed attempt waits another quiet period.
        if (now - lastBorrowTick < quietTicks) return false
        lastBorrowTick = now
        for (group in short) {
            val req = group.required!!
            val stacks = try {
                NetworkStorageHelper.extractVariantAcrossNetwork(level, network(), req.itemId, req.template.componentsPatch, req.amount)
            } catch (_: Exception) { emptyList() }
            for (stack in stacks) {
                if (stack.isEmpty) continue
                val n = stack.count.toLong()
                holdInBuffer(stack, n, networkFull = false)
                req.amount -= n
                group.optionalSent -= n
            }
        }
        remaining.removeAll { it.amount <= 0L }
        return remaining.isEmpty()
    }

    /** Send an optional output to network storage. What storage won't take stays with
     *  the craft in the buffer, over capacity if it must, until the player frees space.
     *  Never back into the machine, never dropped. */
    private fun routeOptional(
        stack: net.minecraft.world.item.ItemStack,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        @Suppress("UNUSED_PARAMETER") source: damien.nodeworks.platform.ItemStorageHandle,
    ) {
        val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, null)
        val left = stack.count - inserted
        if (left <= 0) return
        holdInBuffer(stack.copyWithCount(left), left.toLong(), networkFull = true)
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
                api.outputs.filter { !it.optional }.map { it.stack.copyWithCount(1) to it.count.toLong() },
                pullTargets,
            )
            if (opId >= 0) {
                cpu.setOpResume(opId, resumeInfo(pullTargets))
            }
        }
        val timeoutTicks = if (api.timeout > 0) api.timeout.toLong() else 6000L
        deadlineTick = scheduler.currentTick + timeoutTicks
        val pending = SchedulerImpl.PendingJob(
            pollFn = { tryExtract(getters) },
            timeoutAt = scheduler.currentTick + timeoutTicks,
            onComplete = { success -> pendingResult.complete(success) },
            label = "resume:${api.name}"
        )
        scheduler.addPendingJob(pending)
        pulls.add(pending)
    }
}
