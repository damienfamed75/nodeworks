package damien.nodeworks.screen

import damien.nodeworks.network.CancelCraftPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.CraftTreeGraph
import damien.nodeworks.screen.widget.SlicedButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

class CraftingCoreScreen(
    menu: CraftingCoreMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CraftingCoreMenu>(menu, playerInventory, title) {

    companion object {
        private const val LEFT_PANEL_W = 126  // 6 cols × 18px + 6px scrollbar + 10px padding
        private const val DIVIDER_W = 1
        private const val RIGHT_PANEL_W = 160
        private const val PADDING = 6
        private const val TOP_BAR_H = 20
    }

    private var bufferScrollOffset = 0
    private var cancelButton: SlicedButton? = null
    private val craftGraph = CraftTreeGraph()

    private fun formatCount(count: Int): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }

    init {
        imageWidth = LEFT_PANEL_W + DIVIDER_W + RIGHT_PANEL_W + PADDING * 2
        imageHeight = 194
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        val btnX = leftPos + PADDING + 2
        val btnY = topPos + TOP_BAR_H + 48
        cancelButton = SlicedButton.createColored(btnX, btnY, 54, 14, "Cancel",
            0xFFFF8888.toInt(), 0xFFFFAAAA.toInt()
        ) { _ ->
            PlatformServices.clientNetworking.sendToServer(CancelCraftPayload(menu.corePos))
        }
        addRenderableWidget(cancelButton!!)
    }

    // ========== Background Rendering ==========

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Title bar with network color
        val mc = Minecraft.getInstance()
        val coreEntity = mc.level?.getBlockEntity(menu.corePos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity
        val networkColor = coreEntity?.networkId?.let {
            damien.nodeworks.network.NetworkSettingsRegistry.getColor(it)
        } ?: 0x888888
        NineSlice.drawTitleBar(graphics, font, Component.literal("Crafting CPU"), leftPos, topPos, imageWidth, trimColor = networkColor)

        val contentTop = topPos + TOP_BAR_H + PADDING
        val contentLeft = leftPos + PADDING

        // --- Left panel ---
        renderInfoPanel(graphics, coreEntity, contentLeft, contentTop, mouseX, mouseY)

        // --- Vertical divider ---
        val dividerX = leftPos + PADDING + LEFT_PANEL_W
        graphics.fill(dividerX, topPos + TOP_BAR_H, dividerX + DIVIDER_W, topPos + imageHeight - PADDING, 0xFF444444.toInt())

        // --- Right panel: Craft tree graph ---
        val treeLeft = dividerX + DIVIDER_W + 1
        val treeTop = topPos + TOP_BAR_H + 1
        val treeW = RIGHT_PANEL_W + 1
        val treeH = imageHeight - TOP_BAR_H - PADDING + 1

        craftGraph.activeSteps = menu.activeSteps
        craftGraph.render(graphics, menu.craftTree, treeLeft, treeTop, treeW, treeH)
    }

    private fun renderInfoPanel(
        graphics: GuiGraphics,
        coreEntity: damien.nodeworks.block.entity.CraftingCoreBlockEntity?,
        contentLeft: Int, contentTop: Int,
        mouseX: Int, mouseY: Int
    ) {
        // Status line
        val statusLabel = when {
            !menu.isFormed -> "Not Formed"
            menu.isCrafting -> "Crafting"
            else -> "Idle"
        }
        val statusColor = when {
            !menu.isFormed -> 0xFFFF5555.toInt()
            menu.isCrafting -> 0xFF55FF55.toInt()
            else -> 0xFFAAAAAA.toInt()
        }
        graphics.drawString(font, "Status:", contentLeft, contentTop, 0xFFAAAAAA.toInt())
        graphics.drawString(font, statusLabel, contentLeft + font.width("Status:") + 4, contentTop, statusColor)

        // Job line (only when crafting)
        var nextY = contentTop + 12
        if (menu.isCrafting) {
            val originalId = coreEntity?.originalCraftId ?: ""
            val originalCount = coreEntity?.originalCraftCount ?: 0
            val craftName = coreEntity?.currentCraftItem ?: originalId.substringAfter(':').replace('_', ' ')
            val jobLabel = if (originalCount > 1) "$craftName (x$originalCount)" else craftName
            graphics.drawString(font, "Job:", contentLeft, nextY, 0xFFAAAAAA.toInt())
            val jobX = contentLeft + font.width("Job:") + 4
            val maxJobW = LEFT_PANEL_W - font.width("Job:") - 8
            val jobTrimmed = if (font.width(jobLabel) > maxJobW) font.plainSubstrByWidth(jobLabel, maxJobW - 4) + ".." else jobLabel
            graphics.drawString(font, jobTrimmed, jobX, nextY, 0xFFFFCC44.toInt())
            nextY += 12
        }

        // Buffer bar
        val barTop = nextY + 2
        graphics.drawString(font, "Buffer:", contentLeft, barTop, 0xFFAAAAAA.toInt())
        val barX = contentLeft + font.width("Buffer:") + 4
        val barW = LEFT_PANEL_W - font.width("Buffer:") - 8
        val barH = 10

        NineSlice.CONTENT_BORDER.draw(graphics, barX - 2, barTop - 2, barW + 4, barH + 4)
        graphics.fill(barX, barTop, barX + barW, barTop + barH, 0xFF1A1A1A.toInt())

        if (menu.bufferCapacity > 0) {
            val fillW = (barW * menu.bufferUsed.toLong() / menu.bufferCapacity).toInt().coerceAtMost(barW)
            if (fillW > 0) {
                val fillColor = if (menu.bufferUsed > menu.bufferCapacity * 0.9) 0xFFFF5555.toInt() else 0xFF55AA55.toInt()
                graphics.fill(barX, barTop, barX + fillW, barTop + barH, fillColor)
            }
        }
        val countText = "${menu.bufferUsed} / ${menu.bufferCapacity}"
        val countWidth = font.width(countText)
        graphics.drawString(font, countText, barX + (barW - countWidth) / 2, barTop + 1, 0xFFFFFFFF.toInt())

        // Cancel button visibility
        cancelButton?.visible = menu.isCrafting || menu.bufferUsed > 0

        // Cancel button position (moves with layout)
        cancelButton?.y = barTop + barH + 6

        // Buffer section — always visible with border and scrollbar track
        val bufferLabelTop = barTop + barH + 24
        graphics.drawString(font, "Buffer:", contentLeft, bufferLabelTop, 0xFFAAAAAA.toInt())

        val gridTop = bufferLabelTop + 12
        val slotSize = 18
        val bufferCols = 6
        val bufferRows = 5
        val scrollbarW = 6
        val gridW = bufferCols * slotSize
        val gridH = bufferRows * slotSize
        val cols = bufferCols

        // Inset around the buffer area + scrollbar
        val sbX = contentLeft + gridW + 4
        NineSlice.PANEL_INSET.draw(graphics, contentLeft - 2, gridTop - 2, gridW + scrollbarW + 10, gridH + 4)

        // Scrollbar track (always visible, inside the inset)
        NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, gridTop, scrollbarW, gridH)

        if (!menu.isFormed) {
            graphics.drawString(font, "Not formed", contentLeft + 4, gridTop + 4, 0xFF888888.toInt())
        } else {
            renderBufferGrid(graphics, contentLeft, gridTop, gridW, gridH, cols, sbX, scrollbarW)
        }
    }

    private fun renderBufferGrid(
        graphics: GuiGraphics,
        startX: Int, startY: Int,
        gridW: Int, gridH: Int,
        cols: Int, sbX: Int, scrollbarW: Int
    ) {
        val contents = menu.clientBufferContents
        val slotSize = 18
        val rows = maxOf(1, gridH / slotSize)
        val totalRows = (contents.size + cols - 1) / cols
        val maxScroll = maxOf(0, totalRows - rows)
        bufferScrollOffset = bufferScrollOffset.coerceIn(0, maxScroll)

        graphics.enableScissor(startX, startY, startX + cols * slotSize, startY + rows * slotSize)

        for ((i, entry) in contents.withIndex()) {
            val row = i / cols - bufferScrollOffset
            val col = i % cols
            if (row < 0 || row >= rows) continue
            val ix = startX + col * slotSize
            val iy = startY + row * slotSize
            val id = ResourceLocation.tryParse(entry.first) ?: continue
            val item = BuiltInRegistries.ITEM.get(id) ?: continue
            val stack = ItemStack(item, 1)
            NineSlice.SLOT.draw(graphics, ix - 1, iy - 1, 18, 18)
            graphics.renderItem(stack, ix, iy)
        }

        // Count text at higher Z with 0.5x scale
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 200f)
        val scale = 0.5f
        graphics.pose().scale(scale, scale, 1f)
        for ((i, entry) in contents.withIndex()) {
            val row = i / cols - bufferScrollOffset
            val col = i % cols
            if (row < 0 || row >= rows) continue
            val ix = startX + col * slotSize
            val iy = startY + row * slotSize
            if (entry.second > 1) {
                val ct = formatCount(entry.second)
                val tw = font.width(ct)
                val cx = ((ix + 16).toFloat() / scale - tw).toInt()
                val cy = ((iy + 16).toFloat() / scale - font.lineHeight).toInt()
                graphics.drawString(font, ct, cx, cy, 0xFFFFFFFF.toInt(), true)
            }
        }
        graphics.pose().popPose()

        graphics.disableScissor()

        // Scrollbar thumb (always visible — grayed out when not scrollable)
        if (maxScroll > 0) {
            val thumbH = maxOf(8, gridH * rows / totalRows)
            val thumbY = startY + (gridH - thumbH) * bufferScrollOffset / maxScroll
            NineSlice.SCROLLBAR_THUMB.draw(graphics, sbX, thumbY, scrollbarW, thumbH)
        } else {
            // Grayed thumb sized as if there's one extra row to scroll
            val fakeThumbH = maxOf(8, gridH * rows / (rows + 1))
            com.mojang.blaze3d.systems.RenderSystem.enableBlend()
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(0.6f, 0.6f, 0.6f, 0.5f)
            NineSlice.SCROLLBAR_THUMB.draw(graphics, sbX, startY, scrollbarW, fakeThumbH)
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            com.mojang.blaze3d.systems.RenderSystem.disableBlend()
        }

        // Empty state
        if (contents.isEmpty()) {
            graphics.drawString(font, "Empty", startX + 4, startY + 4, 0xFF555555.toInt())
        }
    }

    // ========== Overlay Rendering ==========

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Buffer item tooltips
        val contents = menu.clientBufferContents
        if (contents.isNotEmpty()) {
            val contentLeft = leftPos + PADDING
            val contentsTop = topPos + TOP_BAR_H + PADDING + 66 + 12
            val slotSize = 18
            val cols = maxOf(1, (LEFT_PANEL_W - 6) / slotSize)
            for ((i, entry) in contents.withIndex()) {
                val row = i / cols - bufferScrollOffset
                val col = i % cols
                val ix = contentLeft + col * slotSize
                val iy = contentsTop + row * slotSize
                if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) {
                    val id = ResourceLocation.tryParse(entry.first) ?: continue
                    val item = BuiltInRegistries.ITEM.get(id) ?: continue
                    val stack = ItemStack(item, entry.second)
                    graphics.renderTooltip(font, stack, mouseX, mouseY)
                    break
                }
            }
        }
    }

    // ========== Input ==========

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val dividerX = leftPos + PADDING + LEFT_PANEL_W

        if (mouseX < dividerX) {
            bufferScrollOffset -= scrollY.toInt()
            return true
        } else {
            val treeLeft = dividerX + DIVIDER_W + 1
            val treeW = RIGHT_PANEL_W - 1
            val centerX = treeLeft + treeW / 2f
            val treeTop = topPos + TOP_BAR_H + 1
            val treeH = imageHeight - TOP_BAR_H - PADDING - 1
            val centerY = treeTop + treeH / 2f
            return craftGraph.onMouseScrolled(mouseX, mouseY, scrollY, centerX, centerY)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val dividerX = leftPos + PADDING + LEFT_PANEL_W
        if (mouseX >= dividerX && mouseY >= topPos + TOP_BAR_H && mouseY < topPos + imageHeight - PADDING) {
            return craftGraph.onMouseClicked(mouseX, mouseY)
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (craftGraph.dragging) {
            craftGraph.onMouseReleased()
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (craftGraph.dragging) {
            return craftGraph.onMouseDragged(mouseX, mouseY)
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }
}
