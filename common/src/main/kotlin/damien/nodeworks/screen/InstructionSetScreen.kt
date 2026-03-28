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

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Draw semi-transparent overlay on ghost/result slots using guiOverlay (renders above items)
        for (slot in menu.slots) {
            if (slot.index in 0..9 && slot.hasItem()) {
                val x = leftPos + slot.x
                val y = topPos + slot.y
                val color = 0x808B8B8B.toInt()
                graphics.fillGradient(net.minecraft.client.renderer.RenderType.guiOverlay(), x, y, x + 16, y + 16, color, color, 0)
            }
        }

        renderTooltip(graphics, mouseX, mouseY)
    }
}
