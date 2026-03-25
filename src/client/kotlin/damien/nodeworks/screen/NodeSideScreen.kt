package damien.nodeworks.screen

import damien.nodeworks.screen.NodeSideScreenHandler
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

class NodeSideScreen(
    menu: NodeSideScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<NodeSideScreenHandler>(menu, playerInventory, title) {

    companion object {
        private val BACKGROUND = Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/node_side.png")
    }

    init {
        imageWidth = 176
        imageHeight = 172
        inventoryLabelY = imageHeight - 94
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Draw a simple dark background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFC6C6C6.toInt())
        // Darker border
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF555555.toInt())
        graphics.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, 0xFF555555.toInt())
        graphics.fill(leftPos, topPos, leftPos + 1, topPos + imageHeight, 0xFF555555.toInt())
        graphics.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF555555.toInt())

        // Card slot highlight (slot 0 is at x=80, y=18 relative to the menu)
        val cardX = leftPos + 80 - 1
        val cardY = topPos + 18 - 1
        graphics.fill(cardX, cardY, cardX + 18, cardY + 18, 0xFF8BE08E.toInt())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
