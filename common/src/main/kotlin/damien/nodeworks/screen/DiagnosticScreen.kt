package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
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

        private val BLOCK_ITEMS: Map<String, ItemStack> by lazy {
            val reg = damien.nodeworks.registry.ModBlocks
            mapOf(
                "node" to ItemStack(reg.NODE),
                "controller" to ItemStack(reg.NETWORK_CONTROLLER),
                "terminal" to ItemStack(reg.TERMINAL),
                "crafting_core" to ItemStack(reg.CRAFTING_CORE),
                "crafting_storage" to ItemStack(reg.CRAFTING_STORAGE),
                "instruction_storage" to ItemStack(reg.INSTRUCTION_STORAGE),
                "processing_storage" to ItemStack(reg.PROCESSING_STORAGE),
                "variable" to ItemStack(reg.VARIABLE),
                "receiver_antenna" to ItemStack(reg.RECEIVER_ANTENNA),
                "broadcast_antenna" to ItemStack(reg.BROADCAST_ANTENNA),
                "inventory_terminal" to ItemStack(reg.INVENTORY_TERMINAL)
            )
        }
    }

    private var activeTab = 0

    // Topology view state
    private var panX = 0f
    private var panY = 0f
    private var zoom = 2f
    private var dragging = false
    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private var hoveredBlock: DiagnosticOpenData.NetworkBlock? = null
    private var selectedBlock: DiagnosticOpenData.NetworkBlock? = null

    // Craft preview state
    private var craftItemField: net.minecraft.client.gui.components.EditBox? = null
    private var craftFieldLastClickTime = 0L
    private var craftTreeScrollY = 0
    private var craftGraphPanX = 0f
    private var craftGraphPanY = 0f
    private var craftGraphZoom = 1f
    private var craftGraphDragging = false
    private var craftGraphLastDragX = 0.0
    private var craftGraphLastDragY = 0.0

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
        // Scale to fill most of the screen with some margin
        imageWidth = (width * 0.9f).toInt().coerceIn(320, width - 20)
        imageHeight = (height * 0.85f).toInt().coerceIn(200, height - 20)
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Craft preview search field
        craftItemField = net.minecraft.client.gui.components.EditBox(font, contentLeft + 4, contentTop + 4, 200, 14, net.minecraft.network.chat.Component.literal("Search recipes...")).also {
            it.setMaxLength(128)
            it.setBordered(true)
            it.visible = activeTab == 2
            it.setResponder { value -> updateCraftAutocomplete(value) }
            addRenderableWidget(it)
        }
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
            2 -> renderCraftPreview(graphics, mouseX, mouseY)
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

        val networkLineColor = menu.topology.networkColor or 0xFF000000.toInt()

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
                drawLine(graphics, sx1.roundToInt(), sy1.roundToInt(), sx2.roundToInt(), sy2.roundToInt(), networkLineColor)
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
                val iconSize = 16
                val itemH = iconSize + 2 // icon + spacing
                val padding = 4
                val totalH = group.blocks.size * itemH + padding * 2
                val totalW = iconSize + padding * 2 + 6 // extra for card dots

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
                    val by = rectY + padding + i * itemH + iconSize / 2
                    renderBlockIcon(graphics, block, bx, by, halfBlock, mouseX, mouseY)
                }

                // [-] button inside the rectangle (top-right corner)
                val btnX = rectX + totalW - 9
                val btnY = rectY + 1
                val btnHovered = mouseX >= btnX && mouseX < btnX + 8 && mouseY >= btnY && mouseY < btnY + 8
                graphics.fill(btnX, btnY, btnX + 8, btnY + 8, if (btnHovered) 0xFF555555.toInt() else 0xFF333333.toInt())
                graphics.drawString(font, "-", btnX + 2, btnY, WHITE, false)
                if (btnHovered) hoveredGroupIdx = groupIdx
            } else {
                // Collapsed: render stacked item icons with slight offset + count badge
                val sx = worldToScreenX(group.displayPos.first).roundToInt()
                val sy = worldToScreenY(group.displayPos.second).roundToInt()
                val stackOffset = 3 // pixel offset per stacked icon

                // Draw bottom items first (lowest Y = last in list), top item last (highest Y = index 0)
                // Each layer gets a higher z-level so it fully covers the one below
                for (i in group.blocks.lastIndex downTo 0) {
                    val off = i * stackOffset
                    val itemStack = BLOCK_ITEMS[group.blocks[i].type]
                    if (itemStack != null) {
                        val zLayer = (group.blocks.lastIndex - i) * 50f
                        graphics.pose().pushPose()
                        graphics.pose().translate(0f, 0f, zLayer)
                        graphics.renderItem(itemStack, sx - 8 + off, sy - 8 + off)
                        graphics.pose().popPose()
                    }
                }

                // Count badge on top of everything
                val topZ = group.blocks.size * 50f + 100f
                val totalOffset = (group.blocks.size - 1) * stackOffset
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, topZ)

                if (group.blocks.size > 1) {
                    val countStr = "+${group.blocks.size}"
                    graphics.drawString(font, countStr, sx + 2 + totalOffset, sy - 10, WHITE, true)
                }

                graphics.pose().popPose()

                // Hover on stacked group — clicking expands
                if (mouseX >= sx - 8 && mouseX < sx + 8 + totalOffset &&
                    mouseY >= sy - 8 && mouseY < sy + 8 + totalOffset) {
                    hoveredGroupIdx = groupIdx
                }
            }
        }

        graphics.disableScissor()

        // Zoom indicator
        val zoomStr = String.format("%.0f%%", zoom * 100)
        graphics.drawString(font, zoomStr, contentLeft + contentW - font.width(zoomStr) - 4, contentTop + contentH - font.lineHeight - 2, DIM)
    }

    // ========== Craft Preview Tab ==========

    private val craftSplitRatio = 3f / 5f

    private fun renderCraftPreview(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val tree = menu.craftTree
        val treeAreaTop = contentTop + 22
        val splitX = contentLeft + (contentW * craftSplitRatio).toInt()

        // Always draw panel backgrounds and separator
        graphics.fill(contentLeft, treeAreaTop, splitX, contentTop + contentH, 0xFF191919.toInt()) // darker left
        graphics.fill(splitX + 1, treeAreaTop, contentLeft + contentW, contentTop + contentH, CONTENT_BG) // right
        graphics.fill(splitX, contentTop, splitX + 1, contentTop + contentH, SEPARATOR) // separator

        // Autocomplete dropdown for item field
        renderCraftAutocomplete(graphics, mouseX, mouseY)

        if (tree == null) {
            graphics.drawString(font, "Enter an item ID above to preview crafting", contentLeft + 8, treeAreaTop + 8, DIM)
            return
        }

        // Left panel: detail text tree
        graphics.enableScissor(contentLeft, treeAreaTop, splitX, contentTop + contentH)
        renderCraftTreeText(graphics, tree, contentLeft + 4, treeAreaTop + 2 - craftTreeScrollY, 0)
        graphics.disableScissor()

        // Right panel: visual item graph with zoom/pan
        val graphLeft = splitX + 2
        val graphRight = contentLeft + contentW
        val graphW = graphRight - graphLeft
        graphics.enableScissor(graphLeft, treeAreaTop, graphRight, contentTop + contentH)
        graphics.pose().pushPose()
        val graphCenterX = graphLeft + graphW / 2f + craftGraphPanX
        val graphCenterY = treeAreaTop + 20f + craftGraphPanY
        // Precompute layout then render
        val layout = layoutCraftTree(tree)
        renderCraftTreeVisual(graphics, tree, layout, graphCenterX, graphCenterY, craftGraphZoom)
        graphics.pose().popPose()
        graphics.disableScissor()
    }

    // ========== Craft Item Autocomplete ==========

    private var craftAutocompleteSuggestions: List<String> = emptyList()
    private var craftAutocompleteSelected = 0
    private var craftAutocompleteScroll = 0
    private val craftDropdownMaxVisible = 10

    private fun updateCraftAutocomplete(query: String) {
        val all = menu.topology.craftableItems
        craftAutocompleteSuggestions = if (query.isEmpty()) {
            all // show all when empty
        } else {
            all.filter { it.contains(query, ignoreCase = true) }
        }
        craftAutocompleteSelected = 0
        craftAutocompleteScroll = 0
    }

    private fun renderCraftAutocomplete(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (craftAutocompleteSuggestions.isEmpty()) return
        val field = craftItemField ?: return
        if (!field.isFocused) return

        val dropX = field.x
        val dropY = field.y + field.height + 1
        val dropW = field.width
        val itemH = font.lineHeight + 4
        val visibleCount = minOf(craftAutocompleteSuggestions.size, craftDropdownMaxVisible)
        val dropH = visibleCount * itemH + 2

        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 300f)
        graphics.fill(dropX, dropY, dropX + dropW, dropY + dropH, 0xFF111111.toInt())
        graphics.fill(dropX, dropY, dropX + dropW, dropY + 1, SEPARATOR)
        graphics.fill(dropX, dropY + dropH - 1, dropX + dropW, dropY + dropH, SEPARATOR)

        for (i in 0 until visibleCount) {
            val idx = craftAutocompleteScroll + i
            if (idx >= craftAutocompleteSuggestions.size) break
            val suggestion = craftAutocompleteSuggestions[idx]
            val sy = dropY + 1 + i * itemH

            val rowBg = when {
                idx == craftAutocompleteSelected -> 0xFF3A5FCD.toInt()
                i % 2 == 1 -> 0x08FFFFFF.toInt()
                else -> 0
            }
            if (rowBg != 0) graphics.fill(dropX + 1, sy, dropX + dropW - 1, sy + itemH, rowBg)

            // Item icon
            val id = net.minecraft.resources.ResourceLocation.tryParse(suggestion)
            if (id != null) {
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)
                if (item != null) {
                    graphics.pose().pushPose()
                    graphics.pose().translate((dropX + 2).toFloat(), (sy + 1).toFloat(), 0f)
                    graphics.pose().scale(0.5f, 0.5f, 1f)
                    graphics.renderItem(ItemStack(item), 0, 0)
                    graphics.pose().popPose()
                }
            }

            // Item name (short) + ID
            val shortName = suggestion.substringAfter(':').replace('_', ' ')
            val color = if (idx == craftAutocompleteSelected) WHITE else GRAY
            graphics.drawString(font, shortName, dropX + 14, sy + 2, color, false)
        }

        // Scroll indicator
        if (craftAutocompleteSuggestions.size > craftDropdownMaxVisible) {
            val countStr = "${craftAutocompleteScroll + 1}-${minOf(craftAutocompleteScroll + visibleCount, craftAutocompleteSuggestions.size)} of ${craftAutocompleteSuggestions.size}"
            graphics.drawString(font, countStr, dropX + dropW - font.width(countStr) - 4, dropY + dropH - itemH + 2, DIM, false)
        }

        graphics.pose().popPose()
    }

    private var craftTreeTextY = 0 // tracks current Y for text rendering

    private fun renderCraftTreeText(graphics: GuiGraphics, node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode, x: Int, startY: Int, depth: Int) {
        val lineH = font.lineHeight + 2
        val indent = depth * 12
        var y = if (depth == 0) startY else craftTreeTextY

        // Source label and color
        val sourceLabel = when (node.source) {
            "craft_template" -> "Craft Template"
            "process_template" -> "Process Template"
            "process_no_handler" -> "No Handler!"
            "storage" -> "In Storage"
            "missing" -> "Missing!"
            else -> node.source
        }
        val sourceColor = when (node.source) {
            "storage" -> 0xFF55AA55.toInt()
            "craft_template" -> 0xFF5599FF.toInt()
            "process_template" -> 0xFFAA55DD.toInt()
            "process_no_handler" -> 0xFFFF5555.toInt()
            "missing" -> 0xFFFF5555.toInt()
            else -> GRAY
        }

        // Row background
        val rowBg = if ((depth % 2) == 0) 0x00000000 else 0x08FFFFFF.toInt()
        graphics.fill(contentLeft, y, contentLeft + contentW * 3 / 5, y + lineH, rowBg)

        // Item icon (small)
        val itemId = net.minecraft.resources.ResourceLocation.tryParse(node.itemId)
        if (itemId != null) {
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId)
            if (item != null) {
                graphics.pose().pushPose()
                graphics.pose().translate((x + indent).toFloat(), (y - 1).toFloat(), 0f)
                graphics.pose().scale(0.5f, 0.5f, 1f)
                graphics.renderItem(ItemStack(item), 0, 0)
                graphics.pose().popPose()
            }
        }

        // Text: count x name — source
        val text = "${node.count}x ${node.itemName}"
        graphics.drawString(font, text, x + indent + 10, y, WHITE, false)
        graphics.drawString(font, " — $sourceLabel", x + indent + 10 + font.width(text), y, sourceColor, false)

        // Sub-info
        y += lineH
        if (node.templateName.isNotEmpty()) {
            graphics.drawString(font, "Template: ${node.templateName}", x + indent + 14, y, DIM, false)
            y += lineH
        }
        if (node.resolvedBy.isNotEmpty() && node.resolvedBy != "storage") {
            graphics.drawString(font, "Handler: ${node.resolvedBy}", x + indent + 14, y, DIM, false)
            y += lineH
        }
        if (node.inStorage > 0 && node.source != "storage") {
            graphics.drawString(font, "In storage: ${node.inStorage}", x + indent + 14, y, 0xFF557755.toInt(), false)
            y += lineH
        }

        craftTreeTextY = y
        for (child in node.children) {
            renderCraftTreeText(graphics, child, x, y, depth + 1)
            y = craftTreeTextY
        }
        craftTreeTextY = y
    }

    /** Layout data: x,y position for each node in the tree (unscaled, relative to root at 0,0). */
    private data class TreeLayout(val positions: Map<damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode, Pair<Float, Float>>)

    private val nodeSpacingX = 28f  // horizontal distance between siblings
    private val nodeSpacingY = 30f  // vertical distance between parent and child (depth)

    /** Compute layout positions for all nodes. Root at (0,0), children spread horizontally below. */
    private fun layoutCraftTree(root: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode): TreeLayout {
        val positions = mutableMapOf<damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode, Pair<Float, Float>>()
        layoutNode(root, 0f, 0f, positions)
        return TreeLayout(positions)
    }

    /** Returns the total width occupied by this subtree. */
    private fun layoutNode(
        node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode,
        xStart: Float, y: Float,
        positions: MutableMap<damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode, Pair<Float, Float>>
    ): Float {
        if (node.children.isEmpty()) {
            positions[node] = xStart to y
            return nodeSpacingX
        }

        // Layout children first to know total width
        var childX = xStart
        val childWidths = mutableListOf<Float>()
        for (child in node.children) {
            val w = layoutNode(child, childX, y + nodeSpacingY, positions)
            childWidths.add(w)
            childX += w
        }

        // Center this node horizontally among its children
        val totalChildW = childWidths.sum()
        val centerX = xStart + totalChildW / 2f - nodeSpacingX / 2f
        positions[node] = centerX to y

        return totalChildW
    }

    private fun renderCraftTreeVisual(
        graphics: GuiGraphics, node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode,
        layout: TreeLayout, originX: Float, originY: Float, zoom: Float
    ) {
        val lineColor = 0xFF444444.toInt()

        for ((n, pos) in layout.positions) {
            val sx = (originX + pos.first * zoom).roundToInt()
            val sy = (originY + pos.second * zoom).roundToInt()

            // Draw lines to children (top-to-bottom tree)
            for (child in n.children) {
                val childPos = layout.positions[child] ?: continue
                val cx = (originX + childPos.first * zoom).roundToInt()
                val cy = (originY + childPos.second * zoom).roundToInt()
                // L-shaped connector: vertical down from parent, then horizontal to child
                val midY = (sy + 16 + cy) / 2
                graphics.fill(sx, sy + 16, sx + 1, midY, lineColor)     // vertical from parent
                graphics.fill(minOf(sx, cx), midY, maxOf(sx, cx) + 1, midY + 1, lineColor) // horizontal
                graphics.fill(cx, midY, cx + 1, cy, lineColor)          // vertical to child
            }

            // Render item icon
            val itemId = net.minecraft.resources.ResourceLocation.tryParse(n.itemId)
            if (itemId != null) {
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId)
                if (item != null) {
                    graphics.renderItem(ItemStack(item), sx - 8, sy)
                    if (n.count > 1) {
                        graphics.drawString(font, "x${n.count}", sx + 9, sy + 9, WHITE, true)
                    }
                }
            }

            // Source color dot below icon
            val dotColor = when (n.source) {
                "storage" -> 0xFF55AA55.toInt()
                "craft_template" -> 0xFF5599FF.toInt()
                "process_template" -> 0xFFAA55DD.toInt()
                "missing", "process_no_handler" -> 0xFFFF5555.toInt()
                else -> GRAY
            }
            graphics.fill(sx - 2, sy + 17, sx + 2, sy + 19, dotColor)
        }
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

    private val inspectorWidth = 150
    private val lineH get() = font.lineHeight + 1

    /** Build sections for the inspector panel. Each section has a title and list of lines. */
    /** iconU >= 0 = draw card icon from atlas. blockItemId = render block's item icon. indent = tree depth. */
    private data class InspectorLine(val text: String, val color: Int, val iconU: Int = -1, val blockItemId: String = "", val indent: Int = 0)
    private data class InspectorSection(val title: String?, val lines: List<InspectorLine>)

    private val CARD_ICON_U = mapOf("io" to 0, "storage" to 16, "redstone" to 32)

    private fun buildInspectorSections(block: DiagnosticOpenData.NetworkBlock): List<InspectorSection> {
        val sections = mutableListOf<InspectorSection>()

        // Info section
        sections.add(InspectorSection("Info", listOf(
            InspectorLine("Position: ${block.pos.x}, ${block.pos.y}, ${block.pos.z}", GRAY),
            InspectorLine("Connections: ${block.connections.size}", GRAY)
        )))

        // Cards section grouped by face (nodes only)
        if (block.cards.isNotEmpty()) {
            val dirNames = arrayOf("Down", "Up", "North", "South", "East", "West")
            val bySide = block.cards.groupBy { it.side }
            val cardLines = mutableListOf<InspectorLine>()
            for ((side, sideCards) in bySide) {
                val dir = dirNames.getOrElse(side) { "?" }
                val adjBlockId = sideCards.firstOrNull()?.adjacentBlockId ?: ""
                val adjName = if (adjBlockId.isNotEmpty()) adjBlockId.substringAfter(':').replace('_', ' ') else "air"
                // Face header: indent=0, has block icon
                cardLines.add(InspectorLine("$dir: $adjName", WHITE, -1, adjBlockId, indent = 0))
                // Card entries: indent=1, has card icon
                for (card in sideCards) {
                    val alias = if (card.alias.isNotEmpty()) card.alias else card.cardType
                    val color = CARD_COLORS[card.cardType] ?: GRAY
                    val iconU = CARD_ICON_U[card.cardType] ?: -1
                    cardLines.add(InspectorLine(alias, color, iconU, indent = 1))
                }
            }
            sections.add(InspectorSection("Cards", cardLines))
        }

        // Details section (block-specific)
        if (block.details.isNotEmpty()) {
            val detailLines = block.details.mapNotNull { detail ->
                if (detail.startsWith("__glow:")) {
                    // Special: glow style icon — parsed during rendering
                    InspectorLine(detail, GRAY)
                } else {
                    InspectorLine(detail, GRAY)
                }
            }
            sections.add(InspectorSection("Details", detailLines))
        }

        return sections
    }

    private fun getInspectorBounds(): IntArray? {
        val sel = selectedBlock ?: return null
        val sections = buildInspectorSections(sel)
        val headerH = 20 // icon + title + separator
        var bodyH = 0
        for (section in sections) {
            bodyH += lineH + 2 // section header + gap below header
            bodyH += section.lines.size * lineH
            bodyH += 3 // spacing after section
        }
        val panelH = headerH + bodyH + 4
        val px = leftPos + imageWidth - inspectorWidth - 6
        val py = contentTop + 4
        return intArrayOf(px, py, inspectorWidth, panelH)
    }

    private fun renderInspector(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val sel = selectedBlock ?: return
        val bounds = getInspectorBounds() ?: return
        val px = bounds[0]; val py = bounds[1]; val pw = bounds[2]; val ph = bounds[3]

        // Border
        graphics.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, SEPARATOR)
        // Body background
        graphics.fill(px, py, px + pw, py + ph, 0xEE1E1E1E.toInt())
        // Header background (darker)
        graphics.fill(px, py, px + pw, py + 19, 0xEE151515.toInt())

        // Header: icon + title
        val title = BLOCK_LABELS[sel.type] ?: sel.type
        val titleColor = BLOCK_COLORS[sel.type] ?: WHITE
        val itemStack = BLOCK_ITEMS[sel.type]
        if (itemStack != null) {
            graphics.renderItem(itemStack, px + 2, py + 1)
        }
        graphics.drawString(font, title, px + 20, py + 5, titleColor)

        // [X] close button
        val closeX = px + pw - 10
        val closeY = py + 2
        val closeHovered = mouseX >= closeX && mouseX < closeX + 8 && mouseY >= closeY && mouseY < closeY + 8
        graphics.drawString(font, "x", closeX + 1, closeY, if (closeHovered) WHITE else GRAY, false)

        // Header separator
        val sepY = py + 18
        graphics.fill(px, sepY, px + pw, sepY + 1, SEPARATOR)

        // Sections
        val sections = buildInspectorSections(sel)
        val iconsTexture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")
        var curY = sepY + 3
        var rowIndex = 0
        for ((sectionIdx, section) in sections.withIndex()) {
            // Section header
            if (section.title != null) {
                graphics.fill(px + 1, curY - 1, px + pw - 1, curY + lineH - 1, 0x20FFFFFF.toInt())
                graphics.drawString(font, section.title, px + 4, curY, WHITE, false)
                curY += lineH + 2
                rowIndex = 0
            }
            // Section lines with tree structure
            val indentStep = 10
            val treeLineColor = 0xFF444444.toInt()
            for ((lineIdx, line) in section.lines.withIndex()) {
                val rowBg = if (rowIndex % 2 == 1) 0x08FFFFFF.toInt() else 0x00000000
                graphics.fill(px + 1, curY, px + pw - 1, curY + lineH, rowBg)

                val baseX = px + 8 + line.indent * indentStep
                var textX = baseX

                // Draw tree connector lines
                if (line.indent > 0) {
                    val treeX = px + 8 + (line.indent - 1) * indentStep + 2
                    // Vertical line from above
                    graphics.fill(treeX, curY - 1, treeX + 1, curY + lineH / 2, treeLineColor)
                    // Horizontal branch
                    graphics.fill(treeX, curY + lineH / 2 - 1, treeX + 5, curY + lineH / 2, treeLineColor)
                    textX = treeX + 6
                }

                // Adjacent block item icon (face headers)
                if (line.blockItemId.isNotEmpty()) {
                    val adjId = net.minecraft.resources.ResourceLocation.tryParse(line.blockItemId)
                    if (adjId != null) {
                        val adjItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(adjId)
                        if (adjItem != null) {
                            graphics.pose().pushPose()
                            graphics.pose().translate(textX.toFloat(), curY.toFloat(), 0f)
                            graphics.pose().scale(0.5f, 0.5f, 1f)
                            graphics.renderItem(ItemStack(adjItem), 0, 0)
                            graphics.pose().popPose()
                            textX += 10
                        }
                    }
                }

                // Card type icon from atlas
                if (line.iconU >= 0) {
                    graphics.blit(iconsTexture, textX, curY, 8, 8,
                        (line.iconU + 4).toFloat(), 20f, 8, 8, 256, 256)
                    textX += 12
                }

                // Special rendering for color swatch
                if (line.text.startsWith("__color:")) {
                    val colorVal = line.text.removePrefix("__color:").toIntOrNull() ?: 0
                    val swatchColor = colorVal or 0xFF000000.toInt()
                    graphics.drawString(font, "Color:", textX, curY, WHITE, false)
                    val swatchX = textX + font.width("Color: ")
                    val swatchSize = font.lineHeight - 2
                    // Swatch with border
                    graphics.fill(swatchX - 1, curY - 1, swatchX + swatchSize + 1, curY + swatchSize + 1, 0xFF444444.toInt())
                    graphics.fill(swatchX, curY, swatchX + swatchSize, curY + swatchSize, swatchColor)
                    // Hex label after swatch
                    val hexStr = "#${Integer.toHexString(colorVal).uppercase().padStart(6, '0')}"
                    graphics.drawString(font, hexStr, swatchX + swatchSize + 3, curY, 0xFF888888.toInt(), false)
                } else if (line.text.startsWith("__glow:")) {
                    val parts = line.text.removePrefix("__glow:").split(":")
                    val glowStyle = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val glowColor = (parts.getOrNull(1)?.toIntOrNull() ?: 0x83E086) or 0xFF000000.toInt()
                    graphics.drawString(font, "Node Glow:", textX, curY, WHITE, false)
                    val iconX = textX + font.width("Node Glow: ")
                    val iconCx = iconX + 4
                    val iconCy = curY + font.lineHeight / 2
                    // Draw glow icon (same as NetworkControllerScreen)
                    when (glowStyle) {
                        0 -> graphics.fill(iconCx - 3, iconCy - 3, iconCx + 3, iconCy + 3, glowColor)
                        1 -> { graphics.fill(iconCx - 2, iconCy - 3, iconCx + 2, iconCy + 3, glowColor); graphics.fill(iconCx - 3, iconCy - 2, iconCx + 3, iconCy + 2, glowColor) }
                        2 -> graphics.fill(iconCx - 1, iconCy - 1, iconCx + 1, iconCy + 1, glowColor)
                        3 -> { graphics.fill(iconCx - 3, iconCy - 3, iconCx - 1, iconCy - 1, glowColor); graphics.fill(iconCx + 1, iconCy - 3, iconCx + 3, iconCy - 1, glowColor); graphics.fill(iconCx - 1, iconCy - 1, iconCx + 1, iconCy + 1, glowColor); graphics.fill(iconCx - 2, iconCy + 1, iconCx + 2, iconCy + 3, glowColor) }
                        4 -> { graphics.fill(iconCx - 3, iconCy - 4, iconCx - 2, iconCy - 2, glowColor); graphics.fill(iconCx + 2, iconCy - 4, iconCx + 3, iconCy - 2, glowColor); graphics.fill(iconCx - 2, iconCy - 2, iconCx + 2, iconCy + 2, glowColor) }
                        5 -> { for (j in -3..3) { graphics.fill(iconCx + j, iconCy + j, iconCx + j + 1, iconCy + j + 1, 0xFF666666.toInt()); graphics.fill(iconCx + j, iconCy - j, iconCx + j + 1, iconCy - j + 1, 0xFF666666.toInt()) } }
                    }
                } else {
                    // Render text — split "key: value" for color differentiation
                    val colonIdx = line.text.indexOf(':')
                    if (colonIdx > 0 && line.indent == 0) {
                        val key = line.text.substring(0, colonIdx + 1)
                        val value = line.text.substring(colonIdx + 1)
                        graphics.drawString(font, key, textX, curY, WHITE, false)
                        graphics.drawString(font, value, textX + font.width(key), curY, 0xFF888888.toInt(), false)
                    } else {
                        graphics.drawString(font, line.text, textX, curY, line.color, false)
                    }
                }

                // Draw vertical tree line connecting to next sibling at same indent
                if (line.indent == 0 && lineIdx + 1 < section.lines.size) {
                    val nextIndent = section.lines[lineIdx + 1].indent
                    if (nextIndent > 0) {
                        val treeX = px + 8 + line.indent * indentStep + 2
                        graphics.fill(treeX, curY + lineH - 1, treeX + 1, curY + lineH, treeLineColor)
                    }
                }

                curY += lineH
                rowIndex++
            }
            // Section separator (except after last section)
            if (sectionIdx < sections.lastIndex) {
                curY += 1
                graphics.fill(px + 4, curY, px + pw - 4, curY + 1, 0xFF333333.toInt())
                curY += 2
            }
        }
    }

    private fun renderTooltipLines(graphics: GuiGraphics, lines: List<String>, mouseX: Int, mouseY: Int) {
        val tooltipW = lines.maxOf { font.width(it) } + 6
        val tooltipH = lines.size * (font.lineHeight + 1) + 4
        val tx = mouseX + 10
        val ty = mouseY - tooltipH - 2

        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 400f)
        graphics.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, SEPARATOR)
        graphics.fill(tx, ty, tx + tooltipW, ty + tooltipH, 0xFF1A1A1A.toInt())
        for ((i, line) in lines.withIndex()) {
            val c = if (i == 0) WHITE else GRAY
            graphics.drawString(font, line, tx + 3, ty + 2 + i * (font.lineHeight + 1), c)
        }
        graphics.pose().popPose()
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default labels
    }

    // ========== Line drawing ==========

    private fun renderBlockIcon(
        graphics: GuiGraphics, block: DiagnosticOpenData.NetworkBlock,
        sx: Int, sy: Int, halfBlock: Int, mouseX: Int, mouseY: Int
    ) {
        // Render the block's item icon (16x16 centered on sx,sy)
        val itemStack = BLOCK_ITEMS[block.type]
        if (itemStack != null) {
            graphics.renderItem(itemStack, sx - 8, sy - 8)
        } else {
            val color = BLOCK_COLORS[block.type] ?: 0xFFAAAAAA.toInt()
            graphics.fill(sx - halfBlock, sy - halfBlock, sx + halfBlock, sy + halfBlock, color)
        }

        // Card icons in a visible pill below the block
        if (block.cards.isNotEmpty()) {
            val iconsTexture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")
            val uniqueTypes = block.cards.map { it.cardType }.distinct()
            val iconSize = 10
            val iconSpacing = 0
            val pillW = uniqueTypes.size * (iconSize + iconSpacing) - iconSpacing
            val pillH = iconSize
            val pillX = sx - pillW / 2
            val pillY = sy + 9

            // Pill background with border
            graphics.fill(pillX - 1, pillY - 1, pillX + pillW + 1, pillY + pillH + 1, 0xFF333333.toInt())
            graphics.fill(pillX, pillY, pillX + pillW, pillY + pillH, 0xFF1A1A1A.toInt())

            for ((i, cardType) in uniqueTypes.withIndex()) {
                val iconU = CARD_ICON_U[cardType] ?: continue
                val iconX = pillX + i * (iconSize + iconSpacing)
                val iconY = pillY
                // Render the full 16x16 atlas tile scaled down to iconSize
                graphics.blit(iconsTexture, iconX, iconY, iconSize, iconSize,
                    iconU.toFloat(), 16f, 16, 16, 256, 256)
            }
        }

        // Selection highlight — tight around the 16x16 icon, uses network color
        if (block == selectedBlock) {
            val nc = menu.topology.networkColor
            val selR = minOf(((nc shr 16) and 0xFF) * 3 / 2, 255)
            val selG = minOf(((nc shr 8) and 0xFF) * 3 / 2, 255)
            val selB = minOf((nc and 0xFF) * 3 / 2, 255)
            val selColor = (0xFF shl 24) or (selR shl 16) or (selG shl 8) or selB
            graphics.fill(sx - 9, sy - 9, sx + 9, sy - 8, selColor)
            graphics.fill(sx - 9, sy + 8, sx + 9, sy + 9, selColor)
            graphics.fill(sx - 9, sy - 9, sx - 8, sy + 9, selColor)
            graphics.fill(sx + 8, sy - 9, sx + 9, sy + 9, selColor)
        }

        // Hover detection (16x16 area)
        if (mouseX >= sx - 8 && mouseX < sx + 8 &&
            mouseY >= sy - 8 && mouseY < sy + 8) {
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

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val field = craftItemField
        if (field != null && field.isFocused) {
            if (keyCode == 256) { // ESC
                craftAutocompleteSuggestions = emptyList()
                return super.keyPressed(keyCode, scanCode, modifiers)
            }
            // Dropdown navigation
            if (craftAutocompleteSuggestions.isNotEmpty()) {
                when (keyCode) {
                    265 -> { // UP
                        craftAutocompleteSelected = (craftAutocompleteSelected - 1).coerceAtLeast(0)
                        // Scroll to keep selected visible
                        if (craftAutocompleteSelected < craftAutocompleteScroll) craftAutocompleteScroll = craftAutocompleteSelected
                        return true
                    }
                    264 -> { // DOWN
                        craftAutocompleteSelected = (craftAutocompleteSelected + 1).coerceAtMost(craftAutocompleteSuggestions.lastIndex)
                        if (craftAutocompleteSelected >= craftAutocompleteScroll + craftDropdownMaxVisible) craftAutocompleteScroll = craftAutocompleteSelected - craftDropdownMaxVisible + 1
                        return true
                    }
                    257, 258 -> { // ENTER or TAB — accept and request preview
                        val selected = craftAutocompleteSuggestions[craftAutocompleteSelected]
                        field.value = selected
                        craftAutocompleteSuggestions = emptyList()
                        damien.nodeworks.platform.PlatformServices.clientNetworking.sendToServer(
                            damien.nodeworks.network.CraftPreviewRequestPayload(menu.containerId, menu.clickedPos, selected)
                        )
                        return true
                    }
                }
            }
            field.keyPressed(keyCode, scanCode, modifiers)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Double-click on craft field → select all
        val field = craftItemField
        if (field != null && field.visible && mx >= field.x && mx < field.x + field.width && my >= field.y && my < field.y + field.height) {
            val now = net.minecraft.Util.getMillis()
            if (now - craftFieldLastClickTime < 400) {
                field.moveCursorToStart(false)
                field.moveCursorToEnd(true)
                craftFieldLastClickTime = 0
                return true
            }
            craftFieldLastClickTime = now
        }

        // Craft dropdown click
        if (craftAutocompleteSuggestions.isNotEmpty() && craftItemField != null) {
            val field = craftItemField!!
            val dropX = field.x
            val dropY = field.y + field.height + 1
            val dropW = field.width
            val itemH = font.lineHeight + 4
            if (mx >= dropX && mx < dropX + dropW && my >= dropY) {
                val visIdx = (my - dropY - 1) / itemH
                val idx = craftAutocompleteScroll + visIdx
                if (idx in craftAutocompleteSuggestions.indices) {
                    val selected = craftAutocompleteSuggestions[idx]
                    field.value = selected
                    craftAutocompleteSuggestions = emptyList()
                    damien.nodeworks.platform.PlatformServices.clientNetworking.sendToServer(
                        damien.nodeworks.network.CraftPreviewRequestPayload(menu.containerId, menu.clickedPos, selected)
                    )
                    return true
                }
            }
        }

        // Tab clicks
        val tabY = topPos + 20
        val tabH = 12
        if (my >= tabY && my < tabY + tabH) {
            var tabX = leftPos + 4
            for ((i, name) in TAB_NAMES.withIndex()) {
                val tabW = font.width(name) + 10
                if (mx >= tabX && mx < tabX + tabW) {
                    activeTab = i
                    craftItemField?.visible = i == 2
                    return true
                }
                tabX += tabW + 2
            }
        }

        // Group expand/collapse — click on stacked icons or expanded rectangle to toggle
        if (activeTab == 0 && hoveredGroupIdx in stackGroups.indices) {
            val groupIdx = hoveredGroupIdx
            if (groupIdx in expandedGroups) expandedGroups.remove(groupIdx)
            else expandedGroups.add(groupIdx)
            return true
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

        // Click on a block to select/swap inspector
        if (activeTab == 0 && hoveredBlock != null) {
            selectedBlock = hoveredBlock
            return true
        }

        // Click in content area — start drag (don't deselect inspector)
        if (activeTab == 0 && mx >= contentLeft && mx < contentLeft + contentW &&
            my >= contentTop && my < contentTop + contentH) {
            dragging = true
            lastDragX = mouseX
            lastDragY = mouseY
            return true
        }

        // Craft graph drag
        val splitX = contentLeft + (contentW * craftSplitRatio).toInt()
        if (activeTab == 2 && mx >= splitX && mx < contentLeft + contentW &&
            my >= contentTop + 22 && my < contentTop + contentH) {
            craftGraphDragging = true
            craftGraphLastDragX = mouseX
            craftGraphLastDragY = mouseY
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragging = false
        craftGraphDragging = false
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
        if (craftGraphDragging) {
            craftGraphPanX += (mouseX - craftGraphLastDragX).toFloat()
            craftGraphPanY += (mouseY - craftGraphLastDragY).toFloat()
            craftGraphLastDragX = mouseX
            craftGraphLastDragY = mouseY
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // Scroll craft dropdown
        if (craftAutocompleteSuggestions.size > craftDropdownMaxVisible && craftItemField?.isFocused == true) {
            val maxScroll = craftAutocompleteSuggestions.size - craftDropdownMaxVisible
            craftAutocompleteScroll = (craftAutocompleteScroll - scrollY.toInt()).coerceIn(0, maxScroll)
            return true
        }

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
        // Craft tab: scroll text tree on left, zoom graph on right
        if (activeTab == 2 && mouseY >= contentTop && mouseY < contentTop + contentH) {
            val splitX = contentLeft + (contentW * craftSplitRatio).toInt()
            if (mouseX >= splitX) {
                // Zoom the graph panel
                val oldZoom = craftGraphZoom
                craftGraphZoom = (craftGraphZoom * (1f + scrollY.toFloat() * 0.15f)).coerceIn(0.3f, 4f)
                // Zoom toward mouse
                val mx = (mouseX - (splitX + 2 + (contentLeft + contentW - splitX - 2) / 2f) - craftGraphPanX).toFloat()
                val my = (mouseY - (contentTop + 20f) - craftGraphPanY).toFloat()
                craftGraphPanX -= mx * (craftGraphZoom / oldZoom - 1f)
                craftGraphPanY -= my * (craftGraphZoom / oldZoom - 1f)
                return true
            } else if (mouseX >= contentLeft) {
                craftTreeScrollY = (craftTreeScrollY - scrollY.toInt() * 10).coerceAtLeast(0)
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
}
