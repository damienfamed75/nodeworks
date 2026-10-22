package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * Platform service locator, set by the loader-specific module at init time.
 */
object PlatformServices {
    lateinit var storage: StorageService
    lateinit var menu: MenuService
    lateinit var blockEntity: BlockEntityService
    lateinit var modState: ModStateService
    lateinit var clientNetworking: ClientNetworkingService
    lateinit var serverNetworking: ServerNetworkingService
    lateinit var clientEvents: ClientEventService

    /** FakePlayer factory + permission gate for script-driven block mutations. Defaults
     *  to a no-op so unit tests and pre-init paths don't crash, the loader replaces
     *  this at mod init. */
    var fakePlayer: FakePlayerService = NoopFakePlayerService

    /** Loader hooks around vanilla crafting. */
    var crafting: CraftingHookService = CraftingHookService.Noop

    /** Set by the loader-specific client init. Falls back to a plain gray square if
     *  the loader didn't register a renderer (e.g. dedicated server, never touched). */
    var fluidRenderer: FluidSpriteRenderer = FluidSpriteRenderer.Fallback

    /** Opens a reference into the in-game guidebook. Refs look like
     *  `namespace:path#fragment`, same shape `guideme.PageAnchor.parse` accepts.
     *  Default is a no-op so loader-less contexts (unit tests) don't crash, neoforge
     *  sets a GuideME-backed implementation at client init. */
    var guidebook: GuidebookService = GuidebookService.Noop

    /** Reports whether the "open docs on hover" key is currently held down, polled
     *  via GLFW so it sees through focus routing (typing in a text field doesn't hide
     *  the held state from us). Used by the Scripting Terminal's editor to drive the
     *  Hold-G progress bar. Default always returns false so unconfigured loaders just
     *  never show a hold-progress bar. */
    var openDocsKeyHeld: () -> Boolean = { false }

    var coolantRating: (BlockState) -> damien.nodeworks.script.cpu.CoolantRating? = { null }
}

/**
 * Abstracts "open the guide at this ref" so code in `:common` can drive navigation
 * without importing GuideME directly (it's a neoforge-only dep). The ref format is
 * whatever the impl understands, for the GuideME-backed impl, `namespace:path` with an
 * optional `#fragment`.
 */
interface GuidebookService {
    fun open(ref: String)

    companion object Noop : GuidebookService {
        override fun open(ref: String) {}
    }
}

/**
 * Draws a fluid's still texture at a given GUI position. Loader-specific because
 * 26.1 moved fluid client assets onto vanilla's `ModelManager.fluidStateModelSet`
 * (→ `FluidModel.stillMaterial().sprite()`), which the common module can reach via
 * Minecraft APIs but historically lived behind NeoForge's `IClientFluidTypeExtensions`,
 * keeping the entry point behind a platform service leaves room for loader-specific
 * fallbacks and Fabric's eventual Transfer-API equivalent.
 */
interface FluidSpriteRenderer {
    fun render(graphics: net.minecraft.client.gui.GuiGraphicsExtractor, fluidId: String, x: Int, y: Int, size: Int)

    companion object Fallback : FluidSpriteRenderer {
        override fun render(graphics: net.minecraft.client.gui.GuiGraphicsExtractor, fluidId: String, x: Int, y: Int, size: Int) {
            // Gray placeholder, the NeoForge impl replaces this at client init.
            graphics.fill(x, y, x + size, y + size, 0xFF808080.toInt())
        }
    }
}

/**
 * Abstracts client-side packet sending.
 */
interface ClientNetworkingService {
    fun sendToServer(payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload)
}

/** Abstracts server-side packet sending. Used by `:common` code that pushes
 *  batched updates to clients without going through per-BE NBT sync. */
interface ServerNetworkingService {
    /** Broadcast [payload] to every player in [level]'s dimension. */
    fun sendToPlayersInDimension(
        level: ServerLevel,
        payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload,
    )

