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
 * Full sync on open, delta updates while open.
 * Player inventory at the bottom for inserting items into the network.
 */
class InventoryTerminalMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val serverLevel: ServerLevel?,
    private val nodePos: BlockPos?
) : AbstractContainerMenu(ModScreenHandlers.INVENTORY_TERMINAL, syncId) {

    private var cache: NetworkInventoryCache? = null
    private var snapshot: damien.nodeworks.network.NetworkSnapshot? = null
    private var needsFullSync = true
    private var tickCounter = 0

    companion object {
        const val PLAYER_INV_Y = 136
        const val HOTBAR_Y = 194

        fun createServer(syncId: Int, inv: Inventory, level: ServerLevel, nodePos: BlockPos): InventoryTerminalMenu {
            val menu = InventoryTerminalMenu(syncId, inv, level, nodePos)
            menu.snapshot = NetworkDiscovery.discoverNetwork(level, nodePos)
            menu.cache = NetworkInventoryCache.getOrCreate(level, nodePos)
            return menu
        }

        fun clientFactory(syncId: Int, inv: Inventory, data: InventoryTerminalOpenData): InventoryTerminalMenu {
            return InventoryTerminalMenu(syncId, inv, null, null)
        }
    }

    init {
        // Player inventory (3 rows)
        for (row in 0..2) {
            for (col in 0..8) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18))
            }
        }
        // Hotbar
        for (col in 0..8) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y))
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()

        // Insert into network storage
        val lvl = serverLevel ?: return ItemStack.EMPTY
        val snap = snapshot ?: return ItemStack.EMPTY
        val c = cache

        val inserted = NetworkStorageHelper.insertItemStack(lvl, snap, stack, c)
        if (inserted > 0) {
            stack.shrink(inserted)
            if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
            return original
        }

        return ItemStack.EMPTY
    }

    /**
     * Handle a click on the network grid.
     * action 0 = extract full stack, action 1 = insert carried, action 2 = extract half
     */
    fun handleGridClick(player: Player, itemId: String, action: Int) {
        val lvl = serverLevel ?: return
        val snap = snapshot ?: return
        val c = cache

        when (action) {
            0, 2, 3 -> {
                // Extract from network: 0=full stack, 2=half of available, 3=shift-click (full stack to inventory)
                val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return

                // Count how many are available
                val available = if (c != null) c.count(itemId) else {
                    NetworkStorageHelper.countItems(lvl, snap, itemId)
                }
                val maxStack = item.defaultMaxStackSize.toLong()
                val toExtract = when (action) {
                    2 -> maxOf(1L, minOf(available, maxStack) / 2) // half of available (capped at stack)
                    else -> minOf(available, maxStack) // full stack
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
                        // Shift-click: put directly into player inventory
                        val inv = findServerPlayer()?.inventory ?: run {
                            NetworkStorageHelper.insertItemStack(lvl, snap, stack, c)
                            return
                        }
                        if (!inv.add(stack)) {
                            // Couldn't fit — put back
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
                // Insert carried item into network
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
    }

    override fun stillValid(player: Player): Boolean = true

    override fun broadcastChanges() {
        super.broadcastChanges()

        val player = slots.firstOrNull()?.container?.let {
            // Find the player from the inventory
        }
        // Get player from the container listeners
        val serverPlayer = findServerPlayer() ?: return
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
        if (tickCounter % 5 != 0) return
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

    private fun findServerPlayer(): ServerPlayer? {
        // The first slot's container is the player inventory
        val inv = slots.firstOrNull()?.container as? Inventory ?: return null
        return inv.player as? ServerPlayer
    }

    private fun sendToClient(player: ServerPlayer, payload: InventorySyncPayload) {
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
    }
}
