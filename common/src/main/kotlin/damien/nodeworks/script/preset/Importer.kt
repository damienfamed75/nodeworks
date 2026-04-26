package damien.nodeworks.script.preset

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.NetworkStorageHelper
import damien.nodeworks.script.ScriptEngine
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * Factory for [ImporterBuilder]. Exposed as the `importer` Lua global.
 *
 * Entry point: `importer:from(filter, sources...)` returns a new [ImporterBuilder].
 */
object Importer {

    fun createGlobal(engine: ScriptEngine): LuaTable {
        val t = LuaTable()

        t.set("from", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                // args.arg(1) is `self`, real args start at index 2.
                val sources = CardRefs.fromVarargs(args, 2)
                if (sources.isEmpty()) {
                    throw LuaError("importer:from requires at least one source")
                }
                val builder = ImporterBuilder(engine, sources)
                engine.registerPreset(builder)
                return builder.toLuaTable()
            }
        })

        return t
    }
}

enum class DistributionStrategy { FILL, ROUND_ROBIN }

/** A concrete endpoint after wildcard expansion. Either the Network Storage pool
 *  (sentinel) or a specific Card on the network. Importer and Stocker both iterate
 *  over lists of these at tick time, wildcards like `"io_*"` fan out to multiple
 *  [Card] entries, and the global `network` becomes [Pool]. */
internal sealed class ResolvedRef {
    data object Pool : ResolvedRef()
    data class Card(val snapshot: CardSnapshot) : ResolvedRef()
}

/** Expand a single [CardRef] into zero-or-more [ResolvedRef]s. Supports `*`
 *  wildcards in card aliases (e.g. `"io_*"` matches every card whose alias starts
 *  with `io_`). An alias that doesn't resolve to any card yields an empty list,
 *  the preset silently skips it and retries on the next snapshot change. */
internal fun expandCardRef(snapshot: NetworkSnapshot, ref: CardRef): List<ResolvedRef> = when (ref) {
    is CardRef.Pool -> listOf(ResolvedRef.Pool)
    is CardRef.Named -> {
        if (!ref.alias.contains('*')) {
            val card = snapshot.findByAlias(ref.alias)
            if (card != null) listOf(ResolvedRef.Card(card)) else emptyList()
        } else {
            val regex = wildcardToRegex(ref.alias)
            snapshot.allCards()
                .filter { regex.matchEntire(it.effectiveAlias) != null }
                .distinctBy { it.effectiveAlias }
                .map { ResolvedRef.Card(it) }
        }
    }
}

/** Convert a glob-style alias (just `*` as wildcard) to a Regex. Every other
 *  character is literal so card aliases with punctuation don't get misinterpreted. */
private fun wildcardToRegex(alias: String): Regex {
    val pattern = alias.split("*").joinToString(".*") { Regex.escape(it) }
    return Regex(pattern)
}

/**
 * Moves items from sources to targets with a distribution strategy.
 *
 * Default strategy is [DistributionStrategy.FILL]: pour into each target in order,
 * draining what fits per tick. Switch to round robin with `:roundrobin(step)` to
 * spread items evenly across every target each tick.
 *
 * Sources and targets each accept string aliases (with `*` wildcards for bulk
 * selection like `"io_*"`), CardHandle objects, or the `network` global (meaning
 * "the whole Network Storage pool").
 *
 * Item-only in v1. Fluid filters (`$fluid:...`) won't move anything, add fluid
 * support in v1.1 by dispatching on filter kind.
 */