    /** Send [payload] to a single player. Used for menu-scoped streams (e.g.
     *  Diagnostic Tool topology chunks) that don't fan out to other players. */
    fun sendToPlayer(
        player: ServerPlayer,
        payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload,
    )

    /** Send [payload] once to every player who can see any of [positions]. A block
     *  change only matters to clients that render it, so a network spanning a world
     *  need not tell players a continent away. */
    fun sendToPlayersTracking(
        level: ServerLevel,
        positions: Collection<net.minecraft.core.BlockPos>,
        payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload,
    ) = sendToPlayersInDimension(level, payload)
}

/**
 * Abstracts client-side event registration (render events, etc.).
 */
interface ClientEventService {
    fun onWorldRender(handler: (poseStack: com.mojang.blaze3d.vertex.PoseStack?, consumers: net.minecraft.client.renderer.MultiBufferSource?, camera: net.minecraft.world.phys.Vec3) -> Unit)
}

/**
 * Discriminator for the two resource domains a card can bridge, items vs. fluids.
 * Counts are interpreted per-kind: 1-per-unit for items, 1-per-millibucket for fluids.
 */
enum class ResourceKind { ITEM, FLUID }

/**
 * Abstracts storage access (Fabric Transfer API vs NeoForge IItemHandler / IFluidHandler).
 * Both item and fluid capabilities are probed via the same service, callers choose by the
 * resource kind of the query (filter prefix / handle kind).
 */
private val STORAGE_LOGGER = org.slf4j.LoggerFactory.getLogger("nodeworks/storage")

/** Hooks around vanilla crafting that common code cannot reach on its own. */
interface CraftingHookService {
    /** Runs [block] with [player] known to the loader as the crafting player, for mods
     *  that read it while working out crafting remainders. */
    fun <T> withCraftingPlayer(player: net.minecraft.world.entity.player.Player, block: () -> T): T = block()

    object Noop : CraftingHookService
}

interface StorageService {
    /** Get an item storage handle for a block at [pos] accessed from [face]. Returns null if not available. */
    fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle?

    /** Get a fluid storage handle for a block at [pos] accessed from [face]. Returns null if not available or unsupported on this platform. */
    fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? = null

    /** Move items between storages. Returns number moved. With a [cursor], a slot-based
     *  source is scanned from where the last call stopped and wraps at the end, so a huge
     *  source is covered over several calls instead of restarting at its first slot. */
    fun moveItems(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor? = null,
        /** Extra per-stack check on top of [filter], for a destination with its own rules
         *  such as a Storage Card. Sees the real stack, components included. */
        accept: ((ItemStack) -> Boolean)? = null,
        /** Told each stack that landed and how much of it, so a caller can keep an
         *  inventory cache current without rescanning anything. */
        onMoved: ((ItemStack, Long) -> Unit)? = null,
    ): Long

