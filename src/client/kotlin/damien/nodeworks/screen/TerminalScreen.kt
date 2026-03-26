package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.screen.TerminalScreenHandler
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class TerminalScreen(
    menu: TerminalScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TerminalScreenHandler>(menu, playerInventory, title) {

    private lateinit var editor: MultiLineEditBox
    private val cards: List<CardSnapshot> = menu.getCards()
    private var scriptRunning: Boolean = menu.isRunning()
    private var autoRun: Boolean = menu.isAutoRun()

    // Layout constants
    private val cardPanelWidth = 80
    private val editorPadding = 4
    private val buttonHeight = 20
    private val topBarHeight = 24

    init {
        imageWidth = 320
        imageHeight = 220
    }

    override fun init() {
        super.init()

        val editorX = leftPos + cardPanelWidth + editorPadding
        val editorY = topPos + topBarHeight
        val editorW = imageWidth - cardPanelWidth - editorPadding * 2
        val editorH = imageHeight - topBarHeight - editorPadding

        editor = MultiLineEditBox.builder()
            .setX(editorX)
            .setY(editorY)
            .setShowBackground(true)
            .setShowDecorations(true)
            .build(font, editorW, editorH, Component.literal("Script"))

        editor.setValue(menu.getScriptText())
        editor.setCharacterLimit(32767)
        addRenderableWidget(editor)

        // Run button
        addRenderableWidget(Button.builder(Component.literal("Run")) { _ ->
            ClientPlayNetworking.send(TerminalPackets.RunScriptPayload(menu.getTerminalPos(), editor.value))
            scriptRunning = true
        }.bounds(leftPos + imageWidth - 90, topPos + 2, 40, buttonHeight).build())

        // Stop button
        addRenderableWidget(Button.builder(Component.literal("Stop")) { _ ->
            ClientPlayNetworking.send(TerminalPackets.StopScriptPayload(menu.getTerminalPos()))
            scriptRunning = false
        }.bounds(leftPos + imageWidth - 46, topPos + 2, 40, buttonHeight).build())

        // Auto-run toggle — in the card panel area below the card list
        addRenderableWidget(Button.builder(autoRunLabel()) { btn ->
            autoRun = !autoRun
            ClientPlayNetworking.send(TerminalPackets.ToggleAutoRunPayload(menu.getTerminalPos(), autoRun))
            btn.message = autoRunLabel()
        }.bounds(leftPos + 4, topPos + imageHeight - 24, cardPanelWidth - 8, buttonHeight).build())
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Main background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + topBarHeight, 0xFF3C3C3C.toInt())

        // Card panel background
        graphics.fill(leftPos, topPos + topBarHeight, leftPos + cardPanelWidth, topPos + imageHeight, 0xFF333333.toInt())

        // Card panel separator
        graphics.fill(leftPos + cardPanelWidth, topPos + topBarHeight, leftPos + cardPanelWidth + 1, topPos + imageHeight, 0xFF555555.toInt())

        // Title in top bar
        graphics.drawString(font, title, leftPos + 6, topPos + 7, 0xFFFFFFFF.toInt())

        // Status indicator — circle + text, left of the buttons
        val statusX = leftPos + imageWidth - 150
        val statusY = topPos + 7
        val circleColor = if (scriptRunning) 0xFF55FF55.toInt() else 0xFF666666.toInt()
        val statusText = if (scriptRunning) "Running" else "Stopped"
        val textColor = if (scriptRunning) 0xFF55FF55.toInt() else 0xFF888888.toInt()
        // Draw a small filled circle (approximated as a 5x5 square with rounded feel)
        graphics.fill(statusX + 1, statusY, statusX + 4, statusY + 5, circleColor)
        graphics.fill(statusX, statusY + 1, statusX + 5, statusY + 4, circleColor)
        graphics.drawString(font, statusText, statusX + 7, statusY, textColor)

        // Card list header
        val cardStartY = topPos + topBarHeight + 6
        graphics.drawString(font, "Cards:", leftPos + 6, cardStartY, 0xFFAAAAAA.toInt())

        // Card entries
        for ((i, card) in cards.withIndex()) {
            val y = cardStartY + 12 + i * 11
            if (y + 11 > topPos + imageHeight) break
            val alias = card.alias ?: "${card.capability.type}#${i + 1}"
            val color = when (card.capability.type) {
                "inventory" -> 0xFF83E086.toInt()
                "energy" -> 0xFFFFD700.toInt()
                "fluid" -> 0xFF55AAFF.toInt()
                else -> 0xFFAAAAAA.toInt()
            }
            graphics.drawString(font, alias, leftPos + 6, y, color)
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (editor.isFocused) {
            // Escape closes the screen
            if (keyEvent.key() == InputConstants.KEY_ESCAPE) {
                return super.keyPressed(keyEvent)
            }
            // Tab inserts 4 spaces
            if (keyEvent.key() == InputConstants.KEY_TAB) {
                editor.charTyped(net.minecraft.client.input.CharacterEvent(' '.code, 0))
                editor.charTyped(net.minecraft.client.input.CharacterEvent(' '.code, 0))
                editor.charTyped(net.minecraft.client.input.CharacterEvent(' '.code, 0))
                editor.charTyped(net.minecraft.client.input.CharacterEvent(' '.code, 0))
                return true
            }
            return editor.keyPressed(keyEvent)
        }
        return super.keyPressed(keyEvent)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default inventory labels
    }

    override fun onClose() {
        // Save script text to server when closing
        ClientPlayNetworking.send(TerminalPackets.SaveScriptPayload(menu.getTerminalPos(), editor.value))
        super.onClose()
    }

    private fun autoRunLabel(): Component {
        val state = if (autoRun) "\u00A7aON" else "\u00A77OFF"
        return Component.literal("Auto: $state")
    }

    override fun isPauseScreen(): Boolean = false
}
