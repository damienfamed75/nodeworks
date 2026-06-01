package damien.nodeworks.screen.widget

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.screen.Icons
import damien.nodeworks.script.PlanningErrorLabels
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Inline `[icon] [text]` status label. Host draws via [render] in its
 * background pass, [renderTooltip] in the foreground pass.
 *
 * State precedence: disconnected, active, error, emitting, idle.
 */
class JobStatusIndicator(
    var x: Int,
    var y: Int,
    var maxInlineWidth: Int,
) {
    companion object {
        private const val ICON_SIZE = 8
        private const val ICON_TEXT_GAP = 3

        private const val IDLE_ICON_COLOR = 0xFF4F6B4F.toInt()
        private const val ACTIVE_ICON_COLOR = 0xFF33FF55.toInt()
        private const val ERROR_ICON_COLOR = 0xFFFF5555.toInt()
        private const val DISCONNECTED_ICON_COLOR = 0xFF808080.toInt()
        private const val EMITTING_ICON_COLOR = 0xFFFFAA33.toInt()
        private const val TEXT_COLOR = 0xFFCCCCCC.toInt()

        private const val IDLE_TEXT = "Idle"
        private const val ACTIVE_TEXT = "Crafting"
        private const val DISCONNECTED_TEXT = "Disconnected"
        private const val EMITTING_TEXT = "Emitting Signal"

        private const val MAX_TOOLTIP_LINES = 8
    }

    private enum class State { IDLE, ACTIVE, ERROR, DISCONNECTED, EMITTING }

    var hasActiveJob: Boolean = false
        private set
    private var errors: List<String> = emptyList()
    // Defaults to true so non-opt-in hosts never see Disconnected.
    private var connected: Boolean = true
    // Defaults to false so non-opt-in hosts never see Emitting Signal.
    private var emittingSignal: Boolean = false
    private var renderedWidth: Int = 0

    fun setActive(active: Boolean) {
        hasActiveJob = active
    }

    fun setErrors(list: List<String>) {
        errors = list
    }

    fun setConnected(value: Boolean) {
        connected = value
    }

    fun setEmittingSignal(value: Boolean) {
        emittingSignal = value
    }

    private fun state(): State = when {
        !connected -> State.DISCONNECTED
        hasActiveJob -> State.ACTIVE
        errors.isNotEmpty() -> State.ERROR
        emittingSignal -> State.EMITTING
        else -> State.IDLE
    }

    fun render(graphics: GuiGraphicsExtractor, font: Font) {
        val state = state()
        val iconColor = when (state) {
            State.ACTIVE -> ACTIVE_ICON_COLOR
            State.ERROR -> ERROR_ICON_COLOR
            State.IDLE -> IDLE_ICON_COLOR
            State.DISCONNECTED -> DISCONNECTED_ICON_COLOR
            State.EMITTING -> EMITTING_ICON_COLOR
        }
        val rawText = when (state) {
            State.ACTIVE -> ACTIVE_TEXT
            // Inline shows the short label, tooltip below shows the full text.
            State.ERROR -> PlanningErrorLabels.shortLabel(errors.last())
            State.IDLE -> IDLE_TEXT
            State.DISCONNECTED -> DISCONNECTED_TEXT
            State.EMITTING -> EMITTING_TEXT
        }
        val maxTextWidth = (maxInlineWidth - ICON_SIZE - ICON_TEXT_GAP).coerceAtLeast(0)
        val inlineText = truncateToWidth(font, rawText, maxTextWidth)

        val textY = y
        val iconY = y + (font.lineHeight - ICON_SIZE) / 2
        Icons.JOB_STATUS.drawTopLeftTinted(graphics, x, iconY, ICON_SIZE, ICON_SIZE, iconColor)
        graphics.drawString(font, inlineText, x + ICON_SIZE + ICON_TEXT_GAP, textY, TEXT_COLOR)

        renderedWidth = ICON_SIZE + ICON_TEXT_GAP + font.width(inlineText)
    }

    /** Hover tooltip with the full error list. No-op outside the error state. */
    fun renderTooltip(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int) {
        if (state() != State.ERROR) return
        val w = renderedWidth.coerceAtLeast(1)
        if (mouseX !in x until x + w) return
        if (mouseY !in y until y + font.lineHeight) return
        val lines = errors.takeLast(MAX_TOOLTIP_LINES).map { Component.literal(it) }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY)
    }

    private fun truncateToWidth(font: Font, text: String, maxW: Int): String {
        if (maxW <= 0) return ""
        if (font.width(text) <= maxW) return text
        val ellipsis = "..."
        val ellipsisW = font.width(ellipsis)
        var cur = text
        while (cur.isNotEmpty() && font.width(cur) + ellipsisW > maxW) {
            cur = cur.dropLast(1)
        }
        return cur + ellipsis
    }
}