    /** Move items with data-aware filter. Filter receives (itemId, hasData). */
    fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        // Default: ignore hasData, delegate to string-only version
        return moveItems(source, dest, { filter(it, false) }, maxCount)
    }

    /** Move items with a full-stack filter. The [filter] sees each candidate
     *  slot's [ItemStack], so callers can match on component-bearing identity.
     *  Use this instead of [moveItemsVariant] when routing a specific variant:
     *  the `(itemId, hasData)` predicate there can't tell a Strength Potion
     *  from a Healing Potion.
     *
     *  Loaders should override with a slot-walking variant; the default
     *  extract-then-insert path re-inserts rejects back into [source]. */
    fun moveItemsByStackPredicate(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
    ): Long {
        if (maxCount <= 0L) return 0L
        val extracted = extractStacksByPredicate(source, filter, maxCount)
        var moved = 0L
        for (stack in extracted) {
            if (stack.isEmpty) continue
            val inserted = insertItemStack(dest, stack)
            moved += inserted
            // Keep the move loss-free: return what dest rejected to source.
            if (inserted < stack.count) {
                insertItemStack(source, stack.copyWithCount(stack.count - inserted))
            }
        }
        return moved
    }

    /** Count items matching filter in a storage. */
    fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long

    /** Find the first item ID in storage matching the filter. Returns null if none found. */
    fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String?

    /** Find the first item in storage matching the filter, with full metadata. */
    fun findFirstItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): ItemInfo?

    /** Find ALL unique item types in storage matching the filter, with full metadata. */
    fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo>

    /** Slots a ranged scan can address, or -1 when [storage] isn't slot-addressable
     *  (an ME network) and can only be scanned whole via [findAllItemInfo]. */
    fun slotCount(storage: ItemStorageHandle): Int = -1

    /** [findAllItemInfo] over slots `[from, until)`, [until] clamped to the current
     *  slot count. Lets the inventory cache spread one huge handler across ticks. */
    fun findAllItemInfoInRange(
        storage: ItemStorageHandle,
        from: Int,
        until: Int,
        filter: (String) -> Boolean,
    ): List<ItemInfo> = if (from == 0) findAllItemInfo(storage, filter) else emptyList()

    /** Freeze a whole-only storage's entries (an ME network's item types) into an
     *  indexable snapshot, so the inventory cache can read it in ranges across
     *  ticks like slots. [memo] is the [EntrySnapshot.memo] of the previous
     *  snapshot taken for the same storage, letting unchanged entries be reused
     *  instead of rebuilt. Null when [storage] doesn't support this. */
    fun snapshotEntries(storage: ItemStorageHandle, memo: Any?): EntrySnapshot? = null

    /** Visit entries `[from, until)` of [snapshot] with their cache key. Entries
     *  unchanged since the memo'd snapshot come back as the same objects. */
    fun readEntries(
        snapshot: EntrySnapshot,
        from: Int,
        until: Int,
        visit: (damien.nodeworks.script.BufferKey.Key, ItemInfo) -> Unit,
    ) {}

    /** Extract (remove) items from storage matching the filter. Returns count actually removed. */
    fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long

    /** Extract (remove) items from storage matching the filter, returning the actual
     *  removed [ItemStack]s with all components / NBT preserved. Use this instead of
     *  [extractItems] when the caller is going to hand the items to a player or insert
     *  them into another inventory: rebuilding a stack from `ItemStack(item, count)`
     *  would silently strip durability, enchantments, custom names, dyed colour, etc.
     *
     *  Returned stacks are already removed from the source storage. May return multiple
     *  stacks if matching items are split across slots with different components. */
    fun extractItemStacksMatching(
        storage: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
    ): List<ItemStack>

    /** Stack-aware extract: the [filter] sees the full [ItemStack] of each
     *  candidate slot, so callers can match by (itemId + DataComponents).
     *  Used by the planner's Pull op to extract a specific variant (e.g. a
     *  Strength Potion specifically, not just any potion). Default
     *  implementation delegates to [extractItemStacksMatching] with an
     *  itemId-only filter and then post-filters the returned stacks, which
     *  is correct but extracts more than necessary from storage on a
     *  mixed-variant chest. Loaders should override with a slot-walking
     *  variant for efficiency. */
    fun extractStacksByPredicate(
        storage: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        /** With a cursor the scan starts where the last call stopped and wraps, see
         *  [moveItems]. Loaders honour it, this default does not. */
        cursor: ScanCursor? = null,
    ): List<ItemStack> {
        // Default: extract everything matching by itemId-of-anything-stored, then
        // filter the returned stacks and re-insert the rejects. Loaders can do
        // better by walking slots directly.
        val all = extractItemStacksMatching(storage, { true }, maxCount)
        val keep = ArrayList<ItemStack>(all.size)
        var kept = 0L
        for (stack in all) {
            if (kept < maxCount && filter(stack)) {
                keep.add(stack)
                kept += stack.count
            } else {
                insertItemStack(storage, stack)
            }
        }
        return keep
    }

    /** Stack-aware count: sums every slot whose [filter] returns true. Used
     *  by the planner's feasibility check to count specific variants (not
     *  every variant sharing an itemId). Default implementation delegates
     *  via a non-mutating slot scan in the loader. */
    fun countStacksByPredicate(
        storage: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
    ): Long = 0L

    /** Insert an ItemStack into storage. Returns count actually inserted. */
    fun insertItemStack(storage: ItemStorageHandle, stack: net.minecraft.world.item.ItemStack): Int

    /** The stack a scan would move next, without taking it: the first one past the cursor
     *  that passes [filter], sized to what the source would actually let go. The cursor is left on it, so an extract with the
     *  same cursor takes from that spot first, and a scan stopped by the move deadline
     *  leaves the cursor where it stopped for the next call. Null when nothing matches,
     *  or the backend cannot look without taking. */
    fun peekStack(storage: ItemStorageHandle, filter: (ItemStack) -> Boolean, cursor: ScanCursor?): ItemStack? = null

    /** How much of [stack], components included, [dest] would take right now. */
    fun simulateInsertStack(dest: ItemStorageHandle, stack: ItemStack): Long =
        simulateInsertItem(dest, stack.item, stack.count.toLong())

    /**
     * Spreads the source's stacks evenly over the targets, a stack at a time, so 32 logs
     * over four furnaces land 8, 8, 8, 8 and 30 land 8, 8, 7, 7 with the extras going to
     * the targets at the rotation index. Each target is asked once per stack how much it
     * would take, the stack is split by those answers, every share is placed, and only
     * then is the total taken from the source, so nothing is lifted without a home and
     * the source is walked once per stack rather than once per item.
     *
     * Only [window] targets from the rotation index are considered for one stack. When
     * none of them takes it the window moves on and tries again while time allows, so a
     * large network is covered a window at a time over runs and one run never touches
     * more targets than the window. A type no window will take is skipped for the rest of
     * the call. Targets are built and resolved lazily, so untouched targets cost nothing.
     *
     * Honours the move deadline between targets and between stacks, never between
     * placing shares and taking their total. A source that then gives back less than its
     * targets received has the surplus taken back out of them, and if even that fails the
     * call stops with one logged warning rather than duplicating again. Returns what moved
     * and the index the next call starts at.
     */
    fun moveItemsRoundRobin(
        source: ItemStorageHandle,
        /** How many targets there are. Only the ones a window reaches are ever built. */
        targetCount: Int,
        /** Builds the target at an index. Called once per index per call, on first use. */
        targetAt: (Int) -> RoundRobinTarget,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        cursor: ScanCursor?,
        startIndex: Int,
        window: Int = targetCount,
        onMoved: ((RoundRobinTarget, ItemStack, Long) -> Unit)? = null,
    ): RoundRobinResult {
        val n = targetCount
        if (n <= 0 || maxCount <= 0L) return RoundRobinResult(0L, startIndex)
        var index = startIndex.mod(n)
        val span = window.coerceIn(1, n)
        val windows = (n + span - 1) / span
        var remaining = maxCount
        var moved = 0L
        val refused = ArrayList<ItemStack>()
        // Everything per call is sized to the window or to what was touched, never to
        // the whole target list, so a network of thousands of cards costs a run nothing
        // beyond the few it actually offers items to.
        val built = HashMap<Int, RoundRobinTarget>()
        fun target(idx: Int): RoundRobinTarget = built.getOrPut(idx) { targetAt(idx) }
        val dead = java.util.BitSet(n)
        val caps = LongArray(span)
        val plan = LongArray(span)
        val put = LongArray(span)
        val sameAs: (ItemStack) -> (ItemStack) -> Boolean = { t -> { s -> ItemStack.isSameItemSameComponents(s, t) } }

        stacks@ while (remaining > 0L && !MoveDeadline.expired()) {
            val stack = peekStack(source, { s -> filter(s) && refused.none { ItemStack.isSameItemSameComponents(it, s) } }, cursor)
                ?: break
            val template = stack.copyWithCount(1)
            val count = minOf(stack.count.toLong(), remaining)

            // Ask the window what it takes. Move the window on while it takes nothing.
            var any = false
            var tried = 0
            while (!any && tried < windows) {
                java.util.Arrays.fill(caps, 0L)
                for (k in 0 until span) {
                    val idx = (index + k) % n
                    if (dead.get(idx)) continue
                    if (k > 0 && MoveDeadline.expired()) break
                    val target = target(idx)
                    val storage = target.storage
                    if (storage == null) { dead.set(idx); continue }
                    if (target.accept?.invoke(stack) == false) continue
                    val cap = simulateInsertStack(storage, stack.copyWithCount(count.toInt()))
                    if (cap > 0L) { caps[k] = cap; any = true }
                }
                if (any) break
                tried++
                if (MoveDeadline.expired()) break@stacks
                index = (index + span) % n
            }
            if (!any) { refused.add(template); continue }

            // Even shares within the window, extras to the first in rotation order.
            java.util.Arrays.fill(plan, 0L)
            val lastReceiver = RoundRobinSpread.plan(count, caps, plan)

            // Commit: every share placed, then one take for the lot.
            java.util.Arrays.fill(put, 0L)
            var inserted = 0L
            for (k in 0 until span) {
                if (plan[k] <= 0L) continue
                val storage = target((index + k) % n).storage ?: continue
                val got = insertItemStack(storage, stack.copyWithCount(plan[k].toInt())).toLong()
                put[k] = got
                inserted += got
            }
            if (inserted <= 0L) { refused.add(template); continue }
            var taken = 0L
            for (s in extractStacksByPredicate(source, sameAs(template), inserted, cursor)) taken += s.count.toLong()
            var surplus = inserted - taken
            if (surplus > 0L) {
                for (k in span - 1 downTo 0) {
                    if (surplus <= 0L) break
                    if (put[k] <= 0L) continue
                    val storage = target((index + k) % n).storage ?: continue
                    var back = 0L
                    for (s in extractStacksByPredicate(storage, sameAs(template), minOf(surplus, put[k]))) back += s.count.toLong()
                    put[k] -= back
                    surplus -= back
                }
                if (surplus > 0L) {
                    STORAGE_LOGGER.warn(
                        "Round robin: source gave back {} fewer {} than its targets received and the surplus could not be recovered, stopping this run",
                        surplus, template.item,
                    )
                }
            }
            if (onMoved != null) {
                for (k in 0 until span) if (put[k] > 0L) onMoved(target((index + k) % n), template, put[k])
            }
            moved += taken
            remaining -= taken
            if (lastReceiver >= 0) index = (index + lastReceiver + 1) % n
            if (inserted != taken) break
        }
        return RoundRobinResult(moved, index)
    }

    /**
     * Insert exactly [count] items of [item] into [dest] atomically.
     *
     * Either inserts all [count] and returns true, or inserts nothing and returns false,
     * never leaves a partial state. Used by `:insert` to guarantee items can't be duped or
     * deleted on a half-successful move.
     *
     * The implementation uses the platform's native transaction/simulation primitives so
     * simulation reflects the real post-insert state (accumulating effects of each stack-sized
     * batch). Cheap: O(destination slots) regardless of item count.
     */
    fun tryInsertAll(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, count: Long): Boolean

    /**
     * Non-mutating capacity probe: returns how many [item] could be inserted into [dest]
     * right now, up to [maxCount]. Backs atomic network-wide inserts, we sum the
     * per-card capacities before committing so a partially-full network never extracts
     * from source unless the whole move will fit.
     *
     * Uses the platform's native simulate primitive (`IItemHandler.insertItem(simulate=true)`
     * on NeoForge), so the returned number reflects slot-level constraints (stack limits,
     * filter slots, etc.), not just free slot count × stack size.
     */
    fun simulateInsertItem(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, maxCount: Long): Long = 0L

    /** Dry run of a move of up to [maxCount] items matching [filter], touching neither
     *  side. Lets callers refuse a move that cannot fit before extracting anything, which
     *  is both faster and never puts items in flight. Null when it can't be predicted. */
    fun previewMove(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (ItemStack) -> Boolean,
        maxCount: Long,
        /** Stop after this many distinct items. Fullness checks pass a small cap so a
         *  source full of unique items doesn't cost one simulated insert per item. A capped
         *  run that stops early can't rule the move out, so it skips the simulation and
         *  reports null [MovePreview.fits]. */
        maxStacks: Int = Int.MAX_VALUE,
    ): MovePreview? = null

    /**
     * Move up to [count] items matching [filter] from [source] to [dest] atomically.
     *
     * Either moves exactly [count] and returns true, or moves nothing and returns false.
     * If [source] lacks [count] matching items, returns false without touching either side.
     * If [dest] can't accept the full amount, returns false without touching either side.
     */
    fun tryMoveAll(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        count: Long
    ): Boolean

    /** Get a slotted view of the storage, or null if not slotted. */
    fun getSlottedStorage(level: ServerLevel, pos: BlockPos, face: Direction): SlottedItemStorageHandle?

    // --- Fluid side (default no-op to keep non-implementing loaders compiling) ---

    /** Count fluid (in mB) matching [filter] in [storage]. */
    fun countFluid(storage: FluidStorageHandle, filter: (String) -> Boolean): Long = 0L

    /** Find the first fluid in [storage] matching [filter], with metadata. */
    fun findFirstFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): FluidInfo? = null

    /** Find all unique fluid types in [storage] matching [filter]. */
    fun findAllFluidInfo(storage: FluidStorageHandle, filter: (String) -> Boolean): List<FluidInfo> = emptyList()

    /** Move fluid between storages. Returns amount moved (mB). */
    fun moveFluid(source: FluidStorageHandle, dest: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long = 0L

    /** Atomically move exactly [amount] mB of fluid matching [filter] from [source] to [dest]. */
    fun tryMoveAllFluid(source: FluidStorageHandle, dest: FluidStorageHandle, filter: (String) -> Boolean, amount: Long): Boolean = false

    /** Insert up to [amount] mB of the given fluid id into [dest]. Returns amount actually inserted. */
    fun insertFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Long = 0L

    /** Atomically insert exactly [amount] mB of [fluidId] into [dest], or nothing. */
    fun tryInsertAllFluid(dest: FluidStorageHandle, fluidId: String, amount: Long): Boolean = false

    /**
     * Non-mutating fluid capacity probe. Returns how many mB of [fluidId] could be
     * filled into [dest] right now, up to [maxAmount]. Backs atomic network-wide
     * fluid inserts.
     */
    fun simulateInsertFluid(dest: FluidStorageHandle, fluidId: String, maxAmount: Long): Long = 0L

    /** Extract up to [maxAmount] mB matching [filter] from [storage]. Returns amount actually removed. */
    fun extractFluid(storage: FluidStorageHandle, filter: (String) -> Boolean, maxAmount: Long): Long = 0L

    // --- Energy side (read only, default no-op to keep non-implementing loaders compiling) ---

    /** Stored energy and capacity of the block at [pos] as seen from [face], null when it
     *  has no energy storage. */
    fun getEnergy(level: ServerLevel, pos: BlockPos, face: Direction): EnergyInfo? = null
}

