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

    /** Channel the grid shows and every extract and insert goes through. Starts on all
     *  channels each time the terminal opens. Set on the server by [selectChannel], and
     *  mirrored on the client copy by the screen. */
    var channel: damien.nodeworks.network.ChannelFilter = damien.nodeworks.network.ChannelFilter.All

    /** Entries last sent while filtered to one channel, by serial, so changes go out as
     *  deltas. Null when showing all channels. */
    private var filteredSent: HashMap<Long, InventorySyncPayload.SyncEntry>? = null
    private var filteredVersion = -1L

    /** Set on connect so the next broadcast tells the client which network it's on,
     *  so its picker shows the channel and later changes save under that network. */
    private var networkNeedsSync = false

    private var requestedChannel: damien.nodeworks.network.ChannelFilter? = null
    private var lastChannelSwitch = -CHANNEL_SWITCH_TICKS

    /** Client copy only: the network the server said this menu is on. */
    var networkId: java.util.UUID? = null
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
    // Template of the stack the last shift-click moved into the network. A shift
    // double-click sweeps everything matching it, the clicked slot is already empty.
    private var lastShiftInserted: ItemStack = ItemStack.EMPTY
    private var autoPullPattern: List<ItemStack>? = null  // captured right before craft output is taken
    private var suppressSlotsChanged = false
    // Scoped to one [clicked] call. Any other route into [quickMoveStack]
    // (other mods, vanilla paths) gets the one-stack default.
    private var craftAllOnQuickMove = false

    /** The grid's last recipe match, tried first on every grid change so a run of
     *  identical crafts never rescans the recipe list. */
    private val craftingCheck = net.minecraft.world.item.crafting.RecipeManager
        .createCheck<net.minecraft.world.item.crafting.CraftingInput, CraftingRecipe>(RecipeType.CRAFTING)
    private var lastRecipe: net.minecraft.world.item.crafting.RecipeHolder<CraftingRecipe>? = null

    /** True while a batch of crafts runs. Each grid change inside would otherwise
     *  broadcast every slot and sync the terminal on its own. One sync follows the batch. */
    private var batchCrafting = false

    /** A craft-all that used up its slice of the tick with more to craft. Carried on a
     *  slice per tick by [broadcastChanges] until the recipe stops matching, the inventory
     *  is full, or the player does anything else. */
    private var craftAllResult: ItemStack? = null
        set(value) {
            if ((field == null) != (value == null)) activeCraftAll += if (value != null) 1 else -1
            field = value
        }

    /** True while vanilla's slot change hook runs. A craft-all must not continue from
     *  inside it, that would craft in the middle of whatever changed the slot. */
    private var inSlotsChanged = false
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
        addSlot(TerminalResultSlot(playerInventory.player))

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
                val recipe = craftingCheck.getRecipeFor(craftingContainer.asCraftInput(), level)
                lastRecipe = recipe.orElse(null)
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
        inSlotsChanged = true
        try {
            super.slotsChanged(container)
        } finally {
            inSlotsChanged = false
        }
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
        // Open on the channel this player's client saved for the network, no round trip.
        channel = InvTerminalChannelPrefs.get(playerInventory.player.uuid, snapshot?.networkId)
        filteredSent = null
        networkNeedsSync = true
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
    /** Ask to show [filter]. Applied by [applyRequestedChannel] on the next broadcast. */
    fun selectChannel(filter: damien.nodeworks.network.ChannelFilter) {
        requestedChannel = filter
    }

    /** Switch to the requested channel, re-sending the whole grid for it. A switch costs a
     *  full rebuild, so a client spamming them gets the latest one at most every
     *  [CHANNEL_SWITCH_TICKS], the first one is still immediate. */
    private fun applyRequestedChannel() {
        val filter = requestedChannel ?: return
        if (tickCounter - lastChannelSwitch < CHANNEL_SWITCH_TICKS) return
        requestedChannel = null
        if (filter == channel) return
        lastChannelSwitch = tickCounter
        channel = filter
        InvTerminalChannelPrefs.set(playerInventory.player.uuid, snapshot?.networkId, filter)
        filteredSent = null
        fullSyncChunks = null
        fullSyncChunkIndex = 0
        needsFullSync = true
    }

    private fun channelCards(snap: damien.nodeworks.network.NetworkSnapshot) =
        NetworkStorageHelper.getStorageCards(snap).filter { channel.matches(it.channel) }

    /** [itemId] across all variants in the selected channel, read from the cache. */
    private fun cachedCount(c: NetworkInventoryCache, itemId: String): Long = when (val ch = channel) {
        damien.nodeworks.network.ChannelFilter.All -> c.count(itemId)
        is damien.nodeworks.network.ChannelFilter.Color -> c.channelCount(ch.color, itemId)
    }

    /** One exact variant of [itemId] in the selected channel, plain when [patch] is empty.
     *  Read from the cache, falling back to a storage walk without one. */
    private fun variantCount(
        c: NetworkInventoryCache?,
        lvl: ServerLevel,
        snap: damien.nodeworks.network.NetworkSnapshot,
        itemId: String,
        patch: net.minecraft.core.component.DataComponentPatch,
    ): Long {
        if (c == null) return NetworkStorageHelper.countVariantAcrossNetwork(lvl, snap, itemId, patch, channel = channel)
        val key = damien.nodeworks.script.BufferKey.Key(itemId, damien.nodeworks.script.BufferKey.componentsHash(patch))
        return when (val ch = channel) {
            damien.nodeworks.network.ChannelFilter.All -> c.variantCount(key)
            is damien.nodeworks.network.ChannelFilter.Color -> c.channelItemCounts(ch.color)[key] ?: 0L
        }
    }

    /** The stored variant a clicked cell's [patch] refers to.
     *
     *  The fast [damien.nodeworks.script.BufferKey.componentsHash] compares the live
     *  component objects, and a component that holds a registry tag set (the `tool`
     *  component's mineable rules, for one) compares that set by object identity. A
     *  tool built in-session by a mod can carry a tag set that is a different object
     *  from the one the client's click decodes to, with identical NBT, so the cell
     *  shows the tool but every pull misses. Reloading the world re-decodes the stored
     *  tool and the mismatch vanishes, which is why such items only work after a
     *  reload.
     *
     *  When the cache has no entry under [patch]'s own hash, the cache's other
     *  variants of the item are compared by their encoded NBT and the matching stored
     *  patch is returned, so count and extract then run their normal fast path
     *  against the hash the stored stacks really have. Nothing in storage is scanned,
     *  the request is encoded once, and the common path is a single map lookup. */
    private fun resolveVariant(
        c: NetworkInventoryCache?,
        lvl: ServerLevel,
        itemId: String,
        patch: net.minecraft.core.component.DataComponentPatch,
    ): net.minecraft.core.component.DataComponentPatch {
        if (c == null || patch.size() == 0) return patch
        val key = damien.nodeworks.script.BufferKey.Key(itemId, damien.nodeworks.script.BufferKey.componentsHash(patch))
        if (c.variantCount(key) > 0L) return patch
        val candidates = c.variantsOf(itemId)
        if (candidates.isEmpty()) return patch
        val registries = lvl.registryAccess()
        val want = damien.nodeworks.script.BufferKey.stableComponentsHash(patch, registries)
        return candidates.firstOrNull {
            damien.nodeworks.script.BufferKey.stableComponentsHash(it.componentsPatch, registries) == want
        }?.componentsPatch ?: patch
    }

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
    /** The registry can replace a cache underneath us when its network's id moves to
     *  another anchor or a controller, or when an orphan is evicted. Runs once a second
     *  for every terminal, fixed or handheld, so the view never freezes on a cache
     *  nobody polls any more. Looks the cache up without reseating its entry point,
     *  two terminals on one network must not take turns forcing rescans. */
    private fun refreshCacheBinding() {
        val lvl = serverLevel ?: return
        val src = source ?: return
        val current = cache ?: return
        val live = NetworkInventoryCache.existing(lvl, src.entryPoint)
            ?: NetworkInventoryCache.getOrCreate(lvl, src.entryPoint)
        if (live !== current) {
            cache = live
            networkNeedsSync = true
            // A new cache numbers its rows from one again, so the client gets the grid afresh.
            needsFullSync = true
            fullSyncChunks = null
        }
    }

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

    /** The vanilla result slot finds the recipe again on every take to learn its
     *  remainders. The grid's last match is at hand, so a take that still matches it
     *  uses that, and only a grid that no longer matches pays for a fresh lookup. The
     *  remainder placement is vanilla's, step for step. */
    private inner class TerminalResultSlot(player: Player) :
        ResultSlot(player, craftingContainer, resultContainer, 0, -999, -999) {

        override fun onTake(player: Player, carried: ItemStack) {
            val lvl = serverLevel
            val recipe = lastRecipe
            val positioned = craftingContainer.asPositionedCraftInput()
            val input = positioned.input()
            if (lvl == null || recipe == null || !recipe.value().matches(input, lvl)) {
                super.onTake(player, carried)
                return
            }
            checkTakeAchievements(carried)
            val remaining = damien.nodeworks.platform.PlatformServices.crafting.withCraftingPlayer(player) { recipe.value().getRemainingItems(input) }
            // One grid change at the end instead of one per consumed slot.
            suppressSlotsChanged = true
            try {
                for (y in 0 until input.height()) {
                    for (x in 0 until input.width()) {
                        val slot = x + positioned.left() + (y + positioned.top()) * craftingContainer.width
                        var stack = craftingContainer.getItem(slot)
                        val replacement = remaining[x + y * input.width()]
                        if (!stack.isEmpty) {
                            craftingContainer.removeItem(slot, 1)
                            stack = craftingContainer.getItem(slot)
                        }
                        if (replacement.isEmpty) continue
                        if (stack.isEmpty) {
                            craftingContainer.setItem(slot, replacement)
                        } else if (ItemStack.isSameItemSameComponents(stack, replacement)) {
                            replacement.grow(stack.count)
                            craftingContainer.setItem(slot, replacement)
                        } else if (!player.inventory.add(replacement)) {
                            player.drop(replacement, false)
                        }
                    }
                }
            } finally {
                suppressSlotsChanged = false
            }
            slotsChanged(craftingContainer)
        }
    }

    companion object {
        private const val CHANNEL_SWITCH_TICKS = 5

        const val PLAYER_SLOT_COUNT = 36
        const val CRAFT_INPUT_START = 36
        const val CRAFT_OUTPUT_SLOT = 45
        /** Link crystal slot index for Handheld menus. Absent (no slot) for fixed
         *  terminals, only valid when `menu.hasCrystalSlot` is true. */
        const val CRYSTAL_SLOT = 46
        /** QUICK_MOVE button on [CRAFT_OUTPUT_SLOT] meaning "craft as many as fit"
         *  (Ctrl+Shift-click). Buttons 0/1 craft one stack's worth. Vanilla only
         *  acts on 0/1, so [clicked] maps this back to 0 before delegating. */
        const val CRAFT_ALL_BUTTON = 2

        /** Time one menu may spend crafting in a tick when it is the only one. A craft-all
         *  that needs more carries on next tick, so a huge order never stalls the server. */
        private const val CRAFT_BATCH_NANOS = 4_000_000L

        /** Time every craft-all on the server shares per tick, and the least one gets,
         *  so fifty players crafting at once cannot each take their own slice. */
        private const val CRAFT_ALL_TOTAL_NANOS = 8_000_000L
        private const val CRAFT_BATCH_MIN_NANOS = 500_000L

        /** Menus with a craft-all in progress. Server thread only. */
        private var activeCraftAll = 0

        private fun craftSliceNanos(): Long =
            (CRAFT_ALL_TOTAL_NANOS / activeCraftAll.coerceAtLeast(1)).coerceIn(CRAFT_BATCH_MIN_NANOS, CRAFT_BATCH_NANOS)

        /** Crafts one batch may run, a bound on a grid that never stops matching. */
        private const val CRAFT_BATCH_SAFETY = 4096
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
            val firstResult = slot.item.copy()
            // Server-side: batch crafts until the recipe stops matching or
            // the next FULL result no longer fits. Gated on [canFitFully],
            // not Inventory.add, which reports success on partial inserts
            // (always, in creative) and would void the overflow. The 4096
            // bound covers a full 9-input 64-per-stack grid.
            if (serverLevel != null) {
                craftAllResult = null
                val more = craftBatch(player, slot, firstResult, craftAllOnQuickMove, System.nanoTime() + craftSliceNanos())
                if (more && craftAllOnQuickMove) craftAllResult = firstResult
                // Always EMPTY. A non-empty return re-enters vanilla's
                // QUICK_MOVE loop while auto-pull keeps the slot populated,
                // spinning the server thread forever once the inventory fills.
                return ItemStack.EMPTY
            }
            // Client-side: one predicted iteration with the same full-fit
            // gate. Clearing the slot exits the client's QUICK_MOVE loop
            // (recipe re-assembly is server-gated), the server syncs back.
            if (!canFitFully(firstResult)) return ItemStack.EMPTY
            if (!playerInventory.add(firstResult.copy())) return ItemStack.EMPTY
            slot.onTake(player, firstResult)
            slot.set(ItemStack.EMPTY)
            return firstResult
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

    /** Crafts from the result slot into the player's inventory until the recipe stops
     *  matching, the next full result no longer fits, a plain shift-click reaches one
     *  stack's worth, or the slice of time runs out. The fit gate is [canFitFully], not
     *  Inventory.add, which reports success on partial inserts (always, in creative)
     *  and would void the overflow. Nothing is broadcast while it runs. Returns true
     *  when it stopped on time with more to craft. */
    private fun craftBatch(player: Player, slot: Slot, firstResult: ItemStack, craftAll: Boolean, deadline: Long): Boolean {
        var crafted = 0
        var safety = 0
        var outOfTime = false
        batchCrafting = true
        try {
            while (slot.hasItem() && safety < CRAFT_BATCH_SAFETY) {
                safety++
                val nextResult = slot.item.copy()
                // Leftovers (buckets etc.) can make the grid match a
                // different recipe. Same stop rule as vanilla's loop.
                if (!ItemStack.isSameItem(nextResult, firstResult)) break
                // One stack's worth unless craft-all. Counted in items so a
                // 4-per-craft recipe stops at 64, and the first craft always
                // runs so tools and oversized yields still work.
                if (!craftAll && crafted > 0 && crafted + nextResult.count > firstResult.maxStackSize) break
                if (!canFitFully(nextResult)) break
                if (crafted > 0 && System.nanoTime() >= deadline) {
                    outOfTime = true
                    break
                }
                val inserted = nextResult.copy()
                playerInventory.add(inserted)
                crafted += nextResult.count
                // The gate guarantees a full insert. Route any leftover
                // to network storage rather than voiding it.
                if (!inserted.isEmpty) {
                    val lvl = serverLevel
                    val snap = snapshot
                    if (lvl != null && snap != null) {
                        NetworkStorageHelper.insertItemStack(lvl, snap, inserted, cache, channel = channel)
                    }
                }
                // Refill consumes the pattern, re-snapshot while the
                // grid still holds the ingredients.
                if (autoPull) autoPullPattern = snapshotCraftPattern()
                slot.onTake(player, nextResult)
                // onTake consumes the grid and slotsChanged writes the
                // next result. Refill inline, chaining must not rely on
                // vanilla's QUICK_MOVE re-entry.
                if (autoPull && !slot.hasItem()) autoPullRefill(bulk = craftAll)
            }
        } finally {
            batchCrafting = false
        }
        if (crafted > 0) needsImmediateSync = true
        return outOfTime
    }

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ContainerInput, player: Player) {
        // Any click ends a craft-all still running from an earlier one.
        craftAllResult = null
        // Capture the grid pattern BEFORE super.clicked() consumes ingredients
        var resultBefore: ItemStack? = null
        if (slotId == CRAFT_OUTPUT_SLOT && !resultContainer.getItem(0).isEmpty && autoPull) {
            autoPullPattern = snapshotCraftPattern()
            resultBefore = resultContainer.getItem(0).copy()
        }
        val craftAll = slotId == CRAFT_OUTPUT_SLOT &&
            clickType == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE &&
            button == CRAFT_ALL_BUTTON
        // try/finally so a throw mid-craft can't leave the flag set and turn
        // the next plain shift-click into a craft-all.
        craftAllOnQuickMove = craftAll
        try {
            super.clicked(slotId, if (craftAll) 0 else button, clickType, player)
        } finally {
            craftAllOnQuickMove = false
        }
        // Refill right here, not from broadcastChanges. A rapid second click arrives with
        // a stale state id, and the server then calls broadcastFullState instead, so a
        // refill deferred to broadcastChanges would land a tick late with an empty result
        // slot in between.
        if (autoPullPattern != null) {
            if (serverLevel != null) autoPullRefill(bulk = false) else predictRefill(resultBefore)
        }
    }

    /** The client's guess at the server's refill, so the grid and result don't blink
     *  empty for a round trip on every craft. The grid gets the pattern back and the
     *  result its previous stack, which is what a successful refill produces. The
     *  server's slot sync corrects both when the network can't supply an ingredient. */
    private fun predictRefill(resultBefore: ItemStack?) {
        val pattern = autoPullPattern ?: return
        autoPullPattern = null
        for ((i, template) in pattern.withIndex()) {
            if (template.isEmpty || !craftingContainer.getItem(i).isEmpty) continue
            craftingContainer.setItem(i, template.copy())
        }
        if (resultBefore != null && !resultBefore.isEmpty) resultContainer.setItem(0, resultBefore)
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

    /**
     * True when the player inventory can absorb ALL of [stack]. Mirrors
     * Inventory.add placement: matching partial stacks in the main 36 slots
     * plus offhand, empty slots in the main 36 only. Gates shift-click
     * crafts so a partial fit never consumes ingredients and voids overflow.
     */
    private fun canFitFully(stack: ItemStack): Boolean {
        if (stack.isEmpty) return true
        var room = 0
        for (slotStack in playerInventory.nonEquipmentItems) {
            room += if (slotStack.isEmpty) {
                playerInventory.getMaxStackSize(stack)
            } else if (ItemStack.isSameItemSameComponents(slotStack, stack) && slotStack.isStackable) {
                (playerInventory.getMaxStackSize(slotStack) - slotStack.count).coerceAtLeast(0)
            } else 0
            if (room >= stack.count) return true
        }
        val offhand = playerInventory.getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND)
        if (!offhand.isEmpty && ItemStack.isSameItemSameComponents(offhand, stack) && offhand.isStackable) {
            room += (playerInventory.getMaxStackSize(offhand) - offhand.count).coerceAtLeast(0)
        }
        return room >= stack.count
    }

    /** One-item copy of each grid slot, so auto-pull refills the same variant. */
    private fun snapshotCraftPattern(): List<ItemStack> {
        return (0 until craftingContainer.containerSize).map { i ->
            val stack = craftingContainer.getItem(i)
            if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        }
    }

    /**
     * Handle a click on the network item grid.
     * action: 0=extract full stack, 1=insert carried, 2=extract half, 3=shift-click to inventory,
     *         7=extract one to cursor (shift+right-click)
     *
     * [componentsPatch] is non-empty when the clicked cell carried a specific
     * variant (e.g. Strength Potion). Used to narrow the extract so the same
     * variant the player saw in the grid is what they get on the cursor.
     */
    fun handleGridClick(
        player: Player,
        itemId: String,
        action: Int,
        componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        val c = cache
        // The clicked cell is one exact variant, a plain cell only takes plain stacks.
        val variantPatch = resolveVariant(c, lvl, itemId, componentsPatch)

        when (action) {
            0, 2, 3, 7 -> {
                val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return

                val available = variantCount(c, lvl, snap, itemId, variantPatch)
                val maxStack = item.getDefaultMaxStackSize().toLong()
                val toExtract = when (action) {
                    2 -> maxOf(1L, minOf(available, maxStack) / 2)
                    7 -> 1L
                    else -> minOf(available, maxStack)
                }

                val stacks = extractRealStacks(lvl, snap, c, itemId, toExtract, variantPatch)
                if (stacks.isNotEmpty()) {
                    if (action == 3) {
                        // Shift-click into player inventory. Each component-distinct
                        // stack lands separately so a 1-damage pickaxe and a pristine
                        // pickaxe end up in different slots, matching vanilla.
                        for (stack in stacks) {
                            if (!playerInventory.add(stack)) {
                                NetworkStorageHelper.insertItemStack(lvl, snap, stack, c, channel = channel)
                            }
                        }
                    } else {
                        // Cursor pickup. Multiple component variants can't share a
                        // cursor stack (vanilla forbids merging across components),
                        // so the first stack goes on the cursor and the rest spill
                        // back to network storage.
                        val first = stacks.first()
                        val carried = carried
                        if (carried.isEmpty) {
                            setCarried(first)
                        } else if (ItemStack.isSameItemSameComponents(carried, first)) {
                            // Top up the carried stack, return the overflow.
                            val take = minOf(carried.maxStackSize - carried.count, first.count)
                            carried.grow(take)
                            first.shrink(take)
                            if (!first.isEmpty) {
                                NetworkStorageHelper.insertItemStack(lvl, snap, first, c, channel = channel)
                            }
                        } else {
                            NetworkStorageHelper.insertItemStack(lvl, snap, first, c, channel = channel)
                        }
                        for (i in 1 until stacks.size) {
                            NetworkStorageHelper.insertItemStack(lvl, snap, stacks[i], c, channel = channel)
                        }
                    }
                }
            }
            1 -> {
                val carried = carried
                if (!carried.isEmpty) {
                    val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, carried, c, channel = channel)
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
                    val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, single, c, channel = channel)
                    if (inserted > 0) {
                        carried.shrink(1)
                        if (carried.isEmpty) setCarried(ItemStack.EMPTY)
                    }
                }
            }
            5, 6 -> {
                // Drop one (5) or a stack (6) of [itemId] from the network into
                // the world. Mirrors vanilla's Q / Ctrl+Q behavior on a hovered
                // slot, but pulls from network storage instead of an inventory
                // slot. Stack count is clamped to the item's default max stack
                // size so dropping doesn't dump 64-stack-thousands at once.
                //
                // Threads [variantPatch] through count + extract so dropping
                // from a component-bearing grid entry (e.g. Strength Potion)
                // pulls that specific variant rather than any potion sharing
                // the itemId.
                val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
                val available = variantCount(c, lvl, snap, itemId, variantPatch)
                val maxStack = item.getDefaultMaxStackSize().toLong()
                val toDrop = if (action == 5) 1L else minOf(available, maxStack)

                for (stack in extractRealStacks(lvl, snap, c, itemId, toDrop, variantPatch)) {
                    player.drop(stack, true)
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
        val available = NetworkStorageHelper.countFluid(lvl, snap, fluidId, channel = channel)
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
                val emptyAvailable = if (c != null) cachedCount(c, emptyBucketId) else
                    NetworkStorageHelper.countItems(lvl, snap, emptyBucketId, channel = channel)
                if (emptyAvailable < 1L) return
                BucketSource.NETWORK
            }
            else -> return // carried is something we can't consume, bail.
        }

        // Step 2, pull 1000 mB from the network's fluid storage. If drain falls short,
        //  put back what we got and abort (no half-state).
        var drained = 0L
        for (card in channelCards(snap)) {
            if (drained >= BUCKET_MB) break
            val storage = NetworkStorageHelper.getFluidStorage(lvl, card) ?: continue
            val got = damien.nodeworks.platform.PlatformServices.storage.extractFluid(
                storage, { it == fluidId }, BUCKET_MB - drained
            )
            if (got > 0) {
                c?.onFluidExtracted(fluidId, got)
                c?.onChannelFluidDelta(card.channel, fluidId, -got)
                drained += got
            }
        }
        if (drained < BUCKET_MB) {
            // Roll back partial drain, push back into any fluid storage that will accept it.
            if (drained > 0) NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, drained, c, channel = channel)
            return
        }

        // Step 3, commit the bucket source (consume the empty bucket for real).
        when (bucketSource) {
            BucketSource.CURSOR -> carried.shrink(1)
            BucketSource.NETWORK -> {
                val emptyBucketId = "minecraft:bucket"
                var consumed = 0L
                for (card in channelCards(snap)) {
                    if (consumed >= 1L) break
                    val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
                    val got = damien.nodeworks.platform.PlatformServices.storage.extractItems(
                        storage, { it == emptyBucketId }, 1L - consumed
                    )
                    if (got > 0) {
                        c?.onExtracted(emptyBucketId, false, got)
                        c?.onChannelDelta(card.channel, emptyBucketId, net.minecraft.core.component.DataComponentPatch.EMPTY, -got)
                        consumed += got
                    }
                }
                if (consumed < 1L) {
                    // Bucket vanished between pre-check and commit (another player extracted it).
                    // Return the drained fluid and bail.
                    NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, BUCKET_MB, c, channel = channel)
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
                    NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, BUCKET_MB, c, channel = channel)
                    if (bucketSource == BucketSource.CURSOR) carried.grow(1)
                    else NetworkStorageHelper.insertItemStack(lvl, snap, ItemStack(net.minecraft.world.item.Items.BUCKET), c, channel = channel)
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
                    NetworkStorageHelper.insertFluidAcrossNetwork(lvl, snap, fluidId, BUCKET_MB, channel = channel)
                    if (bucketSource == BucketSource.CURSOR) carried.grow(1)
                    else NetworkStorageHelper.insertItemStack(lvl, snap, ItemStack(net.minecraft.world.item.Items.BUCKET), c, channel = channel)
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
                // Reset so a shift double-click on an empty slot sweeps nothing.
                lastShiftInserted = ItemStack.EMPTY
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
                            lastShiftInserted = slotStack.copyWithCount(1)
                            val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, slotStack, cache, channel = channel)
                            if (inserted > 0) {
                                slotStack.shrink(inserted)
                                if (slotStack.isEmpty) playerInventory.setItem(invIndex, ItemStack.EMPTY)
                            }
                        }
                    }
                }
            }
            3 -> { // Shift double-click, move every stack matching the last shift-inserted item
                val template = lastShiftInserted
                val lvl = serverLevel
                val snap = snapshot
                if (!template.isEmpty && lvl != null && snap != null) {
                    for (i in 0 until 36) {
                        if (isHeldPortableInvSlot(i)) continue
                        val stack = playerInventory.getItem(i)
                        if (stack.isEmpty || !ItemStack.isSameItemSameComponents(stack, template)) continue
                        val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache, channel = channel)
                        if (inserted > 0) {
                            stack.shrink(inserted)
                            if (stack.isEmpty) playerInventory.setItem(i, ItemStack.EMPTY)
                        }
                        // Network stopped accepting, the rest would be refused too.
                        if (!stack.isEmpty) break
                    }
                }
            }
        }

        // Mark inventory dirty so the player's inventoryMenu syncs the change
        playerInventory.setChanged()
        // Force immediate network inventory sync if we inserted into network
        if (action == 2 || action == 3) needsImmediateSync = true
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
        // Clear the grid first: push into the network so the items return to
        // storage instead of stuffing the player's inventory with a stack
        // they didn't want back. Falls through to player inventory when the
        // network is unreachable or refuses the stack, drops on the ground
        // as a last resort.
        val lvl = serverLevel
        val snap = snapshot
        for (i in 0 until craftingContainer.containerSize) {
            val stack = craftingContainer.getItem(i)
            if (stack.isEmpty) continue
            if (lvl != null && snap != null) {
                val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache, channel = channel)
                if (inserted > 0) stack.shrink(inserted)
            }
            if (!stack.isEmpty) {
                if (!playerInventory.add(stack.copy())) {
                    player.drop(stack, false)
                }
            }
            craftingContainer.setItem(i, ItemStack.EMPTY)
        }
        if (lvl != null && snap != null) needsImmediateSync = true

        val ingredients = resolveRecipeIngredients(recipeId)
        // Track claimed ingredients so a single `#planks` entry doesn't
        // get greedy-matched to all 8 slots of a chest pattern, leaving
        // the rest empty.
        val claimed = BooleanArray(ingredients.size)
        // Built after the clear so items just pushed out of the grid count.
        val stock = GridFillStock()
        for (slot in 0 until 9) {
            if (slot >= craftingContainer.containerSize) break
            val fallbackId = fallback.getOrNull(slot).orEmpty()
            if (fallbackId.isEmpty()) continue
            val taken = stock.take(pickCandidatesFor(fallbackId, ingredients, claimed)) ?: continue
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

    /** Extract up to [maxCount] of [itemId] from network storage, returning the
     *  actual [ItemStack]s with components preserved (durability, enchantments,
     *  custom names, dyed colour, etc.).
     *
     *  Replaces the count-only `extractItems` path because rebuilding a stack
     *  from `ItemStack(item, count)` after a count-only extract silently strips
     *  every per-stack component. Cache deltas use each stack's real `hasData`
     *  so the per-variant bucket counts in [NetworkInventoryCache] stay accurate.
     *
     *  Multiple distinct stacks may come back for one call when a chest holds
     *  several component-distinct copies of the same item (e.g. three pickaxes
     *  at different durabilities). */
    private fun extractRealStacks(
        lvl: ServerLevel,
        snap: damien.nodeworks.network.NetworkSnapshot,
        c: NetworkInventoryCache?,
        itemId: String,
        maxCount: Long,
        /** Optional variant patch. When non-null, only stacks whose components
         *  hash equal the patch are extracted, so clicking a Strength Potion
         *  cell doesn't accidentally pull a Fire Resistance one out of the
         *  same itemId bucket. */
        componentsPatch: net.minecraft.core.component.DataComponentPatch? = null,
    ): List<ItemStack> {
        if (maxCount <= 0L) return emptyList()
        val wantHash = componentsPatch?.let { damien.nodeworks.script.BufferKey.componentsHash(it) }
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        for (card in channelCards(snap)) {
            if (remaining <= 0L) break
            val storage = NetworkStorageHelper.getStorage(lvl, card) ?: continue
            // Use the stack-aware predicate when narrowing to a variant so
            // the platform can filter at the slot level instead of returning
            // all variants and forcing the caller to re-insert mismatches.
            val stacks = if (wantHash != null) {
                damien.nodeworks.platform.PlatformServices.storage.extractStacksByPredicate(
                    storage,
                    { stack ->
                        val sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                        sid == itemId && damien.nodeworks.script.BufferKey.componentsHash(stack) == wantHash
                    },
                    remaining,
                )
            } else {
                damien.nodeworks.platform.PlatformServices.storage.extractItemStacksMatching(
                    storage, { it == itemId }, remaining,
                )
            }
            for (stack in stacks) {
                if (stack.isEmpty) continue
                val hasData = !stack.componentsPatch.isEmpty
                c?.onExtracted(itemId, hasData, stack.count.toLong(), stack.componentsPatch)
                c?.onChannelDelta(card.channel, itemId, stack.componentsPatch, -stack.count.toLong())
                out.add(stack)
                remaining -= stack.count
            }
        }
        // Coalesce same-variant fragments (e.g. 43 + 21 diamonds from split
        // physical stacks). Callers treat each returned stack as a distinct
        // variant, so keep it one per variant, capped at maxStackSize.
        val merged = ArrayList<ItemStack>(out.size)
        for (stack in out) {
            var leftover = stack
            for (existing in merged) {
                if (leftover.isEmpty) break
                if (ItemStack.isSameItemSameComponents(existing, leftover) && existing.count < existing.maxStackSize) {
                    val take = minOf(existing.maxStackSize - existing.count, leftover.count)
                    existing.grow(take)
                    leftover.shrink(take)
                }
            }
            if (!leftover.isEmpty) merged.add(leftover)
        }
        return merged
    }

    /** Sources one item per call for a JEI grid fill. Pockets before network,
     *  and within each source the most-stocked candidate first, so a chest
     *  fills with a single plank type whenever one covers it.
     *
     *  Each candidate list is ranked once and tracked locally from there.
     *  Re-ranking after every take would alternate near-equal types slot by
     *  slot, and re-querying would rescan storage for every slot. Network
     *  counts are read lazily, only for candidates whose pockets ran dry, and
     *  at most once per item id for the whole fill. */
    private inner class GridFillStock {
        // Plain stacks only, the same ones [takeFromPocket] can take.
        private val pocket = HashMap<String, Int>()
        private val network = HashMap<String, Long>()
        private val pocketRank = HashMap<List<String>, List<String>>()
        private val networkRank = HashMap<List<String>, List<String>>()

        init {
            for (i in 0 until PLAYER_SLOT_COUNT) {
                if (isHeldPortableInvSlot(i)) continue
                val stack = playerInventory.getItem(i)
                if (stack.isEmpty || !ItemStack.isSameItemSameComponents(stack, ItemStack(stack.item))) continue
                val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
                pocket.merge(id, stack.count, Int::plus)
            }
        }

        fun take(candidates: List<String>): ItemStack? {
            val fromPocket = pocketRank.getOrPut(candidates) {
                candidates.filter { (pocket[it] ?: 0) > 0 }.sortedByDescending { pocket[it] ?: 0 }
            }
            for (id in fromPocket) {
                val have = pocket[id] ?: 0
                if (have <= 0) continue
                val taken = takeFromPocket(id)
                // A miss means the tally drifted, retire the id rather than retry it.
                pocket[id] = if (taken != null) have - 1 else 0
                if (taken != null) return taken
            }

            val lvl = serverLevel ?: return null
            val snap = snapshot ?: return null
            val fromNetwork = networkRank.getOrPut(candidates) { rankNetwork(candidates) }
            for (id in fromNetwork) {
                val have = network[id] ?: 0L
                if (have <= 0L) continue
                // Plain first, as pockets are, any variant only when no plain copy is left.
                val taken = extractRealStacks(lvl, snap, cache, id, 1L, net.minecraft.core.component.DataComponentPatch.EMPTY).firstOrNull()
                    ?: extractRealStacks(lvl, snap, cache, id, 1L).firstOrNull()
                network[id] = if (taken != null) have - 1 else 0L
                if (taken != null) return taken
            }
            return null
        }

        /** Highest network stock first, ties keep tag order (stable sort).
         *  Zero-stock candidates are dropped so a slot nothing can fill
         *  doesn't scan every storage card once per candidate. Without a
         *  cache there's nothing to rank by, so tag order and a try each. */
        private fun rankNetwork(candidates: List<String>): List<String> {
            // Fallback ids come from the client. Only registered items get
            // through, anything else (`*`, `#tag`, `/regex/`) would make
            // count() walk every entry or run a client-supplied regex.
            val valid = candidates.filter { raw ->
                val id = net.minecraft.resources.Identifier.tryParse(raw)
                id != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)
            }
            val c = cache
            if (c == null) {
                for (id in valid) network.putIfAbsent(id, Long.MAX_VALUE)
                return valid
            }
            for (id in valid) {
                if (id !in network) network[id] = cachedCount(c, id)
            }
            return valid.filter { (network[it] ?: 0L) > 0L }.sortedByDescending { network[it] ?: 0L }
        }

        private fun takeFromPocket(itemId: String): ItemStack? {
            val id = net.minecraft.resources.Identifier.tryParse(itemId) ?: return null
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id) ?: return null
            val template = ItemStack(item)
            for (i in 0 until PLAYER_SLOT_COUNT) {
                if (isHeldPortableInvSlot(i)) continue
                val stack = playerInventory.getItem(i)
                if (!stack.isEmpty && ItemStack.isSameItemSameComponents(stack, template)) return stack.split(1)
            }
            return null
        }
    }

    /**
     * Crafting grid utility actions.
     * action 0 = distribute/balance items evenly across slots of the same type
     * action 1 = clear grid, depositing all items into network storage
     * action 2 = toggle the server's auto-pull flag
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
                        val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache, channel = channel)
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
    /** [bulk] is a craft-all: whole stacks go into each slot so the next crafts draw from
     *  the grid. A plain click or shift-click pulls one per slot, as a pattern would, so
     *  the network is not emptied into the grid for a single craft. */
    private fun autoPullRefill(bulk: Boolean) {
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
            // Empty slots that want the same item share one pull, split evenly, so a recipe
            // using an item in four slots keeps crafting while the network holds enough for
            // all four, instead of filling the first slot and starving the rest.
            val groups = LinkedHashMap<damien.nodeworks.script.BufferKey.Key, MutableList<Int>>()
            for ((i, template) in pattern.withIndex()) {
                if (template.isEmpty) continue
                if (!craftingContainer.getItem(i).isEmpty) continue
                groups.getOrPut(damien.nodeworks.script.BufferKey.of(template)) { ArrayList() }.add(i)
            }
            for ((_, slots) in groups) {
                val template = pattern[slots[0]]
                val itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(template.item).toString()
                val perSlot = if (bulk) template.maxStackSize.toLong() else 1L
                val stacks = extractRealStacks(lvl, snap, c, itemId, perSlot * slots.size, template.componentsPatch)
                var total = 0L
                for (stack in stacks) total += stack.count
                if (total <= 0L) continue
                val per = (total / slots.size).toInt()
                var placed = 0L
                if (per > 0) {
                    for (slot in slots) {
                        craftingContainer.setItem(slot, template.copyWithCount(per))
                        placed += per
                    }
                }
                // The pulled stacks are one variant, the patch narrowed them, so the share
                // that found no slot goes back as copies of the template.
                var rest = total - placed
                while (rest > 0L) {
                    val n = minOf(rest, template.maxStackSize.toLong()).toInt()
                    val back = template.copyWithCount(n)
                    val stored = NetworkStorageHelper.insertItemStack(lvl, snap, back, c, channel = channel)
                    if (stored < n) playerInventory.placeItemBackInInventory(template.copyWithCount(n - stored))
                    rest -= n
                }
            }
        } finally {
            suppressSlotsChanged = false
        }

        // Single slotsChanged call with the final grid state
        slotsChanged(craftingContainer)
        needsImmediateSync = true
    }

    /** Whether a distribute drag may place into [slotIndex]: empty, or the same item
     *  with room. Matches the client gate so a slot holding something else neither
     *  receives items nor shrinks the share of the others. */
    private fun canDistributeInto(slotType: Int, slotIndex: Int, carried: ItemStack): Boolean {
        val existing = when (slotType) {
            0 -> if (slotIndex in CRAFT_INPUT_START until CRAFT_OUTPUT_SLOT) slots[slotIndex].item else return false
            1 -> {
                if (slotIndex < 0 || slotIndex >= 36) return false
                val invIndex = if (slotIndex < 27) slotIndex + 9 else slotIndex - 27
                if (isHeldPortableInvSlot(invIndex)) return false
                playerInventory.getItem(invIndex)
            }
            else -> return false
        }
        return existing.isEmpty || (ItemStack.isSameItemSameComponents(existing, carried) && existing.count < existing.maxStackSize)
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

        val targets = slotIndices.filter { canDistributeInto(slotType, it, carried) }
        if (targets.isEmpty()) return
        val total = carried.count
        val count = targets.size
        val perSlot = total / count
        val remainder = total % count
        if (perSlot <= 0 && remainder <= 0) return

        var distributed = 0
        for ((idx, slotIndex) in targets.withIndex()) {
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
    fun handleCraftRequest(
        player: Player,
        itemId: String,
        count: Int,
        componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    ) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        if (count <= 0 || count > 999) return

        // The queue entry tracks the variant the craft produces. A plain request takes the
        // Instruction Set's stored output, e.g. a barrel's wood type.
        val queuedPatch = if (componentsPatch.size() > 0) componentsPatch
            else snap.findInstructionSet(itemId)?.instructionSet?.outputComponents ?: componentsPatch

        // Display name prefers the variant's hover name (e.g. "Potion of Strength")
        // when the request carries components, falling back to the identifier path.
        val itemName = if (queuedPatch.size() > 0) {
            val id = net.minecraft.resources.Identifier.tryParse(itemId)
            val item = id?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(it) }
            if (item != null) {
                val stack = net.minecraft.world.item.ItemStack(item)
                stack.applyComponents(queuedPatch)
                stack.hoverName.string
            } else {
                itemId
            }
        } else {
            net.minecraft.resources.Identifier.tryParse(itemId)?.path?.replace('_', ' ') ?: itemId
        }

        // Create queue entry (pending until the whole job completes), scoped to the
        // network that owns this craft so other networks' terminals don't display it.
        val entry = CraftQueueManager.addEntry(player.uuid, snap.networkId, itemId, itemName, count, queuedPatch)

        try {
            // CraftingHelper.craft does feasibility-aware CPU selection across every CPU on
            // the network. On failure, lastFailReason carries the player-facing message.
            damien.nodeworks.script.CraftingHelper.currentPendingJob = null
            val result = damien.nodeworks.script.CraftingHelper.craft(
                itemId, count, lvl, snap,
                cache = cache,
                submitterUuid = player.uuid,
                componentsPatch = componentsPatch,
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
        craftAllResult = null
        // The screen's removed() runs this on the client too, including when JEI swaps
        // its recipe view in front. Only the server owns the grid, the client copy must
        // keep showing it. Vanilla's crafting table gets the same guard from its
        // ContainerLevelAccess being a no-op client side.
        if (!player.level().isClientSide) drainCraftingGridOnClose(player)
        // Mark completed queue entries as seen so they're cleared on next open.
        // Scoped to the current network, closing this terminal shouldn't acknowledge
        // jobs on other networks the player has open queues for.
        val networkId = snapshot?.networkId
        if (networkId != null) {
            for (entry in CraftQueueManager.getQueue(player.uuid)) {
                if (entry.networkId == networkId && entry.isComplete) {
                    entry.seenComplete = true
                }
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
                val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack, channel = channel)
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
        // Reject extract requests for entries that belong to another network. The
        // client only ever sees this network's slice of the queue, but a malicious
        // client could try to drain a craft sitting in a different network's CPU.
        if (entry.networkId != snap.networkId) return
        if (entry.availableCount <= 0) return

        val identifier = net.minecraft.resources.Identifier.tryParse(entry.itemId) ?: return
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return
        val maxStack = item.getDefaultMaxStackSize().toLong()

        val toExtract = when (action) {
            2 -> maxOf(1L, minOf(entry.availableCount.toLong(), maxStack) / 2)
            else -> minOf(entry.availableCount.toLong(), maxStack)
        }

        // Only the crafted variant, never another one already in storage.
        val variant = entry.componentsPatch.takeIf { it.size() > 0 }
        val stacks = extractRealStacks(lvl, snap, cache, entry.itemId, toExtract, variant)
        if (stacks.isNotEmpty()) {
            val totalExtracted = stacks.sumOf { it.count }
            entry.takenCount += totalExtracted
            entry.dirty = true
            if (action == 1) {
                for (stack in stacks) {
                    if (!playerInventory.add(stack)) {
                        NetworkStorageHelper.insertItemStack(lvl, snap, stack, cache, channel = channel)
                    }
                }
            } else {
                val first = stacks.first()
                val carried = carried
                if (carried.isEmpty) {
                    setCarried(first)
                } else if (ItemStack.isSameItemSameComponents(carried, first) && carried.count + first.count <= carried.maxStackSize) {
                    carried.grow(first.count)
                } else {
                    NetworkStorageHelper.insertItemStack(lvl, snap, first, cache, channel = channel)
                }
                for (i in 1 until stacks.size) {
                    NetworkStorageHelper.insertItemStack(lvl, snap, stacks[i], cache, channel = channel)
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
        if (batchCrafting) return
        if (!inSlotsChanged) craftAllResult?.let { result ->
            val slot = slots[CRAFT_OUTPUT_SLOT]
            val more = slot.hasItem() &&
                craftBatch(playerInventory.player, slot, result, craftAll = true, System.nanoTime() + craftSliceNanos())
            if (!more) craftAllResult = null
        }
        super.broadcastChanges()

        val serverPlayer = playerInventory.player as? ServerPlayer ?: return
        tickCounter++
        applyRequestedChannel()

        if (tickCounter % 20 == 0) refreshCacheBinding()

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

        if (networkNeedsSync) {
            networkNeedsSync = false
            serverPlayer.connection.send(
                net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                    damien.nodeworks.network.InvTerminalNetworkPayload(containerId, snapshot?.networkId?.toString() ?: "")
                )
            )
        }

        // On first sync: purge acknowledged entries from the player's full queue
        // (across networks, since seenComplete only flips for the current-network
        // entries inside [removed], so this is correct cleanup either way).
        if (needsFullSync) {
            CraftQueueManager.getQueue(serverPlayer.uuid).removeAll { it.seenComplete }
        }

        // Scope the displayed queue + reserved deductions to this terminal's network.
        // Without this, opening a portable terminal on Network A would show jobs queued
        // on Network B in the pinned row and incorrectly deduct items B reserved.
        val networkId = snapshot?.networkId
        val queue = CraftQueueManager.getQueueForNetwork(serverPlayer.uuid, networkId)
        val reserved = CraftQueueManager.getReservedCounts(serverPlayer.uuid, networkId)
        val c = cache

        if (needsFullSync) {
            // Build all entries (items + fluids) and split into chunks. When we're
            // disconnected (no cache) we still ship a single empty chunk so the
            // client replaces whatever was previously displayed with a blank grid.
            val color = (channel as? damien.nodeworks.network.ChannelFilter.Color)?.color
            filteredSent = null
            val allEntries: List<InventorySyncPayload.SyncEntry> = if (c != null && color != null) {
                val entries = channelEntries(c, color, reserved)
                filteredSent = entries.associateByTo(HashMap()) { it.serial }
                filteredVersion = c.version
                entries
            } else if (c != null) {
                val itemEntries = c.getAllEntries().map { entry ->
                    val deduct = reservedFor(reserved, entry.info)
                    InventorySyncPayload.SyncEntry(
                        serial = entry.serial,
                        itemId = entry.info.itemId,
                        name = entry.info.name,
                        count = maxOf(0L, entry.info.count - deduct),
                        maxStackSize = entry.info.maxStackSize,
                        hasData = entry.info.hasData,
                        craftable = entry.info.isCraftable,
                        componentsPatch = entry.info.componentsPatch,
                        craftWarning = entry.info.craftWarning,
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

        // One channel: the shared change list is network-wide, so diff this view instead.
        val color = (channel as? damien.nodeworks.network.ChannelFilter.Color)?.color
        if (color != null) {
            if (c.version == filteredVersion) return
            filteredVersion = c.version
            sendChannelDelta(serverPlayer, c, color, reserved)
            return
        }
        if (!c.hasChanges() && !queueDirty) return

        if (c.hasChanges()) {
            val (changed, removed) = c.consumeChanges()
            val changedFluids = c.consumeFluidChanges()
            val itemEntries = changed.map { entry ->
                val deduct = reservedFor(reserved, entry.info)
                InventorySyncPayload.SyncEntry(
                    serial = entry.serial,
                    itemId = entry.info.itemId,
                    name = entry.info.name,
                    count = maxOf(0L, entry.info.count - deduct),
                    maxStackSize = entry.info.maxStackSize,
                    hasData = entry.info.hasData,
                    craftable = entry.info.isCraftable,
                    componentsPatch = entry.info.componentsPatch,
                    craftWarning = entry.info.craftWarning,
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

    private fun reservedFor(reserved: Map<damien.nodeworks.script.BufferKey.Key, Int>, info: damien.nodeworks.platform.ItemInfo): Int =
        reserved[damien.nodeworks.script.BufferKey.Key(info.itemId, damien.nodeworks.script.BufferKey.componentsHash(info.componentsPatch))] ?: 0

    /** Grid entries for one channel. Uses the network's serials so the client treats
     *  them like the unfiltered grid. Craftable entries stay so crafting still works.
     *  With [since], only entries that differ from it are built, and [live] collects
     *  every serial still shown, so an unchanged grid allocates nothing. */
    private fun channelEntries(
        c: NetworkInventoryCache,
        color: net.minecraft.world.item.DyeColor,
        reserved: Map<damien.nodeworks.script.BufferKey.Key, Int>,
        since: Map<Long, InventorySyncPayload.SyncEntry>? = null,
        live: MutableSet<Long>? = null,
    ): List<InventorySyncPayload.SyncEntry> {
        val out = ArrayList<InventorySyncPayload.SyncEntry>()
        val counts = c.channelItemCounts(color)
        c.forEachEntry { key, entry ->
            val info = entry.info
            val n = counts[key] ?: 0L
            if (n <= 0L && !info.isCraftable) return@forEachEntry
            val count = maxOf(0L, n - (reserved[key] ?: 0))
            live?.add(entry.serial)
            val prev = since?.get(entry.serial)
            if (prev != null && prev.count == count && prev.craftable == info.isCraftable &&
                prev.craftWarning == info.craftWarning &&
                prev.name == info.name && prev.componentsPatch == info.componentsPatch
            ) return@forEachEntry
            out += InventorySyncPayload.SyncEntry(
                serial = entry.serial,
                itemId = info.itemId,
                name = info.name,
                count = count,
                maxStackSize = info.maxStackSize,
                hasData = info.hasData,
                craftable = info.isCraftable,
                componentsPatch = info.componentsPatch,
                craftWarning = info.craftWarning,
            )
        }
        val fluidCounts = c.channelFluidCounts(color)
        c.forEachFluidEntry { key, entry ->
            val n = fluidCounts[key] ?: return@forEachFluidEntry
            live?.add(entry.serial)
            val prev = since?.get(entry.serial)
            if (prev != null && prev.count == n && prev.name == entry.info.name) return@forEachFluidEntry
            out += InventorySyncPayload.SyncEntry(
                serial = entry.serial,
                itemId = entry.info.fluidId,
                name = entry.info.name,
                count = n,
                maxStackSize = 1,
                hasData = false,
                craftable = false,
                kind = 1,
            )
        }
        return out
    }

    /** Send what changed in the one-channel view since the last send. */
    private fun sendChannelDelta(
        player: ServerPlayer,
        c: NetworkInventoryCache,
        color: net.minecraft.world.item.DyeColor,
        reserved: Map<damien.nodeworks.script.BufferKey.Key, Int>,
    ) {
        val sent = filteredSent ?: return
        val live = HashSet<Long>(sent.size)
        val changed = channelEntries(c, color, reserved, sent, live)
        val removed = sent.keys.filter { it !in live }
        for (serial in removed) sent.remove(serial)
        for (e in changed) sent[e.serial] = e
        if (changed.isEmpty() && removed.isEmpty()) return
        sendToClient(player, InventorySyncPayload(false, changed, removed))
    }

    private fun sendQueueSync(player: ServerPlayer, queue: List<CraftQueueManager.CraftQueueEntry>) {
        val entries = queue.map { e ->
            val readyCount = if (e.isComplete) e.totalRequested else 0
            damien.nodeworks.network.CraftQueueSyncPayload.QueueEntry(
                id = e.id, itemId = e.itemId, name = e.itemName,
                totalRequested = e.totalRequested, readyCount = readyCount,
                availableCount = e.availableCount, isComplete = e.isComplete,
                componentsPatch = e.componentsPatch,
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
