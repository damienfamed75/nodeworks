package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import damien.nodeworks.platform.PlatformServices
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * Manages a sandboxed Lua VM for one terminal. Provides the Nodeworks API
 * (card, scheduler, print) and enforces an instruction budget per tick.
 */
class ScriptEngine(
    private val level: ServerLevel,
    private val networkEntryNode: BlockPos,
    private val logCallback: (String, Boolean) -> Unit // (message, isError)
) {
    private var globals: Globals? = null
    private var networkSnapshot: NetworkSnapshot? = null
    val scheduler = SchedulerImpl { errorMsg -> logCallback(errorMsg, true) }

    /** Precomputed route table set by network:route(). */
    var routeTable: RouteTable? = null
        private set

    /** Cached inventory index across all network storage. */
    var inventoryCache: NetworkInventoryCache? = null
        private set

    /** Processing handlers registered by network:handle(). Keyed by card name. */
    val processingHandlers = mutableMapOf<String, LuaFunction>()

    /** Redstone onChange callbacks. Keyed by card alias → (capability, lastStrength, callback). */
    private data class RedstoneCallback(
        val capability: damien.nodeworks.card.RedstoneSideCapability,
        var lastStrength: Int,
        val callback: LuaFunction
    )
    private val redstoneCallbacks = mutableMapOf<String, RedstoneCallback>()


    fun start(scripts: Map<String, String>): Boolean {
        stop()

        val mainScript = scripts["main"]
        if (mainScript.isNullOrBlank()) {
            logCallback("Error: no 'main' script found.", true)
            return false
        }

        // Discover network
        networkSnapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        inventoryCache = NetworkInventoryCache.getOrCreate(level, networkEntryNode)

        // Create sandboxed globals
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())

        // Install the Lua compiler
        LuaC.install(g)

        // Remove dangerous globals
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("os", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)

        // Custom require() that resolves modules from the scripts map
        val loaded = LuaTable()
        g.set("require", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val modName = arg.checkjstring()

                // Return cached module if already loaded
                val cached = loaded.get(modName)
                if (!cached.isnil()) return cached

                val source = scripts[modName]
                    ?: throw LuaError("module '$modName' not found")

                // Mark as loading (prevents circular require)
                loaded.set(modName, LuaValue.TRUE)

                val chunk = g.load(wrapForLoopIterators(stripTypeAnnotations(source)), modName)
                val result = chunk.call()

                // If the module returned a value, cache that; otherwise cache true
                val moduleValue = if (result.isnil()) LuaValue.TRUE else result
                loaded.set(modName, moduleValue)
                return moduleValue
            }
        })

        // Initialize scheduler with the current server tick
        scheduler.initialize(PlatformServices.modState.tickCount)

        // Inject Nodeworks API
        injectApi(g)

        globals = g

        // Compile and run the main script (top-level code: variable setup, scheduler registrations)
        return try {
            val chunk = g.load(wrapForLoopIterators(stripTypeAnnotations(mainScript)), "main")
            logCallback("Script started.", false)
            chunk.call()
            true
        } catch (e: LuaError) {
            // Script-level errors belong in the player-facing terminal log and the
            // Diagnostic Tool's error buffer — not the server console.
            logCallback("Error: ${e.message}", true)
            stop()
            false
        }
    }

    fun stop() {
        routeTable = null
        inventoryCache = null // clear local reference, cache lives in global registry
        processingHandlers.clear()
        redstoneCallbacks.clear()
        scheduler.clear()
        globals = null
        networkSnapshot = null
    }

    fun isRunning(): Boolean = globals != null

    /** Whether this engine should stay alive — has scheduler tasks, handlers, or routing. */
    fun hasWork(): Boolean = scheduler.hasActiveTasks()
        || processingHandlers.isNotEmpty()
        || redstoneCallbacks.isNotEmpty()
        || routeTable?.hasRoutes() == true


    /** Called each server tick. Runs scheduler callbacks within the instruction budget. */
    fun tick(tickCount: Long) {
        if (globals == null) return

        // The server keeps every Connectable's `networkId` current — when an LOS break or
        // removed node severs the path, `propagateNetworkId` clears it on the orphaned
        // side. If our entry node no longer claims a network the terminal is effectively
        // disconnected; running further would silently operate against a stale snapshot
        // so stop with a clear error and let auto-run restart us once reconnected.
        val entry = level.getBlockEntity(networkEntryNode) as? damien.nodeworks.network.Connectable
        if (entry?.networkId == null) {
            logCallback("Network disconnected — no controller reachable.", true)
            stop()
            return
        }

        try {
            scheduler.tick(tickCount)
            pollRedstoneCallbacks()
        } catch (e: LuaError) {
            logCallback("Runtime error: ${e.message}", true)
            stop()
        } catch (e: Exception) {
            logCallback("Runtime error: ${e.message}", true)
            stop()
        }
    }

    private fun pollRedstoneCallbacks() {
        if (redstoneCallbacks.isEmpty()) return
        for ((_, cb) in redstoneCallbacks) {
            val currentStrength = level.getSignal(cb.capability.adjacentPos, cb.capability.nodeSide)
            if (currentStrength != cb.lastStrength) {
                cb.lastStrength = currentStrength
                cb.callback.call(LuaValue.valueOf(currentStrength))
            }
        }
    }

    companion object {
        /**
         * Strips Luau-style type annotations from script text before Lua compilation.
         * Handles: function params `(x: Type)`, return types `): Type`, local vars `local x: Type =`
         */
        private val typePattern = """(?:[A-Z]\w*|string|number|boolean|any)\??"""

        fun stripTypeAnnotations(source: String): String {
            var result = source

            // Container return-type annotations come BEFORE the scalar strip so the
            // brace-delimited form `: { CardHandle }` / `: { [string]: V }` gets caught
            // as a whole rather than leaving an unmatched brace behind.
            result = result.replace(Regex("""\)\s*:\s*\{[^}]*}""")) { ")" }

            // Function parameter types: (param:Type) or (param: Type) or (param :Type)
            // Matches uppercase types (CardHandle, ItemsHandle) and builtin types (string, number, boolean, any)
            result = result.replace(Regex("""\b(\w+)\s*:\s*($typePattern)""")) { match ->
                match.groupValues[1]
            }

            // Return type annotations: ): TypeName or ): TypeName?
            result = result.replace(Regex("""\)\s*:\s*($typePattern)""")) { ")" }

            // Container param types: (param: { CardHandle }) — keep the param name,
            // drop the annotation. Matches the same brace-delimited form as returns.
            result = result.replace(Regex("""\b(\w+)\s*:\s*\{[^}]*}""")) { it.groupValues[1] }

            return result
        }

        /**
         * Rewrite `for ... in EXPR do` to `for ... in ipairs(EXPR) do` or `pairs(EXPR)`
         * when EXPR isn't already wrapped. Iterator choice comes from [LuaApiDocs] —
         * array-returning calls (`{ X }`) wrap in `ipairs`, map-returning calls
         * (`{ [K]: V }`) wrap in `pairs`, and anything we can't resolve defaults to
         * `pairs` since it iterates any table safely.
         *
         * Intentionally conservative: if the expression already starts with `ipairs(` /
         * `pairs(`, or parses to something other than a function/method call, we leave
         * it alone — users writing custom iterators keep full control.
         */
        fun wrapForLoopIterators(source: String): String {
            // First pass: infer container kind for bare-variable for-loops. Covers two
            // sources, with the explicit annotation winning when both apply:
            //   * `local xs: { T }` / `local xs: { [K]: V }` — user-declared container
            //     type. Authoritative.
            //   * `local xs = fn()` where fn returns a container (per [LuaApiDocs]) —
            //     inferred fallback.
            val containerVars = mutableMapOf<String, LuaApiDocs.Container>()
            val annotationPattern = Regex("""\blocal\s+(\w+)\s*:\s*(\{[^}]*})""")
            for (match in annotationPattern.findAll(source)) {
                val varName = match.groupValues[1]
                val rt = LuaApiDocs.parseReturnType("() → ${match.groupValues[2]}")
                if (rt != null && rt.container != LuaApiDocs.Container.NONE) {
                    containerVars[varName] = rt.container
                }
            }
            val localPattern = Regex("""\blocal\s+(\w+)\s*=\s*(.+)""")
            for (match in localPattern.findAll(source)) {
                val varName = match.groupValues[1]
                if (varName in containerVars) continue // explicit annotation wins
                val rhs = match.groupValues[2].trim()
                val methodName = Regex("""(\w+)\s*\(""").findAll(rhs).lastOrNull()?.groupValues?.get(1) ?: continue
                val rt = LuaApiDocs.methodReturnType(methodName) ?: continue
                if (rt.container != LuaApiDocs.Container.NONE) {
                    containerVars[varName] = rt.container
                }
            }

            val forPattern = Regex("""\bfor\s+(\w+(?:\s*,\s*\w+)?)\s+in\s+(.+?)\s+do\b""")
            return forPattern.replace(source) { match ->
                val binding = match.groupValues[1]
                val expr = match.groupValues[2].trim()
                if (expr.startsWith("ipairs(") || expr.startsWith("pairs(")) return@replace match.value

                // Bare variable referring to a tracked container: pick the right wrapper
                // based on what the original call returned. Falls through to the call
                // resolution below when the var isn't known to hold a container.
                val bareContainer = if (Regex("""^\w+$""").matches(expr)) containerVars[expr] else null

                val container = bareContainer ?: run {
                    val methodName = Regex("""(\w+)\s*\(""").findAll(expr).lastOrNull()?.groupValues?.get(1)
                    methodName?.let { LuaApiDocs.methodReturnType(it)?.container }
                }
                val iter = when (container) {
                    LuaApiDocs.Container.ARRAY -> "ipairs"
                    LuaApiDocs.Container.MAP -> "pairs"
                    // Unknown / scalar / nothing parsed — default to `pairs` because it
                    // iterates any table shape without needing contiguous integer keys.
                    else -> "pairs"
                }
                "for $binding in $iter($expr) do"
            }
        }
    }

    private fun createCardTable(card: damien.nodeworks.network.CardSnapshot, alias: String): LuaTable {
        val table = CardHandle.create(card, level)
        val cap = card.capability

        if (cap is damien.nodeworks.card.RedstoneSideCapability) {
            // Remove inventory methods that don't apply to redstone
            table.set("find", LuaValue.NIL)
            table.set("findEach", LuaValue.NIL)
            table.set("insert", LuaValue.NIL)
            table.set("count", LuaValue.NIL)
            table.set("slots", LuaValue.NIL)

            // powered() → boolean
            table.set("powered", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue {
                    val strength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    return LuaValue.valueOf(strength > 0)
                }
            })

            // strength() → number 0-15
            table.set("strength", object : OneArgFunction() {
                override fun call(selfArg: LuaValue): LuaValue {
                    val strength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    return LuaValue.valueOf(strength)
                }
            })

            // set(boolean | number) — emit redstone signal
            table.set("set", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, valueArg: LuaValue): LuaValue {
                    val strength = when {
                        valueArg.isboolean() -> if (valueArg.toboolean()) 15 else 0
                        valueArg.isnumber() -> valueArg.checkint().coerceIn(0, 15)
                        else -> throw LuaError("set() expects boolean or number (0-15)")
                    }
                    val entity = level.getBlockEntity(cap.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity
                        ?: throw LuaError("Node block entity not found")
                    entity.setRedstoneOutput(cap.nodeSide, strength)
                    return LuaValue.NIL
                }
            })

            // onChange(function(strength: number)) — register callback for signal changes
            table.set("onChange", object : TwoArgFunction() {
                override fun call(selfArg: LuaValue, fnArg: LuaValue): LuaValue {
                    val fn = fnArg.checkfunction()
                    val currentStrength = level.getSignal(cap.adjacentPos, cap.nodeSide)
                    redstoneCallbacks[alias] = RedstoneCallback(cap, currentStrength, fn)
                    return LuaValue.NIL
                }
            })
        }

        return table
    }

    /**
     * Backs both `network:insert` (atomic=true → boolean) and `network:tryInsert`
     * (atomic=false → number). Structured identically to [CardHandle]'s insert pair so
     * scripts get consistent semantics whether they're targeting a specific card or the
     * network as a whole. `routeTable` / `inventoryCache` are read from `this` at call
     * time so routes registered mid-script via `network:route` take effect.
     */
    private fun buildNetworkInsertFn(snapshot: NetworkSnapshot, atomic: Boolean): VarArgFunction {
        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val itemsTable = args.checktable(2)
                val maxCount = if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    args.checklong(3)
                } else {
                    Long.MAX_VALUE
                }
                val ref = itemsTable.get("_itemsHandle")
                if (ref.isnil() || ref !is ItemsHandle.ItemsHandleRef) {
                    throw LuaError("Expected an ItemsHandle from :find() or network:craft()")
                }
                val itemsHandle = ref.handle
                val requested = minOf(maxCount, itemsHandle.count.toLong())
                if (requested <= 0L) {
                    return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)
                }

                return if (itemsHandle.kind == damien.nodeworks.platform.ResourceKind.FLUID) {
                    invokeFluid(snapshot, itemsHandle, requested, atomic)
                } else {
                    invokeItems(snapshot, itemsHandle, requested, atomic)
                }
            }
        }
    }

    /**
     * Fluid insert path.
     *
     * Atomic mode: first sim the network capacity via [NetworkStorageHelper.tryInsertFluidAcrossNetwork]
     * — which itself runs sim-first — so source is never drained unless the full amount is
     * known to fit. Draining from the handle's source is the last step; if the network
     * commit diverges from the sim, unwinds push fluid back.
     *
     * Best-effort: drain-then-place, push unused back to source. Fluids are never destroyed
     * on overflow.
     */
    private fun invokeFluid(
        snapshot: NetworkSnapshot,
        itemsHandle: ItemsHandle,
        requested: Long,
        atomic: Boolean
    ): LuaValue {
        val sourceFluid = itemsHandle.fluidSourceStorage()
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)

        if (atomic) {
            // Capacity probe BEFORE touching source: sum simulate across cards.
            val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
            var capacity = 0L
            for (card in storageCards) {
                if (capacity >= requested) break
                val dest = NetworkStorageHelper.getFluidStorage(level, card) ?: continue
                capacity += try {
                    damien.nodeworks.platform.PlatformServices.storage.simulateInsertFluid(
                        dest, itemsHandle.itemId, requested - capacity
                    )
                } catch (_: Exception) { 0L }
            }
            if (capacity < requested) return LuaValue.FALSE

            val drained = damien.nodeworks.platform.PlatformServices.storage.extractFluid(
                sourceFluid, { it == itemsHandle.itemId }, requested
            )
            if (drained < requested) {
                // Source turned out short after the probe passed — refund and bail.
                if (drained > 0L) {
                    damien.nodeworks.platform.PlatformServices.storage.insertFluid(
                        sourceFluid, itemsHandle.itemId, drained
                    )
                }
                return LuaValue.FALSE
            }
            val placed = NetworkStorageHelper.insertFluidAcrossNetwork(
                level, snapshot, itemsHandle.itemId, drained, inventoryCache
            )
            if (placed < drained) {
                // Commit diverged from sim — refund the shortfall to source.
                damien.nodeworks.platform.PlatformServices.storage.insertFluid(
                    sourceFluid, itemsHandle.itemId, drained - placed
                )
                return LuaValue.FALSE
            }
            return LuaValue.TRUE
        }

        // Best-effort: drain then place what fits; return unused to source.
        val drained = damien.nodeworks.platform.PlatformServices.storage.extractFluid(
            sourceFluid, { it == itemsHandle.itemId }, requested
        )
        if (drained <= 0L) return LuaValue.valueOf(0)
        val placed = NetworkStorageHelper.insertFluidAcrossNetwork(
            level, snapshot, itemsHandle.itemId, drained, inventoryCache
        )
        if (placed < drained) {
            damien.nodeworks.platform.PlatformServices.storage.insertFluid(
                sourceFluid, itemsHandle.itemId, drained - placed
            )
        }
        return LuaValue.valueOf(placed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * Item insert path. Delegates to [NetworkStorageHelper] for both modes. The atomic
     * variant rolls back by reverse-moving on shortfall; best-effort returns the count
     * that actually landed.
     */
    private fun invokeItems(
        snapshot: NetworkSnapshot,
        itemsHandle: ItemsHandle,
        requested: Long,
        atomic: Boolean
    ): LuaValue {
        val sourceStorage = itemsHandle.sourceStorage()
            ?: return if (atomic) LuaValue.FALSE else LuaValue.valueOf(0)

        return if (atomic) {
            val ok = NetworkStorageHelper.tryInsertItemsAcrossNetwork(
                level, snapshot, sourceStorage, itemsHandle.filter,
                requested, routeTable, inventoryCache
            )
            LuaValue.valueOf(ok)
        } else {
            val moved = NetworkStorageHelper.insertItems(
                level, snapshot, sourceStorage, itemsHandle.filter,
                requested, routeTable, null, inventoryCache
            )
            LuaValue.valueOf(moved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun injectApi(g: Globals) {
        val snapshot = networkSnapshot!!

        // scheduler object
        g.set("scheduler", scheduler.createLuaTable())

        // network object
        val networkTable = LuaTable()

        // network:get(alias) → CardHandle or error
        networkTable.set("get", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                val card = snapshot.findByAlias(alias)
                    ?: throw LuaError("Not found on network: '$alias'")
                return createCardTable(card, alias)
            }
        })

        // network:getAll(type) → list of CardHandles matching that type
        networkTable.set("getAll", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, typeArg: LuaValue): LuaValue {
                val type = typeArg.checkjstring()
                val cards = snapshot.allCards().filter { it.capability.type == type }
                val result = LuaTable()
                for ((i, card) in cards.withIndex()) {
                    result.set(i + 1, createCardTable(card, card.effectiveAlias))
                }
                return result
            }
        })

        // network:find(filter) → ItemsHandle or nil (scans real storage, aggregated count)
        // Respects kind-qualified filters (`item:*`, `fluid:*`). Bare filters check items first.
        networkTable.set("find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = CardHandle.parseFilterKind(filter)

                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.ITEM) {
                    val itemResult = NetworkStorageHelper.findFirstItemInfoAcrossNetwork(level, snapshot, filter)
                    if (itemResult != null) {
                        val (info, _) = itemResult
                        val totalCount = NetworkStorageHelper.countItems(level, snapshot, filter)
                        val aggregatedInfo = info.copy(count = totalCount)
                        val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                                val storage = NetworkStorageHelper.getStorage(level, card)
                                if (storage != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                        CardHandle.matchesFilter(it, damien.nodeworks.platform.ResourceKind.ITEM, filter)
                                    }
                                    if (has > 0) storage else null
                                } else null
                            }
                        }
                        return ItemsHandle.toLuaTable(ItemsHandle.fromItemInfo(aggregatedInfo, filter, sourceStorage, level))
                    }
                }
                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.FLUID) {
                    val fluidResult = NetworkStorageHelper.findFirstFluidInfoAcrossNetwork(level, snapshot, filter)
                    if (fluidResult != null) {
                        val (info, _) = fluidResult
                        val totalAmount = NetworkStorageHelper.countFluid(level, snapshot, filter)
                        val aggregated = damien.nodeworks.platform.FluidInfo(info.fluidId, info.name, totalAmount)
                        val fluidSource: () -> damien.nodeworks.platform.FluidStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                                val storage = NetworkStorageHelper.getFluidStorage(level, card)
                                if (storage != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countFluid(storage) { it == info.fluidId }
                                    if (has > 0) storage else null
                                } else null
                            }
                        }
                        return ItemsHandle.toLuaTable(ItemsHandle.fromFluidInfo(aggregated, filter, fluidSource, level))
                    }
                }
                return LuaValue.NIL
            }
        })

        // network:findEach(filter) → table of ItemsHandles (scans real storage).
        // Bare filter lists items then fluids; kind-prefixed filter yields only that kind.
        networkTable.set("findEach", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (kindGate, _) = CardHandle.parseFilterKind(filter)
                val result = LuaTable()
                var idx = 1
                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.ITEM) {
                    val allItems = NetworkStorageHelper.findAllItemInfoAcrossNetwork(level, snapshot, filter)
                    for (pair in allItems) {
                        val (info, _) = pair
                        val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                                val storage = NetworkStorageHelper.getStorage(level, card)
                                if (storage != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) { it == info.itemId }
                                    if (has > 0) storage else null
                                } else null
                            }
                        }
                        val handle = ItemsHandle.fromItemInfo(info, info.itemId, sourceStorage, level)
                        result.set(idx++, ItemsHandle.toLuaTable(handle))
                    }
                }
                if (kindGate == null || kindGate == damien.nodeworks.platform.ResourceKind.FLUID) {
                    // Single-pass aggregation — avoids O(N*M) rescans from calling countFluid
                    // per discovered fluid id.
                    val allFluids = NetworkStorageHelper.findAllFluidInfoAcrossNetwork(level, snapshot, filter)
                    for ((info, _) in allFluids) {
                        val fluidSource: () -> damien.nodeworks.platform.FluidStorageHandle? = {
                            NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { c ->
                                val s = NetworkStorageHelper.getFluidStorage(level, c)
                                if (s != null) {
                                    val has = damien.nodeworks.platform.PlatformServices.storage.countFluid(s) { it == info.fluidId }
                                    if (has > 0) s else null
                                } else null
                            }
                        }
                        val handle = ItemsHandle.fromFluidInfo(info, "\$fluid:${info.fluidId}", fluidSource, level)
                        result.set(idx++, ItemsHandle.toLuaTable(handle))
                    }
                }
                return result
            }
        })

        // network:count(filter) → number (items + fluids, or kind-filtered)
        networkTable.set("count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val count = NetworkStorageHelper.countResource(level, snapshot, filter)
                return LuaValue.valueOf(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        })

        // network:insert(itemsHandle, count?) → boolean (atomic — either the full count lands
        // in network storage or nothing moves). Mirrors CardHandle:insert for consistency.
        // Use network:tryInsert for "move what fits, leave the rest" semantics.
        networkTable.set("insert", buildNetworkInsertFn(snapshot, atomic = true))

        // network:tryInsert(itemsHandle, count?) → number (best-effort count moved).
        networkTable.set("tryInsert", buildNetworkInsertFn(snapshot, atomic = false))

        // network:craft(identifier, count?) → CraftBuilder with :connect(fn) and :store()
        networkTable.set("craft", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val identifier = args.checkjstring(2)
                val count = if (args.narg() >= 3 && !args.arg(3).isnil()) args.checkint(3) else 1

                CraftingHelper.currentPendingJob = null
                val result = CraftingHelper.craft(identifier, count, level, snapshot, cache = inventoryCache, processingHandlers = processingHandlers.takeIf { it.isNotEmpty() }, callerScheduler = scheduler, traceLog = { msg -> logCallback(msg, false) })

                // Check for async pending job (processing handler or async assembly)
                val pending = CraftingHelper.currentPendingJob
                CraftingHelper.currentPendingJob = null

                if (result == null && pending == null) {
                    CraftingHelper.lastFailReason?.let { logCallback(it, true) }
                    return LuaValue.NIL
                }

                // For async: result is null but pending is set. Build a CraftResult placeholder.
                val craftResult = result ?: run {
                    // Async — we don't know the exact output yet. Use the identifier.
                    val id = net.minecraft.resources.Identifier.tryParse(identifier)
                    val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) else null
                    val name = if (item != null) net.minecraft.world.item.ItemStack(item).hoverName.string else identifier
                    CraftingHelper.CraftResult(identifier, name, count,
                        cpu = snapshot.cpus.firstOrNull()?.let { level.getBlockEntity(it.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity },
                        level = level, snapshot = snapshot, cache = inventoryCache)
                }

                // Helper: release CPU buffer → storage and create ItemsHandle
                fun releaseAndCreateHandle(): LuaTable {
                    CraftingHelper.releaseCraftResult(craftResult)
                    val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
                    val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                        storageCards.firstNotNullOfOrNull { card ->
                            val storage = NetworkStorageHelper.getStorage(level, card)
                            if (storage != null) {
                                val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                    CardHandle.matchesFilter(it, craftResult.outputItemId)
                                }
                                if (has > 0) storage else null
                            } else null
                        }
                    }
                    return ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                        itemId = craftResult.outputItemId,
                        itemName = craftResult.outputName,
                        count = craftResult.count,
                        sourceStorage = sourceStorage,
                        level = level
                    ))
                }

                // Build the CraftBuilder table
                val builder = LuaTable()

                // :connect(fn) — release to storage, call fn with ItemsHandle
                builder.set("connect", object : TwoArgFunction() {
                    override fun call(selfArg: LuaValue, callbackArg: LuaValue): LuaValue {
                        val callback = callbackArg.checkfunction()

                        if (pending == null || pending.isComplete) {
                            // Instant — release and call now
                            val handle = releaseAndCreateHandle()
                            try { callback.call(handle) }
                            catch (e: LuaError) { logCallback("craft callback error: ${e.message}", true) }
                        } else {
                            // Async — wait for pending job, then release and call
                            pending.onCompleteCallback = { success ->
                                if (success) {
                                    val handle = releaseAndCreateHandle()
                                    try { callback.call(handle) }
                                    catch (e: LuaError) { logCallback("craft callback error: ${e.message}", true) }
                                } else {
                                    logCallback("Craft timed out for '$identifier'", true)
                                    // Clean up CPU buffer
                                    CraftingHelper.releaseCraftResult(craftResult)
                                }
                            }
                        }
                        return LuaValue.NIL
                    }
                })

                // :store() — release to storage, no callback
                builder.set("store", object : OneArgFunction() {
                    override fun call(selfArg: LuaValue): LuaValue {
                        if (pending == null || pending.isComplete) {
                            CraftingHelper.releaseCraftResult(craftResult)
                        } else {
                            pending.onCompleteCallback = { _ ->
                                CraftingHelper.releaseCraftResult(craftResult)
                            }
                        }
                        return LuaValue.NIL
                    }
                })

                return builder
            }
        })

        // network:route(alias, predicate) — register a declarative storage route
        // Items where predicate(itemsHandle) returns true go to that storage
        routeTable = RouteTable(level, snapshot)
        networkTable.set("route", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue, predicateArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                val predicate = predicateArg.checkfunction()
                routeTable?.addRoute(alias, predicate)
                return LuaValue.NIL
            }
        })


        // network:shapeless(item1, count1, item2?, count2?, ...) → ItemsHandle or nil
        // Crafts using vanilla shapeless recipes. Inputs are item/count pairs.
        networkTable.set("shapeless", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                // Parse item/count pairs from varargs (self is arg1)
                val ingredients = mutableMapOf<String, Int>()
                var i = 2
                while (i <= args.narg()) {
                    val itemId = args.checkjstring(i)
                    val count = if (i + 1 <= args.narg() && !args.arg(i + 1).isnil() && args.arg(i + 1).isnumber()) {
                        i++
                        args.checkint(i)
                    } else {
                        1
                    }
                    ingredients[itemId] = (ingredients[itemId] ?: 0) + count
                    i++
                }

                if (ingredients.isEmpty()) throw LuaError("shapeless requires at least one item")

                val result = ShapelessCraftHelper.craft(ingredients, level, snapshot, cache = inventoryCache)
                    ?: return LuaValue.NIL

                val storageCards2 = NetworkStorageHelper.getStorageCards(snapshot)
                val sourceStorage2: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                    storageCards2.firstNotNullOfOrNull { card ->
                        val storage = NetworkStorageHelper.getStorage(level, card)
                        if (storage != null) {
                            val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                CardHandle.matchesFilter(it, result.outputItemId)
                            }
                            if (has > 0) storage else null
                        } else null
                    }
                }

                return ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                    itemId = result.outputItemId,
                    itemName = result.outputName,
                    count = result.count,
                    sourceStorage = sourceStorage2,
                    level = level
                ))
            }
        })

        // network:handle(cardName, handlerFn) — register a processing handler
        // cardName matches the name set on a Processing Set in Processing Storage.
        // The handler function receives input items as arguments and should return
        // the result ItemsHandle from the processing machine's output.
        networkTable.set("handle", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue, handlerArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val handler = handlerArg.checkfunction()
                processingHandlers[name] = handler
                return LuaValue.NIL
            }
        })

        networkTable.set("var", object : TwoArgFunction() {
            override fun call(self: LuaValue, nameArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val varSnapshot = snapshot.findVariable(name)
                    ?: throw LuaError("Variable not found on network: '$name'")
                return VariableHandle.create(varSnapshot, level)
            }
        })

        // network:debug() — print full network summary
        networkTable.set("debug", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                val sb = StringBuilder()
                sb.appendLine("=== Network Debug ===")
                sb.appendLine("Controller: ${snapshot.controller?.pos ?: "none"}")
                sb.appendLine("Nodes: ${snapshot.nodes.size}")
                for (node in snapshot.nodes) {
                    val cardCount = node.sides.values.sumOf { it.size }
                    sb.appendLine("  Node ${node.pos}: $cardCount cards")
                    for ((dir, cards) in node.sides) {
                        for (card in cards) {
                            sb.appendLine("    ${dir.name}: ${card.effectiveAlias} (${card.capability.type})")
                        }
                    }
                }
                sb.appendLine("Terminals: ${snapshot.terminalPositions.size}")
                for (pos in snapshot.terminalPositions) sb.appendLine("  $pos")
                sb.appendLine("CPUs: ${snapshot.cpus.size}")
                for (cpu in snapshot.cpus) sb.appendLine("  ${cpu.pos}: ${cpu.bufferUsed}/${cpu.bufferCapacity} ${if (cpu.isBusy) "BUSY" else "idle"}")
                sb.appendLine("Crafters (Instruction Sets): ${snapshot.crafters.size}")
                for (crafter in snapshot.crafters) {
                    sb.appendLine("  ${crafter.pos}: ${crafter.instructionSets.size} recipes")
                    for (recipe in crafter.instructionSets) sb.appendLine("    ${recipe.alias ?: recipe.outputItemId}")
                }
                sb.appendLine("Processing APIs: ${snapshot.processingApis.size}")
                for (api in snapshot.processingApis) {
                    val remote = if (api.remoteTerminalPositions != null) " (remote)" else ""
                    sb.appendLine("  ${api.pos}$remote: ${api.apis.size} cards")
                    for (card in api.apis) {
                        val inputs = card.inputs.joinToString(", ") { "${it.first} x${it.second}" }
                        val outputs = card.outputs.joinToString(", ") { "${it.first} x${it.second}" }
                        sb.appendLine("    [${card.name}] $inputs -> $outputs")
                    }
                }
                sb.appendLine("Variables: ${snapshot.variables.size}")
                for (v in snapshot.variables) sb.appendLine("  ${v.name} (${v.type})")
                logCallback(sb.toString().trimEnd(), false)
                return LuaValue.NIL
            }
        })

        g.set("network", networkTable)

        // clock() -> seconds since script started (as a decimal)
        val startTime = System.currentTimeMillis()
        g.set("clock", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                return LuaValue.valueOf(elapsed)
            }
        })

        // print(message)
        g.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val parts = mutableListOf<String>()
                for (i in 1..args.narg()) {
                    parts.add(formatValue(args.arg(i)))
                }
                logCallback(parts.joinToString("  "), false)
                return LuaValue.NONE
            }
        })
    }

    private fun formatValue(value: LuaValue, depth: Int = 0): String {
        if (depth > 3) return "{...}"
        return when {
            value.istable() -> {
                val table = value.checktable()
                val entries = mutableListOf<String>()
                var isArray = true
                var arrayIdx = 1

                // First pass: check if it's a pure array
                var key = LuaValue.NIL
                while (true) {
                    val n = table.next(key)
                    if (n.arg1().isnil()) break
                    key = n.arg1()
                    if (!key.isint() || key.toint() != arrayIdx) {
                        isArray = false
                        break
                    }
                    arrayIdx++
                }

                // Second pass: format entries
                key = LuaValue.NIL
                while (true) {
                    val n = table.next(key)
                    if (n.arg1().isnil()) break
                    key = n.arg1()
                    val v = n.arg(2)
                    if (entries.size >= 20) { entries.add("..."); break }
                    if (isArray) {
                        entries.add(formatValue(v, depth + 1))
                    } else {
                        val keyStr = if (key.isstring()) key.tojstring() else formatValue(key, depth + 1)
                        entries.add("$keyStr = ${formatValue(v, depth + 1)}")
                    }
                }

                if (entries.isEmpty()) "{}" else "{ ${entries.joinToString(", ")} }"
            }
            value.isstring() -> "\"${value.tojstring()}\""
            else -> value.tojstring()
        }
    }
}
