package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.hasAltDownCompat
import damien.nodeworks.compat.hasControlDownCompat
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.modifierBits
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import damien.nodeworks.compat.scan
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

class InstructionSetScreen(
    menu: InstructionSetScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<InstructionSetScreenHandler>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val GHOST_OVERLAY = 0x40FFFFFF.toInt()

        private const val FRAME_W = 180
        private const val BG_H = 78  // upper panel height (matches exported PNG)

        // Lower (inventory) panel, identical layout to ProcessingSetScreen.
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

        // Clear-all + substitutions toggle, stacked left of the input grid and
        // centered as a pair against the 3-row grid. The pair is the
        // substitutions button on top, a 2px gap, then the [x] beneath.
        private const val CLEAR_BTN_SIZE = 14
        private const val CLEAR_BTN_X = 16
        private const val SUBST_BTN_SIZE = 14
        private const val SUBST_BTN_X = CLEAR_BTN_X
        private const val SUBST_ICON_SIZE = 9
        private const val BTN_PAIR_GAP = 2
        private const val BTN_PAIR_H = SUBST_BTN_SIZE + BTN_PAIR_GAP + CLEAR_BTN_SIZE
        private const val SUBST_BTN_Y = INPUT_SECTION_Y + (INPUT_SECTION_H - BTN_PAIR_H) / 2
        private const val CLEAR_BTN_Y = SUBST_BTN_Y + SUBST_BTN_SIZE + BTN_PAIR_GAP

        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Menu-button ID sent to the server to clear the 3x3 recipe grid. */
        const val BTN_ID_CLEAR_GRID = 0
        /** Menu-button ID sent to the server to toggle the substitutions flag. */
        const val BTN_ID_TOGGLE_SUBSTITUTIONS = 1

        private val BG_TEXTURE = Identifier.fromNamespaceAndPath(
            "nodeworks", "textures/gui/instruction_set_bg.png"
        )
    }

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    /** Hover tooltip queued during [extractBackground]'s button rendering and
     *  drawn after the rest of the GUI in [extractRenderState], so it overlays
     *  on top of every other layer. Mirrors the pattern in StorageCardScreen. */
    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
        val x = leftPos
        val y = topPos

        // Upper panel, single blit of the pre-composited static background.
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

        // Clear-all button, matches ProcessingSetScreen: drawn 13×13 (1px shorter on bottom
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
        if (clearHover) queueTooltip(mouseX, mouseY, "Clear grid")

        // Substitutions toggle. Icon reflects the server-synced flag from
        // [substitutionsData] so it stays in sync without bespoke payloads.
        val substX = x + SUBST_BTN_X
        val substY = y + SUBST_BTN_Y
        val substDrawW = SUBST_BTN_SIZE - 1
        val substDrawH = SUBST_BTN_SIZE - 1
        val substHover = mouseX in substX until substX + SUBST_BTN_SIZE && mouseY in substY until substY + SUBST_BTN_SIZE
        (if (substHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, substX, substY, substDrawW, substDrawH)
        val substIcon = if (menu.getSubstitutions()) Icons.SUBSTITUTIONS_ON else Icons.SUBSTITUTIONS_OFF
        substIcon.drawTopLeft(
            graphics,
            substX + (substDrawW - SUBST_ICON_SIZE) / 2,
            substY + (substDrawH - SUBST_ICON_SIZE) / 2,
            SUBST_ICON_SIZE, SUBST_ICON_SIZE,
        )
        if (substHover) {
            queueTooltip(
                mouseX, mouseY,
                "Substitutions: ${if (menu.getSubstitutions()) "On" else "Off"}",
                "Allow tag substitutions (e.g. any plank type).",
                "Click to toggle.",
            )
        }

        // Player inventory, separate panel.
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (button == 0) {
            val mx = mouseX.toInt()
            val my = mouseY.toInt()
            val clearX = leftPos + CLEAR_BTN_X
            val clearY = topPos + CLEAR_BTN_Y
            if (mx in clearX until clearX + CLEAR_BTN_SIZE && my in clearY until clearY + CLEAR_BTN_SIZE) {
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, BTN_ID_CLEAR_GRID)
                return true
            }
            val substX = leftPos + SUBST_BTN_X
            val substY = topPos + SUBST_BTN_Y
            if (mx in substX until substX + SUBST_BTN_SIZE && my in substY until substY + SUBST_BTN_SIZE) {
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, BTN_ID_TOGGLE_SUBSTITUTIONS)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // Dim overlay on ghost/result slots that contain an item.
        for (slot in menu.slots) {
            if (slot.index in 0..9 && slot.hasItem()) {
                val sx = leftPos + slot.x
                val sy = topPos + slot.y
                graphics.fillGradient(sx, sy, sx + 16, sy + 16, GHOST_OVERLAY, GHOST_OVERLAY)
            }
        }
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }
}
