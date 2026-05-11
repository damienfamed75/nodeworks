package damien.nodeworks.script.cpu

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.script.BufferSource
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.CraftingHelper
import damien.nodeworks.script.NetworkStorageHelper
import damien.nodeworks.script.ProcessingJob
import damien.nodeworks.script.ResumeScheduler
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

/**
 * Block-side equivalent of a Lua Processing Handler. Driven entirely by the BE's
 * channel configuration:
 *
 *  - **Inputs:** for each declared input slot, look up its per-itemId channel on
 *    the BE, find storage cards on the **front** (micro) network with a matching
 *    channel, and atomically move the slot's items from the CPU buffer into
 *    those cards (mirroring `items.slotName:insert(card)` from the Lua side).
 *  - **Outputs:** find storage cards on the front network with the BE's
 *    [ProcessingHandlerBlockEntity.outputChannel], wrap them as
 *    [CardHandle.StorageGetter]s, and call [ProcessingJob.startPoll] to register
 *    the same async pull machinery the Lua path uses.
 *
 * Sharing across multiple PHandlers on a merged micro-network is naturally
 * supported: each handler walks the front face independently and the BFS gives
 * the same network to all handlers whose front faces touch. Channels disambiguate
 * traffic.
 */
object BlockProcessingHandler {

    private val logger = LoggerFactory.getLogger("nodeworks-block-handler")

    /** Outcome of a one-shot block-handler invocation. The CPU executor maps
     *  these onto [damien.nodeworks.script.cpu.CraftScheduler.OpResult] in the
     *  same shape it already uses for the Lua path. */
    sealed class InvokeResult {
        /** Inputs routed, output pull registered. The executor stashes
         *  [pending] + [job] for the existing poll loop. */
        data class InProgress(
            val pending: CraftingHelper.PendingHandlerJob,
            val job: ProcessingJob,
        ) : InvokeResult()
        /** Output pull completed during invocation (rare, the recipe's
         *  outputs were sitting in the cards already). */
        data class CompletedSync(val pending: CraftingHelper.PendingHandlerJob) : InvokeResult()
        /** Routing failed for a recoverable reason (no capacity, no matching
         *  cards). Executor treats this like the Lua "destination unavailable"
         *  retry path. */
        data class Retry(val reason: String) : InvokeResult()
        /** Routing failed for a permanent reason on this op (no output cards
         *  configured at all, partial input commitment that can't be unwound).
         *  Executor fails the op. */
        data class Failed(val reason: String) : InvokeResult()
    }

