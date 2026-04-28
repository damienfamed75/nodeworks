package damien.nodeworks.screen

import damien.nodeworks.block.entity.BreakerBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Settings menu for the Breaker device. Mirrors [VariableMenu] at a reduced
 * footprint, just channel id sync via [ContainerData] (the device name is
 * one-shot via [BreakerOpenData] and round-trips through the settings payload
 * the same way Variable's name does, so it doesn't need a slot here).
 */
class BreakerMenu(
    syncId: Int,
    val devicePos: BlockPos,
    val initialName: String = "",
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS),
) : AbstractContainerMenu(ModScreenHandlers.BREAKER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val DATA_SLOTS = 1 // 0 = channelId

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: BreakerOpenData): BreakerMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, openData.channelId)
            return BreakerMenu(syncId, openData.pos, openData.deviceName, data)
        }

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: BreakerBlockEntity,
        ): BreakerMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.channel.id
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return BreakerMenu(syncId, entity.blockPos, entity.deviceName, data)
        }
    }

    val channelId: Int get() = data.get(0)

    init {
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(devicePos, 8.0)
}
