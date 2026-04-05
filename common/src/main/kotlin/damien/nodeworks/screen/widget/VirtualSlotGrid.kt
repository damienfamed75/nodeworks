package damien.nodeworks.screen.widget

import damien.nodeworks.screen.NineSlice
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/**
 * A single virtual slot — no dependency on MC's Slot class.
 */
data class VirtualSlot(
    var index: Int,
    val gridType: GridType,
    var x: Int = 0,
    var y: Int = 0
) {
    enum class GridType { PLAYER_MAIN, PLAYER_HOTBAR, NETWORK }
}

/**
 * Reusable virtual slot grid — renders items and handles click detection
 * without using MC's Slot system. Fully portable across MC versions.
 *
 * Positioning is absolute (screen coordinates). Call [moveTo] to reposition.
 */
class VirtualSlotGrid(
    val cols: Int,
    val rows: Int,
    val slotType: VirtualSlot.GridType,
    private val slotStartIndex: Int = 0
) {
    val slots: List<VirtualSlot>
    var x: Int = 0; private set
    var y: Int = 0; private set

    /** Provider that returns the ItemStack for a given slot index and grid type. */
    var stackProvider: ((VirtualSlot) -> ItemStack)? = null

    /** Provider that returns a formatted count string (for network grid large counts). */
    var countFormatter: ((VirtualSlot) -> String?)? = null

    init {
        slots = List(cols * rows) { i ->
            VirtualSlot(slotStartIndex + i, slotType)
        }
    }

    /** Move the entire grid to a new absolute position and update all slot positions. */
    fun moveTo(newX: Int, newY: Int) {
        x = newX
        y = newY
        for ((i, slot) in slots.withIndex()) {
            slot.x = newX + (i % cols) * 18
            slot.y = newY + (i / cols) * 18
        }
    }

    /** Render slot backgrounds. Uses direct blit for performance (1 blit per slot instead of 9). */
    fun renderBackground(graphics: GuiGraphics) {
        // Direct blit of SLOT texture region — same visual as 9-slice at native 18x18 but much faster
        val slot = NineSlice.SLOT
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                graphics.blit(
                    NineSlice.GUI_ATLAS,
                    x + c * 18, y + r * 18,
                    slot.u.toFloat(), slot.v.toFloat(),
                    18, 18, 256, 256
                )
            }
        }
        NineSlice.INVENTORY_BORDER.draw(graphics, x - 2, y - 2, cols * 18 + 4, rows * 18 + 4)
    }

    /**
     * Render all items in the grid.
     * @param scrollOffset For scrollable grids, the number of rows scrolled.
     * @param totalItems For scrollable grids, the total number of items available.
     */
    // Reusable slot for provider callbacks — avoids per-frame allocations
    private val tempSlot = VirtualSlot(0, slotType)

    fun renderItems(graphics: GuiGraphics, scrollOffset: Int = 0, totalItems: Int = slots.size) {
        val font = Minecraft.getInstance().font
        val provider = stackProvider ?: return
        val formatter = countFormatter

        for ((i, slot) in slots.withIndex()) {
            val viewIndex = if (slotType == VirtualSlot.GridType.NETWORK) {
                scrollOffset * cols + i
            } else {
                slot.index
            }

            if (slotType == VirtualSlot.GridType.NETWORK && viewIndex >= totalItems) continue

            // Reuse temp slot to avoid allocation
            tempSlot.index = viewIndex
            tempSlot.x = slot.x
            tempSlot.y = slot.y

            val stack = provider(tempSlot)
            if (stack.isEmpty) continue

            val ix = slot.x + 1
            val iy = slot.y + 1

            graphics.renderItem(stack, ix, iy)

            val customCount = formatter?.invoke(tempSlot)
            if (customCount != null) {
                graphics.renderItemDecorations(font, stack, ix, iy, customCount)
            } else if (stack.count > 1) {
                graphics.renderItemDecorations(font, stack, ix, iy)
            }
        }
    }

    /**
     * Find the slot under the mouse, or null.
     * @param scrollOffset For scrollable grids, the number of rows scrolled.
     */
    // Reusable slot for getSlotAt results
    private val hitSlot = VirtualSlot(0, slotType)

    fun getSlotAt(mouseX: Int, mouseY: Int, scrollOffset: Int = 0): VirtualSlot? {
        // Full 18x18 hit area per slot (no dead zones between slots)
        if (mouseX < x || mouseX >= x + cols * 18 || mouseY < y || mouseY >= y + rows * 18) return null

        val col = (mouseX - x) / 18
        val row = (mouseY - y) / 18
        if (col < 0 || col >= cols || row < 0 || row >= rows) return null

        val i = row * cols + col
        val slot = slots[i]

        hitSlot.index = if (slotType == VirtualSlot.GridType.NETWORK) scrollOffset * cols + i else slot.index
        hitSlot.x = slot.x
        hitSlot.y = slot.y
        return hitSlot
    }

    /** Render a highlight on the hovered slot. */
    fun renderHoverHighlight(graphics: GuiGraphics, mouseX: Int, mouseY: Int, scrollOffset: Int = 0) {
        val slot = getSlotAt(mouseX, mouseY, scrollOffset) ?: return
        val ix = slot.x + 1
        val iy = slot.y + 1
        graphics.fill(ix, iy, ix + 16, iy + 16, 0x80FFFFFF.toInt())
    }

    /** Total pixel width of the grid. */
    val width: Int get() = cols * 18

    /** Total pixel height of the grid. */
    val height: Int get() = rows * 18

    /** Total pixel height for player inventory layout (3x9 + gap + 1x9). */
    fun playerInventoryHeight(gap: Int = 4): Int = 3 * 18 + gap + 18
}
