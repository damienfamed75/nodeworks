package damien.nodeworks.screen

import damien.nodeworks.screen.NodeSideScreenHandler
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
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
        imageHeight = 166
        inventoryLabelY = imageHeight - 94
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            BACKGROUND,
            leftPos, topPos,
            0f, 0f,
            imageWidth, imageHeight,
            256, 256
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
