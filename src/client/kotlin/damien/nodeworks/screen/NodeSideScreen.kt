package damien.nodeworks.screen

import damien.nodeworks.card.StorageCard
import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.screen.NodeSideScreenHandler
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
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

    private var priorityValue = 0
    private var hasStorageCard = false

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()

        // Check if any slot has a Storage Card and read its priority
        detectStorageCard()

        if (hasStorageCard) {
            // Priority - button
            addRenderableWidget(Button.builder(Component.literal("-")) { _ ->
                priorityValue = (priorityValue - 1).coerceIn(0, 99)
                sendPriorityUpdate()
            }.bounds(leftPos + 120, topPos + 17, 14, 14).build())

            // Priority + button
            addRenderableWidget(Button.builder(Component.literal("+")) { _ ->
                priorityValue = (priorityValue + 1).coerceIn(0, 99)
                sendPriorityUpdate()
            }.bounds(leftPos + 155, topPos + 17, 14, 14).build())
        }
    }

    private fun detectStorageCard() {
        // Scan the 9 node slots for a Storage Card
        val side = menu.getSide()
        val offset = side.ordinal * 9
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

    private var storageCardSlotIndex: Int = -1

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
        ClientPlayNetworking.send(
            TerminalPackets.SetStoragePriorityPayload(
                menu.getNodePos(),
                menu.getSide().ordinal,
                slot,
                priorityValue
            )
        )
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

        // Draw priority display if a Storage Card is present
        if (hasStorageCard) {
            val px = leftPos + 134
            val py = topPos + 19
            graphics.drawString(font, "$priorityValue", px, py, 0xFFFFFFFF.toInt())

            // Label
            graphics.drawString(font, "Priority", leftPos + 118, topPos + 8, 0xFFAAAAAA.toInt())
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Re-detect storage card each frame in case player adds/removes one
        val prevHas = hasStorageCard
        detectStorageCard()
        if (prevHas != hasStorageCard) {
            rebuildWidgets()
        }

        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }
}
