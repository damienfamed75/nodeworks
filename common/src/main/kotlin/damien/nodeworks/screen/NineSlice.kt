package damien.nodeworks.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

/**
 * 9-slice texture renderer for scalable GUI elements.
 *
 * A 9-slice texture is divided into 9 regions by 4 inset values (left, right, top, bottom).
 * The 4 corners are drawn at fixed size, the 4 edges stretch along one axis,
 * and the center stretches in both directions.
 *
 * ```
 *  TL | Top  | TR
 *  ---+------+---
 *  L  | Mid  | R
 *  ---+------+---
 *  BL | Bot  | BR
 * ```
 *
 * @param texture   ResourceLocation of the texture file (typically 256x256)
 * @param u         X offset of the slice region in the atlas (pixels)
 * @param v         Y offset of the slice region in the atlas (pixels)
 * @param srcWidth  Width of the full source region in the atlas
 * @param srcHeight Height of the full source region in the atlas
 * @param left      Left inset (width of left column)
 * @param right     Right inset (width of right column)
 * @param top       Top inset (height of top row)
 * @param bottom    Bottom inset (height of bottom row)
 * @param texW      Full texture atlas width (default 256)
 * @param texH      Full texture atlas height (default 256)
 */
class NineSlice(
    val texture: ResourceLocation,
    val u: Int,
    val v: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int,
    val texW: Int = 256,
    val texH: Int = 256
) {
    /**
     * Draw this 9-slice tinted with an RGB color. Supports semi-transparent atlas pixels.
     */
    fun drawTinted(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int, alpha: Float = 1f) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, alpha)
        drawInner(graphics, x, y, width, height)
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        com.mojang.blaze3d.systems.RenderSystem.disableBlend()
    }

    /**
     * Draw this 9-slice at the given screen position and size.
     */
    fun draw(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        drawInner(graphics, x, y, width, height)
    }

    private fun drawInner(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        val midSrcW = srcWidth - left - right
        val midSrcH = srcHeight - top - bottom
        val midDstW = width - left - right
        val midDstH = height - top - bottom

        // Clamp: if target is smaller than insets, just draw center
        if (midDstW <= 0 || midDstH <= 0) {
            blitRegion(graphics, x, y, width, height, u, v, srcWidth, srcHeight)
            return
        }

        val cx = x + left
        val cy = y + top
        val srcCx = u + left
        val srcCy = v + top

        // Top-left corner
        blitRegion(graphics, x, y, left, top, u, v, left, top)
        // Top edge (tile horizontally)
        tileH(graphics, cx, y, midDstW, top, srcCx, v, midSrcW, top)
        // Top-right corner
        blitRegion(graphics, cx + midDstW, y, right, top, srcCx + midSrcW, v, right, top)

        // Left edge (tile vertically)
        tileV(graphics, x, cy, left, midDstH, u, srcCy, left, midSrcH)
        // Center (tile both axes)
        tileBoth(graphics, cx, cy, midDstW, midDstH, srcCx, srcCy, midSrcW, midSrcH)
        // Right edge (tile vertically)
        tileV(graphics, cx + midDstW, cy, right, midDstH, srcCx + midSrcW, srcCy, right, midSrcH)

        // Bottom-left corner
        blitRegion(graphics, x, cy + midDstH, left, bottom, u, srcCy + midSrcH, left, bottom)
        // Bottom edge (tile horizontally)
        tileH(graphics, cx, cy + midDstH, midDstW, bottom, srcCx, srcCy + midSrcH, midSrcW, bottom)
        // Bottom-right corner
        blitRegion(graphics, cx + midDstW, cy + midDstH, right, bottom, srcCx + midSrcW, srcCy + midSrcH, right, bottom)
    }

    /** Tile a source region horizontally to fill drawW, at fixed drawH. */
    private fun tileH(
        graphics: GuiGraphics,
        screenX: Int, screenY: Int,
        drawW: Int, drawH: Int,
        srcX: Int, srcY: Int,
        srcW: Int, srcH: Int
    ) {
        if (drawW <= 0 || drawH <= 0 || srcW <= 0) return
        var cx = 0
        while (cx < drawW) {
            val w = minOf(srcW, drawW - cx)
            blitRegion(graphics, screenX + cx, screenY, w, drawH, srcX, srcY, w, srcH)
            cx += srcW
        }
    }

    /** Tile a source region vertically to fill drawH, at fixed drawW. */
    private fun tileV(
        graphics: GuiGraphics,
        screenX: Int, screenY: Int,
        drawW: Int, drawH: Int,
        srcX: Int, srcY: Int,
        srcW: Int, srcH: Int
    ) {
        if (drawW <= 0 || drawH <= 0 || srcH <= 0) return
        var cy = 0
        while (cy < drawH) {
            val h = minOf(srcH, drawH - cy)
            blitRegion(graphics, screenX, screenY + cy, drawW, h, srcX, srcY, srcW, h)
            cy += srcH
        }
    }

    /** Tile a source region in both axes to fill drawW x drawH. */
    private fun tileBoth(
        graphics: GuiGraphics,
        screenX: Int, screenY: Int,
        drawW: Int, drawH: Int,
        srcX: Int, srcY: Int,
        srcW: Int, srcH: Int
    ) {
        if (drawW <= 0 || drawH <= 0 || srcW <= 0 || srcH <= 0) return
        var cy = 0
        while (cy < drawH) {
            val h = minOf(srcH, drawH - cy)
            var cx = 0
            while (cx < drawW) {
                val w = minOf(srcW, drawW - cx)
                blitRegion(graphics, screenX + cx, screenY + cy, w, h, srcX, srcY, w, h)
                cx += srcW
            }
            cy += srcH
        }
    }

    private fun blitRegion(
        graphics: GuiGraphics,
        screenX: Int, screenY: Int,
        drawW: Int, drawH: Int,
        srcX: Int, srcY: Int,
        srcW: Int, srcH: Int
    ) {
        if (drawW <= 0 || drawH <= 0) return
        graphics.blit(
            texture,
            screenX, screenY,
            drawW, drawH,
            srcX.toFloat(), srcY.toFloat(),
            srcW, srcH,
            texW, texH
        )
    }

    companion object {
        /** The shared GUI atlas containing all 9-slice regions. */
        val GUI_ATLAS = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/gui/gui_atlas.png")

        /**
         * Draw a grid of slots with a CONTENT_BORDER overlay.
         * The border is inset 1px on each side to cover the outer edge of the edge slots.
         */
        fun drawSlotGrid(graphics: GuiGraphics, x: Int, y: Int, cols: Int, rows: Int) {
            // Direct blit at native size — 1 blit per slot instead of 9
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    graphics.blit(
                        GUI_ATLAS, x + c * 18, y + r * 18,
                        SLOT.u.toFloat(), SLOT.v.toFloat(),
                        18, 18, 256, 256
                    )
                }
            }
            INVENTORY_BORDER.draw(graphics, x - 2, y - 2, cols * 18 + 4, rows * 18 + 4)
        }

        /**
         * Draw the standard 36-slot player inventory (3x9 main + 1x9 hotbar with gap).
         */
        fun drawPlayerInventory(graphics: GuiGraphics, x: Int, y: Int, gap: Int = 4) {
            // Main inventory (3x9)
            drawSlotGrid(graphics, x, y, 9, 3)
            // Hotbar (1x9)
            drawSlotGrid(graphics, x, y + 3 * 18 + gap, 9, 1)
        }

        /** Visual text offset from top of the TOP_BAR — accounts for the top-heavy 9-slice. */
        const val TITLE_TEXT_Y = 7

        /**
         * Draw a TOP_BAR with a left-aligned title, visually centered.
         * Use this for consistent title bars across all screens.
         */
        fun drawTitleBar(
            graphics: GuiGraphics,
            font: net.minecraft.client.gui.Font,
            title: net.minecraft.network.chat.Component,
            x: Int,
            y: Int,
            width: Int,
            height: Int = 20,
            trimColor: Int = -1
        ) {
            TOP_BAR.draw(graphics, x, y, width, height)
            if (trimColor >= 0) {
                // Brighten the trim color by 50%
                // val r = minOf(255, ((trimColor shr 16) and 0xFF) * 3 / 2)
                // val g = minOf(255, ((trimColor shr 8) and 0xFF) * 3 / 2)
                // val b = minOf(255, (trimColor and 0xFF) * 3 / 2)
                // val brightened = (r shl 16) or (g shl 8) or b
                // TITLE_TRIM.drawTinted(graphics, x, y, width, height, brightened)
                TITLE_TRIM.drawTinted(graphics, x, y, width, height, trimColor, alpha = 0.7f)
            }
            graphics.drawString(font, title, x + 6, y + TITLE_TEXT_Y, 0xFFFFFFFF.toInt())
        }

        // =====================================================================
        // Atlas Layout Reference (gui_atlas.png, 256x256)
        // =====================================================================
        //
        // Position   Size    Name                 Insets (L,R,T,B)  Description
        // ---------  ------  -------------------  ----------------  ---------------------------
        // (0,   0)   24x24   WINDOW_FRAME         3, 3, 3, 3        Main window background (#2B2B2B) with gradient border
        // (24,  0)   24x24   WINDOW_RECESSED      3, 3, 3, 3        Recessed window panel — darker inset variant of WINDOW_FRAME
        // (0,  24)   24x24   TOP_BAR              3, 3, 3, 3        Header bar (#3C3C3C) with border
        // (24, 24)   24x24   TITLE_TRIM           3, 3, 3, 3        White trim overlay for title bar — tint with network color
        // (0,  48)   24x16   TAB_ACTIVE           3, 3, 3, 2        Active tab (#2B2B2B) with blue accent top edge
        // (24, 48)   24x16   TAB_INACTIVE         3, 3, 3, 2        Inactive tab (#222222) with subtle border
        // (48, 48)   24x16   TAB_HOVER            3, 3, 3, 2        Hovered tab (#333333) between active/inactive
        // (72, 48)   24x16   TAB_TRIM             3, 3, 3, 2        White trim overlay for active tab — tint with network color
        // (0,  64)   24x16   BUTTON               3, 3, 3, 3        Raised button (#3C3C3C) with 3D borders
        // (24, 64)   24x16   BUTTON_HOVER         3, 3, 3, 3        Hovered button (#4A4A4A) brighter 3D borders
        // (48, 64)   24x16   BUTTON_ACTIVE        3, 3, 3, 3        Pressed button (#333333) inverted 3D borders
        // (0,  80)   24x24   PANEL_INSET          3, 3, 3, 3        Recessed content area (#1E1E1E) for editors/lists
        // (0, 104)   18x18   SLOT                 1, 1, 1, 1        Item slot (#1A1A1A) with inset border
        // (24,104)   24x16   INPUT_FIELD          3, 3, 3, 3        Text input (#1A1A1A) with highlighted border
        // (24, 80)   24x24   CONTENT_BORDER       3, 3, 3, 3        Inset frame with transparent center — overlay on top of content
        // (48, 80)   24x16   ROW                  1, 1, 1, 1        Default/odd row (#1E1E1E) with subtle border
        // (72, 80)   24x16   ROW_HIGHLIGHT        1, 1, 1, 1        Alternating/even row stripe (#252525) with subtle border
        // (72,104)   24x3    SEPARATOR            1, 1, 1, 1        Thin horizontal divider line (#3C3C3C)
        // (96,104)   24x6    SEPARATOR_BAR        1, 1, 1, 1        Horizontal bar for thin gaps (#2B2B2B fill, bordered)
        // (0, 128)   8x24    SCROLLBAR_TRACK      2, 2, 3, 3        Scrollbar groove (#1A1A1A)
        // (8, 128)   8x16    SCROLLBAR_THUMB      2, 2, 3, 3        Scrollbar thumb (#555555) with grip lines
        // (16,128)   8x16    SCROLLBAR_THUMB_HOVER 2, 2, 3, 3       Scrollbar thumb hovered (#666666)
        //
        // (72, 64)   48x16   TOGGLE_ACTIVE        3, 3, 3, 3        Toggle switch ON state
        // (120,64)   48x16   TOGGLE_INACTIVE      3, 3, 3, 3        Toggle switch OFF state
        //
        // Free space: (72+, 48–63), (160+, 64–79), (96+, 80–103), (48+, 104–127), (24+, 128+), entire rows 152+
        // =====================================================================

        // ---- Pre-built slices ----

        val WINDOW_FRAME = NineSlice(GUI_ATLAS, 0, 0, 24, 24, 3, 3, 3, 3)
        val WINDOW_RECESSED = NineSlice(GUI_ATLAS, 24, 0, 24, 24, 3, 3, 5, 3)
        val TOP_BAR = NineSlice(GUI_ATLAS, 0, 24, 24, 24, 3, 3, 3, 3)
        val TITLE_TRIM = NineSlice(GUI_ATLAS, 24, 24, 24, 24, 3, 3, 3, 3)
        val TAB_ACTIVE = NineSlice(GUI_ATLAS, 0, 48, 24, 16, 3, 3, 3, 2)
        val TAB_INACTIVE = NineSlice(GUI_ATLAS, 24, 48, 24, 16, 3, 3, 3, 2)
        val TAB_HOVER = NineSlice(GUI_ATLAS, 48, 48, 24, 16, 3, 3, 3, 2)
        val TAB_TRIM = NineSlice(GUI_ATLAS, 72, 48, 24, 16, 3, 3, 3, 2)
        val PILL = NineSlice(GUI_ATLAS, 96, 48, 24, 16, 5, 5, 5, 5)
        val BUTTON = NineSlice(GUI_ATLAS, 0, 64, 24, 16, 3, 3, 3, 3)
        val BUTTON_HOVER = NineSlice(GUI_ATLAS, 24, 64, 24, 16, 3, 3, 3, 3)
        val BUTTON_ACTIVE = NineSlice(GUI_ATLAS, 48, 64, 24, 16, 3, 3, 3, 3)
        val PANEL_INSET = NineSlice(GUI_ATLAS, 0, 80, 24, 24, 3, 3, 3, 3)
        val SLOT = NineSlice(GUI_ATLAS, 0, 104, 18, 18, 1, 1, 1, 1)
        val INVENTORY_BORDER = NineSlice(GUI_ATLAS, 48, 104, 24, 24, 2, 2, 2, 2)
        val INPUT_FIELD = NineSlice(GUI_ATLAS, 24, 104, 24, 16, 3, 3, 3, 3)
        val CONTENT_BORDER = NineSlice(GUI_ATLAS, 24, 80, 24, 24, 3, 3, 3, 3)
        val INSPECTOR_H1 = NineSlice(GUI_ATLAS, 96, 80, 24, 24, 3, 3, 3, 3)
        val INSPECTOR_H2 = NineSlice(GUI_ATLAS, 120, 80, 24, 24, 3, 3, 3, 3)
        val TOOLTIP = NineSlice(GUI_ATLAS, 144, 80, 24, 24, 3, 3, 3, 3)
        val ROW = NineSlice(GUI_ATLAS, 48, 80, 24, 16, 1, 1, 1, 1)
        val ROW_HIGHLIGHT = NineSlice(GUI_ATLAS, 72, 80, 24, 16, 1, 1, 1, 1)
        val SEPARATOR = NineSlice(GUI_ATLAS, 72, 104, 24, 3, 1, 1, 1, 1)
        val SEPARATOR_BAR = NineSlice(GUI_ATLAS, 96, 104, 24, 6, 1, 1, 1, 1)
        val TOGGLE_ACTIVE = NineSlice(GUI_ATLAS, 72, 64, 48, 16, 3, 3, 3, 3)
        val TOGGLE_INACTIVE = NineSlice(GUI_ATLAS, 120, 64, 48, 16, 3, 3, 3, 3)
        val SCROLLBAR_TRACK = NineSlice(GUI_ATLAS, 0, 128, 8, 24, 2, 2, 3, 3)
        val SCROLLBAR_THUMB = NineSlice(GUI_ATLAS, 8, 128, 8, 16, 2, 2, 3, 3)
        val SCROLLBAR_THUMB_HOVER = NineSlice(GUI_ATLAS, 16, 128, 8, 16, 2, 2, 3, 3)
    }
}
