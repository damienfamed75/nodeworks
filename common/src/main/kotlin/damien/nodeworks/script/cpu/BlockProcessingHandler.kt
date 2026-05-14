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
import net.minecraft.world.item.ItemStack
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
        bulkOutputOverride: List<Pair<damien.nodeworks.script.RecipeIngredient, Long>>?,
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
        // neighbor. The per-side BFS routes the walk into the micro
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
        // Per-slot data is (itemId, batchCount) but we zip with api.inputs to
        // recover the component-bearing ingredient stack for buffer keying
        // and destination insert. Without that pairing, a recipe that wants
        // Potion of Strength would route from the generic potion bucket and
        // hand the handler bare uncraftable potions.
        val rolledBack = mutableListOf<Pair<damien.nodeworks.script.BufferKey.Key, Long>>()
        for ((idx, slotData) in perBatchInputs.withIndex()) {
            val (itemId, batchCount) = slotData
            if (batchCount <= 0L) continue
            val ingredient = api.inputs.getOrNull(idx)
            // Look up the channel by full variant key so a recipe that takes
            // Strength + Fire Resistance potion inputs can route each to its
            // own channel. Falls back to the itemId-only lookup for legacy
            // recipes that don't have variant-bearing inputs.
            val bufferKey = ingredient?.bufferKey() ?: damien.nodeworks.script.BufferKey.Key(itemId, "")
            val color = handlerBE.getInputChannel(bufferKey)
            val moveOk = routeInputAtomic(
                handlerLevel, microSnapshot, cpu, handlerBE,
                itemId, ingredient, batchCount, ChannelFilter.Color(color),
            )
            if (moveOk) {
                rolledBack += bufferKey to batchCount
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
        // Output cards' (pos, face) coords double as the poll's resume
        // targets so a reload mid-process restarts the poll instead of
        // re-invoking the handler against an already-drained buffer.
        val pullTargets = outputCards.mapNotNull { card ->
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                ?: return@mapNotNull null
            cap.adjacentPos to cap.defaultFace
        }
        job.startPoll(getters, pullTargets)

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
        /** Recipe ingredient for this slot. Carries the full ItemStack
         *  including components so a Potion-of-Strength recipe slot pulls
         *  from the right buffer bucket and inserts the right variant into
         *  the destination card. Null when the caller doesn't have the
         *  ingredient (legacy path, treated as plain item). */
        ingredient: damien.nodeworks.script.RecipeIngredient?,
        count: Long,
        channel: ChannelFilter,
    ): Boolean {
        val id = Identifier.tryParse(itemId) ?: return false
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return false
        val bufferKey = ingredient?.bufferKey() ?: damien.nodeworks.script.BufferKey.Key(itemId, "")
        val hasData = !bufferKey.isPlain

        // Filter cards: channel match AND per-card filter accepts this item.
        // Storage cards are already sorted by priority descending in the
        // snapshot, so walking in order naturally targets filtered + high
        // priority cards first.
        val registries = level.registryAccess()
        val componentsPatch = ingredient?.stack?.componentsPatch ?: net.minecraft.core.component.DataComponentPatch.EMPTY
        val cards = NetworkStorageHelper.getStorageCards(snapshot).filter { card ->
            if (!channel.matches(card.channel)) return@filter false
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                ?: return@filter true
            cap.acceptsItem(itemId, componentsPatch, registries)
        }
        if (cards.isEmpty()) return false

        // Capacity probe in priority order. Sum simulateInsertItem across
        // qualifying cards until either count is satisfied or we exhaust
        // the list. The simulate primitive is item-only (no components),
        // which can over-count capacity for slots that filter by component,
        // but the worst case is an extra commit-then-rollback pass below.
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
        // Buffer-key match guarantees we pull the recipe's specific variant
        // (Strength Potion bucket) rather than a generic potion.
        val bufSrc = BufferSource(cpu, bufferKey, count)
        // Snapshot the template BEFORE extracting. If this slot drains the
        // bucket dry, BufferState wipes the bucket and the later template
        // read returns EMPTY → destination card receives a bare uncraftable
        // potion. Recipe ingredient stack is the fallback when the buffer
        // somehow has no template (defensive only).
        val capturedTemplate = bufSrc.template.let { tmpl ->
            when {
                !tmpl.isEmpty -> tmpl.copy()
                ingredient != null -> ingredient.stack.copyWithCount(1)
                else -> ItemStack(item)
            }
        }
        val extracted = bufSrc.extract(count)
        if (extracted < count) {
            bufSrc.returnUnused(extracted)
            return false
        }
        val variantStack = capturedTemplate

        // Commit in priority order. Each card takes whatever its remaining
        // free space allows (per-card sim then real insert). We accumulate
        // committed (card, amount) pairs so we can reverse them if a later
        // card's tryInsertAll disagrees with its simulate - otherwise items
        // already inserted into earlier cards would be silently stranded
        // there when the outer caller treats this slot's `false` return as
        // "nothing happened" and skips it in [unwindRoutedInputs].
        var remaining = extracted
        val maxStack = item.getDefaultMaxStackSize().toLong()
        // Variant hash so the rollback pulls back the exact same variant.
        val variantHash = damien.nodeworks.script.BufferKey.componentsHash(variantStack)
        val committed = ArrayList<Pair<CardSnapshot, Long>>(cards.size)
        for (card in cards) {
            if (remaining <= 0L) break
            val dest = NetworkStorageHelper.getStorage(level, card) ?: continue
            var sim = try {
                PlatformServices.storage.simulateInsertItem(dest, item, remaining)
            } catch (_: Exception) { 0L }
            if (sim <= 0L) continue
            // Drain this card's full simulated capacity before moving on, so
            // a multi-stack card doesn't leave a stack behind and trip the
            // rollback path despite having room.
            var cardInserted = 0L
            while (sim > 0L && remaining > 0L) {
                val batch = minOf(sim, remaining, maxStack)
                val toInsert = variantStack.copyWithCount(batch.toInt())
                val inserted = try {
                    PlatformServices.storage.insertItemStack(dest, toInsert).toLong()
                } catch (_: Exception) { 0L }
                if (inserted <= 0L) break  // card stopped accepting despite sim
                cardInserted += inserted
                remaining -= inserted
                sim -= inserted
            }
            if (cardInserted > 0L) committed += card to cardInserted
        }
        if (remaining > 0L) {
            // Real insert accepted less than sim claimed (rare). Pull placed
            // items back so the slot's net effect on buffer + cards is zero.
            for ((card, amount) in committed) {
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                // Variant-aware rollback so a sibling variant in the card
                // can't be grabbed instead of the one we inserted.
                val pulledStacks = try {
                    PlatformServices.storage.extractStacksByPredicate(
                        storage,
                        { st ->
                            val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.item)?.toString()
                            sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(st) == variantHash
                        },
                        amount,
                    )
                } catch (_: Exception) { emptyList() }
                var totalPulled = 0L
                for (stack in pulledStacks) {
                    if (stack.isEmpty) continue
                    val n = stack.count.toLong()
                    cpu.addToBuffer(stack, n)
                    totalPulled += n
                }
                if (totalPulled < amount) {
                    logger.warn(
                        "Block handler partial-commit rollback shortfall for {}: {} of {} items recovered from card",
                        itemId, totalPulled, amount,
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
        moved: List<Pair<damien.nodeworks.script.BufferKey.Key, Long>>,
    ) {
        val allCards = NetworkStorageHelper.getStorageCards(snapshot)
        for ((bufferKey, count) in moved) {
            // Pull only from cards on the channel the forward route used. Items
            // were JUST placed there a few microseconds ago; scanning the full
            // network would let same-tick mutations from other devices steal
            // items we're trying to recover.
            val channel = ChannelFilter.Color(handlerBE.getInputChannel(bufferKey))
            val itemId = bufferKey.itemId
            val wantHash = bufferKey.componentsHash
            var stillNeeded = count
            for (card in allCards) {
                if (stillNeeded <= 0L) break
                if (!channel.matches(card.channel)) continue
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                // Variant-aware unwind: narrow to the specific (itemId, hash)
                // bucket so the rollback grabs the exact potion / dyed armor
                // we just routed out, not a fresh swap that drifted in.
                val pulledStacks = try {
                    PlatformServices.storage.extractStacksByPredicate(storage, { stack ->
                        val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                        sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == wantHash
                    }, stillNeeded)
                } catch (_: Exception) { emptyList() }
                for (stack in pulledStacks) {
                    if (stack.isEmpty) continue
                    val n = stack.count.toLong()
                    cpu.addToBuffer(stack, n)
                    stillNeeded -= n
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