/** A block's energy storage, in the loader's energy unit (FE on NeoForge). */
data class EnergyInfo(val stored: Long, val capacity: Long)

/** Metadata for an item found in storage.
 *
 *  [componentsPatch] is a representative sample of one source stack's components
 *  (the first sampled when multiple stacks aggregate into the same `(itemId,
 *  hasData)` bucket). Drives the Inventory Terminal's display stack so durability
 *  bars, custom names, and enchantment glints render in the grid. Extracted
 *  stacks carry their own real components via [StorageService.extractItemStacksMatching],
 *  so per-bucket aggregation never loses data on the way back out. */
data class ItemInfo(
    val itemId: String,
    val name: String,
    val count: Long,
    val maxStackSize: Int,
    val hasData: Boolean,
    val isCraftable: Boolean = false,
    val componentsPatch: DataComponentPatch = DataComponentPatch.EMPTY,
    /** Why a craftable entry cannot be crafted right now, one of [CraftWarning]. */
    val craftWarning: Byte = CraftWarning.NONE,
) {
    val stackable: Boolean get() = maxStackSize > 1
}

/** Why the Inventory Terminal flags a recipe output. Lower is better, so when an
 *  item has several recipes the best one decides. */
object CraftWarning {
    const val NONE: Byte = 0
    const val NO_CONTROLLER: Byte = 1
    const val NO_HANDLER: Byte = 2

