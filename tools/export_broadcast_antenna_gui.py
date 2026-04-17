"""
Export the current Broadcast Antenna upper-panel GUI (no player inventory) as a single
PNG so it can be repainted as one big static background. 40px of padding is added at
the top of the output to leave room for a custom title-bar / art.

Output: common/src/main/resources/assets/nodeworks/textures/gui/broadcast_antenna_bg.png

Usage:
    python tools/export_broadcast_antenna_gui.py
"""

from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "common" / "src" / "main" / "resources" / "assets" / "nodeworks" / "textures"
GUI_ATLAS = Image.open(ASSETS / "gui" / "gui_atlas.png").convert("RGBA")
ICONS_ATLAS = Image.open(ASSETS / "gui" / "icons.png").convert("RGBA")
PAIRING_IMG = Image.open(ASSETS / "gui" / "pairing_crystal.png").convert("RGBA")

# --- Layout constants (must mirror BroadcastAntennaScreen.kt) ---
TOP_PADDING = 40
FRAME_W = 176
UPPER_H = 80

# Pairing image position inside the upper panel.
PAIRING_IMG_X = 31
PAIRING_IMG_Y = 8

# Right-side recessed upgrade area.
UPGRADE_RECESS_X = 96
UPGRADE_RECESS_Y = 9
UPGRADE_RECESS_W = 72
UPGRADE_RECESS_H = 56
SCREW_SIZE = 6
SCREW_OFFSET = 1
TOP_SCREW_Y_OFFSET = 4

# Upgrade slot position.
UPGRADE_SLOT_X = 124
UPGRADE_SLOT_Y = 30


# --- 9-slice definitions: (u, v, srcW, srcH, left, right, top, bottom) ---
WINDOW_FRAME = (0, 0, 24, 24, 3, 3, 3, 3)
WINDOW_RECESSED = (24, 0, 24, 24, 3, 3, 5, 3)
SLOT = (0, 104, 18, 18, 1, 1, 1, 1)


def crop_atlas(atlas, u, v, w, h):
    return atlas.crop((u, v, u + w, v + h))


def draw_nine_slice(canvas, atlas, ns, x, y, w, h):
    u, v, srcW, srcH, left, right, top, bottom = ns
    midSrcW = srcW - left - right
    midSrcH = srcH - top - bottom
    midDstW = w - left - right
    midDstH = h - top - bottom

    if midDstW <= 0 or midDstH <= 0:
        region = crop_atlas(atlas, u, v, srcW, srcH).resize((w, h), Image.NEAREST)
        canvas.alpha_composite(region, (x, y))
        return

    cx = x + left
    cy = y + top
    srcCx = u + left
    srcCy = v + top

    def paste(dx, dy, dw, dh, su, sv, sw, sh):
        region = crop_atlas(atlas, su, sv, sw, sh)
        if (dw, dh) != (sw, sh):
            region = region.resize((dw, dh), Image.NEAREST)
        canvas.alpha_composite(region, (dx, dy))

    def tile_region(dx, dy, dw, dh, su, sv, sw, sh):
        if dw <= 0 or dh <= 0 or sw <= 0 or sh <= 0:
            return
        tile = crop_atlas(atlas, su, sv, sw, sh)
        for ty_off in range(0, dh, sh):
            for tx_off in range(0, dw, sw):
                tw = min(sw, dw - tx_off)
                th = min(sh, dh - ty_off)
                piece = tile.crop((0, 0, tw, th))
                canvas.alpha_composite(piece, (dx + tx_off, dy + ty_off))

    paste(x, y, left, top, u, v, left, top)
    paste(cx + midDstW, y, right, top, srcCx + midSrcW, v, right, top)
    paste(x, cy + midDstH, left, bottom, u, srcCy + midSrcH, left, bottom)
    paste(cx + midDstW, cy + midDstH, right, bottom, srcCx + midSrcW, srcCy + midSrcH, right, bottom)

    tile_region(cx, y, midDstW, top, srcCx, v, midSrcW, top)
    tile_region(cx, cy + midDstH, midDstW, bottom, srcCx, srcCy + midSrcH, midSrcW, bottom)
    tile_region(x, cy, left, midDstH, u, srcCy, left, midSrcH)
    tile_region(cx + midDstW, cy, right, midDstH, srcCx + midSrcW, srcCy, right, midSrcH)

    tile_region(cx, cy, midDstW, midDstH, srcCx, srcCy, midSrcW, midSrcH)


def main():
    canvas_w = FRAME_W
    canvas_h = TOP_PADDING + UPPER_H
    canvas = Image.new("RGBA", (canvas_w, canvas_h), (0, 0, 0, 0))

    # Shift everything below by TOP_PADDING so the upper panel starts there.
    upper_y = TOP_PADDING

    # Outer WINDOW_FRAME for the upper panel.
    draw_nine_slice(canvas, GUI_ATLAS, WINDOW_FRAME, 0, upper_y, FRAME_W, UPPER_H)

    # Pairing crystal image on the left.
    canvas.alpha_composite(PAIRING_IMG, (PAIRING_IMG_X, upper_y + PAIRING_IMG_Y))

    # Recessed upgrade area on the right.
    recess_x = UPGRADE_RECESS_X
    recess_y = upper_y + UPGRADE_RECESS_Y
    draw_nine_slice(canvas, GUI_ATLAS, WINDOW_RECESSED, recess_x, recess_y, UPGRADE_RECESS_W, UPGRADE_RECESS_H)

    # Four corner screws (top screws offset down 4px).
    screw_u = 10 * 16 + 5   # SMALL_SCREW is col=10 row=1, with drawSmall offset +5
    screw_v = 1 * 16 + 5
    screw_region = crop_atlas(ICONS_ATLAS, screw_u, screw_v, 8, 8).resize((SCREW_SIZE, SCREW_SIZE), Image.NEAREST)
    top_screw_y = recess_y - SCREW_SIZE - SCREW_OFFSET + TOP_SCREW_Y_OFFSET
    bottom_screw_y = recess_y + UPGRADE_RECESS_H + SCREW_OFFSET
    left_x = recess_x - SCREW_SIZE - SCREW_OFFSET
    right_x = recess_x + UPGRADE_RECESS_W + SCREW_OFFSET
    canvas.alpha_composite(screw_region, (left_x, top_screw_y))
    canvas.alpha_composite(screw_region, (right_x, top_screw_y))
    canvas.alpha_composite(screw_region, (left_x, bottom_screw_y))
    canvas.alpha_composite(screw_region, (right_x, bottom_screw_y))

    # Upgrade slot frame (NineSlice.SLOT is 18x18, drawn at slot_pos - 1).
    draw_nine_slice(
        canvas, GUI_ATLAS, SLOT,
        UPGRADE_SLOT_X - 1, upper_y + UPGRADE_SLOT_Y - 1, 18, 18
    )

    out_path = ASSETS / "gui" / "broadcast_antenna_bg.png"
    canvas.save(out_path)
    print(f"Saved {out_path} ({canvas_w}x{canvas_h})")


if __name__ == "__main__":
    main()
