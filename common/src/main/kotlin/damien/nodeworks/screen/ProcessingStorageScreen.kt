package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot

class ProcessingStorageScreen(
    menu: ProcessingStorageScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ProcessingStorageScreenHandler>(menu, playerInventory, title) {

    companion object {
        // Reuse the instruction storage texture — same layout
        private val BACKGROUND = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/instruction_storage.png")

        private val LOCKED_OVERLAY = 0x50E0E0E0.toInt()
        private val OVERFLOW_OVERLAY = 0x80FF4444.toInt()
    }

    init {
        imageWidth = 176
        imageHeight = 196
        inventoryLabelY = imageHeight - 94
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.blit(
            BACKGROUND,
            leftPos, topPos,
            0f, 0f,
            imageWidth, imageHeight,
            256, 256
        )
    }

    override fun renderSlot(graphics: GuiGraphics, slot: Slot) {
        super.renderSlot(graphics, slot)

        if (slot.index >= ProcessingStorageScreenHandler.API_SLOT_COUNT) return

        if (!menu.isSlotActive(slot.index)) {
            val x = slot.x
            val y = slot.y
            if (slot.hasItem()) {
                graphics.fill(x, y, x + 16, y + 16, OVERFLOW_OVERLAY)
            } else {
                graphics.fill(x, y, x + 16, y + 16, LOCKED_OVERLAY)
            }
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
