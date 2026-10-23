package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Interface GUI. The buffer row with a line of explanation above the player inventory.
 * Nothing to configure, the network behind the block decides what it shows.
 */
class InterfaceScreen(
    menu: InterfaceMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<InterfaceMenu>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val HINT_COLOR = 0xFF777777.toInt()
        private const val FRAME_W = 176
        private const val TOP_PANEL_H = 86
        private const val INV_PANEL_Y = 88
        private const val INV_PANEL_H = 96
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H
        private const val INV_X = 8
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val HOTBAR_GAP = 4
        private const val TITLE_Y = 7
        private const val HINT_Y_1 = 48
        private const val HINT_Y_2 = 60
    }

    init {
        titleLabelY = -9999
        inventoryLabelY = -9999
        imageWidth = FRAME_W
        imageHeight = FRAME_H
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        val x = leftPos
        val y = topPos
        NineSlice.WINDOW_FRAME.draw(graphics, x, y, FRAME_W, TOP_PANEL_H)
        graphics.drawString(font, title.string, x + 8, y + TITLE_Y, LABEL_COLOR)
        for (col in 0 until InterfaceMenu.SLOT_COUNT) {
            NineSlice.SLOT.draw(graphics, x + InterfaceMenu.GRID_X + col * 18 - 1, y + InterfaceMenu.GRID_Y - 1, 18, 18)
        }
        graphics.drawString(font, "Other networks read this one here.", x + 8, y + HINT_Y_1, HINT_COLOR)
        graphics.drawString(font, "Items put here go into storage.", x + 8, y + HINT_Y_2, HINT_COLOR)

        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }
}