    /**
     * Run one handler invocation: route this batch's inputs from the CPU
     * buffer into the front micro-network's channel-filtered cards, then
     * register the output pull on the front micro-network's output cards.
     *
     * Atomicity contract: if **any** input slot can't be routed, **no** items
     * leave the buffer (each per-slot move is itself atomic, and the function
     * unwinds successful moves on a later failure by returning items to the
     * buffer). Mirrors the Lua handler's implicit "all or nothing" guarantee
     * the executor's conservation check enforces.
     */
    fun invoke(
        cpu: CraftingCoreBlockEntity,
        level: ServerLevel,
        api: ProcessingStorageBlockEntity.ProcessingApiInfo,
        handlerBE: ProcessingHandlerBlockEntity,
        perBatchInputs: List<Pair<String, Long>>,
        bulkOutputOverride: List<Pair<String, Int>>?,
        opId: Int,
    ): InvokeResult {
        // The cpu and the handler may live on different networks (and even
        // different dimensions when reached through a Broadcast / Receiver
        // antenna pair). [level] is the cpu's level, used for [ProcessingJob]'s
        // stale-recovery network discovery; everything that walks the
        // handler's micro-network and its storage cards has to use the
        // HANDLER's level instead.
        val handlerLevel = handlerBE.level as? ServerLevel ?: return InvokeResult.Failed(
            "handler block at ${handlerBE.blockPos} has no server level"
        )

        // Discover the FRONT (micro) network from the handler's front-face
        // neighbor. Phase 1's per-side BFS routes the walk into the micro
        // side because the handler's own [adjacencyFaceAllowed] gates entry.
        val frontPos = handlerBE.blockPos.relative(handlerBE.frontFace)
        val microSnapshot = NetworkDiscovery.discoverNetwork(handlerLevel, frontPos)

        // Output cards FIRST so a recipe with no destination fails fast,
        // before we move any items.
        val outputChannelFilter = ChannelFilter.Color(handlerBE.outputChannel)
        val outputCards = NetworkStorageHelper.getStorageCards(microSnapshot)
            .filter { outputChannelFilter.matches(it.channel) }
        if (outputCards.isEmpty()) {
            return InvokeResult.Failed(
                "no output storage cards on micro-network with channel ${handlerBE.outputChannel.name.lowercase()}"
            )
        }

        // Route inputs. Each slot is independently atomic; we accumulate
        // successful moves so we can roll back if a later slot fails to route.
        // Per-slot data is (itemId, batchCount).
        val rolledBack = mutableListOf<Pair<String, Long>>()
        for ((itemId, batchCount) in perBatchInputs) {
            if (batchCount <= 0L) continue
            val color = handlerBE.getInputChannel(itemId)
            val moveOk = routeInputAtomic(handlerLevel, microSnapshot, cpu, handlerBE, itemId, batchCount, ChannelFilter.Color(color))
            if (moveOk) {
                rolledBack += itemId to batchCount
                continue
            }
            // Couldn't route this input. Unwind anything already moved so the
            // buffer is bit-equal to its pre-invocation state, then retry.
            unwindRoutedInputs(handlerLevel, microSnapshot, cpu, handlerBE, rolledBack)
            return InvokeResult.Retry(
                "input ${itemId} (channel ${color.name.lowercase()}) couldn't route to a free destination"
            )
        }

        // All inputs routed. Set up the pull on output channel.
        val pending = CraftingHelper.PendingHandlerJob()
        if (api.serial) {
            CraftingHelper.addActiveSerialJob(api.name)
            pending.onCompleteCallback = { CraftingHelper.removeActiveSerialJob(api.name) }
        }
        val scheduler = ResumeScheduler.scheduler
        val job = ProcessingJob(api, cpu, level, scheduler, pending, bulkOutputOverride, opId)
        val getters = outputCards.map { card ->
            CardHandle.StorageGetter { NetworkStorageHelper.getStorage(handlerLevel, card) }
        }
        job.startPoll(getters)

        // startPoll completes synchronously when outputs were sitting in the
        // cards already; the pending result reflects that.
        if (pending.isComplete) return InvokeResult.CompletedSync(pending)
        return InvokeResult.InProgress(pending, job)
    }

