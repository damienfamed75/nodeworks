package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.config.ClientConfig
import damien.nodeworks.network.InvTerminalClickPayload
import damien.nodeworks.network.InvTerminalSlotClickPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.SlicedButton
import damien.nodeworks.screen.widget.VirtualSlot
import damien.nodeworks.screen.widget.VirtualSlotGrid
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
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Client-side screen for the Inventory Terminal.
 * Uses VirtualSlotGrid, zero MC Slot objects. Fully dynamic layout.
 */
class InventoryTerminalScreen(
    menu: InventoryTerminalMenu,
    playerInventory: Inventory,
    title: Component
// TODO MC 26.1.2: ACS imageWidth/imageHeight are now final. Using a
// fixed default derived from the pre-migration "small" layout, the dynamic
// per-layout resize assignments have been commented out in init() below
// until we can restore mutability (AT or custom size fields).
) : AbstractContainerScreen<InventoryTerminalMenu>(menu, playerInventory, title, 230, 230) {

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

    /** After clicking Craft, wait this many ms for a server error before assuming
     *  success and closing the dialog. Generous enough for laggy connections,
     *  short enough that players don't notice. */
    private val CRAFT_CLOSE_DELAY_MS = 500L
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
        kindMode = InventoryRepo.KindMode.entries.firstOrNull { it.name == ClientConfig.invTerminalKindMode } ?: InventoryRepo.KindMode.BOTH
    }
    private var scrollOffset = 0
    private var maxScroll = 0
    private var draggingScrollbar = false
    private var lastClickTime = 0L
    private var lastClickSlotType = -1
    private var lastClickSlotIndex = -1

    // Server-driven craft queue for the reserved row
    data class QueueSlot(
        val id: Int, val itemId: String, val name: String,
        val totalRequested: Int, val readyCount: Int, val availableCount: Int,
        val isComplete: Boolean
    )
    var craftQueue: List<QueueSlot> = emptyList()
    private val MAX_PINNED get() = layout.cols

    // Craft dialogue state
    private var craftDialogueItemId: String? = null
    private var craftDialogueItemName: String = ""
    private var craftDialogueCount: String = "1"
    private var craftDialogueField: EditBox? = null

    // Craft error returned from the server (e.g. CPU buffer cannot fit the job)
    private var craftError: String? = null

    /** Remembered details of the last craft attempt, used to re-open the dialog
     *  if the server sends a late error after the dialog has already auto-closed. */
    private var lastAttemptItemId: String? = null
    private var lastAttemptItemName: String = ""
    private var lastAttemptCount: Int = 1

    /** Absolute ms deadline after which the dialog auto-closes (treating no error
     *  arriving as success). 0 = no pending close scheduled. */
    private var craftCloseAfterMs: Long = 0

    /** Called by the client packet handler when the server rejects a craft request.
     *  Re-opens the dialog if it has already been auto-closed. */
    fun setCraftError(message: String) {
        craftError = message
        craftCloseAfterMs = 0  // cancel any pending auto-close
        if (craftDialogueItemId == null && lastAttemptItemId != null) {
            craftDialogueItemId = lastAttemptItemId
            craftDialogueItemName = lastAttemptItemName
            craftDialogueField?.value = lastAttemptCount.toString()
            craftDialogueField?.visible = true
        }
    }

    // Slot drag state (works across crafting grid and player inventory).
    // slotType: 0 = crafting, 1 = player inventory. index: menu slot index
    // for crafting, virtual index (0-26 main, 27-35 hotbar) for player.
    private data class DragSlotRef(val slotType: Int, val index: Int)
    private var slotDragButton = -1                  // -1 = not dragging, 0 = left, 1 = right
    private var slotDragShift = false                // shift-drag: move items out
    private var slotDragStack = ItemStack.EMPTY      // carried snapshot at drag start
    private val slotDragVisited = mutableListOf<DragSlotRef>()  // dedup for non-shift drags
    private var slotDragLastHovered: DragSlotRef? = null         // slot-entry tracker for shift-drag
    private lateinit var searchBox: EditBox
    private var cachedNetworkColor: Int? = null
    private var itemStackCache = HashMap<String, ItemStack>()
    // Reused container for JEI hoveredSlot, avoids allocating new SimpleContainer every frame
    private val hoverContainer = net.minecraft.world.SimpleContainer(1)

    // ========== Crystal slot (Handheld only) ==========
    // Absolute screen coordinates of the Link Crystal slot, set in init() when
    // menu.hasCrystalSlot is true. Placed at the right end of the title bar,
    // vertically centered inside its 22px height. Painted last so it draws on
    // top of the 9-slice TOP_BAR background and any title text it overlaps.
    private var crystalSlotX = 0
    private var crystalSlotY = 0
    private val CRYSTAL_SLOT_SIZE = 18

    init {
        computeLayout()
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private var syncedAutoPull = false

    private fun computeLayout() {
        // 26.1: imageWidth/imageHeight are `protected final` at compile time in common/
        //  (the AT that drops `final` applies at runtime only). AcsCompat writes through
        //  to the runtime-mutable fields via reflection.
        val gridW = layout.cols * SLOT_SIZE
        val gridH = layout.rows * SLOT_SIZE
        val craftAreaH = if (craftingCollapsed) CRAFT_COLLAPSED_H else CRAFT_H
        val w = GRID_PAD + 4 + gridW + 2 + SCROLLBAR_W + GRID_PAD + 4
        val h = TOP_BAR_H + SEARCH_PAD + SEARCH_H + SEARCH_PAD + gridH + GRID_PAD + craftAreaH + GRID_PAD + 76 + INV_BOTTOM_PAD
        damien.nodeworks.compat.AcsCompat.setImageSize(this, w, h)
    }

    override fun init() {
        super.init()
        // JEI's recipe view overlays this screen via setScreen, so the
        // recipe-transfer handler can't reach the live terminal through
        // `Minecraft.getInstance().screen` and reads through here instead.
        activeScreen = this
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

        // Crafting area position, centered in window
        val craftAreaH = if (craftingCollapsed) CRAFT_COLLAPSED_H else CRAFT_H
        val craftTotalW = 3 * 18 + 16 + 18 // 3x3 grid + arrow gap + output slot
        craftX = leftPos + (imageWidth - craftTotalW) / 2
        craftY = gridY + layout.rows * SLOT_SIZE + GRID_PAD

        // Player inventory grids, centered in window
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

        // Crystal slot (Handheld-only). Sits at the top-right, anchored inside the
        // window's top-right corner. The decorative PORTABLE_CRYSTAL_SLOT_FRAME is
        // 20x20 and wraps the 18x18 interactive slot with a 1px border on all
        // sides, (crystalSlotX, crystalSlotY) targets the interactive slot
        // itself, so the frame is drawn at (crystalSlotX - 1, crystalSlotY - 1).
        // The surrounding WINDOW_FRAME protrusion is positioned independently
        // in extractBackground, not tied to these coordinates.
        //
        // Real MC Slot behind this visual stays at (-999, -999) in
        // menu.slots[CRYSTAL_SLOT], we render/hit-test and dispatch clicks via
        // slotClicked ourselves.
        if (menu.hasCrystalSlot) {
            crystalSlotX = leftPos + imageWidth - CRYSTAL_SLOT_SIZE - 6
            crystalSlotY = topPos + 3
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
        val dialogDw = 160
        val dialogDx = leftPos + (imageWidth - dialogDw) / 2
        val dialogDy = topPos + (imageHeight - 70) / 2
        craftDialogueField = EditBox(font, dialogDx + 24, dialogDy + 28, dialogDw - 48, 12, Component.literal("Count"))
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

        // Kind mode toggle (items / fluids / both)
        val kindIcon = when (repo.kindMode) {
            InventoryRepo.KindMode.BOTH -> Icons.FLUID_AND_ITEMS
            InventoryRepo.KindMode.ITEMS_ONLY -> Icons.ITEMS_ONLY
            InventoryRepo.KindMode.FLUIDS_ONLY -> Icons.FLUIDS_ONLY
        }
        addRenderableWidget(SlicedButton.create(
            sideBtnX, sideBtnY, SIDE_BTN_W, SIDE_BTN_W, "", kindIcon
        ) { _ ->
            repo.kindMode = InventoryRepo.KindMode.entries[(repo.kindMode.ordinal + 1) % InventoryRepo.KindMode.entries.size]
            ClientConfig.invTerminalKindMode = repo.kindMode.name
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

        // Crafting grid utility buttons (left of 3x3 grid, only when expanded)
        if (!craftingCollapsed) {
            val ubX = craftX - 18
            val ubW = 16
            val ubH = 16

            // Auto-pull toggle (persists client-side + syncs to server)
            val autoPullIcon = if (ClientConfig.invTerminalAutoPull) Icons.AUTO_PULL_ON else Icons.AUTO_PULL_OFF
            addRenderableWidget(SlicedButton.create(ubX, craftY + 1, ubW, ubH, "", autoPullIcon) { _ ->
                ClientConfig.invTerminalAutoPull = !ClientConfig.invTerminalAutoPull
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.InvTerminalCraftGridActionPayload(menu.containerId, 2)
                )
                rebuildWidgets()
            })

            // Distribute/balance
            addRenderableWidget(SlicedButton.create(ubX, craftY + 19, ubW, ubH, "", Icons.CRAFTING_GRID_DISTRIBUTE) { _ ->
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.InvTerminalCraftGridActionPayload(menu.containerId, 0)
                )
            })

            // Clear to network
            addRenderableWidget(SlicedButton.create(ubX, craftY + 37, ubW, ubH, "", Icons.CRAFTING_GRID_CLEAR) { _ ->
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.InvTerminalCraftGridActionPayload(menu.containerId, 1)
                )
            })
        }
    }

    // ========== Rendering ==========

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Portable header layout (hasCrystalSlot = true). The main WINDOW_FRAME is
        // pushed down so the top-bar + crystal slot protrusion read as header
        // elements stacked above it. Fixed terminal keeps everything flush (both
        // offsets = 0). Current Portable anchors:
        //   barYOffset   = 6  → PORTABLE_TOP_BAR at topPos+6..16, left cap at
        //                       topPos+3..24 (16x21, pipe-style).
        //   frameYOffset = 22 → WINDOW_FRAME top overlaps the bottom 2px of the
        //                       left cap's pipe so the pipe merges seamlessly
        //                       into the frame border.
        // The protruding WINDOW_FRAME around the crystal slot (drawn further
        // below) is anchored independently to the top-right corner, not via
        // these offsets.
        val frameYOffset = if (menu.hasCrystalSlot) 22 else 0
        val barYOffset = if (menu.hasCrystalSlot) 6 else 0
        // Window frame (stretched for performance, large area)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos + frameYOffset, imageWidth, imageHeight - frameYOffset)

        // Title bar. Portable uses its own 10px-tall horizontally-tiled strip with
        // no title text, the crystal slot decoration sits on top of it, and the
        // network color is already conveyed by the emissive item layer + the
        // dimmed/messaged overlay when disconnected. Fixed terminals use the usual
        // 9-sliced TOP_BAR with network-color trim + centered title.
        val mc = Minecraft.getInstance()
        if (menu.hasCrystalSlot) {
            NineSlice.PORTABLE_TOP_BAR.draw(graphics, leftPos + 4, topPos + barYOffset, imageWidth - 8, 10)
            // Left end-cap: the top 16x16 is the corner where the bar turns.
            // The bottom 5px of the 16x21 sprite runs down like a pipe into the
            // window frame's top edge.
            NineSlice.PORTABLE_TOP_BAR_LEFT_CAP.draw(graphics, leftPos + 2, topPos + barYOffset - 3, 16, 21)
        } else {
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
        }

        // Network item grid background
        networkGrid.renderBackground(graphics)

        // Pinned row: lighter background overlay per slot (preserves slot borders)
        for (c in 0 until layout.cols) {
            val sx = networkGrid.x + c * 18 + 1
            val sy = networkGrid.y + 1
            graphics.fill(sx, sy, sx + 16, sy + 16, 0x30FFFFFF.toInt())
        }

        // Scrollbar (repo.ensureUpdated() called once in render(), not here)
        val availableRows = layout.rows - 1 // first row always reserved for pinned
        maxScroll = maxOf(0, (repo.viewSize + layout.cols - 1) / layout.cols - availableRows)
        scrollOffset = scrollOffset.coerceIn(0, maxOf(0, maxScroll))
        val scrollbarX = gridX + layout.cols * SLOT_SIZE + 2
        val gridH = layout.rows * SLOT_SIZE
        NineSlice.SCROLLBAR_TRACK.draw(graphics, scrollbarX, gridY, SCROLLBAR_W, gridH)
        if (maxScroll > 0) {
            val totalRows = (repo.viewSize + layout.cols - 1) / layout.cols
            val thumbH = maxOf(12, gridH * availableRows / totalRows.coerceAtLeast(1))
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

        // Crafting collapse toggle, to the left of the crafting area
        val collapseIcon = if (craftingCollapsed) Icons.EXPAND_IDLE else Icons.COLLAPSE_IDLE
        collapseIcon.draw(graphics, craftX - 30, craftY + 1)

        // Utility buttons are SlicedButton widgets, rendered automatically

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

        // Crystal slot background (Handheld only). Layers:
        //   1. WINDOW_FRAME (open bottom) anchored to the top-right of the
        //      window, spanning from 30px in from the right edge out to the
        //      right edge itself (so the protrusion shares its right border
        //      with the main window frame). The bottom 3px are transparent so
        //      the protrusion blends into the main window frame below.
        //      Position is independent of crystalSlotX/Y, the slot itself can
        //      shift inside the protrusion without dragging the frame along.
        //   2. WINDOW_INNER_CORNER_TL at the left junction where the
        //      protrusion's left side meets the main frame's top edge (Y =
        //      topPos + frameYOffset). No right-side inner corner: the right
        //      border is shared with the main frame, so there's no concave
        //      junction to bevel.
        //   3. PORTABLE_CRYSTAL_SLOT_FRAME (20x20) painted on top, with the
        //      18x18 interactive area centered inside its 1px border. Follows
        //      crystalSlotX/Y, offset by -1 on both axes so the inner slot
        //      lines up with (crystalSlotX, crystalSlotY).
        if (menu.hasCrystalSlot) {
            val protrusionX = leftPos + imageWidth - 30
            val protrusionY = topPos - 3
            val protrusionW = 30
            NineSlice.WINDOW_FRAME.drawOpenBottom(graphics, protrusionX, protrusionY, protrusionW, 31)
            NineSlice.WINDOW_INNER_CORNER_TL.draw(graphics, protrusionX, topPos + frameYOffset, 3, 3)
            NineSlice.PORTABLE_CRYSTAL_SLOT_FRAME.draw(graphics, crystalSlotX - 1, crystalSlotY - 1, 20, 20)
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Sync persisted auto-pull state to server on first render
        if (!syncedAutoPull) {
            syncedAutoPull = true
            if (ClientConfig.invTerminalAutoPull) {
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.InvTerminalCraftGridActionPayload(menu.containerId, 2)
                )
            }
        }

        // Auto-close the craft dialog after CRAFT_CLOSE_DELAY_MS if no error arrived.
        // [setCraftError] clears this deadline if a late error lands.
        if (craftCloseAfterMs > 0L && System.currentTimeMillis() >= craftCloseAfterMs) {
            craftCloseAfterMs = 0L
            if (craftError == null && craftDialogueItemId != null) {
                craftDialogueItemId = null
                craftDialogueField?.visible = false
            }
        }

        // Hide the craft dialogue field from normal rendering, we render it manually at higher Z
        val fieldWasVisible = craftDialogueField?.visible ?: false
        if (fieldWasVisible) craftDialogueField?.visible = false
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        if (fieldWasVisible) craftDialogueField?.visible = true

        // Ensure repo view is up-to-date before reading
        repo.ensureUpdated()

        // Batch icon rendering to avoid per-call RenderSystem state changes
        Icons.beginBatch()

        // Reserved slot icons on empty pinned slots
        val pinnedY = networkGrid.y + 1
        val iconSize = 10
        for (c in 0 until layout.cols) {
            if (c < craftQueue.size) continue  // slot occupied by a queue entry
            val sx = networkGrid.x + c * 18 + 1
            graphics.pose().pushMatrix()
            graphics.pose().translate((0f).toFloat(), (0f).toFloat())
            Icons.RESERVED_SLOT.draw(graphics, sx + 17 - iconSize, pinnedY - 1, iconSize)
            graphics.pose().popMatrix()
        }

        // Craft queue reserved row (always visible as first row)
        for ((i, slot) in craftQueue.withIndex()) {
            if (i >= layout.cols) break
            val sx = networkGrid.x + i * 18 + 1
            val stack = getItemStack(slot.itemId)
            if (!stack.isEmpty) {
                if (slot.availableCount <= 0) {
                    // Pending: gray overlay + dim item + queued count in gray (0.5x scale)
                    graphics.renderItem(stack, sx, pinnedY)
                    graphics.fill(sx, pinnedY, sx + 16, pinnedY + 16, 0x80000000.toInt())
                    graphics.pose().pushMatrix()
                    graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                    val countStr = formatCount(slot.totalRequested.toLong())
                    val scale = 0.5f
                    graphics.pose().scale((scale).toFloat(), (scale).toFloat())
                    val tw = font.width(countStr)
                    val cx = ((sx + 16).toFloat() / scale - tw).toInt()
                    val cy = ((pinnedY + 16).toFloat() / scale - font.lineHeight).toInt()
                    graphics.drawString(font, countStr, cx, cy, 0xFF888888.toInt(), true)
                    graphics.pose().popMatrix()
                    graphics.pose().pushMatrix()
                    graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                    Icons.CRAFTING_IN_PROGRESS.draw(graphics, sx + 17 - iconSize, pinnedY - 1, iconSize)
                    graphics.pose().popMatrix()
                } else {
                    // Items available, render normally with available count (0.5x scale)
                    graphics.renderItem(stack, sx, pinnedY)
                    if (slot.availableCount > 1) {
                        val countStr = formatCount(slot.availableCount.toLong())
                        val scale = 0.5f
                        graphics.pose().pushMatrix()
                        graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                        graphics.pose().scale((scale).toFloat(), (scale).toFloat())
                        val tw = font.width(countStr)
                        val cx = ((sx + 16).toFloat() / scale - tw).toInt()
                        val cy = ((pinnedY + 16).toFloat() / scale - font.lineHeight).toInt()
                        graphics.drawString(font, countStr, cx, cy, 0xFFFFFFFF.toInt(), true)
                        graphics.pose().popMatrix()
                    }
                    // Status icon
                    graphics.pose().pushMatrix()
                    graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                    val icon = if (slot.isComplete) Icons.CRAFTING_COMPLETE else Icons.CRAFTING_IN_PROGRESS
                    icon.draw(graphics, sx + 17 - iconSize, pinnedY - 1, iconSize)
                    graphics.pose().popMatrix()
                }
            }
        }
        // Separator line below pinned row
        val sepY = networkGrid.y + 18
        graphics.fill(networkGrid.x, sepY, networkGrid.x + layout.cols * 18, sepY + 1, 0xFF444444.toInt())

        // Render network items (skip first row, reserved for pinned)
        networkGrid.renderItems(graphics, scrollOffset, repo.viewSize, 1)

        // Second pass, draw fluid still-textures where the first pass skipped fluid cells
        // (getItemStackForNetworkSlot returns EMPTY for fluids so they pass through the
        // item renderer without a sprite). Counts render alongside via the same scaled-
        // text block used for items.
        for ((i, slot) in networkGrid.slots.withIndex()) {
            val row = i / layout.cols
            if (row < 1) continue
            val viewIndex = scrollOffset * layout.cols + (i - layout.cols)
            val entry = repo.getViewEntry(viewIndex) ?: continue
            if (!entry.isFluid) continue
            val ix = slot.x + 1
            val iy = slot.y + 1
            PlatformServices.fluidRenderer.render(graphics, entry.info.itemId, ix, iy, 16)
            val countStr = getCountForNetworkSlot(viewIndex)
            if (countStr != null) {
                val pose = graphics.pose()
                pose.pushMatrix()
                val scale = 0.5f
                pose.scale(scale, scale)
                val textWidth = font.width(countStr)
                val cx = ((ix + 16).toFloat() / scale - textWidth).toInt()
                val cy = ((iy + 16).toFloat() / scale - font.lineHeight).toInt()
                graphics.drawString(font, countStr, cx, cy, 0xFFFFFFFF.toInt(), true)
                pose.popMatrix()
            }
        }

        // Craftable overlays (skip pinned row)
        val altHeld = hasAltDownCompat()
        for ((i, slot) in networkGrid.slots.withIndex()) {
            val row = i / layout.cols
            if (row < 1) continue // skip pinned row
            val viewIndex = scrollOffset * layout.cols + (i - layout.cols)
            val entry = repo.getViewEntry(viewIndex) ?: continue
            if (entry.info.isCraftable) {
                val ix = slot.x + 1
                val iy = slot.y + 1
                if (entry.info.count == 0L) {
                    // Ghost item, dim overlay
                    graphics.fill(ix, iy, ix + 16, iy + 16, 0x80000000.toInt())
                }
                if (altHeld) {
                    // Show + icon on craftable items (above item Z layer)
                    graphics.pose().pushMatrix()
                    graphics.pose().translate((0f).toFloat(), (0f).toFloat())
                    Icons.CRAFT_PLUS.draw(graphics, ix + 17 - iconSize, iy - 1, iconSize)
                    graphics.pose().popMatrix()
                }
            }
        }

        Icons.endBatch()

        // Disconnect overlay (Handheld only). Paints over the network grid whenever
        // the server reports the menu isn't driving a live network, blanks out the
        // (empty) grid with a neutral veil so the empty state reads as
        // "disconnected" rather than "this network has no items," and surfaces the
        // reason so the player knows whether to move closer, swap crystals, etc.
        if (menu.hasCrystalSlot) {
            val status = menu.connectionStatus
            if (status != damien.nodeworks.screen.PortableConnectionStatus.CONNECTED) {
                val gridW = layout.cols * SLOT_SIZE
                val gridH = layout.rows * SLOT_SIZE
                graphics.fill(networkGrid.x, networkGrid.y, networkGrid.x + gridW, networkGrid.y + gridH, 0xC0202020.toInt())
                val label = disconnectMessage(status)
                val tw = font.width(label)
                val cx = networkGrid.x + (gridW - tw) / 2
                val cy = networkGrid.y + (gridH - font.lineHeight) / 2
                graphics.drawString(font, label, cx, cy, 0xFFFFFFFF.toInt(), true)
            }
        }

        // Render player inventory items
        playerMainGrid.renderItems(graphics)
        playerHotbarGrid.renderItems(graphics)

        // Crystal slot contents + hover highlight
        if (menu.hasCrystalSlot) {
            val stack = menu.slots[InventoryTerminalMenu.CRYSTAL_SLOT].item
            if (!stack.isEmpty) {
                graphics.renderItem(stack, crystalSlotX + 1, crystalSlotY + 1)
            }
            if (isCrystalSlotAt(mouseX, mouseY)) {
                graphics.fill(crystalSlotX + 1, crystalSlotY + 1,
                    crystalSlotX + 1 + 16, crystalSlotY + 1 + 16, 0x80FFFFFF.toInt())
            }
        }

        // Set hoveredSlot for JEI R/U key support
        val networkHover = networkGrid.getSlotAt(mouseX, mouseY, 0)
        if (networkHover != null && networkHover.index / layout.cols >= 1) {
            // Row 2+ = network items
            val viewIndex = scrollOffset * layout.cols + (networkHover.index - layout.cols)
            val entry = repo.getViewEntry(viewIndex)
            if (entry != null) {
                val id = net.minecraft.resources.Identifier.tryParse(entry.info.itemId)
                if (id != null) {
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id)
                    if (item != null) {
                        hoverContainer.setItem(0, ItemStack(item))
                        hoveredSlot = net.minecraft.world.inventory.Slot(hoverContainer, 0, networkHover.x - leftPos, networkHover.y - topPos)
                    }
                }
            }
        } else if (menu.hasCrystalSlot && isCrystalSlotAt(mouseX, mouseY)) {
            // Crystal slot, MC real slot, pointable directly.
            hoveredSlot = menu.slots[InventoryTerminalMenu.CRYSTAL_SLOT]
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
                        hoverContainer.setItem(0, stack.copy())
                        hoveredSlot = net.minecraft.world.inventory.Slot(hoverContainer, 0, playerSlot.x - leftPos, playerSlot.y - topPos)
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
        val hoveredNetwork = networkGrid.getSlotAt(mouseX, mouseY, 0)
        val hoveredIsQueueSlot = hoveredNetwork != null && hoveredNetwork.index / layout.cols == 0
        val hoveredIsNetworkItem = hoveredNetwork != null && hoveredNetwork.index / layout.cols >= 1

        if (hoveredIsQueueSlot) {
            val queueIdx = hoveredNetwork!!.index % layout.cols
            if (queueIdx < craftQueue.size) {
                val slot = craftQueue[queueIdx]
                val stack = getItemStack(slot.itemId)
                if (!stack.isEmpty) {
                    val lines = getTooltipFromItem(Minecraft.getInstance(), stack).toMutableList()
                    val statusStr = if (slot.isComplete) "Complete" else "Crafting..."
                    lines.add(Component.literal("$statusStr (${slot.readyCount}/${slot.totalRequested})").withStyle { it.withColor(if (slot.isComplete) 0x55FF55 else 0xFFAA00) })
                    if (slot.availableCount > 0) {
                        lines.add(Component.literal("Click to extract ${slot.availableCount}").withStyle { it.withColor(0xAAAAAA) })
                    }
                    graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY)
                }
            }
        } else if (hoveredIsNetworkItem) {
            val viewIndex = scrollOffset * layout.cols + (hoveredNetwork!!.index - layout.cols)
            val entry = repo.getViewEntry(viewIndex)
            if (entry != null) {
                if (entry.isFluid) {
                    val lines = mutableListOf<Component>(Component.literal(entry.info.name))
                    lines.add(Component.literal(entry.info.itemId).withStyle { it.withColor(0x666666) })
                    lines.add(Component.literal("${formatCount(entry.info.count)} mB").withStyle { it.withColor(0xAAAAAA) })
                    graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY)
                } else {
                    val stack = getItemStack(entry.info.itemId)
                    if (!stack.isEmpty) {
                        val lines = getTooltipFromItem(Minecraft.getInstance(), stack).toMutableList()
                        lines.add(Component.literal("Network: ${formatCount(entry.info.count)}").withStyle { it.withColor(0xAAAAAA) })
                        if (entry.info.isCraftable) {
                            lines.add(Component.literal("Craftable").withStyle { it.withColor(0x55FF55) })
                        }
                        graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY)
                    }
                }
            }
        } else if (!hoveredIsNetworkItem) {
            // Crafting slot tooltip
            var craftTooltipShown = false
            if (!craftingCollapsed) {
                val hoveredCraft = getCraftSlotAt(mouseX, mouseY)
                if (hoveredCraft >= 0) {
                    val stack = menu.craftingContainer.getItem(hoveredCraft)
                    if (!stack.isEmpty) {
                        graphics.setTooltipForNextFrame(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                        craftTooltipShown = true
                    }
                } else if (isCraftOutputAt(mouseX, mouseY)) {
                    val stack = menu.resultContainer.getItem(0)
                    if (!stack.isEmpty) {
                        graphics.setTooltipForNextFrame(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                        craftTooltipShown = true
                    }
                }
            }

            // Utility button tooltips
            if (!craftTooltipShown && !craftingCollapsed) {
                val ubX = craftX - 18
                val ubW = 16
                val ubH = 16
                if (mouseX >= ubX && mouseX < ubX + ubW) {
                    val tip = when {
                        mouseY >= craftY + 1 && mouseY < craftY + 1 + ubH -> {
                            val state = if (damien.nodeworks.config.ClientConfig.invTerminalAutoPull) "On" else "Off"
                            "Auto-pull: $state"
                        }
                        mouseY >= craftY + 19 && mouseY < craftY + 19 + ubH -> "Distribute evenly"
                        mouseY >= craftY + 37 && mouseY < craftY + 37 + ubH -> "Clear to network"
                        else -> null
                    }
                    if (tip != null) {
                        graphics.renderTooltip(font, Component.literal(tip), mouseX, mouseY)
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
                        graphics.setTooltipForNextFrame(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                    }
                }
            }

            // Crystal slot tooltip, item tooltip when occupied, hint when empty.
            if (!craftTooltipShown && menu.hasCrystalSlot && isCrystalSlotAt(mouseX, mouseY)) {
                val stack = menu.slots[InventoryTerminalMenu.CRYSTAL_SLOT].item
                if (!stack.isEmpty) {
                    graphics.setTooltipForNextFrame(font, getTooltipFromItem(Minecraft.getInstance(), stack), stack.tooltipImage, mouseX, mouseY)
                } else {
                    graphics.renderTooltip(font, Component.literal("Link Crystal"), mouseX, mouseY)
                }
            }

            // Side-button tooltips, "Feature: Current setting" form.
            val sideTip = sideButtonTooltip(mouseX, mouseY)
            if (sideTip != null) {
                graphics.renderTooltip(font, sideTip, mouseX, mouseY)
            }
        }

        // Craft dialogue overlay
        if (craftDialogueItemId != null) {
            val dw = 160
            // If an error is present, word-wrap it and expand the dialog height to fit
            val errorWrapLines: List<net.minecraft.util.FormattedCharSequence> = craftError?.let {
                font.split(Component.literal(it), dw - 12)
            } ?: emptyList()
            val errorHeight = if (errorWrapLines.isNotEmpty()) errorWrapLines.size * (font.lineHeight + 1) + 6 else 0
            val dh = 70 + errorHeight
            val dx = leftPos + (imageWidth - dw) / 2
            val dy = topPos + (imageHeight - dh) / 2

            graphics.pose().pushMatrix()
            graphics.pose().translate((0f).toFloat(), (0f).toFloat())
            NineSlice.PANEL_INSET.draw(graphics, dx, dy, dw, dh)
            NineSlice.CONTENT_BORDER.draw(graphics, dx, dy, dw, dh)

            // Item icon + name
            val stack = getItemStack(craftDialogueItemId!!)
            if (!stack.isEmpty) {
                graphics.renderItem(stack, dx + 6, dy + 6)
            }
            graphics.drawString(font, craftDialogueItemName, dx + 26, dy + 10, 0xFFFFFFFF.toInt())

            // Keep the count field aligned with the (dynamic) dialog, when an error expands
            // the dialog height, `dy` shifts up to stay centered, so the field must follow.
            craftDialogueField?.let { field ->
                field.x = dx + 24
                field.y = dy + 28
                if (field.visible) field.extractRenderState(graphics, mouseX, mouseY, 0f)
            }

            // Count stepper: [-] [field] [+]
            val stepperY = dy + 28
            val minusBtnX = dx + 6
            val plusBtnX = dx + dw - 22
            val stepBtnW = 16
            val stepBtnH = 14
            val minusHover = mouseX >= minusBtnX && mouseX < minusBtnX + stepBtnW && mouseY >= stepperY && mouseY < stepperY + stepBtnH
            val plusHover = mouseX >= plusBtnX && mouseX < plusBtnX + stepBtnW && mouseY >= stepperY && mouseY < stepperY + stepBtnH
            (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, minusBtnX, stepperY, stepBtnW, stepBtnH)
            (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, plusBtnX, stepperY, stepBtnW, stepBtnH)
            graphics.drawString(font, "-", minusBtnX + (stepBtnW - font.width("-")) / 2, stepperY + 3, if (minusHover) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            graphics.drawString(font, "+", plusBtnX + (stepBtnW - font.width("+")) / 2, stepperY + 3, if (plusHover) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())

            // Error text between the stepper and the action buttons
            if (errorWrapLines.isNotEmpty()) {
                val errorTop = dy + 46
                for ((i, line) in errorWrapLines.withIndex()) {
                    graphics.drawString(font, line, dx + 6, errorTop + i * (font.lineHeight + 1), 0xFFFF6666.toInt(), false)
                }
            }

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

            graphics.pose().popMatrix()
        }

        // Render carried item on cursor
        val carried = menu.carried
        if (!carried.isEmpty) {
            graphics.pose().pushMatrix()
            graphics.pose().translate((0f).toFloat(), (0f).toFloat())
            graphics.renderItem(carried, mouseX - 8, mouseY - 8)
            graphics.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8)
            graphics.pose().popMatrix()
        }
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Don't render default labels
    }


    // ========== Input ==========

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Craft dialogue buttons
        if (craftDialogueItemId != null) {
            val dw = 160
            val dh = 70
            val dx = leftPos + (imageWidth - dw) / 2
            val dy = topPos + (imageHeight - dh) / 2
            val craftBtnX = dx + 6
            val cancelBtnX = dx + dw / 2 + 2
            val btnY = dy + dh - 18
            val btnW = dw / 2 - 8

            // Stepper buttons
            val stepperY = dy + 28
            val minusBtnX = dx + 6
            val plusBtnX = dx + dw - 22
            val stepBtnW = 16
            val stepBtnH = 14
            if (mx >= minusBtnX && mx < minusBtnX + stepBtnW && my >= stepperY && my < stepperY + stepBtnH) {
                val current = craftDialogueField?.value?.toIntOrNull() ?: 1
                val step = if (hasShiftDownCompat()) 10 else 1
                craftDialogueField?.value = maxOf(1, current - step).toString()
                return true
            }
            if (mx >= plusBtnX && mx < plusBtnX + stepBtnW && my >= stepperY && my < stepperY + stepBtnH) {
                val current = craftDialogueField?.value?.toIntOrNull() ?: 1
                val step = if (hasShiftDownCompat()) 10 else 1
                craftDialogueField?.value = minOf(999, current + step).toString()
                return true
            }

            if (mx >= craftBtnX && mx < craftBtnX + btnW && my >= btnY && my < btnY + 14) {
                // Craft button. Clear prior error, send request, and schedule an auto-close:
                // if no error arrives within the window, the dialog closes optimistically.
                // [setCraftError] re-opens the dialog if a late error comes in.
                craftError = null
                val count = craftDialogueField?.value?.toIntOrNull() ?: 1
                if (count > 0) {
                    lastAttemptItemId = craftDialogueItemId
                    lastAttemptItemName = craftDialogueItemName
                    lastAttemptCount = count
                    PlatformServices.clientNetworking.sendToServer(
                        damien.nodeworks.network.InvTerminalCraftPayload(menu.containerId, craftDialogueItemId!!, count)
                    )
                    craftCloseAfterMs = System.currentTimeMillis() + CRAFT_CLOSE_DELAY_MS
                }
                return true
            }
            if (mx >= cancelBtnX && mx < cancelBtnX + btnW && my >= btnY && my < btnY + 14) {
                // Cancel button
                craftDialogueItemId = null
                craftError = null
                craftDialogueField?.visible = false
                return true
            }
            // Click inside dialogue, consume
            if (mx >= dx && mx < dx + dw && my >= dy && my < dy + dh) {
                return super.mouseClicked(event, doubleClick)
            }
            // Click outside, close
            craftDialogueItemId = null
            craftError = null
            craftDialogueField?.visible = false
            return true
        }

        // Crystal slot click (Handheld only). Dispatches through the real MC Slot so
        // AbstractContainerMenu's mayPlace / max stack size checks apply and the
        // result syncs to the server, the menu's onCrystalSlotChanged hook then
        // persists the crystal to the Portable and re-resolves the network.
        //
        // `setSkipNextRelease(true)` mirrors what vanilla's own mouseClicked does
        // after a slotClicked: it tells the paired mouseReleased to no-op. Without
        // it, mouseReleased sees a release outside the window rect, computes
        // `slotId = -999`, and sends a PICKUP click that throws the carried crystal
        // onto the ground.
        if (menu.hasCrystalSlot && isCrystalSlotAt(mx, my)) {
            val clickType = if (hasShiftDownCompat()) {
                net.minecraft.world.inventory.ContainerInput.QUICK_MOVE
            } else {
                net.minecraft.world.inventory.ContainerInput.PICKUP
            }
            slotClicked(
                menu.slots[InventoryTerminalMenu.CRYSTAL_SLOT],
                InventoryTerminalMenu.CRYSTAL_SLOT,
                button,
                clickType,
            )
            damien.nodeworks.compat.AcsCompat.setSkipNextRelease(this, true)
            return true
        }

        // Crafting collapse toggle (moved further left)
        if (mx >= craftX - 30 && mx < craftX - 14 && my >= craftY + 1 && my < craftY + 17) {
            craftingCollapsed = !craftingCollapsed
            ClientConfig.invTerminalCraftingCollapsed = craftingCollapsed
            rebuildWidgets()
            return true
        }

        // Right-click search bar = clear + focus, so a player can chain queries
        // without manually selecting and deleting the prior text first.
        if (button == 1 && mx >= searchX && mx < searchX + searchW && my >= searchY && my < searchY + SEARCH_H) {
            searchBox.value = ""
            setFocused(searchBox)
            return true
        }

        // Crafting utility buttons are SlicedButton widgets, click handled automatically

        // Double-click collect check
        val now = net.minecraft.util.Util.getMillis()
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
                // Double-click collect, works with item on cursor (from first click)
                val collectItem = if (!menu.carried.isEmpty) menu.carried else menu.craftingContainer.getItem(craftSlot)
                if (!collectItem.isEmpty) {
                    val craftItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(collectItem.item)?.toString() ?: ""
                    if (checkDoubleClick(0, craftSlot, craftItemId)) return true
                }
                if (hasShiftDownCompat()) {
                    // Shift-click: move to player inventory
                    slotClicked(menu.slots[slotIdx], slotIdx, button, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE)
                    // Start shift-drag for dragging over more slots
                    slotDragButton = button
                    slotDragShift = true
                    slotDragVisited.clear()
                    slotDragVisited.add(DragSlotRef(0, slotIdx))
                } else if (!menu.carried.isEmpty && button == 1) {
                    // Right-click: place one item, start right-drag
                    slotClicked(menu.slots[slotIdx], slotIdx, 1, net.minecraft.world.inventory.ContainerInput.PICKUP)
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
                    slotClicked(menu.slots[slotIdx], slotIdx, button, net.minecraft.world.inventory.ContainerInput.PICKUP)
                }
                return true
            }
            // Output slot
            if (isCraftOutputAt(mx, my)) {
                val clickType = if (hasShiftDownCompat()) net.minecraft.world.inventory.ContainerInput.QUICK_MOVE else net.minecraft.world.inventory.ContainerInput.PICKUP
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
        val networkSlot = networkGrid.getSlotAt(mx, my, 0) // raw grid position
        if (networkSlot != null) {
            val gridRow = networkSlot.index / layout.cols

            // First row = craft queue reserved area
            if (gridRow == 0) {
                val queueIdx = networkSlot.index % layout.cols
                if (queueIdx < craftQueue.size) {
                    val slot = craftQueue[queueIdx]
                    if (slot.availableCount > 0) {
                        val action = when {
                            hasShiftDownCompat() -> 1  // shift to inventory
                            button == 1 -> 2     // right click = half
                            else -> 0            // left click = full stack to cursor
                        }
                        PlatformServices.clientNetworking.sendToServer(
                            damien.nodeworks.network.CraftQueueExtractPayload(menu.containerId, slot.id, action)
                        )
                        unfocusSearchBox()
                    }
                }
                return true
            }

            // Row 2+: network items (offset by 1 row for pinned)
            val viewIndex = scrollOffset * layout.cols + (networkSlot.index - layout.cols)
            val entry = repo.getViewEntry(viewIndex)
            if (entry != null && entry.info.isCraftable && (hasAltDownCompat() || entry.info.count == 0L)) {
                craftDialogueItemId = entry.info.itemId
                craftDialogueItemName = entry.info.name
                craftDialogueField?.value = "1"
                craftDialogueField?.visible = true
                craftDialogueField?.isFocused = true
                craftError = null  // fresh dialog, no stale error
                return true
            }
            // Fluid entry, fill a bucket. Only legal when carried is empty (network supplies
            // the empty bucket) or carried is an empty bucket (that bucket gets filled).
            // Filled-bucket items in storage are normal items and follow the item path below.
            if (entry != null && entry.isFluid && entry.info.count > 0) {
                val carried = menu.carried
                val canAct = carried.isEmpty ||
                    (carried.item == net.minecraft.world.item.Items.BUCKET && carried.count > 0)
                if (canAct) {
                    val action = if (hasShiftDownCompat()) 3 else 0
                    PlatformServices.clientNetworking.sendToServer(
                        InvTerminalClickPayload(menu.containerId, entry.info.itemId, action, kind = 1)
                    )
                    unfocusSearchBox()
                }
                return true
            }
            if (menu.carried.isEmpty && entry != null && entry.info.count > 0) {
                val action = when {
                    hasShiftDownCompat() -> 3
                    button == 1 -> 2
                    else -> 0
                }
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalClickPayload(menu.containerId, entry.info.itemId, action)
                )
                unfocusSearchBox()
                return true
            } else if (!menu.carried.isEmpty) {
                val insertAction = if (button == 1) 4 else 1  // right=one, left=all
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalClickPayload(menu.containerId, "", insertAction)
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
            // Double-click collect, works with item on cursor (from first click)
            val collectItem = if (!menu.carried.isEmpty) menu.carried else (Minecraft.getInstance().player?.inventory?.getItem(invIndex) ?: ItemStack.EMPTY)
            if (!collectItem.isEmpty) {
                val playerItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(collectItem.item)?.toString() ?: ""
                if (checkDoubleClick(1, virtualIndex, playerItemId)) return true
            }
            if (hasShiftDownCompat()) {
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
                unfocusSearchBox()
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        // Bootstrap a shift-drag if the player started left-click+shift
        // outside any slot and dragged into inventory territory. Without
        // this every slot-touch path below short-circuits on slotDragButton.
        if (slotDragButton == -1 && button == 0 && hasShiftDownCompat()) {
            slotDragButton = 0
            slotDragShift = true
            slotDragVisited.clear()
        }
        if (slotDragButton >= 0) {
            val mx = mouseX.toInt()
            val my = mouseY.toInt()

            // Check crafting grid
            if (!craftingCollapsed) {
                val craftSlot = getCraftSlotAt(mx, my)
                if (craftSlot >= 0) {
                    val slotIdx = InventoryTerminalMenu.CRAFT_INPUT_START + craftSlot
                    val ref = DragSlotRef(0, slotIdx)
                    if (shouldTriggerDragOn(ref)) {
                        if (slotDragShift) {
                            slotClicked(menu.slots[slotIdx], slotIdx, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE)
                        } else if (slotDragButton == 1 && !menu.carried.isEmpty) {
                            slotClicked(menu.slots[slotIdx], slotIdx, 1, net.minecraft.world.inventory.ContainerInput.PICKUP)
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
                if (shouldTriggerDragOn(ref)) {
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

            // Cursor off every slot. Clear the last-hovered marker so a
            // shift-drag retriggers when it sweeps back onto a slot.
            slotDragLastHovered = null
            return true
        }

        if (draggingScrollbar && maxScroll > 0) {
            val gridH = layout.rows * SLOT_SIZE
            val availableRows = layout.rows - 1
            val totalRows = (repo.viewSize + layout.cols - 1) / layout.cols
            val thumbH = maxOf(12, gridH * availableRows / totalRows.coerceAtLeast(1))
            val scrollRange = gridH - thumbH
            if (scrollRange > 0) {
                val relY = (mouseY.toInt() - gridY - thumbH / 2).toFloat() / scrollRange
                scrollOffset = (relY * maxScroll).toInt().coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val mouseX = event.mouseX
        val mouseY = event.mouseY
        val button = event.buttonNum
        // Finish left-drag distribute
        if (slotDragButton == 0 && !slotDragShift && slotDragVisited.isNotEmpty() && !slotDragStack.isEmpty) {
            if (slotDragVisited.size == 1) {
                // Single click, place all
                val ref = slotDragVisited.first()
                if (ref.slotType == 0) {
                    slotClicked(menu.slots[ref.index], ref.index, 0, net.minecraft.world.inventory.ContainerInput.PICKUP)
                } else {
                    // Player inventory: send via packet
                    PlatformServices.clientNetworking.sendToServer(
                        damien.nodeworks.network.InvTerminalSlotClickPayload(menu.containerId, ref.index, 0)
                    )
                }
            } else {
                // Multi-slot drag, send distribute packet to server
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
        slotDragLastHovered = null

        draggingScrollbar = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scrollOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxOf(0, maxScroll))
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scan
        val modifiers = event.modifierBits
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
            return craftDialogueField?.keyPressed(event) ?: false
        }

        if (searchBox.isFocused) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                unfocusSearchBox()
                return true
            }
            return searchBox.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val codePoint = event.character
        val modifiers = 0
        if (craftDialogueItemId != null && craftDialogueField?.isFocused == true) {
            // Only allow digits
            if (codePoint.isDigit()) {
                return craftDialogueField?.charTyped(event) ?: false
            }
            return true
        }
        if (searchBox.isFocused) {
            return searchBox.charTyped(event)
        }
        return super.charTyped(event)
    }

    /** Handle server sync of craft queue state. */
    fun handleQueueSync(payload: damien.nodeworks.network.CraftQueueSyncPayload) {
        craftQueue = payload.entries.map { e ->
            QueueSlot(e.id, e.itemId, e.name, e.totalRequested, e.readyCount, e.availableCount, e.isComplete)
        }
    }

    /** For JEI: get the item ID of the hovered network grid item. */
    fun getHoveredNetworkItemId(mx: Int, my: Int): String? {
        repo.ensureUpdated()
        val slot = networkGrid.getSlotAt(mx, my, scrollOffset) ?: return null
        val entry = repo.getViewEntry(slot.index) ?: return null
        return entry.info.itemId
    }

    // ========== Slot Helpers ==========

    /** Whether the drag tick over [ref] should fire an insert / place. For
     *  shift-drag we trigger only when the cursor enters a different slot
     *  than the previous tick: standing still doesn't re-fire, but sweeping
     *  back over a slot whose items have changed (e.g. picked up after a
     *  prior insert) does. For non-shift drags we keep the visited-list
     *  semantics so a left-distribute or right-place-one drag only acts on
     *  each slot once. */
    private fun shouldTriggerDragOn(ref: DragSlotRef): Boolean {
        if (slotDragShift) {
            if (ref == slotDragLastHovered) return false
            slotDragLastHovered = ref
            return dragSlotItemCount(ref) > 0
        }
        if (ref in slotDragVisited) return false
        slotDragVisited.add(ref)
        return true
    }

    /** Client-side item count at the slot referenced by [ref], used by
     *  shift-drag to skip slot-entry triggers when there's nothing to
     *  insert. Returns 0 for unknown slot types. */
    private fun dragSlotItemCount(ref: DragSlotRef): Int = when (ref.slotType) {
        0 -> menu.craftingContainer.getItem(ref.index - InventoryTerminalMenu.CRAFT_INPUT_START).count
        1 -> {
            val invIndex = if (ref.index < 27) ref.index + 9 else ref.index - 27
            (Minecraft.getInstance().player?.inventory?.getItem(invIndex) ?: ItemStack.EMPTY).count
        }
        else -> 0
    }

    /** Drop the search field's focus, also clearing the screen-level tracker
     *  when it was pointing here. Without the `setFocused(null)` route, vanilla's
     *  same-target equality check on `setFocused(child)` will skip re-focusing
     *  on the next click and the field becomes silently unclickable. */
    private fun unfocusSearchBox() {
        if (!searchBox.isFocused) return
        if (focused === searchBox) setFocused(null) else searchBox.isFocused = false
    }

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

    /** True when the mouse is over the Handheld's crystal slot. Always false for the
     *  fixed terminal, where the slot doesn't exist. */
    private fun isCrystalSlotAt(mx: Int, my: Int): Boolean {
        if (!menu.hasCrystalSlot) return false
        return mx >= crystalSlotX && mx < crystalSlotX + CRYSTAL_SLOT_SIZE &&
               my >= crystalSlotY && my < crystalSlotY + CRYSTAL_SLOT_SIZE
    }

    /**
     * Returns the tooltip label for whichever side button the mouse is over, or
     * null if the mouse is outside the side-button strip. Each tooltip shows the
     * button's purpose plus the current setting, e.g. "Sort: A-Z", "Layout:
     * Small". Recomputed fresh every frame so toggles reflect immediately.
     */
    private fun sideButtonTooltip(mouseX: Int, mouseY: Int): Component? {
        val btnX = leftPos - SIDE_BTN_W - 4
        if (mouseX < btnX || mouseX >= btnX + SIDE_BTN_W) return null
        val step = SIDE_BTN_W + SIDE_BTN_GAP
        val relY = mouseY - (topPos + TOP_BAR_H + 2)
        if (relY < 0) return null
        val index = relY / step
        // Mouse in the gap between two buttons counts as neither.
        if (relY - index * step >= SIDE_BTN_W) return null
        return when (index) {
            0 -> Component.literal("Layout: ${when (layout) {
                Layout.SMALL -> "Small"
                Layout.WIDE -> "Wide"
                Layout.TALL -> "Tall"
                Layout.LARGE -> "Large"
            }}")
            1 -> Component.literal("Sort: ${when (repo.sortMode) {
                InventoryRepo.SortMode.ALPHA -> "A-Z"
                InventoryRepo.SortMode.COUNT_DESC -> "Count (high to low)"
                InventoryRepo.SortMode.COUNT_ASC -> "Count (low to high)"
            }}")
            2 -> Component.literal("Filter: ${when (repo.filterMode) {
                InventoryRepo.FilterMode.STORAGE -> "Storage"
                InventoryRepo.FilterMode.RECIPES -> "Recipes"
                InventoryRepo.FilterMode.BOTH -> "Both"
            }}")
            3 -> Component.literal("Kind: ${when (repo.kindMode) {
                InventoryRepo.KindMode.ITEMS_ONLY -> "Items"
                InventoryRepo.KindMode.FLUIDS_ONLY -> "Fluids"
                InventoryRepo.KindMode.BOTH -> "Both"
            }}")
            4 -> Component.literal(
                "Auto-focus search: ${if (ClientConfig.invTerminalAutoFocusSearch) "On" else "Off"}"
            )
            else -> null
        }
    }

    /** Short label shown centered over the grid when the Handheld is disconnected.
     *  Kept terse because the grid-sized overlay doesn't have room for prose. */
    private fun disconnectMessage(status: damien.nodeworks.screen.PortableConnectionStatus): String = when (status) {
        damien.nodeworks.screen.PortableConnectionStatus.CONNECTED -> ""
        damien.nodeworks.screen.PortableConnectionStatus.NO_CRYSTAL -> "No Link Crystal"
        damien.nodeworks.screen.PortableConnectionStatus.BLANK_CRYSTAL -> "Crystal Not Paired"
        damien.nodeworks.screen.PortableConnectionStatus.WRONG_KIND -> "Wrong Crystal Type"
        damien.nodeworks.screen.PortableConnectionStatus.OUT_OF_RANGE -> "Out of Range"
        damien.nodeworks.screen.PortableConnectionStatus.DIMENSION_MISMATCH -> "Wrong Dimension"
        damien.nodeworks.screen.PortableConnectionStatus.UNREACHABLE -> "Network Unreachable"
    }

    // ========== Helpers ==========

    private fun getItemStackForNetworkSlot(viewIndex: Int): ItemStack {
        val entry = repo.getViewEntry(viewIndex) ?: return ItemStack.EMPTY
        // Fluids don't use the item-stack render path, they're drawn in a second pass
        // (renderFluidOverlay) using the fluid's still texture. Returning EMPTY here
        // keeps the grid's item renderer from drawing anything in the fluid cell.
        if (entry.isFluid) return ItemStack.EMPTY
        return getItemStack(entry.info.itemId)
    }

    private val countStringCache = HashMap<Long, String>()

    private fun getCountForNetworkSlot(viewIndex: Int): String? {
        val entry = repo.getViewEntry(viewIndex) ?: return null
        // Always show a count for fluids (in mB), the bucket icon alone doesn't convey amount.
        // Items keep the existing "hide on count ≤ 1" convention.
        if (!entry.isFluid && entry.info.count <= 1) return null
        return countStringCache.getOrPut(entry.info.count) { formatCount(entry.info.count) }
    }

    private fun getItemStack(itemId: String): ItemStack {
        return itemStackCache.getOrPut(itemId) {
            val id = Identifier.tryParse(itemId) ?: return@getOrPut ItemStack.EMPTY
            val item = BuiltInRegistries.ITEM.getValue(id) ?: return@getOrPut ItemStack.EMPTY
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

    companion object {
        /** Set in [init] so the JEI recipe-transfer handler can read this
         *  screen's [repo]. Never cleared, callers validate by checking
         *  `activeScreen?.menu === menu` before trusting the reference. */
        var activeScreen: InventoryTerminalScreen? = null
            private set
    }
}
