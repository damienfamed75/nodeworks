package damien.nodeworks.screen

import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

class NetworkControllerMenu(
    syncId: Int,
    val controllerPos: BlockPos,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS)
) : AbstractContainerMenu(ModScreenHandlers.NETWORK_CONTROLLER, syncId) {

    companion object {
        const val DATA_SLOTS = 3

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: NetworkControllerOpenData): NetworkControllerMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, (openData.networkColor shr 16) and 0xFF)
            data.set(1, openData.networkColor and 0xFFFF)
            data.set(2, openData.redstoneMode)
            return NetworkControllerMenu(syncId, openData.pos, data)
        }

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: NetworkControllerBlockEntity
        ): NetworkControllerMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> (entity.networkColor shr 16) and 0xFF
                    1 -> entity.networkColor and 0xFFFF
                    2 -> entity.redstoneMode
                    else -> 0
                }
                override fun set(index: Int, value: Int) {
                    when (index) {
                        0 -> entity.networkColor = (value shl 16) or (entity.networkColor and 0xFFFF)
                        1 -> entity.networkColor = (entity.networkColor and 0xFF0000) or (value and 0xFFFF)
                        2 -> entity.redstoneMode = value
                    }
                }
                override fun getCount(): Int = DATA_SLOTS
            }
            return NetworkControllerMenu(syncId, entity.blockPos, data)
        }
    }

    val networkColor: Int get() = (data.get(0) shl 16) or (data.get(1) and 0xFFFF)
    val redstoneMode: Int get() = data.get(2)

    init {
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(controllerPos, 8.0)
    }
}
