package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class DiagnosticScreen(
    menu: DiagnosticMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<DiagnosticMenu>(menu, playerInventory, title) {

    companion object {
        private const val BG = 0xFF2B2B2B.toInt()
        private const val TOP_BAR = 0xFF3C3C3C.toInt()
        private const val TAB_ACTIVE = 0xFF2B2B2B.toInt()
        private const val TAB_INACTIVE = 0xFF222222.toInt()
        private const val TAB_HOVER = 0xFF333333.toInt()
        private const val SEPARATOR = 0xFF555555.toInt()
        private const val CONTENT_BG = 0xFF1E1E1E.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GRAY = 0xFFAAAAAA.toInt()
        private const val DIM = 0xFF666666.toInt()

        private val BLOCK_COLORS = mapOf(
            "node" to 0xFFCCCCCC.toInt(),
            "controller" to 0xFFFFD700.toInt(),
            "terminal" to 0xFF5599FF.toInt(),
            "crafting_core" to 0xFFFF8833.toInt(),
            "crafting_storage" to 0xFFFFAA55.toInt(),
            "instruction_storage" to 0xFF55DDDD.toInt(),
            "processing_storage" to 0xFFDD55DD.toInt(),
            "variable" to 0xFFFFAA33.toInt(),
            "receiver_antenna" to 0xFF55BBAA.toInt(),
            "broadcast_antenna" to 0xFF55BBAA.toInt(),
            "inventory_terminal" to 0xFF77BBFF.toInt()
        )

        private val CARD_COLORS = mapOf(
            "io" to 0xFF83E086.toInt(),
            "storage" to 0xFFAA83E0.toInt(),
            "redstone" to 0xFFF53B68.toInt()
        )

        private val BLOCK_LABELS = mapOf(
            "node" to "Node",
            "controller" to "Controller",
            "terminal" to "Terminal",
            "crafting_core" to "Crafting Core",
            "crafting_storage" to "Crafting Storage",
            "instruction_storage" to "Instruction Storage",
            "processing_storage" to "Processing Storage",
            "variable" to "Variable",
            "receiver_antenna" to "Receiver Antenna",
            "broadcast_antenna" to "Broadcast Antenna",
            "inventory_terminal" to "Inventory Terminal"
        )

        private val TAB_NAMES = listOf("Topology", "Route", "Craft", "Jobs")
    }

    private var activeTab = 0

    // Topology view state
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f
    private var dragging = false
    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private var hoveredBlock: DiagnosticOpenData.NetworkBlock? = null
    private var selectedBlock: DiagnosticOpenData.NetworkBlock? = null

    // Precomputed center of the network (for initial view)
    private var centerX = 0f
    private var centerZ = 0f
    private val gridSize = 20f // pixels per block at zoom 1.0
    private val blockSize = 8 // rendered block square size in pixels

    /** Rotated display position per block (relative to player). */
    private val rotatedPositions = mutableMapOf<BlockPos, Pair<Float, Float>>()

    /** Groups of blocks stacked at the same XZ. Key = group ID, Value = list of blocks sorted by Y. */
    private data class StackGroup(val blocks: List<DiagnosticOpenData.NetworkBlock>, val displayPos: Pair<Float, Float>)
    private val stackGroups = mutableListOf<StackGroup>()
    private val expandedGroups = mutableSetOf<Int>() // indices of expanded groups

    /** Map from BlockPos to its group index (only for stacked blocks). */
    private val blockToGroup = mutableMapOf<BlockPos, Int>()

    init {
        imageWidth = 480
        imageHeight = 300
        inventoryLabelY = -9999
        titleLabelY = -9999

        val player = net.minecraft.client.Minecraft.getInstance().player
        val playerX = player?.x?.toFloat() ?: 0f
        val playerZ = player?.z?.toFloat() ?: 0f
        val yawDeg = player?.yRot ?: 0f
        val yawRad = Math.toRadians((yawDeg + 180).toDouble()).toFloat()
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)

        centerX = 0f
        centerZ = 0f

        val blocks = menu.topology.blocks
        if (blocks.isNotEmpty()) {
            // Compute rotated positions for all blocks
            for (block in blocks) {
                val dx = block.pos.x + 0.5f - playerX
                val dz = block.pos.z + 0.5f - playerZ
                val rx = dx * cosYaw + dz * sinYaw
                val rz = -dx * sinYaw + dz * cosYaw
                rotatedPositions[block.pos] = rx to rz
            }

            // Group blocks that overlap in rotated space
            val byXZ = blocks.groupBy {
                val p = rotatedPositions[it.pos]!!
                (p.first * 10).roundToInt() to (p.second * 10).roundToInt()
            }
            for ((_, group) in byXZ) {
                if (group.size > 1) {
                    val sorted = group.sortedByDescending { it.pos.y }
                    val groupIdx = stackGroups.size
                    stackGroups.add(StackGroup(sorted, rotatedPositions[sorted[0].pos]!!))
                    for (b in sorted) {
                        blockToGroup[b.pos] = groupIdx
                    }
                }
            }
        }
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    // ========== Coordinate conversion ==========

    private val contentLeft get() = leftPos + 4
    private val contentTop get() = topPos + 32
    private val contentW get() = imageWidth - 8
    private val contentH get() = imageHeight - 36
    private val viewCenterX get() = contentLeft + contentW / 2f
    private val viewCenterY get() = contentTop + contentH / 2f

    private fun worldToScreenX(x: Float): Float =
        (x - centerX) * zoom * gridSize + viewCenterX + panX

    private fun worldToScreenY(z: Float): Float =
        (z - centerZ) * zoom * gridSize + viewCenterY + panY

    private fun blockScreenX(pos: BlockPos): Float {
        val dp = rotatedPositions[pos] ?: return viewCenterX
        return worldToScreenX(dp.first)
    }

    private fun blockScreenY(pos: BlockPos): Float {
        val dp = rotatedPositions[pos] ?: return viewCenterY
        return worldToScreenY(dp.second)
    }

    /** Whether a block should be drawn individually (not part of any group). */
    private fun isBlockVisible(pos: BlockPos): Boolean {
        return pos !in blockToGroup
    }

    private fun screenToWorldX(sx: Float): Float =
        (sx - viewCenterX - panX) / (zoom * gridSize) + centerX

    private fun screenToWorldZ(sy: Float): Float =
        (sy - viewCenterY - panY) / (zoom * gridSize) + centerZ

    // ========== Rendering ==========

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Main background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG)

        // Top bar
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 20, TOP_BAR)
        val titleStr = if (menu.topology.networkName.isNotEmpty()) menu.topology.networkName else "Network Diagnostic"
        graphics.drawString(font, titleStr, leftPos + 6, topPos + 6, WHITE)

        // Tab bar
        val tabY = topPos + 20
        val tabH = 12
        graphics.fill(leftPos, tabY, leftPos + imageWidth, tabY + tabH, TAB_INACTIVE)

        var tabX = leftPos + 4
        for ((i, name) in TAB_NAMES.withIndex()) {
            val tabW = font.width(name) + 10
            val hovered = mouseX >= tabX && mouseX < tabX + tabW && mouseY >= tabY && mouseY < tabY + tabH
            val bg = when {
                i == activeTab -> TAB_ACTIVE
                hovered -> TAB_HOVER
                else -> TAB_INACTIVE
            }
            graphics.fill(tabX, tabY, tabX + tabW, tabY + tabH, bg)
            if (i == activeTab) {
                graphics.fill(tabX, tabY, tabX + tabW, tabY + 1, 0xFF5599FF.toInt())
            }
            val textColor = if (i == activeTab) WHITE else GRAY
            graphics.drawString(font, name, tabX + 5, tabY + 2, textColor)
            tabX += tabW + 2
        }
        graphics.fill(leftPos, tabY + tabH, leftPos + imageWidth, tabY + tabH + 1, SEPARATOR)

        // Content area
        graphics.fill(contentLeft, contentTop, contentLeft + contentW, contentTop + contentH, CONTENT_BG)

        when (activeTab) {
            0 -> renderTopology(graphics, mouseX, mouseY)
            else -> {
                val msg = "Coming Soon"
                val msgW = font.width(msg)
                graphics.drawString(font, msg, contentLeft + (contentW - msgW) / 2, contentTop + contentH / 2, DIM)
            }
        }
    }

    private var hoveredGroupIdx = -1

    private fun renderTopology(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val blocks = menu.topology.blocks
        if (blocks.isEmpty()) {
            graphics.drawString(font, "No blocks found", contentLeft + 8, contentTop + 8, DIM)
            return
        }

        graphics.enableScissor(contentLeft, contentTop, contentLeft + contentW, contentTop + contentH)

        val posSet = blocks.map { it.pos }.toSet()

        // Draw connections (use group center for collapsed blocks)
        for (block in blocks) {
            val sx1 = blockScreenX(block.pos)
            val sy1 = blockScreenY(block.pos)
            for (conn in block.connections) {
                if (conn !in posSet) continue
                if (block.pos.asLong() > conn.asLong()) continue
                val sx2 = blockScreenX(conn)
                val sy2 = blockScreenY(conn)
                drawLine(graphics, sx1.roundToInt(), sy1.roundToInt(), sx2.roundToInt(), sy2.roundToInt(), SEPARATOR)
            }
        }

        // Draw individual blocks (skip those in collapsed groups)
        hoveredBlock = null
        hoveredGroupIdx = -1
        val halfBlock = blockSize / 2
        for (block in blocks) {
            if (!isBlockVisible(block.pos)) continue

            val sx = blockScreenX(block.pos).roundToInt()
            val sy = blockScreenY(block.pos).roundToInt()
            renderBlockIcon(graphics, block, sx, sy, halfBlock, mouseX, mouseY)
        }

        // Draw collapsed group icons
        for ((groupIdx, group) in stackGroups.withIndex()) {
            if (groupIdx in expandedGroups) {
                // Draw expanded blocks stacked vertically with a bounding rectangle
                val gsx = worldToScreenX(group.displayPos.first).roundToInt()
                val gsy = worldToScreenY(group.displayPos.second).roundToInt()
                val itemH = blockSize + font.lineHeight + 2 // block + Y label
                val padding = 3
                val totalH = group.blocks.size * itemH + padding * 2
                val totalW = blockSize + padding * 2 + 10 // extra for card dots

                // Bounding rectangle
                val rectX = gsx - totalW / 2
                val rectY = gsy - padding
                graphics.fill(rectX, rectY, rectX + totalW, rectY + totalH, 0xCC1E1E1E.toInt())
                graphics.fill(rectX, rectY, rectX + totalW, rectY + 1, SEPARATOR)
                graphics.fill(rectX, rectY + totalH - 1, rectX + totalW, rectY + totalH, SEPARATOR)
                graphics.fill(rectX, rectY, rectX + 1, rectY + totalH, SEPARATOR)
                graphics.fill(rectX + totalW - 1, rectY, rectX + totalW, rectY + totalH, SEPARATOR)

                for ((i, block) in group.blocks.withIndex()) {
                    val bx = gsx
                    val by = rectY + padding + i * itemH + halfBlock
                    renderBlockIcon(graphics, block, bx, by, halfBlock, mouseX, mouseY)
                }

                // [-] collapse button top-right of rectangle
                val btnX = rectX + totalW + 1
                val btnY = rectY
                val btnHovered = mouseX >= btnX && mouseX < btnX + 7 && mouseY >= btnY && mouseY < btnY + 7
                graphics.fill(btnX, btnY, btnX + 7, btnY + 7, if (btnHovered) 0xFF555555.toInt() else 0xFF333333.toInt())
                graphics.drawString(font, "-", btnX + 1, btnY - 1, WHITE, false)
            } else {
                // Collapsed: draw stacked icon with count
                val sx = worldToScreenX(group.displayPos.first).roundToInt()
                val sy = worldToScreenY(group.displayPos.second).roundToInt()

                // Stacked effect: draw shadow squares behind
                graphics.fill(sx - halfBlock + 2, sy - halfBlock + 2, sx + halfBlock + 2, sy + halfBlock + 2, 0x40FFFFFF.toInt())
                graphics.fill(sx - halfBlock + 1, sy - halfBlock + 1, sx + halfBlock + 1, sy + halfBlock + 1, 0x60FFFFFF.toInt())

                // Main block (use first block's color)
                val mainColor = BLOCK_COLORS[group.blocks[0].type] ?: 0xFFAAAAAA.toInt()
                graphics.fill(sx - halfBlock, sy - halfBlock, sx + halfBlock, sy + halfBlock, mainColor)
                graphics.fill(sx - halfBlock, sy - halfBlock, sx + halfBlock, sy - halfBlock + 1, 0xFF000000.toInt())
                graphics.fill(sx - halfBlock, sy + halfBlock - 1, sx + halfBlock, sy + halfBlock, 0xFF000000.toInt())
                graphics.fill(sx - halfBlock, sy - halfBlock, sx - halfBlock + 1, sy + halfBlock, 0xFF000000.toInt())
                graphics.fill(sx + halfBlock - 1, sy - halfBlock, sx + halfBlock, sy + halfBlock, 0xFF000000.toInt())

                // Count badge
                val countStr = group.blocks.size.toString()
                val countW = font.width(countStr)
                graphics.drawString(font, countStr, sx - countW / 2, sy - 3, WHITE, true)

                // [+] expand button
                val btnX = sx + halfBlock + 2
                val btnY = sy - halfBlock - 1
                val btnHovered = mouseX >= btnX && mouseX < btnX + 7 && mouseY >= btnY && mouseY < btnY + 7
                graphics.fill(btnX, btnY, btnX + 7, btnY + 7, if (btnHovered) 0xFF555555.toInt() else 0xFF333333.toInt())
                graphics.drawString(font, "+", btnX + 1, btnY - 1, WHITE, false)

                // Hover on group
                if (mouseX >= sx - halfBlock && mouseX < sx + halfBlock &&
                    mouseY >= sy - halfBlock && mouseY < sy + halfBlock) {
                    hoveredGroupIdx = groupIdx
                }
                if (btnHovered) hoveredGroupIdx = groupIdx + 1000 // signal for click handler
            }
        }

        graphics.disableScissor()

        // Zoom indicator
        val zoomStr = String.format("%.0f%%", zoom * 100)
        graphics.drawString(font, zoomStr, contentLeft + contentW - font.width(zoomStr) - 4, contentTop + contentH - font.lineHeight - 2, DIM)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Hover tooltip for blocks
        val hovered = hoveredBlock
        if (hovered != null) {
            val label = BLOCK_LABELS[hovered.type] ?: hovered.type
            val posStr = "(${hovered.pos.x}, ${hovered.pos.y}, ${hovered.pos.z})"
            val cardStr = if (hovered.cards.isNotEmpty()) {
                val counts = hovered.cards.groupBy { it.cardType }.map { "${it.value.size}x ${it.key}" }
                counts.joinToString(", ")
            } else null

            val lines = mutableListOf(label, posStr)
            if (cardStr != null) lines.add(cardStr)
            renderTooltipLines(graphics, lines, mouseX, mouseY)
        }

        // Hover tooltip for collapsed groups
        if (hoveredBlock == null && hoveredGroupIdx in stackGroups.indices) {
            val group = stackGroups[hoveredGroupIdx]
            val lines = mutableListOf("${group.blocks.size} stacked blocks")
            for (b in group.blocks) {
                val label = BLOCK_LABELS[b.type] ?: b.type
                lines.add("  Y=${b.pos.y}: $label")
            }
            lines.add("Click [+] to expand")
            renderTooltipLines(graphics, lines, mouseX, mouseY)
        }

        // Inspector panel for selected block
        renderInspector(graphics, mouseX, mouseY)
    }

    private val inspectorWidth = 140
    private val lineH get() = font.lineHeight + 1

    private fun getInspectorBounds(): IntArray? {
        val sel = selectedBlock ?: return null
        val lines = buildInspectorLines(sel)
        val panelH = (lines.size + 1) * lineH + 8 // +1 for title row
        val px = leftPos + imageWidth - inspectorWidth - 6
        val py = contentTop + 4
        return intArrayOf(px, py, inspectorWidth, panelH)
    }

    private fun buildInspectorLines(block: DiagnosticOpenData.NetworkBlock): List<Pair<String, Int>> {
        val lines = mutableListOf<Pair<String, Int>>()
        lines.add("(${block.pos.x}, ${block.pos.y}, ${block.pos.z})" to GRAY)
        lines.add("Connections: ${block.connections.size}" to GRAY)

        if (block.cards.isNotEmpty()) {
            lines.add("" to 0) // spacer
            lines.add("Cards:" to GRAY)
            val dirNames = arrayOf("Down", "Up", "North", "South", "East", "West")
            for (card in block.cards) {
                val dir = dirNames.getOrElse(card.side) { "?" }
                val alias = if (card.alias.isNotEmpty()) " \"${card.alias}\"" else ""
                val color = CARD_COLORS[card.cardType] ?: GRAY
                lines.add("  $dir: ${card.cardType}$alias" to color)
            }
        }

        if (block.details.isNotEmpty()) {
            lines.add("" to 0) // spacer
            for (detail in block.details) {
                lines.add(detail to GRAY)
            }
        }

        return lines
    }

    private fun renderInspector(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val sel = selectedBlock ?: return
        val bounds = getInspectorBounds() ?: return
        val px = bounds[0]; val py = bounds[1]; val pw = bounds[2]; val ph = bounds[3]

        // Background
        graphics.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, SEPARATOR)
        graphics.fill(px, py, px + pw, py + ph, 0xEE1E1E1E.toInt())

        // Title bar
        val title = BLOCK_LABELS[sel.type] ?: sel.type
        val titleColor = BLOCK_COLORS[sel.type] ?: WHITE
        graphics.drawString(font, title, px + 4, py + 3, titleColor)

        // [X] close button
        val closeX = px + pw - 10
        val closeY = py + 2
        val closeHovered = mouseX >= closeX && mouseX < closeX + 8 && mouseY >= closeY && mouseY < closeY + 8
        graphics.drawString(font, "x", closeX + 1, closeY, if (closeHovered) WHITE else GRAY, false)

        // Detail lines
        val lines = buildInspectorLines(sel)
        for ((i, pair) in lines.withIndex()) {
            val (text, color) = pair
            if (text.isEmpty()) continue
            graphics.drawString(font, text, px + 4, py + 3 + (i + 1) * lineH, color, false)
        }
    }

    private fun renderTooltipLines(graphics: GuiGraphics, lines: List<String>, mouseX: Int, mouseY: Int) {
        val tooltipW = lines.maxOf { font.width(it) } + 6
        val tooltipH = lines.size * (font.lineHeight + 1) + 4
        val tx = mouseX + 10
        val ty = mouseY - tooltipH - 2

        graphics.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, SEPARATOR)
        graphics.fill(tx, ty, tx + tooltipW, ty + tooltipH, 0xFF1A1A1A.toInt())
        for ((i, line) in lines.withIndex()) {
            val c = if (i == 0) WHITE else GRAY
            graphics.drawString(font, line, tx + 3, ty + 2 + i * (font.lineHeight + 1), c)
        }
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default labels
    }

    // ========== Line drawing ==========

    private fun renderBlockIcon(
        graphics: GuiGraphics, block: DiagnosticOpenData.NetworkBlock,
        sx: Int, sy: Int, halfBlock: Int, mouseX: Int, mouseY: Int
    ) {
        val color = BLOCK_COLORS[block.type] ?: 0xFFAAAAAA.toInt()

        graphics.fill(sx - halfBlock, sy - halfBlock, sx + halfBlock, sy + halfBlock, color)
        graphics.fill(sx - halfBlock, sy - halfBlock, sx + halfBlock, sy - halfBlock + 1, 0xFF000000.toInt())
        graphics.fill(sx - halfBlock, sy + halfBlock - 1, sx + halfBlock, sy + halfBlock, 0xFF000000.toInt())
        graphics.fill(sx - halfBlock, sy - halfBlock, sx - halfBlock + 1, sy + halfBlock, 0xFF000000.toInt())
        graphics.fill(sx + halfBlock - 1, sy - halfBlock, sx + halfBlock, sy + halfBlock, 0xFF000000.toInt())

        if (block.cards.isNotEmpty()) {
            val dotSize = 2
            val uniqueTypes = block.cards.map { it.cardType }.distinct()
            for ((i, cardType) in uniqueTypes.withIndex()) {
                val dotColor = CARD_COLORS[cardType] ?: continue
                val dotX = sx + halfBlock + 2
                val dotY = sy - halfBlock + i * (dotSize + 1)
                graphics.fill(dotX, dotY, dotX + dotSize, dotY + dotSize, dotColor)
            }
        }

        if (mouseX >= sx - halfBlock && mouseX < sx + halfBlock &&
            mouseY >= sy - halfBlock && mouseY < sy + halfBlock) {
            hoveredBlock = block
        }
    }

    private fun drawLine(graphics: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val steps = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        if (steps == 0) return
        val xInc = dx.toFloat() / steps
        val yInc = dy.toFloat() / steps
        var x = x1.toFloat()
        var y = y1.toFloat()
        for (i in 0..steps) {
            graphics.fill(x.roundToInt(), y.roundToInt(), x.roundToInt() + 1, y.roundToInt() + 1, color)
            x += xInc
            y += yInc
        }
    }

    // ========== Input ==========

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Tab clicks
        val tabY = topPos + 20
        val tabH = 12
        if (my >= tabY && my < tabY + tabH) {
            var tabX = leftPos + 4
            for ((i, name) in TAB_NAMES.withIndex()) {
                val tabW = font.width(name) + 10
                if (mx >= tabX && mx < tabX + tabW) {
                    activeTab = i
                    return true
                }
                tabX += tabW + 2
            }
        }

        // Group expand/collapse buttons
        if (activeTab == 0) {
            val halfBlock = blockSize / 2
            for ((groupIdx, group) in stackGroups.withIndex()) {
                val gsx = worldToScreenX(group.displayPos.first).roundToInt()
                val gsy = worldToScreenY(group.displayPos.second).roundToInt()

                val btnX: Int; val btnY: Int
                if (groupIdx in expandedGroups) {
                    // [-] button position matches expanded rendering
                    val padding = 3
                    val itemH = blockSize + font.lineHeight + 2
                    val totalH = group.blocks.size * itemH + padding * 2
                    val totalW = blockSize + padding * 2 + 10
                    val rectX = gsx - totalW / 2
                    val rectY = gsy - padding
                    btnX = rectX + totalW + 1
                    btnY = rectY
                } else {
                    // [+] button position matches collapsed rendering
                    btnX = gsx + halfBlock + 2
                    btnY = gsy - halfBlock - 1
                }

                if (mx >= btnX && mx < btnX + 7 && my >= btnY && my < btnY + 7) {
                    if (groupIdx in expandedGroups) expandedGroups.remove(groupIdx)
                    else expandedGroups.add(groupIdx)
                    return true
                }
            }
        }

        // Inspector panel interactions
        if (activeTab == 0 && selectedBlock != null) {
            val bounds = getInspectorBounds()
            if (bounds != null) {
                val px = bounds[0]; val py = bounds[1]; val pw = bounds[2]; val ph = bounds[3]
                // [X] close button
                val closeX = px + pw - 10
                val closeY = py + 2
                if (mx >= closeX && mx < closeX + 8 && my >= closeY && my < closeY + 8) {
                    selectedBlock = null
                    return true
                }
                // Click inside inspector panel — consume but don't deselect
                if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
                    return true
                }
            }
        }

        // Click on a block to select it for inspection
        if (activeTab == 0 && hoveredBlock != null) {
            selectedBlock = hoveredBlock
            return true
        }

        // Click in content area but not on a block — deselect and start drag
        if (activeTab == 0 && mx >= contentLeft && mx < contentLeft + contentW &&
            my >= contentTop && my < contentTop + contentH) {
            selectedBlock = null
            dragging = true
            lastDragX = mouseX
            lastDragY = mouseY
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragging = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (dragging) {
            panX += (mouseX - lastDragX).toFloat()
            panY += (mouseY - lastDragY).toFloat()
            lastDragX = mouseX
            lastDragY = mouseY
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (activeTab == 0 && mouseX >= contentLeft && mouseX < contentLeft + contentW &&
            mouseY >= contentTop && mouseY < contentTop + contentH) {
            val oldZoom = zoom
            zoom = (zoom * (1f + scrollY.toFloat() * 0.15f)).coerceIn(0.3f, 8f)

            // Zoom toward mouse position
            val mx = (mouseX - viewCenterX - panX).toFloat()
            val my = (mouseY - viewCenterY - panY).toFloat()
            panX -= mx * (zoom / oldZoom - 1f)
            panY -= my * (zoom / oldZoom - 1f)

            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
}
