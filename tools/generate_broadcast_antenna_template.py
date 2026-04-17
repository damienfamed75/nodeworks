"""
Generate a color-coded reference texture for the Broadcast Antenna block model.
Each region corresponds to exactly one face of one element — use it as a starting
point and paint over each area.

Output: common/src/main/resources/assets/nodeworks/textures/block/broadcast_antenna.png
"""

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "common" / "src" / "main" / "resources" / "assets" / "nodeworks" / "textures" / "block" / "broadcast_antenna.png"

W, H = 64, 48
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# (x1, y1, x2, y2, fill_color, label)
regions = [
    # Base (16x6x16)
    (0,  0, 16, 16, (180,  70,  70), "BT"),   # base top
    (16, 0, 32, 16, (140,  50,  50), "BB"),   # base bottom
    (0,  16, 16, 22, (200, 100, 100), "BN"),  # base north
    (16, 16, 32, 22, (200, 100, 100), "BS"),  # base south
    (32, 16, 48, 22, (200, 100, 100), "BE"),  # base east
    (48, 16, 64, 22, (200, 100, 100), "BW"),  # base west

    # Rod / boom (2x18x2) — top/bottom/sides
    (0,  22, 2,  24, (90, 160, 220), None),   # rod top
    (2,  22, 4,  24, (70, 140, 200), None),   # rod bottom
    (0,  24, 2,  42, (100, 180, 240), None),  # rod north
    (2,  24, 4,  42, (100, 180, 240), None),  # rod south
    (4,  24, 6,  42, (100, 180, 240), None),  # rod east
    (6,  24, 8,  42, (100, 180, 240), None),  # rod west

    # Reflector (10x2x2) — longest crossbar, yellows
    (4,  22, 14, 24, (220, 200,  80), None),  # C1 up
    (14, 22, 24, 24, (200, 180,  60), None),  # C1 down
    (8,  26, 18, 28, (240, 220, 100), None),  # C1 north
    (8,  28, 18, 30, (240, 220, 100), None),  # C1 south
    (8,  24, 10, 26, (220, 200,  80), None),  # C1 east end
    (10, 24, 12, 26, (220, 200,  80), None),  # C1 west end

    # Driven (8x2x2) — greens
    (24, 22, 32, 24, ( 90, 180,  90), None),  # C2 up
    (32, 22, 40, 24, ( 70, 160,  70), None),  # C2 down
    (18, 26, 26, 28, (110, 200, 110), None),  # C2 north
    (18, 28, 26, 30, (110, 200, 110), None),  # C2 south
    (12, 24, 14, 26, ( 90, 180,  90), None),  # C2 east end
    (14, 24, 16, 26, ( 90, 180,  90), None),  # C2 west end

    # Director (6x2x2) — purples
    (40, 22, 46, 24, (160,  90, 200), None),  # C3 up
    (46, 22, 52, 24, (140,  70, 180), None),  # C3 down
    (26, 26, 32, 28, (180, 110, 220), None),  # C3 north
    (26, 28, 32, 30, (180, 110, 220), None),  # C3 south
    (16, 24, 18, 26, (160,  90, 200), None),  # C3 east end
    (18, 24, 20, 26, (160,  90, 200), None),  # C3 west end
]

for (x1, y1, x2, y2, color, label) in regions:
    draw.rectangle([x1, y1, x2 - 1, y2 - 1], fill=color + (255,))

# 1px-thick boundary outlines for readability (black).
for (x1, y1, x2, y2, _, _) in regions:
    draw.rectangle([x1, y1, x2 - 1, y2 - 1], outline=(0, 0, 0, 255))

# Labels for the larger regions only — 2px-tall glyphs won't fit on tiny faces.
for (x1, y1, x2, y2, _, label) in regions:
    if label is None:
        continue
    draw.text((x1 + 1, y1 + 1), label, fill=(0, 0, 0, 255))

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print(f"Saved {OUT} ({W}x{H})")
