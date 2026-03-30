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

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos; val y = topPos; val w = imageWidth

        // Dark background
        graphics.fill(x, y, x + w, y + imageHeight, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(x, y, x + w, y + 20, 0xFF3C3C3C.toInt())
        graphics.fill(x, y + 19, x + w, y + 20, 0xFF555555.toInt())
        graphics.drawString(font, title, x + 6, y + 6, 0xFFFFFFFF.toInt())

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
        graphics.drawString(font, "Status:", x + 8, y + 26, 0xFFAAAAAA.toInt())
        graphics.drawString(font, statusLabel, x + 52, y + 26, statusColor)

        // Chip slot background
        val sx = x + 80; val sy = y + 35
        graphics.fill(sx - 1, sy - 1, sx + 17, sy, 0xFF111111.toInt())
        graphics.fill(sx - 1, sy - 1, sx, sy + 17, 0xFF111111.toInt())
        graphics.fill(sx + 16, sy - 1, sx + 17, sy + 17, 0xFF444444.toInt())
        graphics.fill(sx - 1, sy + 16, sx + 17, sy + 17, 0xFF444444.toInt())
        graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF1A1A1A.toInt())

        // Label
        graphics.drawString(font, "Insert encoded Link Crystal", x + 8, y + 56, 0xFFAAAAAA.toInt())

        // Separator
        graphics.fill(x + 4, y + 72, x + w - 4, y + 73, 0xFF555555.toInt())

        // Player inventory slots
        for (i in 1 until menu.slots.size) {
            val slot = menu.slots[i]
            val slotX = x + slot.x; val slotY = y + slot.y
            graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0xFF1A1A1A.toInt())
            graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY, 0xFF111111.toInt())
            graphics.fill(slotX - 1, slotY - 1, slotX, slotY + 17, 0xFF111111.toInt())
            graphics.fill(slotX + 16, slotY - 1, slotX + 17, slotY + 17, 0xFF444444.toInt())
            graphics.fill(slotX - 1, slotY + 16, slotX + 17, slotY + 17, 0xFF444444.toInt())
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
