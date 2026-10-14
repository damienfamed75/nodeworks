package damien.nodeworks.item

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModDataComponents
import damien.nodeworks.screen.InventoryTerminalMenu
import damien.nodeworks.screen.InventoryTerminalOpenData
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Handheld Inventory Terminal. A wearable version of the fixed
 * <ItemLink id="inventory_terminal" /> that connects to a network remotely via a
 * [LinkCrystalItem] installed in its crystal slot. The crystal must be paired to a
 * Broadcast Antenna sitting adjacent to a Network Controller, see
 * [BroadcastSourceKind.NETWORK_CONTROLLER] for the pairing model.
 *
 * ## Crystal storage
 *
 * The installed crystal lives in the Portable's
 * [ModDataComponents.PORTABLE_INVENTORY_TERMINAL_CRYSTAL] component as a full
 * [ItemStack]. Storing the stack (not just the pairing data) preserves any extra
 * state on the crystal, a renamed crystal stays renamed when pulled out.
 *
 * ## Opening
 *
 * Right-click always opens the Inventory Terminal menu, regardless of whether a
 * crystal is installed or links to a reachable network. If the crystal is missing,
 * blank, wrong-kind, or out of range, the menu opens in a disconnected state, the
 * grid renders empty and operations no-op until the player installs a working
 * crystal via the menu's slot (the menu re-resolves on any slot change, and once a
 * second on its own as a recovery path).
 */
class PortableInventoryTerminalItem(properties: Properties) : Item(properties) {

