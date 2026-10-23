package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.item.ScriptItem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Script Runner GUI. Eight rows, each a Script slot, the program name, a status light and
 * an On/Off toggle. Below them the last console lines, then the player inventory.
 */
class ScriptRunnerScreen(
    menu: ScriptRunnerMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ScriptRunnerMenu>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val FRAME_W = ScriptRunnerMenu.FRAME_W
        private const val TOP_PANEL_H = 130
        private const val INV_PANEL_Y = ScriptRunnerMenu.INV_PANEL_Y
        private const val INV_PANEL_H = 96
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H
        private const val TITLE_Y = 7
        private const val NAME_DX = 22
        private const val NAME_W = 70
        private const val LIGHT_DX = 96
        private const val LIGHT_SIZE = 6
        private const val TOGGLE_DX = 106
        private const val TOGGLE_W = 28
        private const val TOGGLE_H = 14
        private const val CONSOLE_Y = 94
        private const val CONSOLE_H = 36
        private const val CONSOLE_X = 8
        private const val CONSOLE_W = FRAME_W - 16
        private const val CLEAR_W = 30
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val DIM_COLOR = 0xFF666666.toInt()
        private const val NAME_COLOR = 0xFFEEEEEE.toInt()
        private const val RUNNING = 0xFF55FF55.toInt()
        private const val FAULT = 0xFFFF5555.toInt()
        private const val ARMED = 0xFFFFAA33.toInt()
        private const val IDLE = 0xFF555555.toInt()
    }

    private var consoleScroll = 0

    init {
        titleLabelY = -9999
        inventoryLabelY = -9999
        imageWidth = FRAME_W
        imageHeight = FRAME_H
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    private fun toggleRect(slot: Int): IntArray = intArrayOf(
        leftPos + ScriptRunnerMenu.slotX(slot) + TOGGLE_DX,
        topPos + ScriptRunnerMenu.slotY(slot) + (ScriptRunnerMenu.ROW_H - TOGGLE_H) / 2,
        TOGGLE_W,
        TOGGLE_H,
    )

    private fun lightRect(slot: Int): IntArray = intArrayOf(
        leftPos + ScriptRunnerMenu.slotX(slot) + LIGHT_DX,
        topPos + ScriptRunnerMenu.slotY(slot) + (ScriptRunnerMenu.ROW_H - LIGHT_SIZE) / 2,
        LIGHT_SIZE,
        LIGHT_SIZE,
    )

    private fun hit(r: IntArray, mx: Int, my: Int): Boolean = mx in r[0] until r[0] + r[2] && my in r[1] until r[1] + r[3]

    private fun clearRect(): IntArray = intArrayOf(leftPos + CONSOLE_X + CONSOLE_W - CLEAR_W - 3, topPos + CONSOLE_Y + 1, CLEAR_W, 10)

    private fun statusColor(slot: Int, hasProgram: Boolean): Int = when {
        menu.isRunning(slot) -> RUNNING
        menu.initialErrors.getOrElse(slot) { "" }.isNotEmpty() && !menu.isEnabled(slot) -> FAULT
        menu.isEnabled(slot) && hasProgram -> ARMED
        else -> IDLE
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        val x = leftPos
        val y = topPos
        NineSlice.WINDOW_FRAME.draw(graphics, x, y, FRAME_W, TOP_PANEL_H)
        graphics.drawString(font, title.string, x + 8, y + TITLE_Y, LABEL_COLOR)

        for (slot in 0 until ScriptRunnerMenu.SLOT_COUNT) {
            val sx = x + ScriptRunnerMenu.slotX(slot)
            val sy = y + ScriptRunnerMenu.slotY(slot)
            NineSlice.SLOT.draw(graphics, sx, sy, 18, 18)
            val stack = menu.slots[slot].item
            val program = ScriptItem.program(stack)
            val name = program?.name ?: if (stack.isEmpty) "empty" else "blank script"
            val nameColor = if (program == null) DIM_COLOR else NAME_COLOR
            graphics.drawString(font, font.plainSubstrByWidth(name, NAME_W), sx + NAME_DX, sy + 5, nameColor, false)

            val light = lightRect(slot)
            graphics.fill(light[0] - 1, light[1] - 1, light[0] + light[2] + 1, light[1] + light[3] + 1, 0xFF222222.toInt())
            graphics.fill(light[0], light[1], light[0] + light[2], light[1] + light[3], statusColor(slot, program != null))

            val t = toggleRect(slot)
            val hovered = program != null && hit(t, mouseX, mouseY)
            (if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, t[0], t[1], t[2], t[3])
            val on = menu.isEnabled(slot)
            val label = if (on) "On" else "Off"
            val labelColor = when {
                program == null -> DIM_COLOR
                on -> 0xFF88FF88.toInt()
                else -> LABEL_COLOR
            }
            graphics.drawString(font, label, t[0] + (t[2] - font.width(label)) / 2, t[1] + (t[3] - font.lineHeight) / 2 + 1, labelColor, false)
        }

        // Console
        val cx = x + CONSOLE_X
        val cy = y + CONSOLE_Y
        NineSlice.PANEL_INSET.draw(graphics, cx, cy, CONSOLE_W, CONSOLE_H)
        graphics.drawString(font, "Output", cx + 4, cy + 2, LABEL_COLOR, false)
        val clear = clearRect()
        val clearHovered = hit(clear, mouseX, mouseY)
        graphics.drawString(font, "Clear", clear[0] + 4, clear[1] + 1, if (clearHovered) NAME_COLOR else DIM_COLOR, false)
        val logs = TerminalLogBuffer.getLogs(menu.devicePos)
        val lineH = font.lineHeight + 1
        val contentTop = cy + 12
        val maxLines = (CONSOLE_H - 14) / lineH
        val lines = ArrayList<Pair<String, Int>>()
        for (entry in logs) {
            val color = if (entry.isError) FAULT else 0xFF999999.toInt()
            for (part in font.splitter.splitLines(entry.displayMessage, CONSOLE_W - 8, net.minecraft.network.chat.Style.EMPTY)) {
                lines.add(part.string to color)
            }
        }
        val maxScroll = maxOf(0, lines.size - maxLines)
        consoleScroll = consoleScroll.coerceIn(0, maxScroll)
        val start = maxOf(0, lines.size - maxLines - consoleScroll)
        graphics.enableScissor(cx, contentTop, cx + CONSOLE_W, cy + CONSOLE_H - 2)
        for (i in 0 until maxLines) {
            val idx = start + i
            if (idx >= lines.size) break
            val (text, color) = lines[idx]
            graphics.drawString(font, text, cx + 4, contentTop + i * lineH, color, false)
        }
        graphics.disableScissor()

        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        graphics.drawString(font, "Inventory", x + ScriptRunnerMenu.INV_X, y + INV_PANEL_Y + 4, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + ScriptRunnerMenu.INV_X, y + ScriptRunnerMenu.INV_Y, ScriptRunnerMenu.HOTBAR_GAP)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        for (slot in 0 until ScriptRunnerMenu.SLOT_COUNT) {
            val light = lightRect(slot)
            val rowHover = mouseX in light[0] - LIGHT_DX + NAME_DX until light[0] + light[2] + 2 &&
                mouseY in topPos + ScriptRunnerMenu.slotY(slot) until topPos + ScriptRunnerMenu.slotY(slot) + ScriptRunnerMenu.ROW_H
            if (!rowHover) continue
            val program = ScriptItem.program(menu.slots[slot].item) ?: continue
            val lines = ArrayList<Component>()
            lines.add(Component.literal(program.name))
            val status = when {
                menu.isRunning(slot) -> "Running"
                menu.isEnabled(slot) -> "Enabled, waiting to start"
                else -> "Off"
            }
            lines.add(Component.literal(status).withStyle(net.minecraft.ChatFormatting.GRAY))
            val err = menu.initialErrors.getOrElse(slot) { "" }
            if (err.isNotEmpty()) lines.add(Component.literal(err).withStyle(net.minecraft.ChatFormatting.RED))
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY)
            break
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        for (slot in 0 until ScriptRunnerMenu.SLOT_COUNT) {
            if (hit(toggleRect(slot), mx, my)) {
                if (ScriptItem.program(menu.slots[slot].item) != null) {
                    Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, slot)
                    minecraft?.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f)
                }
                return true
            }
        }
        if (hit(clearRect(), mx, my)) {
            TerminalLogBuffer.clear(menu.devicePos)
            consoleScroll = 0
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val cx = leftPos + CONSOLE_X
        val cy = topPos + CONSOLE_Y
        if (mouseX >= cx && mouseX < cx + CONSOLE_W && mouseY >= cy && mouseY < cy + CONSOLE_H) {
            consoleScroll = (consoleScroll + scrollY.toInt()).coerceAtLeast(0)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
}
