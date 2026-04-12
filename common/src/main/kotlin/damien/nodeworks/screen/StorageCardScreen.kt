package damien.nodeworks.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class StorageCardScreen(
    menu: StorageCardMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<StorageCardMenu>(menu, playerInventory, title) {

    companion object {
        private const val W = 140
        private const val H = 50
        private const val INSET_X = 4
        private const val INSET_Y = 19
        private const val INSET_W = W - 8
        private const val INSET_H = H - 24
    }

    private var priorityField: EditBox? = null
    /** Tracks last known server value to detect external changes. */
    private var lastSyncedPriority = -1

    init {
        imageWidth = W
        imageHeight = H
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()

        val fieldX = leftPos + 50
        val fieldY = topPos + INSET_Y + 8
        priorityField = EditBox(font, fieldX, fieldY, 40, 12, Component.literal("Priority"))
        priorityField!!.setMaxLength(3)
        priorityField!!.value = "${menu.getPriority()}"
        lastSyncedPriority = menu.getPriority()
        addRenderableWidget(priorityField!!)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (priorityField?.isFocused == true) {
            if (codePoint.isDigit()) {
                return priorityField?.charTyped(codePoint, modifiers) ?: false
            }
            return true // consume non-digits
        }
        return super.charTyped(codePoint, modifiers)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (priorityField?.isFocused == true) {
            // Allow typing keys to pass through to the field
            if (keyCode == 256) { // Escape
                priorityField!!.isFocused = false
                return true
            }
            if (keyCode == 257 || keyCode == 335) { // Enter/KP_Enter
                commitFieldValue()
                priorityField!!.isFocused = false
                return true
            }
            return priorityField!!.keyPressed(keyCode, scanCode, modifiers)
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun commitFieldValue() {
        val value = priorityField?.value?.toIntOrNull()?.coerceIn(0, 999) ?: 0
        priorityField?.value = "$value"
        // Use clickMenuButton to set exact value: id 100+ encodes the value directly
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 100 + value)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Flat frame (no title bar)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Title text
        graphics.drawString(font, "Storage Card", leftPos + 6, topPos + 7, 0xFFFFFFFF.toInt())

        // Recessed inset for priority area
        NineSlice.WINDOW_RECESSED.draw(graphics, leftPos + INSET_X, topPos + INSET_Y, INSET_W, INSET_H)

        // "Priority:" label
        graphics.drawString(font, "Priority:", leftPos + 10, topPos + INSET_Y + 9, 0xFFAAAAAA.toInt())

        // Stepper buttons [-] and [+] flanking the field
        val stepY = topPos + INSET_Y + 7
        val minusX = leftPos + 94
        val plusX = leftPos + 112
        val btnW = 14
        val btnH = 14
        val minusHover = mouseX >= minusX && mouseX < minusX + btnW && mouseY >= stepY && mouseY < stepY + btnH
        val plusHover = mouseX >= plusX && mouseX < plusX + btnW && mouseY >= stepY && mouseY < stepY + btnH
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, minusX, stepY, btnW, btnH)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, plusX, stepY, btnW, btnH)
        graphics.drawString(font, "-", minusX + (btnW - font.width("-")) / 2, stepY + 3, 0xFFFFFFFF.toInt())
        graphics.drawString(font, "+", plusX + (btnW - font.width("+")) / 2, stepY + 3, 0xFFFFFFFF.toInt())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Sync field if server value changed (e.g. from stepper button round-trip)
        val serverVal = menu.getPriority()
        if (serverVal != lastSyncedPriority && priorityField?.isFocused != true) {
            priorityField?.value = "$serverVal"
            lastSyncedPriority = serverVal
        }

        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val stepY = topPos + INSET_Y + 7
            val minusX = leftPos + 94
            val plusX = leftPos + 112
            val btnW = 14
            val btnH = 14
            val mx = mouseX.toInt()
            val my = mouseY.toInt()

            if (mx >= minusX && mx < minusX + btnW && my >= stepY && my < stepY + btnH) {
                val step = if (hasShiftDown()) 10 else 1
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0)
                if (step > 1) {
                    // For shift-click, send multiple decrements
                    repeat(step - 1) {
                        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0)
                    }
                }
                return true
            }
            if (mx >= plusX && mx < plusX + btnW && my >= stepY && my < stepY + btnH) {
                val step = if (hasShiftDown()) 10 else 1
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1)
                if (step > 1) {
                    repeat(step - 1) {
                        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1)
                    }
                }
                return true
            }

            // Commit field when clicking outside it
            if (priorityField?.isFocused == true) {
                commitFieldValue()
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun removed() {
        // Commit any pending field value on close
        commitFieldValue()
        super.removed()
    }
}
