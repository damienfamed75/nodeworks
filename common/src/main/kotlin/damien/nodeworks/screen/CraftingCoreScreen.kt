package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class CraftingCoreScreen(
    menu: CraftingCoreMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CraftingCoreMenu>(menu, playerInventory, title) {

    init {
        imageWidth = 180
        imageHeight = 100
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Dark background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 20, 0xFF3C3C3C.toInt())
        graphics.fill(leftPos, topPos + 19, leftPos + imageWidth, topPos + 20, 0xFF555555.toInt())
        graphics.drawString(font, title, leftPos + 6, topPos + 6, 0xFFFFFFFF.toInt())

        val contentLeft = leftPos + 8
        val contentTop = topPos + 26

        // Status
        val statusLabel = when {
            !menu.isFormed -> "Not Formed"
            menu.isCrafting -> "Crafting..."
            else -> "Idle"
        }
        val statusColor = when {
            !menu.isFormed -> 0xFFFF5555.toInt()
            menu.isCrafting -> 0xFF55FF55.toInt()
            else -> 0xFFAAAAAA.toInt()
        }
        graphics.drawString(font, "Status:", contentLeft, contentTop, 0xFFAAAAAA.toInt())
        graphics.drawString(font, statusLabel, contentLeft + 50, contentTop, statusColor)

        // Buffer bar
        val barTop = contentTop + 16
        graphics.drawString(font, "Buffer:", contentLeft, barTop, 0xFFAAAAAA.toInt())

        val barX = contentLeft + 50
        val barW = imageWidth - 66
        val barH = 10

        // Bar background
        graphics.fill(barX, barTop, barX + barW, barTop + barH, 0xFF1E1E1E.toInt())
        // Inset border
        graphics.fill(barX - 1, barTop - 1, barX + barW + 1, barTop, 0xFF555555.toInt())
        graphics.fill(barX - 1, barTop - 1, barX, barTop + barH + 1, 0xFF555555.toInt())
        graphics.fill(barX + barW, barTop - 1, barX + barW + 1, barTop + barH + 1, 0xFF3C3C3C.toInt())
        graphics.fill(barX - 1, barTop + barH, barX + barW + 1, barTop + barH + 1, 0xFF3C3C3C.toInt())

        // Fill
        if (menu.bufferCapacity > 0) {
            val fillW = (barW * menu.bufferUsed.toLong() / menu.bufferCapacity).toInt().coerceAtMost(barW)
            if (fillW > 0) {
                val fillColor = if (menu.bufferUsed > menu.bufferCapacity * 0.9) 0xFFFF5555.toInt() else 0xFF55AA55.toInt()
                graphics.fill(barX, barTop, barX + fillW, barTop + barH, fillColor)
            }
        }

        // Count text
        val countText = "${menu.bufferUsed} / ${menu.bufferCapacity}"
        val countWidth = font.width(countText)
        graphics.drawString(font, countText, barX + (barW - countWidth) / 2, barTop + 1, 0xFFFFFFFF.toInt())

        // Capacity info
        val capacityTop = barTop + 16
        if (!menu.isFormed) {
            graphics.drawString(font, "Place Crafting Storage adjacent", contentLeft, capacityTop, 0xFF888888.toInt())
            graphics.drawString(font, "to form the CPU", contentLeft, capacityTop + 11, 0xFF888888.toInt())
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
    }
}
