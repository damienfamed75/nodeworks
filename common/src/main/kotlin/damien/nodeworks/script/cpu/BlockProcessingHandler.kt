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
        /** The item each slot moves, the planner's substitutions or the recipe's own. */
        inputs: List<damien.nodeworks.script.RecipeIngredient> = api.inputs,
        /** Per slot, the recipe's own input key, which is what the handler's channel map
         *  is bound to, so a substituted slot still routes to its authored channel. */
        channelKeys: List<damien.nodeworks.script.BufferKey.Key> = inputs.map { it.bufferKey() },
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
        val rolledBack = mutableListOf<Moved>()
        for ((idx, slotData) in perBatchInputs.withIndex()) {
            val (itemId, batchCount) = slotData
            if (batchCount <= 0L) continue
            val ingredient = inputs.getOrNull(idx)
            // Look up the channel by full variant key so a recipe that takes
            // Strength + Fire Resistance potion inputs can route each to its
            // own channel. Falls back to the itemId-only lookup for legacy
            // recipes that don't have variant-bearing inputs.
            val bufferKey = ingredient?.bufferKey() ?: damien.nodeworks.script.BufferKey.Key(itemId, "")
            val channelKey = channelKeys.getOrNull(idx) ?: bufferKey
            val color = handlerBE.getInputChannel(channelKey)
            val moveOk = routeInputAtomic(
                handlerLevel, microSnapshot, cpu, handlerBE,
                itemId, ingredient, batchCount, ChannelFilter.Color(color),
            )
            if (moveOk) {
                rolledBack += Moved(bufferKey, channelKey, batchCount)
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
        val cards = inputCards(level, snapshot, itemId, ingredient, channel)
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

    /** Cards on [channel] whose filter accepts this input, in priority order. Shared by
     *  [routeInputAtomic] and [fittingBatches] so the probe and the route agree. */
    private fun inputCards(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        itemId: String,
        ingredient: damien.nodeworks.script.RecipeIngredient?,
        channel: ChannelFilter,
    ): List<CardSnapshot> {
        val registries = level.registryAccess()
        val componentsPatch = ingredient?.stack?.componentsPatch ?: net.minecraft.core.component.DataComponentPatch.EMPTY
        return NetworkStorageHelper.getStorageCards(snapshot).filter { card ->
            if (!channel.matches(card.channel)) return@filter false
            val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                ?: return@filter true
            cap.acceptsItem(itemId, componentsPatch, registries)
        }
    }

    /** Most whole batches of [api], up to [maxBatches], whose inputs the handler's input
     *  cards have room for right now, 0 when not even one fits. Simulate only, nothing
     *  moves. Inputs sharing one machine slot are checked separately, so this can read
     *  high, in which case the real route still refuses cleanly. Without output cards it
     *  returns [maxBatches] so [invoke] reports that before moving anything. */
    fun fittingBatches(
        api: ProcessingStorageBlockEntity.ProcessingApiInfo,
        handlerBE: ProcessingHandlerBlockEntity,
        maxBatches: Long,
        inputs: List<damien.nodeworks.script.RecipeIngredient> = api.inputs,
        channelKeys: List<damien.nodeworks.script.BufferKey.Key> = inputs.map { it.bufferKey() },
    ): Long {
        val handlerLevel = handlerBE.level as? ServerLevel ?: return 0L
        val snapshot = NetworkDiscovery.discoverNetwork(handlerLevel, handlerBE.blockPos.relative(handlerBE.frontFace))
        val outputChannel = ChannelFilter.Color(handlerBE.outputChannel)
        if (NetworkStorageHelper.getStorageCards(snapshot).none { outputChannel.matches(it.channel) }) return maxBatches
        // Slots sending one item to one channel share its capacity, so they are summed per
        // batch. The same item bound for two channels is checked on each.
        val perBatch = LinkedHashMap<Pair<damien.nodeworks.script.BufferKey.Key, damien.nodeworks.script.BufferKey.Key>, Pair<damien.nodeworks.script.RecipeIngredient, Long>>()
        for ((idx, ingr) in inputs.withIndex()) {
            val key = ingr.bufferKey()
            val group = key to (channelKeys.getOrNull(idx) ?: key)
            val prev = perBatch[group]
            perBatch[group] = ingr to ((prev?.second ?: 0L) + ingr.count.toLong())
        }
        var best = maxBatches
        for ((group, entry) in perBatch) {
            val (key, channelKey) = group
            val (ingr, count) = entry
            if (count <= 0L) continue
            val item = ingr.stack.item
            val channel = ChannelFilter.Color(handlerBE.getInputChannel(channelKey))
            val need = count * best
            var capacity = 0L
            for (card in inputCards(handlerLevel, snapshot, key.itemId, ingr, channel)) {
                if (capacity >= need) break
                val dest = NetworkStorageHelper.getStorage(handlerLevel, card) ?: continue
                capacity += try {
                    PlatformServices.storage.simulateInsertItem(dest, item, need - capacity)
                } catch (_: Exception) { 0L }
            }
            best = minOf(best, capacity / count)
            if (best <= 0L) return 0L
        }
        return best
    }

    /** One routed slot: what moved, the authored key its channel is bound to, how many. */
    private data class Moved(
        val bufferKey: damien.nodeworks.script.BufferKey.Key,
        val channelKey: damien.nodeworks.script.BufferKey.Key,
        val count: Long,
    )

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
        moved: List<Moved>,
    ) {
        val allCards = NetworkStorageHelper.getStorageCards(snapshot)
        for ((bufferKey, channelKey, count) in moved) {
            // Pull only from cards on the channel the forward route used. Items
            // were JUST placed there a few microseconds ago; scanning the full
            // network would let same-tick mutations from other devices steal
            // items we're trying to recover.
            val channel = ChannelFilter.Color(handlerBE.getInputChannel(channelKey))
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

