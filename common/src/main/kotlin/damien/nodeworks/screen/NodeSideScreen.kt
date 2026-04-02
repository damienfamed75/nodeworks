package damien.nodeworks.screen

import damien.nodeworks.card.StorageCard
import damien.nodeworks.network.*
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.NodeSideScreenHandler

import damien.nodeworks.screen.widget.SlicedButton
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class NodeSideScreen(
    menu: NodeSideScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<NodeSideScreenHandler>(menu, playerInventory, title) {

    companion object {
        // Layout constants
        private const val W = 176
        private const val TOP_BAR_H = 20
        private const val CARD_AREA_Y = 24       // top bar + 4px padding
        private const val CARD_GRID_X = 62        // centered 3x3 grid
        private const val INV_LABEL_Y = 82        // "Inventory" label
        private const val INV_Y = 92              // player inventory start
        private const val INV_X = 8
        private const val HOTBAR_GAP = 4
        private const val H = INV_Y + 3 * 18 + HOTBAR_GAP + 18 + 8  // = 172
    }

    private var priorityValue = 0
    private var hasStorageCard = false

    init {
        imageWidth = W
        imageHeight = H
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        detectStorageCard()

        if (hasStorageCard) {
            addRenderableWidget(SlicedButton.create(
                leftPos + 120, topPos + CARD_AREA_Y + 20, 14, 14, "-"
            ) { _ ->
                priorityValue = (priorityValue - 1).coerceIn(0, 99)
                sendPriorityUpdate()
            })

            addRenderableWidget(SlicedButton.create(
                leftPos + 155, topPos + CARD_AREA_Y + 20, 14, 14, "+"
            ) { _ ->
                priorityValue = (priorityValue + 1).coerceIn(0, 99)
                sendPriorityUpdate()
            })
        }
    }

    private fun detectStorageCard() {
        for (i in 0 until 9) {
            val stack = menu.getSlot(i).item
            if (stack.item is StorageCard) {
                hasStorageCard = true
                priorityValue = StorageCard.getPriority(stack)
                return
            }
        }
        hasStorageCard = false
    }

    private fun findStorageCardSlot(): Int {
        for (i in 0 until 9) {
            val stack = menu.getSlot(i).item
            if (stack.item is StorageCard) return i
        }
        return -1
    }

    private fun sendPriorityUpdate() {
        val slot = findStorageCardSlot()
        if (slot < 0) return
        PlatformServices.clientNetworking.sendToServer(
            SetStoragePriorityPayload(
                menu.getNodePos(),
                menu.getSide().ordinal,
                slot,
                priorityValue
            )
        )
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Window frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Top bar with network color trim (gray if disconnected)
        val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(menu.getNodePos())
        val trimColor = if (reachable) {
            val nodeEntity = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(menu.getNodePos()) as? damien.nodeworks.network.Connectable
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(nodeEntity?.networkId)
        } else {
            -1
        }
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, trimColor)

        // 3x3 card slot grid
        NineSlice.drawSlotGrid(graphics, leftPos + CARD_GRID_X, topPos + CARD_AREA_Y, 3, 3)

        // Priority controls
        if (hasStorageCard) {
            graphics.drawString(font, "Priority", leftPos + 118, topPos + CARD_AREA_Y + 10, 0xFFAAAAAA.toInt())
            val px = leftPos + 134
            val py = topPos + CARD_AREA_Y + 22
            graphics.drawString(font, "$priorityValue", px + (14 - font.width("$priorityValue")) / 2, py + 3, 0xFFFFFFFF.toInt())
        }

        // Inventory label
        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_LABEL_Y, 0xFFAAAAAA.toInt())

        // Player inventory (3x9 + hotbar)
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_Y, HOTBAR_GAP)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val prevHas = hasStorageCard
        detectStorageCard()
        if (prevHas != hasStorageCard) {
            rebuildWidgets()
        }

        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
