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
 * Platform service locator — set by the loader-specific module at init time.
 */
object PlatformServices {
    lateinit var storage: StorageService
    lateinit var menu: MenuService
    lateinit var blockEntity: BlockEntityService
    lateinit var modState: ModStateService
    lateinit var clientNetworking: ClientNetworkingService
    lateinit var clientEvents: ClientEventService
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
 * Abstracts item storage access (Fabric Transfer API vs NeoForge IItemHandler).
 */
interface StorageService {
    /** Get an item storage handle for a block at [pos] accessed from [face]. Returns null if not available. */
    fun getItemStorage(level: ServerLevel, pos: BlockPos, face: Direction): ItemStorageHandle?

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
     * Either inserts all [count] and returns true, or inserts nothing and returns false —
     * never leaves a partial state. Used by `:insert` to guarantee items can't be duped or
     * deleted on a half-successful move.
     *
     * The implementation uses the platform's native transaction/simulation primitives so
     * simulation reflects the real post-insert state (accumulating effects of each stack-sized
     * batch). Cheap: O(destination slots) regardless of item count.
     */
    fun tryInsertAll(dest: ItemStorageHandle, item: net.minecraft.world.item.Item, count: Long): Boolean

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

/** Opaque handle to an item storage — platform-specific implementation. */
interface ItemStorageHandle

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
     *  pulse toggle on the Terminal block — no network round-trip, no open GUI required. */
    fun startScript(level: ServerLevel, pos: BlockPos)

    /** Register a terminal for auto-run on world startup. */
    fun registerPendingAutoRun(level: ServerLevel, pos: BlockPos)

    /**
     * Find the ScriptEngine that has a processing handler for the given card name,
     * scoped to the given terminal positions (i.e., only terminals on the same network).
     */
    fun findProcessingEngine(level: ServerLevel, terminalPositions: List<BlockPos>, cardName: String): Any? = null

    /** Find any active ScriptEngine at the given terminal positions. */
    fun findAnyEngine(level: ServerLevel, terminalPositions: List<BlockPos>): Any? = null

    /** Get the ScriptEngine at a specific terminal position, or null. */
    fun getScriptEngine(level: ServerLevel, pos: BlockPos): Any? = null
}