    fun message(warning: Byte): String? = when (warning) {
        NO_CONTROLLER -> "Network Controller missing, cannot autocraft"
        NO_HANDLER -> "Recipe missing handler"
        else -> null
    }
}

/** One destination of [StorageService.moveItemsRoundRobin]. [tag] rides along to the
 *  move callback so the caller can tell which destination took what. */
class RoundRobinTarget(
    /** Resolved on first use, so a target the spread never reaches costs nothing. */
    private val resolve: () -> ItemStorageHandle?,
    val accept: ((ItemStack) -> Boolean)?,
    val tag: Any? = null,
) {
    private var resolved = false
    private var handle: ItemStorageHandle? = null

    val storage: ItemStorageHandle?
        get() {
            if (!resolved) {
                handle = resolve()
                resolved = true
            }
            return handle
        }
}

/** The split behind [StorageService.moveItemsRoundRobin], kept free of any backend so it
 *  can be tested on its own. */
object RoundRobinSpread {
    /** Shares [count] over a window of targets, each capped by its entry in [caps], as
     *  evenly as the caps allow: every pass gives each target with room the same amount,
     *  and the last few go one each to the first targets in order. Shares land in [plan].
     *  Returns the last window position that received anything, or -1 for none. */
    fun plan(count: Long, caps: LongArray, plan: LongArray): Int {
        var left = count
        var lastReceiver = -1
        while (left > 0L) {
            var live = 0
            for (k in caps.indices) if (plan[k] < caps[k]) live++
            if (live == 0) break
            val per = maxOf(1L, left / live)
            for (k in caps.indices) {
                if (left <= 0L) break
                val room = caps[k] - plan[k]
                if (room <= 0L) continue
                val give = minOf(per, room, left)
                plan[k] += give
                left -= give
                lastReceiver = k
            }
        }
        return lastReceiver
    }
}

