package damien.nodeworks.screen.widget

import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.screen.Icons
import damien.nodeworks.screen.NineSlice
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * 16×16 cycle button for the three-state redstone gating mode used across the
 * device GUIs (Ignored / Active Low / Active High). Click cycles forward and
 * fires [onCycle] with the new value so the host can ship a [DeviceSettingsPayload]
 * to the server. Hover surfaces a "Redstone: <mode>" + "Click to cycle." vanilla
 * tooltip via [renderTooltip], which the host calls at the end of its render
 * phase so the tooltip layers above the rest of the GUI.
 *
 * Mirrors [ChannelPickerWidget]'s pattern: widget owns the visual + interaction,
 * host owns persistence + external sync (call [setMode] each frame to push
 * server-side mutations down).
 */
class RedstoneCycleButton(
    x: Int,
    y: Int,
    initialMode: Int,
    private val onCycle: (Int) -> Unit,
) : AbstractWidget(x, y, SIZE, SIZE, Component.literal("Redstone")) {

    companion object {
        const val SIZE = 16
        const val MODE_IGNORED = 0
        const val MODE_LOW = 1
        const val MODE_HIGH = 2
        private const val MODE_COUNT = 3
        private val LABELS = arrayOf("Ignored", "Active Low", "Active High")
    }

    var mode: Int = initialMode.coerceIn(0, 2)
        private set

    /** Push a server-confirmed mode into the widget. Used by hosts that
     *  mirror [redstoneMode] from a [ContainerData] slot. */
    fun setMode(m: Int) {
        mode = m.coerceIn(0, 2)
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val slice = if (isHovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, x, y, SIZE, SIZE)
        val icon = when (mode) {
            MODE_LOW -> Icons.REDSTONE_INACTIVE
            MODE_HIGH -> Icons.REDSTONE_ACTIVE
            else -> Icons.REDSTONE_IGNORE
        }
        icon.draw(graphics, x, y)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val next = (mode + 1) % MODE_COUNT
        mode = next
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
        )
        onCycle(next)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }

    /** Host screens must call this AFTER rendering their other widgets so the
     *  tooltip layers above buttons / labels rendered by the screen frame. */
    fun renderTooltip(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (mouseX !in x until x + width || mouseY !in y until y + height) return
        val font = Minecraft.getInstance().font
        graphics.renderComponentTooltip(
            font,
            listOf(
                Component.literal("Redstone: ${LABELS[mode]}"),
                Component.literal("Click to cycle."),
            ),
            mouseX,
            mouseY,
        )
    }
}
