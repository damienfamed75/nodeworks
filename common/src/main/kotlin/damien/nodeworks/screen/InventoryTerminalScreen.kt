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
    private val SIDE_BTN_GAP = 2
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

    // ========== Crafting ==========

    private var craftingCollapsed = ClientConfig.invTerminalCraftingCollapsed
    private var craftX = 0
    private var craftY = 0
    private val CRAFT_H = 58 // 3×18 = 54 + 4 padding
    private val CRAFT_COLLAPSED_H = 14

    // ========== State ==========

    val repo = InventoryRepo().apply {
        sortMode = InventoryRepo.SortMode.entries.firstOrNull { it.name == ClientConfig.invTerminalSortMode } ?: InventoryRepo.SortMode.ALPHA
        filterMode = InventoryRepo.FilterMode.entries.firstOrNull { it.name == ClientConfig.invTerminalFilterMode } ?: InventoryRepo.FilterMode.BOTH
    }
    private var scrollOffset = 0
    private var maxScroll = 0
    private var draggingScrollbar = false
    private var lastClickTime = 0L
    private var lastClickSlotType = -1
    private var lastClickSlotIndex = -1

    // Craft dialogue state
    private var craftDialogueItemId: String? = null
    private var craftDialogueItemName: String = ""
    private var craftDialogueCount: String = "1"
    private var craftDialogueField: EditBox? = null

    // Slot drag state (works across crafting grid and player inventory)
    private var slotDragButton = -1       // -1 = not dragging, 0 = left, 1 = right
    private var slotDragShift = false     // shift-drag: move items out
    private var slotDragStack = ItemStack.EMPTY // snapshot of carried stack at drag start
    // slotType: 0=crafting, 1=player inventory. index: menu slot index for crafting, virtual index for player
    private data class DragSlotRef(val slotType: Int, val index: Int)
    private val slotDragVisited = mutableListOf<DragSlotRef>()
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
        val craftAreaH = if (craftingCollapsed) CRAFT_COLLAPSED_H else CRAFT_H
        imageWidth = GRID_PAD + 4 + gridW + 2 + SCROLLBAR_W + GRID_PAD + 4
        imageHeight = TOP_BAR_H + SEARCH_PAD + SEARCH_H + SEARCH_PAD + gridH + GRID_PAD + craftAreaH + GRID_PAD + 76 + INV_BOTTOM_PAD
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

        // Crafting area position — centered in window
        val craftAreaH = if (craftingCollapsed) CRAFT_COLLAPSED_H else CRAFT_H
        val craftTotalW = 3 * 18 + 16 + 18 // 3x3 grid + arrow gap + output slot
        craftX = leftPos + (imageWidth - craftTotalW) / 2
        craftY = gridY + layout.rows * SLOT_SIZE + GRID_PAD

        // Player inventory grids — centered in window
        val invTotalW = 9 * 18
        val invX = leftPos + (imageWidth - invTotalW) / 2
        val invY = craftY + craftAreaH + GRID_PAD
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

        // Craft dialogue count field (hidden until dialogue opens)
        val dialogDw = 140
        val dialogDx = leftPos + (imageWidth - dialogDw) / 2
        val dialogDy = topPos + (imageHeight - 60) / 2
        craftDialogueField = EditBox(font, dialogDx + 46, dialogDy + 25, 86, 12, Component.literal("Count"))
        craftDialogueField!!.setMaxLength(4)
        craftDialogueField!!.value = "1"
        craftDialogueField!!.visible = craftDialogueItemId != null
        addRenderableWidget(craftDialogueField!!)

        // Side buttons
        val sideBtnX = leftPos - SIDE_BTN_W - 4
        var sideBtnY = topPos + TOP_BAR_H + 2

        // Layout toggle
        addRenderableWidget(SlicedButton.create(
            sideBtnX, sideBtnY, SIDE_BTN_W, SIDE_BTN_W, "", layout.icon
        ) { _ ->
            layout = Layout.entries[(layout.ordinal + 1) % Layout.entries.size]
            ClientConfig.invTerminalLayout = layout.name
            val pos = menu.terminalPos
            if (pos != null) {
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.SetLayoutPayload(pos, layout.ordinal)
                )
            }
            rebuildWidgets()
        })
        sideBtnY += SIDE_BTN_W + SIDE_BTN_GAP

        // Sort mode toggle
        val sortIcon = when (repo.sortMode) {
            InventoryRepo.SortMode.ALPHA -> Icons.SORT_ALPHA
            InventoryRepo.SortMode.COUNT_DESC -> Icons.SORT_COUNT_DESC
            InventoryRepo.SortMode.COUNT_ASC -> Icons.SORT_COUNT_ASC
        }
        addRenderableWidget(SlicedButton.create(
            sideBtnX, sideBtnY, SIDE_BTN_W, SIDE_BTN_W, "", sortIcon
        ) { _ ->
            repo.sortMode = InventoryRepo.SortMode.entries[(repo.sortMode.ordinal + 1) % InventoryRepo.SortMode.entries.size]
            ClientConfig.invTerminalSortMode = repo.sortMode.name
            scrollOffset = 0
            rebuildWidgets()
        })
        sideBtnY += SIDE_BTN_W + SIDE_BTN_GAP

        // Filter mode toggle
        val filterIcon = when (repo.filterMode) {
            InventoryRepo.FilterMode.STORAGE -> Icons.FILTER_STORAGE
            InventoryRepo.FilterMode.RECIPES -> Icons.FILTER_RECIPES
            InventoryRepo.FilterMode.BOTH -> Icons.FILTER_BOTH
        }
        addRenderableWidget(SlicedButton.create(
            sideBtnX, sideBtnY, SIDE_BTN_W, SIDE_BTN_W, "", filterIcon
        ) { _ ->
            repo.filterMode = InventoryRepo.FilterMode.entries[(repo.filterMode.ordinal + 1) % InventoryRepo.FilterMode.entries.size]
            ClientConfig.invTerminalFilterMode = repo.filterMode.name
            scrollOffset = 0
            rebuildWidgets()
        })
        sideBtnY += SIDE_BTN_W + SIDE_BTN_GAP

        // Auto-focus search toggle
        val autoFocusIcon = if (ClientConfig.invTerminalAutoFocusSearch) Icons.AUTO_FOCUS_ON else Icons.AUTO_FOCUS_OFF
        addRenderableWidget(SlicedButton.create(
            sideBtnX, sideBtnY, SIDE_BTN_W, SIDE_BTN_W, "", autoFocusIcon
        ) { _ ->
            ClientConfig.invTerminalAutoFocusSearch = !ClientConfig.invTerminalAutoFocusSearch
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
        repo.ensureUpdated()
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

        // Crafting area
        if (!craftingCollapsed) {
            // 3x3 crafting grid
            val slotU = NineSlice.SLOT.u.toFloat()
            val slotV = NineSlice.SLOT.v.toFloat()
            for (row in 0..2) {
                for (col in 0..2) {
                    graphics.blit(NineSlice.GUI_ATLAS, craftX + col * 18, craftY + row * 18, slotU, slotV, 18, 18, 256, 256)
                }
            }
            // Arrow
            graphics.drawString(font, "\u2192", craftX + 3 * 18 + 4, craftY + 18 + 4, 0xFFAAAAAA.toInt())
            // Output slot
            graphics.blit(NineSlice.GUI_ATLAS, craftX + 3 * 18 + 16, craftY + 18, slotU, slotV, 18, 18, 256, 256)

            // Render crafting items (from the real MC slots)
            for (i in 0..8) {
                val stack = menu.craftingContainer.getItem(i)
                if (!stack.isEmpty) {
                    val sx = craftX + (i % 3) * 18 + 1
                    val sy = craftY + (i / 3) * 18 + 1
                    graphics.renderItem(stack, sx, sy)
                    if (stack.count > 1) graphics.renderItemDecorations(font, stack, sx, sy)
                }
            }
            // Output
            val resultStack = menu.resultContainer.getItem(0)
            if (!resultStack.isEmpty) {
                val sx = craftX + 3 * 18 + 17
                val sy = craftY + 19
                graphics.renderItem(resultStack, sx, sy)
                if (resultStack.count > 1) graphics.renderItemDecorations(font, resultStack, sx, sy)
            }
        }

        // Crafting collapse toggle — to the left of the crafting area
        val collapseIcon = if (craftingCollapsed) Icons.EXPAND_IDLE else Icons.COLLAPSE_IDLE
        collapseIcon.draw(graphics, craftX - 20, craftY + 1)

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

        // Ensure repo view is up-to-date before reading
        repo.ensureUpdated()

        // Render network items
        networkGrid.renderItems(graphics, scrollOffset, repo.viewSize)

        // Craftable overlays
        val altHeld = hasAltDown()
        for ((i, slot) in networkGrid.slots.withIndex()) {
            val viewIndex = scrollOffset * layout.cols + i
            val entry = repo.getViewEntry(viewIndex) ?: continue
            if (entry.info.isCraftable) {
                val ix = slot.x + 1
                val iy = slot.y + 1
                if (entry.info.count == 0L) {
                    // Ghost item — dim overlay
                    graphics.fill(ix, iy, ix + 16, iy + 16, 0x80000000.toInt())
                }
                if (altHeld) {
                    // Show + icon on craftable items
                    Icons.CRAFT_PLUS.draw(graphics, ix + 8, iy - 2, 8)
                }
            }
        }

        // Render player inventory items
        playerMainGrid.renderItems(graphics)
        playerHotbarGrid.renderItems(graphics)

        // Set hoveredSlot for JEI R/U key support
        val networkHover = networkGrid.getSlotAt(mouseX, mouseY, scrollOffset)
        if (networkHover != null) {
            val entry = repo.getViewEntry(networkHover.index)
            if (entry != null) {
                val id = net.minecraft.resources.ResourceLocation.tryParse(entry.info.itemId)
                if (id != null) {
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)
                    if (item != null) {
                        val tempContainer = net.minecraft.world.SimpleContainer(1)
                        tempContainer.setItem(0, ItemStack(item))
                        hoveredSlot = net.minecraft.world.inventory.Slot(tempContainer, 0, networkHover.x - leftPos, networkHover.y - topPos)
                    }
                }
            }
        } else {
            // Check crafting grid
            val craftSlot = getCraftSlotAt(mouseX, mouseY)
            if (craftSlot >= 0) {
                val slot = menu.slots[InventoryTerminalMenu.CRAFT_INPUT_START + craftSlot]
                hoveredSlot = slot
            } else if (isCraftOutputAt(mouseX, mouseY)) {
                hoveredSlot = menu.slots[InventoryTerminalMenu.CRAFT_OUTPUT_SLOT]
            } else {
                // Check player inventory
                val mainSlot = playerMainGrid.getSlotAt(mouseX, mouseY)
                val hotbarSlot = playerHotbarGrid.getSlotAt(mouseX, mouseY)
                val playerSlot = mainSlot ?: hotbarSlot
                if (playerSlot != null) {
                    val invIndex = if (playerSlot.gridType == VirtualSlot.GridType.PLAYER_MAIN) playerSlot.index + 9 else playerSlot.index
                    val stack = Minecraft.getInstance().player?.inventory?.getItem(invIndex) ?: ItemStack.EMPTY
                    if (!stack.isEmpty) {
                        val tempContainer = net.minecraft.world.SimpleContainer(1)
                        tempContainer.setItem(0, stack.copy())
                        hoveredSlot = net.minecraft.world.inventory.Slot(tempContainer, 0, playerSlot.x - leftPos, playerSlot.y - topPos)
                    } else {
                        hoveredSlot = null
                    }
                } else {
                    hoveredSlot = null
                }
            }
        }

        // Hover highlights (single pass)
        networkGrid.renderHoverHighlight(graphics, mouseX, mouseY, scrollOffset)
        playerMainGrid.renderHoverHighlight(graphics, mouseX, mouseY)
        playerHotbarGrid.renderHoverHighlight(graphics, mouseX, mouseY)

        // Left-drag preview: show distributed items
        if (slotDragButton == 0 && !slotDragShift && slotDragVisited.size > 1 && !slotDragStack.isEmpty) {
            val total = slotDragStack.count
            val perSlot = total / slotDragVisited.size
            val remainder = total % slotDragVisited.size
            for ((idx, ref) in slotDragVisited.withIndex()) {
                val amount = perSlot + if (idx < remainder) 1 else 0
                if (amount <= 0) continue
                val previewStack = slotDragStack.copyWithCount(amount)

                if (ref.slotType == 0) {
                    // Crafting slot
                    val i = ref.index - InventoryTerminalMenu.CRAFT_INPUT_START
                    if (i < 0 || i > 8) continue
                    val sx = craftX + (i % 3) * 18 + 1
                    val sy = craftY + (i / 3) * 18 + 1
                    graphics.renderItem(previewStack, sx, sy)
                    graphics.renderItemDecorations(font, previewStack, sx, sy)
                } else {
                    // Player inventory slot
                    val virtualIdx = ref.index
                    val grid = if (virtualIdx < 27) playerMainGrid else playerHotbarGrid
                    val gridIdx = if (virtualIdx < 27) virtualIdx else virtualIdx - 27
                    if (gridIdx < grid.slots.size) {
                        val slot = grid.slots[gridIdx]
                        graphics.renderItem(previewStack, slot.x + 1, slot.y + 1)
                        graphics.renderItemDecorations(font, previewStack, slot.x + 1, slot.y + 1)
                    }
                }
            }
        }

        // Crafting slot hover highlights
        if (!craftingCollapsed) {
            val hoveredCraft = getCraftSlotAt(mouseX, mouseY)
            if (hoveredCraft >= 0) {
                val sx = craftX + (hoveredCraft % 3) * 18 + 1
                val sy = craftY + (hoveredCraft / 3) * 18 + 1
                graphics.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF.toInt())
            }
            if (isCraftOutputAt(mouseX, mouseY)) {
                val ox = craftX + 3 * 18 + 17
                val oy = craftY + 19
                graphics.fill(ox, oy, ox + 16, oy + 16, 0x80FFFFFF.toInt())
            }
        }

        // Tooltips
        val hoveredNetwork = networkGrid.getSlotAt(mouseX, mouseY, scrollOffset)
        if (hoveredNetwork != null) {
            val entry = repo.getViewEntry(hoveredNetwork.index)
            if (entry != null) {
                val stack = getItemStack(entry.info.itemId)
                if (!stack.isEmpty) {
                    val lines = getTooltipFromItem(Minecraft.getInstance(), stack).toMutableList()
                    lines.add(Component.literal("Network: ${formatCount(entry.info.count)}").withStyle { it.withColor(0xAAAAAA) })
                    if (entry.info.isCraftable) {
                        lines.add(Component.literal("Craftable").withStyle { it.withColor(0x55FF55) })
                    }
                    graphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY)
                }
            }
        } else {
            // Crafting slot tooltip
            var craftTooltipShown = false
            if (!craftingCollapsed) {
                val hoveredCraft = getCraftSlotAt(mouseX, mouseY)
                if (hoveredCraft >= 0) {
                    val stack = menu.craftingContainer.getItem(hoveredCraft)
                    if (!stack.isEmpty) {
                        graphics.renderTooltip(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                        craftTooltipShown = true
                    }
                } else if (isCraftOutputAt(mouseX, mouseY)) {
                    val stack = menu.resultContainer.getItem(0)
                    if (!stack.isEmpty) {
                        graphics.renderTooltip(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                        craftTooltipShown = true
                    }
                }
            }

            // Player inventory tooltip
            if (!craftTooltipShown) {
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
        }

        // Craft dialogue overlay
        if (craftDialogueItemId != null) {
            val dw = 140
            val dh = 60
            val dx = leftPos + (imageWidth - dw) / 2
            val dy = topPos + (imageHeight - dh) / 2

            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 400f)
            NineSlice.PANEL_INSET.draw(graphics, dx, dy, dw, dh)
            NineSlice.CONTENT_BORDER.draw(graphics, dx, dy, dw, dh)

            // Item icon + name
            val stack = getItemStack(craftDialogueItemId!!)
            if (!stack.isEmpty) {
                graphics.renderItem(stack, dx + 6, dy + 6)
            }
            graphics.drawString(font, craftDialogueItemName, dx + 26, dy + 10, 0xFFFFFFFF.toInt())

            // Count label
            graphics.drawString(font, "Count:", dx + 6, dy + 28, 0xFFAAAAAA.toInt())

            // Craft / Cancel buttons
            val craftBtnX = dx + 6
            val cancelBtnX = dx + dw / 2 + 2
            val btnY = dy + dh - 18
            val btnW = dw / 2 - 8
            val craftHover = mouseX >= craftBtnX && mouseX < craftBtnX + btnW && mouseY >= btnY && mouseY < btnY + 14
            val cancelHover = mouseX >= cancelBtnX && mouseX < cancelBtnX + btnW && mouseY >= btnY && mouseY < btnY + 14
            val craftSlice = if (craftHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
            val cancelSlice = if (cancelHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
            craftSlice.draw(graphics, craftBtnX, btnY, btnW, 14)
            cancelSlice.draw(graphics, cancelBtnX, btnY, btnW, 14)
            val craftColor = if (craftHover) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
            val cancelColor = if (cancelHover) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            graphics.drawString(font, "Craft", craftBtnX + (btnW - font.width("Craft")) / 2, btnY + 3, craftColor)
            graphics.drawString(font, "Cancel", cancelBtnX + (btnW - font.width("Cancel")) / 2, btnY + 3, cancelColor)

            graphics.pose().popPose()
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

        // Craft dialogue buttons
        if (craftDialogueItemId != null) {
            val dw = 140
            val dh = 60
            val dx = leftPos + (imageWidth - dw) / 2
            val dy = topPos + (imageHeight - dh) / 2
            val craftBtnX = dx + 6
            val cancelBtnX = dx + dw / 2 + 2
            val btnY = dy + dh - 18
            val btnW = dw / 2 - 8

            if (mx >= craftBtnX && mx < craftBtnX + btnW && my >= btnY && my < btnY + 14) {
                // Craft button
                val count = craftDialogueField?.value?.toIntOrNull() ?: 1
                if (count > 0) {
                    PlatformServices.clientNetworking.sendToServer(
                        damien.nodeworks.network.InvTerminalCraftPayload(menu.containerId, craftDialogueItemId!!, count)
                    )
                }
                craftDialogueItemId = null
                craftDialogueField?.visible = false
                return true
            }
            if (mx >= cancelBtnX && mx < cancelBtnX + btnW && my >= btnY && my < btnY + 14) {
                // Cancel button
                craftDialogueItemId = null
                craftDialogueField?.visible = false
                return true
            }
            // Click inside dialogue — consume
            if (mx >= dx && mx < dx + dw && my >= dy && my < dy + dh) {
                return super.mouseClicked(mouseX, mouseY, button)
            }
            // Click outside — close
            craftDialogueItemId = null
            craftDialogueField?.visible = false
            return true
        }

        // Crafting collapse toggle
        if (mx >= craftX - 20 && mx < craftX - 4 && my >= craftY + 1 && my < craftY + 17) {
            craftingCollapsed = !craftingCollapsed
            ClientConfig.invTerminalCraftingCollapsed = craftingCollapsed
            rebuildWidgets()
            return true
        }

        // Double-click collect check
        val now = net.minecraft.Util.getMillis()
        fun checkDoubleClick(slotType: Int, slotIndex: Int, itemId: String): Boolean {
            val isDoubleClick = button == 0
                && now - lastClickTime < 400
                && lastClickSlotType == slotType
                && lastClickSlotIndex == slotIndex
                && !menu.carried.isEmpty  // must have picked up from first click
            lastClickTime = now
            lastClickSlotType = slotType
            lastClickSlotIndex = slotIndex
            if (isDoubleClick) {
                lastClickTime = 0 // prevent triple-click
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.InvTerminalCollectPayload(menu.containerId, itemId)
                )
                return true
            }
            return false
        }

        // Crafting grid click
        if (!craftingCollapsed) {
            val craftSlot = getCraftSlotAt(mx, my)
            if (craftSlot >= 0) {
                val slotIdx = InventoryTerminalMenu.CRAFT_INPUT_START + craftSlot
                // Double-click collect — works with item on cursor (from first click)
                val collectItem = if (!menu.carried.isEmpty) menu.carried else menu.craftingContainer.getItem(craftSlot)
                if (!collectItem.isEmpty) {
                    val craftItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(collectItem.item)?.toString() ?: ""
                    if (checkDoubleClick(0, craftSlot, craftItemId)) return true
                }
                if (hasShiftDown()) {
                    // Shift-click: move to player inventory
                    slotClicked(menu.slots[slotIdx], slotIdx, button, net.minecraft.world.inventory.ClickType.QUICK_MOVE)
                    // Start shift-drag for dragging over more slots
                    slotDragButton = button
                    slotDragShift = true
                    slotDragVisited.clear()
                    slotDragVisited.add(DragSlotRef(0, slotIdx))
                } else if (!menu.carried.isEmpty && button == 1) {
                    // Right-click: place one item, start right-drag
                    slotClicked(menu.slots[slotIdx], slotIdx, 1, net.minecraft.world.inventory.ClickType.PICKUP)
                    slotDragButton = 1
                    slotDragShift = false
                    slotDragVisited.clear()
                    slotDragVisited.add(DragSlotRef(0, slotIdx))
                } else if (!menu.carried.isEmpty && button == 0) {
                    // Left-click: start left-drag (defer action to release)
                    slotDragButton = 0
                    slotDragShift = false
                    slotDragStack = menu.carried.copy()
                    slotDragVisited.clear()
                    slotDragVisited.add(DragSlotRef(0, slotIdx))
                } else {
                    // Normal click (pickup or swap)
                    slotClicked(menu.slots[slotIdx], slotIdx, button, net.minecraft.world.inventory.ClickType.PICKUP)
                }
                return true
            }
            // Output slot
            if (isCraftOutputAt(mx, my)) {
                val clickType = if (hasShiftDown()) net.minecraft.world.inventory.ClickType.QUICK_MOVE else net.minecraft.world.inventory.ClickType.PICKUP
                slotClicked(menu.slots[InventoryTerminalMenu.CRAFT_OUTPUT_SLOT], InventoryTerminalMenu.CRAFT_OUTPUT_SLOT, button, clickType)
                return true
            }
        }

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
            if (entry != null && entry.info.isCraftable && (hasAltDown() || entry.info.count == 0L)) {
                // Alt+click or click on zero-count craftable: open craft dialogue
                craftDialogueItemId = entry.info.itemId
                craftDialogueItemName = entry.info.name
                craftDialogueField?.value = "1"
                craftDialogueField?.visible = true
                craftDialogueField?.isFocused = true
                return true
            }
            if (menu.carried.isEmpty && entry != null && entry.info.count > 0) {
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
            val invIndex = if (virtualIndex < 27) virtualIndex + 9 else virtualIndex - 27
            // Double-click collect — works with item on cursor (from first click)
            val collectItem = if (!menu.carried.isEmpty) menu.carried else (Minecraft.getInstance().player?.inventory?.getItem(invIndex) ?: ItemStack.EMPTY)
            if (!collectItem.isEmpty) {
                val playerItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(collectItem.item)?.toString() ?: ""
                if (checkDoubleClick(1, virtualIndex, playerItemId)) return true
            }
            if (hasShiftDown()) {
                // Shift-click: insert into network
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalSlotClickPayload(menu.containerId, virtualIndex, 2)
                )
                // Start shift-drag
                slotDragButton = button
                slotDragShift = true
                slotDragVisited.clear()
                slotDragVisited.add(DragSlotRef(1, virtualIndex))
            } else if (!menu.carried.isEmpty && button == 0) {
                // Left-click with items: start left-drag
                slotDragButton = 0
                slotDragShift = false
                slotDragStack = menu.carried.copy()
                slotDragVisited.clear()
                slotDragVisited.add(DragSlotRef(1, virtualIndex))
            } else if (!menu.carried.isEmpty && button == 1) {
                // Right-click with items: place one, start right-drag
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalSlotClickPayload(menu.containerId, virtualIndex, 1)
                )
                slotDragButton = 1
                slotDragShift = false
                slotDragVisited.clear()
                slotDragVisited.add(DragSlotRef(1, virtualIndex))
            } else {
                // Normal click
                val action = if (button == 1) 1 else 0
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalSlotClickPayload(menu.containerId, virtualIndex, action)
                )
            }
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
        // Slot drag across crafting grid and player inventory
        if (slotDragButton >= 0) {
            val mx = mouseX.toInt()
            val my = mouseY.toInt()

            // Check crafting grid
            if (!craftingCollapsed) {
                val craftSlot = getCraftSlotAt(mx, my)
                if (craftSlot >= 0) {
                    val slotIdx = InventoryTerminalMenu.CRAFT_INPUT_START + craftSlot
                    val ref = DragSlotRef(0, slotIdx)
                    if (ref !in slotDragVisited) {
                        slotDragVisited.add(ref)
                        if (slotDragShift) {
                            slotClicked(menu.slots[slotIdx], slotIdx, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE)
                        } else if (slotDragButton == 1 && !menu.carried.isEmpty) {
                            slotClicked(menu.slots[slotIdx], slotIdx, 1, net.minecraft.world.inventory.ClickType.PICKUP)
                        }
                    }
                    return true
                }
            }

            // Check player inventory
            val mainSlot = playerMainGrid.getSlotAt(mx, my)
            val hotbarSlot = playerHotbarGrid.getSlotAt(mx, my)
            val playerSlot = mainSlot ?: hotbarSlot
            if (playerSlot != null) {
                val virtualIndex = if (playerSlot.gridType == VirtualSlot.GridType.PLAYER_MAIN) playerSlot.index else playerSlot.index + 27
                val ref = DragSlotRef(1, virtualIndex)
                if (ref !in slotDragVisited) {
                    slotDragVisited.add(ref)
                    if (slotDragShift) {
                        PlatformServices.clientNetworking.sendToServer(
                            InvTerminalSlotClickPayload(menu.containerId, virtualIndex, 2)
                        )
                    } else if (slotDragButton == 1 && !menu.carried.isEmpty) {
                        PlatformServices.clientNetworking.sendToServer(
                            InvTerminalSlotClickPayload(menu.containerId, virtualIndex, 1)
                        )
                    }
                }
                return true
            }

            return true
        }

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
        // Finish left-drag distribute
        if (slotDragButton == 0 && !slotDragShift && slotDragVisited.isNotEmpty() && !slotDragStack.isEmpty) {
            if (slotDragVisited.size == 1) {
                // Single click — place all
                val ref = slotDragVisited.first()
                if (ref.slotType == 0) {
                    slotClicked(menu.slots[ref.index], ref.index, 0, net.minecraft.world.inventory.ClickType.PICKUP)
                } else {
                    // Player inventory: send via packet
                    PlatformServices.clientNetworking.sendToServer(
                        damien.nodeworks.network.InvTerminalSlotClickPayload(menu.containerId, ref.index, 0)
                    )
                }
            } else {
                // Multi-slot drag — send distribute packet to server
                val slotType = slotDragVisited.first().slotType
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.InvTerminalDistributePayload(
                        menu.containerId, slotType,
                        slotDragVisited.map { it.index }
                    )
                )
            }
        }
        slotDragButton = -1
        slotDragShift = false
        slotDragStack = ItemStack.EMPTY
        slotDragVisited.clear()

        draggingScrollbar = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scrollOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxOf(0, maxScroll))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Craft dialogue keys
        if (craftDialogueItemId != null) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                craftDialogueItemId = null
                craftDialogueField?.visible = false
                return true
            }
            if (keyCode == InputConstants.KEY_RETURN) {
                val count = craftDialogueField?.value?.toIntOrNull() ?: 1
                if (count > 0) {
                    PlatformServices.clientNetworking.sendToServer(
                        damien.nodeworks.network.InvTerminalCraftPayload(menu.containerId, craftDialogueItemId!!, count)
                    )
                }
                craftDialogueItemId = null
                craftDialogueField?.visible = false
                return true
            }
            // Let the field handle the input
            return craftDialogueField?.keyPressed(keyCode, scanCode, modifiers) ?: false
        }

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
        if (craftDialogueItemId != null && craftDialogueField?.isFocused == true) {
            // Only allow digits
            if (codePoint.isDigit()) {
                return craftDialogueField?.charTyped(codePoint, modifiers) ?: false
            }
            return true
        }
        if (searchBox.isFocused) {
            return searchBox.charTyped(codePoint, modifiers)
        }
        return super.charTyped(codePoint, modifiers)
    }

    /** For JEI: get the item ID of the hovered network grid item. */
    fun getHoveredNetworkItemId(mx: Int, my: Int): String? {
        repo.ensureUpdated()
        val slot = networkGrid.getSlotAt(mx, my, scrollOffset) ?: return null
        val entry = repo.getViewEntry(slot.index) ?: return null
        return entry.info.itemId
    }

    // ========== Slot Helpers ==========

    /** Find the crafting input slot index (0-8) at the mouse position, or -1. */
    private fun getCraftSlotAt(mx: Int, my: Int): Int {
        if (craftingCollapsed) return -1
        // Full 18x18 hit area per slot (no dead zones between slots)
        val relX = mx - craftX
        val relY = my - craftY
        if (relX < 0 || relX >= 3 * 18 || relY < 0 || relY >= 3 * 18) return -1
        val col = relX / 18
        val row = relY / 18
        return row * 3 + col
    }

    /** Find the crafting output slot at mouse position. */
    private fun isCraftOutputAt(mx: Int, my: Int): Boolean {
        if (craftingCollapsed) return false
        val ox = craftX + 3 * 18 + 16
        val oy = craftY + 18
        return mx >= ox && mx < ox + 18 && my >= oy && my < oy + 18
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
