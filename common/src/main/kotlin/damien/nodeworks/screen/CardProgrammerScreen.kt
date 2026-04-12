package damien.nodeworks.screen

import damien.nodeworks.screen.widget.SlicedButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class CardProgrammerScreen(
    menu: CardProgrammerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CardProgrammerMenu>(menu, playerInventory, title) {

    companion object {
        private const val W = 176
        private const val TOP_BAR_H = 20
        private const val INV_Y = 102
        private const val HOTBAR_GAP = 4
        private const val H = INV_Y + 3 * 18 + HOTBAR_GAP + 18 + 8  // 186
    }

    init {
        imageWidth = W
        imageHeight = H
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()

        // Counter [-] button
        addRenderableWidget(SlicedButton.create(
            leftPos + 68, topPos + 62, 14, 14, "-"
        ) { _ ->
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0)
        })

        // Counter [+] button
        addRenderableWidget(SlicedButton.create(
            leftPos + 100, topPos + 62, 14, 14, "+"
        ) { _ ->
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1)
        })
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Window frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Title bar
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H)

        // Template slot
        NineSlice.drawSlotGrid(graphics, leftPos + 32, topPos + 38, 1, 1)
        graphics.drawString(font, "Template", leftPos + 20, topPos + 28, 0xFFAAAAAA.toInt())

        // Arrow between slots
        Icons.ARROW_RIGHT.draw(graphics, leftPos + 76, topPos + 40)

        // Input slot
        NineSlice.drawSlotGrid(graphics, leftPos + 120, topPos + 38, 1, 1)
        graphics.drawString(font, "Input", leftPos + 122, topPos + 28, 0xFFAAAAAA.toInt())

        // Gray overlay on input slot when no template
        if (!menu.hasTemplate()) {
            graphics.fill(leftPos + 121, topPos + 39, leftPos + 137, topPos + 55, 0x80000000.toInt())
        }

        // Counter row
        graphics.drawString(font, "Counter:", leftPos + 14, topPos + 65, 0xFFAAAAAA.toInt())
        val counterStr = "${menu.getCounter()}"
        val counterX = leftPos + 84 + (14 - font.width(counterStr)) / 2
        graphics.drawString(font, counterStr, counterX, topPos + 65, 0xFFFFFFFF.toInt())

        // Next name preview
        val nextName = menu.getNextName()
        if (nextName.isNotEmpty()) {
            graphics.drawString(font, "Next: $nextName", leftPos + 14, topPos + 80, 0xFF888888.toInt())
        }

        // Inventory label
        graphics.drawString(font, "Inventory", leftPos + 8, topPos + 92, 0xFFAAAAAA.toInt())

        // Player inventory
        NineSlice.drawPlayerInventory(graphics, leftPos + 8, topPos + INV_Y, HOTBAR_GAP)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
