package damien.nodeworks.screen.widget

import damien.nodeworks.screen.Icons
import damien.nodeworks.script.CraftTreeBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
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

    /** Item IDs currently being processed — highlighted in the graph. */
    var activeSteps: Set<String> = emptySet()

    private var lastTree: Any? = null
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
        graphics: GuiGraphics,
        tree: CraftTreeBuilder.CraftTreeNode?,
        x: Int, y: Int, w: Int, h: Int
    ) {
        if (tree == null) {
            val font = Minecraft.getInstance().font
            graphics.drawString(font, "No active craft", x + w / 2 - font.width("No active craft") / 2, y + h / 2 - 4, 0xFF555555.toInt())
            return
        }

        // Cache layout, recompute on tree change
        if (tree !== lastTree) {
            lastTree = tree
            cachedLayout = layoutTree(tree)
            needsAutoFit = true
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
        graphics: GuiGraphics,
        root: CraftTreeBuilder.CraftTreeNode,
        layout: TreeLayout,
        originX: Float, originY: Float,
        zoom: Float
    ) {
        val font = Minecraft.getInstance().font
        val lineColor = 0xFF444444.toInt()
        val activeLineColor = 0xFFFF8212.toInt()
        val time = (System.currentTimeMillis() % 10000) / 1000f

        for ((node, pos) in layout.positions) {
            val sx = (originX + pos.first * zoom).roundToInt()
            val sy = (originY + pos.second * zoom).roundToInt()
            val isStorage = node.source == "storage"
            val isActive = !isStorage && node.itemId in activeSteps

            // Draw L-shaped connectors to children + animated flow dots
            for (child in node.children) {
                val childPos = layout.positions[child] ?: continue
                val cx = (originX + childPos.first * zoom).roundToInt()
                val cy = (originY + childPos.second * zoom).roundToInt()
                val midY = (sy + 16 + cy) / 2
                val childIsStorage = child.source == "storage"
                val childActive = !childIsStorage && child.itemId in activeSteps
                val connColor = if (childActive || isActive) activeLineColor else lineColor

                // L-shape: child bottom → midY → parent top
                graphics.fill(cx, cy, cx + 1, midY, connColor)       // vertical up from child
                graphics.fill(minOf(sx, cx), midY, maxOf(sx, cx) + 1, midY + 1, connColor) // horizontal
                graphics.fill(sx, midY, sx + 1, sy + 16, connColor)  // vertical up to parent

                // Animated flow dots (move upward from child to parent)
                if (childActive || isActive) {
                    val totalLen = (cy - midY) + kotlin.math.abs(cx - sx) + (midY - sy - 16)
                    if (totalLen > 0) {
                        val dotCount = maxOf(1, totalLen / 12)
                        for (d in 0 until dotCount) {
                            val t = ((time * 0.25f + d.toFloat() / dotCount) % 1f)
                            val pos2 = (t * totalLen).toInt()
                            val seg1 = cy - midY  // vertical from child up
                            val seg2 = kotlin.math.abs(cx - sx)  // horizontal
                            val dotX: Int
                            val dotY: Int
                            if (pos2 < seg1) {
                                // On vertical segment from child
                                dotX = cx
                                dotY = cy - pos2
                            } else if (pos2 < seg1 + seg2) {
                                // On horizontal segment
                                val hPos = pos2 - seg1
                                dotX = if (cx < sx) cx + hPos else cx - hPos
                                dotY = midY
                            } else {
                                // On vertical segment to parent
                                val vPos = pos2 - seg1 - seg2
                                dotX = sx
                                dotY = midY - vPos
                            }
                            graphics.pose().pushPose()
                            graphics.pose().translate(-7.5f, -7.5f, 0f)
                            com.mojang.blaze3d.systems.RenderSystem.enableBlend()
                            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
                            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 0.8f, 0.27f, 1f)
                            graphics.blit(Icons.ATLAS, dotX, dotY, Icons.GLOW_CIRCLE.u.toFloat(), Icons.GLOW_CIRCLE.v.toFloat(), 16, 16, 256, 256)
                            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                            com.mojang.blaze3d.systems.RenderSystem.disableBlend()
                            graphics.pose().popPose()
                        }
                    }
                }
            }

            // Determine highlight color
            val highlightColor: Int? = when {
                isStorage -> 0xFF55FF55.toInt()  // green
                isActive -> 0xFFFFAA00.toInt()   // amber
                else -> null
            }

            // Item icon with per-pixel glow highlight
            val itemResId = ResourceLocation.tryParse(node.itemId)
            if (itemResId != null) {
                val item = BuiltInRegistries.ITEM.get(itemResId)
                if (item != null) {
                    val stack = ItemStack(item)
                    val iconX = sx - 8
                    val iconY = sy

                    // Render flat-color silhouette glow behind the item (1px offsets)
                    if (highlightColor != null) {
                        val offsets = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
                        for ((ox, oy) in offsets) {
                            damien.nodeworks.render.FlatColorItemRenderer.renderFlatColorItem(
                                graphics, stack, iconX + ox, iconY + oy, highlightColor, 200
                            )
                        }
                    }

                    // Render actual item icon
                    graphics.renderItem(stack, iconX, iconY)
                    if (node.count > 1) {
                        graphics.drawString(font, "x${node.count}", sx + 9, sy + 9, WHITE, true)
                    }
                }
            }

            // Status icon overlay
            if (isStorage) {
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, 300f)
                Icons.CHECKMARK.draw(graphics, sx + 6, sy - 4, 10)
                graphics.pose().popPose()
            } else if (isActive) {
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, 300f)
                Icons.CRAFTING_IN_PROGRESS.draw(graphics, sx + 6, sy - 4, 10)
                graphics.pose().popPose()
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
                graphics.pose().pushPose()
                graphics.pose().translate((sx - 4).toFloat(), (sy + 16).toFloat(), 0f)
                graphics.pose().scale(0.5f, 0.5f, 1f)
                graphics.renderItem(ItemStack(srcItem), 0, 0)
                graphics.pose().popPose()
            }

            // X overlay for missing/no-handler
            if (node.source == "missing" || node.source == "process_no_handler") {
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, 300f)
                Icons.X.draw(graphics, sx - 4, sy + 16, 8)
                graphics.pose().popPose()
            }

            // Checkmark for completed (skip root node — it's the final output)
            val isComplete = node.inStorage >= node.count && node.source != "storage"
            if (isComplete && node !== root) {
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, 300f)
                Icons.CHECKMARK.draw(graphics, sx + 6, sy - 2, 8)
                graphics.pose().popPose()
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
        activeSteps = emptySet()
    }
}
