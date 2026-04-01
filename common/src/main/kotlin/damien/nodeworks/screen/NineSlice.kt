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
     * Draw this 9-slice at the given screen position and size.
     */
    fun draw(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
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

        // =====================================================================
        // Atlas Layout Reference (gui_atlas.png, 256x256)
        // =====================================================================
        //
        // Position   Size    Name                 Insets (L,R,T,B)  Description
        // ---------  ------  -------------------  ----------------  ---------------------------
        // (0,   0)   24x24   WINDOW_FRAME         3, 3, 3, 3        Main window background (#2B2B2B) with gradient border
        // (0,  24)   24x24   TOP_BAR              3, 3, 3, 3        Header bar (#3C3C3C) with border
        // (0,  48)   24x16   TAB_ACTIVE           3, 3, 3, 2        Active tab (#2B2B2B) with blue accent top edge
        // (24, 48)   24x16   TAB_INACTIVE         3, 3, 3, 2        Inactive tab (#222222) with subtle border
        // (48, 48)   24x16   TAB_HOVER            3, 3, 3, 2        Hovered tab (#333333) between active/inactive
        // (0,  64)   24x16   BUTTON               3, 3, 3, 3        Raised button (#3C3C3C) with 3D borders
        // (24, 64)   24x16   BUTTON_HOVER         3, 3, 3, 3        Hovered button (#4A4A4A) brighter 3D borders
        // (48, 64)   24x16   BUTTON_ACTIVE        3, 3, 3, 3        Pressed button (#333333) inverted 3D borders
        // (0,  80)   24x24   PANEL_INSET          3, 3, 3, 3        Recessed content area (#1E1E1E) for editors/lists
        // (0, 104)   18x18   SLOT                 1, 1, 1, 1        Item slot (#1A1A1A) with inset border
        // (24,104)   24x16   INPUT_FIELD          3, 3, 3, 3        Text input (#1A1A1A) with highlighted border
        // (48, 80)   24x16   ROW                  1, 1, 1, 1        Default/odd row (#1E1E1E) with subtle border
        // (72, 80)   24x16   ROW_HIGHLIGHT        1, 1, 1, 1        Alternating/even row stripe (#252525) with subtle border
        // (72,104)   24x3    SEPARATOR            1, 1, 1, 1        Thin horizontal divider line (#3C3C3C)
        // (0, 128)   8x24    SCROLLBAR_TRACK      2, 2, 3, 3        Scrollbar groove (#1A1A1A)
        // (8, 128)   8x16    SCROLLBAR_THUMB      2, 2, 3, 3        Scrollbar thumb (#555555) with grip lines
        // (16,128)   8x16    SCROLLBAR_THUMB_HOVER 2, 2, 3, 3       Scrollbar thumb hovered (#666666)
        //
        // Free space: (72+, 48–79), (96+, 80–103), (48+, 104–127), (24+, 128+), entire rows 152+
        // =====================================================================

        // ---- Pre-built slices ----

        val WINDOW_FRAME         = NineSlice(GUI_ATLAS,  0,   0, 24, 24, 3, 3, 3, 3)
        val TOP_BAR              = NineSlice(GUI_ATLAS,  0,  24, 24, 24, 3, 3, 3, 3)
        val TAB_ACTIVE           = NineSlice(GUI_ATLAS,  0,  48, 24, 16, 3, 3, 3, 2)
        val TAB_INACTIVE         = NineSlice(GUI_ATLAS, 24,  48, 24, 16, 3, 3, 3, 2)
        val TAB_HOVER            = NineSlice(GUI_ATLAS, 48,  48, 24, 16, 3, 3, 3, 2)
        val BUTTON               = NineSlice(GUI_ATLAS,  0,  64, 24, 16, 3, 3, 3, 3)
        val BUTTON_HOVER         = NineSlice(GUI_ATLAS, 24,  64, 24, 16, 3, 3, 3, 3)
        val BUTTON_ACTIVE        = NineSlice(GUI_ATLAS, 48,  64, 24, 16, 3, 3, 3, 3)
        val PANEL_INSET          = NineSlice(GUI_ATLAS,  0,  80, 24, 24, 3, 3, 3, 3)
        val SLOT                 = NineSlice(GUI_ATLAS,  0, 104, 18, 18, 1, 1, 1, 1)
        val INPUT_FIELD          = NineSlice(GUI_ATLAS, 24, 104, 24, 16, 3, 3, 3, 3)
        val ROW                  = NineSlice(GUI_ATLAS, 48,  80, 24, 16, 1, 1, 1, 1)
        val ROW_HIGHLIGHT        = NineSlice(GUI_ATLAS, 72,  80, 24, 16, 1, 1, 1, 1)
        val SEPARATOR            = NineSlice(GUI_ATLAS, 72, 104, 24,  3, 1, 1, 1, 1)
        val SCROLLBAR_TRACK      = NineSlice(GUI_ATLAS,  0, 128,  8, 24, 2, 2, 3, 3)
        val SCROLLBAR_THUMB      = NineSlice(GUI_ATLAS,  8, 128,  8, 16, 2, 2, 3, 3)
        val SCROLLBAR_THUMB_HOVER = NineSlice(GUI_ATLAS, 16, 128, 8, 16, 2, 2, 3, 3)
    }
}
