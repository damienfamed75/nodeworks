"""
Composites the Processing Set upper-panel background from the GUI atlas and
icons atlas PNGs, producing a static texture that the screen blits in one call
instead of ~20 individual 9-slice draws.

Output: common/src/main/resources/assets/nodeworks/textures/gui/processing_set_bg.png

Usage:
    python tools/export_processing_set_bg.py
"""

from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "common" / "src" / "main" / "resources" / "assets" / "nodeworks" / "textures" / "gui"
GUI_ATLAS = Image.open(ASSETS / "gui_atlas.png").convert("RGBA")
ICONS_ATLAS = Image.open(ASSETS / "icons.png").convert("RGBA")

# --- Layout constants (must match ProcessingSetScreen.kt) ---
FRAME_W = 180
PANEL_Y = 76
PANEL_H = 42
UPPER_FRAME_H = PANEL_Y + PANEL_H + 2  # 120

SECTION_LABEL_Y = 6
INPUT_COL_X = 36
OUTPUT_COL_X = 128
INPUT_SECTION_Y = 18
INPUT_SECTION_H = 54
ARROW_ICON_SIZE = 12
ARROW_OFFSET_X = -2

PANEL_X = 10
PANEL_W = 160
SCREW_SIZE = 6
SCREW_OFFSET = 1

PANEL_CONTROL_Y = 97
STEPPER_BTN_SIZE = 14
TIMEOUT_GROUP_CENTER_X = 53
TIMEOUT_ENTRY_W = 26
STEPPER_GAP = 2
TIMEOUT_GROUP_W = STEPPER_BTN_SIZE + STEPPER_GAP + TIMEOUT_ENTRY_W + STEPPER_GAP + STEPPER_BTN_SIZE
TIMEOUT_MINUS_X = TIMEOUT_GROUP_CENTER_X - TIMEOUT_GROUP_W // 2
TIMEOUT_ENTRY_X = TIMEOUT_MINUS_X + STEPPER_BTN_SIZE + STEPPER_GAP
TIMEOUT_PLUS_X = TIMEOUT_ENTRY_X + TIMEOUT_ENTRY_W + STEPPER_GAP

CLEAR_BTN_SIZE = 14
CLEAR_BTN_X = 14
CLEAR_BTN_Y = INPUT_SECTION_Y + (INPUT_SECTION_H - CLEAR_BTN_SIZE) // 2


# --- 9-slice definitions: (u, v, srcW, srcH, left, right, top, bottom) ---
WINDOW_FRAME = (0, 0, 24, 24, 3, 3, 3, 3)
WINDOW_RECESSED = (24, 0, 24, 24, 3, 3, 5, 3)
SLOT = (0, 104, 18, 18, 1, 1, 1, 1)
BUTTON = (0, 64, 24, 16, 3, 3, 3, 3)


def crop_atlas(atlas: Image.Image, u: int, v: int, w: int, h: int) -> Image.Image:
    return atlas.crop((u, v, u + w, v + h))


def draw_nine_slice(canvas: Image.Image, atlas: Image.Image, ns, x: int, y: int, w: int, h: int):
    """Draw a 9-slice element with tiling onto canvas."""
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
        """Tile a source region to fill destination."""
        if dw <= 0 or dh <= 0 or sw <= 0 or sh <= 0:
            return
        tile = crop_atlas(atlas, su, sv, sw, sh)
        for ty_off in range(0, dh, sh):
            for tx_off in range(0, dw, sw):
                tw = min(sw, dw - tx_off)
                th = min(sh, dh - ty_off)
                piece = tile.crop((0, 0, tw, th))
                canvas.alpha_composite(piece, (dx + tx_off, dy + ty_off))

    # 4 corners
    paste(x, y, left, top, u, v, left, top)
    paste(cx + midDstW, y, right, top, srcCx + midSrcW, v, right, top)
    paste(x, cy + midDstH, left, bottom, u, srcCy + midSrcH, left, bottom)
    paste(cx + midDstW, cy + midDstH, right, bottom, srcCx + midSrcW, srcCy + midSrcH, right, bottom)

    # 4 edges (tiled)
    tile_region(cx, y, midDstW, top, srcCx, v, midSrcW, top)
    tile_region(cx, cy + midDstH, midDstW, bottom, srcCx, srcCy + midSrcH, midSrcW, bottom)
    tile_region(x, cy, left, midDstH, u, srcCy, left, midSrcH)
    tile_region(cx + midDstW, cy, right, midDstH, srcCx + midSrcW, srcCy, right, midSrcH)

    # Center (tiled)
    tile_region(cx, cy, midDstW, midDstH, srcCx, srcCy, midSrcW, midSrcH)


