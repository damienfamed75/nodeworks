package damien.nodeworks.screen

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
// 26.1: imageWidth/imageHeight are `protected final` at the common/ compile-time
//  view of ACS (the NeoForge AT only strips `final` at runtime). `AcsCompat` writes
//  through to the runtime-mutable fields via reflection in init() below so the
//  pre-migration "75%/85% of window" dynamic sizing still works. The 720×540 in
//  the ctor is just a pre-resize placeholder.
) : AbstractContainerScreen<DiagnosticMenu>(menu, playerInventory, title, 720, 540) {

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
            "crafting_storage" to "Crafting Buffer",
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
    private var panY = 80f
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
    private var craftGraphNeedsAutoFit = true
    private var lastCraftTree: Any? = null
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
        damien.nodeworks.compat.AcsCompat.setImageSize(
            this,
            (width * 0.75f).toInt().coerceIn(320, width - 20),
            (height * 0.85f).toInt().coerceIn(200, height - 20)
        )
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Craft preview search field
        craftItemField = net.minecraft.client.gui.components.EditBox(
            font,
            contentLeft + 4,
            contentTop + 4,
            200,
            14,
            net.minecraft.network.chat.Component.literal("Search recipes...")
        ).also {
            it.setMaxLength(128)
            it.setBordered(true)
            it.setHint(net.minecraft.network.chat.Component.literal("insert item id"))
            it.visible = activeTab == 2
            it.setResponder { value -> updateCraftAutocomplete(value) }
            addRenderableWidget(it)
        }
    }

    // ========== Coordinate conversion ==========

    private val contentLeft get() = leftPos + 4
    private val contentTop get() = topPos + 24
    private val contentW get() = imageWidth - 8
    private val contentH get() = imageHeight - 28
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

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Main background
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Top bar with network color
        val networkColor = menu.topology.networkColor
        val netName = if (menu.topology.networkName.isNotEmpty()) menu.topology.networkName else "Network"
        val titleStr = "Inspecting: $netName"
        NineSlice.drawTitleBar(
            graphics,
            font,
            Component.literal(titleStr),
            leftPos,
            topPos,
            imageWidth,
            22,
            networkColor
        )

        // Tab buttons in title bar — right-aligned
        val btnH = 14
        val btnY = topPos + 5
        var tabX = leftPos + imageWidth - 4
        for (i in TAB_NAMES.indices.reversed()) {
            val name = TAB_NAMES[i]
            val btnW = font.width(name) + 10
            tabX -= btnW + 2
            val hovered = mouseX >= tabX && mouseX < tabX + btnW && mouseY >= btnY && mouseY < btnY + btnH
            val btnSlice = when {
                i == activeTab -> NineSlice.BUTTON_ACTIVE
                hovered -> NineSlice.BUTTON_HOVER
                else -> NineSlice.BUTTON
            }
            btnSlice.draw(graphics, tabX, btnY, btnW, btnH)
            val textColor = if (i == activeTab) WHITE else GRAY
            val textY = btnY + (btnH - font.lineHeight) / 2 + 1
            graphics.drawString(font, name, tabX + 5, textY, textColor)
        }

        // Content area
        NineSlice.PANEL_INSET.draw(graphics, contentLeft, contentTop, contentW, contentH)

        when (activeTab) {
            0 -> renderTopology(graphics, mouseX, mouseY)
            2 -> renderCraftPreview(graphics, mouseX, mouseY)
            3 -> renderJobsAndErrors(graphics, mouseX, mouseY)
            else -> {
                val msg = "Coming Soon"
                val msgW = font.width(msg)
                graphics.drawString(font, msg, contentLeft + (contentW - msgW) / 2, contentTop + contentH / 2, DIM)
            }
        }

        // Inspector panel — rendered last with higher Z to draw over topology blocks.
        // 26.1: `flush()` is gone; `nextStratum()` is the replacement — it advances
        // the Z stratum so later draws layer above what came before in this extract.
        if (activeTab == 0) {
            graphics.nextStratum()
            graphics.pose().pushMatrix()
            renderInspector(graphics, mouseX, mouseY)
            graphics.pose().popMatrix()
        }
    }

    private var hoveredGroupIdx = -1

    private fun renderTopology(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
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
                drawLine(
                    graphics,
                    sx1.roundToInt(),
                    sy1.roundToInt(),
                    sx2.roundToInt(),
                    sy2.roundToInt(),
                    networkLineColor
                )
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
                graphics.fill(
                    btnX,
                    btnY,
                    btnX + 8,
                    btnY + 8,
                    if (btnHovered) 0xFF555555.toInt() else 0xFF333333.toInt()
                )
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
                        graphics.pose().pushMatrix()
                        graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                        graphics.renderItem(itemStack, sx - 8 + off, sy - 8 + off)
                        graphics.pose().popMatrix()
                    }
                }

                // Count badge on top of everything
                val topZ = group.blocks.size * 50f + 100f
                val totalOffset = (group.blocks.size - 1) * stackOffset
                graphics.pose().pushMatrix()
                graphics.pose().translate((0f).toFloat(), (0f).toFloat())

                if (group.blocks.size > 1) {
                    val countStr = "+${group.blocks.size}"
                    graphics.drawString(font, countStr, sx + 2 + totalOffset, sy - 10, WHITE, true)
                }

                graphics.pose().popMatrix()

                // Hover on stacked group — clicking expands
                if (mouseX >= sx - 8 && mouseX < sx + 8 + totalOffset &&
                    mouseY >= sy - 8 && mouseY < sy + 8 + totalOffset
                ) {
                    hoveredGroupIdx = groupIdx
                }
            }
        }

        graphics.disableScissor()

        // Zoom indicator
        val zoomStr = String.format("%.0f%%", zoom * 100)
        graphics.drawString(
            font,
            zoomStr,
            contentLeft + contentW - font.width(zoomStr) - 4,
            contentTop + contentH - font.lineHeight - 2,
            DIM
        )
    }

    // ========== Craft Preview Tab ==========

    private val craftSplitRatio = 3f / 5f

    private fun renderCraftPreview(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val tree = menu.craftTree
        val treeAreaTop = contentTop + 22

        // Tree view fills the content area
        val graphLeft = contentLeft + 4
        val graphRight = contentLeft + contentW - 4
        val graphTop = treeAreaTop
        val graphBottom = contentTop + contentH - 4
        val graphW = graphRight - graphLeft

        // Tree view background + border
        NineSlice.PANEL_INSET.draw(graphics, graphLeft - 3, graphTop - 3, graphW + 6, graphBottom - graphTop + 6)
        NineSlice.CONTENT_BORDER.draw(graphics, graphLeft - 3, graphTop - 3, graphW + 6, graphBottom - graphTop + 6)

        // Floating detail panel frame (always visible)
        val detailW = 180
        val detailX = contentLeft + 6
        val detailY = treeAreaTop + 2
        val detailH = graphBottom - detailY - 2

        graphics.pose().pushMatrix()
        graphics.pose().translate((0f).toFloat(), (0f).toFloat())
        NineSlice.PANEL_INSET.draw(graphics, detailX, detailY, detailW, detailH)
        NineSlice.CONTENT_BORDER.draw(graphics, detailX, detailY, detailW, detailH)
        graphics.pose().popMatrix()

        if (tree == null) {
            // Placeholder text inside the detail panel
            graphics.pose().pushMatrix()
            graphics.pose().translate((0f).toFloat(), (0f).toFloat())
            graphics.drawString(
                font,
                "Search for a recipe",
                detailX + 8,
                detailY + 8,
                DIM
            )
            graphics.pose().popMatrix()
            // The autocomplete dropdown needs to render even when no tree has been
            //  picked yet — that's the normal state when the user first focuses the
            //  search bar. Previously we fell through to the early return below and
            //  the dropdown never drew despite click handlers working.
            renderCraftAutocomplete(graphics, mouseX, mouseY)
            return
        }

        // Visual item graph with zoom/pan. Scissor starts at the RIGHT edge of the
        // detail panel so tree items/icons can't render on top of it — item rendering
        // uses its own Z stack that the panel's Z=100 re-draw doesn't outrank.
        graphics.enableScissor(detailX + detailW, graphTop, graphRight, graphBottom)
        graphics.pose().pushMatrix()
        val layout = layoutCraftTree(tree)

        // Reset auto-fit when tree changes
        if (tree !== lastCraftTree) {
            lastCraftTree = tree
            craftGraphNeedsAutoFit = true
        }
        // The visible tree area is between the detail panel right edge and the graph right edge
        val treeAreaLeft = detailX + detailW
        val treeAreaW = graphRight - treeAreaLeft

        // Auto-fit: compute bounds of the tree and scale to fit the visible area
        if (craftGraphNeedsAutoFit) {
            craftGraphNeedsAutoFit = false
            val positions = layout.positions.values
            if (positions.isNotEmpty()) {
                val minX = positions.minOf { it.first }
                val maxX = positions.maxOf { it.first }
                val minY = positions.minOf { it.second }
                val maxY = positions.maxOf { it.second }
                val treeW = maxX - minX + 32f
                val treeH = maxY - minY + 32f
                val scaleX = (treeAreaW - 16f) / treeW
                val scaleY = ((graphBottom - graphTop) - 16f) / treeH
                craftGraphZoom = minOf(scaleX, scaleY, 2f).coerceAtLeast(0.3f)
                craftGraphPanX = -(minX + maxX) / 2f * craftGraphZoom
                craftGraphPanY = -(minY + maxY) / 2f * craftGraphZoom + 10f
            }
        }

        val graphCenterX = treeAreaLeft + treeAreaW / 2f + craftGraphPanX
        val graphCenterY = graphTop + (graphBottom - graphTop) / 2f + craftGraphPanY
        renderCraftTreeVisual(graphics, tree, layout, graphCenterX, graphCenterY, craftGraphZoom)
        graphics.pose().popMatrix()
        graphics.disableScissor()

        // Detail panel content (reuses frame drawn above)
        val scrollbarW = 6

        graphics.pose().pushMatrix()
        graphics.pose().translate((0f).toFloat(), (0f).toFloat())
        // Redraw panel over the tree view content
        NineSlice.PANEL_INSET.draw(graphics, detailX, detailY, detailW, detailH)
        NineSlice.CONTENT_BORDER.draw(graphics, detailX, detailY, detailW, detailH)

        val innerTop = detailY + 3
        val innerBottom = detailY + detailH - 3
        val innerH = innerBottom - innerTop

        graphics.enableScissor(detailX + 3, innerTop, detailX + detailW - 3 - scrollbarW, innerBottom)
        craftTreeTextY = 0
        renderCraftTreeText(graphics, tree, detailX + 6, innerTop + 2 - craftTreeScrollY, 0)
        val totalTextH = craftTreeTextY - (innerTop + 1 - craftTreeScrollY)
        graphics.disableScissor()

        // Scrollbar
        val maxScroll = maxOf(0, totalTextH - innerH)
        craftTreeScrollY = craftTreeScrollY.coerceIn(0, maxOf(0, maxScroll))
        if (totalTextH > innerH) {
            val sbX = detailX + detailW - 3 - scrollbarW
            val thumbH = maxOf(12, innerH * innerH / totalTextH)
            val thumbY = innerTop + (innerH - thumbH) * craftTreeScrollY / maxScroll
            NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, innerTop, scrollbarW, innerH)
            NineSlice.SCROLLBAR_THUMB.draw(graphics, sbX, thumbY, scrollbarW, thumbH)
        }

        graphics.pose().popMatrix()

        // Autocomplete dropdown last so it overlays both the detail panel and the
        //  tree view — pre-migration the detail panel was drawn above it in the
        //  submission order, which hid the dropdown on click.
        renderCraftAutocomplete(graphics, mouseX, mouseY)
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

    private fun renderCraftAutocomplete(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (craftAutocompleteSuggestions.isEmpty()) return
        val field = craftItemField ?: return
        if (!field.isFocused) return

        val dropX = field.x
        val dropY = field.y + field.height + 1
        val dropW = field.width
        val itemH = font.lineHeight + 4
        val visibleCount = minOf(craftAutocompleteSuggestions.size, craftDropdownMaxVisible)
        val dropH = visibleCount * itemH + 2

        graphics.pose().pushMatrix()
        graphics.pose().translate((0f).toFloat(), (0f).toFloat())
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
            val id = net.minecraft.resources.Identifier.tryParse(suggestion)
            if (id != null) {
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id)
                if (item != null) {
                    graphics.pose().pushMatrix()
                    graphics.pose().translate((dropX + 2).toFloat(), (sy + 1).toFloat())
                    graphics.pose().scale((0.5f).toFloat(), (0.5f).toFloat())
                    graphics.renderItem(ItemStack(item), 0, 0)
                    graphics.pose().popMatrix()
                }
            }

            // Item name (short) + ID
            val shortName = suggestion.substringAfter(':').replace('_', ' ')
            val color = if (idx == craftAutocompleteSelected) WHITE else GRAY
            graphics.drawString(font, shortName, dropX + 14, sy + 2, color, false)
        }

        // Scroll indicator
        if (craftAutocompleteSuggestions.size > craftDropdownMaxVisible) {
            val countStr = "${craftAutocompleteScroll + 1}-${
                minOf(
                    craftAutocompleteScroll + visibleCount,
                    craftAutocompleteSuggestions.size
                )
            } of ${craftAutocompleteSuggestions.size}"
            graphics.drawString(
                font,
                countStr,
                dropX + dropW - font.width(countStr) - 4,
                dropY + dropH - itemH + 2,
                DIM,
                false
            )
        }

        graphics.pose().popMatrix()
    }

    private var craftTreeTextY = 0 // tracks current Y for text rendering

    private fun renderCraftTreeText(
        graphics: GuiGraphicsExtractor,
        node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode,
        x: Int,
        startY: Int,
        depth: Int
    ) {
        val lineH = font.lineHeight + 2
        val indent = depth * 12
        var y = if (depth == 0) startY else craftTreeTextY

        // Source icon item
        val sourceItem: net.minecraft.world.item.Item? = when (node.source) {
            "craft_template" -> damien.nodeworks.registry.ModItems.INSTRUCTION_SET
            "process_template" -> damien.nodeworks.registry.ModItems.PROCESSING_SET
            "process_no_handler" -> damien.nodeworks.registry.ModItems.PROCESSING_SET
            "storage" -> net.minecraft.world.item.Items.CHEST
            "missing" -> net.minecraft.world.item.Items.CHEST
            else -> null
        }
        val showXOverlay = node.source == "process_no_handler" || node.source == "missing"

        // Row background
        val rowBg = if ((depth % 2) == 0) 0x00000000 else 0x08FFFFFF.toInt()
        if (rowBg != 0) graphics.fill(x, y - 2, x + 300, y + lineH - 2, rowBg)

        // Item icon (small)
        val itemId = net.minecraft.resources.Identifier.tryParse(node.itemId)
        if (itemId != null) {
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId)
            if (item != null) {
                graphics.pose().pushMatrix()
                graphics.pose().translate((x + indent).toFloat(), (y - 1).toFloat())
                graphics.pose().scale((0.5f).toFloat(), (0.5f).toFloat())
                graphics.renderItem(ItemStack(item), 0, 0)
                graphics.pose().popMatrix()
            }
        }

        // Text: count x name — [icon]
        val text = "${node.count}x ${node.itemName}"
        val textX = x + indent + 10
        graphics.drawString(font, text, textX, y, WHITE, false)
        val dashX = textX + font.width(text)
        graphics.drawString(font, " — ", dashX, y, DIM, false)
        val iconX = dashX + font.width(" — ")
        if (sourceItem != null) {
            graphics.pose().pushMatrix()
            graphics.pose().translate(iconX.toFloat(), (y - 1).toFloat())
            graphics.pose().scale((0.5f).toFloat(), (0.5f).toFloat())
            graphics.renderItem(ItemStack(sourceItem), 0, 0)
            graphics.pose().popMatrix()
        }
        if (showXOverlay) {
            graphics.pose().pushMatrix()
            graphics.pose().translate((0f).toFloat(), (0f).toFloat())
            Icons.X.draw(graphics, if (sourceItem != null) iconX else iconX - 4, y - 1, 8)
            graphics.pose().popMatrix()
        }

        // Sub-info
        y += lineH
        if (node.templateName.isNotEmpty()) {
            graphics.drawString(font, "Template: ${node.templateName}", x + indent + 14, y, DIM, false)
            y += lineH
        }
        if (node.resolvedBy.isNotEmpty() && node.resolvedBy != "storage") {
            val netIconX = x + indent + 14
            Icons.NETWORK.draw(graphics, netIconX - 4, y - 4)
            val netName = when {
                node.resolvedBy.startsWith("subnet: ") -> node.resolvedBy.removePrefix("subnet: ")
                node.resolvedBy == "subnet" -> "subnet"
                else -> "in-network"
            }
            graphics.drawString(font, netName, netIconX + 11, y, DIM, false)
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
    private val nodeSpacingY = 36f  // vertical distance between parent and child (depth)

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
        graphics: GuiGraphicsExtractor, node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode,
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
            val itemId = net.minecraft.resources.Identifier.tryParse(n.itemId)
            if (itemId != null) {
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId)
                if (item != null) {
                    graphics.renderItem(ItemStack(item), sx - 8, sy)
                    if (n.count > 1) {
                        graphics.drawString(font, "x${n.count}", sx + 9, sy + 9, WHITE, true)
                    }
                }
            }

            // Source icon below item
            val srcItem: net.minecraft.world.item.Item? = when (n.source) {
                "craft_template" -> damien.nodeworks.registry.ModItems.INSTRUCTION_SET
                "process_template" -> damien.nodeworks.registry.ModItems.PROCESSING_SET
                "process_no_handler" -> damien.nodeworks.registry.ModItems.PROCESSING_SET
                "storage", "missing" -> net.minecraft.world.item.Items.CHEST
                else -> null
            }
            if (srcItem != null) {
                graphics.pose().pushMatrix()
                graphics.pose().translate((sx - 4).toFloat(), (sy + 16).toFloat())
                graphics.pose().scale((0.5f).toFloat(), (0.5f).toFloat())
                graphics.renderItem(ItemStack(srcItem), 0, 0)
                graphics.pose().popMatrix()
            }
            if (n.source == "missing" || n.source == "process_no_handler") {
                graphics.pose().pushMatrix()
                graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                Icons.X.draw(graphics, sx - 4, sy + 16, 8)
                graphics.pose().popMatrix()
            }
        }
    }

    // ========== Jobs & Errors Tab ==========

    private var jobsScrollY = 0

    /** Rendered error click regions: (y start, y end, terminal pos) */
    private val errorClickRegions = mutableListOf<Triple<Int, Int, BlockPos>>()

    private fun renderJobsAndErrors(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val lineH = font.lineHeight + 2
        val splitX = contentLeft + contentW / 2
        graphics.fill(splitX, contentTop, splitX + 1, contentTop + contentH, SEPARATOR)

        // Left panel: Jobs (CPUs + Terminals)
        graphics.enableScissor(contentLeft, contentTop, splitX, contentTop + contentH)
        var y = contentTop + 2 - jobsScrollY

        // CPU section
        graphics.fill(contentLeft + 1, y, splitX - 1, y + lineH, 0x20FFFFFF.toInt())
        graphics.drawString(font, "Crafting CPUs", contentLeft + 4, y + 1, WHITE, false)
        y += lineH + 2

        val cpus = menu.topology.cpuInfos
        if (cpus.isEmpty()) {
            graphics.drawString(font, "No CPUs on network", contentLeft + 8, y, DIM, false)
            y += lineH
        } else {
            for (cpu in cpus) {
                val statusColor = when {
                    !cpu.isFormed -> 0xFFFF5555.toInt()
                    cpu.isCrafting -> 0xFF55FF55.toInt()
                    else -> GRAY
                }
                val status = when {
                    !cpu.isFormed -> "Not Formed"
                    cpu.isCrafting -> "Crafting: ${cpu.currentCraftItem}"
                    else -> "Idle"
                }
                graphics.drawString(font, status, contentLeft + 8, y, statusColor, false)
                y += lineH
                graphics.drawString(
                    font,
                    "Buffer: ${cpu.bufferUsed}/${cpu.bufferCapacity}",
                    contentLeft + 12,
                    y,
                    DIM,
                    false
                )
                y += lineH
                val posStr = "(${cpu.pos.x}, ${cpu.pos.y}, ${cpu.pos.z})"
                graphics.drawString(font, posStr, contentLeft + 12, y, DIM, false)
                y += lineH + 2
            }
        }

        // Terminal section
        y += 4
        graphics.fill(contentLeft + 1, y, splitX - 1, y + lineH, 0x20FFFFFF.toInt())
        graphics.drawString(font, "Terminals", contentLeft + 4, y + 1, WHITE, false)
        y += lineH + 2

        val terminals = menu.topology.terminalInfos
        if (terminals.isEmpty()) {
            graphics.drawString(font, "No terminals on network", contentLeft + 8, y, DIM, false)
            y += lineH
        } else {
            for (term in terminals) {
                val statusColor = if (term.isRunning) 0xFF55FF55.toInt() else GRAY
                val statusStr = if (term.isRunning) "Running" else "Stopped"
                val autoStr = if (term.autoRun) " [auto]" else ""
                graphics.drawString(font, "$statusStr$autoStr", contentLeft + 8, y, statusColor, false)
                y += lineH

                val posStr = "(${term.pos.x}, ${term.pos.y}, ${term.pos.z})"
                graphics.drawString(font, posStr, contentLeft + 12, y, DIM, false)
                y += lineH

                if (term.handlers.isNotEmpty()) {
                    graphics.drawString(
                        font,
                        "Handlers: ${term.handlers.joinToString(", ")}",
                        contentLeft + 12,
                        y,
                        0xFFAA83E0.toInt(),
                        false
                    )
                    y += lineH
                }

                val scripts = term.scriptNames.joinToString(", ")
                graphics.drawString(font, "Scripts: $scripts", contentLeft + 12, y, DIM, false)
                y += lineH + 2
            }
        }
        graphics.disableScissor()

        // Right panel: Live Errors
        graphics.fill(splitX + 2, contentTop, contentLeft + contentW, contentTop + lineH + 2, 0x20FFFFFF.toInt())
        graphics.drawString(font, "Errors (live)", splitX + 6, contentTop + 2, WHITE, false)

        val errors = menu.liveErrors
        val errorTop = contentTop + lineH + 4
        graphics.enableScissor(splitX + 2, errorTop, contentLeft + contentW, contentTop + contentH)

        errorClickRegions.clear()
        if (errors.isEmpty()) {
            graphics.drawString(font, "No errors", splitX + 8, errorTop + 2, DIM, false)
        } else {
            var ey = errorTop + 2
            for (error in errors) {
                val entryStart = ey
                val age = (System.currentTimeMillis() - error.timestamp) / 1000
                val ageStr = if (age < 60) "${age}s ago" else "${age / 60}m ago"
                val posStr = "(${error.terminalPos.x}, ${error.terminalPos.y}, ${error.terminalPos.z})"

                // Check if this entry is hovered
                val errorRight = contentLeft + contentW
                val isHovered = mouseX >= splitX + 2 && mouseX < errorRight

                // Time + position header
                val headerColor =
                    if (isHovered && mouseY >= entryStart && mouseY < entryStart + lineH) 0xFFAABBCC.toInt() else DIM
                graphics.drawString(font, "$ageStr — $posStr", splitX + 6, ey, headerColor, false)
                ey += lineH

                // Error message (may wrap)
                val maxW = errorRight - splitX - 10
                val wrapped = font.splitter.splitLines(error.message, maxW, net.minecraft.network.chat.Style.EMPTY)
                for (line in wrapped) {
                    graphics.drawString(font, line.string, splitX + 8, ey, 0xFFFF5555.toInt(), false)
                    ey += lineH
                }

                val entryEnd = ey

                // Hover highlight + underline
                if (isHovered && mouseY >= entryStart && mouseY < entryEnd) {
                    graphics.fill(splitX + 3, entryStart - 1, errorRight - 2, entryEnd, 0x15FFFFFF.toInt())
                    // Underline the position text to indicate clickability
                    val posW = font.width("$ageStr — $posStr")
                    graphics.fill(
                        splitX + 6,
                        entryStart + lineH - 2,
                        splitX + 6 + posW,
                        entryStart + lineH - 1,
                        0x60FFFFFF.toInt()
                    )
                }

                errorClickRegions.add(Triple(entryStart, entryEnd, error.terminalPos))

                ey += 2
                graphics.fill(splitX + 6, ey - 1, errorRight - 4, ey, 0xFF333333.toInt())
                ey += 2

                if (ey > contentTop + contentH) break
            }
        }
        graphics.disableScissor()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

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

        // Inspector is rendered in renderBg after topology to ensure Z-ordering
    }

    private val inspectorWidth = 150
    private var inspectorScrollY = 0
    private var draggingInspectorScrollbar = false
    private val lineH get() = font.lineHeight + 1

    /** Inspector row types for the floating inspector panel. */
    private enum class RowType { H2, PROPERTY }
    private data class InspectorRow(
        val type: RowType,
        val text: String,
        val color: Int = WHITE,
        val iconU: Int = -1,
        val blockItemId: String = ""
    )

    private val CARD_ICON_U = mapOf("io" to 0, "storage" to 16, "redstone" to 32)

    private fun buildInspectorRows(block: DiagnosticOpenData.NetworkBlock): List<InspectorRow> {
        val rows = mutableListOf<InspectorRow>()

        // Info
        rows.add(InspectorRow(RowType.H2, "Info"))
        rows.add(InspectorRow(RowType.PROPERTY, "Position: ${block.pos.x}, ${block.pos.y}, ${block.pos.z}", GRAY))
        rows.add(InspectorRow(RowType.PROPERTY, "Connections: ${block.connections.size}", GRAY))

        // Each face as its own section
        if (block.cards.isNotEmpty()) {
            val dirNames = arrayOf("Down", "Up", "North", "South", "East", "West")
            val bySide = block.cards.groupBy { it.side }
            for ((side, sideCards) in bySide) {
                val dir = dirNames.getOrElse(side) { "?" }
                val adjBlockId = sideCards.firstOrNull()?.adjacentBlockId ?: ""
                val adjName = if (adjBlockId.isNotEmpty()) adjBlockId.substringAfter(':') else "air"
                rows.add(InspectorRow(RowType.H2, "$dir \u2014 $adjName", WHITE, blockItemId = adjBlockId))
                for (card in sideCards) {
                    val alias = if (card.alias.isNotEmpty()) card.alias else card.cardType
                    val color = CARD_COLORS[card.cardType] ?: GRAY
                    val iconU = CARD_ICON_U[card.cardType] ?: -1
                    rows.add(InspectorRow(RowType.PROPERTY, alias, color, iconU))
                }
            }
        }

        // Details — pull out the __error: prefix into its own dedicated section
        // at the top so it's the first thing the player sees.
        val errorDetail = block.details.firstOrNull { it.startsWith("__error:") }
        if (errorDetail != null) {
            rows.add(InspectorRow(RowType.H2, "Error"))
            rows.add(InspectorRow(RowType.PROPERTY, errorDetail.removePrefix("__error:"), 0xFFFF8888.toInt()))
        }
        val visibleDetails = block.details.filter { !it.startsWith("__error:") }
        if (visibleDetails.isNotEmpty()) {
            rows.add(InspectorRow(RowType.H2, "Details"))
            for (detail in visibleDetails) {
                rows.add(InspectorRow(RowType.PROPERTY, detail, GRAY))
            }
        }

        return rows
    }

    private fun getInspectorTotalBodyH(): Int {
        val sel = selectedBlock ?: return 0
        val rows = buildInspectorRows(sel)
        var bodyH = 4
        for (row in rows) {
            bodyH += when (row.type) {
                RowType.H2 -> lineH + 3
                RowType.PROPERTY -> lineH
            }
        }
        bodyH += 4
        return bodyH
    }

    private fun getInspectorBounds(): IntArray? {
        val sel = selectedBlock ?: return null
        val headerH = 20
        val totalBodyH = getInspectorTotalBodyH()
        val maxH = contentH - 8
        val panelH = minOf(headerH + totalBodyH, maxH)
        val px = leftPos + imageWidth - inspectorWidth - 6
        val py = contentTop + 4
        return intArrayOf(px, py, inspectorWidth, panelH)
    }

    private fun renderInspector(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val sel = selectedBlock ?: return
        val bounds = getInspectorBounds() ?: return
        val px = bounds[0];
        val py = bounds[1];
        val pw = bounds[2];
        val ph = bounds[3]

        // Full inset body extending behind the title area
        NineSlice.PANEL_INSET.draw(graphics, px, py, pw, ph)

        // H1: block icon + title
        val title = BLOCK_LABELS[sel.type] ?: sel.type
        val titleColor = BLOCK_COLORS[sel.type] ?: WHITE
        val itemStack = BLOCK_ITEMS[sel.type]
        if (itemStack != null) {
            graphics.renderItem(itemStack, px + 2, py + 3)
        }
        graphics.drawString(font, title, px + 20, py + 8, titleColor)

        // H1: pin button
        val isPinned = damien.nodeworks.render.NodeConnectionRenderer.pinnedBlock == sel.pos
        val pinX = px + pw - 27
        val pinY = py + 6
        val pinIcon = if (isPinned) Icons.PINNED else Icons.UNPINNED
        val pinHovered = mouseX >= pinX && mouseX < pinX + 10 && mouseY >= pinY && mouseY < pinY + 10
        val tint = if (isPinned) 0xFF55CCFF.toInt() else if (pinHovered) WHITE else GRAY
        pinIcon.drawTinted(graphics, pinX - 3, pinY - 3, tint)

        // H1: close button
        val closeX = px + pw - 13
        val closeY = py + 7
        val closeHovered = mouseX >= closeX && mouseX < closeX + 8 && mouseY >= closeY && mouseY < closeY + 8
        graphics.drawString(font, "x", closeX + 1, closeY, if (closeHovered) WHITE else GRAY, false)

        // Body rows (scrollable)
        val rows = buildInspectorRows(sel)
        val bodyTop = py + 20
        val bodyBottom = py + ph
        val bodyH = bodyBottom - bodyTop
        val totalBodyH = getInspectorTotalBodyH()
        val maxScroll = maxOf(0, totalBodyH - bodyH)
        inspectorScrollY = inspectorScrollY.coerceIn(0, maxOf(0, maxScroll))
        val scrollbarW = 6

        graphics.enableScissor(px, bodyTop, px + pw, bodyBottom)
        var curY = bodyTop + 4 - inspectorScrollY
        var rowIndex = 0

        var isFirstRow = true
        for (row in rows) {
            when (row.type) {
                RowType.H2 -> {
                    if (!isFirstRow) curY += 1
                    NineSlice.INSPECTOR_H2.draw(graphics, px + 2, curY - 1, pw - 4, lineH + 3)
                    // H2 text with optional block icon
                    var h2TextX = px + 6
                    val h2TextY = curY + 2
                    if (row.blockItemId.isNotEmpty()) {
                        val adjId = net.minecraft.resources.Identifier.tryParse(row.blockItemId)
                        if (adjId != null) {
                            val adjItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(adjId)
                            if (adjItem != null) {
                                graphics.pose().pushMatrix()
                                graphics.pose().translate((h2TextX).toFloat(), (h2TextY - 1).toFloat())
                                graphics.pose().scale((0.5f).toFloat(), (0.5f).toFloat())
                                graphics.renderItem(ItemStack(adjItem), 0, 0)
                                graphics.pose().popMatrix()
                                h2TextX += 10
                            }
                        }
                    }
                    graphics.drawString(font, row.text, h2TextX, h2TextY, WHITE, false)
                    curY += lineH + 2
                    rowIndex = 0
                }

                RowType.PROPERTY -> {
                    val textY = curY + 1
                    // Alternating row background
                    if (rowIndex % 2 == 1) {
                        graphics.fill(px + 3, curY, px + pw - 3, curY + lineH, 0x08FFFFFF.toInt())
                    }
                    var textX = px + 14

                    // Card type icon
                    if (row.iconU >= 0) {
                        val icon = when (row.iconU) {
                            0 -> Icons.IO_CARD; 16 -> Icons.STORAGE_CARD; 32 -> Icons.REDSTONE_CARD; else -> Icons.IO_CARD
                        }
                        icon.drawSmall(graphics, textX, textY)
                        textX += 12
                    }

                    // Special rendering
                    if (row.text.startsWith("__color:")) {
                        val colorVal = row.text.removePrefix("__color:").toIntOrNull() ?: 0
                        val swatchColor = colorVal or 0xFF000000.toInt()
                        graphics.drawString(font, "Color:", textX, textY, WHITE, false)
                        val swatchX = textX + font.width("Color: ")
                        val ss = font.lineHeight - 2
                        graphics.fill(swatchX - 1, textY - 1, swatchX + ss + 1, textY + ss + 1, 0xFF444444.toInt())
                        graphics.fill(swatchX, textY, swatchX + ss, textY + ss, swatchColor)
                        val hexStr = "#${Integer.toHexString(colorVal).uppercase().padStart(6, '0')}"
                        graphics.drawString(font, hexStr, swatchX + ss + 3, textY, DIM, false)
                    } else if (row.text.startsWith("__glow:")) {
                        val parts = row.text.removePrefix("__glow:").split(":")
                        val glowStyle = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val glowColor = (parts.getOrNull(1)?.toIntOrNull() ?: 0x83E086) or 0xFF000000.toInt()
                        graphics.drawString(font, "Node Glow:", textX, textY, WHITE, false)
                        val glowIcon = when (glowStyle) {
                            0 -> Icons.GLOW_SQUARE; 1 -> Icons.GLOW_CIRCLE; 2 -> Icons.GLOW_DOT
                            3 -> Icons.GLOW_CREEPER; 4 -> Icons.GLOW_CAT; else -> Icons.GLOW_NONE
                        }
                        glowIcon.drawTinted(
                            graphics,
                            textX + font.width("Node Glow: "),
                            textY - 3,
                            glowColor and 0xFFFFFF
                        )
                    } else {
                        // Key: value color split
                        val colonIdx = row.text.indexOf(':')
                        if (colonIdx > 0) {
                            val key = row.text.substring(0, colonIdx + 1)
                            val value = row.text.substring(colonIdx + 1)
                            graphics.drawString(font, key, textX, textY, 0xFFCCCCCC.toInt(), false)
                            graphics.drawString(font, value, textX + font.width(key), textY, DIM, false)
                        } else {
                            graphics.drawString(font, row.text, textX, textY, row.color, false)
                        }
                    }

                    curY += lineH
                    rowIndex++
                }
            }
            isFirstRow = false
        }
        graphics.disableScissor()

        // Scrollbar
        if (totalBodyH > bodyH) {
            val sbX = px + pw - scrollbarW - 2
            val thumbH = maxOf(12, bodyH * bodyH / totalBodyH)
            val thumbY = bodyTop + (bodyH - thumbH) * inspectorScrollY / maxScroll
            NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, bodyTop, scrollbarW, bodyH)
            NineSlice.SCROLLBAR_THUMB.draw(graphics, sbX, thumbY, scrollbarW, thumbH)
        }
    }

    private fun renderTooltipLines(graphics: GuiGraphicsExtractor, lines: List<String>, mouseX: Int, mouseY: Int) {
        val tooltipW = lines.maxOf { font.width(it) } + 6
        val tooltipH = lines.size * (font.lineHeight + 1) + 4
        val tx = mouseX + 10
        val ty = mouseY - tooltipH - 2

        graphics.pose().pushMatrix()
        graphics.pose().translate((0f).toFloat(), (0f).toFloat())
        NineSlice.TOOLTIP.draw(graphics, tx - 1, ty - 1, tooltipW + 2, tooltipH + 2)
        for ((i, line) in lines.withIndex()) {
            val c = if (i == 0) WHITE else GRAY
            graphics.drawString(font, line, tx + 3, ty + 2 + i * (font.lineHeight + 1), c)
        }
        graphics.pose().popMatrix()
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Don't render default labels
    }

    // ========== Line drawing ==========

    private fun renderBlockIcon(
        graphics: GuiGraphicsExtractor, block: DiagnosticOpenData.NetworkBlock,
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
            val iconsTexture =
                net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")
            val uniqueTypes = block.cards.map { it.cardType }.distinct()
            val iconSize = 12
            val iconSpacing = 0
            val pillW = uniqueTypes.size * (iconSize + iconSpacing) - iconSpacing
            val pillH = iconSize
            val pillX = sx - pillW / 2
            val pillY = sy + 5

            // Pill background
            NineSlice.PILL.draw(graphics, pillX - 2, pillY, pillW + 4, pillH)

            for ((i, cardType) in uniqueTypes.withIndex()) {
                val iconU = CARD_ICON_U[cardType] ?: continue
                val iconX = pillX + i * (iconSize + iconSpacing)
                val iconY = pillY
                // Render the full 16x16 atlas tile scaled down to iconSize
                graphics.blit(
                    iconsTexture, iconX, iconY, iconSize, iconSize,
                    iconU.toFloat(), 16f, 16, 16, 256, 256
                )
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

        // Pinned indicator icon (top-left corner of block)
        if (damien.nodeworks.render.NodeConnectionRenderer.pinnedBlock == block.pos) {
            val pinIconTex =
                net.minecraft.resources.Identifier.fromNamespaceAndPath("nodeworks", "textures/gui/icons.png")
            // 26.1: `RenderSystem.setShaderColor` is gone; tint goes via the per-call
            //  ARGB arg on the stretched-blit overload. 0xFF54CCFF = rgb(0.33, 0.8, 1.0).
            graphics.blit(pinIconTex, sx - 11, sy - 11, 6, 6, 80f, 0f, 16, 16, 256, 256, 0xFF54CCFF.toInt())
        }

        // Warning indicator (top-right corner of block) when the block has an
        // undismissed error — currently only Crafting Cores emit this via the
        // "__error:" detail prefix. renderItem draws at a higher Z than normal
        // blits, so we translate the pose up to sit on top of the block icon.
        if (block.details.any { it.startsWith("__error:") }) {
            graphics.pose().pushMatrix()
            graphics.pose().translate((0f).toFloat(), (0f).toFloat())
            Icons.WARNING.draw(graphics, sx + 3, sy - 11, 10)
            graphics.pose().popMatrix()
        }

        // Hover detection (16x16 area)
        if (mouseX >= sx - 8 && mouseX < sx + 8 &&
            mouseY >= sy - 8 && mouseY < sy + 8
        ) {
            hoveredBlock = block
        }
    }

    private fun drawLine(graphics: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
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

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
        val field = craftItemField
        if (field != null && field.isFocused) {
            if (keyCode == 256) { // ESC
                craftAutocompleteSuggestions = emptyList()
                return super.keyPressed(event)
            }
            // Dropdown navigation
            if (craftAutocompleteSuggestions.isNotEmpty()) {
                when (keyCode) {
                    265 -> { // UP
                        craftAutocompleteSelected = (craftAutocompleteSelected - 1).coerceAtLeast(0)
                        // Scroll to keep selected visible
                        if (craftAutocompleteSelected < craftAutocompleteScroll) craftAutocompleteScroll =
                            craftAutocompleteSelected
                        return true
                    }

                    264 -> { // DOWN
                        craftAutocompleteSelected =
                            (craftAutocompleteSelected + 1).coerceAtMost(craftAutocompleteSuggestions.lastIndex)
                        if (craftAutocompleteSelected >= craftAutocompleteScroll + craftDropdownMaxVisible) craftAutocompleteScroll =
                            craftAutocompleteSelected - craftDropdownMaxVisible + 1
                        return true
                    }

                    257, 258 -> { // ENTER or TAB — accept and request preview
                        val selected = craftAutocompleteSuggestions[craftAutocompleteSelected]
                        field.value = selected
                        craftAutocompleteSuggestions = emptyList()
                        damien.nodeworks.platform.PlatformServices.clientNetworking.sendToServer(
                            damien.nodeworks.network.CraftPreviewRequestPayload(
                                menu.containerId,
                                menu.clickedPos,
                                selected
                            )
                        )
                        return true
                    }
                }
            }
            field.keyPressed(event)
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

        // Double-click on craft field → select all; single-click → populate dropdown
        // with the current query (empty query shows every craftable item).
        val field = craftItemField
        if (field != null && field.visible && mx >= field.x && mx < field.x + field.width && my >= field.y && my < field.y + field.height) {
            val now = net.minecraft.util.Util.getMillis()
            if (now - craftFieldLastClickTime < 400) {
                field.moveCursorToStart(false)
                field.moveCursorToEnd(true)
                craftFieldLastClickTime = 0
                return true
            }
            craftFieldLastClickTime = now
            // Seed the dropdown on click so the full list appears immediately
            //  without the user needing to type anything first.
            updateCraftAutocomplete(field.value)
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

        // Error click → switch to topology, select + pin the terminal
        if (activeTab == 3 && errorClickRegions.isNotEmpty()) {
            for ((yStart, yEnd, termPos) in errorClickRegions) {
                if (my >= yStart && my < yEnd && mx >= contentLeft + contentW / 2 && mx < contentLeft + contentW) {
                    // Find the block in topology that matches this terminal pos
                    val block = menu.topology.blocks.firstOrNull { it.pos == termPos }
                    if (block != null) {
                        selectedBlock = block
                        inspectorScrollY = 0
                        damien.nodeworks.render.NodeConnectionRenderer.pinnedBlock = termPos
                        activeTab = 0
                        craftItemField?.visible = false
                    }
                    return true
                }
            }
        }

        // Tab clicks
        val btnH = 14
        val btnY = topPos + 5
        if (my >= btnY && my < btnY + btnH) {
            var tabX = leftPos + imageWidth - 4
            for (i in TAB_NAMES.indices.reversed()) {
                val btnW = font.width(TAB_NAMES[i]) + 10
                tabX -= btnW + 2
                if (mx >= tabX && mx < tabX + btnW) {
                    activeTab = i
                    craftItemField?.visible = i == 2
                    selectedBlock = null
                    // Clear hover state so a stale topology-tab tooltip doesn't bleed
                    // into the newly-opened tab.
                    hoveredBlock = null
                    hoveredGroupIdx = -1
                    return true
                }
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
                val px = bounds[0];
                val py = bounds[1];
                val pw = bounds[2];
                val ph = bounds[3]
                // Pin button
                val pinX = px + pw - 27
                val pinY = py + 6
                if (mx >= pinX && mx < pinX + 16 && my >= pinY && my < pinY + 16) {
                    val currentPin = damien.nodeworks.render.NodeConnectionRenderer.pinnedBlock
                    if (currentPin == selectedBlock?.pos) {
                        damien.nodeworks.render.NodeConnectionRenderer.pinnedBlock = null
                    } else {
                        damien.nodeworks.render.NodeConnectionRenderer.pinnedBlock = selectedBlock?.pos
                    }
                    return true
                }

                // [X] close button
                val closeX = px + pw - 13
                val closeY = py + 7
                if (mx >= closeX && mx < closeX + 10 && my >= closeY && my < closeY + 10) {
                    selectedBlock = null
                    return true
                }
                // Scrollbar drag start
                val totalBodyH = getInspectorTotalBodyH()
                val bodyTop = py + 20
                val bodyH = ph - 20
                if (totalBodyH > bodyH) {
                    val sbX = px + pw - 8
                    if (mx >= sbX && mx < sbX + 6 && my >= bodyTop && my < bodyTop + bodyH) {
                        draggingInspectorScrollbar = true
                        return true
                    }
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
            inspectorScrollY = 0
            return true
        }

        // Click in content area — start drag (don't deselect inspector)
        if (activeTab == 0 && mx >= contentLeft && mx < contentLeft + contentW &&
            my >= contentTop && my < contentTop + contentH
        ) {
            dragging = true
            lastDragX = mouseX
            lastDragY = mouseY
            return true
        }

        // Craft graph drag — bounds must mirror the render's actual tree area:
        //   treeAreaLeft  = detailX + detailW = (contentLeft + 6) + 180
        //   treeAreaRight = graphRight        = contentLeft + contentW - 4
        //   treeAreaTop   = graphTop          = contentTop + 22
        //   treeAreaBottom= graphBottom       = contentTop + contentH - 4
        if (activeTab == 2) {
            val treeAreaLeft = contentLeft + 6 + 180
            val treeAreaRight = contentLeft + contentW - 4
            val treeAreaTop = contentTop + 22
            val treeAreaBottom = contentTop + contentH - 4
            if (mx >= treeAreaLeft && mx < treeAreaRight && my >= treeAreaTop && my < treeAreaBottom) {
                craftGraphDragging = true
                craftGraphLastDragX = mouseX
                craftGraphLastDragY = mouseY
                return true
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        dragging = false
        craftGraphDragging = false
        draggingInspectorScrollbar = false
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        if (draggingInspectorScrollbar) {
            val bounds = getInspectorBounds()
            if (bounds != null) {
                val py = bounds[1]; val ph = bounds[3]
                val bodyTop = py + 20
                val bodyH = ph - 20
                val totalBodyH = getInspectorTotalBodyH()
                val maxScroll = maxOf(1, totalBodyH - bodyH)
                val thumbH = maxOf(12, bodyH * bodyH / totalBodyH)
                val scrollRange = bodyH - thumbH
                if (scrollRange > 0) {
                    val relY = (mouseY.toInt() - bodyTop - thumbH / 2).toFloat() / scrollRange
                    inspectorScrollY = (relY * maxScroll).toInt().coerceIn(0, maxScroll)
                }
            }
            return true
        }
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
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // Scroll craft dropdown
        if (craftAutocompleteSuggestions.size > craftDropdownMaxVisible && craftItemField?.isFocused == true) {
            val maxScroll = craftAutocompleteSuggestions.size - craftDropdownMaxVisible
            craftAutocompleteScroll = (craftAutocompleteScroll - scrollY.toInt()).coerceIn(0, maxScroll)
            return true
        }

        // Inspector panel scroll
        if (activeTab == 0 && selectedBlock != null) {
            val bounds = getInspectorBounds()
            if (bounds != null) {
                val ipx = bounds[0]; val ipy = bounds[1]; val ipw = bounds[2]; val iph = bounds[3]
                if (mouseX >= ipx && mouseX < ipx + ipw && mouseY >= ipy && mouseY < ipy + iph) {
                    inspectorScrollY = (inspectorScrollY - scrollY.toInt() * 10).coerceAtLeast(0)
                    return true
                }
            }
        }

        if (activeTab == 0 && mouseX >= contentLeft && mouseX < contentLeft + contentW &&
            mouseY >= contentTop && mouseY < contentTop + contentH
        ) {
            val oldZoom = zoom
            zoom = (zoom * (1f + scrollY.toFloat() * 0.15f)).coerceIn(0.3f, 8f)

            // Zoom toward mouse position
            val mx = (mouseX - viewCenterX - panX).toFloat()
            val my = (mouseY - viewCenterY - panY).toFloat()
            panX -= mx * (zoom / oldZoom - 1f)
            panY -= my * (zoom / oldZoom - 1f)

            return true
        }
        // Craft tab: scroll text tree on left detail panel, zoom graph on the right.
        if (activeTab == 2 && mouseY >= contentTop && mouseY < contentTop + contentH) {
            val treeAreaLeft = contentLeft + 6 + 180
            val treeAreaRight = contentLeft + contentW - 4
            val treeAreaTop = contentTop + 22
            val treeAreaBottom = contentTop + contentH - 4
            if (mouseX >= treeAreaLeft && mouseX < treeAreaRight &&
                mouseY >= treeAreaTop && mouseY < treeAreaBottom
            ) {
                val oldZoom = craftGraphZoom
                craftGraphZoom = (craftGraphZoom * (1f + scrollY.toFloat() * 0.15f)).coerceIn(0.3f, 4f)
                // Zoom toward mouse — center must match graphCenterX/Y in the render path.
                val centerX = treeAreaLeft + (treeAreaRight - treeAreaLeft) / 2f
                val centerY = treeAreaTop + (treeAreaBottom - treeAreaTop) / 2f
                val mx = (mouseX - centerX - craftGraphPanX).toFloat()
                val my = (mouseY - centerY - craftGraphPanY).toFloat()
                craftGraphPanX -= mx * (craftGraphZoom / oldZoom - 1f)
                craftGraphPanY -= my * (craftGraphZoom / oldZoom - 1f)
                return true
            }
            // Detail panel (left of the graph) — scroll the text tree.
            val detailLeft = contentLeft + 6
            val detailRight = detailLeft + 180
            if (mouseX >= detailLeft && mouseX < detailRight &&
                mouseY >= treeAreaTop && mouseY < treeAreaBottom
            ) {
                craftTreeScrollY = (craftTreeScrollY - scrollY.toInt() * 10).coerceAtLeast(0)
                return true
            }
        }
        // Jobs tab scrolling
        if (activeTab == 3 && mouseX >= contentLeft && mouseX < contentLeft + contentW &&
            mouseY >= contentTop && mouseY < contentTop + contentH
        ) {
            jobsScrollY = (jobsScrollY - scrollY.toInt() * 10).coerceAtLeast(0)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
}
