package damien.nodeworks.screen

import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

class VariableMenu(
    syncId: Int,
    val variablePos: BlockPos,
    val initialName: String = "",
    val initialValue: String = "",
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS)
) : AbstractContainerMenu(ModScreenHandlers.VARIABLE, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = variablePos

    companion object {
        const val DATA_SLOTS = 3 // 0=variableType, 1=boolValue, 2=channelId

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: VariableOpenData): VariableMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, openData.variableType)
            data.set(1, if (openData.variableValue == "true") 1 else 0)
            data.set(2, openData.channelId)
            return VariableMenu(syncId, openData.pos, openData.variableName, openData.variableValue, data)
        }

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: VariableBlockEntity
        ): VariableMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.variableType.ordinal
                    1 -> if (entity.variableValue == "true") 1 else 0
                    2 -> entity.channel.id
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return VariableMenu(syncId, entity.blockPos, entity.variableName, entity.variableValue, data)
        }
    }

    val variableType: Int get() = data.get(0)
    val boolValue: Boolean get() = data.get(1) != 0
    val channelId: Int get() = data.get(2)

    init {
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(variablePos, 8.0)
    }
}
