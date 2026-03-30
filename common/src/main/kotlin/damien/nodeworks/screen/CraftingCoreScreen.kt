package damien.nodeworks.screen

import damien.nodeworks.network.CancelCraftPayload
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class CraftingCoreScreen(
    menu: CraftingCoreMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CraftingCoreMenu>(menu, playerInventory, title) {

    private var bufferScrollOffset = 0

    private fun formatCount(count: Int): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }

    init {
        imageWidth = 180
        imageHeight = 140
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Dark background
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2B2B2B.toInt())

        // Top bar
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 20, 0xFF3C3C3C.toInt())
        graphics.fill(leftPos, topPos + 19, leftPos + imageWidth, topPos + 20, 0xFF555555.toInt())
        graphics.drawString(font, title, leftPos + 6, topPos + 6, 0xFFFFFFFF.toInt())

        val contentLeft = leftPos + 8
        val contentTop = topPos + 26

        // Status + current craft item
        val mc = net.minecraft.client.Minecraft.getInstance()
        val level = mc.level
        val coreEntity = level?.getBlockEntity(menu.corePos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity

        val craftItemName = coreEntity?.currentCraftItem ?: ""
        val statusLabel = when {
            !menu.isFormed -> "Not Formed"
            menu.isCrafting && craftItemName.isNotEmpty() -> "Crafting: $craftItemName"
            menu.isCrafting -> "Crafting..."
            else -> "Idle"
        }
        val statusColor = when {
            !menu.isFormed -> 0xFFFF5555.toInt()
            menu.isCrafting -> 0xFF55FF55.toInt()
            else -> 0xFFAAAAAA.toInt()
        }
        graphics.drawString(font, "Status:", contentLeft, contentTop, 0xFFAAAAAA.toInt())
        graphics.drawString(font, statusLabel, contentLeft + 50, contentTop, statusColor)

        // Buffer bar
        val barTop = contentTop + 16
        graphics.drawString(font, "Buffer:", contentLeft, barTop, 0xFFAAAAAA.toInt())

        val barX = contentLeft + 50
        val barW = imageWidth - 66
        val barH = 10

        // Bar background
        graphics.fill(barX, barTop, barX + barW, barTop + barH, 0xFF1E1E1E.toInt())
        // Inset border
        graphics.fill(barX - 1, barTop - 1, barX + barW + 1, barTop, 0xFF555555.toInt())
        graphics.fill(barX - 1, barTop - 1, barX, barTop + barH + 1, 0xFF555555.toInt())
        graphics.fill(barX + barW, barTop - 1, barX + barW + 1, barTop + barH + 1, 0xFF3C3C3C.toInt())
        graphics.fill(barX - 1, barTop + barH, barX + barW + 1, barTop + barH + 1, 0xFF3C3C3C.toInt())

        // Fill
        if (menu.bufferCapacity > 0) {
            val fillW = (barW * menu.bufferUsed.toLong() / menu.bufferCapacity).toInt().coerceAtMost(barW)
            if (fillW > 0) {
                val fillColor = if (menu.bufferUsed > menu.bufferCapacity * 0.9) 0xFFFF5555.toInt() else 0xFF55AA55.toInt()
                graphics.fill(barX, barTop, barX + fillW, barTop + barH, fillColor)
            }
        }

        // Count text
        val countText = "${menu.bufferUsed} / ${menu.bufferCapacity}"
        val countWidth = font.width(countText)
        graphics.drawString(font, countText, barX + (barW - countWidth) / 2, barTop + 1, 0xFFFFFFFF.toInt())

        // Cancel button (only when crafting)
        val btnTop = barTop + 14
        if (menu.isCrafting || menu.bufferUsed > 0) {
            val btnX = contentLeft
            val btnW = 50
            val btnH = 14
            val hovered = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnTop && mouseY < btnTop + btnH
            val bg = if (hovered) 0xFF553333.toInt() else 0xFF442222.toInt()
            graphics.fill(btnX, btnTop, btnX + btnW, btnTop + btnH, bg)
            graphics.fill(btnX, btnTop, btnX + btnW, btnTop + 1, 0xFF664444.toInt())
            graphics.fill(btnX, btnTop + btnH - 1, btnX + btnW, btnTop + btnH, 0xFF331111.toInt())
            val textW = font.width("Cancel")
            graphics.drawString(font, "Cancel", btnX + (btnW - textW) / 2, btnTop + 3, 0xFFFF8888.toInt())
        }

        // Buffer contents or capacity info
        val contentsTop = btnTop + 18
        if (!menu.isFormed) {
            graphics.drawString(font, "Place Crafting Storage adjacent", contentLeft, contentsTop, 0xFF888888.toInt())
            graphics.drawString(font, "to form the CPU", contentLeft, contentsTop + 11, 0xFF888888.toInt())
        } else if (menu.bufferUsed > 0) {
            graphics.drawString(font, "Buffer:", contentLeft, contentsTop, 0xFFAAAAAA.toInt())
            run {
                val contents = menu.clientBufferContents
                val startX = contentLeft
                val startY = contentsTop + 12
                val slotSize = 18
                val cols = (imageWidth - 24) / slotSize  // leave room for scrollbar
                val availHeight = topPos + imageHeight - startY - 4
                val rows = maxOf(1, availHeight / slotSize)
                val totalRows = (contents.size + cols - 1) / cols
                val maxScroll = maxOf(0, totalRows - rows)
                bufferScrollOffset = bufferScrollOffset.coerceIn(0, maxScroll)

                graphics.enableScissor(startX, startY, startX + cols * slotSize, startY + rows * slotSize)
                // Pass 1: backgrounds + items
                for ((i, entry) in contents.withIndex()) {
                    val row = i / cols - bufferScrollOffset
                    val col = i % cols
                    if (row < 0 || row >= rows) continue
                    val ix = startX + col * slotSize
                    val iy = startY + row * slotSize
                    val id = net.minecraft.resources.ResourceLocation.tryParse(entry.first) ?: continue
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: continue
                    val stack = net.minecraft.world.item.ItemStack(item, 1)
                    graphics.fill(ix, iy, ix + 16, iy + 16, 0xFF1A1A1A.toInt())
                    graphics.renderItem(stack, ix, iy)
                }
                // Pass 2: count text on top of all items
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, 200f)
                for ((i, entry) in contents.withIndex()) {
                    val row = i / cols - bufferScrollOffset
                    val col = i % cols
                    if (row < 0 || row >= rows) continue
                    val ix = startX + col * slotSize
                    val iy = startY + row * slotSize
                    val countText = formatCount(entry.second)
                    graphics.drawString(font, countText, ix + 17 - font.width(countText), iy + 9, 0xFFFFFFFF.toInt(), true)
                }
                graphics.pose().popPose()
                graphics.disableScissor()

                // Scrollbar
                if (totalRows > rows) {
                    val sbX = startX + cols * slotSize + 2
                    val sbH = rows * slotSize
                    val thumbH = maxOf(6, sbH * rows / totalRows)
                    val thumbY = startY + (sbH - thumbH) * bufferScrollOffset / maxScroll
                    graphics.fill(sbX, startY, sbX + 3, startY + sbH, 0xFF1A1A1A.toInt())
                    graphics.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, 0xFF555555.toInt())
                }
            }
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (menu.bufferUsed > 0) {
            bufferScrollOffset -= scrollY.toInt()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (menu.isCrafting || menu.bufferUsed > 0) {
            val contentLeft = leftPos + 8
            val btnTop = topPos + 26 + 16 + 14
            val btnW = 50
            val btnH = 14
            if (mouseX >= contentLeft && mouseX < contentLeft + btnW && mouseY >= btnTop && mouseY < btnTop + btnH) {
                PlatformServices.clientNetworking.sendToServer(CancelCraftPayload(menu.corePos))
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }
}
