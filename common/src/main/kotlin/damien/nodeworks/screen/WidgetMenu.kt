package damien.nodeworks.screen

import damien.nodeworks.block.entity.WidgetBlockEntity
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Widget settings menu. [widgetType] and [channelId] ride on a [ContainerData]
 * so server-side mutations push down live; the name / slider bounds / options
 * seed once from [WidgetOpenData] and use the device-screen commit pattern.
 */
class WidgetMenu(
    syncId: Int,
    val widgetPos: BlockPos,
    val initialName: String = "",
    val initialSliderMin: Float = 0f,
    val initialSliderMax: Float = 10f,
    val initialSliderStep: Float = 1f,
    val initialOptions: String = "",
    val initialButtonHold: Int = 0,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS),
) : AbstractContainerMenu(ModScreenHandlers.WIDGET, syncId), BlockBackedMenu {

    override val blockBackingPos: BlockPos get() = widgetPos

    companion object {
        const val DATA_SLOTS = 2 // 0=widgetType, 1=channelId

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: WidgetOpenData): WidgetMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, openData.widgetType)
            data.set(1, openData.channelId)
            return WidgetMenu(
                syncId, openData.pos, openData.widgetName,
                openData.sliderMin, openData.sliderMax, openData.sliderStep,
                openData.options, openData.buttonHoldTicks, data,
            )
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: WidgetBlockEntity): WidgetMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.widgetType.ordinal
                    1 -> entity.channel.id
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            return WidgetMenu(
                syncId, entity.blockPos, entity.widgetName,
                entity.sliderMin, entity.sliderMax, entity.sliderStep,
                entity.radioOptions.joinToString("\n"), entity.buttonHoldTicks, data,
            )
        }
    }

    val widgetType: Int get() = data.get(0)
    val channelId: Int get() = data.get(1)

    init {
        addDataSlots(data)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().closerThan(widgetPos, 8.0)
}
