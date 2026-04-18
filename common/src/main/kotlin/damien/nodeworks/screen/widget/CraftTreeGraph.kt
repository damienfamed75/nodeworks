package damien.nodeworks.screen.widget

import damien.nodeworks.screen.Icons
import damien.nodeworks.script.CraftTreeBuilder
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.drawCenteredString
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.drawWordWrap
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderFakeItem
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.renderItemDecorations
import damien.nodeworks.compat.renderTooltip
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/**
 * Reusable visual craft tree graph widget.
 * Renders item icons connected by L-shaped lines in a hierarchical layout.
 * Supports zoom, pan, auto-fit, and highlighting active/completed nodes.
 *
 * Used by both DiagnosticScreen (craft tab) and CraftingCoreScreen.
 */
class CraftTreeGraph {

    companion object {
        private const val NODE_SPACING_X = 28f
        private const val NODE_SPACING_Y = 36f
        private const val WHITE = 0xFFFFFFFF.toInt()
    }

    data class TreeLayout(
        val positions: Map<CraftTreeBuilder.CraftTreeNode, Pair<Float, Float>>
    )

    // View state
    var panX = 0f
    var panY = 0f
    var zoom = 1f
    var needsAutoFit = true
    var dragging = false
    var lastDragX = 0.0
    var lastDragY = 0.0

    /** Tree node IDs of branches currently being worked on — amber highlight + flow dots. */
    var activeNodeIds: Set<Int> = emptySet()

    /** Tree node IDs of branches that have fully completed — green highlight. */
    var completedNodeIds: Set<Int> = emptySet()

    private var lastTree: Any? = null
    /** Structural hash of [lastTree]. Compared each frame so re-synced trees with the same
     *  shape don't trigger an autoFit reset (which would wipe the user's pan/zoom every
     *  time the server pushes a tree update — annoying with 4 Hz active-state syncs). */
    private var lastTreeHash: Int = 0
    private var cachedLayout: TreeLayout? = null

    // ========== Layout ==========

    fun layoutTree(root: CraftTreeBuilder.CraftTreeNode): TreeLayout {
        val positions = mutableMapOf<CraftTreeBuilder.CraftTreeNode, Pair<Float, Float>>()
        layoutNode(root, 0f, 0f, positions)
        return TreeLayout(positions)
    }

    private fun layoutNode(
        node: CraftTreeBuilder.CraftTreeNode,
        xStart: Float, y: Float,
        positions: MutableMap<CraftTreeBuilder.CraftTreeNode, Pair<Float, Float>>
    ): Float {
        if (node.children.isEmpty()) {
            positions[node] = xStart to y
            return NODE_SPACING_X
        }

        var childX = xStart
        val childWidths = mutableListOf<Float>()
        for (child in node.children) {
            val w = layoutNode(child, childX, y + NODE_SPACING_Y, positions)
            childWidths.add(w)
            childX += w
        }

        val totalChildW = childWidths.sum()
        val centerX = xStart + totalChildW / 2f - NODE_SPACING_X / 2f
        positions[node] = centerX to y
        return totalChildW
    }

    // ========== Auto-Fit ==========

    fun autoFit(layout: TreeLayout, areaW: Float, areaH: Float) {
        val positions = layout.positions.values
        if (positions.isEmpty()) return

        val minX = positions.minOf { it.first }
        val maxX = positions.maxOf { it.first }
        val minY = positions.minOf { it.second }
        val maxY = positions.maxOf { it.second }
        val treeW = maxX - minX + 32f
        val treeH = maxY - minY + 32f
        val scaleX = (areaW - 16f) / treeW
        val scaleY = (areaH - 16f) / treeH
        zoom = minOf(scaleX, scaleY, 2f).coerceAtLeast(0.3f)
        panX = -(minX + maxX) / 2f * zoom
        panY = -(minY + maxY) / 2f * zoom + 10f
        needsAutoFit = false
    }

    // ========== Rendering ==========

    /**
     * Render the craft tree graph within the given bounds.
     * Handles layout caching, auto-fit, scissoring, and all node rendering.
     */
    fun render(
        graphics: GuiGraphicsExtractor,
        tree: CraftTreeBuilder.CraftTreeNode?,
        x: Int, y: Int, w: Int, h: Int
    ) {
        if (tree == null) {
            val font = Minecraft.getInstance().font
            graphics.drawString(font, "No active craft", x + w / 2 - font.width("No active craft") / 2, y + h / 2 - 4, 0xFF555555.toInt())
            return
        }

        // Always rebuild layout when the tree reference changes (positions map is keyed by
        // node identity, and the server pushes a fresh tree on every resync). But only
        // auto-fit when the structure is genuinely new — preserves the user's pan/zoom
        // across the steady-stream syncs that happen during an active craft.
        if (tree !== lastTree) {
            val newHash = tree.hashCode()
            val structurallyChanged = newHash != lastTreeHash
            val firstEver = lastTree == null
            lastTree = tree
            lastTreeHash = newHash
            cachedLayout = layoutTree(tree)
            if (firstEver || structurallyChanged) needsAutoFit = true
        }
        val layout = cachedLayout ?: return

        if (needsAutoFit) {
            autoFit(layout, w.toFloat(), h.toFloat())
        }

        val centerX = x + w / 2f + panX
        val centerY = y + h / 2f + panY

        graphics.enableScissor(x, y, x + w, y + h)
        renderGraph(graphics, tree, layout, centerX, centerY, zoom)
        graphics.disableScissor()
    }

