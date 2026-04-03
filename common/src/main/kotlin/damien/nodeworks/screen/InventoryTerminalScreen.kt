package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.config.ClientConfig
import damien.nodeworks.network.InvTerminalClickPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.SlicedButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Client-side screen for the Inventory Terminal — Phase 1.
 * 9-slice GUI with virtual scrollable item grid, layout toggle, and player inventory.
 */
class InventoryTerminalScreen(
    menu: InventoryTerminalMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<InventoryTerminalMenu>(menu, playerInventory, title) {

    // ========== Layout ==========

    enum class Layout(val cols: Int, val rows: Int, val icon: Icons) {
        SMALL(9, 5, Icons.LAYOUT_SMALL),
        WIDE(9, 7, Icons.LAYOUT_WIDE),
        TALL(13, 5, Icons.LAYOUT_TALL),
        LARGE(13, 7, Icons.LAYOUT_LARGE);

        companion object {
            fun fromName(name: String): Layout = entries.firstOrNull { it.name == name } ?: SMALL
        }
    }

    private var layout = Layout.fromName(ClientConfig.invTerminalLayout)

    // ========== Constants ==========

    private val SLOT_SIZE = 18
    private val TOP_BAR_H = 22
    private val SEARCH_H = 16
    private val SEARCH_PAD = 4
    private val GRID_PAD = 4
    private val INV_GAP = 4
    private val SCROLLBAR_W = 8
    private val SIDE_BTN_W = 20
    private val SIDE_BTN_GAP = 2

    // ========== Computed positions ==========

    private var gridX = 0
    private var gridY = 0
    private var gridW = 0
    private var gridH = 0
    private var searchX = 0
    private var searchY = 0
    private var searchW = 0
    private var invX = 0
    private var invY = 0
    private var scrollbarX = 0

    // ========== State ==========

    val repo = InventoryRepo()
    private var hoveredNetworkEntry: InventoryRepo.RepoEntry? = null
    private var scrollOffset = 0
    private var maxScroll = 0
    private var draggingScrollbar = false
    private lateinit var searchBox: EditBox
    private var cachedNetworkColor: Int? = null
    private var itemStackCache = HashMap<String, ItemStack>()

    // Slot offset within the GUI (relative to leftPos/topPos) — must match menu
    private val SLOT_OFFSET_X = 9  // 1px into the slot border
    private val INV_BOTTOM_PAD = 6

    init {
        computeLayout()
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    /** Compute the player inventory Y offset (relative to topPos) for the current layout. */
    private fun playerInvYOffset(): Int {
        return TOP_BAR_H + SEARCH_PAD + SEARCH_H + SEARCH_PAD + layout.rows * SLOT_SIZE + GRID_PAD
    }

    private fun computeLayout() {
        gridW = layout.cols * SLOT_SIZE
        gridH = layout.rows * SLOT_SIZE
        imageWidth = GRID_PAD + 4 + gridW + 2 + SCROLLBAR_W + GRID_PAD + 4
        imageHeight = playerInvYOffset() + 76 + INV_BOTTOM_PAD
    }

    override fun init() {
        super.init()
        computeLayout()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Compute absolute positions
        gridX = leftPos + GRID_PAD + 4
        gridY = topPos + TOP_BAR_H + SEARCH_PAD + SEARCH_H + SEARCH_PAD
        scrollbarX = gridX + gridW + 2
        searchX = gridX
        searchY = topPos + TOP_BAR_H + SEARCH_PAD
        searchW = gridW + SCROLLBAR_W + 2

        // Reposition player inventory slots to match layout
        repositionSlots()
        invX = gridX
        invY = gridY + gridH + GRID_PAD

        // Search box
        searchBox = EditBox(font, searchX, searchY, searchW, SEARCH_H - 2, Component.literal("Search"))
        searchBox.setMaxLength(100)
        searchBox.setBordered(true)
        searchBox.setTextColor(0xFFE0E0E0.toInt())
        searchBox.setHint(Component.literal("Search items..."))
        searchBox.setResponder { text -> repo.searchText = text }
        if (ClientConfig.invTerminalAutoFocusSearch) {
            searchBox.isFocused = true
        }
        addRenderableWidget(searchBox)

        // Side button: layout toggle
        val sideBtnX = leftPos - SIDE_BTN_W - 4
        addRenderableWidget(SlicedButton.create(
            sideBtnX, topPos + TOP_BAR_H + 2, SIDE_BTN_W, SIDE_BTN_W, "", layout.icon
        ) { _ ->
            layout = Layout.entries[(layout.ordinal + 1) % Layout.entries.size]
            ClientConfig.invTerminalLayout = layout.name
            // Save to server
            val pos = menu.terminalPos
            if (pos != null) {
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.SetLayoutPayload(pos, layout.ordinal)
                )
            }
            // Reposition slots and rebuild
            repositionSlots()
            rebuildWidgets()
        })
    }

    // ========== Rendering ==========

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Window frame
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        // Title bar with network color
        val mc = Minecraft.getInstance()
        val pos = menu.terminalPos
        val networkColor = if (pos != null) {
            val entity = mc.level?.getBlockEntity(pos) as? damien.nodeworks.network.Connectable
            val reachable = damien.nodeworks.render.NodeConnectionRenderer.isReachable(pos)
            if (reachable && entity?.networkId != null) {
                damien.nodeworks.network.NetworkSettingsRegistry.getColor(entity.networkId)
            } else {
                cachedNetworkColor ?: damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(
                    mc.level, pos
                ).also { cachedNetworkColor = it }
            }
        } else -1
        NineSlice.drawTitleBar(graphics, font, title, leftPos, topPos, imageWidth, TOP_BAR_H, networkColor)

        // Item grid background
        NineSlice.drawSlotGrid(graphics, gridX, gridY, layout.cols, layout.rows)

        // Scrollbar
        maxScroll = maxOf(0, (repo.viewSize + layout.cols - 1) / layout.cols - layout.rows)
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, maxScroll))
        NineSlice.SCROLLBAR_TRACK.draw(graphics, scrollbarX, gridY, SCROLLBAR_W, gridH)
        if (maxScroll > 0) {
            val thumbH = maxOf(12, gridH * layout.rows / ((repo.viewSize + layout.cols - 1) / layout.cols).coerceAtLeast(1))
            val thumbY = gridY + (gridH - thumbH) * scrollOffset / maxScroll
            val thumbSlice = if (draggingScrollbar) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            thumbSlice.draw(graphics, scrollbarX, thumbY, SCROLLBAR_W, thumbH)
        }

        // Player inventory
        NineSlice.drawPlayerInventory(graphics, invX, invY, INV_GAP)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Render items in the virtual grid
        var hoveredEntry: InventoryRepo.RepoEntry? = null
        for (row in 0 until layout.rows) {
            for (col in 0 until layout.cols) {
                val viewIndex = (scrollOffset + row) * layout.cols + col
                val entry = repo.getViewEntry(viewIndex) ?: continue

                val sx = gridX + col * SLOT_SIZE + 1
                val sy = gridY + row * SLOT_SIZE + 1

                // Get or create cached ItemStack
                val stack = getItemStack(entry.info.itemId)
                if (stack.isEmpty) continue

                graphics.renderItem(stack, sx, sy)
                val countText = if (entry.info.count > 1) formatCount(entry.info.count) else null
                if (countText != null) {
                    graphics.renderItemDecorations(font, stack, sx, sy, countText)
                }

                // Hover highlight
                if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                    hoveredEntry = entry
                    graphics.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF.toInt())
                }
            }
        }

        this.hoveredNetworkEntry = hoveredEntry
        renderTooltip(graphics, mouseX, mouseY)

        // Network item tooltip
        val hovered = this.hoveredNetworkEntry
        if (hovered != null) {
            val stack = getItemStack(hovered.info.itemId)
            if (!stack.isEmpty) {
                val tooltipLines = getTooltipFromItem(Minecraft.getInstance(), stack).toMutableList()
                tooltipLines.add(Component.literal("Network: ${formatCount(hovered.info.count)}").withStyle { it.withColor(0xAAAAAA) })
                graphics.renderTooltip(font, tooltipLines, java.util.Optional.empty(), mouseX, mouseY)
            }
        }
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default labels
    }

    // ========== Input ==========

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Scrollbar drag
        if (mx >= scrollbarX && mx < scrollbarX + SCROLLBAR_W && my >= gridY && my < gridY + gridH && maxScroll > 0) {
            draggingScrollbar = true
            return true
        }

        // Grid click
        if (mx >= gridX + 1 && mx < gridX + gridW && my >= gridY + 1 && my < gridY + gridH) {
            val col = (mx - gridX - 1) / SLOT_SIZE
            val row = (my - gridY - 1) / SLOT_SIZE
            val viewIndex = (scrollOffset + row) * layout.cols + col
            val entry = repo.getViewEntry(viewIndex)

            if (menu.carried.isEmpty && entry != null) {
                val isShift = hasShiftDown()
                val action = when {
                    isShift -> 3
                    button == 1 -> 2
                    else -> 0
                }
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalClickPayload(menu.containerId, entry.info.itemId, action)
                )
                return true
            } else if (!menu.carried.isEmpty) {
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalClickPayload(menu.containerId, "", 1)
                )
                return true
            }
        }

        // Deselect search if clicking elsewhere
        if (searchBox.isFocused) {
            if (!(mx >= searchX && mx < searchX + searchW && my >= searchY && my < searchY + SEARCH_H)) {
                searchBox.isFocused = false
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar && maxScroll > 0) {
            val thumbH = maxOf(12, gridH * layout.rows / ((repo.viewSize + layout.cols - 1) / layout.cols).coerceAtLeast(1))
            val scrollRange = gridH - thumbH
            if (scrollRange > 0) {
                val relY = (mouseY.toInt() - gridY - thumbH / 2).toFloat() / scrollRange
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
        scrollOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxOf(0, maxScroll))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (searchBox.isFocused) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                searchBox.isFocused = false
                return true
            }
            return searchBox.keyPressed(keyCode, scanCode, modifiers)
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (searchBox.isFocused) {
            return searchBox.charTyped(codePoint, modifiers)
        }
        return super.charTyped(codePoint, modifiers)
    }

    companion object {
        // Reflection fields for moving slot positions (Slot.x/y are final)
        private val SLOT_X_FIELD: java.lang.reflect.Field? by lazy {
            try {
                net.minecraft.world.inventory.Slot::class.java.getDeclaredField("x").also { it.isAccessible = true }
            } catch (_: Exception) { null }
        }
        private val SLOT_Y_FIELD: java.lang.reflect.Field? by lazy {
            try {
                net.minecraft.world.inventory.Slot::class.java.getDeclaredField("y").also { it.isAccessible = true }
            } catch (_: Exception) { null }
        }

        private fun moveSlot(slot: net.minecraft.world.inventory.Slot, x: Int, y: Int) {
            try {
                // Try direct assignment first (works with Fabric access widener)
                slot.x = x
                slot.y = y
            } catch (_: Throwable) {
                // Fallback to reflection (NeoForge)
                SLOT_X_FIELD?.setInt(slot, x)
                SLOT_Y_FIELD?.setInt(slot, y)
            }
        }
    }

    /** Reposition player inventory slots to match current layout. */
    private fun repositionSlots() {
        computeLayout()
        val slotX = GRID_PAD + 4 + 1  // relative to leftPos, +1 for slot border
        val slotY = playerInvYOffset() + 1
        for (i in 0 until 27) {
            moveSlot(menu.slots[i], slotX + (i % 9) * 18, slotY + (i / 9) * 18)
        }
        for (i in 27 until 36) {
            moveSlot(menu.slots[i], slotX + (i - 27) * 18, slotY + 3 * 18 + 4)
        }
    }

    // ========== Helpers ==========

    private fun getItemStack(itemId: String): ItemStack {
        return itemStackCache.getOrPut(itemId) {
            val id = ResourceLocation.tryParse(itemId) ?: return@getOrPut ItemStack.EMPTY
            val item = BuiltInRegistries.ITEM.get(id) ?: return@getOrPut ItemStack.EMPTY
            ItemStack(item)
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000_000 -> String.format("%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
