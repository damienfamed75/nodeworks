package damien.nodeworks.screen

import damien.nodeworks.card.CardChannel
import damien.nodeworks.card.NodeCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

/**
 * Generic settings menu used by every card that doesn't already have its own GUI
 * (IO Card, Redstone Card, Observer Card). Currently exposes one knob — the
 * channel color — but the structure has room to grow if we add more per-card
 * settings later (per-card filter strings, etc).
 *
 * Persistence pattern is the same as [StorageCardMenu]: a single-entry
 * [SimpleContainerData] holds the working value, [clickMenuButton] mutates it
 * (button id encodes the dye ordinal directly), and [removed] writes back to the
 * held ItemStack via [CardChannel.set].
 */
class CardSettingsMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val hand: InteractionHand?,
) : AbstractContainerMenu(ModScreenHandlers.CARD_SETTINGS, syncId) {

    /** Slot 0: the dye color ordinal (0..15). Defaults to white when no card is held. */
    val channelData = SimpleContainerData(1)

    init {
        if (hand != null) {
            val stack = playerInventory.player.getItemInHand(hand)
            channelData.set(0, CardChannel.get(stack).id)
        }
        addDataSlots(channelData)
    }

    fun getChannel(): DyeColor =
        runCatching { DyeColor.byId(channelData.get(0)) }.getOrDefault(DyeColor.WHITE)

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        // The screen sends the chosen dye ordinal as the button id directly.
        // Reject anything outside 0..15 so a malformed packet can't corrupt the
        // working value into a state DyeColor.byId would throw on.
        if (id in 0 until 16) {
            channelData.set(0, id)
            return true
        }
        return false
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        if (hand == null) return
        val stack = player.getItemInHand(hand)
        if (stack.item is NodeCard) {
            CardChannel.set(stack, getChannel())
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        if (hand == null) return true
        return player.getItemInHand(hand).item is NodeCard
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, data: CardSettingsOpenData): CardSettingsMenu {
            val hand = if (data.handOrdinal < InteractionHand.entries.size)
                InteractionHand.entries[data.handOrdinal] else null
            return CardSettingsMenu(syncId, playerInventory, hand)
        }
    }
}
