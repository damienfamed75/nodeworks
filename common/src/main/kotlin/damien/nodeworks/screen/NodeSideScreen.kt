package damien.nodeworks.screen

import damien.nodeworks.card.StorageCard
import damien.nodeworks.network.*
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.NodeSideScreenHandler

import damien.nodeworks.screen.widget.SlicedButton
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

class NodeSideScreen(
    menu: NodeSideScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<NodeSideScreenHandler>(menu, playerInventory, title) {

    companion object {
        // Layout constants
        private const val W = 176
        private const val TOP_BAR_H = 20
        private const val TAB_ROW_Y = TOP_BAR_H           // tabs start right after title bar
        private const val TAB_H = 16
        private const val TAB_SHIFT = NodeSideScreenHandler.TAB_SHIFT  // 18
        private const val CARD_AREA_Y = 24 + TAB_SHIFT     // top bar + tabs + padding
        private const val CARD_GRID_X = 62                 // centered 3x3 grid
        private const val INV_LABEL_Y = 82 + TAB_SHIFT     // "Inventory" label
        private const val INV_Y = 92 + TAB_SHIFT           // player inventory start
        private const val INV_X = 8
        private const val HOTBAR_GAP = 4
        private const val H = INV_Y + 3 * 18 + HOTBAR_GAP + 18 + 8  // = 190

        private val SIDE_LABELS = arrayOf("D", "U", "N", "S", "W", "E")
    }

    private var priorityValue = 0
    private var hasStorageCard = false

    /** Cached facing block item for rendering. */
    private var facingBlockStack: ItemStack = ItemStack.EMPTY
    private var facingBlockName: String = ""

    /** Displayed title — updated when switching sides. */
    private var sideTitle: Component = title

    init {
        imageWidth = W
        imageHeight = H
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        detectStorageCard()
        updateFacingBlock()
        addPriorityButtons()
    }

    private fun addPriorityButtons() {
        if (hasStorageCard) {
            addRenderableWidget(SlicedButton.create(
                leftPos + 120, topPos + CARD_AREA_Y + 20, 14, 14, "-"
            ) { _ ->
                priorityValue = (priorityValue - 1).coerceIn(0, 99)
                sendPriorityUpdate()
            })

            addRenderableWidget(SlicedButton.create(
                leftPos + 155, topPos + CARD_AREA_Y + 20, 14, 14, "+"
            ) { _ ->
                priorityValue = (priorityValue + 1).coerceIn(0, 99)
                sendPriorityUpdate()
            })
        }
    }

    private fun updateFacingBlock() {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val level = mc.level ?: return
        val adjacentPos = menu.getNodePos().relative(menu.activeSide)
        val blockState = level.getBlockState(adjacentPos)
        val item = blockState.block.asItem()
        facingBlockStack = if (item != net.minecraft.world.item.Items.AIR) ItemStack(item) else ItemStack.EMPTY
        facingBlockName = if (!facingBlockStack.isEmpty) {
            facingBlockStack.hoverName.string
        } else {
            blockState.block.name.string
        }
    }

    private fun detectStorageCard() {
        val activeStart = menu.activeSide.ordinal * 9
        for (i in activeStart until activeStart + 9) {
            val stack = menu.getSlot(i).item
            if (stack.item is StorageCard) {
                hasStorageCard = true
                priorityValue = StorageCard.getPriority(stack)
                return
            }
        }
        hasStorageCard = false
    }

    private fun findStorageCardSlot(): Int {
        val activeStart = menu.activeSide.ordinal * 9
        for (i in activeStart until activeStart + 9) {
            val stack = menu.getSlot(i).item
            if (stack.item is StorageCard) return i - activeStart
        }
        return -1
    }

    private fun sendPriorityUpdate() {
        val slot = findStorageCardSlot()
        if (slot < 0) return
        PlatformServices.clientNetworking.sendToServer(
            SetStoragePriorityPayload(
                menu.getNodePos(),
                menu.activeSide.ordinal,
                slot,
                priorityValue
            )
        )
    }

    private fun switchToSide(newSide: Direction) {
        // Update menu (client-side)
        menu.switchSide(newSide)

        // Notify server
        PlatformServices.clientNetworking.sendToServer(
            SwitchNodeSidePayload(menu.getNodePos(), newSide.ordinal)
        )

        // Update title
        val sideName = newSide.name.replaceFirstChar { it.uppercase() }
        sideTitle = Component.translatable("container.nodeworks.node_side", sideName)

        // Refresh state for the new side
        updateFacingBlock()
        val prevHas = hasStorageCard
        detectStorageCard()
        if (prevHas != hasStorageCard) {
            rebuildWidgets()
        }
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Window frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Top bar with network color trim (gray if disconnected)
        val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(menu.getNodePos())
        val trimColor = if (reachable) {
            val nodeEntity = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(menu.getNodePos()) as? damien.nodeworks.network.Connectable
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(nodeEntity?.networkId)
        } else {
            -1
        }
        NineSlice.drawTitleBar(graphics, font, sideTitle, leftPos, topPos, imageWidth, TOP_BAR_H, trimColor)

        // Tab row — 6 side tabs
        renderTabs(graphics, mouseX, mouseY, trimColor)

        // 3x3 card slot grid
        NineSlice.drawSlotGrid(graphics, leftPos + CARD_GRID_X, topPos + CARD_AREA_Y, 3, 3)

        // Facing block icon + label (left of card grid)
        renderFacingBlock(graphics)

        // Priority controls
        if (hasStorageCard) {
            graphics.drawString(font, "Priority", leftPos + 118, topPos + CARD_AREA_Y + 10, 0xFFAAAAAA.toInt())
            val px = leftPos + 134
            val py = topPos + CARD_AREA_Y + 22
            graphics.drawString(font, "$priorityValue", px + (14 - font.width("$priorityValue")) / 2, py + 3, 0xFFFFFFFF.toInt())
        }

        // Inventory label
        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_LABEL_Y, 0xFFAAAAAA.toInt())

        // Player inventory (3x9 + hotbar)
        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_Y, HOTBAR_GAP)
    }

    private fun renderTabs(graphics: GuiGraphics, mouseX: Int, mouseY: Int, trimColor: Int) {
        val currentSide = menu.activeSide.ordinal
        val tabCount = 6
        val totalW = imageWidth - 6  // 3px inset on each side
        val tabW = totalW / tabCount
        val startX = leftPos + 3
        val tabY = topPos + TAB_ROW_Y

        for (i in 0 until tabCount) {
            val tx = startX + i * tabW
            val isActive = i == currentSide
            val isHovered = !isActive && mouseX >= tx && mouseX < tx + tabW && mouseY >= tabY && mouseY < tabY + TAB_H

            val slice = when {
                isActive -> NineSlice.TAB_ACTIVE
                isHovered -> NineSlice.TAB_HOVER
                else -> NineSlice.TAB_INACTIVE
            }
            slice.draw(graphics, tx, tabY, tabW, TAB_H)

            // Tint active tab with network color
            if (isActive && trimColor >= 0) {
                NineSlice.TAB_TRIM.drawTinted(graphics, tx, tabY, tabW, TAB_H, trimColor, 0.7f)
            }

            // Label
            val label = SIDE_LABELS[i]
            val labelColor = if (isActive) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            val labelX = tx + (tabW - font.width(label)) / 2
            val labelY = tabY + (TAB_H - font.lineHeight) / 2
            graphics.drawString(font, label, labelX, labelY, labelColor)
        }
    }

    private fun renderFacingBlock(graphics: GuiGraphics) {
        val labelX = leftPos + 8
        val iconY = topPos + CARD_AREA_Y + 18

        // "Facing" label
        graphics.drawString(font, "Facing", labelX, topPos + CARD_AREA_Y + 6, 0xFFAAAAAA.toInt())

        // Block icon (16x16)
        if (!facingBlockStack.isEmpty) {
            graphics.renderItem(facingBlockStack, leftPos + 22, iconY)
        } else {
            // Air or unloaded — show "Air" text
            graphics.drawString(font, "Air", labelX + 4, iconY + 4, 0xFF666666.toInt())
        }

        // Block name (truncated to fit)
        val maxNameW = CARD_GRID_X - 10
        var displayName = facingBlockName
        if (font.width(displayName) > maxNameW) {
            while (displayName.isNotEmpty() && font.width("$displayName...") > maxNameW) {
                displayName = displayName.dropLast(1)
            }
            displayName = "$displayName..."
        }
        graphics.drawString(font, displayName, labelX, iconY + 20, 0xFF888888.toInt())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val prevHas = hasStorageCard
        detectStorageCard()
        if (prevHas != hasStorageCard) {
            rebuildWidgets()
        }

        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // Check tab clicks
        if (button == 0) {
            val currentSide = menu.activeSide.ordinal
            val tabCount = 6
            val totalW = imageWidth - 6
            val tabW = totalW / tabCount
            val startX = leftPos + 3
            val tabY = topPos + TAB_ROW_Y

            for (i in 0 until tabCount) {
                if (i == currentSide) continue
                val tx = startX + i * tabW
                if (mouseX >= tx && mouseX < tx + tabW && mouseY >= tabY && mouseY < tabY + TAB_H) {
                    switchToSide(Direction.entries[i])
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }
}
