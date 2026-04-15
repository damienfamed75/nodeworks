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
 * Zero MC Slot objects — all interaction via custom packets.
 * Full sync on open, delta updates while open.
 */
class InventoryTerminalMenu(
    syncId: Int,
    val playerInventory: Inventory,
    private val serverLevel: ServerLevel?,
    val terminalPos: BlockPos?
) : AbstractContainerMenu(ModScreenHandlers.INVENTORY_TERMINAL, syncId) {

    private var cache: NetworkInventoryCache? = null
    private var snapshot: damien.nodeworks.network.NetworkSnapshot? = null
    private var needsFullSync = true
    private var needsImmediateSync = false
    private var tickCounter = 0

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

        // Crafting input slots (slots 36-44) — positioned off-screen, screen renders them
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(Slot(craftingContainer, col + row * 3, -999, -999))
            }
        }

        // Crafting output slot (slot 45)
        addSlot(ResultSlot(playerInventory.player, craftingContainer, resultContainer, 0, -999, -999))
    }

    /** Called when crafting grid contents change — recompute the result. */
    override fun slotsChanged(container: net.minecraft.world.Container) {
        if (suppressSlotsChanged) return
        if (container === craftingContainer) {
            val level = serverLevel ?: playerInventory.player.level()
            val recipe = level.recipeManager
                .getRecipeFor(RecipeType.CRAFTING, craftingContainer.asCraftInput(), level)
            if (recipe.isPresent) {
                resultContainer.setItem(0, recipe.get().value().assemble(craftingContainer.asCraftInput(), level.registryAccess()))
            } else {
                resultContainer.setItem(0, ItemStack.EMPTY)
            }
        }
        super.slotsChanged(container)
    }

    fun createServer(level: ServerLevel, nodePos: BlockPos) {
        snapshot = NetworkDiscovery.discoverNetwork(level, nodePos)
        cache = NetworkInventoryCache.getOrCreate(level, nodePos)
    }

    companion object {
        const val PLAYER_SLOT_COUNT = 36
        const val CRAFT_INPUT_START = 36
        const val CRAFT_OUTPUT_SLOT = 45

        fun createServer(syncId: Int, inv: Inventory, level: ServerLevel, nodePos: BlockPos): InventoryTerminalMenu {
            val menu = InventoryTerminalMenu(syncId, inv, level, nodePos)
            menu.createServer(level, nodePos)
            return menu
        }

        fun clientFactory(syncId: Int, inv: Inventory, data: InventoryTerminalOpenData): InventoryTerminalMenu {
            return InventoryTerminalMenu(syncId, inv, null, data.terminalPos)
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
            if (autoPull) pendingAutoPull = true
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
        return ItemStack.EMPTY
    }

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ClickType, player: Player) {
        // Capture the grid pattern BEFORE super.clicked() consumes ingredients
        if (slotId == CRAFT_OUTPUT_SLOT && !resultContainer.getItem(0).isEmpty && autoPull) {
            autoPullPattern = snapshotCraftPattern()
        }
        super.clicked(slotId, button, clickType, player)
        if (autoPullPattern != null) {
            pendingAutoPull = true
        }
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
                val identifier = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: return
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(identifier) ?: return

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
     * Handle a click on a player inventory slot.
     * action: 0=left click, 1=right click, 2=shift-click (insert into network)
     */
    fun handlePlayerSlotClick(player: Player, slotIndex: Int, action: Int) {
        if (slotIndex < 0 || slotIndex >= 36) return

        // Map virtual slot index to actual inventory index
        // Virtual: 0-26 = main inventory (inv slots 9-35), 27-35 = hotbar (inv slots 0-8)
        val invIndex = if (slotIndex < 27) slotIndex + 9 else slotIndex - 27

        when (action) {
            0 -> { // Left click — swap with carried
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
            1 -> { // Right click — pick up half or place one
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
            2 -> { // Shift-click — insert into network
                val lvl = serverLevel ?: return
                val snap = snapshot ?: return
                val c = cache
                val slotStack = playerInventory.getItem(invIndex)
                if (!slotStack.isEmpty) {
                    val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, slotStack, c)
                    if (inserted > 0) {
                        slotStack.shrink(inserted)
                        if (slotStack.isEmpty) playerInventory.setItem(invIndex, ItemStack.EMPTY)
                    }
                }
            }
        }

        // Mark inventory dirty so the player's inventoryMenu syncs the change
        playerInventory.setChanged()
        // Force immediate network inventory sync if we inserted into network
        if (action == 2) needsImmediateSync = true
    }

    /**
     * Fill the crafting grid with items from a JEI recipe.
     * Returns items from the old grid to the player inventory first.
     */
    fun handleCraftGridFill(player: Player, grid: List<String>) {
        // Return current crafting grid items to player
        for (i in 0 until craftingContainer.containerSize) {
            val stack = craftingContainer.getItem(i)
            if (!stack.isEmpty) {
                if (!playerInventory.add(stack.copy())) {
                    player.drop(stack, false)
                }
                craftingContainer.setItem(i, ItemStack.EMPTY)
            }
        }

        // Fill with new recipe
        for ((i, itemId) in grid.withIndex()) {
            if (i >= 9 || itemId.isEmpty()) continue
            val id = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: continue
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: continue

            // Try player inventory first, then pull from network
            val invSlot = playerInventory.findSlotMatchingItem(ItemStack(item))
            if (invSlot >= 0) {
                val taken = playerInventory.getItem(invSlot).split(1)
                craftingContainer.setItem(i, taken)
            } else {
                // Pull from network storage
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
                if (extracted > 0) {
                    craftingContainer.setItem(i, ItemStack(item, 1))
                }
            }
        }

        slotsChanged(craftingContainer)
        playerInventory.setChanged()
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

        // Suppress slotsChanged during refill — intermediate states can match
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
                    val id = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: continue
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: continue
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
        val id = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: return
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: return
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

        val itemName = net.minecraft.resources.ResourceLocation.tryParse(itemId)?.path?.replace('_', ' ') ?: itemId

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
                // Total failure — no feasible CPU, missing recipe, etc.
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

            // Build a CraftResult for the release — same as the scripting terminal
            val craftResult = result ?: run {
                val id = net.minecraft.resources.ResourceLocation.tryParse(itemId)
                val item = if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) else null
                val name = if (item != null) ItemStack(item).hoverName.string else itemId
                damien.nodeworks.script.CraftingHelper.CraftResult(
                    itemId, name, count,
                    cpu = snap.cpus.firstOrNull()?.let { lvl.getBlockEntity(it.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity },
                    level = lvl, snapshot = snap, cache = cache
                )
            }

            if (pending == null || pending.isComplete) {
                // Synchronous — release immediately (flush buffer → network, free CPU)
                damien.nodeworks.script.CraftingHelper.releaseCraftResult(craftResult)
                if (pending == null || pending.success) {
                    entry.completedOps = 1
                } else {
                    CraftQueueManager.getQueue(player.uuid).remove(entry)
                }
                entry.dirty = true
            } else {
                // Async — release when the pending job completes (same as :store())
                pending.onCompleteCallback = { success ->
                    damien.nodeworks.script.CraftingHelper.releaseCraftResult(craftResult)
                    if (success) {
                        entry.completedOps = 1
                    } else {
                        // Cancelled or failed — drop the entry so nothing can be extracted
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

    override fun stillValid(player: Player): Boolean = true

    override fun removed(player: Player) {
        super.removed(player)
        clearContainer(player, craftingContainer)
        // Mark completed queue entries as seen so they're cleared on next open
        val queue = CraftQueueManager.getQueue(player.uuid)
        for (entry in queue) {
            if (entry.isComplete) {
                entry.seenComplete = true
            }
        }
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

        val identifier = net.minecraft.resources.ResourceLocation.tryParse(entry.itemId) ?: return
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(identifier) ?: return
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
        val c = cache ?: return
        val queue = CraftQueueManager.getQueue(serverPlayer.uuid)

        // On first sync: purge acknowledged entries
        if (needsFullSync) {
            queue.removeAll { it.seenComplete }
        }

        // Build reserved count deduction map
        val reserved = CraftQueueManager.getReservedCounts(serverPlayer.uuid)

        if (needsFullSync) {
            // Build all entries and split into chunks
            val allEntries = c.getAllEntries().map { entry ->
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
            fullSyncChunks = allEntries.chunked(FULL_SYNC_CHUNK_SIZE)
            fullSyncChunkIndex = 0
            sendQueueSync(serverPlayer, queue)
            needsFullSync = false
            // Don't return — fall through to send first chunk below
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

        tickCounter++
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
            val entries = changed.map { entry ->
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
            sendToClient(serverPlayer, InventorySyncPayload(false, entries, removed))
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
