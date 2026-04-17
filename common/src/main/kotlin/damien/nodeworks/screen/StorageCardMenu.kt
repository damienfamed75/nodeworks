package damien.nodeworks.screen

import damien.nodeworks.card.StorageCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

class StorageCardMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val hand: InteractionHand?
) : AbstractContainerMenu(ModScreenHandlers.STORAGE_CARD, syncId) {

    val priorityData = SimpleContainerData(1)

    init {
        if (hand != null) {
            val stack = playerInventory.player.getItemInHand(hand)
            priorityData.set(0, StorageCard.getPriority(stack))
        }
        addDataSlots(priorityData)
    }

    fun getPriority(): Int = priorityData.get(0)

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        when {
            id == 0 -> priorityData.set(0, (priorityData.get(0) - 1).coerceIn(0, 999))
            id == 1 -> priorityData.set(0, (priorityData.get(0) + 1).coerceIn(0, 999))
            id in 100..1099 -> priorityData.set(0, (id - 100).coerceIn(0, 999)) // direct value set
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