    /**
     * Atomic single-item route: pull [count] items of [itemId] from the CPU
     * buffer and insert them across the [snapshot]'s channel-filtered storage
     * cards. Returns true only when the full count fit; on false the buffer
     * is bit-equal to its pre-call state (no partial commit).
     *
     * Cards are walked in priority order with each card's filter
     * ([SideCapability.acceptsItem]) honoured, matching the routing model
     * `network:insert` uses. A high-priority filtered card (e.g. "bamboo
     * only") therefore takes its matching ingredient before a generic
     * unfiltered card on the same channel does.
     */
    private fun routeInputAtomic(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cpu: CraftingCoreBlockEntity,
        @Suppress("UNUSED_PARAMETER") handlerBE: ProcessingHandlerBlockEntity,
        itemId: String,
        count: Long,
        channel: ChannelFilter,
    ): Boolean {
        val id = Identifier.tryParse(itemId) ?: return false
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return false
        val hasData = false  // declared input slots never carry components

        // Filter cards: channel match AND per-card filter accepts this item.
        // Storage cards are already sorted by priority descending in the
        // snapshot, so walking in order naturally targets filtered + high
        // priority cards first.
        val cards = NetworkStorageHelper.getStorageCards(snapshot).filter { card ->
            if (!channel.matches(card.channel)) return@filter false
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                ?: return@filter true
            cap.acceptsItem(itemId, hasData)
        }
        if (cards.isEmpty()) return false

        // Capacity probe in priority order. Sum simulateInsertItem across
        // qualifying cards until either count is satisfied or we exhaust
        // the list.
        var capacity = 0L
        for (card in cards) {
            if (capacity >= count) break
            val dest = NetworkStorageHelper.getStorage(level, card) ?: continue
            capacity += try {
                PlatformServices.storage.simulateInsertItem(dest, item, count - capacity)
            } catch (_: Exception) { 0L }
        }
        if (capacity < count) return false

        // Capacity check passed. Extract from buffer; if buffer somehow has
        // fewer items than declared, return what we did extract and bail.
        val bufSrc = BufferSource(cpu, itemId, count)
        val extracted = bufSrc.extract(count)
        if (extracted < count) {
            bufSrc.returnUnused(extracted)
            return false
        }

        // Commit in priority order. Each card takes whatever its remaining
        // free space allows (per-card sim then real insert). We accumulate
        // committed (card, amount) pairs so we can reverse them if a later
        // card's tryInsertAll disagrees with its simulate - otherwise items
        // already inserted into earlier cards would be silently stranded
        // there when the outer caller treats this slot's `false` return as
        // "nothing happened" and skips it in [unwindRoutedInputs].
        var remaining = extracted
        val committed = ArrayList<Pair<CardSnapshot, Long>>(cards.size)
        for (card in cards) {
            if (remaining <= 0L) break
            val dest = NetworkStorageHelper.getStorage(level, card) ?: continue
            val sim = try {
                PlatformServices.storage.simulateInsertItem(dest, item, remaining)
            } catch (_: Exception) { 0L }
            if (sim <= 0L) continue
            val ok = try {
                PlatformServices.storage.tryInsertAll(dest, item, sim)
            } catch (_: Exception) { false }
            if (ok) {
                committed += card to sim
                remaining -= sim
            }
        }
        if (remaining > 0L) {
            // Sim claimed more capacity than tryInsertAll actually accepted -
            // shouldn't happen under a well-behaved single-threaded backend
            // but is the only edge case that could violate the "buffer
            // bit-equal on failure" contract this function advertises.
            // Pull back the items we did place and route them home so the
            // slot's net effect on buffer + cards is zero.
            for ((card, amount) in committed) {
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                val pulled = try {
                    PlatformServices.storage.extractItems(storage, { it == itemId }, amount)
                } catch (_: Exception) { 0L }
                if (pulled > 0L) cpu.addToBuffer(itemId, pulled)
                if (pulled < amount) {
                    logger.warn(
                        "Block handler partial-commit rollback shortfall for {}: {} of {} items recovered from card",
                        itemId, pulled, amount,
                    )
                }
            }
            bufSrc.returnUnused(remaining)
            return false
        }
        return true
    }

    /**
     * Reverse of [routeInputAtomic]: pull each previously-moved input back
     * out of the network cards and return it to the buffer. Used when a
     * later input slot fails to route, so the invocation as a whole leaves
     * the buffer untouched and the executor's conservation check passes.
     *
     * Best-effort: if a destination has been mutated externally between the
     * insert and the rollback (unlikely under single-threaded server tick),
     * we recover what we can and log the shortfall.
     */
    private fun unwindRoutedInputs(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cpu: CraftingCoreBlockEntity,
        handlerBE: ProcessingHandlerBlockEntity,
        moved: List<Pair<String, Long>>,
    ) {
        val allCards = NetworkStorageHelper.getStorageCards(snapshot)
        for ((itemId, count) in moved) {
            // Pull only from cards on the channel the forward route used. Items
            // were JUST placed there a few microseconds ago; scanning the full
            // network would let same-tick mutations from other devices steal
            // items we're trying to recover.
            val channel = ChannelFilter.Color(handlerBE.getInputChannel(itemId))
            var stillNeeded = count
            for (card in allCards) {
                if (stillNeeded <= 0L) break
                if (!channel.matches(card.channel)) continue
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                val pulled = try {
                    PlatformServices.storage.extractItems(storage, { it == itemId }, stillNeeded)
                } catch (_: Exception) { 0L }
                if (pulled > 0L) {
                    cpu.addToBuffer(itemId, pulled)
                    stillNeeded -= pulled
                }
            }
            if (stillNeeded > 0L) {
                logger.warn(
                    "Block handler rollback shortfall for {}: {} of {} items lost in transit",
                    itemId, stillNeeded, count,
                )
            }
        }
    }
}

