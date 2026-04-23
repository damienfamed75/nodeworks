package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot

class BroadcastAntennaScreen(
    menu: BroadcastAntennaMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<BroadcastAntennaMenu>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val FRAME_W = 176

        // Upper panel is just the pre-composited background image — no text, no 9-slice
        // chrome. Slot frames are baked into the image; the actual slot hitboxes are
        // "invisible" (no runtime slot-grid draw).
        private const val BG_H = 120

        // Lower player-inventory panel — matches ProcessingSetScreen exactly.
        private const val INV_PANEL_Y = BG_H + 2
        private const val INV_PANEL_H = 96
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val INV_X = 8
        private const val HOTBAR_GAP = 4
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()

        private val BG_TEXTURE = Identifier.fromNamespaceAndPath(
            "nodeworks", "textures/gui/broadcast_antenna_bg.png"
        )
    }

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        val x = leftPos
        val y = topPos

        // Upper panel — single blit of the pre-composited background.
        graphics.blit(BG_TEXTURE, x, y, 0f, 0f, FRAME_W, BG_H, FRAME_W, BG_H)

        // Lower panel — player inventory (matches Processing Set exactly).
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // Empty-slot hints. Vanilla only tooltips slots that contain an item, so
        // the two bay slots look like featureless holes when empty. Mirror the
        // Receiver Antenna's "Link Crystal" label and add "Range Upgrade" for the
        // second slot.
        val chipSlot = menu.slots[0]
        val upgradeSlot = menu.slots[1]
        if (chipSlot.item.isEmpty && overSlot(chipSlot, mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.literal("Link Crystal"), mouseX, mouseY)
        } else if (upgradeSlot.item.isEmpty && overSlot(upgradeSlot, mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.literal("Range Upgrade"), mouseX, mouseY)
        }
    }

    /** True when the mouse is within the visible 18x18 slot rect. Matches
     *  vanilla's hover test (`slot.x - 1` … `slot.x + 17`), which accounts for
     *  the 1px border around the item's 16x16 area. */
    private fun overSlot(slot: Slot, mouseX: Int, mouseY: Int): Boolean {
        val sx = leftPos + slot.x - 1
        val sy = topPos + slot.y - 1
        return mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18
    }
}
