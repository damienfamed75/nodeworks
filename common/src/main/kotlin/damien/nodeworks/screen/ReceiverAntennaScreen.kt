package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class ReceiverAntennaScreen(
    menu: ReceiverAntennaMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ReceiverAntennaMenu>(menu, playerInventory, title) {

    companion object {
        private const val W = 176
        private const val TOP_BAR_H = 20
        private const val CHIP_X = 80
        private const val CHIP_Y = 36
        private const val INV_X = 8
        private const val INV_Y = 82
        private const val HOTBAR_GAP = 4
        private const val H = INV_Y + 3 * 18 + HOTBAR_GAP + 18 + 4
    }

    init {
        imageWidth = W
        imageHeight = H
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        val entity = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(menu.antennaPos) as? damien.nodeworks.network.Connectable
        val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(menu.antennaPos)
        val trimColor = if (reachable) damien.nodeworks.network.NetworkSettingsRegistry.getColor(entity?.networkId) else -1
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, trimColor)

        // Status
        val statusCode = menu.statusCode
        val statusLabel = when (statusCode) {
            0 -> "Not Linked"
            1 -> "Linked"
            2 -> "Out of Range"
            3 -> "Broadcast Not Found"
            4 -> "Frequency Mismatch"
            5 -> "Not Loaded"
            else -> "Unknown"
        }
        val statusColor = when (statusCode) {
            1 -> 0xFF55FF55.toInt()
            0 -> 0xFFFF5555.toInt()
            else -> 0xFFFFAA00.toInt()
        }
        graphics.drawString(font, "Status:", leftPos + 8, topPos + 23, 0xFFAAAAAA.toInt())
        graphics.drawString(font, statusLabel, leftPos + 52, topPos + 23, statusColor)

        graphics.drawString(font, "Insert encoded Link Crystal", leftPos + 8, topPos + 59, 0xFFAAAAAA.toInt())

        NineSlice.drawSlotGrid(graphics, leftPos + CHIP_X, topPos + CHIP_Y, 1, 1)

        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_Y - 10, 0xFFAAAAAA.toInt())
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_Y, HOTBAR_GAP)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