/** Result of [StorageService.moveItemsRoundRobin]. */
data class RoundRobinResult(val moved: Long, val nextIndex: Int)

/** Where a resumable [StorageService.moveItems] scan picks up next. Only a hint, a slot
 *  past the end of the source starts again at the first slot. */
class ScanCursor {
    var slot: Int = 0

    /** For a source with no slots (ME), the item type the last call stopped on. The next
     *  call starts there, or from the top when it is gone. */
    var key: Any? = null

    /** True when [key] already had its full turn (the call ran out of time, not items), so
     *  the next call starts after it and a slow type can't hold the scan in place. */
    var keyDone: Boolean = false
}

/** Result of [StorageService.previewMove]. [available] is how many matching items the
 *  source holds, up to the asked amount. [fits] is how many of those the destination
 *  would accept, null when the destination can't simulate. [sawAll] is true when the
 *  dry run saw every matching item in the source. */
data class MovePreview(val available: Long, val fits: Long?, val sawAll: Boolean)

/** Metadata for a fluid found in storage. Counts are in millibuckets. */
data class FluidInfo(
    val fluidId: String,
    val name: String,
    val amount: Long
)

/** Opaque handle to an item storage, platform-specific implementation. */
interface ItemStorageHandle

/** Opaque handle to a fluid storage, platform-specific implementation. */
interface FluidStorageHandle