def draw_icon(canvas: Image.Image, col: int, row: int, x: int, y: int, size: int, tint: tuple[int, ...] | None = None):
    """Draw a 16x16 icon cell from icons.png scaled to size, optionally tinted."""
    u = col * 16
    v = row * 16
    icon = crop_atlas(ICONS_ATLAS, u, v, 16, 16)
    if size != 16:
        icon = icon.resize((size, size), Image.NEAREST)
    if tint is not None:
        r, g, b = tint
        pixels = icon.load()
        for py in range(icon.height):
            for px in range(icon.width):
                pr, pg, pb, pa = pixels[px, py]
                pixels[px, py] = (pr * r // 255, pg * g // 255, pb * b // 255, pa)
    canvas.alpha_composite(icon, (x, y))


def draw_icon_topleft(canvas: Image.Image, col: int, row: int, x: int, y: int, w: int, h: int, tint: tuple[int, ...] | None = None):
    """Draw the top-left w×h pixels of a 16×16 icon cell, optionally tinted."""
    u = col * 16
    v = row * 16
    icon = crop_atlas(ICONS_ATLAS, u, v, w, h)
    if tint is not None:
        r, g, b = tint
        pixels = icon.load()
        for py in range(icon.height):
            for px in range(icon.width):
                pr, pg, pb, pa = pixels[px, py]
                pixels[px, py] = (pr * r // 255, pg * g // 255, pb * b // 255, pa)
    canvas.alpha_composite(icon, (x, y))


def main():
    canvas = Image.new("RGBA", (FRAME_W, UPPER_FRAME_H), (0, 0, 0, 0))

    # Outer WINDOW_FRAME
    draw_nine_slice(canvas, GUI_ATLAS, WINDOW_FRAME, 0, 0, FRAME_W, UPPER_FRAME_H)

    # Clear-all button (non-hover)
    draw_nine_slice(canvas, GUI_ATLAS, BUTTON, CLEAR_BTN_X, CLEAR_BTN_Y, CLEAR_BTN_SIZE, CLEAR_BTN_SIZE)
    # X_SMALL icon (5×5 in top-left of 16×16 cell), col=15, row=1
    x_off = CLEAR_BTN_X + (CLEAR_BTN_SIZE - 5) // 2
    y_off = CLEAR_BTN_Y + (CLEAR_BTN_SIZE - 5) // 2
    draw_icon_topleft(canvas, 15, 1, x_off, y_off, 5, 5)

    # Input 3×3 slot frames
    for row in range(3):
        for col in range(3):
            sx = INPUT_COL_X + col * 18
            sy = INPUT_SECTION_Y + row * 18
            draw_nine_slice(canvas, GUI_ATLAS, SLOT, sx - 1, sy - 1, 18, 18)

    # Output 3×1 slot frames
    for i in range(3):
        sx = OUTPUT_COL_X
        sy = INPUT_SECTION_Y + i * 18
        draw_nine_slice(canvas, GUI_ATLAS, SLOT, sx - 1, sy - 1, 18, 18)

    # Crafting arrow (Icons col=2, row=0, tinted gray)
    arrow_gap_center = (INPUT_COL_X + 54 + OUTPUT_COL_X) // 2
    arrow_x = arrow_gap_center - ARROW_ICON_SIZE // 2 + ARROW_OFFSET_X
    arrow_y = INPUT_SECTION_Y + (INPUT_SECTION_H - ARROW_ICON_SIZE) // 2
    draw_icon(canvas, 2, 0, arrow_x, arrow_y, ARROW_ICON_SIZE, tint=(0x88, 0x88, 0x88))

    # Recessed control panel
    draw_nine_slice(canvas, GUI_ATLAS, WINDOW_RECESSED, PANEL_X, PANEL_Y, PANEL_W, PANEL_H)

    # Top screws (SMALL_SCREW = col 10, row 1, drawSmall uses u+5,v+5 region 8×8 then drawn at SCREW_SIZE)
    screw_u = 10 * 16 + 5
    screw_v = 1 * 16 + 5
    screw_region = crop_atlas(ICONS_ATLAS, screw_u, screw_v, 8, 8)
    screw_region = screw_region.resize((SCREW_SIZE, SCREW_SIZE), Image.NEAREST)
    stl_x = PANEL_X - SCREW_SIZE - SCREW_OFFSET
    stl_y = PANEL_Y - SCREW_SIZE - SCREW_OFFSET
    str_x = PANEL_X + PANEL_W + SCREW_OFFSET
    canvas.alpha_composite(screw_region, (stl_x, stl_y))
    canvas.alpha_composite(screw_region, (str_x, stl_y))

    # Timeout stepper [-] and [+] buttons (non-hover)
    draw_nine_slice(canvas, GUI_ATLAS, BUTTON, TIMEOUT_MINUS_X, PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE)
    draw_nine_slice(canvas, GUI_ATLAS, BUTTON, TIMEOUT_PLUS_X, PANEL_CONTROL_Y, STEPPER_BTN_SIZE, STEPPER_BTN_SIZE)

    # Save
    out_path = ASSETS / "processing_set_bg.png"
    canvas.save(out_path)
    print(f"Saved {out_path} ({FRAME_W}x{UPPER_FRAME_H})")


if __name__ == "__main__":
    main()
