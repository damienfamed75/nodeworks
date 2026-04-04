package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.config.ClientConfig
import damien.nodeworks.network.InvTerminalClickPayload
import damien.nodeworks.network.InvTerminalSlotClickPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.SlicedButton
import damien.nodeworks.screen.widget.VirtualSlot
import damien.nodeworks.screen.widget.VirtualSlotGrid
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
 * Client-side screen for the Inventory Terminal.
 * Uses VirtualSlotGrid — zero MC Slot objects. Fully dynamic layout.
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
    private val INV_BOTTOM_PAD = 6

    // ========== Virtual Grids ==========

    private lateinit var networkGrid: VirtualSlotGrid
    private lateinit var playerMainGrid: VirtualSlotGrid
    private lateinit var playerHotbarGrid: VirtualSlotGrid

    // ========== Computed positions ==========

    private var gridX = 0
    private var gridY = 0
    private var searchX = 0
    private var searchY = 0
    private var searchW = 0

    // ========== State ==========

    val repo = InventoryRepo()
    private var scrollOffset = 0
    private var maxScroll = 0
    private var draggingScrollbar = false
    private lateinit var searchBox: EditBox
    private var cachedNetworkColor: Int? = null
    private var itemStackCache = HashMap<String, ItemStack>()

    init {
        computeLayout()
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private fun computeLayout() {
        val gridW = layout.cols * SLOT_SIZE
        val gridH = layout.rows * SLOT_SIZE
        imageWidth = GRID_PAD + 4 + gridW + 2 + SCROLLBAR_W + GRID_PAD + 4
        imageHeight = TOP_BAR_H + SEARCH_PAD + SEARCH_H + SEARCH_PAD + gridH + GRID_PAD + 76 + INV_BOTTOM_PAD
    }

    override fun init() {
        super.init()
        computeLayout()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        // Compute positions
        gridX = leftPos + GRID_PAD + 4
        gridY = topPos + TOP_BAR_H + SEARCH_PAD + SEARCH_H + SEARCH_PAD
        searchX = gridX
        searchY = topPos + TOP_BAR_H + SEARCH_PAD
        searchW = layout.cols * SLOT_SIZE + SCROLLBAR_W + 2

        // Network grid
        networkGrid = VirtualSlotGrid(layout.cols, layout.rows, VirtualSlot.GridType.NETWORK)
        networkGrid.moveTo(gridX, gridY)
        networkGrid.stackProvider = { slot -> getItemStackForNetworkSlot(slot.index) }
        networkGrid.countFormatter = { slot -> getCountForNetworkSlot(slot.index) }

        // Player inventory grids
        val invX = gridX
        val invY = gridY + layout.rows * SLOT_SIZE + GRID_PAD
        playerMainGrid = VirtualSlotGrid(9, 3, VirtualSlot.GridType.PLAYER_MAIN, 0)
        playerMainGrid.moveTo(invX, invY)
        val localPlayer = Minecraft.getInstance().player
        playerMainGrid.stackProvider = { slot ->
            localPlayer?.inventory?.getItem(slot.index + 9) ?: ItemStack.EMPTY
        }

        playerHotbarGrid = VirtualSlotGrid(9, 1, VirtualSlot.GridType.PLAYER_HOTBAR, 0)
        playerHotbarGrid.moveTo(invX, invY + 3 * 18 + INV_GAP)
        playerHotbarGrid.stackProvider = { slot ->
            localPlayer?.inventory?.getItem(slot.index) ?: ItemStack.EMPTY
        }

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
            // Save to server block entity
            val pos = menu.terminalPos
            if (pos != null) {
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.SetLayoutPayload(pos, layout.ordinal)
                )
            }
            // Seamless relayout — just rebuild widgets
            rebuildWidgets()
        })
    }

    // ========== Rendering ==========

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Window frame (stretched for performance — large area)
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

        // Network item grid
        networkGrid.renderBackground(graphics)

        // Scrollbar
        maxScroll = maxOf(0, (repo.viewSize + layout.cols - 1) / layout.cols - layout.rows)
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, maxScroll))
        val scrollbarX = gridX + layout.cols * SLOT_SIZE + 2
        val gridH = layout.rows * SLOT_SIZE
        NineSlice.SCROLLBAR_TRACK.draw(graphics, scrollbarX, gridY, SCROLLBAR_W, gridH)
        if (maxScroll > 0) {
            val totalRows = (repo.viewSize + layout.cols - 1) / layout.cols
            val thumbH = maxOf(12, gridH * layout.rows / totalRows.coerceAtLeast(1))
            val thumbY = gridY + (gridH - thumbH) * scrollOffset / maxScroll
            val thumbSlice = if (draggingScrollbar) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            thumbSlice.draw(graphics, scrollbarX, thumbY, SCROLLBAR_W, thumbH)
        }

        // Player inventory (use direct slot blits for performance)
        val slotU = NineSlice.SLOT.u.toFloat()
        val slotV = NineSlice.SLOT.v.toFloat()
        for (slot in playerMainGrid.slots) {
            graphics.blit(NineSlice.GUI_ATLAS, slot.x, slot.y, slotU, slotV, 18, 18, 256, 256)
        }
        for (slot in playerHotbarGrid.slots) {
            graphics.blit(NineSlice.GUI_ATLAS, slot.x, slot.y, slotU, slotV, 18, 18, 256, 256)
        }
        NineSlice.INVENTORY_BORDER.drawStretched(graphics, playerMainGrid.x - 2, playerMainGrid.y - 2, 9 * 18 + 4, 3 * 18 + 4)
        NineSlice.INVENTORY_BORDER.drawStretched(graphics, playerHotbarGrid.x - 2, playerHotbarGrid.y - 2, 9 * 18 + 4, 18 + 4)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Render network items
        networkGrid.renderItems(graphics, scrollOffset, repo.viewSize)

        // Render player inventory items
        playerMainGrid.renderItems(graphics)
        playerHotbarGrid.renderItems(graphics)

        // Hover highlights (single pass)
        networkGrid.renderHoverHighlight(graphics, mouseX, mouseY, scrollOffset)
        playerMainGrid.renderHoverHighlight(graphics, mouseX, mouseY)
        playerHotbarGrid.renderHoverHighlight(graphics, mouseX, mouseY)

        // Tooltips
        val hoveredNetwork = networkGrid.getSlotAt(mouseX, mouseY, scrollOffset)
        if (hoveredNetwork != null) {
            val entry = repo.getViewEntry(hoveredNetwork.index)
            if (entry != null) {
                val stack = getItemStack(entry.info.itemId)
                if (!stack.isEmpty) {
                    val lines = getTooltipFromItem(Minecraft.getInstance(), stack).toMutableList()
                    lines.add(Component.literal("Network: ${formatCount(entry.info.count)}").withStyle { it.withColor(0xAAAAAA) })
                    graphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY)
                }
            }
        } else {
            // Player inventory tooltip
            val hoveredMain = playerMainGrid.getSlotAt(mouseX, mouseY)
            val hoveredHotbar = playerHotbarGrid.getSlotAt(mouseX, mouseY)
            val hoveredPlayer = hoveredMain ?: hoveredHotbar
            if (hoveredPlayer != null) {
                val invIndex = if (hoveredPlayer.gridType == VirtualSlot.GridType.PLAYER_MAIN) hoveredPlayer.index + 9 else hoveredPlayer.index
                val stack = Minecraft.getInstance().player?.inventory?.getItem(invIndex) ?: ItemStack.EMPTY
                if (!stack.isEmpty) {
                    graphics.renderTooltip(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                }
            }
        }

        // Render carried item on cursor
        val carried = menu.carried
        if (!carried.isEmpty) {
            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 300f)
            graphics.renderItem(carried, mouseX - 8, mouseY - 8)
            graphics.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8)
            graphics.pose().popPose()
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
        val scrollbarX = gridX + layout.cols * SLOT_SIZE + 2
        val gridH = layout.rows * SLOT_SIZE
        if (mx >= scrollbarX && mx < scrollbarX + SCROLLBAR_W && my >= gridY && my < gridY + gridH && maxScroll > 0) {
            draggingScrollbar = true
            return true
        }

        // Network grid click
        val networkSlot = networkGrid.getSlotAt(mx, my, scrollOffset)
        if (networkSlot != null) {
            val entry = repo.getViewEntry(networkSlot.index)
            if (menu.carried.isEmpty && entry != null) {
                val action = when {
                    hasShiftDown() -> 3
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

        // Player inventory click
        val mainSlot = playerMainGrid.getSlotAt(mx, my)
        val hotbarSlot = playerHotbarGrid.getSlotAt(mx, my)
        val playerSlot = mainSlot ?: hotbarSlot
        if (playerSlot != null) {
            val virtualIndex = if (playerSlot.gridType == VirtualSlot.GridType.PLAYER_MAIN) playerSlot.index else playerSlot.index + 27
            val action = when {
                hasShiftDown() -> 2
                button == 1 -> 1
                else -> 0
            }
            PlatformServices.clientNetworking.sendToServer(
                InvTerminalSlotClickPayload(menu.containerId, virtualIndex, action)
            )
            return true
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
            val gridH = layout.rows * SLOT_SIZE
            val totalRows = (repo.viewSize + layout.cols - 1) / layout.cols
            val thumbH = maxOf(12, gridH * layout.rows / totalRows.coerceAtLeast(1))
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

    // ========== Helpers ==========

    private fun getItemStackForNetworkSlot(viewIndex: Int): ItemStack {
        val entry = repo.getViewEntry(viewIndex) ?: return ItemStack.EMPTY
        return getItemStack(entry.info.itemId)
    }

    private val countStringCache = HashMap<Long, String>()

    private fun getCountForNetworkSlot(viewIndex: Int): String? {
        val entry = repo.getViewEntry(viewIndex) ?: return null
        if (entry.info.count <= 1) return null
        return countStringCache.getOrPut(entry.info.count) { formatCount(entry.info.count) }
    }

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