    private fun renderGraph(
        graphics: GuiGraphicsExtractor,
        root: CraftTreeBuilder.CraftTreeNode,
        layout: TreeLayout,
        originX: Float, originY: Float,
        zoom: Float
    ) {
        val font = Minecraft.getInstance().font
        val lineColor = 0xFF444444.toInt()
        val activeLineColor = 0xFFFF8212.toInt()
        val time = (System.currentTimeMillis() % 10000) / 1000f

        // PASS 1 — draw all INACTIVE connectors. Iteration order across nodes is undefined,
        // so without this pass split, a later node's gray connector could draw over an
        // earlier node's amber active connector. Splitting guarantees amber + flow dots
        // are always on top.
        for ((node, pos) in layout.positions) {
            val sx = (originX + pos.first * zoom).roundToInt()
            val sy = (originY + pos.second * zoom).roundToInt()
            val isStorage = node.source == "storage"
            val isActive = !isStorage && node.nodeId in activeNodeIds
            for (child in node.children) {
                val childPos = layout.positions[child] ?: continue
                val cx = (originX + childPos.first * zoom).roundToInt()
                val cy = (originY + childPos.second * zoom).roundToInt()
                val midY = (sy + 16 + cy) / 2
                val childIsStorage = child.source == "storage"
                val childActive = !childIsStorage && child.nodeId in activeNodeIds
                if (childActive || isActive) continue  // skip — drawn in pass 2
                graphics.fill(cx, cy, cx + 1, midY, lineColor)
                graphics.fill(minOf(sx, cx), midY, maxOf(sx, cx) + 1, midY + 1, lineColor)
                graphics.fill(sx, midY, sx + 1, sy + 16, lineColor)
            }
        }

        // PASS 2 — draw all ACTIVE connectors + animated flow dots on top of pass 1.
        for ((node, pos) in layout.positions) {
            val sx = (originX + pos.first * zoom).roundToInt()
            val sy = (originY + pos.second * zoom).roundToInt()
            val isStorage = node.source == "storage"
            val isActive = !isStorage && node.nodeId in activeNodeIds
            for (child in node.children) {
                val childPos = layout.positions[child] ?: continue
                val cx = (originX + childPos.first * zoom).roundToInt()
                val cy = (originY + childPos.second * zoom).roundToInt()
                val midY = (sy + 16 + cy) / 2
                val childIsStorage = child.source == "storage"
                val childActive = !childIsStorage && child.nodeId in activeNodeIds
                if (!(childActive || isActive)) continue
                graphics.fill(cx, cy, cx + 1, midY, activeLineColor)
                graphics.fill(minOf(sx, cx), midY, maxOf(sx, cx) + 1, midY + 1, activeLineColor)
                graphics.fill(sx, midY, sx + 1, sy + 16, activeLineColor)

                val totalLen = (cy - midY) + kotlin.math.abs(cx - sx) + (midY - sy - 16)
                if (totalLen > 0) {
                    // Constant pixel speed regardless of line length: dots move at
                    // PIXELS_PER_SEC and stay DOT_SPACING_PX apart along the path.
                    // Without this, a long line's dots sprinted while a short line's crawled.
                    val pixelsPerSec = 15f
                    val spacing = 12
                    val dotCount = maxOf(1, totalLen / spacing)
                    val cycle = (time * pixelsPerSec) % totalLen
                    for (d in 0 until dotCount) {
                        val pos2 = ((cycle + d * spacing).toInt()) % totalLen
                        val seg1 = cy - midY
                        val seg2 = kotlin.math.abs(cx - sx)
                        val dotX: Int
                        val dotY: Int
                        if (pos2 < seg1) {
                            dotX = cx; dotY = cy - pos2
                        } else if (pos2 < seg1 + seg2) {
                            val hPos = pos2 - seg1
                            dotX = if (cx < sx) cx + hPos else cx - hPos
                            dotY = midY
                        } else {
                            val vPos = pos2 - seg1 - seg2
                            dotX = sx; dotY = midY - vPos
                        }
                        graphics.pose().pushMatrix()
                        graphics.pose().translate((-7.5f).toFloat(), (-7.5f).toFloat())
                        // 26.1: tint via ARGB on the blit call. 0xFFFFCC44 = rgb(1, 0.8, 0.27).
                        graphics.blit(Icons.ATLAS, dotX, dotY, Icons.GLOW_CIRCLE.u.toFloat(), Icons.GLOW_CIRCLE.v.toFloat(), 16, 16, 256, 256, 0xFFFFCC44.toInt())
                        graphics.pose().popMatrix()
                    }
                }
            }
        }

        // PASS 3 — node icons + labels (drawn on top of all connectors).
        for ((node, pos) in layout.positions) {
            val sx = (originX + pos.first * zoom).roundToInt()
            val sy = (originY + pos.second * zoom).roundToInt()
            val isStorage = node.source == "storage"
            val isActive = !isStorage && node.nodeId in activeNodeIds
            val isCompleted = !isStorage && node.nodeId in completedNodeIds

            // Determine highlight color
            val highlightColor: Int? = when {
                isStorage -> 0xFF55FF55.toInt()    // green (already in storage / leaf)
                isActive -> 0xFFFFAA00.toInt()     // amber (currently being worked on)
                isCompleted -> 0xFF55FF55.toInt()  // green (this branch finished)
                else -> null
            }

            // Item icon with per-pixel glow highlight
            val itemResId = Identifier.tryParse(node.itemId)
            if (itemResId != null) {
                val item = BuiltInRegistries.ITEM.getValue(itemResId)
                if (item != null) {
                    val stack = ItemStack(item)
                    val iconX = sx - 8
                    val iconY = sy

                    // 26.1: the pre-migration silhouette glow ran the item through
                    //  FlatColorItemRenderer, which needs a custom RenderPipeline that
                    //  hasn't been ported yet (see FlatColorItemRenderer for details).
                    //  As a stand-in, paint an amber backdrop behind active nodes so
                    //  the highlight still reads clearly without the shader.
                    if (highlightColor != null) {
                        graphics.fill(
                            iconX - 1, iconY - 1, iconX + 17, iconY + 17,
                            0x80000000.toInt() or (highlightColor and 0xFFFFFF)
                        )
                    }

                    // Render actual item icon on top of the highlight backdrop.
                    graphics.renderItem(stack, iconX, iconY)
                    if (node.count > 1) {
                        graphics.drawString(font, "x${node.count}", sx + 9, sy + 9, WHITE, true)
                    }
                }
            }

            // Status icon overlay
            if (isStorage) {
                graphics.pose().pushMatrix()
                graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                Icons.CHECKMARK.draw(graphics, sx + 6, sy - 4, 10)
                graphics.pose().popMatrix()
            } else if (isActive) {
                graphics.pose().pushMatrix()
                graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                Icons.CRAFTING_IN_PROGRESS.draw(graphics, sx + 6, sy - 4, 10)
                graphics.pose().popMatrix()
            }

            // Source icon below item (half-scale)
            val srcItem: net.minecraft.world.item.Item? = when (node.source) {
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

            // X overlay for missing/no-handler
            if (node.source == "missing" || node.source == "process_no_handler") {
                graphics.pose().pushMatrix()
                graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                Icons.X.draw(graphics, sx - 4, sy + 16, 8)
                graphics.pose().popMatrix()
            }

            // Checkmark for completed (skip root node — it's the final output)
            val isComplete = node.inStorage >= node.count && node.source != "storage"
            if (isComplete && node !== root) {
                graphics.pose().pushMatrix()
                graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                Icons.CHECKMARK.draw(graphics, sx + 6, sy - 2, 8)
                graphics.pose().popMatrix()
            }
        }
    }

    // ========== Interaction ==========

    fun onMouseClicked(mouseX: Double, mouseY: Double): Boolean {
        dragging = true
        lastDragX = mouseX
        lastDragY = mouseY
        return true
    }

    fun onMouseReleased(): Boolean {
        dragging = false
        return true
    }

    fun onMouseDragged(mouseX: Double, mouseY: Double): Boolean {
        if (!dragging) return false
        panX += (mouseX - lastDragX).toFloat()
        panY += (mouseY - lastDragY).toFloat()
        lastDragX = mouseX
        lastDragY = mouseY
        return true
    }

    fun onMouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double, centerX: Float, centerY: Float): Boolean {
        val oldZoom = zoom
        zoom = (zoom * (1f + scrollY.toFloat() * 0.15f)).coerceIn(0.3f, 4f)
        // Zoom toward mouse position
        val mx = (mouseX - centerX - panX).toFloat()
        val my = (mouseY - centerY - panY).toFloat()
        panX -= mx * (zoom / oldZoom - 1f)
        panY -= my * (zoom / oldZoom - 1f)
        return true
    }

    fun reset() {
        panX = 0f
        panY = 0f
        zoom = 1f
        needsAutoFit = true
        lastTree = null
        cachedLayout = null
        dragging = false
        activeNodeIds = emptySet()
        completedNodeIds = emptySet()
    }
}
