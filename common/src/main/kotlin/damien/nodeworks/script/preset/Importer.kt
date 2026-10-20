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
 *  [Card] entries, and the global `network` becomes [Pool].
 *
 *  [faceOverride] (Card only) replaces the card's stored access face when reading
 *  or writing the card's adjacent storage. Carried in from a face-overridden
 *  CardHandle or HandleList. */
internal sealed class ResolvedRef {
    data object Pool : ResolvedRef()
    data class Card(
        val snapshot: CardSnapshot,
        val faceOverride: net.minecraft.core.Direction? = null,
    ) : ResolvedRef()

    /** Subset of [Pool] scoped to one channel's storage cards. Move dispatch
     *  routes through [ChannelFilter.Color] so only the matching cards are
     *  walked at tick time. */
    data class ChannelPool(val color: net.minecraft.world.item.DyeColor) : ResolvedRef()
}

/** Expand a single [CardRef] into zero-or-more [ResolvedRef]s. Supports `*`
 *  wildcards in card aliases (e.g. `"io_*"` matches every card whose alias starts
 *  with `io_`). An alias that doesn't resolve to any card yields an empty list,
 *  the preset silently skips it and retries on the next snapshot change. */
internal fun expandCardRef(snapshot: NetworkSnapshot, ref: CardRef): List<ResolvedRef> = when (ref) {
    is CardRef.Pool -> listOf(ResolvedRef.Pool)
    is CardRef.Channel -> listOf(ResolvedRef.ChannelPool(ref.color))
    is CardRef.Named -> {
        if (!ref.alias.contains('*')) {
            val card = snapshot.findByAlias(ref.alias)
            if (card != null) listOf(ResolvedRef.Card(card, ref.faceOverride)) else emptyList()
        } else {
            val regex = wildcardToRegex(ref.alias)
            snapshot.allCards()
                .filter { regex.matchEntire(it.effectiveAlias) != null }
                .distinctBy { it.effectiveAlias }
                .map { ResolvedRef.Card(it, ref.faceOverride) }
        }
    }
}

/** Convert a glob-style alias (just `*` as wildcard) to a Regex. Every other
 *  character is literal so card aliases with punctuation don't get misinterpreted.
 *  Internal so [damien.nodeworks.script.ScriptEngine.networkCards] can reuse the
 *  same matcher when materialising `network:cards(pattern)` lookups. */