class ImporterBuilder(
    engine: ScriptEngine,
    internal val sources: List<CardRef>,
) : PresetBuilder<ImporterBuilder>(engine) {

    /** Match pattern for items/fluids. Defaults to `"*"` (everything) so a bare
     *  `importer:from(src):to(dst):start()` moves the entire contents without the
     *  user having to type a filter for the common case. Narrow it with `:filter(...)`. */
    internal var filter: String = "*"
    internal var targets: List<CardRef> = emptyList()
    internal var strategy: DistributionStrategy = DistributionStrategy.FILL
    internal var roundRobinStep: Int = 1
    internal var rrCursor: Int = 0

    /** How many items the current round robin target has received toward its
     *  [roundRobinStep] allotment. Carried across ticks so a target that got less
     *  than `step` (source ran dry) keeps priority on the next tick until its
     *  allotment is filled. Reset to 0 whenever the cursor advances or the
     *  resolved-targets list changes. */
    internal var rrServedToCurrent: Int = 0

    // Flat, post-expansion resolution. One list entry per real endpoint the tick
    // loop touches, so a single `"io_*"` CardRef becomes however many cards match.
    private var resolvedSources: List<ResolvedRef> = emptyList()
    private var resolvedTargets: List<ResolvedRef> = emptyList()

    override val presetName = "importer"

    fun to(targets: List<CardRef>): ImporterBuilder {
        this.targets = targets
        return this
    }

    fun roundrobin(step: Int): ImporterBuilder {
        require(step >= 1) { "importer:roundrobin(step) requires step >= 1" }
        strategy = DistributionStrategy.ROUND_ROBIN
        roundRobinStep = step
        return this
    }

    fun filter(pattern: String): ImporterBuilder {
        filter = pattern
        return this
    }

    override fun validate() {
        if (targets.isEmpty()) {
            throw LuaError("importer: :to(...) required before :start()")
        }
        val allSourcesPool = sources.isNotEmpty() && sources.all { it is CardRef.Pool }
        val allTargetsPool = targets.isNotEmpty() && targets.all { it is CardRef.Pool }
        if (allSourcesPool && allTargetsPool) {
            throw LuaError("importer: source and target are both network")
        }
    }

    override fun onSnapshotChanged(snapshot: NetworkSnapshot) {
        resolvedSources = sources.flatMap { expandCardRef(snapshot, it) }
        resolvedTargets = targets.flatMap { expandCardRef(snapshot, it) }
        if (resolvedTargets.isNotEmpty() && rrCursor >= resolvedTargets.size) {
            rrCursor %= resolvedTargets.size
            rrServedToCurrent = 0
        }
    }

    override fun tickOnce() {
        val snapshot = lastSnapshotSeen ?: return
        val level = engine.level
        val filterPred: (String) -> Boolean = { CardHandle.matchesFilter(it, filter) }

        when (strategy) {
            DistributionStrategy.FILL -> tickFill(snapshot, level, filterPred)
            DistributionStrategy.ROUND_ROBIN -> tickRoundRobin(snapshot, level, filterPred)
        }
    }

    /** Fill: pour into each target in order, draining as much as each target can hold
     *  from every source, then move on to the next target. */
    private fun tickFill(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        filterPred: (String) -> Boolean,
    ) {
        for (target in resolvedTargets) {
            moveIntoTarget(snapshot, level, target, filterPred, Long.MAX_VALUE)
        }
    }

    /** Round robin: stay on the current target until it has received its
     *  [roundRobinStep] allotment, then advance. Carries partial fills across
     *  ticks so a target that got less than `step` (source ran dry) keeps the
     *  cursor until it's topped off next tick.
     *
     *  Example: 5 items, step=2, 3 targets:
     *    Tick 1: chest1 fills to 2, chest2 fills to 2, chest3 starts with 1/2 → stop
     *    Tick 2 (source gets 1 more item): chest3 fills to 2/2, cursor advances to chest1
     *    Tick 3 (source gets 2 items): chest1 fills to 2/2, cursor to chest2, etc. */
    private fun tickRoundRobin(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        filterPred: (String) -> Boolean,
    ) {
        if (resolvedTargets.isEmpty()) return
        val stepL = roundRobinStep.toLong()
        // Cap one tick at exactly one full ring through the resolved targets so a
        // huge source doesn't monopolise a tick, bulk flow still comes through
        // over subsequent ticks.
        var ringsRemaining = resolvedTargets.size
        while (ringsRemaining > 0) {
            val idx = rrCursor % resolvedTargets.size
            val needed = stepL - rrServedToCurrent.toLong()
            if (needed <= 0L) {
                // Current allotment already satisfied (defensive): advance and keep going.
                rrCursor = (rrCursor + 1) % resolvedTargets.size
                rrServedToCurrent = 0
                ringsRemaining--
                continue
            }
            val moved = moveIntoTarget(snapshot, level, resolvedTargets[idx], filterPred, needed)
            rrServedToCurrent = (rrServedToCurrent.toLong() + moved).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (rrServedToCurrent.toLong() >= stepL) {
                // Allotment filled, advance to the next target for the next round.
                rrCursor = (rrCursor + 1) % resolvedTargets.size
                rrServedToCurrent = 0
                ringsRemaining--
            } else {
                // Source ran dry or target full before the allotment was hit. Keep
                // the cursor on this target so the next tick can finish filling it.
                break
            }
        }
    }

    /** Move up to [maxCount] items from each source into [target]. Returns the
     *  total count actually moved so round robin can track partial fills. */
    private fun moveIntoTarget(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        target: ResolvedRef,
        filterPred: (String) -> Boolean,
        maxCount: Long,
    ): Long {
        if (maxCount <= 0L) return 0L
        var remaining = maxCount
        for (source in resolvedSources) {
            if (remaining <= 0L) break
            remaining -= movePair(snapshot, level, source, target, filterPred, remaining)
        }
        return maxCount - remaining
    }

    /** Move items from [source] to [target] with the given per-call cap. Dispatches
     *  on all four source/target combinations. */
    private fun movePair(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        source: ResolvedRef,
        target: ResolvedRef,
        filterPred: (String) -> Boolean,
        maxCount: Long,
    ): Long {
        if (maxCount <= 0L) return 0L
        return when (source) {
            is ResolvedRef.Card -> {
                val srcStorage = CardStorage.forCard(level, source.snapshot) ?: return 0L
                when (target) {
                    is ResolvedRef.Card -> {
                        val dest = CardStorage.forCard(level, target.snapshot) ?: return 0L
                        PlatformServices.storage.moveItems(srcStorage, dest, filterPred, maxCount)
                    }
                    is ResolvedRef.Pool -> NetworkStorageHelper.insertItems(
                        level, snapshot, srcStorage, filter,
                        maxCount, engine.routeTable, null, engine.inventoryCache
                    )
                }
            }
            is ResolvedRef.Pool -> when (target) {
                // Pool to Pool is a no-op, items would just shuffle between storage cards.
                is ResolvedRef.Pool -> 0L
                is ResolvedRef.Card -> movePoolToCard(snapshot, level, target.snapshot, filterPred, maxCount)
            }
        }
    }

    /** Pull from the Network Storage pool into a specific card. Walks Storage Cards
     *  in priority order until [maxCount] is satisfied or the pool runs dry. */
    private fun movePoolToCard(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        targetCard: CardSnapshot,
        filterPred: (String) -> Boolean,
        maxCount: Long,
    ): Long {
        val destStorage = CardStorage.forCard(level, targetCard) ?: return 0L
        var remaining = maxCount
        var totalMoved = 0L
        for (poolCard in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L) break
            val poolStorage = NetworkStorageHelper.getStorage(level, poolCard) ?: continue
            val moved = PlatformServices.storage.moveItems(poolStorage, destStorage, filterPred, remaining)
            totalMoved += moved
            remaining -= moved
        }
        return totalMoved
    }

    override fun populateMethods(t: LuaTable) {
        val selfRef = this
        t.set("to", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val refs = CardRefs.fromVarargs(args, 2)
                if (refs.isEmpty()) throw LuaError("importer:to requires at least one target")
                selfRef.to(refs)
                return selfRef.toLuaTable()
            }
        })
        t.set("roundrobin", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val step = if (args.narg() >= 2 && !args.arg(2).isnil()) args.checkint(2) else 1
                selfRef.roundrobin(step)
                return selfRef.toLuaTable()
            }
        })
        t.set("filter", object : org.luaj.vm2.lib.TwoArgFunction() {
            override fun call(selfArg: LuaValue, patternArg: LuaValue): LuaValue {
                selfRef.filter(patternArg.checkjstring())
                return selfRef.toLuaTable()
            }
        })
    }
}
