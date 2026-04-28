package damien.nodeworks.screen

import damien.nodeworks.card.CardChannel
import damien.nodeworks.card.StorageCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

class StorageCardMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val hand: InteractionHand?
) : AbstractContainerMenu(ModScreenHandlers.STORAGE_CARD, syncId) {

    val priorityData = SimpleContainerData(1)
    val channelData = SimpleContainerData(1)

    init {
        if (hand != null) {
            val stack = playerInventory.player.getItemInHand(hand)
            priorityData.set(0, StorageCard.getPriority(stack))
            channelData.set(0, CardChannel.get(stack).id)
        }
        addDataSlots(priorityData)
        addDataSlots(channelData)
    }

    fun getPriority(): Int = priorityData.get(0)

    fun getChannel(): DyeColor =
        runCatching { DyeColor.byId(channelData.get(0)) }.getOrDefault(DyeColor.WHITE)

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        when {
            id == 0 -> priorityData.set(0, (priorityData.get(0) - 1).coerceIn(0, 999))
            id == 1 -> priorityData.set(0, (priorityData.get(0) + 1).coerceIn(0, 999))
            id in 100..1099 -> priorityData.set(0, (id - 100).coerceIn(0, 999)) // direct value set
            // Channel picker uses ids 2000..2015, outside the priority range so the two
            // controls can't accidentally collide if one expands later.
            id in 2000..2015 -> channelData.set(0, id - 2000)
        }
        return true
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        if (hand == null) return
        val stack = player.getItemInHand(hand)
        if (stack.item is StorageCard) {
            StorageCard.setPriority(stack, priorityData.get(0))
            CardChannel.set(stack, getChannel())
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        if (hand == null) return true
        return player.getItemInHand(hand).item is StorageCard
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, data: StorageCardOpenData): StorageCardMenu {
            val hand = if (data.handOrdinal < InteractionHand.entries.size) InteractionHand.entries[data.handOrdinal] else null
            return StorageCardMenu(syncId, playerInventory, hand)
        }
    }
}
