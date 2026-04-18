package damien.nodeworks.screen

import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.platform.PlatformServices
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
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

class NetworkControllerScreen(
    menu: NetworkControllerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<NetworkControllerMenu>(menu, playerInventory, title, 260, 180) {

    companion object {
        private const val DEFAULT_COLOR = 0x83E086
        private val REDSTONE_LABELS = arrayOf("Ignored", "Active Low", "Active High")
        private val GLOW_LABELS = arrayOf("Square", "Circle", "Dot", "Creeper", "Cat", "None")
        private const val GLOW_COUNT = 6

        // Layout
        private const val TOP_BAR_H = 20
        private const val ROW_H = 24
        private const val SCROLL_BAR_W = 6
        private const val LABEL_W = 60
    }

    // Property definitions
    private data class Property(val label: String, val type: PropertyType)
    private enum class PropertyType { NAME, COLOR, REDSTONE, GLOW_STYLE, HANDLER_RETRY }

    private val properties = listOf(
        Property("Name", PropertyType.NAME),
        Property("Color", PropertyType.COLOR),
        Property("Redstone", PropertyType.REDSTONE),
        Property("Node Glow", PropertyType.GLOW_STYLE),
        Property("Retries", PropertyType.HANDLER_RETRY)
    )

    private lateinit var nameField: EditBox
    private lateinit var retryField: EditBox
    private var nameCheckmarkTime: Long = -1
    private val checkmarkDuration = 30L
    private var scrollOffset = 0
    private var maxScroll = 0
    private var listTop = 0
    private var listBottom = 0
    private var listLeft = 0
    private var listRight = 0
    private var draggingScrollbar = false

    init {
        // Hide default labels — we draw our own title in the top bar
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        listLeft = leftPos + 4
        listRight = leftPos + imageWidth - 4 - SCROLL_BAR_W
        listTop = topPos + TOP_BAR_H
        listBottom = topPos + imageHeight - 4

        maxScroll = maxOf(0, properties.size * ROW_H - (listBottom - listTop))

        // Name field — will be positioned dynamically in render
        nameField = EditBox(font, listLeft + LABEL_W + 4, listTop, 100, 16, Component.literal("Name"))
        nameField.setMaxLength(32)
        nameField.value = menu.initialName
        nameField.setBordered(true)
        addRenderableWidget(nameField)

        // Retry limit field — digits only, positioned dynamically between - / + buttons.
        retryField = EditBox(font, listLeft + LABEL_W + 4, listTop, 36, 16, Component.literal("Retries"))
        retryField.setMaxLength(3)
        retryField.value = menu.handlerRetryLimit.toString()
        retryField.setBordered(true)
        retryField.setFilter { s -> s.all { it.isDigit() } }
        addRenderableWidget(retryField)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Main window frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Top bar
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, menu.networkColor)

        // List area (inset panel)
        NineSlice.PANEL_INSET.draw(graphics, listLeft, listTop, listRight - listLeft, listBottom - listTop)

        // Render scrollable property rows
        graphics.enableScissor(listLeft, listTop, listRight, listBottom)

        for (i in properties.indices) {
            val rowY = listTop + i * ROW_H - scrollOffset
            if (rowY + ROW_H < listTop) continue
            if (rowY > listBottom) break

            val prop = properties[i]

            // Row background
            val rowSlice = if (i % 2 == 0) NineSlice.ROW_HIGHLIGHT else NineSlice.ROW
            rowSlice.draw(graphics, listLeft, rowY, listRight - listLeft, ROW_H)

            // Row separator
            NineSlice.SEPARATOR.draw(graphics, listLeft, rowY + ROW_H - 2, listRight - listLeft, 3)

            // Label
            graphics.drawString(font, prop.label, listLeft + 6, rowY + (ROW_H - 8) / 2 - 1, 0xFFAAAAAA.toInt())

            val controlX = listLeft + LABEL_W + 4
            val controlY = rowY + (ROW_H - 16) / 2 - 1

            when (prop.type) {
                PropertyType.NAME -> {
                    // Position the EditBox to match the current scroll
                    nameField.setX(controlX)
                    nameField.setY(controlY)
                    nameField.visible = rowY + ROW_H > listTop && rowY < listBottom
                    // Set button next to name field
                    if (nameField.visible) {
                        val setBtnX = controlX + 104
                        val setBtnY = controlY
                        val setBtnW = 26
                        val setBtnH = 16
                        val setHovered =
                            mouseX >= setBtnX && mouseX < setBtnX + setBtnW && mouseY >= setBtnY && mouseY < setBtnY + setBtnH
                        val btnSlice = if (setHovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
                        btnSlice.draw(graphics, setBtnX, setBtnY, setBtnW, setBtnH)
                        val label = "Set"
                        val textColor = if (setHovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
                        graphics.drawString(
                            font,
                            label,
                            setBtnX + (setBtnW - font.width(label)) / 2,
                            setBtnY + 4,
                            textColor
                        )

                        // Checkmark icon after click
                        if (nameCheckmarkTime >= 0) {
                            val mc = net.minecraft.client.Minecraft.getInstance()
                            val elapsed = mc.level?.gameTime?.minus(nameCheckmarkTime) ?: checkmarkDuration
                            if (elapsed < checkmarkDuration) {
                                val iconsTexture = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                    "nodeworks",
                                    "textures/gui/icons.png"
                                )
                                graphics.blit(iconsTexture, setBtnX + setBtnW + 1, setBtnY, 0f, 0f, 16, 16, 256, 256)
                            } else {
                                nameCheckmarkTime = -1
                            }
                        }
                    }
                }

                PropertyType.COLOR -> {
                    // Color swatch with slot-style border
                    val swX = controlX
                    val swY = controlY
                    NineSlice.SLOT.draw(graphics, swX - 1, swY - 1, 18, 18)
                    graphics.fill(swX, swY, swX + 16, swY + 16, menu.networkColor or 0xFF000000.toInt())
                    // Hex text next to swatch
                    graphics.drawString(
                        font,
                        "#${String.format("%06X", menu.networkColor)}",
                        swX + 20,
                        swY + 4,
                        0xFF888888.toInt()
                    )
                }

                PropertyType.REDSTONE -> {
                    renderRedstoneControl(graphics, controlX, controlY, mouseX, mouseY)
                }

                PropertyType.GLOW_STYLE -> {
                    renderGlowStyleControl(graphics, controlX, controlY, mouseX, mouseY)
                }

                PropertyType.HANDLER_RETRY -> {
                    renderHandlerRetryControl(graphics, controlX, controlY, mouseX, mouseY)
                }

                else -> {}
            }
        }

        graphics.disableScissor()

        // Scrollbar
        renderScrollbar(graphics, mouseX, mouseY)
    }

    private fun renderRedstoneControl(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val mode = menu.redstoneMode
        val bw = 16
        val bh = 16
        val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh

        val btnSlice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        btnSlice.draw(graphics, bx, by, bw, bh)

        val icon = when (mode) {
            0 -> Icons.REDSTONE_IGNORE
            1 -> Icons.REDSTONE_INACTIVE
            2 -> Icons.REDSTONE_ACTIVE
            else -> Icons.REDSTONE_IGNORE
        }
        icon.draw(graphics, bx + (bw - 16) / 2, by)

        // Label
        graphics.drawString(font, REDSTONE_LABELS[mode], bx + bw + 4, by + 4, 0xFF888888.toInt())
    }

    private fun renderGlowStyleControl(graphics: GuiGraphicsExtractor, startX: Int, by: Int, mouseX: Int, mouseY: Int) {
        val style = menu.nodeGlowStyle
        val btnW = 16
        val btnH = 16

        for (i in 0 until GLOW_COUNT) {
            val bx = startX + i * (btnW + 2)
            val selected = style == i
            val hovered = mouseX >= bx && mouseX < bx + btnW && mouseY >= by && mouseY < by + btnH

            // Button background
            val btnSlice = when {
                selected -> NineSlice.BUTTON_ACTIVE
                hovered -> NineSlice.BUTTON_HOVER
                else -> NineSlice.BUTTON
            }
            btnSlice.draw(graphics, bx, by, btnW, btnH)

            // Draw icon from atlas
            val glowIcon = when (i) {
                0 -> Icons.GLOW_SQUARE
                1 -> Icons.GLOW_CIRCLE
                2 -> Icons.GLOW_DOT
                3 -> Icons.GLOW_CREEPER
                4 -> Icons.GLOW_CAT
                5 -> Icons.GLOW_NONE
                else -> Icons.GLOW_NONE
            }
            glowIcon.draw(graphics, bx, by)

            // Tooltip on hover
            if (hovered) {
                glowTooltip = GLOW_LABELS[i]
                glowTooltipX = mouseX
                glowTooltipY = mouseY
            }
        }
    }

    private fun renderHandlerRetryControl(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val btnW = 16
        val btnH = 16
        val fieldW = 36

        // "-" button
        val minusHovered = mouseX >= bx && mouseX < bx + btnW && mouseY >= by && mouseY < by + btnH
        (if (minusHovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, bx, by, btnW, btnH)
        val minusLabel = "-"
        graphics.drawString(font, minusLabel, bx + (btnW - font.width(minusLabel)) / 2, by + 4, 0xFFDDDDDD.toInt())

        // EditBox position + visibility; sync value from menu when not focused.
        val fieldX = bx + btnW + 4
        retryField.setX(fieldX)
        retryField.setY(by)
        retryField.width = fieldW
        val visible = by + btnH > listTop && by < listBottom
        retryField.visible = visible
        if (visible && !retryField.isFocused) {
            val current = menu.handlerRetryLimit.toString()
            if (retryField.value != current) retryField.value = current
        }

        // "+" button
        val plusX = fieldX + fieldW + 4
        val plusHovered = mouseX >= plusX && mouseX < plusX + btnW && mouseY >= by && mouseY < by + btnH
        (if (plusHovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, plusX, by, btnW, btnH)
        val plusLabel = "+"
        graphics.drawString(font, plusLabel, plusX + (btnW - font.width(plusLabel)) / 2, by + 4, 0xFFDDDDDD.toInt())
    }

    private fun commitRetryField() {
        val parsed = retryField.value.toIntOrNull() ?: return
        val clamped = parsed.coerceIn(0, 500)
        if (clamped != menu.handlerRetryLimit) sendHandlerRetryUpdate(clamped)
        retryField.value = clamped.toString()
    }

    private var glowTooltip: String? = null
    private var glowTooltipX = 0
    private var glowTooltipY = 0

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val sbX = listRight
        val sbW = SCROLL_BAR_W
        val trackH = listBottom - listTop
        val totalH = properties.size * ROW_H

        // Track
        NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, listTop, sbW, trackH)

        if (totalH > trackH) {
            val thumbH = maxOf(12, trackH * trackH / totalH)
            val thumbY = listTop + (trackH - thumbH) * scrollOffset / maxScroll
            val hovered = mouseX >= sbX && mouseX < sbX + sbW && mouseY >= listTop && mouseY < listBottom
            val thumbSlice =
                if (hovered || draggingScrollbar) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            thumbSlice.draw(graphics, sbX, thumbY, sbW, thumbH)
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        glowTooltip = null
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        glowTooltip?.let { tip ->
            graphics.drawString(font, tip, glowTooltipX + 8, glowTooltipY - 12, 0xFFFFFFFF.toInt())
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
        if (this.nameField.isFocused) {
            if (keyCode == 256) return super.keyPressed(event) // ESC
            if (keyCode == 257) { // ENTER — apply name
                sendNameUpdate(this.nameField.value)
                this.nameField.isFocused = false
                nameCheckmarkTime = net.minecraft.client.Minecraft.getInstance().level?.gameTime ?: 0
                return true
            }
            this.nameField.keyPressed(event)
            return true
        }
        if (this.retryField.isFocused) {
            if (keyCode == 256) return super.keyPressed(event) // ESC
            if (keyCode == 257) { // ENTER — commit retries
                commitRetryField()
                this.retryField.isFocused = false
                return true
            }
            this.retryField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Deselect name field if clicking outside it and the Set button
        if (nameField.isFocused) {
            val nameRow = listTop + 0 * ROW_H - scrollOffset
            val controlX = listLeft + LABEL_W + 4
            val controlY = nameRow + (ROW_H - 16) / 2 - 1
            val setBtnX = controlX + 104
            val setBtnW = 26
            val inNameField = mx >= controlX && mx < controlX + 100 && my >= controlY && my < controlY + 16
            val inSetBtn = mx >= setBtnX && mx < setBtnX + setBtnW && my >= controlY && my < controlY + 16
            if (!inNameField && !inSetBtn) {
                nameField.isFocused = false
            }
        }

        // Scrollbar drag start
        if (mx >= listRight && mx < listRight + SCROLL_BAR_W && my >= listTop && my < listBottom && maxScroll > 0) {
            draggingScrollbar = true
            return true
        }

        // Check property row clicks
        for (i in properties.indices) {
            val rowY = listTop + i * ROW_H - scrollOffset
            if (rowY + ROW_H < listTop || rowY > listBottom) continue

            val controlX = listLeft + LABEL_W + 4
            val controlY = rowY + (ROW_H - 16) / 2 - 1
            val prop = properties[i]

            when (prop.type) {
                PropertyType.COLOR -> {
                    if (mx >= controlX && mx < controlX + 16 && my >= controlY && my < controlY + 16) {
                        minecraft?.setScreen(ColorPickerScreen(this, menu.networkColor, DEFAULT_COLOR) { color ->
                            sendColorUpdate(color)
                        })
                        return true
                    }
                }

                PropertyType.REDSTONE -> {
                    val bw = 20;
                    val bh = 16
                    if (mx >= controlX && mx < controlX + bw && my >= controlY && my < controlY + bh) {
                        sendRedstoneUpdate((menu.redstoneMode + 1) % 3)
                        return true
                    }
                }

                PropertyType.GLOW_STYLE -> {
                    val btnW = 16;
                    val btnH = 16
                    for (j in 0 until GLOW_COUNT) {
                        val bx = controlX + j * (btnW + 2)
                        if (mx >= bx && mx < bx + btnW && my >= controlY && my < controlY + btnH) {
                            sendGlowStyleUpdate(j)
                            return true
                        }
                    }
                }

                PropertyType.HANDLER_RETRY -> {
                    val btnW = 16
                    val btnH = 16
                    val fieldW = 36
                    val step = if (hasShiftDownCompat()) 50 else 10
                    val current = menu.handlerRetryLimit
                    val fieldX = controlX + btnW + 4
                    val plusX = fieldX + fieldW + 4
                    // Clicking outside the EditBox while it's focused commits current value.
                    val inField = mx >= fieldX && mx < fieldX + fieldW && my >= controlY && my < controlY + btnH
                    if (retryField.isFocused && !inField) {
                        commitRetryField()
                    }
                    // Minus
                    if (mx >= controlX && mx < controlX + btnW && my >= controlY && my < controlY + btnH) {
                        val next = (current - step).coerceAtLeast(0)
                        if (next != current) sendHandlerRetryUpdate(next)
                        return true
                    }
                    // Plus
                    if (mx >= plusX && mx < plusX + btnW && my >= controlY && my < controlY + btnH) {
                        val next = (current + step).coerceAtMost(500)
                        if (next != current) sendHandlerRetryUpdate(next)
                        return true
                    }
                }

                PropertyType.NAME -> {
                    val setBtnX = controlX + 104
                    val setBtnW = 26
                    val setBtnH = 16
                    if (mx >= setBtnX && mx < setBtnX + setBtnW && my >= controlY && my < controlY + setBtnH) {
                        sendNameUpdate(this.nameField.value)
                        nameField.isFocused = false
                        nameCheckmarkTime = net.minecraft.client.Minecraft.getInstance().level?.gameTime ?: 0
                        minecraft?.player?.playSound(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(),
                            0.5f,
                            1.0f
                        )
                        return true
                    }
                }

                else -> {}
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (draggingScrollbar && maxScroll > 0) {
            val trackH = listBottom - listTop
            val totalH = properties.size * ROW_H
            val thumbH = maxOf(12, trackH * trackH / totalH)
            val scrollRange = trackH - thumbH
            if (scrollRange > 0) {
                val relY = (mouseY.toInt() - listTop - thumbH / 2).toFloat() / scrollRange
                scrollOffset = (relY * maxScroll).toInt().coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        draggingScrollbar = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseX >= listLeft && mouseX < listRight + SCROLL_BAR_W && mouseY >= listTop && mouseY < listBottom) {
            scrollOffset = (scrollOffset - (scrollY * 12).toInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun removed() {
        super.removed()
        sendNameUpdate(nameField.value)
        commitRetryField()
    }

    private fun sendColorUpdate(color: Int) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.ControllerSettingsPayload(menu.controllerPos, "color", color, "")
        )
    }

    private fun sendRedstoneUpdate(mode: Int) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.ControllerSettingsPayload(menu.controllerPos, "redstone", mode, "")
        )
    }

    private fun sendHandlerRetryUpdate(value: Int) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.ControllerSettingsPayload(menu.controllerPos, "retry", value, "")
        )
    }

    private fun sendGlowStyleUpdate(style: Int) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.ControllerSettingsPayload(menu.controllerPos, "glow", style, "")
        )
    }

    private fun sendNameUpdate(name: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.ControllerSettingsPayload(menu.controllerPos, "name", 0, name)
        )
    }
}
