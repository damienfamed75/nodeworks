package damien.nodeworks.screen

import damien.nodeworks.block.entity.VariableType
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.screen.widget.ChannelPickerWidget
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/**
 * Variable block configuration screen, rebuilt on the shared 9-slice toolkit
 * (WINDOW_FRAME + TOP_BAR + BUTTON + INPUT_FIELD + TOGGLE_ACTIVE/INACTIVE). Three
 * fixed rows: Name / Type / Value. Not scrollable, everything fits at once.
 */
class VariableScreen(
    menu: VariableMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<VariableMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 220
        // IMAGE_H grew by one ROW_H to host the channel picker as a fourth row
        // alongside Name / Type / Value. The whole frame stays vertically centered
        // because [init] recomputes leftPos/topPos against width/height.
        private const val IMAGE_H = 124

        private const val TOP_BAR_H = 20
        private const val PAD = 6           // horizontal/vertical inner padding
        private const val ROW_H = 22
        private const val LABEL_W = 42

        // Field / button geometry
        private const val FIELD_W = 120
        private const val FIELD_H = 16
        private const val SET_BTN_W = 28
        private const val SET_BTN_H = 16
        private const val TYPE_BTN_W = 48
        private const val TYPE_BTN_H = 16
        private const val TYPE_BTN_GAP = 4
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16

        /** Checkmark flash duration after a successful Set click (ticks). */
        private const val CHECKMARK_DURATION = 30L

        private val TYPE_LABELS = arrayOf("Number", "String", "Bool")
    }

    private lateinit var nameField: EditBox
    private lateinit var valueField: EditBox
    private var picker: ChannelPickerWidget? = null
    /** Tracks last seen server channel so external mutations push down without
     *  clobbering an in-flight pick (mirrors lastSyncedPriority in StorageCardScreen). */
    private var lastSyncedChannel: Int = -1

    // Which Set button flashed a checkmark most recently, and when.
    private var checkmarkId: String? = null
    private var checkmarkTime: Long = 0

    init {
        // We draw our own title inside the 9-slice TOP_BAR, hide the default labels.
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private fun rowY(idx: Int): Int = topPos + TOP_BAR_H + PAD + idx * ROW_H
    private val controlX: Int get() = leftPos + PAD + LABEL_W

    /** Screen-space hitbox for the [FIELD_W]x[FIELD_H] EditBox at row-origin [fieldY].
     *  Returned as (xRange, yRange) for `in`-checks against mouse coords. */
    private fun fieldRect(fieldY: Int): Pair<IntRange, IntRange> =
        (controlX until controlX + FIELD_W) to (fieldY until fieldY + FIELD_H)

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Plain bordered MC EditBox, matches the other Nodeworks input fields
        // (Processing Set timeout, Storage Card priority, Card Programmer counter).
        // 26.1: text color alpha byte must be non-zero or GuiGraphicsExtractor.text drops the draw.
        nameField = EditBox(font, controlX, rowY(0) + 3, FIELD_W, FIELD_H - 4, Component.literal("Name"))
        nameField.setMaxLength(32)
        nameField.setBordered(true)
        nameField.setTextColor(0xFFFFFFFF.toInt())
        nameField.value = menu.initialName
        addRenderableWidget(nameField)

        valueField = EditBox(font, controlX, rowY(2) + 3, FIELD_W, FIELD_H - 4, Component.literal("Value"))
        valueField.setMaxLength(256)
        valueField.setBordered(true)
        valueField.setTextColor(0xFFFFFFFF.toInt())
        valueField.value = menu.initialValue
        addRenderableWidget(valueField)

        // Row 4, Channel picker. Same column origin (controlX) as the fields above
        // so labels and controls line up. Sends the dye ordinal via the existing
        // VariableSettingsPayload pipeline (key="channel"), reusing the server
        // handler that already mutates entity.channel for us.
        val initialChannel = runCatching { DyeColor.byId(menu.channelId) }.getOrDefault(DyeColor.WHITE)
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(controlX, rowY(3) + 3, initialChannel) { color ->
            sendUpdate("channel", color.id, "")
        }
        addRenderableWidget(picker!!)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)

        // Outer frame + title bar (tinted with the variable's network color)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor())

        // Row 1, Name field + Set
        drawLabel(graphics, "Name", 0)
        val nameFieldY = rowY(0) + 1
        drawSetButton(graphics, controlX + FIELD_W + 4, nameFieldY, mouseX, mouseY, "name")

        // Row 2, Type selector (Number / String / Bool)
        drawLabel(graphics, "Type", 1)
        val typeY = rowY(1) + 1
        for (i in 0 until 3) {
            drawTypeButton(graphics, controlX + i * (TYPE_BTN_W + TYPE_BTN_GAP), typeY, i, mouseX, mouseY)
        }

        // Row 3, Value field + Set, or Bool toggle
        drawLabel(graphics, "Value", 2)
        val valueY = rowY(2) + 1
        if (menu.variableType == VariableType.BOOL.ordinal) {
            valueField.visible = false
            drawBoolToggle(graphics, controlX, valueY, mouseX, mouseY)
        } else {
            valueField.visible = true
            drawSetButton(graphics, controlX + FIELD_W + 4, valueY, mouseX, mouseY, "value")
        }

        // Row 4, Channel label (the picker widget renders itself).
        drawLabel(graphics, "Channel", 3)

        // Sync widget to server value when the popup isn't being interacted with so
        // an external mutation doesn't snap the swatch back mid-pick.
        val serverChannel = menu.channelId
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // Render popup overlay above all other widgets so it isn't clipped.
        picker?.renderOverlay(graphics, mouseX, mouseY)
    }

    /** Network color for the title-bar trim. Client-side BFS through connected nodes,
     *  falling back to the default grey when the variable is unreachable or unnetworked. */
    private fun networkColor(): Int {
        val level = net.minecraft.client.Minecraft.getInstance().level ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(level, menu.variablePos)
    }

    private fun drawLabel(graphics: GuiGraphicsExtractor, text: String, rowIdx: Int) {
        graphics.drawString(font, text, leftPos + PAD, rowY(rowIdx) + 7, 0xFFAAAAAA.toInt())
    }

    private fun drawSetButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int, id: String) {
        val hovered = mouseX in bx until bx + SET_BTN_W && mouseY in by until by + SET_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, bx, by, SET_BTN_W, SET_BTN_H)
        val label = "Set"
        val textColor = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, bx + (SET_BTN_W - font.width(label)) / 2, by + 5, textColor)

        if (checkmarkId == id) {
            val mc = net.minecraft.client.Minecraft.getInstance()
            val elapsed = mc.level?.gameTime?.minus(checkmarkTime) ?: CHECKMARK_DURATION
            if (elapsed < CHECKMARK_DURATION) {
                Icons.CHECKMARK.draw(graphics, bx + SET_BTN_W + 1, by)
            } else {
                checkmarkId = null
            }
        }
    }

    private fun drawTypeButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, idx: Int, mouseX: Int, mouseY: Int) {
        val selected = menu.variableType == idx
        val hovered = mouseX in bx until bx + TYPE_BTN_W && mouseY in by until by + TYPE_BTN_H
        val slice = when {
            selected -> NineSlice.BUTTON_ACTIVE
            hovered -> NineSlice.BUTTON_HOVER
            else -> NineSlice.BUTTON
        }
        slice.draw(graphics, bx, by, TYPE_BTN_W, TYPE_BTN_H)
        val label = TYPE_LABELS[idx]
        val textColor = if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
        graphics.drawString(font, label, bx + (TYPE_BTN_W - font.width(label)) / 2, by + 5, textColor)
    }

    private fun drawBoolToggle(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        // TOGGLE_ACTIVE / TOGGLE_INACTIVE already encode the on/off state visually
        // (slider position + color), no extra TRUE/FALSE label needed.
        val slice = if (menu.boolValue) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE
        slice.draw(graphics, bx, by, TOGGLE_W, TOGGLE_H)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        if (nameField.isFocused || valueField.isFocused) {
            nameField.keyPressed(event)
            valueField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Channel popup gets first crack at the click while open, otherwise its grid
        // cells fall through to the buttons sitting below in the layout.
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // Row 1: Name Set, drops focus on the name field after committing.
        val nameSetX = controlX + FIELD_W + 4
        val nameSetY = rowY(0) + 1
        if (mx in nameSetX until nameSetX + SET_BTN_W && my in nameSetY until nameSetY + SET_BTN_H) {
            sendUpdate("name", 0, nameField.value)
            playClickSound()
            showCheckmark("name")
            clearFieldFocus()
            return true
        }

        // Row 2: Type buttons
        val typeY = rowY(1) + 1
        for (i in 0 until 3) {
            val bx = controlX + i * (TYPE_BTN_W + TYPE_BTN_GAP)
            if (mx in bx until bx + TYPE_BTN_W && my in typeY until typeY + TYPE_BTN_H) {
                sendUpdate("type", i, "")
                valueField.value = VariableType.fromOrdinal(i).defaultValue
                playClickSound()
                clearFieldFocus()
                return true
            }
        }

        // Row 3: Value control (Bool toggle OR value Set button)
        val valueY = rowY(2) + 1
        if (menu.variableType == VariableType.BOOL.ordinal) {
            if (mx in controlX until controlX + TOGGLE_W && my in valueY until valueY + TOGGLE_H) {
                sendUpdate("toggle", 0, "")
                playClickSound()
                clearFieldFocus()
                return true
            }
        } else {
            val valSetX = controlX + FIELD_W + 4
            if (mx in valSetX until valSetX + SET_BTN_W && my in valueY until valueY + SET_BTN_H) {
                sendUpdate("value", 0, valueField.value)
                playClickSound()
                showCheckmark("value")
                clearFieldFocus()
                return true
            }
        }

        // Let MC's widget chain handle EditBox focus (super finds the widget whose bounds
        // contain the click via ContainerEventHandler.getChildAt). If super handles it,
        // it already changed focus, we're done.
        if (super.mouseClicked(event, doubleClick)) return true

        // Click landed in empty space (no button, no field), drop focus on whichever
        // field was focused so the caret disappears, matching VSCode/IntelliJ behavior.
        clearFieldFocus()
        return false
    }

    /** Clear focus on both EditBoxes and on the parent Screen's tracked focus child
     *  (otherwise the Screen still thinks the field is focused, and the caret keeps
     *  blinking). */
    private fun clearFieldFocus() {
        nameField.isFocused = false
        valueField.isFocused = false
        setFocused(null)
    }

    private fun playClickSound() {
        net.minecraft.client.Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
            )
        )
    }

    private fun showCheckmark(id: String) {
        checkmarkId = id
        checkmarkTime = net.minecraft.client.Minecraft.getInstance().level?.gameTime ?: 0
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.VariableSettingsPayload(menu.variablePos, key, intValue, strValue)
        )
    }
}
