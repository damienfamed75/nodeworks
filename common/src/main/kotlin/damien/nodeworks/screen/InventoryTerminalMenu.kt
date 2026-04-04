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
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

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

    companion object {
        fun createServer(syncId: Int, inv: Inventory, level: ServerLevel, nodePos: BlockPos): InventoryTerminalMenu {
            val menu = InventoryTerminalMenu(syncId, inv, level, nodePos)
            menu.snapshot = NetworkDiscovery.discoverNetwork(level, nodePos)
            menu.cache = NetworkInventoryCache.getOrCreate(level, nodePos)
            return menu
        }

        fun clientFactory(syncId: Int, inv: Inventory, data: InventoryTerminalOpenData): InventoryTerminalMenu {
            return InventoryTerminalMenu(syncId, inv, null, data.terminalPos)
        }
    }

    init {
        // Hidden MC Slots for inventory sync only — positioned off-screen.
        // These are never rendered (VirtualSlotGrid handles rendering).
        // MC's broadcastChanges() uses these to detect and sync inventory changes to the client.
        for (row in 0..2) {
            for (col in 0..8) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, -999, -999))
            }
        }
        for (col in 0..8) {
            addSlot(Slot(playerInventory, col, -999, -999))
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

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

    override fun stillValid(player: Player): Boolean = true

    override fun broadcastChanges() {
        super.broadcastChanges()

        val serverPlayer = playerInventory.player as? ServerPlayer ?: return
        val c = cache ?: return

        if (needsFullSync) {
            val entries = c.getAllEntries().map { entry ->
                InventorySyncPayload.SyncEntry(
                    serial = entry.serial,
                    itemId = entry.info.itemId,
                    name = entry.info.name,
                    count = entry.info.count,
                    maxStackSize = entry.info.maxStackSize,
                    hasData = entry.info.hasData
                )
            }
            sendToClient(serverPlayer, InventorySyncPayload(true, entries, emptyList()))
            needsFullSync = false
            return
        }

        tickCounter++
        val immediate = needsImmediateSync
        needsImmediateSync = false
        if (!immediate && tickCounter % 5 != 0) return
        if (!c.hasChanges()) return

        val (changed, removed) = c.consumeChanges()
        val entries = changed.map { entry ->
            InventorySyncPayload.SyncEntry(
                serial = entry.serial,
                itemId = entry.info.itemId,
                name = entry.info.name,
                count = entry.info.count,
                maxStackSize = entry.info.maxStackSize,
                hasData = entry.info.hasData
            )
        }
        sendToClient(serverPlayer, InventorySyncPayload(false, entries, removed))
    }

    private fun sendToClient(player: ServerPlayer, payload: InventorySyncPayload) {
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
    }
}