/** A whole-only storage's entries frozen at one moment, see
 *  [StorageService.snapshotEntries]. Platform-specific, opaque to callers. */
interface EntrySnapshot {
    /** Entries in the snapshot, the index space of [StorageService.readEntries]. */
    val size: Int
    /** Reuse state to pass to the next [StorageService.snapshotEntries] call for
     *  the same storage. Only meaningful once this snapshot was read to its end. */
    val memo: Any
}

/** Opaque handle to a slotted item storage. */
interface SlottedItemStorageHandle : ItemStorageHandle {
    val slotCount: Int
    fun filteredBySlots(slots: Set<Int>): ItemStorageHandle
}

/**
 * Abstracts extended menu type registration and opening.
 */
interface MenuService {
    /** Open an extended menu with custom data sent to the client. */
    fun <D : Any> openExtendedMenu(
        player: ServerPlayer,
        title: Component,
        data: D,
        codec: net.minecraft.network.codec.StreamCodec<in net.minecraft.network.FriendlyByteBuf, D>,
        menuFactory: (syncId: Int, playerInventory: Inventory, player: Player) -> AbstractContainerMenu
    )
}

/**
 * Abstracts block entity type creation.
 */
interface BlockEntityService {
    fun <T : BlockEntity> createBlockEntityType(
        factory: (BlockPos, BlockState) -> T,
        vararg blocks: net.minecraft.world.level.block.Block
    ): BlockEntityType<T>
}

