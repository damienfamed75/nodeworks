package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.hasAltDownCompat
import damien.nodeworks.compat.hasControlDownCompat
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.modifierBits
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import damien.nodeworks.compat.scan
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

class CardProgrammerScreen(
    menu: CardProgrammerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CardProgrammerMenu>(menu, playerInventory, title, W, H) {

    companion object {
        private const val W = 176

        // Programmer image Y offset (image + slots shifted up, controls stay)
        private const val PROG_Y = 2
        private const val PROG_H = 100

        // Slot positions, black squares in bg at source (52, 18) / (106, 18)
        private const val TEMPLATE_SLOT_X = 52
        private const val TEMPLATE_SLOT_Y = 18 + PROG_Y      // 20
        private const val INPUT_SLOT_X = 106
        private const val INPUT_SLOT_Y = 18 + PROG_Y         // 20

        // Copy Name toggle, shifted right 3px to horizontally center the controls row in the frame
        private const val TOGGLE_X = 35
        private const val TOGGLE_Y = 76
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16
        private const val TOGGLE_LABEL_Y = TOGGLE_Y - 10     // 66

        // Increment row, counter 2 digits (max 99), shifted right 3px with the toggle.
        // Field must be wide enough that EditBox's inner width (width - 8 for bordered)
        // fits "99" in the default font (~11px). INC_FIELD_W=20 → 12px inner, comfortable.
        private const val INC_MINUS_X = 93
        private const val INC_FIELD_X = 109
        private const val INC_FIELD_W = 20
        private const val INC_PLUS_X = 131
        private const val INC_BTN_Y = 76
        private const val INC_BTN_W = 14
        private const val INC_BTN_H = 14
        private const val INC_LABEL_Y = INC_BTN_Y - 10

        // Window frame around the controls, extended an additional 2px on each side
        private const val CTRL_FRAME_X = 22
        private const val CTRL_FRAME_Y = 57
        private const val CTRL_FRAME_W = 132
        private const val CTRL_FRAME_H = 43

        // Screw corner positions (tweaked manually to look centered on the frame)
        private const val SCREW_L_X = 29
        private const val SCREW_R_X = 147
        private const val SCREW_T_Y = 63
        private const val SCREW_B_Y = 94

        // Player inventory frame, extended down 14px for extra bottom space
        private const val INV_PANEL_Y = 108
        private const val INV_PANEL_H = 96
        private const val H = INV_PANEL_Y + INV_PANEL_H      // 204

        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val INV_X = 8
        private const val HOTBAR_GAP = 4

        private val BG_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/card_programmer_bg.png")
    }

    private var counterField: EditBox? = null
    private var lastSyncedCounter = -1

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        counterField =
            EditBox(font, leftPos + INC_FIELD_X, topPos + INC_BTN_Y + 1, INC_FIELD_W, 12, Component.literal("Counter"))
        counterField!!.setMaxLength(2)
        counterField!!.value = "${menu.getCounter()}"
        counterField!!.setBordered(true)
        // 26.1: GuiGraphicsExtractor.text skips rendering when ARGB.alpha(color)==0,
        // so the top byte MUST be the alpha channel (0xFF), not left as 0.
        counterField!!.setTextColor(0xFFFFFFFF.toInt())
        lastSyncedCounter = menu.getCounter()
        addRenderableWidget(counterField!!)
    }

    private fun commitCounterField() {
        val v = counterField?.value?.toIntOrNull()?.coerceIn(0, 99) ?: 0
        counterField?.value = "$v"
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 100 + v)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val codePoint = event.character
        val modifiers = 0
        if (menu.getCopyName() && counterField?.isFocused == true) {
            if (codePoint.isDigit()) return counterField?.charTyped(event) ?: false
            return true // swallow non-digits
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
        if (counterField?.isFocused == true) {
            if (keyCode == 256) { // Escape, unfocus, don't close screen
                counterField!!.isFocused = false
                return true
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / KP_Enter
                commitCounterField()
                counterField!!.isFocused = false
                return true
            }
            return counterField!!.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Programmer image
        graphics.blit(BG_TEXTURE, leftPos, topPos + PROG_Y, 0f, 0f, W, PROG_H, W, PROG_H)

        // Card slots intentionally have no visible frame, they sit on top of the
        // programmer background texture which already paints slot cutouts.

        if (!menu.hasTemplate()) {
            graphics.fill(
                leftPos + INPUT_SLOT_X + 1, topPos + INPUT_SLOT_Y + 1,
                leftPos + INPUT_SLOT_X + 17, topPos + INPUT_SLOT_Y + 17,
                0x80000000.toInt()
            )
        }

        // Window frame around the controls + decorative screws at each corner
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos + CTRL_FRAME_X, topPos + CTRL_FRAME_Y, CTRL_FRAME_W, CTRL_FRAME_H)
        // Screws, drawSmall uses 4px inset, so offset by -4 to land at the target pixel
        Icons.SMALL_SCREW.drawSmall(graphics, leftPos + SCREW_L_X - 4, topPos + SCREW_T_Y - 4)
        Icons.SMALL_SCREW.drawSmall(graphics, leftPos + SCREW_R_X - 4, topPos + SCREW_T_Y - 4)
        Icons.SMALL_SCREW.drawSmall(graphics, leftPos + SCREW_L_X - 4, topPos + SCREW_B_Y - 4)
        Icons.SMALL_SCREW.drawSmall(graphics, leftPos + SCREW_R_X - 4, topPos + SCREW_B_Y - 4)

        // Copy Name toggle
        val toggleSlice = if (menu.getCopyName()) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        toggleSlice.draw(graphics, leftPos + TOGGLE_X, topPos + TOGGLE_Y, TOGGLE_W, TOGGLE_H)
        // Center "Copy Name" label above the toggle switch
        val toggleLabel = "Copy Name"
        val toggleLabelX = TOGGLE_X + (TOGGLE_W - font.width(toggleLabel)) / 2
        graphics.drawString(font, toggleLabel, leftPos + toggleLabelX, topPos + TOGGLE_LABEL_Y, 0xFFAAAAAA.toInt())

        // Increment row (grayed when copy-name off)
        val incEnabled = menu.getCopyName()
        val incLabelColor = if (incEnabled) 0xFFAAAAAA.toInt() else 0xFF555555.toInt()
        val incTextColor = if (incEnabled) 0xFFFFFFFF.toInt() else 0xFF555555.toInt()
        // Center "Increment" label above the minus+field+plus row
        val incLabel = "Increment"
        val incRowCenterX = (INC_MINUS_X + INC_PLUS_X + INC_BTN_W) / 2
        val incLabelX = incRowCenterX - font.width(incLabel) / 2
        graphics.drawString(font, incLabel, leftPos + incLabelX, topPos + INC_LABEL_Y, incLabelColor)

        // [-] button (decrement)
        val minusHover =
            incEnabled && hovers(mouseX, mouseY, leftPos + INC_MINUS_X, topPos + INC_BTN_Y, INC_BTN_W, INC_BTN_H)
        val minusSlice = if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        minusSlice.draw(graphics, leftPos + INC_MINUS_X, topPos + INC_BTN_Y, INC_BTN_W, INC_BTN_H)
        graphics.drawString(
            font,
            "-",
            leftPos + INC_MINUS_X + (INC_BTN_W - font.width("-")) / 2,
            topPos + INC_BTN_Y + 3,
            incTextColor
        )

        // [+] button (increment)
        val plusHover =
            incEnabled && hovers(mouseX, mouseY, leftPos + INC_PLUS_X, topPos + INC_BTN_Y, INC_BTN_W, INC_BTN_H)
        val plusSlice = if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        plusSlice.draw(graphics, leftPos + INC_PLUS_X, topPos + INC_BTN_Y, INC_BTN_W, INC_BTN_H)
        graphics.drawString(
            font,
            "+",
            leftPos + INC_PLUS_X + (INC_BTN_W - font.width("+")) / 2,
            topPos + INC_BTN_Y + 3,
            incTextColor
        )

        // Counter field color, manual "gray out" when disabled
        counterField?.setTextColor(if (incEnabled) 0xFFFFFFFF.toInt() else 0xFF555555.toInt())
        counterField?.setEditable(incEnabled)

        // Player inventory frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos + INV_PANEL_Y, W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_LABEL_Y, 0xFFAAAAAA.toInt())
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_GRID_Y, HOTBAR_GAP)
    }

    private fun hovers(mouseX: Int, mouseY: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Sync field if server counter changed and field is not focused
        val sv = menu.getCounter()
        if (sv != lastSyncedCounter && counterField?.isFocused != true) {
            counterField?.value = "$sv"
            lastSyncedCounter = sv
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (button == 0) {
            val mx = mouseX.toInt()
            val my = mouseY.toInt()

            if (hovers(mx, my, leftPos + TOGGLE_X, topPos + TOGGLE_Y, TOGGLE_W, TOGGLE_H)) {
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 2)
                return true
            }

            if (menu.getCopyName()) {
                if (hovers(mx, my, leftPos + INC_MINUS_X, topPos + INC_BTN_Y, INC_BTN_W, INC_BTN_H)) {
                    Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0)
                    return true
                }
                if (hovers(mx, my, leftPos + INC_PLUS_X, topPos + INC_BTN_Y, INC_BTN_W, INC_BTN_H)) {
                    Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1)
                    return true
                }
            }

            // Clicking outside the counter field commits its value and unfocuses
            val clickedField = hovers(mx, my, leftPos + INC_FIELD_X, topPos + INC_BTN_Y, INC_FIELD_W, INC_BTN_H + 2)
            if (!clickedField && counterField?.isFocused == true) {
                commitCounterField()
                counterField?.isFocused = false
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun removed() {
        commitCounterField()
        super.removed()
    }
}
