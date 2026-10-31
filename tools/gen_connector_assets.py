"""Generates the offset connector plate's 16x16 textures (a 3x3 grid face: the ninths it can be offset to) and its item icon."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/ashcc/textures"
OUT = ROOT / "entity"
OUT.mkdir(parents=True, exist_ok=True)
(ROOT / "item").mkdir(parents=True, exist_ok=True)

def base():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            c = 0x4a if edge else 0x78 - ((x * 5 + y * 3) % 4) * 3
            img.putpixel((x, y), (c + 8, c + 4, c - 6, 255))
    return img

# Front: 3x3 cells split by grid lines at 1/3 and 2/3 (pixels 5 and 10), centre cell highlighted
front = base()
for i in range(16):
    for g in (5, 10):
        front.putpixel((g, i), (0x30, 0x2c, 0x26, 255))
        front.putpixel((i, g), (0x30, 0x2c, 0x26, 255))
for y in range(6, 10):
    for x in range(6, 10):
        front.putpixel((x, y), (0xe0, 0xa0, 0x30, 255))
for (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13), (7, 2), (2, 7), (13, 7), (7, 13)]:
    front.putpixel((x, y), (0xb0, 0x80, 0x30, 255))
front.save(OUT / "offset_connector.png")
front.save(ROOT / "item" / "offset_connector.png")

side = base()
for x in range(16):
    side.putpixel((x, 7), (0xe0, 0xa0, 0x30, 255))  # the plate is 1px thick: a stripe band
    side.putpixel((x, 8), (0xe0, 0xa0, 0x30, 255))
side.save(OUT / "offset_connector_side.png")

print("offset connector textures written")
