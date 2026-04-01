package damien.nodeworks.screen

import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

class NetworkControllerScreen(
    menu: NetworkControllerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<NetworkControllerMenu>(menu, playerInventory, title) {

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
    private enum class PropertyType { NAME, COLOR, REDSTONE, GLOW_STYLE }

    private val properties = listOf(
        Property("Name", PropertyType.NAME),
        Property("Color", PropertyType.COLOR),
        Property("Redstone", PropertyType.REDSTONE),
        Property("Node Glow", PropertyType.GLOW_STYLE)
    )

    private lateinit var nameField: EditBox
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
        imageWidth = 260
        imageHeight = 180
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
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Main window frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Top bar
        NineSlice.TOP_BAR.draw(graphics, leftPos, topPos, imageWidth, TOP_BAR_H)
        graphics.drawString(font, title, leftPos + 6, topPos + 6, 0xFFFFFFFF.toInt())

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
                                val iconsTexture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
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

                else -> {}
            }
        }

        graphics.disableScissor()

        // Scrollbar
        renderScrollbar(graphics, mouseX, mouseY)
    }

    private fun renderRedstoneControl(graphics: GuiGraphics, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
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

    private fun renderGlowStyleControl(graphics: GuiGraphics, startX: Int, by: Int, mouseX: Int, mouseY: Int) {
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

    private var glowTooltip: String? = null
    private var glowTooltipX = 0
    private var glowTooltipY = 0

    private fun renderScrollbar(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
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

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        glowTooltip = null
        super.render(graphics, mouseX, mouseY, partialTick)
        glowTooltip?.let { tip ->
            graphics.drawString(font, tip, glowTooltipX + 8, glowTooltipY - 12, 0xFFFFFFFF.toInt())
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (this.nameField.isFocused) {
            if (keyCode == 256) return super.keyPressed(keyCode, scanCode, modifiers) // ESC
            if (keyCode == 257) { // ENTER — apply name
                sendNameUpdate(this.nameField.value)
                this.nameField.isFocused = false
                nameCheckmarkTime = net.minecraft.client.Minecraft.getInstance().level?.gameTime ?: 0
                return true
            }
            this.nameField.keyPressed(keyCode, scanCode, modifiers)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

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

                PropertyType.NAME -> {
                    val setBtnX = controlX + 104
                    val setBtnW = 26
                    val setBtnH = 16
                    if (mx >= setBtnX && mx < setBtnX + setBtnW && my >= controlY && my < controlY + setBtnH) {
                        sendNameUpdate(this.nameField.value)
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

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
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
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        draggingScrollbar = false
        return super.mouseReleased(mouseX, mouseY, button)
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
