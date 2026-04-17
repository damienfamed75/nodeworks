package damien.nodeworks.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
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
        private const val INPUT_SECTION_H = 54  // 3 rows × 18
        private const val RESULT_X = 128
        private const val RESULT_Y = INPUT_SECTION_Y + 18  // middle row

        // Clear-all button — matches ProcessingSetScreen placement (left of the input grid,
        // vertically centered on it). Same CLEAR_BTN_X/Y since both GUIs have INPUT_SECTION_Y=13.
        private const val CLEAR_BTN_SIZE = 14
        private const val CLEAR_BTN_X = 16
        private const val CLEAR_BTN_Y = INPUT_SECTION_Y + (INPUT_SECTION_H - CLEAR_BTN_SIZE) / 2

        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Menu-button ID sent to the server to clear the 3x3 recipe grid. */
        const val BTN_ID_CLEAR_GRID = 0

        private val BG_TEXTURE = Identifier.fromNamespaceAndPath(
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

        // Clear-all button — matches ProcessingSetScreen: drawn 13×13 (1px shorter on bottom
        // and right) so the 5×5 X icon visually centers against the BUTTON 9-slice's
        // asymmetric shadow. Click hitbox stays at the full CLEAR_BTN_SIZE.
        val clearX = x + CLEAR_BTN_X
        val clearY = y + CLEAR_BTN_Y
        val clearDrawW = CLEAR_BTN_SIZE - 1
        val clearDrawH = CLEAR_BTN_SIZE - 1
        val clearHover = mouseX in clearX until clearX + CLEAR_BTN_SIZE && mouseY in clearY until clearY + CLEAR_BTN_SIZE
        (if (clearHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, clearX, clearY, clearDrawW, clearDrawH)
        Icons.X_SMALL.drawTopLeftTinted(graphics,
            clearX + (clearDrawW - 5) / 2,
            clearY + (clearDrawH - 5) / 2,
            5, 5, WHITE)

        // Player inventory — separate panel.
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val mx = mouseX.toInt()
            val my = mouseY.toInt()
            val clearX = leftPos + CLEAR_BTN_X
            val clearY = topPos + CLEAR_BTN_Y
            if (mx in clearX until clearX + CLEAR_BTN_SIZE && my in clearY until clearY + CLEAR_BTN_SIZE) {
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, BTN_ID_CLEAR_GRID)
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
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