    override fun use(
        level: Level,
        player: Player,
        hand: InteractionHand,
    ): InteractionResult {
        // Animation hint handled client-side, all state + menu open happen server-side.
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS

        // Re-read the hand each tick so the menu tracks the live stack through hand swaps.
        PortableTerminalOpener.open(serverPlayer, serverLevel) { player.getItemInHand(hand) }
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        tooltip: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val crystal = getInstalledCrystal(stack)
        if (crystal.isEmpty) {
            tooltip.accept(
                Component.literal("No Link Crystal installed.")
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
            return
        }
        val pairing = LinkCrystalItem.getPairingData(crystal)
        if (pairing == null) {
            tooltip.accept(
                Component.literal("Installed crystal is blank.")
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
            return
        }
        if (pairing.kind != BroadcastSourceKind.NETWORK_CONTROLLER) {
            // Portables only drive network-controller-kind pairings. A wrong-kind
            // crystal is user error, surface it explicitly rather than showing a
            // "Paired to Processing Storage" line that would look fine at a glance.
            tooltip.accept(
                Component.literal("Installed crystal has wrong pairing kind.")
                    .withStyle(ChatFormatting.RED)
            )
            return
        }
        // If the controller's BE happens to be loaded on the client (player is
        // in render range, same dimension), prefer its custom network name.
        // Out of range or cross-dimension we fall back to the position line so
        // the tooltip still tells the player something useful.
        val networkName = resolveNetworkName(pairing)
        if (networkName != null) {
            tooltip.accept(
                Component.literal("Linked to \"$networkName\"")
                    .withStyle(ChatFormatting.GRAY)
            )
        } else {
            tooltip.accept(
                Component.literal("Linked to network at (${pairing.pos.x}, ${pairing.pos.y}, ${pairing.pos.z})")
                    .withStyle(ChatFormatting.GRAY)
            )
        }
        val dimId = pairing.dimension.identifier().path
        tooltip.accept(
            Component.literal("Dimension: $dimId")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    /** Read the live network name off the paired antenna's adjacent controller,
     *  or null when neither BE is loaded on the client (out of range or
     *  different dimension), no controller is adjacent, or the controller
     *  hasn't been given a custom name. The crystal stores the *antenna's*
     *  position, not the controller's, so we have to scan the antenna's six
     *  neighbours the same way [BroadcastAntennaBlockEntity.detectSource]
     *  does at encoding time. */
    private fun resolveNetworkName(pairing: LinkCrystalItem.Companion.PairingData): String? {
        val level = net.minecraft.client.Minecraft.getInstance().level ?: return null
        if (level.dimension() != pairing.dimension) return null
        for (dir in net.minecraft.core.Direction.entries) {
            val neighbor = pairing.pos.relative(dir)
            val be = level.getBlockEntity(neighbor)
                as? damien.nodeworks.block.entity.NetworkControllerBlockEntity
                ?: continue
            return be.networkName.takeIf { it.isNotEmpty() }
        }
        return null
    }

    companion object {
        /**
         * Read the Link Crystal currently installed in [portable]. Returns
         * [ItemStack.EMPTY] when no crystal is installed, the component is absent on
         * a freshly-crafted Portable, and callers treat missing and empty identically.
         *
         * The returned stack is the live value stored in the component. Mutating it
         * is not supported, call [setInstalledCrystal] to write back a modified
         * crystal.
         */
        fun getInstalledCrystal(portable: ItemStack): ItemStack {
            if (portable.isEmpty || portable.item !is PortableInventoryTerminalItem) return ItemStack.EMPTY
            return portable.get(ModDataComponents.PORTABLE_INVENTORY_TERMINAL_CRYSTAL)?.stack ?: ItemStack.EMPTY
        }

        /**
         * Store [crystal] in [portable]'s crystal slot. Pass [ItemStack.EMPTY] to
         * clear the slot. When the stored value would be empty we REMOVE the component
         * entirely rather than storing an empty wrapper, keeps the item's component
         * map clean (relevant for equality checks and stack merging).
         *
         * The stored stack is always a copy: if the caller mutates the stack they
         * passed in (e.g. by decrementing its count), the component's value stays
         * stable.
         */
        fun setInstalledCrystal(portable: ItemStack, crystal: ItemStack) {
            if (portable.isEmpty || portable.item !is PortableInventoryTerminalItem) return
            if (crystal.isEmpty) {
                portable.remove(ModDataComponents.PORTABLE_INVENTORY_TERMINAL_CRYSTAL)
            } else {
                portable.set(
                    ModDataComponents.PORTABLE_INVENTORY_TERMINAL_CRYSTAL,
                    InstalledCrystal(crystal.copy()),
                )
            }
        }
    }
}

/** Shared menu-open path for the Portable, used by right-click and the Ctrl+E hotkey. */
object PortableTerminalOpener {
    /** Other places a Portable can be carried, such as a Curios slot. Each returns a live
     *  holder for the first Portable it finds, or null. Registered by optional integrations. */
    val extraHolders: MutableList<(ServerPlayer) -> (() -> ItemStack)?> = java.util.concurrent.CopyOnWriteArrayList()

    /** [holderProvider] is re-read every tick so validity checks and crystal
     *  write-back always see the live Portable stack. */
    fun open(player: ServerPlayer, level: ServerLevel, holderProvider: () -> ItemStack) {
        PlatformServices.menu.openExtendedMenu(
            player,
            Component.translatable("container.nodeworks.portable_inventory_terminal"),
            // No world position, null tells the client there is no SetLayout target.
            InventoryTerminalOpenData(terminalPos = null, hasCrystalSlot = true),
            InventoryTerminalOpenData.STREAM_CODEC,
            { syncId, inv, _ ->
                // Opens with no source. The menu seeds the crystal slot from the
                // Portable component and resolves (or stays disconnected) itself.
                InventoryTerminalMenu.createServer(
                    syncId = syncId,
                    inv = inv,
                    level = level,
                    source = null,
                    displayPos = null,
                    hasCrystalSlot = true,
                    crystalHolderProvider = holderProvider,
                )
            }
        )
    }

    /** Hotkey entry. First Portable by inventory slot order: hotbar, main, then offhand,
     *  then any [extraHolders]. */
    fun openFromInventory(player: ServerPlayer): Boolean {
        val inv = player.inventory
        val slots = (0 until 36) + net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND
        val slot = slots.firstOrNull { inv.getItem(it).item is PortableInventoryTerminalItem }
        if (slot != null) {
            open(player, player.level()) { inv.getItem(slot) }
            return true
        }
        for (find in extraHolders) {
            val holder = find(player) ?: continue
            open(player, player.level(), holder)
            return true
        }
        return false
    }
}
