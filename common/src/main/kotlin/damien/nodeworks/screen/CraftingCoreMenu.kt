package damien.nodeworks.screen

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

class CraftingCoreMenu(
    syncId: Int,
    val corePos: BlockPos,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS)
) : AbstractContainerMenu(ModScreenHandlers.CRAFTING_CORE, syncId) {

    companion object {
        const val DATA_SLOTS = 4 // bufferUsed, bufferCapacity, isFormed, isCrafting

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: CraftingCoreOpenData): CraftingCoreMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, openData.bufferUsed)
            data.set(1, openData.bufferCapacity)
            data.set(2, if (openData.isFormed) 1 else 0)
            data.set(3, if (openData.isCrafting) 1 else 0)
            return CraftingCoreMenu(syncId, openData.pos, data)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: CraftingCoreBlockEntity): CraftingCoreMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.bufferUsed
                    1 -> entity.bufferCapacity
                    2 -> if (entity.isFormed) 1 else 0
                    3 -> if (entity.isCrafting) 1 else 0
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return CraftingCoreMenu(syncId, entity.blockPos, data)
        }
    }

    val bufferUsed: Int get() = data.get(0)
    val bufferCapacity: Int get() = data.get(1)
    val isFormed: Boolean get() = data.get(2) != 0
    val isCrafting: Boolean get() = data.get(3) != 0

    init {
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(corePos, 8.0)
    }
}
