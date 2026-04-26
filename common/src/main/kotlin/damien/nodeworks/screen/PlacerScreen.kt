package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/** Placer settings screen — same shape as [BreakerScreen]. Two rows: name + channel. */
class PlacerScreen(
    menu: PlacerMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<PlacerMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 200
        private const val IMAGE_H = 80
        private const val TOP_BAR_H = 20
        private const val PAD = 6
        private const val ROW_H = 22
        private const val LABEL_W = 56
        private const val FIELD_W = 110
        private const val FIELD_H = 16
        private const val SET_BTN_W = 24
        private const val SET_BTN_H = 16
    }

    private lateinit var nameField: EditBox
    private var picker: ChannelPickerWidget? = null
    private var lastSyncedChannel: Int = -1

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private fun rowY(idx: Int): Int = topPos + TOP_BAR_H + PAD + idx * ROW_H
    private val controlX: Int get() = leftPos + PAD + LABEL_W

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        nameField = EditBox(font, controlX, rowY(0) + 3, FIELD_W, FIELD_H - 4, Component.literal("Name"))
        nameField.setMaxLength(32)
        nameField.setBordered(true)
        nameField.setTextColor(0xFFFFFFFF.toInt())
        nameField.value = menu.initialName
        addRenderableWidget(nameField)

        val initialChannel = runCatching { DyeColor.byId(menu.channelId) }.getOrDefault(DyeColor.WHITE)
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(controlX, rowY(1) + 3, initialChannel) { color ->
            sendUpdate("channel", color.id, "")
        }
        addRenderableWidget(picker!!)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor())

        graphics.drawString(font, "Name", leftPos + PAD, rowY(0) + 7, 0xFFAAAAAA.toInt())
        drawSetButton(graphics, controlX + FIELD_W + 4, rowY(0) + 1, mouseX, mouseY)
        graphics.drawString(font, "Channel", leftPos + PAD, rowY(1) + 7, 0xFFAAAAAA.toInt())

        val serverChannel = menu.channelId
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
    }

    private fun drawSetButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in bx until bx + SET_BTN_W && mouseY in by until by + SET_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, bx, by, SET_BTN_W, SET_BTN_H)
        val label = "Set"
        val color = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, bx + (SET_BTN_W - font.width(label)) / 2, by + 5, color)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        if (nameField.isFocused) {
            nameField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        val nameSetX = controlX + FIELD_W + 4
        val nameSetY = rowY(0) + 1
        if (mx in nameSetX until nameSetX + SET_BTN_W && my in nameSetY until nameSetY + SET_BTN_H) {
            sendUpdate("name", 0, nameField.value)
            playClickSound()
            nameField.isFocused = false
            return true
        }
        if (super.mouseClicked(event, doubleClick)) return true
        nameField.isFocused = false
        setFocused(null)
        return false
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
            )
        )
    }

    private fun networkColor(): Int {
        val level = Minecraft.getInstance().level ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(level, menu.devicePos)
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(menu.devicePos, key, intValue, strValue)
        )
    }
}
