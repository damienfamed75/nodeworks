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

class ProcessingStorageScreen(
    menu: ProcessingStorageScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ProcessingStorageScreenHandler>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val FRAME_W = 176
        private const val FRAME_H = 196
        private const val TOP_BAR_H = 20
        private const val INV_LABEL_Y = 104
        private const val INV_GRID_Y = 114
        private const val HOTBAR_GAP = 4
    }

    /** Cached network color — resolved via BFS once per open (can be expensive). */
    private var cachedNetworkColor: Int? = null

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Resolve the network color via the same path TerminalScreen/InventoryTerminalScreen use.
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

        // Outer frame.
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Top bar tinted with network color (same call pattern as NetworkControllerScreen).
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor)

        // Card grid — 2 cols × 4 rows.
        for (row in 0 until ProcessingStorageScreenHandler.ROWS) {
            for (col in 0 until ProcessingStorageScreenHandler.COLS) {
                val sx = leftPos + ProcessingStorageScreenHandler.GRID_X + col * 18
                val sy = topPos + ProcessingStorageScreenHandler.GRID_Y + row * 18
                NineSlice.SLOT.draw(graphics, sx - 1, sy - 1, 18, 18)
            }
        }

        // Inventory label + slot frames.
        graphics.drawString(font, "Inventory",
            leftPos + ProcessingStorageScreenHandler.INV_X,
            topPos + INV_LABEL_Y,
            LABEL_COLOR)
        NineSlice.drawPlayerInventory(
            graphics,
            leftPos + ProcessingStorageScreenHandler.INV_X,
            topPos + INV_GRID_Y,
            HOTBAR_GAP
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)
    }
}
