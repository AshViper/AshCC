"""Generates the 16x16 textures of the rotary autocannon and the railgun (Create Big Cannons content)."""
import math
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/ashcc/textures/block"
OUT.mkdir(parents=True, exist_ok=True)
C = 7.5


def steel(dark=0):
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            c = (0x46 if edge else 0x6e - ((x * 5 + y * 3) % 4) * 3) - dark
            img.putpixel((x, y), (c, c + 2, c + 6, 255))
    return img


def rotary_barrel_side():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(3, 13):
            # three barrels seen from the side with dark gaps between them
            band = (x - 3) % 10
            c = 0x30 if band in (3, 6) else 0x5c + (8 if band in (1, 4, 8) else 0)
            if y in (2, 3, 12, 13):
                c = 0x3a  # clamp rings
            img.putpixel((x, y), (c, c + 2, c + 4, 255))
    return img


def rotary_barrel_end():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - C, y - C)
            if r <= 5.2:
                img.putpixel((x, y), (0x50, 0x52, 0x58, 255))
    for k in range(6):
        a = k * math.pi / 3
        bx, by = C + 3.0 * math.cos(a), C + 3.0 * math.sin(a)
        for y in range(16):
            for x in range(16):
                if math.hypot(x - bx, y - by) <= 1.0:
                    img.putpixel((x, y), (0x12, 0x12, 0x14, 255))
    return img


def rotary_breech_side():
    img = steel()
    for y in (4, 11):
        for x in range(1, 15):
            img.putpixel((x, y), (0x34, 0x36, 0x3a, 255))
    for x in range(5, 11):  # feed slot
        for y in range(6, 10):
            img.putpixel((x, y), (0x22, 0x22, 0x24, 255))
    for x in (2, 13):
        img.putpixel((x, 7), (0xd0, 0x90, 0x30, 255))
    return img


def rotary_breech_end():
    img = steel()
    for y in range(16):
        for x in range(16):
            if math.hypot(x - C, y - C) <= 5.5:
                img.putpixel((x, y), (0x3c, 0x3e, 0x44, 255))
    return img


def rail_side():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(2, 14):
            if x in (2, 3, 12, 13):
                c = (0xb0, 0x70, 0x30, 255) if y % 4 else (0x80, 0x50, 0x20, 255)  # copper rails
            else:
                g = 0x40 + (0x30 if (y + x) % 5 == 0 else 0)
                c = (0x30, 0x34, g + 0x20, 255)  # insulator with coils
            img.putpixel((x, y), c)
    return img


def rail_end():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(2, 14):
        for x in range(2, 14):
            c = (0x50, 0x54, 0x5c, 255)
            if 6 <= x <= 9 and 6 <= y <= 9:
                c = (0x0c, 0x0c, 0x10, 255)  # bore
            elif x in (5, 10) and 5 <= y <= 10:
                c = (0xb0, 0x70, 0x30, 255)  # rail faces
            img.putpixel((x, y), c)
    return img


def breech_side():
    img = steel(6)
    for y in range(3, 13):
        for x in (3, 12):
            img.putpixel((x, y), (0x30, 0x60, 0xd0, 255))  # capacitor bars
    for y in (6, 9):
        for x in range(4, 12):
            img.putpixel((x, y), (0x80, 0x50, 0x20, 255))
    return img


def breech_front():
    img = steel(6)
    for y in range(16):
        for x in range(16):
            if 5 <= x <= 10 and 5 <= y <= 10:
                img.putpixel((x, y), (0x0c, 0x0c, 0x10, 255))
            elif (x in (4, 11) and 4 <= y <= 11) or (y in (4, 11) and 4 <= x <= 11):
                img.putpixel((x, y), (0xb0, 0x70, 0x30, 255))
    return img


def breech_back():
    img = steel(6)
    for y in range(5, 11):
        for x in range(5, 11):
            img.putpixel((x, y), (0x40, 0xa0, 0xff, 255) if (x + y) % 2 else (0x20, 0x50, 0x90, 255))
    return img


def lens_side():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(3, 13):
            if y in (0, 1, 7, 8, 14, 15):
                c = (0x58, 0x5c, 0x66, 255)  # clamp rings
            elif x in (3, 12):
                c = (0x3a, 0x3e, 0x46, 255)
            else:
                g = 0x90 + 0x30 * (1 - abs(x - 7.5) / 4.5)
                c = (int(g * 0.45), int(g * 0.75), int(min(255, g + 0x40)), 255)  # glowing glass tube
            img.putpixel((x, y), c)
    return img


def lens_end():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(3, 13):
        for x in range(3, 13):
            r = math.hypot(x - C, y - C)
            if r <= 4.6:
                g = 1 - r / 4.6
                img.putpixel((x, y), (int(0x60 + 0x9f * g), int(0xa0 + 0x5f * g), 0xff, 255))
            else:
                img.putpixel((x, y), (0x48, 0x4c, 0x56, 255))
    return img


def emitter_side():
    img = steel(10)
    for y in range(3, 13):
        for x in range(3, 13):
            if (x + y) % 3 == 0:
                img.putpixel((x, y), (0xb0, 0x70, 0x30, 255))  # pump coils
    for x in range(2, 14):
        img.putpixel((x, 7), (0x50, 0xa0, 0xff, 255))
        img.putpixel((x, 8), (0x50, 0xa0, 0xff, 255))
    return img


def emitter_front():
    img = steel(10)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - C, y - C)
            if r <= 3.2:
                img.putpixel((x, y), (0xe0, 0xf0, 0xff, 255))
            elif r <= 5.2:
                img.putpixel((x, y), (0x40, 0x80, 0xe0, 255))
    return img


def emitter_back():
    img = steel(10)
    for y in (4, 7, 10):
        for x in range(3, 13):
            img.putpixel((x, y), (0x22, 0x22, 0x26, 255))  # heat sink fins
    return img


for name, fn in [("laser_cannon_lens_side", lens_side), ("laser_cannon_lens_end", lens_end),
                 ("laser_cannon_emitter_side", emitter_side), ("laser_cannon_emitter_front", emitter_front),
                 ("laser_cannon_emitter_back", emitter_back),("rotary_autocannon_barrel_side", rotary_barrel_side), ("rotary_autocannon_barrel_end", rotary_barrel_end),
                 ("rotary_autocannon_breech_side", rotary_breech_side), ("rotary_autocannon_breech_end", rotary_breech_end),
                 ("railgun_rail_side", rail_side), ("railgun_rail_end", rail_end),
                 ("railgun_breech_side", breech_side), ("railgun_breech_front", breech_front), ("railgun_breech_back", breech_back)]:
    fn().save(OUT / f"{name}.png")
print("cbc textures written")
