package damien.nodeworks.screen

import damien.nodeworks.screen.InstructionSetScreenHandler
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot

class InstructionSetScreen(
    menu: InstructionSetScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<InstructionSetScreenHandler>(menu, playerInventory, title) {

    companion object {
        private val BACKGROUND = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/instruction_set.png")
    }

    init {
        imageWidth = 176
        imageHeight = 166
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
        // Draw a semi-transparent overlay on ghost slots to make items appear faded
        if (slot.index in 0..8 && slot.hasItem()) {
            val x = slot.x
            val y = slot.y
            graphics.fill(x, y, x + 16, y + 16, 0x808B8B8B.toInt())
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
