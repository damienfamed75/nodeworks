package damien.nodeworks.script.preset

import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.CraftingHelper
import damien.nodeworks.script.NetworkStorageHelper
import damien.nodeworks.script.ScriptEngine
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction

/**
 * Factory for [StockerBuilder]. Exposed as the `stocker` Lua global.
 *
 * Three entry points:
 *   * `stocker:from(filter, sources...)`, pull from specific cards (or the pool)
 *   * `stocker:ensure(itemId)`, pull from the pool first, craft the rest if short
 *   * `stocker:craft(itemId)`, always craft (never pull)
 */
object Stocker {

    fun createGlobal(engine: ScriptEngine): LuaTable {
        val t = LuaTable()

        t.set("from", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val sources = CardRefs.fromVarargs(args, 2)
                if (sources.isEmpty()) {
                    throw LuaError("stocker:from requires at least one source")
                }
                val mode = if (sources.all { it is CardRef.Pool }) StockerMode.POOL_ONLY else StockerMode.PULL_ONLY
                val b = StockerBuilder(engine, sources, mode)
                engine.registerPreset(b)
                return b.toLuaTable()
            }
        })

        t.set("ensure", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, idArg: LuaValue): LuaValue {
                val id = idArg.checkjstring()
                val b = StockerBuilder(
                    engine,
                    sources = listOf(CardRef.Pool),
                    mode = StockerMode.POOL_OR_CRAFT,
                    craftItemId = id
                ).apply { filter = id } // ensure uses the concrete item id as the filter so target counts match
                engine.registerPreset(b)
                return b.toLuaTable()
            }
        })

        t.set("craft", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, idArg: LuaValue): LuaValue {
                val id = idArg.checkjstring()
                val b = StockerBuilder(
                    engine,
                    sources = emptyList(),
                    mode = StockerMode.CRAFT_ONLY,
                    craftItemId = id
                ).apply { filter = id }
                engine.registerPreset(b)
                return b.toLuaTable()
            }
        })

        return t
    }
}

enum class StockerMode { PULL_ONLY, POOL_ONLY, POOL_OR_CRAFT, CRAFT_ONLY }

/**
 * Maintains a target's stock at [keepAmount] by pulling from sources or crafting.
 *
 * Never extracts: if the target already holds more than [keepAmount] (player dumped
 * in extras), the stocker is a no op. It's a "fill to" watermark, not a clamp.
 *
 * Craft batching: `:batch(n)` coalesces small shortfalls into n-sized craft requests.
 * Useful for recipes with large setup cost where many tiny crafts would thrash the
 * CPU. Default batch size 0 means "craft exactly what's needed each tick."
 *
 * Item only in v1.
 */
