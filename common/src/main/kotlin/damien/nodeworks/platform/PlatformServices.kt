package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
    lateinit var clientEvents: ClientEventService

    /** FakePlayer factory + permission gate for script-driven block mutations. Defaults
     *  to a no-op so unit tests and pre-init paths don't crash, the loader replaces
     *  this at mod init. */
    var fakePlayer: FakePlayerService = NoopFakePlayerService

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
interface StorageService {
    /** Get an item storage handle for a block at [pos] accessed from [face]. Returns null if not available. */
    fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle?

    /** Get a fluid storage handle for a block at [pos] accessed from [face]. Returns null if not available or unsupported on this platform. */
    fun getFluidStorage(level: ServerLevel, pos: BlockPos, face: Direction): FluidStorageHandle? = null

    /** Move items between storages. Returns number moved. */
    fun moveItems(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long

    /** Move items with data-aware filter. Filter receives (itemId, hasData). */
    fun moveItemsVariant(source: ItemStorageHandle, dest: ItemStorageHandle, filter: (String, Boolean) -> Boolean, maxCount: Long): Long {
        // Default: ignore hasData, delegate to string-only version
        return moveItems(source, dest, { filter(it, false) }, maxCount)
    }

    /** Count items matching filter in a storage. */
    fun countItems(storage: ItemStorageHandle, filter: (String) -> Boolean): Long

    /** Find the first item ID in storage matching the filter. Returns null if none found. */
    fun findFirstItem(storage: ItemStorageHandle, filter: (String) -> Boolean): String?

    /** Find the first item in storage matching the filter, with full metadata. */
    fun findFirstItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): ItemInfo?

    /** Find ALL unique item types in storage matching the filter, with full metadata. */
    fun findAllItemInfo(storage: ItemStorageHandle, filter: (String) -> Boolean): List<ItemInfo>

    /** Extract (remove) items from storage matching the filter. Returns count actually removed. */
    fun extractItems(storage: ItemStorageHandle, filter: (String) -> Boolean, maxCount: Long): Long

    /** Insert an ItemStack into storage. Returns count actually inserted. */
    fun insertItemStack(storage: ItemStorageHandle, stack: net.minecraft.world.item.ItemStack): Int

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
}

/** Metadata for an item found in storage. */
data class ItemInfo(
    val itemId: String,
    val name: String,
    val count: Long,
    val maxStackSize: Int,
    val hasData: Boolean,
    val isCraftable: Boolean = false
) {
    val stackable: Boolean get() = maxStackSize > 1
}

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
