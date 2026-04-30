package damien.nodeworks.screen

import damien.nodeworks.network.InventorySyncPayload
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.registry.ModScreenHandlers
import damien.nodeworks.script.NetworkInventoryCache
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.inventory.ResultSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.TransientCraftingContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeType

/**
 * Server-side menu for the Inventory Terminal.
 * Zero MC Slot objects, all interaction via custom packets.
 * Full sync on open, delta updates while open.
 */
class InventoryTerminalMenu(
    syncId: Int,
    val playerInventory: Inventory,
    private var serverLevel: ServerLevel?,
    val terminalPos: BlockPos?,
    /** True when this menu is driving a Handheld Inventory Terminal. Adds a single
     *  slot (index [CRYSTAL_SLOT]) for the Link Crystal that points at the target
     *  network. False for the fixed terminal, which has no crystal. Stored as a
     *  `val` (not private) so the client Screen can decide whether to draw the slot. */
    val hasCrystalSlot: Boolean = false,
) : AbstractContainerMenu(ModScreenHandlers.INVENTORY_TERMINAL, syncId), BlockBackedMenu {

    /** Null when the terminal is the Handheld variant (item-backed, not block-
     *  backed). The break-event listener filters nulls out, so a held Handheld
     *  Terminal isn't kicked when an unrelated block is mined. */
    override val blockBackingPos: BlockPos? get() = terminalPos

    /** Abstracts where this menu's network lives (fixed block vs. Handheld). Null
     *  when the Handheld's crystal is absent, blank, wrong-kind, or unreachable,
     *  in which case the grid renders empty and operations no-op. Also null on the
     *  client copy of the menu. */
    private var source: InventoryTerminalNetworkSource? = null

    /**
     * Backing container for the Handheld's Link Crystal slot. Null when
     * [hasCrystalSlot] is false (fixed terminal never has one). For Handhelds it's
     * a 1-slot container that's populated from the Portable's data component on menu
     * open and has its contents written back to the Portable whenever the slot
     * changes, see [onCrystalSlotChanged]. Exposed so the Screen can render the
     * slot at a specific on-screen position.
     *
     * The anonymous [SimpleContainer] subclass overrides `setChanged` to dispatch
     * to this menu's [slotsChanged]. Plain [SimpleContainer] (unlike
     * [TransientCraftingContainer]) has no listener API in 26.1, and relying on
     * [Slot.setChanged] alone misses the pickup path: `container.removeItem`
     * bumps `container.setChanged` without going through the slot. The override
     * catches every mutation, placements, pickups, direct setItem calls, so the
     * menu never has a stale view of the crystal slot.
     */
    val crystalContainer: net.minecraft.world.SimpleContainer? =
        if (hasCrystalSlot) object : net.minecraft.world.SimpleContainer(1) {
            override fun setChanged() {
                super.setChanged()
                this@InventoryTerminalMenu.slotsChanged(this)
            }
        } else null

    /** Callback the server uses to locate the Portable ItemStack that owns the
     *  crystal slot. Invoked on every crystal-slot change so the Portable's
     *  component persists even through inventory moves (the Portable stack we
     *  captured at open-time may have been replaced by a copy during crafting or
     *  merging, re-reading here always gets the live instance). Null for client
     *  menus and for fixed terminals. */
    private var crystalHolderProvider: (() -> ItemStack)? = null

    private var cache: NetworkInventoryCache? = null
    private var snapshot: damien.nodeworks.network.NetworkSnapshot? = null
    private var needsFullSync = true
    private var needsImmediateSync = false
    private var tickCounter = 0

    /** Last reason [tryResolveSource] failed. Null means no attempt yet or the
     *  last attempt succeeded. Server-side only, the client's view of the
     *  connection state is [connectionStatus], synced via payload. */
    private var lastFailure: ResolutionFailure? = null

    /** Last connection status we pushed to the client. Used to debounce the sync
     *  payload so we only send on transitions. Server-side only. */
    private var lastSentStatus: PortableConnectionStatus? = null

    /** Client-facing connection status. Updated by the server's sync payload
     *  handler. The screen reads this to decide whether to draw the
     *  "Out of Range" / "No Link Crystal" / etc. overlay over the grid. */
    var connectionStatus: PortableConnectionStatus = PortableConnectionStatus.CONNECTED

    // Chunked full sync state
    private var fullSyncChunks: List<List<InventorySyncPayload.SyncEntry>>? = null
    private var fullSyncChunkIndex = 0
    private val FULL_SYNC_CHUNK_SIZE = 200

    // Crafting grid
    var autoPull: Boolean = false
    private var autoPullPattern: List<String>? = null  // captured right before craft output is taken
    private var suppressSlotsChanged = false
    private var pendingAutoPull = false  // deferred to next tick to avoid MC packet reconciliation conflicts
    val craftingContainer = TransientCraftingContainer(this, 3, 3)
    val resultContainer = ResultContainer()

    // Slot index constants
    // 0-35: hidden player inventory slots (for sync)
    // 36-44: crafting input slots
    // 45: crafting output slot
    // 46: link crystal slot (Handheld only, absent on fixed terminals)

    init {
        // Hidden MC Slots for inventory sync only (slots 0-35)
        for (row in 0..2) {
            for (col in 0..8) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, -999, -999))
            }
        }
        for (col in 0..8) {
            addSlot(Slot(playerInventory, col, -999, -999))
        }

        // Crafting input slots (slots 36-44), positioned off-screen, screen renders them
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(Slot(craftingContainer, col + row * 3, -999, -999))
            }
        }

        // Crafting output slot (slot 45)
        addSlot(ResultSlot(playerInventory.player, craftingContainer, resultContainer, 0, -999, -999))

        // Link crystal slot (Handheld only). The slot lives at (-999, -999), the
        // screen renders it at its real on-screen position and dispatches clicks
        // via `slotClicked` by index. A filter so non-crystal items bounce off.
        // The container→menu bridge is handled by [crystalContainer]'s `setChanged`
        // override (see field docstring), nothing extra is needed here.
        if (hasCrystalSlot) {
            addSlot(object : Slot(crystalContainer!!, 0, -999, -999) {
                override fun mayPlace(stack: ItemStack): Boolean =
                    stack.isEmpty || stack.item is damien.nodeworks.item.LinkCrystalItem
                override fun getMaxStackSize(stack: ItemStack): Int = 1
            })
        }
    }

    /** Called when any attached container's contents change. We dispatch on the
     *  specific container:
     *    * crafting grid → recompute craft output.
     *    * crystal slot → write the new crystal back to the Portable's data
     *      component and attempt to (re)resolve the network source.
     *  Other containers fall through to super. */
    override fun slotsChanged(container: net.minecraft.world.Container) {
        if (suppressSlotsChanged) return
        if (container === craftingContainer) {
            // slotsChanged fires server-side for a server menu, on the client copy
            // serverLevel is null and we have nothing to look up. Bail cleanly
            // rather than querying a Level's client-side RecipeAccess (which
            // doesn't expose getRecipeFor in 26.1).
            val level = serverLevel
            if (level != null) {
                val recipe = level.recipeAccess()
                    .getRecipeFor(RecipeType.CRAFTING, craftingContainer.asCraftInput(), level)
                if (recipe.isPresent) {
                    // assemble(input) in 26.1, registryAccess folded into the recipe.
                    resultContainer.setItem(0, recipe.get().value().assemble(craftingContainer.asCraftInput()))
                } else {
                    resultContainer.setItem(0, ItemStack.EMPTY)
                }
            }
        }
        if (container === crystalContainer) {
            onCrystalSlotChanged()
        }
        super.slotsChanged(container)
    }

    /**
     * Persist the current crystal-slot contents back to the Portable that opened this
     * menu, then attempt to (re)resolve the network source. Called every time the slot
     * changes, pulling a crystal out, dropping one in, swapping, etc. The write
     * happens against the live Portable stack (via [crystalHolderProvider], which
     * re-reads the player's hand every invocation).
     *
     * If the Portable has gone away since the menu was opened (player dropped it,
     * switched it for something else), we silently skip the write, the next
     * [stillValid] check will see the missing Portable and close the menu.
     *
     * The resolve call runs unconditionally (whether or not the write-back succeeded):
     * an empty slot disconnects, a valid crystal connects, and a wrong-kind or stale
     * crystal disconnects with the grid rendering empty.
     */
    private fun onCrystalSlotChanged() {
        val provider = crystalHolderProvider ?: return
        val container = crystalContainer ?: return
        val portable = provider()
        if (!portable.isEmpty && portable.item is damien.nodeworks.item.PortableInventoryTerminalItem) {
            damien.nodeworks.item.PortableInventoryTerminalItem.setInstalledCrystal(
                portable,
                container.getItem(0),
            )
        }
        tryResolveSource()
    }

    /**
     * Bind this menu to a network source at open time. Two modes:
     *   * Fixed terminal, [source] is non-null, [crystalHolderProvider] is null. Works
     *     exactly as before: discover the network, grab its inventory cache, done.
     *   * Handheld, [crystalHolderProvider] is non-null (and [hasCrystalSlot] must be
     *     true). [source] may be null, in that case the menu opens disconnected and
     *     attempts to resolve from whatever crystal the Portable currently has
     *     installed. Subsequent crystal-slot changes and periodic retries can
     *     promote a disconnected menu to a connected one (and vice versa).
     *
     * In both modes, [level] is the initial ServerLevel, for fixed terminals it's
     * already the network's level, for Handhelds it's the player's current level and
     * will be overwritten if resolve lands the menu on a different dimension's network.
     */
    fun createServer(
        level: ServerLevel,
        source: InventoryTerminalNetworkSource?,
        crystalHolderProvider: (() -> ItemStack)? = null,
    ) {
        serverLevel = level

        if (hasCrystalSlot && crystalHolderProvider != null) {
            this.crystalHolderProvider = crystalHolderProvider
            // Seed the slot from the Portable's current installed crystal. The
            // suppress-slotsChanged guard prevents this initial populate from
            // triggering a redundant write-back to the same Portable we just read.
            val portable = crystalHolderProvider()
            val installed = damien.nodeworks.item.PortableInventoryTerminalItem
                .getInstalledCrystal(portable)
            suppressSlotsChanged = true
            try {
                crystalContainer?.setItem(0, installed.copy())
            } finally {
                suppressSlotsChanged = false
            }
            // Try to resolve whatever crystal is in the slot now. Fails silently into
            // the disconnected state if no crystal / antenna missing / out of range.
            tryResolveSource()
        } else if (source != null) {
            connect(source, level)
        }
    }

    /**
     * Attempt to resolve the Handheld's crystal slot into a live network source.
     * Handheld-only, fixed terminals never call this (their source is pinned at open).
     *
     * Outcomes:
     *   * Empty slot or [CrystalBackedSource.Resolution.Failure] → [disconnect] so the
     *     grid renders empty and operations no-op. The player will see the disconnected
     *     state reflected in the next broadcast.
     *   * [CrystalBackedSource.Resolution.Success] → [connect] against the resolved
     *     source's dimension (may differ from the player's current level for cross-dim
     *     antennas), triggering a full sync on the next broadcast.
     */
    private fun tryResolveSource() {
        val holder = crystalHolderProvider ?: return
        val container = crystalContainer ?: return
        val serverPlayer = playerInventory.player as? ServerPlayer ?: return

        val crystal = container.getItem(0)
        if (crystal.isEmpty) {
            lastFailure = null
            disconnect()
            return
        }

        val server = (serverPlayer.level() as ServerLevel).server
        val resolution = CrystalBackedSource.resolve(
            server = server,
            crystal = crystal,
            player = serverPlayer,
            holderProvider = holder,
        )
        when (resolution) {
            is CrystalBackedSource.Resolution.Failure -> {
                lastFailure = resolution.reason
                disconnect()
            }
            is CrystalBackedSource.Resolution.Success -> {
                val newSource = resolution.source
                val targetLevel = server.getLevel(newSource.dimension)
                if (targetLevel == null) {
                    lastFailure = ResolutionFailure.DIMENSION_UNAVAILABLE
                    disconnect()
                    return
                }
                lastFailure = null
                connect(newSource, targetLevel)
            }
        }
    }

    /**
     * Fold the current server-side state into the client-facing status enum.
     * Considers connection (source != null → CONNECTED), whether the crystal slot
     * is empty at all (NO_CRYSTAL short-circuits everything else), and otherwise
     * maps the [lastFailure] reason into the narrower set of states the overlay
     * renders.
     */
    private fun currentConnectionStatus(): PortableConnectionStatus {
        if (source != null) return PortableConnectionStatus.CONNECTED
        val crystal = crystalContainer?.getItem(0)
        if (crystal == null || crystal.isEmpty) return PortableConnectionStatus.NO_CRYSTAL
        return when (lastFailure) {
            null -> PortableConnectionStatus.NO_CRYSTAL
            ResolutionFailure.BLANK_CRYSTAL -> PortableConnectionStatus.BLANK_CRYSTAL
            ResolutionFailure.WRONG_KIND -> PortableConnectionStatus.WRONG_KIND
            ResolutionFailure.OUT_OF_RANGE -> PortableConnectionStatus.OUT_OF_RANGE
            ResolutionFailure.DIMENSION_MISMATCH -> PortableConnectionStatus.DIMENSION_MISMATCH
            // Everything else, antenna unloaded, antenna gone, frequency changed,
            // controller removed, dimension doesn't exist on the server, is the
            // same "network side is unreachable" state from the player's POV.
            ResolutionFailure.DIMENSION_UNAVAILABLE,
            ResolutionFailure.ANTENNA_UNLOADED,
            ResolutionFailure.ANTENNA_MISSING,
            ResolutionFailure.FREQUENCY_MISMATCH,
            ResolutionFailure.NO_CONTROLLER -> PortableConnectionStatus.UNREACHABLE
        }
    }

    /**
     * Bind the menu to [newSource] on [level]. Discovers the network, grabs the
     * inventory cache, and flags the next broadcast as a full sync so the client
     * replaces any previously-displayed contents.
     */
    private fun connect(newSource: InventoryTerminalNetworkSource, level: ServerLevel) {
        source = newSource
        serverLevel = level
        snapshot = NetworkDiscovery.discoverNetwork(level, newSource.entryPoint)
        cache = NetworkInventoryCache.getOrCreate(level, newSource.entryPoint)
        // Abandon any in-flight chunked sync, the entry set is about to change
        // wholesale, so the remaining chunks would be addressing the old network.
        fullSyncChunks = null
        fullSyncChunkIndex = 0
        needsFullSync = true
    }

    /**
     * Drop the menu into a disconnected state: no source, no snapshot, no cache.
     * Grid operations noop (they all guard on snapshot/cache being non-null) and the
     * next broadcast sends an empty full-sync to clear the client's display.
     *
     * No-op if already disconnected, avoids redundant empty syncs every tick while
     * the player fiddles with an invalid crystal.
     */
    private fun disconnect() {
        if (source == null && snapshot == null && cache == null) return
        source = null
        snapshot = null
        cache = null
        fullSyncChunks = null
        fullSyncChunkIndex = 0
        needsFullSync = true
    }

    /**
     * Handheld recovery tick. Runs once a second from [broadcastChanges]. Two jobs:
     *   * If we have a source but [InventoryTerminalNetworkSource.isValid] says it's
     *     gone (player stepped out of range, antenna chunk unloaded, controller broken,
     *     etc.), drop to the disconnected state.
     *   * If we're disconnected *and* the crystal slot still has a crystal, retry
     *     resolve. This is what lets the menu recover transparently when the antenna's
     *     chunk loads back in or the player walks back into range.
     */
    private fun tickRefreshConnection() {
        if (crystalHolderProvider == null) return
        val serverPlayer = playerInventory.player as? ServerPlayer ?: return

        val current = source
        if (current != null && !current.isValid(serverPlayer)) {
            disconnect()
        }

        if (source == null) {
            tryResolveSource()
        }
    }

    companion object {
        const val PLAYER_SLOT_COUNT = 36
        const val CRAFT_INPUT_START = 36
        const val CRAFT_OUTPUT_SLOT = 45
        /** Link crystal slot index for Handheld menus. Absent (no slot) for fixed
         *  terminals, only valid when `menu.hasCrystalSlot` is true. */
        const val CRYSTAL_SLOT = 46
        private const val BUCKET_MB = 1000L

        /**
         * Server-side factory. Two shapes:
         *   * Fixed terminal, pass a non-null [source] and the ServerLevel hosting
         *     that network (caller resolves it via `server.getLevel(source.dimension)`).
         *   * Handheld, pass [hasCrystalSlot] = true and a [crystalHolderProvider],
         *     and leave [source] null. The menu opens disconnected and resolves from
         *     whatever crystal the Portable currently has in its component slot. If
         *     no valid network can be resolved, the menu stays open but its grid
         *     renders empty until the player installs a working crystal.
         *
         * [level] is always required. For fixed terminals it's the network's dimension,
         * for Handhelds it's the player's current level, used as a starting point until
         * resolve lands on the real target (which may be a different dimension).
         *
         * [displayPos] is an optional hint forwarded to the client for server-round-trip
         * packets like `SetLayoutPayload`. Fixed terminals pass their block position,
         * the Handheld passes null, in which case those packets aren't sent and the
         * related settings become client-only or get persisted elsewhere.
         */
        fun createServer(
            syncId: Int,
            inv: Inventory,
            level: ServerLevel,
            source: InventoryTerminalNetworkSource?,
            displayPos: BlockPos? = null,
            hasCrystalSlot: Boolean = false,
            crystalHolderProvider: (() -> ItemStack)? = null,
        ): InventoryTerminalMenu {
            val menu = InventoryTerminalMenu(syncId, inv, level, displayPos, hasCrystalSlot)
            menu.createServer(level, source, crystalHolderProvider)
            return menu
        }

        fun clientFactory(syncId: Int, inv: Inventory, data: InventoryTerminalOpenData): InventoryTerminalMenu {
            // hasCrystalSlot comes through on the open packet so the client's slot
            // count matches the server's, AbstractContainerMenu syncs by slot index
            // and a mismatch there scrambles every inventory update mid-session.
            return InventoryTerminalMenu(syncId, inv, null, data.terminalPos, data.hasCrystalSlot)
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        if (slotIndex == CRAFT_OUTPUT_SLOT) {
            val slot = slots[slotIndex]
            if (!slot.hasItem()) return ItemStack.EMPTY
            // Snapshot pattern BEFORE onTake consumes ingredients
            if (autoPull) autoPullPattern = snapshotCraftPattern()
            val result = slot.item.copy()
            if (!playerInventory.add(result.copy())) return ItemStack.EMPTY
            slot.onTake(player, result)
            // Clear the live result stack so vanilla's QUICK_MOVE loop
            // terminates client-side after one iteration. Our [slotsChanged]
            // is server-gated, so without this the client's prediction
            // can't see the slot empty and re-enters quickMoveStack until
            // it fills the player inventory with a phantom stack.
            slot.set(ItemStack.EMPTY)
            // Refill from network inline so the server's QUICK_MOVE loop
            // can chain crafts off the player's network stock instead of
            // capping at one craft per click. The refill triggers
            // [slotsChanged] which repopulates [resultContainer], the loop
            // then sees a fresh result and continues. Client-side this is
            // a no-op (server-only network access), so the prediction
            // still exits after one iteration as desired.
            if (autoPull) {
                autoPullRefill()
            }
            return result
        }
        // Crafting input slots: move back to player inventory
        if (slotIndex in CRAFT_INPUT_START until CRAFT_OUTPUT_SLOT) {
            val slot = slots[slotIndex]
            if (!slot.hasItem()) return ItemStack.EMPTY
            val stack = slot.item.copy()
            if (!playerInventory.add(stack)) return ItemStack.EMPTY
            slot.set(ItemStack.EMPTY)
            playerInventory.setChanged()
            return stack
        }
        // Crystal slot: pull the installed crystal back into the player's inventory.
        // The Slot's set-callback routes through slotsChanged → onCrystalSlotChanged,
        // which writes the now-empty slot back to the Portable and disconnects.
        if (hasCrystalSlot && slotIndex == CRYSTAL_SLOT) {
            val slot = slots[slotIndex]
            if (!slot.hasItem()) return ItemStack.EMPTY
            val stack = slot.item.copy()
            if (!playerInventory.add(stack)) return ItemStack.EMPTY
            slot.set(ItemStack.EMPTY)
            playerInventory.setChanged()
            return stack
        }
        return ItemStack.EMPTY
    }

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ContainerInput, player: Player) {
        // Capture the grid pattern BEFORE super.clicked() consumes ingredients
        if (slotId == CRAFT_OUTPUT_SLOT && !resultContainer.getItem(0).isEmpty && autoPull) {
            autoPullPattern = snapshotCraftPattern()
        }
        super.clicked(slotId, button, clickType, player)
        if (autoPullPattern != null) {
            pendingAutoPull = true
        }
    }

    /**
     * True when [invIndex] (a raw [playerInventory] index, 0–35) points at the
     * exact Portable ItemStack this menu was opened against. Used to block
     * interactions with the Portable's own slot for the duration of the menu:
     * picking it up closes the menu or leaves the state inconsistent, and
     * merging items onto it breaks the expected "this is my handheld" model.
     *
     * Always false when the menu isn't driving a Handheld (no
     * [crystalHolderProvider]), so the fixed terminal's callers are unaffected.
     * Identity comparison (`===`) ensures other Portables the player might
     * own don't get locked, only the specific stack driving this menu.
     */
    private fun isHeldPortableInvSlot(invIndex: Int): Boolean {
        val holder = crystalHolderProvider ?: return false
        if (invIndex < 0 || invIndex >= playerInventory.containerSize) return false
        val held = holder()
        if (held.isEmpty) return false
        return playerInventory.getItem(invIndex) === held
    }

    private fun snapshotCraftPattern(): List<String> {
        return (0 until craftingContainer.containerSize).map { i ->
            val stack = craftingContainer.getItem(i)
            if (stack.isEmpty) "" else net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
        }
    }

    /**
     * Handle a click on the network item grid.
     * action: 0=extract full stack, 1=insert carried, 2=extract half, 3=shift-click to inventory
     */
    fun handleGridClick(player: Player, itemId: String, action: Int) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        val c = cache

        when (action) {
            0, 2, 3 -> {
                val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return

                val available = if (c != null) c.count(itemId) else {
                    NetworkStorageHelper.countItems(lvl, snap, itemId)
                }
                val maxStack = item.getDefaultMaxStackSize().toLong()
                val toExtract = when (action) {
                    2 -> maxOf(1L, minOf(available, maxStack) / 2)
                    else -> minOf(available, maxStack)
                }

                var extracted = 0L
                for (card in NetworkStorageHelper.getStorageCards(snap)) {
                    if (extracted >= toExtract) break
                    val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
                    val amount = damien.nodeworks.platform.PlatformServices.storage.extractItems(
                        storage, { it == itemId }, toExtract - extracted
                    )
                    if (amount > 0) {
                        c?.onExtracted(itemId, false, amount)
                        extracted += amount
                    }
                }

                if (extracted > 0) {
                    val stack = ItemStack(item, extracted.toInt())
                    if (action == 3) {
                        if (!playerInventory.add(stack)) {
                            NetworkStorageHelper.insertItemStack(lvl, snap, stack, c)
                        }
                    } else {
                        val carried = carried
                        if (carried.isEmpty) {
                            setCarried(stack)
                        } else if (ItemStack.isSameItemSameComponents(carried, stack) && carried.count + stack.count <= carried.maxStackSize) {
                            carried.grow(stack.count)
                        } else {
                            NetworkStorageHelper.insertItemStack(lvl, snap, stack, c)
                        }
                    }
                }
            }
            1 -> {
                val carried = carried
                if (!carried.isEmpty) {
                    val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, carried, c)
                    if (inserted > 0) {
                        carried.shrink(inserted)
                        if (carried.isEmpty) setCarried(ItemStack.EMPTY)
                    }
                }
            }
            4 -> {
                // Right-click insert: deposit one item
                val carried = carried
                if (!carried.isEmpty) {
                    val single = carried.copyWithCount(1)
                    val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, single, c)
                    if (inserted > 0) {
                        carried.shrink(1)
                        if (carried.isEmpty) setCarried(ItemStack.EMPTY)
                    }
                }
            }
        }

        // Mark inventory dirty so the player's inventoryMenu syncs the change
        playerInventory.setChanged()
        // Force immediate network inventory sync
        needsImmediateSync = true
    }

    /**
     * Handle a fluid grid click. Fluids fill a single bucket (1000 mB) per click.
     *
     * Source of the empty bucket:
     *  - Carried is a stack of empty buckets → consume one from the cursor.
     *  - Carried is empty → take one empty bucket from network storage (must exist).
     *
     * action: 0 = put filled bucket on cursor (or merge if carried is a bucket).
     *         3 = shift-click, route filled bucket into player inventory.
     */
    fun handleFluidGridClick(player: Player, fluidId: String, action: Int) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        val c = cache

        val fluidIdentifier = net.minecraft.resources.Identifier.tryParse(fluidId) ?: return
        val fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.getValue(fluidIdentifier) ?: return
        val filledBucketItem = fluid.bucket
        if (filledBucketItem == null || filledBucketItem == net.minecraft.world.item.Items.AIR) return

        // Confirm the network has 1000 mB available.
        val available = NetworkStorageHelper.countFluid(lvl, snap, fluidId)
        if (available < BUCKET_MB) return

        val carried = carried
        val carriedIsEmptyBucket = !carried.isEmpty &&
            carried.item == net.minecraft.world.item.Items.BUCKET &&
            carried.count > 0

        // Step 1, source the empty bucket. Either the cursor stack or network storage.
        val bucketSource: BucketSource = when {
            carriedIsEmptyBucket -> BucketSource.CURSOR
            carried.isEmpty -> {
                // Check network for an empty bucket before committing to the drain.
                val emptyBucketId = "minecraft:bucket"
                val emptyAvailable = if (c != null) c.count(emptyBucketId) else
                    NetworkStorageHelper.countItems(lvl, snap, emptyBucketId)
                if (emptyAvailable < 1L) return
                BucketSource.NETWORK
            }
            else -> return // carried is something we can't consume, bail.
        }

        // Step 2, pull 1000 mB from the network's fluid storage. If drain falls short,
        //  put back what we got and abort (no half-state).
        var drained = 0L
        for (card in NetworkStorageHelper.getStorageCards(snap)) {
            if (drained >= BUCKET_MB) break
            val storage = NetworkStorageHelper.getFluidStorage(lvl, card) ?: continue
            val got = damien.nodeworks.platform.PlatformServices.storage.extractFluid(
                storage, { it == fluidId }, BUCKET_MB - drained
            )
            if (got > 0) {
                c?.onFluidExtracted(fluidId, got)
                drained += got
            }
        }
        if (drained < BUCKET_MB) {
            // Roll back partial drain, push back into any fluid storage that will accept it.
            if (drained > 0) NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, drained, c)
            return
        }

        // Step 3, commit the bucket source (consume the empty bucket for real).
        when (bucketSource) {
            BucketSource.CURSOR -> carried.shrink(1)
            BucketSource.NETWORK -> {
                val emptyBucketId = "minecraft:bucket"
                var consumed = 0L
                for (card in NetworkStorageHelper.getStorageCards(snap)) {
                    if (consumed >= 1L) break
                    val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
                    val got = damien.nodeworks.platform.PlatformServices.storage.extractItems(
                        storage, { it == emptyBucketId }, 1L - consumed
                    )
                    if (got > 0) {
                        c?.onExtracted(emptyBucketId, false, got)
                        consumed += got
                    }
                }
                if (consumed < 1L) {
                    // Bucket vanished between pre-check and commit (another player extracted it).
                    // Return the drained fluid and bail.
                    NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, BUCKET_MB, c)
                    return
                }
            }
        }

        // Step 4, deliver the filled bucket.
        val filled = ItemStack(filledBucketItem)
        if (action == 3) {
            if (!playerInventory.add(filled)) {
                // No room, drop as cursor item instead of leaking into the world.
                if (carried.isEmpty) {
                    setCarried(filled)
                } else {
                    // Last-ditch: put the fluid back so the player can try again with space.
                    NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, BUCKET_MB, c)
                    if (bucketSource == BucketSource.CURSOR) carried.grow(1)
                    else NetworkStorageHelper.insertItemStack(lvl, snap, ItemStack(net.minecraft.world.item.Items.BUCKET), c)
                    return
                }
            }
        } else {
            if (carried.isEmpty) {
                setCarried(filled)
            } else if (ItemStack.isSameItemSameComponents(carried, filled) &&
                carried.count + 1 <= carried.maxStackSize) {
                carried.grow(1)
            } else {
                // Can't stack onto carried, try to put into inventory, else rollback.
                if (!playerInventory.add(filled)) {
                    NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, BUCKET_MB)
                    if (bucketSource == BucketSource.CURSOR) carried.grow(1)
                    else NetworkStorageHelper.insertItemStack(lvl, snap, ItemStack(net.minecraft.world.item.Items.BUCKET), c)
                    return
                }
            }
        }

        playerInventory.setChanged()
        needsImmediateSync = true
    }

    private enum class BucketSource { CURSOR, NETWORK }

    /**
     * Handle a click on a player inventory slot.
     * action: 0=left click, 1=right click, 2=shift-click (insert into network)
     */
    fun handlePlayerSlotClick(player: Player, slotIndex: Int, action: Int) {
        if (slotIndex < 0 || slotIndex >= 36) return

        // Map virtual slot index to actual inventory index
        // Virtual: 0-26 = main inventory (inv slots 9-35), 27-35 = hotbar (inv slots 0-8)
        val invIndex = if (slotIndex < 27) slotIndex + 9 else slotIndex - 27

        // Block interaction with the Portable's own slot, the stack the
        // Handheld was opened against. Picking it up mid-session would either
        // close the menu immediately (stillValid sees empty hand) or leave a
        // confusing half-state, just refuse the click entirely. Uses identity
        // (`===`) so other Portables the player might own aren't affected.
        if (isHeldPortableInvSlot(invIndex)) return

        when (action) {
            0 -> { // Left click, swap with carried
                val slotStack = playerInventory.getItem(invIndex)
                val carried = carried
                if (carried.isEmpty && !slotStack.isEmpty) {
                    setCarried(slotStack.copy())
                    playerInventory.setItem(invIndex, ItemStack.EMPTY)
                } else if (!carried.isEmpty && slotStack.isEmpty) {
                    playerInventory.setItem(invIndex, carried.copy())
                    setCarried(ItemStack.EMPTY)
                } else if (!carried.isEmpty && !slotStack.isEmpty) {
                    if (ItemStack.isSameItemSameComponents(carried, slotStack) && slotStack.count < slotStack.maxStackSize) {
                        val space = slotStack.maxStackSize - slotStack.count
                        val toMove = minOf(space, carried.count)
                        slotStack.grow(toMove)
                        carried.shrink(toMove)
                        if (carried.isEmpty) setCarried(ItemStack.EMPTY)
                    } else {
                        // Swap
                        playerInventory.setItem(invIndex, carried.copy())
                        setCarried(slotStack.copy())
                    }
                }
            }
            1 -> { // Right click, pick up half or place one
                val slotStack = playerInventory.getItem(invIndex)
                val carried = carried
                if (carried.isEmpty && !slotStack.isEmpty) {
                    val half = (slotStack.count + 1) / 2
                    val picked = slotStack.copyWithCount(half)
                    slotStack.shrink(half)
                    if (slotStack.isEmpty) playerInventory.setItem(invIndex, ItemStack.EMPTY)
                    setCarried(picked)
                } else if (!carried.isEmpty && (slotStack.isEmpty || ItemStack.isSameItemSameComponents(carried, slotStack))) {
                    // Place one item
                    if (slotStack.isEmpty) {
                        playerInventory.setItem(invIndex, carried.copyWithCount(1))
                    } else if (slotStack.count < slotStack.maxStackSize) {
                        slotStack.grow(1)
                    } else return
                    carried.shrink(1)
                    if (carried.isEmpty) setCarried(ItemStack.EMPTY)
                }
            }
            2 -> { // Shift-click, crystal → crystal slot (Handheld), else network
                val slotStack = playerInventory.getItem(invIndex)
                if (!slotStack.isEmpty) {
                    val cContainer = crystalContainer
                    // Handheld shortcut: if the player shift-clicks a Link Crystal
                    // and the crystal slot is empty, route it there rather than
                    // trying to stuff a filter-locked item into network storage.
                    // Reverse direction (crystal slot → inventory) is handled by
                    // quickMoveStack.
                    if (hasCrystalSlot && cContainer != null &&
                        slotStack.item is damien.nodeworks.item.LinkCrystalItem &&
                        cContainer.getItem(0).isEmpty
                    ) {
                        cContainer.setItem(0, slotStack.copy())
                        playerInventory.setItem(invIndex, ItemStack.EMPTY)
                    } else {
                        val lvl = serverLevel
                        val snap = snapshot
                        if (lvl != null && snap != null) {
                            val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, slotStack, cache)
                            if (inserted > 0) {
                                slotStack.shrink(inserted)
                                if (slotStack.isEmpty) playerInventory.setItem(invIndex, ItemStack.EMPTY)
                            }
                        }
                    }
                }
            }
        }

        // Mark inventory dirty so the player's inventoryMenu syncs the change
        playerInventory.setChanged()
        // Force immediate network inventory sync if we inserted into network
        if (action == 2) needsImmediateSync = true
    }

    /** Fill the crafting grid from a JEI recipe transfer. The server
     *  re-resolves the recipe by id to get the authoritative `Ingredient`
     *  list (with full tag expansion), then for each grid slot picks an
     *  item it can actually source. Player inventory wins over network so
     *  pocketed items get used before triggering a network round-trip. */
    fun handleCraftGridFill(
        player: Player,
        recipeId: net.minecraft.resources.Identifier?,
        fallback: List<String>,
    ) {
        for (i in 0 until craftingContainer.containerSize) {
            val stack = craftingContainer.getItem(i)
            if (!stack.isEmpty) {
                if (!playerInventory.add(stack.copy())) {
                    player.drop(stack, false)
                }
                craftingContainer.setItem(i, ItemStack.EMPTY)
            }
        }

        val ingredients = resolveRecipeIngredients(recipeId)
        // Track claimed ingredients so a single `#planks` entry doesn't
        // get greedy-matched to all 8 slots of a chest pattern, leaving
        // the rest empty.
        val claimed = BooleanArray(ingredients.size)
        for (slot in 0 until 9) {
            if (slot >= craftingContainer.containerSize) break
            val fallbackId = fallback.getOrNull(slot).orEmpty()
            if (fallbackId.isEmpty()) continue
            val taken = takeOneOf(pickCandidatesFor(fallbackId, ingredients, claimed)) ?: continue
            craftingContainer.setItem(slot, taken)
        }

        slotsChanged(craftingContainer)
        playerInventory.setChanged()
    }

    /** Resolve a recipe id to its placement-info ingredient list. Empty for
     *  null / unknown / non-crafting / unplaceable, the caller falls back to
     *  per-slot single-item resolution in those cases. */
    private fun resolveRecipeIngredients(
        recipeId: net.minecraft.resources.Identifier?,
    ): List<net.minecraft.world.item.crafting.Ingredient> {
        if (recipeId == null) return emptyList()
        val lvl = serverLevel ?: return emptyList()
        val key = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.RECIPE,
            recipeId,
        )
        val holder = lvl.recipeAccess().byKey(key).orElse(null) ?: return emptyList()
        val recipe = holder.value() as? net.minecraft.world.item.crafting.CraftingRecipe ?: return emptyList()
        return recipe.placementInfo().ingredients()
    }

    /** Candidate item ids for a grid slot. Prefers the recipe's tag-
     *  expanded ingredient set when one accepts the fallback item, marking
     *  it claimed so the next slot moves to a different ingredient. Falls
     *  back to the single fallback id for JEI-synthetic recipes where the
     *  ingredient list doesn't cover the displayed item. */
    private fun pickCandidatesFor(
        fallbackId: String,
        ingredients: List<net.minecraft.world.item.crafting.Ingredient>,
        claimed: BooleanArray,
    ): List<String> {
        val id = net.minecraft.resources.Identifier.tryParse(fallbackId) ?: return listOf(fallbackId)
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: return listOf(fallbackId)
        val probe = ItemStack(item)
        for (ingredientIdx in ingredients.indices) {
            if (claimed[ingredientIdx]) continue
            val ingredient = ingredients[ingredientIdx]
            if (!ingredient.test(probe)) continue
            claimed[ingredientIdx] = true
            // `items()` is deprecated in 26.1, but it's the only public path
            // that enumerates the accepted item set. `acceptsItem(holder)`
            // would need every item id fed in one by one.
            @Suppress("DEPRECATION")
            return ingredient.items().toList()
                .mapNotNull { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.value())?.toString() }
                .distinct()
        }
        return listOf(fallbackId)
    }

    /** Try each candidate id in order and return a 1-count [ItemStack] the
     *  moment one resolves. Mutates the source inventory / network in place,
     *  so successive calls see the running total drained: candidate
     *  `oak_planks` resolves until oak runs out, then `birch_planks` picks
     *  up the rest. */
    private fun takeOneOf(candidates: List<String>): ItemStack? {
        for (itemId in candidates) {
            if (itemId.isEmpty()) continue
            val id = net.minecraft.resources.Identifier.tryParse(itemId) ?: continue
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: continue

            val invSlot = playerInventory.findSlotMatchingItem(ItemStack(item))
            if (invSlot >= 0) {
                return playerInventory.getItem(invSlot).split(1)
            }

            val lvl = serverLevel ?: continue
            val snap = snapshot ?: continue
            val c = cache
            var extracted = 0L
            for (card in NetworkStorageHelper.getStorageCards(snap)) {
                if (extracted >= 1) break
                val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
                val amount = damien.nodeworks.platform.PlatformServices.storage.extractItems(
                    storage, { it == itemId }, 1
                )
                if (amount > 0) {
                    c?.onExtracted(itemId, false, amount)
                    extracted += amount
                }
            }
            if (extracted > 0) return ItemStack(item, 1)
        }
        return null
    }

    /**
     * Crafting grid utility actions.
     * action 0 = distribute/balance items evenly across slots of the same type
     * action 1 = clear grid, depositing all items into network storage
     */
    fun handleCraftGridAction(player: Player, action: Int) {
        when (action) {
            0 -> {
                // Distribute: group slots by item type, split evenly within each group
                val groups = mutableMapOf<String, MutableList<Int>>() // itemKey → slot indices
                for (i in 0 until craftingContainer.containerSize) {
                    val stack = craftingContainer.getItem(i)
                    if (!stack.isEmpty) {
                        val key = ItemStack.hashItemAndComponents(stack).toString()
                        groups.getOrPut(key) { mutableListOf() }.add(i)
                    }
                }
                for ((_, slotIndices) in groups) {
                    if (slotIndices.size < 2) continue
                    val template = craftingContainer.getItem(slotIndices[0])
                    val total = slotIndices.sumOf { craftingContainer.getItem(it).count }
                    val perSlot = total / slotIndices.size
                    val remainder = total % slotIndices.size
                    for ((idx, slotIndex) in slotIndices.withIndex()) {
                        val amount = perSlot + if (idx < remainder) 1 else 0
                        craftingContainer.setItem(slotIndex, template.copyWithCount(amount))
                    }
                }
                slotsChanged(craftingContainer)
            }
            1 -> {
                // Clear: deposit all crafting grid items into network storage
                val lvl = serverLevel ?: return
                val snap = snapshot ?: return
                for (i in 0 until craftingContainer.containerSize) {
                    val stack = craftingContainer.getItem(i)
                    if (!stack.isEmpty) {
                        val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache)
                        if (inserted > 0) {
                            stack.shrink(inserted)
                            if (stack.isEmpty) craftingContainer.setItem(i, ItemStack.EMPTY)
                        }
                    }
                }
                slotsChanged(craftingContainer)
                needsImmediateSync = true
            }
            2 -> {
                // Toggle auto-pull
                autoPull = !autoPull
            }
        }
    }

    /**
     * After a crafting result is taken, refill empty grid slots from network storage.
     * Only runs when autoPull is enabled.
     */
    private fun autoPullRefill() {
        if (!autoPull) return
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        val c = cache
        val pattern = autoPullPattern ?: return
        autoPullPattern = null

        // Suppress slotsChanged during refill, intermediate states can match
        // different recipes and corrupt lastCraftPattern
        suppressSlotsChanged = true
        try {
            for ((i, itemId) in pattern.withIndex()) {
                if (itemId.isEmpty()) continue
                val current = craftingContainer.getItem(i)
                if (!current.isEmpty) continue

                var extracted = 0L
                for (card in NetworkStorageHelper.getStorageCards(snap)) {
                    if (extracted >= 1) break
                    val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
                    val amount = damien.nodeworks.platform.PlatformServices.storage.extractItems(
                        storage, { it == itemId }, 1
                    )
                    if (amount > 0) {
                        c?.onExtracted(itemId, false, amount)
                        extracted += amount
                    }
                }
                if (extracted > 0) {
                    val id = net.minecraft.resources.Identifier.tryParse(itemId) ?: continue
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: continue
                    craftingContainer.setItem(i, ItemStack(item, 1))
                }
            }
        } finally {
            suppressSlotsChanged = false
        }

        // Single slotsChanged call with the final grid state
        slotsChanged(craftingContainer)
        needsImmediateSync = true
    }

    /**
     * Distribute the carried item evenly across the specified crafting slot indices.
     * Used for left-click drag.
     */
    /**
     * Distribute carried item evenly across slots.
     * slotType 0 = crafting grid (slot indices are menu slot indices)
     * slotType 1 = player inventory (slot indices are virtual: 0-26=main, 27-35=hotbar)
     */
    fun handleDistribute(player: Player, slotType: Int, slotIndices: List<Int>) {
        val carried = carried
        if (carried.isEmpty || slotIndices.isEmpty()) return

        val total = carried.count
        val count = slotIndices.size
        val perSlot = total / count
        val remainder = total % count
        if (perSlot <= 0 && remainder <= 0) return

        var distributed = 0
        for ((idx, slotIndex) in slotIndices.withIndex()) {
            val amount = perSlot + if (idx < remainder) 1 else 0
            if (amount <= 0) continue

            when (slotType) {
                0 -> {
                    // Crafting grid
                    if (slotIndex !in CRAFT_INPUT_START until CRAFT_OUTPUT_SLOT) continue
                    val slot = slots[slotIndex]
                    val existing = slot.item
                    if (existing.isEmpty) {
                        slot.set(carried.copyWithCount(amount))
                    } else if (ItemStack.isSameItemSameComponents(existing, carried) && existing.count + amount <= existing.maxStackSize) {
                        existing.grow(amount)
                    } else continue
                }
                1 -> {
                    // Player inventory (virtual index → real inv index)
                    if (slotIndex < 0 || slotIndex >= 36) continue
                    val invIndex = if (slotIndex < 27) slotIndex + 9 else slotIndex - 27
                    // Skip the Portable's own slot (Handheld only), the drag
                    // shouldn't merge items onto the stack driving this menu.
                    if (isHeldPortableInvSlot(invIndex)) continue
                    val existing = playerInventory.getItem(invIndex)
                    if (existing.isEmpty) {
                        playerInventory.setItem(invIndex, carried.copyWithCount(amount))
                    } else if (ItemStack.isSameItemSameComponents(existing, carried) && existing.count + amount <= existing.maxStackSize) {
                        existing.grow(amount)
                    } else continue
                }
                else -> continue
            }
            distributed += amount
        }

        carried.shrink(distributed)
        if (carried.isEmpty) setCarried(ItemStack.EMPTY)
        if (slotType == 0) slotsChanged(craftingContainer)
        playerInventory.setChanged()
    }

    /**
     * Double-click collect: gather all matching items from crafting grid and player inventory onto cursor.
     */
    fun handleCollect(player: Player, itemId: String) {
        val carried = carried
        val id = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: return
        val maxStack = item.getDefaultMaxStackSize()

        // Start with what's on cursor (may already have items from first click)
        val result: ItemStack
        if (carried.isEmpty) {
            result = ItemStack(item, 0)
            setCarried(result)
        } else if (ItemStack.isSameItem(carried, ItemStack(item))) {
            result = carried
        } else {
            return // different item on cursor
        }

        // Collect from crafting grid
        for (i in 0 until craftingContainer.containerSize) {
            if (result.count >= maxStack) break
            val stack = craftingContainer.getItem(i)
            if (!stack.isEmpty && ItemStack.isSameItemSameComponents(result, stack)) {
                val take = minOf(stack.count, maxStack - result.count)
                result.grow(take)
                stack.shrink(take)
                if (stack.isEmpty) craftingContainer.setItem(i, ItemStack.EMPTY)
            }
        }

        // Collect from player inventory
        for (i in 0 until playerInventory.containerSize) {
            if (result.count >= maxStack) break
            // Skip the Portable's own slot (Handheld only), we don't want to
            // vacuum the stack that's driving this menu into the cursor.
            if (isHeldPortableInvSlot(i)) continue
            val stack = playerInventory.getItem(i)
            if (!stack.isEmpty && ItemStack.isSameItemSameComponents(result, stack)) {
                val take = minOf(stack.count, maxStack - result.count)
                result.grow(take)
                stack.shrink(take)
                if (stack.isEmpty) playerInventory.setItem(i, ItemStack.EMPTY)
            }
        }

        setCarried(result)
        slotsChanged(craftingContainer)
        playerInventory.setChanged()
    }

    /**
     * Handle an automated craft request (Alt+click).
     * Finds a CraftingCore, allocates it, and initiates crafting via CraftingHelper.
     */
    /**
     * Handle an automated craft request (Alt+click).
     * Mirrors the scripting terminal's network:craft(id, n):store() flow exactly:
     *   1. CraftingHelper.craft() finds a CPU, extracts ingredients, crafts (sync or async)
     *   2. Items stay in CPU buffer throughout
     *   3. releaseCraftResult flushes buffer → network and releases CPU
     */
    fun handleCraftRequest(player: Player, itemId: String, count: Int) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        if (count <= 0 || count > 999) return

        val itemName = net.minecraft.resources.Identifier.tryParse(itemId)?.path?.replace('_', ' ') ?: itemId

        // Create queue entry (pending until the whole job completes)
        val entry = CraftQueueManager.addEntry(player.uuid, itemId, itemName, count)

        try {
            // CraftingHelper.craft does feasibility-aware CPU selection across every CPU on
            // the network. On failure, lastFailReason carries the player-facing message.
            damien.nodeworks.script.CraftingHelper.currentPendingJob = null
            val result = damien.nodeworks.script.CraftingHelper.craft(
                itemId, count, lvl, snap,
                cache = cache,
                submitterUuid = player.uuid
            )
            val pending = damien.nodeworks.script.CraftingHelper.currentPendingJob
            damien.nodeworks.script.CraftingHelper.currentPendingJob = null

            if (result == null && pending == null) {
                // Total failure, no feasible CPU, missing recipe, etc.
                CraftQueueManager.getQueue(player.uuid).remove(entry)
                val reason = damien.nodeworks.script.CraftingHelper.lastFailReason
                sendCraftError(player, reason ?: "Failed to start craft.")
                needsImmediateSync = true
                return
            }

            // Recipes with output count > 1 (e.g. ingot → 9 nuggets) and processing handlers
            // that batch outputs actually deliver at least one full batch. Reflect that in
            // the queue entry so the reserved slot shows the real delivered count.
            if (result != null && result.count > entry.totalRequested) {
                entry.totalRequested = result.count
                entry.dirty = true
            }

            // Build a CraftResult for the release, same as the scripting terminal
            val craftResult = result ?: run {
                val id = net.minecraft.resources.Identifier.tryParse(itemId)
                val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) else null
                val name = if (item != null) ItemStack(item).hoverName.string else itemId
                damien.nodeworks.script.CraftingHelper.CraftResult(
                    itemId, name, count,
                    cpu = snap.cpus.firstOrNull()?.let { lvl.getBlockEntity(it.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity },
                    level = lvl, snapshot = snap, cache = cache
                )
            }

            if (pending == null || pending.isComplete) {
                // Synchronous, release immediately (flush buffer → network, free CPU)
                damien.nodeworks.script.CraftingHelper.releaseCraftResult(craftResult)
                if (pending == null || pending.success) {
                    entry.completedOps = 1
                } else {
                    CraftQueueManager.getQueue(player.uuid).remove(entry)
                }
                entry.dirty = true
            } else {
                // Async, release when the pending job completes (same as :store())
                pending.onCompleteCallback = { success ->
                    damien.nodeworks.script.CraftingHelper.releaseCraftResult(craftResult)
                    if (success) {
                        entry.completedOps = 1
                    } else {
                        // Cancelled or failed, drop the entry so nothing can be extracted
                        CraftQueueManager.getQueue(player.uuid).remove(entry)
                    }
                    entry.dirty = true
                    needsImmediateSync = true
                }
            }
        } catch (e: Exception) {
            CraftQueueManager.getQueue(player.uuid).remove(entry)
        }

        needsImmediateSync = true
    }

    /** Send a craft rejection message to the client for display in the craft prompt. */
    private fun sendCraftError(player: Player, message: String) {
        val serverPlayer = player as? ServerPlayer ?: return
        serverPlayer.connection.send(
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                damien.nodeworks.network.CraftRequestErrorPayload(containerId, message)
            )
        )
    }

    /**
     * Per-tick validity. Two policies:
     *   * Handheld (crystalHolderProvider is non-null), menu stays open as long as
     *     the Portable itself is still in the player's possession. Crystal removal,
     *     range loss, antenna unloaded, etc. all just blank the grid, we only close
     *     when the Portable stack itself is gone. This lets the player stand there
     *     with an unlinked Portable open and install a crystal without the menu
     *     snapping shut on them.
     *   * Fixed terminal, delegate to the source's [isValid]. Terminals have always
     *     returned `true` unconditionally there, so this is effectively a passthrough.
     *   * Client-side copy (null source + null holder), return true, validity is
     *     decided by the server echoing the close packet.
     */
    override fun stillValid(player: Player): Boolean {
        val serverPlayer = player as? ServerPlayer ?: return true
        val holder = crystalHolderProvider
        if (holder != null) {
            val held = holder()
            return !held.isEmpty && held.item is damien.nodeworks.item.PortableInventoryTerminalItem
        }
        val src = source ?: return true
        return src.isValid(serverPlayer)
    }

    override fun removed(player: Player) {
        super.removed(player)
        drainCraftingGridOnClose(player)
        // Mark completed queue entries as seen so they're cleared on next open
        val queue = CraftQueueManager.getQueue(player.uuid)
        for (entry in queue) {
            if (entry.isComplete) {
                entry.seenComplete = true
            }
        }
    }

    /** Empty the 3x3 crafting grid on close, preferring the network's storage
     *  cards over the player's inventory so staged ingredients don't pile up
     *  in the hotbar.
     *
     *  Pre-pass shrinks each grid slot by what the network accepts. Then
     *  vanilla [clearContainer] handles the rest, which is what we need:
     *  re-implementing the player-inventory side ourselves loses the
     *  `ClientboundContainerSetSlotPacket(-2, ...)` sync that
     *  `placeItemBackInInventory` emits, and [transferState] then ships the
     *  pre-drain `remoteSlots` snapshot to the inventory menu, painting
     *  ghost items in the hotbar until the next open re-syncs. */
    private fun drainCraftingGridOnClose(player: Player) {
        val lvl = serverLevel
        val snap = snapshot
        if (lvl != null && snap != null) {
            for (i in 0 until craftingContainer.containerSize) {
                val stack = craftingContainer.getItem(i)
                if (stack.isEmpty) continue
                val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack)
                if (inserted > 0) {
                    stack.shrink(inserted)
                    if (stack.isEmpty) craftingContainer.setItem(i, ItemStack.EMPTY)
                }
            }
        }
        clearContainer(player, craftingContainer)
    }

    /**
     * Extract ready items from a craft queue slot.
     * action: 0=extract to cursor, 1=shift to inventory, 2=extract half
     */
    fun handleQueueExtract(player: Player, entryId: Int, action: Int) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        val queue = CraftQueueManager.getQueue(player.uuid)
        val entry = queue.firstOrNull { it.id == entryId } ?: return
        if (entry.availableCount <= 0) return

        val identifier = net.minecraft.resources.Identifier.tryParse(entry.itemId) ?: return
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
        val maxStack = item.getDefaultMaxStackSize().toLong()

        val toExtract = when (action) {
            2 -> maxOf(1L, minOf(entry.availableCount.toLong(), maxStack) / 2)
            else -> minOf(entry.availableCount.toLong(), maxStack)
        }

        var extracted = 0L
        for (card in NetworkStorageHelper.getStorageCards(snap)) {
            if (extracted >= toExtract) break
            val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
            val amount = damien.nodeworks.platform.PlatformServices.storage.extractItems(
                storage, { it == entry.itemId }, toExtract - extracted
            )
            if (amount > 0) {
                cache?.onExtracted(entry.itemId, false, amount)
                extracted += amount
            }
        }

        if (extracted > 0) {
            entry.takenCount += extracted.toInt()
            entry.dirty = true
            val stack = ItemStack(item, extracted.toInt())
            if (action == 1) {
                if (!playerInventory.add(stack)) {
                    NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache)
                }
            } else {
                val carried = carried
                if (carried.isEmpty) {
                    setCarried(stack)
                } else if (ItemStack.isSameItemSameComponents(carried, stack) && carried.count + stack.count <= carried.maxStackSize) {
                    carried.grow(stack.count)
                } else {
                    NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache)
                }
            }
        }

        // Remove entry if fully consumed
        if (entry.availableCount <= 0 && entry.isComplete) {
            queue.remove(entry)
        }

        // Force queue sync to client so removed/updated entries are reflected
        val serverPlayer = playerInventory.player as? ServerPlayer
        if (serverPlayer != null) {
            sendQueueSync(serverPlayer, queue)
        }

        playerInventory.setChanged()
        needsImmediateSync = true
    }

    override fun broadcastChanges() {
        // Process deferred auto-pull (must happen after MC's click packet reconciliation)
        if (pendingAutoPull) {
            pendingAutoPull = false
            autoPullRefill()
        }

        super.broadcastChanges()

        val serverPlayer = playerInventory.player as? ServerPlayer ?: return
        tickCounter++

        // Handheld recovery: once per second verify the source is still valid and
        // re-attempt resolve if the menu is disconnected. Fixed terminals skip this.
        if (crystalHolderProvider != null && tickCounter % 20 == 0) {
            tickRefreshConnection()
        }

        // Handheld status sync: any time the client-facing connection status
        // transitions, push the new value so the screen's overlay updates. Sent on
        // every tick the status changes (not throttled to the 20-tick cadence)
        // because the transition from CONNECTED → OUT_OF_RANGE is triggered by
        // `tickRefreshConnection`, and we want the overlay to snap on immediately.
        if (crystalHolderProvider != null) {
            val current = currentConnectionStatus()
            if (current != lastSentStatus) {
                serverPlayer.connection.send(
                    net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        damien.nodeworks.network.PortableConnectionStatusPayload(containerId, current.ordinal)
                    )
                )
                lastSentStatus = current
            }
        }

        val queue = CraftQueueManager.getQueue(serverPlayer.uuid)

        // On first sync: purge acknowledged entries
        if (needsFullSync) {
            queue.removeAll { it.seenComplete }
        }

        // Build reserved count deduction map
        val reserved = CraftQueueManager.getReservedCounts(serverPlayer.uuid)
        val c = cache

        if (needsFullSync) {
            // Build all entries (items + fluids) and split into chunks. When we're
            // disconnected (no cache) we still ship a single empty chunk so the
            // client replaces whatever was previously displayed with a blank grid.
            val allEntries: List<InventorySyncPayload.SyncEntry> = if (c != null) {
                val itemEntries = c.getAllEntries().map { entry ->
                    val deduct = reserved[entry.info.itemId] ?: 0
                    InventorySyncPayload.SyncEntry(
                        serial = entry.serial,
                        itemId = entry.info.itemId,
                        name = entry.info.name,
                        count = maxOf(0L, entry.info.count - deduct),
                        maxStackSize = entry.info.maxStackSize,
                        hasData = entry.info.hasData,
                        craftable = entry.info.isCraftable
                    )
                }
                val fluidEntries = c.getAllFluidEntries().map { entry ->
                    InventorySyncPayload.SyncEntry(
                        serial = entry.serial,
                        itemId = entry.info.fluidId,
                        name = entry.info.name,
                        count = entry.info.amount,
                        maxStackSize = 1,
                        hasData = false,
                        craftable = false,
                        kind = 1
                    )
                }
                itemEntries + fluidEntries
            } else {
                emptyList()
            }
            fullSyncChunks = if (allEntries.isEmpty()) listOf(emptyList()) else allEntries.chunked(FULL_SYNC_CHUNK_SIZE)
            fullSyncChunkIndex = 0
            sendQueueSync(serverPlayer, queue)
            needsFullSync = false
            // Don't return, fall through to send first chunk below
        }

        // Send one chunk per tick during a full sync
        val chunks = fullSyncChunks
        if (chunks != null) {
            val totalChunks = chunks.size.coerceAtLeast(1)
            val chunk = if (fullSyncChunkIndex < chunks.size) chunks[fullSyncChunkIndex] else emptyList()
            sendToClient(serverPlayer, InventorySyncPayload(
                fullUpdate = true,
                entries = chunk,
                removedSerials = emptyList(),
                chunkIndex = fullSyncChunkIndex,
                totalChunks = totalChunks
            ))
            fullSyncChunkIndex++
            if (fullSyncChunkIndex >= totalChunks) {
                fullSyncChunks = null // done
            }
            return
        }

        // Disconnected menus have nothing further to sync this tick. The periodic
        // refresh above will re-resolve when conditions change.
        if (c == null) return

        val immediate = needsImmediateSync
        needsImmediateSync = false

        // Send queue updates if any entries are dirty
        val queueDirty = queue.any { it.dirty }
        if (queueDirty) {
            sendQueueSync(serverPlayer, queue)
        }

        if (!immediate && tickCounter % 5 != 0) return
        if (!c.hasChanges() && !queueDirty) return

        if (c.hasChanges()) {
            val (changed, removed) = c.consumeChanges()
            val changedFluids = c.consumeFluidChanges()
            val itemEntries = changed.map { entry ->
                val deduct = reserved[entry.info.itemId] ?: 0
                InventorySyncPayload.SyncEntry(
                    serial = entry.serial,
                    itemId = entry.info.itemId,
                    name = entry.info.name,
                    count = maxOf(0L, entry.info.count - deduct),
                    maxStackSize = entry.info.maxStackSize,
                    hasData = entry.info.hasData,
                    craftable = entry.info.isCraftable
                )
            }
            val fluidEntries = changedFluids.map { entry ->
                InventorySyncPayload.SyncEntry(
                    serial = entry.serial,
                    itemId = entry.info.fluidId,
                    name = entry.info.name,
                    count = entry.info.amount,
                    maxStackSize = 1,
                    hasData = false,
                    craftable = false,
                    kind = 1
                )
            }
            sendToClient(serverPlayer, InventorySyncPayload(false, itemEntries + fluidEntries, removed))
        }
    }

    private fun sendQueueSync(player: ServerPlayer, queue: List<CraftQueueManager.CraftQueueEntry>) {
        val entries = queue.map { e ->
            val readyCount = if (e.isComplete) e.totalRequested else 0
            damien.nodeworks.network.CraftQueueSyncPayload.QueueEntry(
                id = e.id, itemId = e.itemId, name = e.itemName,
                totalRequested = e.totalRequested, readyCount = readyCount,
                availableCount = e.availableCount, isComplete = e.isComplete
            )
        }
        for (e in queue) e.dirty = false
        player.connection.send(
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                damien.nodeworks.network.CraftQueueSyncPayload(containerId, entries)
            )
        )
    }

    private fun sendToClient(player: ServerPlayer, payload: InventorySyncPayload) {
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
    }
}
