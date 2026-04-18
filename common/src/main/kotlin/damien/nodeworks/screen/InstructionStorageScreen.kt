package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class InstructionStorageScreen(
    menu: InstructionStorageScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<InstructionStorageScreenHandler>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val FRAME_W = 176
        private const val FRAME_H = 232  // taller than ProcessingStorage because the grid has 6 rows vs 4
        private const val TOP_BAR_H = 20
        private const val INV_LABEL_Y = 140
        private const val INV_GRID_Y = 150
        private const val HOTBAR_GAP = 4
    }

    private var cachedNetworkColor: Int? = null

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Resolve network color the same way TerminalScreen / ProcessingStorageScreen do.
        val mc = net.minecraft.client.Minecraft.getInstance()
        val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(menu.storagePos)
        val networkColor = if (reachable) {
            val entity = mc.level?.getBlockEntity(menu.storagePos) as? damien.nodeworks.network.Connectable
            if (entity?.networkId != null) {
                damien.nodeworks.network.NetworkSettingsRegistry.getColor(entity.networkId)
            } else {
                cachedNetworkColor ?: damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(
                    mc.level, menu.storagePos
                ).also { cachedNetworkColor = it }
            }
        } else {
            -1
        }

        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor)

        for (row in 0 until InstructionStorageScreenHandler.ROWS) {
            for (col in 0 until InstructionStorageScreenHandler.COLS) {
                val sx = leftPos + InstructionStorageScreenHandler.GRID_X + col * 18
                val sy = topPos + InstructionStorageScreenHandler.GRID_Y + row * 18
                NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
            }
        }

        graphics.drawString(font, "Inventory",
            leftPos + InstructionStorageScreenHandler.INV_X,
            topPos + INV_LABEL_Y,
            LABEL_COLOR)
        NineSlice.drawPlayerInventory(
            graphics,
            leftPos + InstructionStorageScreenHandler.INV_X,
            topPos + INV_GRID_Y,
            HOTBAR_GAP
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)
    }
}
