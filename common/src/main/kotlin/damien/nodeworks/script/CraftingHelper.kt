package damien.nodeworks.script

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.network.InstructionSetMatch
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.network.ProcessingApiMatch
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.slf4j.LoggerFactory

/**
 * Handles crafting execution for `network:craft()`.
 * Uses a Crafting CPU's internal buffer to hold items during crafting,
 * preventing race conditions between concurrent craft operations.
 */
object CraftingHelper {

    private val logger = LoggerFactory.getLogger("nodeworks-crafting")

    /** Tracks active serial handler jobs by card name. If a card is serial, only one job at a time. */
    private val activeSerialJobs = mutableSetOf<String>()

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

    /** Release a craft result: move items from CPU buffer to storage and mark CPU idle. */
    fun releaseCraftResult(result: CraftResult) {
        val cpu = result.cpu ?: return
        val level = result.level ?: return
        val snapshot = result.snapshot ?: return
        finishCrafting(cpu, level, snapshot, result.cache)
    }

    /** Reason why crafting failed — returned instead of null for diagnostics. */
    var lastFailReason: String? = null
        private set

    /** Set when craftViaProcessing starts an async handler. The caller should wait on this. */
    var currentPendingJob: PendingHandlerJob? = null

    /** Source description for the last resolved processing handler. */
    var lastHandlerSource: String? = null

    /**
     * Represents a pending async handler invocation.
     * CraftingHelper starts the coroutine, and the caller polls [isComplete].
     */
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

