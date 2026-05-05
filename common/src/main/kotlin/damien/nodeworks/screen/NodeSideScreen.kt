package damien.nodeworks.screen

import damien.nodeworks.network.*
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.NodeSideScreenHandler

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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

class NodeSideScreen(
    menu: NodeSideScreenHandler,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<NodeSideScreenHandler>(menu, playerInventory, title, W, H) {

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

    /** Cached adjacent-block stacks for all six sides, indexed by
     *  [Direction.ordinal]. Populated on init and refreshed each frame so
     *  every tab can show its side's facing block at a glance, not just the
     *  active one. Cheap, the per-frame cost is six `level.getBlockState`
     *  hash lookups. */
    private val facingStacks: Array<ItemStack> = Array(6) { ItemStack.EMPTY }

    /** Display name for the active side's facing block, shown under the icon
     *  in the main panel. Refreshed alongside [facingStacks]. */
    private var facingBlockName: String = ""

    /** Displayed title, updated when switching sides. */
    private var sideTitle: Component = title

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        updateFacingBlocks()
    }

    private fun updateFacingBlocks() {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val level = mc.level ?: return
        val nodePos = menu.getNodePos()
        for (i in 0 until 6) {
            val dir = Direction.entries[i]
            val state = level.getBlockState(nodePos.relative(dir))
            val item = state.block.asItem()
            facingStacks[i] = if (item != net.minecraft.world.item.Items.AIR) ItemStack(item) else ItemStack.EMPTY
        }
        val activeIdx = menu.activeSide.ordinal
        val activeStack = facingStacks[activeIdx]
        facingBlockName = if (!activeStack.isEmpty) {
            activeStack.hoverName.string
        } else {
            level.getBlockState(nodePos.relative(menu.activeSide)).block.name.string
        }
    }

    private fun switchToSide(newSide: Direction) {
        menu.switchSide(newSide)

        PlatformServices.clientNetworking.sendToServer(
            SwitchNodeSidePayload(menu.getNodePos(), newSide.ordinal)
        )

        val sideName = newSide.name.replaceFirstChar { it.uppercase() }
        sideTitle = Component.translatable("container.nodeworks.node_side", sideName)

        updateFacingBlocks()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Refresh per frame so the tab icons reflect a player breaking or
        // placing the adjacent block while the GUI is open. Six chunk hash
        // lookups per frame, negligible.
        updateFacingBlocks()
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(menu.getNodePos())
        val trimColor = if (reachable) {
            val nodeEntity = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(menu.getNodePos()) as? damien.nodeworks.network.Connectable
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(nodeEntity?.networkId)
        } else {
            -1
        }
        NineSlice.drawTitleBar(graphics, font, sideTitle, leftPos, topPos, imageWidth, TOP_BAR_H, trimColor)

        renderTabs(graphics, mouseX, mouseY, trimColor)

        NineSlice.drawSlotGrid(graphics, leftPos + CARD_GRID_X, topPos + CARD_AREA_Y, 3, 3)

        renderFacingBlock(graphics)

        graphics.drawString(font, "Inventory", leftPos + INV_X, topPos + INV_LABEL_Y, 0xFFAAAAAA.toInt())

        NineSlice.drawPlayerInventory(graphics, leftPos + INV_X, topPos + INV_Y, HOTBAR_GAP)
    }

    private fun renderTabs(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, trimColor: Int) {
        val currentSide = menu.activeSide.ordinal
        val tabCount = 6
        val totalW = imageWidth - 6
        val tabW = totalW / tabCount
        val startX = leftPos + 3
        val tabY = topPos + TAB_ROW_Y

        val nodeBe = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(menu.getNodePos())
            as? damien.nodeworks.block.entity.NodeBlockEntity

        for (i in 0 until tabCount) {
            val tx = startX + i * tabW
            val isActive = i == currentSide
            val isPipe = nodeBe?.faceRole(Direction.entries[i]) ==
                damien.nodeworks.block.entity.NodeBlockEntity.FaceRole.PIPE
            val isHovered = !isActive && !isPipe &&
                mouseX >= tx && mouseX < tx + tabW && mouseY >= tabY && mouseY < tabY + TAB_H

            val slice = when {
                isActive -> NineSlice.TAB_ACTIVE
                isHovered -> NineSlice.TAB_HOVER
                else -> NineSlice.TAB_INACTIVE
            }
            slice.draw(graphics, tx, tabY, tabW, TAB_H)

            if (isActive && trimColor >= 0) {
                NineSlice.TAB_TRIM.drawTinted(graphics, tx, tabY, tabW, TAB_H, trimColor, 0.7f)
            }

            val label = SIDE_LABELS[i]
            val labelColor = when {
                isPipe -> 0xFF555555.toInt()       // dim, this side is consumed by a pipe
                isActive -> 0xFFFFFFFF.toInt()
                else -> 0xFFAAAAAA.toInt()
            }
            // 2-px nudge down so the letter optically sits in the lower half
            // of the tab, off the active-tab bevel highlight at the top.
            val labelY = tabY + (TAB_H - font.lineHeight) / 2 + 2

            // Tab is split into left half (icon area) and right half (label
            // area). Letter X is fixed regardless of icon presence so the
            // letters all line up vertically across the row.
            val iconAreaW = tabW / 2
            val labelAreaX = tx + iconAreaW
            val labelAreaW = tabW - iconAreaW
            val labelX = labelAreaX + (labelAreaW - font.width(label)) / 2

            val stack = facingStacks[i]
            if (!stack.isEmpty) {
                // 12-px icon scaled from 16×16, centred vertically and nudged
                // 2 px right within the left half.
                val iconSize = 12
                val iconX = tx + (iconAreaW - iconSize) / 2 + 2
                val iconY = tabY + (TAB_H - iconSize) / 2
                val scale = iconSize / 16f
                graphics.pose().pushMatrix()
                graphics.pose().translate(iconX.toFloat(), iconY.toFloat())
                graphics.pose().scale(scale, scale)
                graphics.renderItem(stack, 0, 0)
                graphics.pose().popMatrix()
            }

            graphics.drawString(font, label, labelX, labelY, labelColor)
        }
    }

    private fun renderFacingBlock(graphics: GuiGraphicsExtractor) {
        val labelX = leftPos + 8
        val iconY = topPos + CARD_AREA_Y + 18

        graphics.drawString(font, "Facing", labelX, topPos + CARD_AREA_Y + 6, 0xFFAAAAAA.toInt())

        val activeStack = facingStacks[menu.activeSide.ordinal]
        if (!activeStack.isEmpty) {
            graphics.renderItem(activeStack, leftPos + 22, iconY)
        } else {
            graphics.drawString(font, "Air", labelX + 4, iconY + 4, 0xFF666666.toInt())
        }

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

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        // 26.1: automatic tooltip via extractTooltip. renderTooltip(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (button == 0) {
            val currentSide = menu.activeSide.ordinal
            val tabCount = 6
            val totalW = imageWidth - 6
            val tabW = totalW / tabCount
            val startX = leftPos + 3
            val tabY = topPos + TAB_ROW_Y

            val nodeBe = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(menu.getNodePos())
                as? damien.nodeworks.block.entity.NodeBlockEntity
            for (i in 0 until tabCount) {
                if (i == currentSide) continue
                val tx = startX + i * tabW
                if (mouseX >= tx && mouseX < tx + tabW && mouseY >= tabY && mouseY < tabY + TAB_H) {
                    // Refuse switching to a pipe-roled face, the server-side
                    // handler also guards but the click should feel unresponsive
                    // here too rather than fire a doomed payload.
                    if (nodeBe?.faceRole(Direction.entries[i]) ==
                        damien.nodeworks.block.entity.NodeBlockEntity.FaceRole.PIPE
                    ) return true
                    switchToSide(Direction.entries[i])
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }
}
