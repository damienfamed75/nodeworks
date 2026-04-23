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
 * Broadcast Antenna sitting adjacent to a Network Controller — see
 * [BroadcastSourceKind.NETWORK_CONTROLLER] for the pairing model.
 *
 * ## Crystal storage
 *
 * The installed crystal lives in the Portable's
 * [ModDataComponents.PORTABLE_INVENTORY_TERMINAL_CRYSTAL] component as a full
 * [ItemStack]. Storing the stack (not just the pairing data) preserves any extra
 * state on the crystal — a renamed crystal stays renamed when pulled out.
 *
 * ## Opening
 *
 * Right-click always opens the Inventory Terminal menu, regardless of whether a
 * crystal is installed or links to a reachable network. If the crystal is missing,
 * blank, wrong-kind, or out of range, the menu opens in a disconnected state — the
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
        // Animation hint handled client-side; all state + menu open happen server-side.
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS

        // holderProvider re-reads the same hand slot every tick so the menu's
        // validity check and crystal write-back always see the live Portable stack
        // (the player may swap hands, reorganize inventory, etc.).
        val holderProvider: () -> ItemStack = { player.getItemInHand(hand) }

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.portable_inventory_terminal"),
            // Portable has no world position — null tells the client "no SetLayout
            // round-trip target." See InventoryTerminalOpenData for the nullable
            // terminalPos handling.
            InventoryTerminalOpenData(terminalPos = null, hasCrystalSlot = true),
            InventoryTerminalOpenData.STREAM_CODEC,
            { syncId, inv, _ ->
                // Open with no source. The menu seeds the crystal slot from the
                // Portable's component and then calls tryResolveSource() itself to
                // connect (or stay disconnected) based on the crystal's state.
                // serverLevel is the player's current level — used as a fallback
                // for recipe lookups until resolve swaps it for the network's
                // actual dimension.
                InventoryTerminalMenu.createServer(
                    syncId = syncId,
                    inv = inv,
                    level = serverLevel,
                    source = null,
                    displayPos = null,
                    hasCrystalSlot = true,
                    crystalHolderProvider = holderProvider,
                )
            }
        )
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
            // crystal is user error; surface it explicitly rather than showing a
            // "Paired to Processing Storage" line that would look fine at a glance.
            tooltip.accept(
                Component.literal("Installed crystal has wrong pairing kind.")
                    .withStyle(ChatFormatting.RED)
            )
            return
        }
        tooltip.accept(
            Component.literal("Linked to network at (${pairing.pos.x}, ${pairing.pos.y}, ${pairing.pos.z})")
                .withStyle(ChatFormatting.GRAY)
        )
        val dimId = pairing.dimension.identifier().path
        tooltip.accept(
            Component.literal("Dimension: $dimId")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    companion object {
        /**
         * Read the Link Crystal currently installed in [portable]. Returns
         * [ItemStack.EMPTY] when no crystal is installed — the component is absent on
         * a freshly-crafted Portable, and callers treat missing and empty identically.
         *
         * The returned stack is the live value stored in the component. Mutating it
         * is not supported — call [setInstalledCrystal] to write back a modified
         * crystal.
         */
        fun getInstalledCrystal(portable: ItemStack): ItemStack {
            if (portable.isEmpty || portable.item !is PortableInventoryTerminalItem) return ItemStack.EMPTY
            return portable.get(ModDataComponents.PORTABLE_INVENTORY_TERMINAL_CRYSTAL)?.stack ?: ItemStack.EMPTY
        }

        /**
         * Store [crystal] in [portable]'s crystal slot. Pass [ItemStack.EMPTY] to
         * clear the slot. When the stored value would be empty we REMOVE the component
         * entirely rather than storing an empty wrapper — keeps the item's component
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