class StockerBuilder(
    engine: ScriptEngine,
    internal val sources: List<CardRef>,
    internal val mode: StockerMode,
    internal val craftItemId: String? = null,
) : PresetBuilder<StockerBuilder>(engine) {

    /** Match pattern for items against the target. Defaults to `"*"` (match anything,
     *  which means the stocker maintains a total item count of any kind). `:ensure` and
     *  `:craft` initialise this to the concrete craft item id so `:keep(n)` counts only
     *  that item in the target. For `:from`, set it with `:filter("...")`. */
    internal var filter: String = "*"
    internal var target: CardRef? = null
    internal var keepAmount: Int = -1
    internal var batchSize: Int = 0

    /** Count of items currently being crafted on behalf of this stocker. Subtracted
     *  from "need" so we don't spam the CPU with redundant plans while one is in flight. */
    private var pendingCraftCount: Int = 0

    // Per-snapshot cached resolutions. Sources expand wildcards (e.g. `"chest_*"` fans
    // out to every matching card), target stays single because "maintain 64 in each of
    // chest_*" has ambiguous semantics that a power user can express with multiple
    // stockers if they really want it.
    private var resolvedSources: List<ResolvedRef> = emptyList()
    // Target stays single (no wildcard expansion). Stored as a [ResolvedRef.Card] so
    // the face override (if any) carried in by a face-overridden CardHandle survives
    // through to the storage lookup, same as on the source side.
    private var resolvedTarget: ResolvedRef.Card? = null

    override val presetName = "stocker"

    fun to(dest: CardRef): StockerBuilder {
        target = dest
        return this
    }

    fun keep(n: Int): StockerBuilder {
        require(n >= 0) { "stocker:keep(n) requires n >= 0" }
        keepAmount = n
        return this
    }

    fun batch(n: Int): StockerBuilder {
        require(n >= 0) { "stocker:batch(n) requires n >= 0" }
        batchSize = n
        return this
    }

    fun filter(pattern: String): StockerBuilder {
        filter = pattern
        return this
    }

    override fun validate() {
        if (target == null) {
            throw LuaError("stocker: :to(target) required before :start()")
        }
        if (keepAmount < 0) {
            throw LuaError("stocker: :keep(n) required before :start()")
        }
        if ((mode == StockerMode.POOL_OR_CRAFT || mode == StockerMode.CRAFT_ONLY) && craftItemId == null) {
            throw LuaError("stocker: craft modes require a concrete item id")
        }
    }

    override fun onSnapshotChanged(snapshot: NetworkSnapshot) {
        resolvedSources = sources.flatMap { expandCardRef(snapshot, it) }
        resolvedTarget = when (val t = target) {
            is CardRef.Named -> snapshot.findByAlias(t.alias)?.let {
                ResolvedRef.Card(it, t.faceOverride)
            }
            else -> null
        }
    }

    override fun tickOnce() {
        val snapshot = lastSnapshotSeen ?: return
        val level = engine.level
        val filterPred: (String) -> Boolean = { CardHandle.matchesFilter(it, filter) }
        val targetRef = target ?: return

        // 1. Figure out current stock in the target.
        val current = when (targetRef) {
            is CardRef.Pool -> NetworkStorageHelper.countItems(level, snapshot, filter).toInt()
            is CardRef.Named -> {
                val storage = resolvedTarget?.let { CardStorage.forCard(level, it.snapshot, it.faceOverride) } ?: return
                PlatformServices.storage.countItems(storage, filterPred)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }

        var need = keepAmount - current - pendingCraftCount
        if (need <= 0) return

        // 2. Pulling phase (PULL_ONLY / POOL_ONLY / POOL_OR_CRAFT).
        if (mode != StockerMode.CRAFT_ONLY) {
            need = pullIntoTarget(snapshot, level, targetRef, filterPred, need)
            if (need <= 0) return
        }

        // 2b. CRAFT_ONLY with a Named target: the CPU writes craft output back into the
        // network pool, so we still have to drain pool → target each tick or the items
        // pile up in storage and never reach the named card. POOL_OR_CRAFT already does
        // this implicitly through its `[Pool]` source in step 2, CRAFT_ONLY has empty
        // sources so it needs the explicit move.
        if (mode == StockerMode.CRAFT_ONLY && targetRef is CardRef.Named) {
            val drained = movePoolToTarget(snapshot, level, targetRef, filterPred, need.toLong())
            need = (need - drained.toInt()).coerceAtLeast(0)
            if (need <= 0) return
        }

        // 3. Crafting phase (POOL_OR_CRAFT / CRAFT_ONLY).
        if (mode == StockerMode.POOL_OR_CRAFT || mode == StockerMode.CRAFT_ONLY) {
            issueCraft(need)
        }
    }

    /** Moves items from configured sources into the target. Returns the remaining
     *  shortfall after pulling. Iterates the fully-expanded source list so wildcard
     *  sources like `"chest_*"` contribute each matched card individually. */
    private fun pullIntoTarget(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        targetRef: CardRef,
        filterPred: (String) -> Boolean,
        needIn: Int,
    ): Int {
        var need = needIn
        for (source in resolvedSources) {
            if (need <= 0) break
            when (source) {
                is ResolvedRef.Card -> {
                    val srcStorage = CardStorage.forCard(level, source.snapshot, source.faceOverride) ?: continue
                    need -= moveFromStorageToTarget(snapshot, level, srcStorage, targetRef, filterPred, need.toLong())
                        .toInt().coerceAtLeast(0)
                }
                is ResolvedRef.Pool -> {
                    need -= movePoolToTarget(snapshot, level, targetRef, filterPred, need.toLong())
                        .toInt().coerceAtLeast(0)
                }
            }
        }
        return need.coerceAtLeast(0)
    }

    private fun moveFromStorageToTarget(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        source: ItemStorageHandle,
        targetRef: CardRef,
        filterPred: (String) -> Boolean,
        maxCount: Long,
    ): Long = when (targetRef) {
        is CardRef.Named -> {
            val destStorage = resolvedTarget?.let { CardStorage.forCard(level, it.snapshot, it.faceOverride) }
            if (destStorage == null) 0L
            else PlatformServices.storage.moveItems(source, destStorage, filterPred, maxCount)
        }
        is CardRef.Pool -> NetworkStorageHelper.insertItems(
            level, snapshot, source, filter,
            maxCount, engine.routeTable, null, engine.inventoryCache
        )
    }

    private fun movePoolToTarget(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        targetRef: CardRef,
        filterPred: (String) -> Boolean,
        maxCount: Long,
    ): Long {
        if (targetRef is CardRef.Pool) return 0L
        val destStorage = resolvedTarget?.let { CardStorage.forCard(level, it.snapshot, it.faceOverride) } ?: return 0L
        val cache = engine.inventoryCache
        var remaining = maxCount
        var totalMoved = 0L
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L) break
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            // Per (itemId, hasData) move so the cache gets a paired onExtracted for
            // each item that leaves the pool. See [Importer.movePoolToCard] for the
            // full rationale, same orphan-entry leak applies here.
            val infos = PlatformServices.storage.findAllItemInfo(storage) { filterPred(it) }
            for (info in infos) {
                if (remaining <= 0L) break
                val moved = PlatformServices.storage.moveItemsVariant(
                    storage, destStorage,
                    { id, hasData -> id == info.itemId && hasData == info.hasData },
                    remaining,
                )
                totalMoved += moved
                remaining -= moved
                if (moved > 0L) cache?.onExtracted(info.itemId, info.hasData, moved)
            }
        }
        return totalMoved
    }

    /** Submit a craft to the CPU for [need] items, coalesced to [batchSize] if set.
     *
     *  Under the scheduler based CPU model the executor flushes the CPU buffer back
     *  to the pool on plan completion, so this preset only tracks the in-flight
     *  count. Once the pending job completes (success or failure), the next tick
     *  reassesses current stock and queues another batch if one is still needed. */
    private fun issueCraft(need: Int) {
        val snapshot = lastSnapshotSeen ?: return
        val itemId = craftItemId ?: return
        val batch = if (batchSize > 0) batchSize else need
        if (batch <= 0) return

        CraftingHelper.currentPendingJob = null
        val result = CraftingHelper.craft(
            itemId, batch, engine.level, snapshot,
            cache = engine.inventoryCache,
            processingHandlers = engine.processingHandlers.takeIf { it.isNotEmpty() },
            callerScheduler = engine.scheduler,
        )
        val pending = CraftingHelper.currentPendingJob
        CraftingHelper.currentPendingJob = null

        if (result == null && pending == null) {
            CraftingHelper.lastFailReason?.let { engine.logError("[stocker] $it") }
            return
        }

        pendingCraftCount += batch
        val decrement: (Boolean) -> Unit = { _ ->
            pendingCraftCount = (pendingCraftCount - batch).coerceAtLeast(0)
        }
        if (pending != null && !pending.isComplete) {
            pending.onCompleteCallback = decrement
        } else {
            // Synchronous completion (rare), decrement immediately.
            decrement(true)
        }
    }

    override fun populateMethods(t: LuaTable) {
        val selfRef = this
        t.set("to", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, destArg: LuaValue): LuaValue {
                selfRef.to(CardRefs.fromLua(destArg))
                return selfRef.toLuaTable()
            }
        })
        t.set("keep", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, nArg: LuaValue): LuaValue {
                selfRef.keep(nArg.checkint())
                return selfRef.toLuaTable()
            }
        })
        t.set("batch", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, nArg: LuaValue): LuaValue {
                selfRef.batch(nArg.checkint())
                return selfRef.toLuaTable()
            }
        })
        t.set("filter", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, patternArg: LuaValue): LuaValue {
                selfRef.filter(patternArg.checkjstring())
                return selfRef.toLuaTable()
            }
        })
    }
}
