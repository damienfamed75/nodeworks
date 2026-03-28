package damien.nodeworks.screen

import com.mojang.blaze3d.platform.InputConstants
import damien.nodeworks.network.InvTerminalClickPayload
import damien.nodeworks.platform.PlatformServices
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
 * Scrollable grid of network items with search, plus player inventory for inserting items.
 */
class InventoryTerminalScreen(
    menu: InventoryTerminalMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<InventoryTerminalMenu>(menu, playerInventory, title) {

    companion object {
        private val BACKGROUND = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/inventory_terminal.png")
        private const val COLS = 9
        private const val ROWS = 6
        private const val SLOT_SIZE = 18
        private const val GRID_X = 8
        private const val GRID_Y = 24
        private const val SEARCH_Y = 6
    }

    val repo = InventoryRepo()
    private var hoveredNetworkEntry: InventoryRepo.RepoEntry? = null
    private var scrollOffset = 0
    private var maxScroll = 0
    private lateinit var searchBox: EditBox

    init {
        imageWidth = 176
        imageHeight = 218
        inventoryLabelY = 124
    }

    override fun init() {
        super.init()

        searchBox = EditBox(font, leftPos + 9, topPos + SEARCH_Y + 1, imageWidth - 18, 12, Component.literal("Search"))
        searchBox.setMaxLength(50)
        searchBox.setBordered(true)
        searchBox.setTextColor(0xFFE0E0E0.toInt())
        // searchBox.setTextShadow(true) — not available in 1.21.1
        searchBox.isFocused = true
        searchBox.active = true
        searchBox.visible = true
        searchBox.setResponder { text -> repo.searchText = text }
        addRenderableWidget(searchBox)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.blit(
            BACKGROUND,
            leftPos, topPos,
            0f, 0f,
            imageWidth, imageHeight,
            256, 256
        )

        // Scrollbar thumb
        val scrollbarX = leftPos + imageWidth - 14
        val scrollbarY = topPos + GRID_Y
        val scrollbarH = ROWS * SLOT_SIZE
        maxScroll = maxOf(0, (repo.viewSize + COLS - 1) / COLS - ROWS)
        if (maxScroll > 0) {
            val thumbH = maxOf(8, scrollbarH * ROWS / ((repo.viewSize + COLS - 1) / COLS))
            val thumbY = scrollbarY + (scrollbarH - thumbH) * scrollOffset / maxScroll
            graphics.fill(scrollbarX, thumbY, scrollbarX + 6, thumbY + thumbH, 0xFFAAAAAA.toInt())
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)

        // Render items in the virtual grid
        var hoveredEntry: InventoryRepo.RepoEntry? = null
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val viewIndex = (scrollOffset + row) * COLS + col
                val entry = repo.getViewEntry(viewIndex) ?: continue

                val sx = leftPos + GRID_X + col * SLOT_SIZE + 1
                val sy = topPos + GRID_Y + row * SLOT_SIZE + 1

                // Render item icon with count
                val identifier = ResourceLocation.tryParse(entry.info.itemId) ?: continue
                val item = BuiltInRegistries.ITEM.get(identifier) ?: continue
                val stack = ItemStack(item, minOf(entry.info.count, 64).toInt())
                graphics.renderItem(stack, sx, sy)
                // Use MC's built-in count renderer, but with our custom text for large numbers
                val countText = if (entry.info.count > 1) formatCount(entry.info.count) else null
                if (countText != null) {
                    graphics.renderItemDecorations(font, stack, sx, sy, countText)
                }

                // Check hover
                if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                    hoveredEntry = entry
                    graphics.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF.toInt())
                }
            }
        }

        // Store hovered entry for tooltip rendering at the end (on top of everything)
        this.hoveredNetworkEntry = hoveredEntry

        renderTooltip(graphics, mouseX, mouseY)

        // Render network item tooltip using MC's native tooltip system
        val hovered = this.hoveredNetworkEntry
        if (hovered != null) {
            val identifier = ResourceLocation.tryParse(hovered.info.itemId)
            val item = if (identifier != null) BuiltInRegistries.ITEM.get(identifier) else null
            if (item != null) {
                val stack = ItemStack(item, 1)
                val tooltipLines = getTooltipFromItem(Minecraft.getInstance(), stack).toMutableList()
                tooltipLines.add(Component.literal("Network: ${hovered.info.count}").withStyle { it.withColor(0xAAAAAA) })
                graphics.renderTooltip(font, tooltipLines, java.util.Optional.empty(), mouseX, mouseY)
            }
        }
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Don't render default title at (0,0) — we don't have room above the search
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // Check if click is on the network grid
        val gridLeft = leftPos + GRID_X + 1
        val gridTop = topPos + GRID_Y + 1
        val gridRight = gridLeft + COLS * SLOT_SIZE
        val gridBottom = gridTop + ROWS * SLOT_SIZE

        if (mx >= gridLeft && mx < gridRight && my >= gridTop && my < gridBottom) {
            val col = (mx - gridLeft) / SLOT_SIZE
            val row = (my - gridTop) / SLOT_SIZE
            val viewIndex = (scrollOffset + row) * COLS + col
            val entry = repo.getViewEntry(viewIndex)

            if (menu.carried.isEmpty && entry != null) {
                // Extract from network
                val isShift = hasShiftDown()
                val action = when {
                    isShift -> 3          // shift-click: extract to inventory
                    button == 1 -> 2      // right-click: half stack
                    else -> 0             // left-click: full stack to cursor
                }
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalClickPayload(menu.containerId, entry.info.itemId, action)
                )
                return true
            } else if (!menu.carried.isEmpty) {
                // Insert carried item into network
                PlatformServices.clientNetworking.sendToServer(
                    InvTerminalClickPayload(menu.containerId, "", 1)
                )
                return true
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scrollOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxScroll)
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

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
