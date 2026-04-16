package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

class InstructionSetScreen(
    menu: InstructionSetScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<InstructionSetScreenHandler>(menu, playerInventory, title) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val GHOST_OVERLAY = 0x40FFFFFF.toInt()

        private const val FRAME_W = 180
        private const val BG_H = 78  // upper panel height (matches exported PNG)

        // Lower (inventory) panel — identical layout to ProcessingSetScreen.
        private const val INV_PANEL_Y = 80
        private const val INV_PANEL_H = 96
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val INV_X = 8
        private const val HOTBAR_GAP = 4
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H

        // Slot layout (centered vertically in the 78-tall upper panel).
        private const val INPUT_COL_X = 36
        private const val INPUT_SECTION_Y = 13
        private const val RESULT_X = 128
        private const val RESULT_Y = INPUT_SECTION_Y + 18  // middle row

        private val BG_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "nodeworks", "textures/gui/instruction_set_bg.png"
        )
    }

    init {
        imageWidth = FRAME_W
        imageHeight = FRAME_H
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos

        // Upper panel — single blit of the pre-composited static background.
        graphics.blit(BG_TEXTURE, x, y, 0f, 0f, FRAME_W, BG_H, FRAME_W, BG_H)

        // Slot frames drawn at runtime over the black placeholders in the PNG.
        for (row in 0..2) {
            for (col in 0..2) {
                val sx = x + INPUT_COL_X + col * 18
                val sy = y + INPUT_SECTION_Y + row * 18
                NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
            }
        }
        NineSlice.SLOT.draw(graphics, x + RESULT_X - 1, y + RESULT_Y - 1, 18, 18)

        // Player inventory — separate panel.
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Dim overlay on ghost/result slots that contain an item.
        for (slot in menu.slots) {
            if (slot.index in 0..9 && slot.hasItem()) {
                val sx = leftPos + slot.x
                val sy = topPos + slot.y
                graphics.fillGradient(
                    net.minecraft.client.renderer.RenderType.guiOverlay(),
                    sx, sy, sx + 16, sy + 16, GHOST_OVERLAY, GHOST_OVERLAY, 0
                )
            }
        }

        renderTooltip(graphics, mouseX, mouseY)
    }
}
