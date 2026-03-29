package damien.nodeworks.screen

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.network.SetProcessingApiDataPayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class ProcessingApiCardScreen(
    menu: ProcessingApiCardScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ProcessingApiCardScreenHandler>(menu, playerInventory, title) {

    companion object {
        // Dark theme colors (matches Network Controller / Crafting Core / Terminal)
        private const val BG_COLOR = 0xFF2B2B2B.toInt()
        private const val TOP_BAR_COLOR = 0xFF3C3C3C.toInt()
        private const val TOP_BAR_LINE = 0xFF555555.toInt()
        private const val CONTENT_BG = 0xFF1E1E1E.toInt()
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SLOT_BG = 0xFF1A1A1A.toInt()
        private const val SLOT_BORDER_LIGHT = 0xFF444444.toInt()
        private const val SLOT_BORDER_DARK = 0xFF111111.toInt()
        private const val GHOST_OVERLAY = 0x40FFFFFF.toInt()
        private const val ARROW_COLOR = 0xFF555555.toInt()

        private const val TOP_BAR_H = 20
    }

    /** Public accessors for JEI ghost ingredient handler. */
    fun getLeft(): Int = leftPos
    fun getTop(): Int = topPos

    private var nameBox: EditBox? = null
    private var timeoutBox: EditBox? = null

    init {
        imageWidth = 200
        imageHeight = 198
        // Hide default labels
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Name field
        nameBox = EditBox(font, leftPos + 140, topPos + 4, 54, 14, Component.empty()).also {
            it.setMaxLength(32)
            it.setValue(menu.cardName)
            it.setResponder { value -> menu.cardName = value }
            addRenderableWidget(it)
        }

        // Timeout field — dark-themed EditBox
        timeoutBox = EditBox(font, leftPos + 60, topPos + 94, 40, 14, Component.empty()).also {
            it.setMaxLength(6)
            it.setValue(menu.timeout.toString())
            it.setResponder { value ->
                val timeout = value.toIntOrNull() ?: 0
                PlatformServices.clientNetworking.sendToServer(
                    SetProcessingApiDataPayload(menu.containerId, "timeout", 0, timeout)
                )
            }
            addRenderableWidget(it)
        }
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos
        val w = imageWidth
        val h = imageHeight

        // Main background
        graphics.fill(x, y, x + w, y + h, BG_COLOR)

        // Top bar
        graphics.fill(x, y, x + w, y + TOP_BAR_H, TOP_BAR_COLOR)
        graphics.fill(x, y + TOP_BAR_H - 1, x + w, y + TOP_BAR_H, TOP_BAR_LINE)
        graphics.drawString(font, title, x + 6, y + 6, WHITE)
        graphics.drawString(font, "Name:", x + 112, y + 6, LABEL_COLOR)

        // === Input section ===
        graphics.drawString(font, "Inputs", x + 8, y + 24, LABEL_COLOR)

        // Input grid background panel
        graphics.fill(x + 6, y + 34, x + 64, y + 92, CONTENT_BG)

        // Draw 3x3 input slot backgrounds (matching slot positions: x+8, y+36)
        for (row in 0..2) {
            for (col in 0..2) {
                drawSlotBg(graphics, x + 8 + col * 18, y + 36 + row * 18)
            }
        }

        // === Arrow ===
        val arrowX = x + 72
        val arrowY = y + 54
        // Horizontal line
        graphics.fill(arrowX, arrowY + 2, arrowX + 20, arrowY + 4, ARROW_COLOR)
        // Arrowhead
        graphics.fill(arrowX + 16, arrowY, arrowX + 20, arrowY + 6, ARROW_COLOR)
        graphics.fill(arrowX + 18, arrowY + 1, arrowX + 21, arrowY + 5, ARROW_COLOR)
        graphics.fill(arrowX + 20, arrowY + 2, arrowX + 22, arrowY + 4, ARROW_COLOR)

        // === Output section ===
        graphics.drawString(font, "Outputs", x + 98, y + 24, LABEL_COLOR)

        // Output grid background panel
        graphics.fill(x + 98, y + 34, x + 120, y + 92, CONTENT_BG)

        // Draw 3 output slot backgrounds (matching slot positions: x+100, y+36)
        for (i in 0 until ProcessingApiCardScreenHandler.OUTPUT_SLOTS) {
            drawSlotBg(graphics, x + 100, y + 36 + i * 18)
        }

        // === Timeout section ===
        graphics.drawString(font, "Timeout:", x + 8, y + 97, LABEL_COLOR)
        graphics.drawString(font, "ticks", x + 104, y + 97, 0xFF666666.toInt())

        // === Separator ===
        graphics.fill(x + 4, y + 112, x + w - 4, y + 113, TOP_BAR_LINE)

        // === Player inventory slot backgrounds (use actual slot positions) ===
        for (i in ProcessingApiCardScreenHandler.TOTAL_GHOST_SLOTS until menu.slots.size) {
            val slot = menu.slots[i]
            drawSlotBg(graphics, x + slot.x, y + slot.y)
        }
    }

    private fun drawSlotBg(graphics: GuiGraphics, sx: Int, sy: Int) {
        // Inset slot appearance
        graphics.fill(sx, sy, sx + 16, sy + 16, SLOT_BG)
        graphics.fill(sx - 1, sy - 1, sx + 17, sy, SLOT_BORDER_DARK)
        graphics.fill(sx - 1, sy - 1, sx, sy + 17, SLOT_BORDER_DARK)
        graphics.fill(sx + 16, sy - 1, sx + 17, sy + 17, SLOT_BORDER_LIGHT)
        graphics.fill(sx - 1, sy + 16, sx + 17, sy + 17, SLOT_BORDER_LIGHT)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Ghost overlays on occupied ghost slots
        for (i in 0 until ProcessingApiCardScreenHandler.TOTAL_GHOST_SLOTS) {
            val slot = menu.slots[i]
            if (slot.hasItem()) {
                val sx = leftPos + slot.x
                val sy = topPos + slot.y
                graphics.fillGradient(
                    net.minecraft.client.renderer.RenderType.guiOverlay(),
                    sx, sy, sx + 16, sy + 16, GHOST_OVERLAY, GHOST_OVERLAY, 0
                )
            }
        }

        // Input count labels (bottom-right corner of slot, like vanilla stack count)
        val inputCounts = menu.inputCounts
        for (i in 0 until ProcessingApiCardScreenHandler.INPUT_SLOTS) {
            val slot = menu.slots[i]
            if (slot.hasItem()) {
                val count = inputCounts[i].coerceAtLeast(1)
                if (count > 1) {
                    val text = count.toString()
                    val tx = leftPos + slot.x + 17 - font.width(text)
                    val ty = topPos + slot.y + 9
                    graphics.drawString(font, text, tx, ty, WHITE, true)
                }
            }
        }

        // Output count labels (to the right of each output slot)
        val outputCounts = menu.outputCounts
        for (i in 0 until ProcessingApiCardScreenHandler.OUTPUT_SLOTS) {
            val slot = menu.slots[ProcessingApiCardScreenHandler.INPUT_SLOTS + i]
            if (slot.hasItem()) {
                val count = outputCounts[i].coerceAtLeast(1)
                val text = "x$count"
                graphics.drawString(font, text, leftPos + slot.x + 19, topPos + slot.y + 4, LABEL_COLOR)
            }
        }

        renderTooltip(graphics, mouseX, mouseY)
    }
}
