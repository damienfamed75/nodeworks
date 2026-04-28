package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class ReceiverAntennaScreen(
    menu: ReceiverAntennaMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ReceiverAntennaMenu>(menu, playerInventory, title, W, H) {

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
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
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
            6 -> "Dimension Mismatch"
            7 -> "Wrong Source Kind"
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

        // Crystal slot frame, same 20x20 decoration used by the Portable Inventory
        // Terminal. The inner 18x18 is offset by 1px so items rendered by the MC
        // Slot at (CHIP_X, CHIP_Y) line up with the frame's center.
        NineSlice.PORTABLE_CRYSTAL_SLOT_FRAME.draw(
            graphics, leftPos + CHIP_X - 1, topPos + CHIP_Y - 1, 20, 20,
        )

        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_Y - 10, 0xFFAAAAAA.toInt())
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_Y, HOTBAR_GAP)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)

        // Empty-slot hint: vanilla only shows tooltips for slots that have an
        // item, so hovering the empty crystal slot gives no feedback. Mirror the
        // Portable Inventory Terminal's "Link Crystal" label for consistency.
        val slotX = leftPos + CHIP_X
        val slotY = topPos + CHIP_Y
        val overSlot = mouseX >= slotX && mouseX < slotX + 18 &&
            mouseY >= slotY && mouseY < slotY + 18
        if (overSlot && menu.slots[0].item.isEmpty) {
            graphics.renderTooltip(font, Component.literal("Link Crystal"), mouseX, mouseY)
        }
    }
}