internal fun wildcardToRegex(alias: String): Regex {
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
 * Items and fluids. Each run moves items first and then fluids, each under its own
 * per-run cap. `:filter(...)` and `:except(...)` build the allow and deny lists, see
 * [RuleSet], and a rule applies to both kinds unless it carries a kind sigil.
 */
class ImporterBuilder(
    engine: ScriptEngine,
    internal val sources: List<CardRef>,
) : PresetBuilder<ImporterBuilder>(engine) {

    /** What moves. Empty allow list means everything, so a bare
     *  `importer:from(src):to(dst):start()` moves the entire contents. Narrow it with
     *  `:filter(...)` and cut from it with `:except(...)`. */
    internal val rules = RuleSet()

    /** The run's stack check, built once per run from [rules]. */
    private var stackPred: (net.minecraft.world.item.ItemStack) -> Boolean = { true }
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

    /** Where each card-to-card pair's source scan resumes, so a source with more slots than
     *  one run can get through is still covered, a stretch per run. */
    private val scanCursors = HashMap<String, damien.nodeworks.platform.ScanCursor>()

    private fun cursorKey(source: ResolvedRef.Card, target: ResolvedRef.Card): String =
        "${source.snapshot.effectiveAlias}|${source.faceOverride}>${target.snapshot.effectiveAlias}|${target.faceOverride}"

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

    fun filter(patterns: List<String>): ImporterBuilder {
        rules.filter(patterns)
        return this
    }

    fun except(patterns: List<String>): ImporterBuilder {
        rules.except(patterns)
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
        val livePairs = HashSet<String>()
        for (s in resolvedSources) for (t in resolvedTargets) {
            if (s is ResolvedRef.Card && t is ResolvedRef.Card) livePairs += cursorKey(s, t)
        }
        scanCursors.keys.retainAll(livePairs)
        if (resolvedTargets.isNotEmpty() && rrCursor >= resolvedTargets.size) {
            rrCursor %= resolvedTargets.size
            rrServedToCurrent = 0
        }
    }

    override fun tickOnce() {
        val snapshot = lastSnapshotSeen ?: return
        val level = engine.level
        stackPred = rules.stackPredicate(level.registryAccess())
        // The id check narrows scans before stacks are in hand, the stack check decides.
        val filterPred: (String) -> Boolean = rules.idPredicate(damien.nodeworks.platform.ResourceKind.ITEM)

        if (rules.mayMatch(damien.nodeworks.platform.ResourceKind.ITEM)) {
            when (strategy) {
                DistributionStrategy.FILL -> tickFill(snapshot, level, filterPred)
                DistributionStrategy.ROUND_ROBIN -> tickRoundRobin(snapshot, level, filterPred)
            }
        }
        if (rules.mayMatch(damien.nodeworks.platform.ResourceKind.FLUID)) {
            tickFluids(snapshot, level, rules.idPredicate(damien.nodeworks.platform.ResourceKind.FLUID))
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
     *  [roundRobinStep] allotment, then advance. A target that accepts some
     *  but not all of its allotment (source ran dry mid-fill) holds the
     *  cursor so next tick tops it off. A target that accepts nothing
     *  (full, filter rejects, missing capability) yields the cursor
     *  immediately so a single dead slot doesn't stall the rotation.
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
            // Advance past targets that filled cleanly OR accepted nothing.
            // Partial fills (moved > 0 but allotment short) hold the cursor so
            // the next tick can finish topping the target off when source has more.
            val filled = rrServedToCurrent.toLong() >= stepL
            val rejected = moved == 0L
            if (filled || rejected) {
                rrCursor = (rrCursor + 1) % resolvedTargets.size
                rrServedToCurrent = 0
                ringsRemaining--
            } else {
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
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            remaining -= movePair(snapshot, level, source, target, filterPred, remaining)
        }
        return maxCount - remaining
    }

    /** Move items from [source] to [target], at most [maxCount] and never past this
     *  run's allowance. */
    private fun movePair(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        source: ResolvedRef,
        target: ResolvedRef,
        filterPred: (String) -> Boolean,
        maxCount: Long,
    ): Long = capped(maxCount) { movePairUncapped(snapshot, level, source, target, filterPred, it) }

    /** Dispatches on all four source/target combinations. */
    private fun movePairUncapped(
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
                val srcStorage = CardStorage.forCard(level, source.snapshot, source.faceOverride) ?: return 0L
                when (target) {
                    is ResolvedRef.Card -> {
                        val dest = CardStorage.forCard(level, target.snapshot, target.faceOverride) ?: return 0L
                        // A Storage Card target only takes what its own rules allow, on top of ours.
                        val cardAccept = CardStorage.acceptFilter(level, target.snapshot)
                        val accept: (net.minecraft.world.item.ItemStack) -> Boolean =
                            if (cardAccept == null) stackPred else { stack -> stackPred(stack) && cardAccept(stack) }
                        if (!CardStorage.hasRoom(srcStorage, dest, filterPred, maxCount, accept)) return 0L
                        val cursor = scanCursors.getOrPut(cursorKey(source, target)) { damien.nodeworks.platform.ScanCursor() }
                        PlatformServices.storage.moveItems(srcStorage, dest, filterPred, maxCount, cursor, accept)
                    }
                    is ResolvedRef.Pool -> NetworkStorageHelper.insertItems(
                        level, snapshot, srcStorage, "*",
                        maxCount, engine.routeTable, null, engine.inventoryCache,
                        damien.nodeworks.network.ChannelFilter.All,
                        stackPred = stackPred,
                    )
                    is ResolvedRef.ChannelPool -> NetworkStorageHelper.insertItems(
                        level, snapshot, srcStorage, "*",
                        maxCount, engine.routeTable, null, engine.inventoryCache,
                        damien.nodeworks.network.ChannelFilter.Color(target.color),
                        stackPred = stackPred,
                    )
                }
            }
            is ResolvedRef.Pool -> when (target) {
                // Pool to Pool is a no-op, items would just shuffle between storage cards.
                is ResolvedRef.Pool -> 0L
                is ResolvedRef.Card -> movePoolToCard(snapshot, level, target, filterPred, maxCount, damien.nodeworks.network.ChannelFilter.All)
                is ResolvedRef.ChannelPool -> movePoolBetween(
                    snapshot, level, filterPred, maxCount,
                    sourceChannel = damien.nodeworks.network.ChannelFilter.All,
                    targetChannel = damien.nodeworks.network.ChannelFilter.Color(target.color),
                )
            }
            is ResolvedRef.ChannelPool -> when (target) {
                is ResolvedRef.Pool -> movePoolBetween(
                    snapshot, level, filterPred, maxCount,
                    sourceChannel = damien.nodeworks.network.ChannelFilter.Color(source.color),
                    targetChannel = damien.nodeworks.network.ChannelFilter.All,
                )
                is ResolvedRef.ChannelPool -> {
                    if (source.color == target.color) 0L
                    else movePoolBetween(
                        snapshot, level, filterPred, maxCount,
                        sourceChannel = damien.nodeworks.network.ChannelFilter.Color(source.color),
                        targetChannel = damien.nodeworks.network.ChannelFilter.Color(target.color),
                    )
                }
                is ResolvedRef.Card -> movePoolToCard(
                    snapshot, level, target, filterPred, maxCount,
                    damien.nodeworks.network.ChannelFilter.Color(source.color),
                )
            }
        }
    }

    /** Move items between two channel-scoped (or All-scoped) subsets of network
     *  storage. Walks source-side cards extracting matching items, inserts via
     *  the channel-aware helper so target-side cards filter correctly. */
    private fun movePoolBetween(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        filterPred: (String) -> Boolean,
        maxCount: Long,
        sourceChannel: damien.nodeworks.network.ChannelFilter,
        targetChannel: damien.nodeworks.network.ChannelFilter,
    ): Long {
        var remaining = maxCount
        var totalMoved = 0L
        for (srcCard in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            if (!sourceChannel.matches(srcCard.channel)) continue
            val srcStorage = NetworkStorageHelper.getStorage(level, srcCard) ?: continue
            val moved = NetworkStorageHelper.insertItems(
                level, snapshot, srcStorage, "*",
                remaining, engine.routeTable, null, engine.inventoryCache,
                targetChannel,
                stackPred = stackPred,
            )
            totalMoved += moved
            remaining -= moved
        }
        return totalMoved
    }

    /** Pull from the Network Storage pool into a specific card. Walks Storage Cards
     *  in priority order until [maxCount] is satisfied or the pool runs dry.
     *
     *  Moves per (itemId, hasData) so each transfer can notify the cache via
     *  [NetworkInventoryCache.onExtracted], balancing the [onInserted] that fired
     *  when the item entered the pool. Without this, an importer that pipes the
     *  pool straight back out (`from(network):to("chest")`) leaks ghost entries:
     *  the poll never catches the items in transit, dirtyKeys protects them from
     *  the orphan sweep every tick, and `entries[key].count` grows unbounded. */
    private fun movePoolToCard(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        target: ResolvedRef.Card,
        filterPred: (String) -> Boolean,
        maxCount: Long,
        sourceChannel: damien.nodeworks.network.ChannelFilter = damien.nodeworks.network.ChannelFilter.All,
    ): Long {
        val destStorage = CardStorage.forCard(level, target.snapshot, target.faceOverride) ?: return 0L
        val accept = CardStorage.acceptFilter(level, target.snapshot)
        val cache = engine.inventoryCache
        var remaining = maxCount
        var totalMoved = 0L
        for (poolCard in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            if (!sourceChannel.matches(poolCard.channel)) continue
            val poolStorage = NetworkStorageHelper.getStorage(level, poolCard) ?: continue
            val infos = PlatformServices.storage.findAllItemInfo(poolStorage) { filterPred(it) }
            for (info in infos) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                // Per-variant move so two variants of one itemId aren't conflated.
                val wantHash = damien.nodeworks.script.BufferKey.componentsHash(info.componentsPatch)
                val moved = PlatformServices.storage.moveItemsByStackPredicate(
                    poolStorage, destStorage,
                    { stack ->
                        val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                        sid == info.itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == wantHash &&
                            (accept == null || accept(stack)) && stackPred(stack)
                    },
                    remaining,
                )
                totalMoved += moved
                remaining -= moved
                if (moved > 0L) cache?.onExtracted(info.itemId, info.hasData, moved, info.componentsPatch)
            }
        }
        return totalMoved
    }

    // ---- Fluids ----

    /** Round robin position and the millibuckets served to it, kept apart from the item
     *  ring so the two passes rotate independently. */
    private var rrFluidCursor: Int = 0
    private var rrFluidServed: Long = 0L

    /** The fluid pass runs after the item pass under its own per-run cap. Fill pours
     *  into each target in order, round robin serves [roundRobinStep] millibuckets per
     *  target per turn. */
    private fun tickFluids(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        fluidPred: (String) -> Boolean,
    ) {
        if (resolvedTargets.isEmpty()) return
        when (strategy) {
            DistributionStrategy.FILL -> {
                for (target in resolvedTargets) moveFluidIntoTarget(snapshot, level, target, fluidPred, Long.MAX_VALUE)
            }
            DistributionStrategy.ROUND_ROBIN -> {
                val stepMb = roundRobinStep.toLong()
                var ringsRemaining = resolvedTargets.size
                if (rrFluidCursor >= resolvedTargets.size) {
                    rrFluidCursor = 0
                    rrFluidServed = 0L
                }
                while (ringsRemaining > 0) {
                    val idx = rrFluidCursor % resolvedTargets.size
                    val needed = stepMb - rrFluidServed
                    if (needed <= 0L) {
                        rrFluidCursor = (rrFluidCursor + 1) % resolvedTargets.size
                        rrFluidServed = 0L
                        ringsRemaining--
                        continue
                    }
                    val moved = moveFluidIntoTarget(snapshot, level, resolvedTargets[idx], fluidPred, needed)
                    rrFluidServed += moved
                    if (rrFluidServed >= stepMb || moved == 0L) {
                        rrFluidCursor = (rrFluidCursor + 1) % resolvedTargets.size
                        rrFluidServed = 0L
                        ringsRemaining--
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun moveFluidIntoTarget(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        target: ResolvedRef,
        fluidPred: (String) -> Boolean,
        maxMb: Long,
    ): Long {
        if (maxMb <= 0L) return 0L
        var remaining = maxMb
        for (source in resolvedSources) {
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            remaining -= cappedFluid(remaining) { moveFluidPair(snapshot, level, source, target, fluidPred, it) }
        }
        return maxMb - remaining
    }

    private fun moveFluidPair(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        source: ResolvedRef,
        target: ResolvedRef,
        fluidPred: (String) -> Boolean,
        maxMb: Long,
    ): Long {
        if (maxMb <= 0L) return 0L
        return when (source) {
            is ResolvedRef.Card -> {
                val src = CardStorage.fluidForCard(level, source.snapshot, source.faceOverride) ?: return 0L
                when (target) {
                    is ResolvedRef.Card -> {
                        val dst = CardStorage.fluidForCard(level, target.snapshot, target.faceOverride) ?: return 0L
                        PlatformServices.storage.moveFluid(src, dst, fluidPred, maxMb)
                    }
                    is ResolvedRef.Pool -> moveFluidIntoPool(snapshot, level, src, null, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.All)
                    is ResolvedRef.ChannelPool -> moveFluidIntoPool(snapshot, level, src, null, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.Color(target.color))
                }
            }
            is ResolvedRef.Pool -> when (target) {
                is ResolvedRef.Pool -> 0L
                is ResolvedRef.Card -> moveFluidPoolToCard(snapshot, level, target, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.All)
                is ResolvedRef.ChannelPool -> moveFluidPoolBetween(snapshot, level, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.All, damien.nodeworks.network.ChannelFilter.Color(target.color))
            }
            is ResolvedRef.ChannelPool -> when (target) {
                is ResolvedRef.Pool -> moveFluidPoolBetween(snapshot, level, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.Color(source.color), damien.nodeworks.network.ChannelFilter.All)
                is ResolvedRef.ChannelPool ->
                    if (source.color == target.color) 0L
                    else moveFluidPoolBetween(snapshot, level, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.Color(source.color), damien.nodeworks.network.ChannelFilter.Color(target.color))
                is ResolvedRef.Card -> moveFluidPoolToCard(snapshot, level, target, fluidPred, maxMb, damien.nodeworks.network.ChannelFilter.Color(source.color))
            }
        }
    }

    /** Pours [src] into the pool's tanks on [channel], one fluid at a time so the
     *  terminal cache hears which fluid landed where. [skip] is the card the source
     *  belongs to when it is itself a pool card, so a pool to pool move never pours a
     *  tank into itself. */
    private fun moveFluidIntoPool(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        src: damien.nodeworks.platform.FluidStorageHandle,
        skip: CardSnapshot?,
        fluidPred: (String) -> Boolean,
        maxMb: Long,
        channel: damien.nodeworks.network.ChannelFilter,
    ): Long {
        val cache = engine.inventoryCache
        var remaining = maxMb
        var total = 0L
        for (info in PlatformServices.storage.findAllFluidInfo(src, fluidPred)) {
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            val fluidId = info.fluidId
            var movedThisFluid = 0L
            for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                if (!channel.matches(card.channel)) continue
                if (skip != null && sameBlock(card, skip)) continue
                val dst = NetworkStorageHelper.getFluidStorage(level, card) ?: continue
                val moved = PlatformServices.storage.moveFluid(src, dst, { it == fluidId }, remaining)
                if (moved <= 0L) continue
                cache?.onChannelFluidDelta(card.channel, fluidId, moved)
                movedThisFluid += moved
                remaining -= moved
            }
            if (movedThisFluid > 0L) {
                cache?.onFluidInserted(fluidId, movedThisFluid)
                total += movedThisFluid
            }
        }
        return total
    }

    private fun moveFluidPoolToCard(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        target: ResolvedRef.Card,
        fluidPred: (String) -> Boolean,
        maxMb: Long,
        sourceChannel: damien.nodeworks.network.ChannelFilter,
    ): Long {
        val dst = CardStorage.fluidForCard(level, target.snapshot, target.faceOverride) ?: return 0L
        val cache = engine.inventoryCache
        var remaining = maxMb
        var total = 0L
        for (poolCard in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            if (!sourceChannel.matches(poolCard.channel)) continue
            if (sameBlock(poolCard, target.snapshot)) continue
            val src = NetworkStorageHelper.getFluidStorage(level, poolCard) ?: continue
            for (info in PlatformServices.storage.findAllFluidInfo(src, fluidPred)) {
                if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
                val fluidId = info.fluidId
                val moved = PlatformServices.storage.moveFluid(src, dst, { it == fluidId }, remaining)
                if (moved <= 0L) continue
                cache?.onFluidExtracted(fluidId, moved)
                cache?.onChannelFluidDelta(poolCard.channel, fluidId, -moved)
                total += moved
                remaining -= moved
            }
        }
        return total
    }

    private fun moveFluidPoolBetween(
        snapshot: NetworkSnapshot,
        level: net.minecraft.server.level.ServerLevel,
        fluidPred: (String) -> Boolean,
        maxMb: Long,
        sourceChannel: damien.nodeworks.network.ChannelFilter,
        targetChannel: damien.nodeworks.network.ChannelFilter,
    ): Long {
        var remaining = maxMb
        var total = 0L
        for (srcCard in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (remaining <= 0L || damien.nodeworks.platform.MoveDeadline.expired()) break
            if (!sourceChannel.matches(srcCard.channel)) continue
            val src = NetworkStorageHelper.getFluidStorage(level, srcCard) ?: continue
            val moved = moveFluidIntoPool(snapshot, level, src, srcCard, fluidPred, remaining, targetChannel)
            total += moved
            remaining -= moved
        }
        return total
    }

    /** Two cards on the same block see the same tank. */
    private fun sameBlock(a: CardSnapshot, b: CardSnapshot): Boolean {
        val pa = (a.capability as? damien.nodeworks.card.StorageSideCapability)?.adjacentPos
        val pb = (b.capability as? damien.nodeworks.card.StorageSideCapability)?.adjacentPos
            ?: (b.capability as? damien.nodeworks.card.IOSideCapability)?.adjacentPos
        return pa != null && pa == pb
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
        t.set("filter", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                selfRef.filter(RuleSet.rulesFromArgs(args, 2))
                return selfRef.toLuaTable()
            }
        })
        t.set("except", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                selfRef.except(RuleSet.rulesFromArgs(args, 2))
                return selfRef.toLuaTable()
            }
        })
    }
}