    /**
     * Craft items matching the given identifier.
     * Finds an available Crafting CPU, extracts ingredients into its buffer,
     * crafts, and inserts results into network storage.
     *
     * @param identifier The Instruction Set alias or output item ID
     * @param count Number of crafting operations to perform
     * @param level The server level
     * @param snapshot Network snapshot
     * @param depth Recursion depth guard
     * @param cache Optional inventory cache for UI updates
     * @param cpuPos Optional: force a specific CPU (for recursive calls that reuse the same CPU)
     * @param processingHandlers Optional: Lua handlers for processing recipes (keyed by output item ID)
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
        traceLog: ((String) -> Unit)? = null
    ): CraftResult? {
        if (depth == 0) lastFailReason = null

        if (depth > 20) {
            lastFailReason = "Crafting recursion depth exceeded for '$identifier'"
            return null
        }

        // Find a CPU — reuse the one passed in (recursive call) or find a new one
        val cpu: CraftingCoreBlockEntity
        if (cpuPos != null) {
            cpu = level.getBlockEntity(cpuPos) as? CraftingCoreBlockEntity ?: return null
        } else {
            val cpuSnapshot = snapshot.findAvailableCpu() ?: run {
                lastFailReason = "No available Crafting CPU on network"
                return null
            }
            cpu = level.getBlockEntity(cpuSnapshot.pos) as? CraftingCoreBlockEntity ?: run {
                lastFailReason = "Crafting CPU block entity missing"
                return null
            }
            if (!cpu.isFormed) {
                lastFailReason = "Crafting CPU is not formed (needs adjacent Crafting Storage)"
                return null
            }
            cpu.setCrafting(true, identifier.substringAfter(':').replace('_', ' '))
        }

        // Try Instruction Set (3x3 crafting) first
        val match = snapshot.findInstructionSet(identifier)
        if (match != null) {
            if (depth == 0) {
                val alias = match.instructionSet.alias ?: match.instructionSet.outputItemId
                traceLog?.invoke("[craft] Resolved '$identifier' via Craft Template: $alias")
            }
            val recipe = match.instructionSet.recipe

            var totalCrafted = 0
            for (i in 0 until count) {
                if (!craftOnce(recipe, match, level, snapshot, depth, cache, cpu, processingHandlers, callerScheduler, traceLog)) break
                totalCrafted++
            }

            if (totalCrafted == 0) {
                if (cpuPos == null) finishCrafting(cpu, level, snapshot, cache)
                return null
            }

            val outputId = match.instructionSet.outputItemId
            val outputIdentifier = ResourceLocation.tryParse(outputId) ?: return null
            val outputItem = BuiltInRegistries.ITEM.get(outputIdentifier) ?: return null
            val outputName = ItemStack(outputItem).hoverName.string
            val outputPerCraft = getRecipeOutputCount(recipe, level)

            // Items stay in CPU buffer — caller releases via connect/store
            return CraftResult(outputId, outputName, totalCrafted * outputPerCraft,
                cpu = if (cpuPos == null) cpu else null, level = level, snapshot = snapshot, cache = cache)
        }

        // Try Processing Set + handler
        val apiMatch = snapshot.findProcessingApi(identifier)
        if (apiMatch != null) {
            val cardName = apiMatch.api.name
            // Look for handler by card name: first in local engine, then across network terminals
            var handler = processingHandlers?.get(cardName)
            var handlerEngine: ScriptEngine? = null
            if (handler == null) {
                val searchPositions = apiMatch.apiStorage.remoteTerminalPositions ?: snapshot.terminalPositions
                handlerEngine = PlatformServices.modState.findProcessingEngine(
                    level, searchPositions, cardName
                ) as? ScriptEngine
                if (handlerEngine != null) {
                    handler = handlerEngine.processingHandlers[cardName]
                }
            }

            if (handler != null) {
                val isSubnet = apiMatch.apiStorage.remoteTerminalPositions != null
                val source = if (isSubnet) "subnet" else "local"
                lastHandlerSource = "$cardName ($source)"
                if (depth == 0) {
                    traceLog?.invoke("[craft] Resolved '$identifier' via Process Template: ${cardName} ($source)")
                }
                var totalCrafted = 0
                for (i in 0 until count) {
                    if (!craftViaProcessing(apiMatch, handler, handlerEngine, level, snapshot, depth, cache, cpu, processingHandlers, isRecursive = cpuPos != null, callerScheduler = callerScheduler, traceLog = traceLog)) break
                    totalCrafted++
                }

                if (totalCrafted == 0) {
                    if (cpuPos == null) finishCrafting(cpu, level, snapshot, cache)
                    return null
                }

                val pending = currentPendingJob?.takeIf { !it.isComplete }

                // If async (pending job not done), return null — caller checks currentPendingJob
                if (pending != null) {
                    return null
                }

                val matchedOutput = apiMatch.api.outputs.firstOrNull { it.first == identifier }
                    ?: apiMatch.api.outputs.first()
                val outputId = matchedOutput.first
                val outputIdentifier = ResourceLocation.tryParse(outputId) ?: return null
                val outputItem = BuiltInRegistries.ITEM.get(outputIdentifier) ?: return null
                val outputName = ItemStack(outputItem).hoverName.string

                return CraftResult(outputId, outputName, totalCrafted * matchedOutput.second,
                    cpu = if (cpuPos == null) cpu else null, level = level, snapshot = snapshot, cache = cache)
            }
        }

        // No recipe found — build diagnostic
        if (cpuPos == null) finishCrafting(cpu, level, snapshot, cache)
        val apiFound = apiMatch != null
        lastFailReason = when {
            !apiFound && snapshot.findInstructionSet(identifier) == null ->
                "No recipe found for '$identifier' (no Instruction Set or Processing Set)"
            apiFound -> "Processing Set '${apiMatch!!.api.name}' found for '$identifier' but no handler registered (need network:handle(\"${apiMatch.api.name}\", ...) on a running terminal)"
            else -> "Craft failed for '$identifier'"
        }
        return null
    }

    /**
     * Finish crafting: return any leftover buffer items to network storage and mark CPU idle.
     */
    private fun finishCrafting(
        cpu: CraftingCoreBlockEntity,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache?
    ) {
        // Return leftover buffer contents to network storage
        val leftovers = cpu.clearBuffer()
        for ((itemId, count) in leftovers) {
            val id = ResourceLocation.tryParse(itemId) ?: continue
            val item = BuiltInRegistries.ITEM.get(id) ?: continue
            var remaining = count
            while (remaining > 0) {
                val batchSize = minOf(remaining, item.getDefaultMaxStackSize())
                val stack = ItemStack(item, batchSize)
                val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, cache)
                remaining -= inserted
                if (inserted == 0) {
                    logger.warn("Could not return {} x{} from CPU buffer to storage", itemId, remaining)
                    break
                }
            }
        }
        cpu.setCrafting(false)
    }

    /**
     * Execute one crafting operation using the CPU buffer.
     * If prerequisites need async processing, starts them all and registers a
     * pending job that assembles the recipe when all prerequisites arrive.
     */
    private fun craftOnce(
        recipe: List<String>,
        match: InstructionSetMatch,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        cache: NetworkInventoryCache?,
        cpu: CraftingCoreBlockEntity,
        processingHandlers: Map<String, LuaFunction>? = null,
        callerScheduler: SchedulerImpl? = null,
        traceLog: ((String) -> Unit)? = null
    ): Boolean {
        val ingredientCounts = mutableMapOf<String, Int>()
        for (itemId in recipe) {
            if (itemId.isEmpty()) continue
            ingredientCounts[itemId] = (ingredientCounts[itemId] ?: 0) + 1
        }

        // Phase 1: Extract what's already available, then craft what's missing
        val pendingPrereqs = mutableListOf<PendingHandlerJob>()

        for ((itemId, needed) in ingredientCounts) {
            // First, extract what's already in storage into the buffer
            val inBuffer = cpu.getBufferCount(itemId)
            val inStorage = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            val canExtract = minOf(needed - inBuffer, inStorage).toLong()
            val shortId = itemId.substringAfter(':')
            if (canExtract > 0) {
                var remaining = canExtract
                for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
                    if (remaining <= 0) break
                    val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                    val extracted = PlatformServices.storage.extractItems(
                        storage, { CardHandle.matchesFilter(it, itemId) }, remaining
                    )
                    if (extracted > 0) {
                        cpu.addToBuffer(itemId, extracted.toInt())
                        cache?.onExtracted(itemId, false, extracted)
                        remaining -= extracted
                    }
                }
            }

            // Now craft what's still missing
            var have = cpu.getBufferCount(itemId)
            var toCraft = needed - have
            var asyncCount = 0

            while (toCraft > 0) {
                currentPendingJob = null
                val subResult = craft(itemId, 1, level, snapshot, depth + 1, cache, cpu.blockPos, processingHandlers, callerScheduler, traceLog)
                if (subResult == null || subResult.count == 0) {
                    val pending = currentPendingJob
                    if (pending != null) {
                        pendingPrereqs.add(pending)
                        asyncCount++
                        toCraft -= 1
                    } else {
                        traceLog?.invoke("[craft] Missing ingredient '$shortId' for '${match.instructionSet.outputItemId.substringAfter(':')}' — no recipe found")
                        return false
                    }
                } else {
                    // Sync craft succeeded — re-check buffer
                    have = cpu.getBufferCount(itemId)
                    toCraft = needed - have
                }
            }
            if (asyncCount > 0) {
                val src = lastHandlerSource ?: "handler"
                traceLog?.invoke("[craft] Waiting on ${asyncCount}x $shortId from $src")
            }
        }

        // If there are pending async prerequisites, register a job that assembles when all complete
        if (pendingPrereqs.isNotEmpty()) {
            val assemblyJob = PendingHandlerJob()
            val pollFn: () -> Boolean = {
                if (pendingPrereqs.all { it.isComplete }) {
                    val success = assembleRecipe(recipe, ingredientCounts, level, snapshot, cache, cpu)
                    if (!success) traceLog?.invoke("[craft] Assembly failed")
                    assemblyJob.complete(success)
                    true
                } else false
            }

            // We need to find a scheduler to register on. Store the poll job reference
            // so the caller's scheduler can pick it up.
            currentPendingJob = assemblyJob
            val assemblyScheduler = callerScheduler ?: findAnyScheduler(level, snapshot)
            if (assemblyScheduler == null) {
                logger.warn("No scheduler found to register assembly job for '{}'", match.instructionSet.outputItemId)
                return false
            }
            assemblyScheduler.addPendingJob(SchedulerImpl.PendingJob(
                pollFn = pollFn,
                timeoutAt = Long.MAX_VALUE,
                label = "assembly:${match.instructionSet.outputItemId}"
            ))

            return true // signals "started, pending"
        }

        // Phase 2: All prerequisites available — extract and assemble immediately
        return assembleRecipe(recipe, ingredientCounts, level, snapshot, cache, cpu)
    }

    /** Consume ingredients from buffer and assemble the 3x3 recipe. */
    private fun assembleRecipe(
        recipe: List<String>,
        ingredientCounts: Map<String, Int>,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache?,
        cpu: CraftingCoreBlockEntity
    ): Boolean {
        // All ingredients should already be in the buffer
        // (extracted from storage + crafted by prerequisites)
        for ((itemId, needed) in ingredientCounts) {
            val removed = cpu.removeFromBuffer(itemId, needed)
            if (removed < needed) {
                logger.warn("Buffer underflow: needed {} of '{}' but only had {}", needed, itemId, removed)
                return false
            }
        }

        // Build the crafting input and get the result
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = ResourceLocation.tryParse(itemId) ?: return false
                val item = BuiltInRegistries.ITEM.get(id) ?: return false
                ItemStack(item, 1)
            }
        }
        val craftingInput = CraftingInput.of(3, 3, items)

        val recipeManager = level.getRecipeManager() ?: return false
        val result = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, craftingInput, level)
            .map { it.value().assemble(craftingInput, level.registryAccess()) }
            .orElse(ItemStack.EMPTY)

        if (result.isEmpty) {
            logger.debug("No valid crafting recipe for instruction set")
            return false
        }

        // Insert crafted result into CPU buffer (not storage — caller releases via connect/store)
        val outputId = BuiltInRegistries.ITEM.getKey(result.item)?.toString() ?: return false
        cpu.addToBuffer(outputId, result.count)

        return true
    }

    private fun findAnyScheduler(level: ServerLevel, snapshot: NetworkSnapshot): SchedulerImpl? {
        val engine = PlatformServices.modState.findAnyEngine(level, snapshot.terminalPositions) as? ScriptEngine
        return engine?.scheduler
    }

    /**
     * Execute one processing operation via a Lua handler.
     * Extracts input items from storage into CPU buffer, creates ItemsHandles,
     * invokes the handler function, and inserts the result into network storage.
     */
    private fun craftViaProcessing(
        apiMatch: ProcessingApiMatch,
        handler: LuaFunction,
        handlerEngine: ScriptEngine?,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        cache: NetworkInventoryCache?,
        cpu: CraftingCoreBlockEntity,
        processingHandlers: Map<String, LuaFunction>?,
        isRecursive: Boolean = false,
        callerScheduler: SchedulerImpl? = null,
        traceLog: ((String) -> Unit)? = null
    ): Boolean {
        val api = apiMatch.api

        // Check/craft prerequisites for each input
        for ((itemId, needed) in api.inputs) {
            val inBuffer = cpu.getBufferCount(itemId)
            val inStorage = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            var have = inBuffer + inStorage

            while (have < needed) {
                val subResult = craft(itemId, 1, level, snapshot, depth + 1, cache, cpu.blockPos, processingHandlers, callerScheduler, traceLog)
                if (subResult == null || subResult.count == 0) {
                    traceLog?.invoke("[craft] Missing ingredient '${itemId.substringAfter(':')}' for '${api.name}' — no recipe found")
                    return false
                }
                have = cpu.getBufferCount(itemId) + NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            }
        }

        // Extract input items from storage into CPU buffer
        for ((itemId, needed) in api.inputs) {
            val inBuffer = cpu.getBufferCount(itemId)
            val fromStorage = maxOf(0, needed - inBuffer)

            if (fromStorage > 0) {
                var remaining = fromStorage.toLong()
                for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
                    if (remaining <= 0) break
                    val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                    val extracted = PlatformServices.storage.extractItems(
                        storage, { CardHandle.matchesFilter(it, itemId) }, remaining
                    )
                    if (extracted > 0) {
                        cpu.addToBuffer(itemId, extracted.toInt())
                        cache?.onExtracted(itemId, false, extracted)
                        remaining -= extracted
                    }
                }
                if (remaining > 0) {
                    logger.warn("Failed to extract all '{}' for processing", itemId)
                    return false
                }
            }
        }

        // Verify buffer has all required inputs
        for ((itemId, needed) in api.inputs) {
            if (cpu.getBufferCount(itemId) < needed) {
                logger.warn("Buffer underflow: needed {} of '{}' but only had {}", needed, itemId, cpu.getBufferCount(itemId))
                return false
            }
        }

        try {
            // Serial mode: only one handler invocation at a time per card
            if (api.serial && api.name in activeSerialJobs) {
                logger.debug("Serial handler '{}' is busy, skipping", api.name)
                return false
            }

            val scheduler = handlerEngine?.scheduler ?: callerScheduler ?: return false

            if (api.serial) activeSerialJobs.add(api.name)

            val pending = PendingHandlerJob()
            pending.onCompleteCallback = { if (api.serial) activeSerialJobs.remove(api.name) }
            val job = ProcessingJob(api, cpu, level, scheduler, pending)
            val jobTable = job.toLuaTable()

            val luaArgs = mutableListOf<LuaValue>(jobTable)
            for ((itemId, count) in api.inputs) {
                val identifier = ResourceLocation.tryParse(itemId) ?: return false
                val item = BuiltInRegistries.ITEM.get(identifier) ?: return false
                luaArgs.add(ItemsHandle.toLuaTable(ItemsHandle(
                    itemId = itemId,
                    itemName = ItemStack(item).hoverName.string,
                    count = count,
                    maxStackSize = item.getDefaultMaxStackSize(),
                    hasData = false,
                    filter = itemId,
                    sourceStorage = { null },
                    level = level,
                    bufferSource = BufferSource(cpu, itemId, count)
                )))
            }
            // Update CPU display to show the active sub-craft
            cpu.setCrafting(true, api.outputs.firstOrNull()?.first?.substringAfter(':')?.replace('_', ' ') ?: "")

            // Run handler synchronously on the server thread.
            val result = when (luaArgs.size) {
                1 -> handler.call(luaArgs[0])
                2 -> handler.call(luaArgs[0], luaArgs[1])
                3 -> handler.call(luaArgs[0], luaArgs[1], luaArgs[2])
                else -> handler.invoke(LuaValue.varargsOf(luaArgs.toTypedArray())).arg1()
            }

            // Handler returned false/nil = explicit failure
            if (!result.isnil() && result.isboolean() && !result.toboolean()) {
                logger.debug("Processing handler '{}' returned failure", api.name)
                return false
            }

            // Check if the pending job completed synchronously (job:pull found items immediately)
            if (pending.isComplete) {
                return pending.success
            }

            // job:pull() registered a pending poll (async — outputs not ready yet)
            // Store pending job — caller (craftOnce or network:craft) will wait on it
            currentPendingJob = pending
            return true
        } catch (e: org.luaj.vm2.LuaError) {
            logger.warn("Processing handler error for '{}': {}", api.name, e.message)
            return false
        }
    }

    private fun getRecipeOutputCount(recipe: List<String>, level: ServerLevel): Int {
        val items = recipe.map { itemId ->
            if (itemId.isEmpty()) ItemStack.EMPTY
            else {
                val id = ResourceLocation.tryParse(itemId) ?: return 1
                val item = BuiltInRegistries.ITEM.get(id) ?: return 1
                ItemStack(item, 1)
            }
        }
        val input = CraftingInput.of(3, 3, items)
        val recipeManager = level.getRecipeManager() ?: return 1
        return recipeManager
            .getRecipeFor(RecipeType.CRAFTING, input, level)
            .map { it.value().assemble(input, level.registryAccess()).count }
            .orElse(1)
    }
}
