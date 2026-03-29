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

    data class CraftResult(
        val outputItemId: String,
        val outputName: String,
        val count: Int
    )

    /** Reason why crafting failed — returned instead of null for diagnostics. */
    var lastFailReason: String? = null
        private set

    /** Set when craftViaProcessing starts an async handler. The caller should wait on this. */
    var currentPendingJob: PendingHandlerJob? = null

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
        processingHandlers: Map<String, LuaFunction>? = null
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
            cpu.setCrafting(true)
        }

        // Try Instruction Set (3x3 crafting) first
        val match = snapshot.findInstructionSet(identifier)
        if (match != null) {
            val recipe = match.instructionSet.recipe

            var totalCrafted = 0
            for (i in 0 until count) {
                if (!craftOnce(recipe, match, level, snapshot, depth, cache, cpu, processingHandlers)) break
                totalCrafted++
            }

            if (cpuPos == null) finishCrafting(cpu, level, snapshot, cache)
            if (totalCrafted == 0) return null

            val outputId = match.instructionSet.outputItemId
            val outputIdentifier = ResourceLocation.tryParse(outputId) ?: return null
            val outputItem = BuiltInRegistries.ITEM.get(outputIdentifier) ?: return null
            val outputName = ItemStack(outputItem).hoverName.string
            val outputPerCraft = getRecipeOutputCount(recipe, level)

            return CraftResult(outputId, outputName, totalCrafted * outputPerCraft)
        }

        // Try Processing API Card + handler
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
                var totalCrafted = 0
                for (i in 0 until count) {
                    if (!craftViaProcessing(apiMatch, handler, handlerEngine, level, snapshot, depth, cache, cpu, processingHandlers)) break
                    totalCrafted++
                }

                val pending = currentPendingJob
                if (pending != null && !pending.isComplete && cpuPos == null) {
                    // Defer finishCrafting until the handler coroutine completes
                    pending.onCompleteCallback = {
                        finishCrafting(cpu, level, snapshot, cache)
                    }
                } else if (cpuPos == null) {
                    finishCrafting(cpu, level, snapshot, cache)
                }
                if (totalCrafted == 0) return null

                // Return result for the matched output (the one the caller asked for)
                val matchedOutput = apiMatch.api.outputs.firstOrNull { it.first == identifier }
                    ?: apiMatch.api.outputs.first()
                val outputId = matchedOutput.first
                val outputIdentifier = ResourceLocation.tryParse(outputId) ?: return null
                val outputItem = BuiltInRegistries.ITEM.get(outputIdentifier) ?: return null
                val outputName = ItemStack(outputItem).hoverName.string

                return CraftResult(outputId, outputName, totalCrafted * matchedOutput.second)
            }
        }

        // No recipe found — build diagnostic
        if (cpuPos == null) finishCrafting(cpu, level, snapshot, cache)
        val apiFound = apiMatch != null
        lastFailReason = when {
            !apiFound && snapshot.findInstructionSet(identifier) == null ->
                "No recipe found for '$identifier' (no Instruction Set or Processing API Card)"
            apiFound -> "Processing API Card '${apiMatch!!.api.name}' found for '$identifier' but no handler registered (need network:handle(\"${apiMatch.api.name}\", ...) on a running terminal)"
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
     */
    private fun craftOnce(
        recipe: List<String>,
        match: InstructionSetMatch,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        depth: Int,
        cache: NetworkInventoryCache?,
        cpu: CraftingCoreBlockEntity,
        processingHandlers: Map<String, LuaFunction>? = null
    ): Boolean {
        // Count ingredients needed
        val ingredientCounts = mutableMapOf<String, Int>()
        for (itemId in recipe) {
            if (itemId.isEmpty()) continue
            ingredientCounts[itemId] = (ingredientCounts[itemId] ?: 0) + 1
        }

        // For each ingredient, check buffer + storage. Recursively craft prerequisites if needed.
        for ((itemId, needed) in ingredientCounts) {
            val inBuffer = cpu.getBufferCount(itemId)
            val inStorage = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            var have = inBuffer + inStorage

            while (have < needed) {
                // Try to recursively craft the missing ingredient (reuse same CPU)
                val subResult = craft(itemId, 1, level, snapshot, depth + 1, cache, cpu.blockPos, processingHandlers)
                if (subResult == null || subResult.count == 0) {
                    logger.debug("Cannot craft '{}': unable to craft prerequisite '{}'",
                        match.instructionSet.outputItemId, itemId)
                    return false
                }
                // Re-check: sub-craft results go into storage, so check both buffer + storage
                have = cpu.getBufferCount(itemId) + NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            }
        }

        // Extract ingredients from storage into CPU buffer
        for ((itemId, needed) in ingredientCounts) {
            // First use what's already in the buffer
            val inBuffer = cpu.getBufferCount(itemId)
            val fromStorage = maxOf(0, needed - inBuffer)

            if (fromStorage > 0) {
                // Extract from network storage into buffer
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
                    logger.warn("Failed to extract all '{}' for crafting", itemId)
                    return false
                }
            }
        }

        // Consume ingredients from buffer
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

        // Insert crafted result into network storage
        val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, result, cache)
        if (inserted == 0) {
            logger.debug("Network storage full, cannot insert crafted item")
            return false
        }

        return true
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
        processingHandlers: Map<String, LuaFunction>?
    ): Boolean {
        val api = apiMatch.api

        // Check/craft prerequisites for each input
        for ((itemId, needed) in api.inputs) {
            val inBuffer = cpu.getBufferCount(itemId)
            val inStorage = NetworkStorageHelper.countItems(level, snapshot, itemId).toInt()
            var have = inBuffer + inStorage

            while (have < needed) {
                val subResult = craft(itemId, 1, level, snapshot, depth + 1, cache, cpu.blockPos, processingHandlers)
                if (subResult == null || subResult.count == 0) {
                    logger.debug("Cannot process '{}': unable to obtain prerequisite '{}'", api.name, itemId)
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

        // Run the handler as a coroutine on the handler engine's scheduler.
        // The handler calls job:pull() which yields if outputs aren't ready yet.
        // The scheduler resumes the coroutine each tick until job:pull() succeeds.
        try {
            val engine = handlerEngine ?: return false
            val scheduler = engine.scheduler

            val pending = PendingHandlerJob()
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

            // job:pull() registered a pending poll — store for the caller to wait on
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