/**
 * Abstracts mod-level state that lives in the loader-specific module.
 */
interface ModStateService {
    /** Current server tick count. */
    val tickCount: Long

    /** True when the mod with [modId] is installed, for optional integrations. */
    fun isModLoaded(modId: String): Boolean

    /** Check if a script engine is running at the given terminal position. */
    fun isScriptRunning(level: ServerLevel, pos: BlockPos): Boolean

    /** Stop the script engine at the given terminal position. */
    fun stopScript(level: ServerLevel, pos: BlockPos)

    /** Start the terminal's script immediately on the server tick. Used by the redstone-
     *  pulse toggle on the Terminal block, no network round-trip, no open GUI required. */
    fun startScript(level: ServerLevel, pos: BlockPos)

    /** Register a terminal for auto-run on world startup. */
    fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos)

    /**
     * Find the ScriptEngine that has a processing handler for the given card name,
     * scoped to the given terminal positions (i.e., only terminals on the same network).
     *
     * [overrideDimension] is used when the terminal positions live in a different
     * dimension than the calling [level], e.g. a cross-dimensional Receiver Antenna
     * where the provider network is in the Nether but the consumer terminal is in the
     * Overworld. Pass null when the positions are in the caller's own dimension.
     */
    fun findProcessingEngine(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        cardName: String,
        overrideDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>? = null,
        /** The set itself, so `network:recipes` rules can match it. Without it only an
         *  exact `network:handle` counts, which is what a claim check wants. */
        api: damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo? = null,
    ): Any? = null

    /** Find any active ScriptEngine at the given terminal positions. Same dimension
     *  override semantics as [findProcessingEngine]. */
    fun findAnyEngine(
        level: ServerLevel,
        terminalPositions: List<BlockPos>,
        overrideDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>? = null,
    ): Any? = null

    /** Get the ScriptEngine at a specific terminal position, or null. */
    fun getScriptEngine(level: ServerLevel, pos: BlockPos): Any? = null
}
