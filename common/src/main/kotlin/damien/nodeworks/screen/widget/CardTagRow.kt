package damien.nodeworks.screen.widget

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.card.CardTags
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.screen.NineSlice
import damien.nodeworks.screen.tooltip.CardTagsTooltipRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * Title-bar tag strip for the card settings screens. The field adds a tag on Enter or
 * on the Add button, and while it has focus it offers the tags the server already knows
 * that start with what is typed. The card's tags float above the frame as chips, and
 * clicking a chip removes it. Every change is sent at once, so the card is tagged the
 * moment the chip appears.
 */
class CardTagRow(
    private val font: Font,
    private val leftPos: Int,
    private val topPos: Int,
    private val imageWidth: Int,
    initialTags: List<String>,
    private val knownTags: List<String>,
    private val sendTags: (List<String>) -> Unit,
    private val requestDefocus: () -> Unit = {},
) {
    companion object {
        private const val ROW_Y = 4
        private const val FIELD_H = 14
        private const val ADD_BTN_W = 32
        private const val ADD_BTN_H = 14
        private const val FIELD_X = 5
        private const val FIELD_RIGHT_PAD = 4
        private const val FIELD_TO_BTN_GAP = 4
        private const val MAX_SUGGESTIONS = 5
        private const val SUGGEST_ROW_H = 11
        private const val CHIP_FLOAT_GAP = 3
        private const val CHIP_HOVER_BG = 0xFF6A3A3A.toInt()
        private const val CHIP_HOVER_BORDER = 0xFFE07070.toInt()
        private const val SUGGEST_BG = 0xF0101010.toInt()
        private const val SUGGEST_ROW_HOVER = 0xFF3A4A5A.toInt()
        private const val SUGGEST_TEXT = 0xFFDDDDDD.toInt()
    }

    val tags: MutableList<String> = initialTags.toMutableList()

    private val fieldX: Int = leftPos + FIELD_X
    private val fieldY: Int = topPos + ROW_Y
    private val addBtnX: Int = leftPos + imageWidth - ADD_BTN_W - FIELD_RIGHT_PAD
    private val addBtnY: Int = topPos + ROW_Y
    private val fieldW: Int = addBtnX - fieldX - FIELD_TO_BTN_GAP

    /** Which suggestion the arrow keys have picked, -1 for none. */
    private var highlighted = -1

    private val field: EditBox = EditBox(
        font, fieldX, fieldY, fieldW, FIELD_H, Component.literal("Tag")
    ).also {
        it.setMaxLength(CardTags.MAX_TAG_LENGTH)
        it.setBordered(true)
        it.setTextColor(0xFFFFFFFF.toInt())
        it.setHint(Component.literal("Add tag").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
        it.setResponder { highlighted = -1 }
    }

    fun addToScreen(register: (AbstractWidget) -> Unit) {
        register(field)
    }

    private fun suggestions(): List<String> {
        val prefix = CardTags.normalize(field.value) ?: ""
        return knownTags.asSequence()
            .filter { it !in tags && (prefix.isEmpty() || it.startsWith(prefix)) }
            .take(MAX_SUGGESTIONS).toList()
    }

    private fun showingSuggestions(): Boolean = field.isFocused && tags.size < CardTags.MAX_TAGS && suggestions().isNotEmpty()

    /** Chip rectangles for the current tags, laid out right to left above the frame so
     *  the newest sits nearest the field. Each is x, y, width. */
    private fun chipRects(): List<IntArray> {
        val out = ArrayList<IntArray>()
        val y = topPos - CardTagsTooltipRenderer.CHIP_H - CHIP_FLOAT_GAP
        var x = leftPos
        var row = 0
        for (tag in tags) {
            val w = CardTagsTooltipRenderer.chipWidth(font, tag)
            if (x + w > leftPos + imageWidth && x > leftPos) {
                row++
                x = leftPos
            }
            out.add(intArrayOf(x, y - row * (CardTagsTooltipRenderer.CHIP_H + 2), w))
            x += w + CardTagsTooltipRenderer.CHIP_GAP
        }
        return out
    }

    /** The Add button, drawn with the background. */
    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in addBtnX until addBtnX + ADD_BTN_W && mouseY in addBtnY until addBtnY + ADD_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, addBtnX, addBtnY, ADD_BTN_W, ADD_BTN_H)
        val label = "Add"
        val color = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, addBtnX + (ADD_BTN_W - font.width(label)) / 2, addBtnY + (ADD_BTN_H - font.lineHeight) / 2 + 1, color)
    }

    /** Chips and the suggestion list, drawn after every other widget so they sit on top. */
    fun renderOverlay(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rects = chipRects()
        for ((i, tag) in tags.withIndex()) {
            val r = rects[i]
            val hovered = mouseX in r[0] until r[0] + r[2] && mouseY in r[1] until r[1] + CardTagsTooltipRenderer.CHIP_H
            if (hovered) CardTagsTooltipRenderer.drawChip(graphics, font, r[0], r[1], tag, CHIP_HOVER_BG, CHIP_HOVER_BORDER)
            else CardTagsTooltipRenderer.drawChip(graphics, font, r[0], r[1], tag)
        }
        if (!showingSuggestions()) return
        val list = suggestions()
        val x = fieldX
        val y = fieldY + FIELD_H + 1
        val h = list.size * SUGGEST_ROW_H + 2
        graphics.fill(x, y, x + fieldW, y + h, SUGGEST_BG)
        for ((i, tag) in list.withIndex()) {
            val ry = y + 1 + i * SUGGEST_ROW_H
            val hovered = mouseX in x until x + fieldW && mouseY in ry until ry + SUGGEST_ROW_H
            if (hovered || i == highlighted) graphics.fill(x + 1, ry, x + fieldW - 1, ry + SUGGEST_ROW_H, SUGGEST_ROW_HOVER)
            graphics.drawString(font, tag, x + 4, ry + 2, SUGGEST_TEXT)
        }
    }

    /** True when the click was ours: a chip, the Add button, or a suggestion. A click
     *  away from a focused field commits nothing and asks the host to drop focus. */
    fun mouseClicked(event: MouseButtonEvent): Boolean {
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        val rects = chipRects()
        for (i in tags.indices.reversed()) {
            val r = rects[i]
            if (mx in r[0] until r[0] + r[2] && my in r[1] until r[1] + CardTagsTooltipRenderer.CHIP_H) {
                click()
                tags.removeAt(i)
                sendTags(tags.toList())
                return true
            }
        }
        if (showingSuggestions()) {
            val list = suggestions()
            val x = fieldX
            val y = fieldY + FIELD_H + 1
            for ((i, tag) in list.withIndex()) {
                val ry = y + 1 + i * SUGGEST_ROW_H
                if (mx in x until x + fieldW && my in ry until ry + SUGGEST_ROW_H) {
                    add(tag)
                    return true
                }
            }
        }
        if (mx in addBtnX until addBtnX + ADD_BTN_W && my in addBtnY until addBtnY + ADD_BTN_H) {
            add(field.value)
            return true
        }
        if (field.isFocused) {
            val inField = mx in field.x until field.x + field.width && my in field.y until field.y + field.height
            if (!inField) {
                field.isFocused = false
                highlighted = -1
                requestDefocus()
            }
        }
        return false
    }

    /** Enter adds the picked suggestion or the typed text, arrows pick, Escape drops
     *  focus. Every other key is consumed while the field has focus, so the inventory
     *  key cannot close the screen mid-word. */
    fun keyPressed(event: KeyEvent): Boolean {
        if (!field.isFocused) return false
        when (event.keyCode) {
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                val list = suggestions()
                add(if (highlighted in list.indices) list[highlighted] else field.value)
                return true
            }
            InputConstants.KEY_DOWN -> {
                val n = suggestions().size
                if (n > 0) highlighted = (highlighted + 1).coerceIn(0, n - 1)
                return true
            }
            InputConstants.KEY_UP -> {
                highlighted = (highlighted - 1).coerceAtLeast(-1)
                return true
            }
            InputConstants.KEY_ESCAPE -> {
                field.isFocused = false
                highlighted = -1
                requestDefocus()
                return true
            }
        }
        field.keyPressed(event)
        return true
    }

    private fun add(raw: String) {
        val tag = CardTags.normalize(raw) ?: return
        if (tag in tags || tags.size >= CardTags.MAX_TAGS) {
            field.value = ""
            return
        }
        click()
        tags.add(tag)
        field.value = ""
        highlighted = -1
        sendTags(tags.toList())
    }

    private fun click() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }
}
