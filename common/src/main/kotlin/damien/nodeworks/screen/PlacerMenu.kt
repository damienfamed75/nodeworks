package damien.nodeworks.screen

import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Settings menu for the Placer device. Live-syncing fields (channel,
 * redstoneMode, previewArea) ride on [ContainerData] so external mutations
 * reflect in the open screen. Non-syncing fields (deviceName, filterRule)
 * seed from [PlacerOpenData] and use commit-style updates via
 * [DeviceSettingsPayload], last-write-wins.
 */
class PlacerMenu(
    syncId: Int,
    val devicePos: BlockPos,
    val initialName: String,
    val initialFilter: String,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS),
) : AbstractContainerMenu(ModScreenHandlers.PLACER, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = devicePos

    companion object {
        const val DATA_SLOTS = 3
        private const val DATA_CHANNEL = 0
        private const val DATA_REDSTONE = 1
        private const val DATA_PREVIEW = 2

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: PlacerOpenData): PlacerMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(DATA_CHANNEL, openData.channelId)
            data.set(DATA_REDSTONE, openData.redstoneMode)
            data.set(DATA_PREVIEW, if (openData.previewArea) 1 else 0)
            return PlacerMenu(syncId, openData.pos, openData.deviceName, openData.filterRule, data)
        }

        fun createServer(
            syncId: Int,
            playerInventory: Inventory,
            entity: PlacerBlockEntity,
        ): PlacerMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    DATA_CHANNEL -> entity.channel.id
                    DATA_REDSTONE -> entity.redstoneMode
                    DATA_PREVIEW -> if (entity.previewArea) 1 else 0
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return PlacerMenu(syncId, entity.blockPos, entity.deviceName, entity.filterRule, data)
        }
    }

    val channelId: Int get() = data.get(DATA_CHANNEL)
    val redstoneMode: Int get() = data.get(DATA_REDSTONE)
    val previewArea: Boolean get() = data.get(DATA_PREVIEW) != 0

    init {
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(devicePos, 8.0)
}
