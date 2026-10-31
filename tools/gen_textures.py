"""Generates the radar's 16x16 placeholder textures."""
import math, sys
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/ashcc/textures/block"
OUT.mkdir(parents=True, exist_ok=True)

def panel():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            c = 0x5a if edge else 0x8a - ((x * 7 + y * 3) % 5) * 3
            img.putpixel((x, y), (c, c + 4, c + 10, 255))
    for (x, y) in [(1, 1), (14, 1), (1, 14), (14, 14)]:
        img.putpixel((x, y), (0xc8, 0xcc, 0xd0, 255))
    return img

side = panel()
for y in (5, 7, 9, 11):  # vent slots
    for x in range(4, 12):
        side.putpixel((x, y), (0x30, 0x33, 0x38, 255))
side.save(OUT / "radar_side.png")

front = panel()
cx, cy = 7.5, 7.5
for y in range(3, 13):
    for x in range(3, 13):
        r = math.hypot(x - cx, y - cy)
        if r <= 5.2:
            g = 0x30
            if abs(r - 2.5) < 0.5 or abs(r - 4.8) < 0.5: g = 0x70
            if abs((y - cy) + (x - cx) * 0.6) < 0.6 and x >= cx: g = 0xd0  # sweep line
            front.putpixel((x, y), (0x10, g, 0x20, 255))
front.putpixel((10, 5), (0xff, 0x50, 0x40, 255))  # blip
front.save(OUT / "radar_front.png")

top = panel()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - cx, y - cy)
        if r <= 6.5:
            c = 0xb0 + int(30 * (1 - r / 6.5))
            if abs(r - 6.0) < 0.6: c = 0x60
            top.putpixel((x, y), (c, c + 2, c + 6, 255))
for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
    top.putpixel((x, y), (0xe0, 0x40, 0x30, 255))
top.save(OUT / "radar_top.png")

bottom = panel()
bottom.save(OUT / "radar_bottom.png")
print("wrote", sorted(p.name for p in OUT.iterdir()))

# ---- Thruster ----
def nozzle(glow):
    img = panel()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - cx, y - cy)
            if r <= 6.8:
                if r > 5.6:
                    c = (0x50, 0x52, 0x58)
                elif glow:
                    k = 1 - r / 5.6
                    c = (0xff, int(0x70 + 0x8f * k), int(0x20 + 0xa0 * k * k))
                else:
                    c = (0x22 - int(r), 0x22 - int(r), 0x26 - int(r))
                img.putpixel((x, y), c + (255,))
    return img

nozzle(False).save(OUT / "thruster_nozzle.png")
nozzle(True).save(OUT / "thruster_nozzle_active.png")

tside = panel()
for x in range(1, 15):  # hazard stripe band
    for y in (6, 7, 8, 9):
        tside.putpixel((x, y), (0xe0, 0xb0, 0x20, 255) if (x + y) % 4 < 2 else (0x30, 0x30, 0x30, 255))
tside.save(OUT / "thruster_side.png")

tback = panel()
for y in range(5, 11):
    for x in range(5, 11):
        tback.putpixel((x, y), (0x40, 0x44, 0x4a, 255))
for (x, y) in [(6, 6), (9, 6), (6, 9), (9, 9)]:
    tback.putpixel((x, y), (0x20, 0xa0, 0x40, 255))  # CC port pins
tback.save(OUT / "thruster_back.png")
print("thruster textures written")

# ---- Link hub / link card ----
hub = panel()
for y in range(4, 12):
    for x in range(4, 12):
        hub.putpixel((x, y), (0x28, 0x2c, 0x34, 255))
for r, col in ((1.5, (0x40, 0xe0, 0xff)), (3.0, (0x20, 0x90, 0xc0)), (4.2, (0x18, 0x60, 0x90))):
    for y in range(16):
        for x in range(16):
            if abs(math.hypot(x - cx, y - cy) - r) < 0.45 and 3 <= x <= 12 and 3 <= y <= 12:
                hub.putpixel((x, y), col + (255,))
hub.save(OUT / "link_hub.png")
hub_top = panel()
for y in range(6, 10):
    for x in range(6, 10):
        hub_top.putpixel((x, y), (0x40, 0xe0, 0xff, 255))
hub_top.save(OUT / "link_hub_top.png")

ITEM = OUT.parent / "item"
ITEM.mkdir(exist_ok=True)
card = Image.new("RGBA", (16, 16))
for y in range(3, 13):
    for x in range(1, 15):
        edge = x in (1, 14) or y in (3, 12)
        card.putpixel((x, y), (0x30, 0x50, 0x70, 255) if edge else (0x50, 0x80, 0xb0, 255))
for x in range(3, 7):  # chip
    for y in range(5, 9):
        card.putpixel((x, y), (0xd0, 0xb0, 0x40, 255))
for i, x in enumerate(range(9, 13)):  # signal bars
    for y in range(10 - i, 11):
        card.putpixel((x, y), (0x40, 0xe0, 0xff, 255))
card.save(ITEM / "link_card.png")
print("link textures written")

# ---- Flap bearing (Create's bearing model; only the side and back are ours) ----
fside = Image.new("RGBA", (16, 16))
for y in range(16):
    for x in range(16):
        if y < 4:
            c = (0x6a, 0x58, 0x40)  # unused by the model, kept wood-like
        elif y in (4, 15) or x in (0, 15):
            c = (0x3c, 0x40, 0x48)
        elif y < 8:
            c = (0x9a, 0x7a, 0x50) if (x + y) % 5 else (0x86, 0x68, 0x44)  # wooden band
        else:
            c = (0x5e, 0x66, 0x72) if (x * 3 + y) % 7 else (0x52, 0x58, 0x62)  # metal casing
        fside.putpixel((x, y), c + (255,))
for x in range(3, 13):  # blue servo stripe
    fside.putpixel((x, 11), (0x30, 0x90, 0xd0, 255))
    fside.putpixel((x, 12), (0x28, 0x70, 0xb0, 255))
fside.save(OUT / "flap_bearing_side.png")

fback = panel()
for y in range(4, 12):
    for x in range(4, 12):
        fback.putpixel((x, y), (0x2c, 0x30, 0x38, 255))
for (x, y) in [(5, 5), (10, 5), (5, 10), (10, 10)]:
    fback.putpixel((x, y), (0x20, 0xa0, 0x40, 255))
for x in range(6, 10):
    fback.putpixel((x, 7), (0x30, 0x90, 0xd0, 255))
    fback.putpixel((x, 8), (0x30, 0x90, 0xd0, 255))
fback.save(OUT / "flap_bearing_back.png")
print("flap bearing textures written")

# ---- Seeker (lens on the front) ----
sfront = panel()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - cx, y - cy)
        if r <= 6.2:
            if r > 5.2:
                c = (0x30, 0x32, 0x38)
            else:
                k = 1 - r / 5.2
                c = (int(0x20 + 0x40 * k), int(0x10 + 0x20 * k), int(0x30 + 0x90 * k))  # dark violet glass
            sfront.putpixel((x, y), c + (255,))
for (x, y) in [(5, 5), (6, 5), (5, 6)]:
    sfront.putpixel((x, y), (0xe0, 0xd0, 0xff, 255))  # glint
for x in range(4, 12):  # crosshair
    if abs(x - 7.5) > 1:
        sfront.putpixel((x, 8), (0xff, 0x50, 0x40, 255))
for y in range(4, 12):
    if abs(y - 7.5) > 1:
        sfront.putpixel((8, y), (0xff, 0x50, 0x40, 255))
sfront.save(OUT / "seeker_front.png")
sside = panel()
for y in range(3, 13):
    sside.putpixel((3, y), (0x80, 0x40, 0xc0, 255))
    sside.putpixel((12, y), (0x80, 0x40, 0xc0, 255))
sside.save(OUT / "seeker_side.png")
print("seeker textures written")

# ---- Quad flap bearing: bearing plate on the four flap faces, plain ends ----
qside = panel()
for y in range(2, 14):
    for x in range(2, 14):
        edge = x in (2, 13) or y in (2, 13)
        qside.putpixel((x, y), (0x9a, 0x7a, 0x50, 255) if not edge else (0x6a, 0x50, 0x34, 255))  # wooden plate
for (x, y) in [(4, 4), (11, 4), (4, 11), (11, 11)]:
    qside.putpixel((x, y), (0x40, 0x40, 0x44, 255))  # bolts
for x in range(6, 10):
    for y in range(6, 10):
        qside.putpixel((x, y), (0x30, 0x90, 0xd0, 255))  # servo hub
qside.save(OUT / "quad_flap_bearing_side.png")
qend = panel()
for i in range(3, 13):  # cross showing the four flap directions
    qend.putpixel((i, 7), (0x30, 0x90, 0xd0, 255))
    qend.putpixel((i, 8), (0x30, 0x90, 0xd0, 255))
    qend.putpixel((7, i), (0x30, 0x90, 0xd0, 255))
    qend.putpixel((8, i), (0x30, 0x90, 0xd0, 255))
qend.save(OUT / "quad_flap_bearing_end.png")
qfront = qend.copy()
for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
    qfront.putpixel((x, y), (0xff, 0x50, 0x40, 255))  # marks FACING
qfront.save(OUT / "quad_flap_bearing_front.png")
print("quad flap bearing textures written")

# ---- Laser ----
def lens(active):
    img = panel()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - cx, y - cy)
            if r <= 6.0:
                if abs(r - 5.5) < 0.6:
                    c = (0x40, 0x42, 0x48)  # housing ring
                elif active:
                    k = max(0.0, 1 - r / 5.0)
                    c = (0xff, int(0x40 + 0xbf * k * k), int(0x30 + 0xb0 * k * k))
                else:
                    k = max(0.0, 1 - r / 5.0)
                    c = (int(0x50 + 0x50 * k), 0x10, int(0x14 + 0x10 * k))
                img.putpixel((x, y), (*c, 255))
    if not active:
        img.putpixel((6, 6), (0xe0, 0x90, 0x90, 255))  # glint
    return img

lens(False).save(OUT / "laser_front.png")
lens(True).save(OUT / "laser_front_active.png")
print("wrote laser textures")

# ---- Servo bearing: casing below, turntable plate (top 6 px of the side) above ----
vside = Image.new("RGBA", (16, 16))
for y in range(16):
    for x in range(16):
        if y < 6:  # turntable rim
            edge = y in (0, 5) or x in (0, 15)
            c = (0x50, 0x56, 0x60) if edge else ((0xa8, 0xae, 0xb8) if (x + y) % 6 else (0x96, 0x9c, 0xa6))
        elif y == 6:
            c = (0x22, 0x24, 0x28)  # gap between plate and body
        else:  # casing
            edge = y == 15 or x in (0, 15)
            c = (0x3c, 0x40, 0x48) if edge else ((0x5e, 0x66, 0x72) if (x * 3 + y) % 7 else (0x52, 0x58, 0x62))
        vside.putpixel((x, y), c + (255,))
for x in range(3, 13):  # blue servo stripe, as on the flap bearings
    vside.putpixel((x, 10), (0x30, 0x90, 0xd0, 255))
    vside.putpixel((x, 11), (0x28, 0x70, 0xb0, 255))
vside.save(OUT / "servo_bearing_side.png")

vtop = panel()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - cx, y - cy)
        if r <= 7.2:
            c = (0xb4, 0xba, 0xc4) if r < 6.2 else (0x60, 0x66, 0x70)
            vtop.putpixel((x, y), c + (255,))
for (x, y) in [(3, 7), (12, 8), (7, 3), (8, 12)]:
    vtop.putpixel((x, y), (0x48, 0x4c, 0x52, 255))  # bolts
for y in range(2, 8):  # pointer towards the top edge so the angle is visible
    vtop.putpixel((7, y), (0x30, 0x90, 0xd0, 255))
    vtop.putpixel((8, y), (0x30, 0x90, 0xd0, 255))
for (x, y) in [(6, 3), (9, 3)]:
    vtop.putpixel((x, y), (0x30, 0x90, 0xd0, 255))
vtop.save(OUT / "servo_bearing_top.png")

vbody = panel()
for y in range(16):
    for x in range(16):
        if math.hypot(x - cx, y - cy) <= 5.5:
            vbody.putpixel((x, y), (0x2c, 0x30, 0x38, 255))
vbody.save(OUT / "servo_bearing_body_top.png")

vback = panel()
for y in range(4, 12):
    for x in range(4, 12):
        vback.putpixel((x, y), (0x2c, 0x30, 0x38, 255))
for (x, y) in [(5, 5), (10, 5), (5, 10), (10, 10)]:
    vback.putpixel((x, y), (0x20, 0xa0, 0x40, 255))  # CC port pins
for x in range(6, 10):
    vback.putpixel((x, 7), (0x30, 0x90, 0xd0, 255))
    vback.putpixel((x, 8), (0x30, 0x90, 0xd0, 255))
vback.save(OUT / "servo_bearing_back.png")
print("servo bearing textures written")

# ---- IFF transponder: status lamp and code display on the sides, antenna on top ----
xside = panel()
for y in range(3, 7):
    for x in range(3, 13):
        xside.putpixel((x, y), (0x10, 0x30, 0x18, 255))  # code display
for x in (4, 6, 7, 9, 11):
    xside.putpixel((x, 4), (0x40, 0xe0, 0x60, 255))
    xside.putpixel((x, 5), (0x30, 0xb0, 0x50, 255))
for (x, y) in [(4, 10), (5, 10), (4, 11), (5, 11)]:
    xside.putpixel((x, y), (0x30, 0xd0, 0x50, 255))  # lamp
for x in range(8, 13):
    xside.putpixel((x, 10), (0x30, 0x33, 0x38, 255))
    xside.putpixel((x, 12), (0x30, 0x33, 0x38, 255))
xside.save(OUT / "transponder_side.png")
xtop = panel()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - cx, y - cy)
        if abs(r - 5.0) < 0.6 or abs(r - 2.6) < 0.6:
            xtop.putpixel((x, y), (0x50, 0x54, 0x5c, 255))
for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
    xtop.putpixel((x, y), (0x40, 0xe0, 0x60, 255))
xtop.save(OUT / "transponder_top.png")
print("transponder textures written")

# ---- Decoy launcher: four launch tubes on the front ----
dfront = panel()
for (tx, ty, rim) in [(4.5, 4.5, (0xe0, 0x60, 0x20)), (10.5, 4.5, (0xc8, 0xcc, 0xd0)), (4.5, 10.5, (0xc8, 0xcc, 0xd0)), (10.5, 10.5, (0xe0, 0x60, 0x20))]:
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - tx, y - ty)
            if r <= 2.6:
                dfront.putpixel((x, y), rim + (255,) if r > 1.7 else (0x18, 0x18, 0x1c, 255))
dfront.save(OUT / "decoy_launcher_front.png")
dside = panel()
for x in range(1, 15):  # orange / white band
    for y in (10, 11, 12):
        dside.putpixel((x, y), (0xe0, 0x60, 0x20, 255) if (x // 2) % 2 == 0 else (0xd8, 0xd8, 0xd8, 255))
for y in range(3, 8):
    dside.putpixel((7, y), (0x30, 0x33, 0x38, 255))
    dside.putpixel((8, y), (0x30, 0x33, 0x38, 255))
dside.save(OUT / "decoy_launcher_side.png")
print("decoy launcher textures written")

# ---- Ammo items: cartridges ----
ITEMS = OUT.parent / "item"
ITEMS.mkdir(parents=True, exist_ok=True)
def cartridge(body, cap, mark):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(2, 14):
        for x in range(5, 11):
            edge = x in (5, 10)
            c = tuple(max(0, v - 0x30) for v in body) if edge else body
            img.putpixel((x, y), c + (255,))
    for y in range(12, 15):
        for x in range(4, 12):
            img.putpixel((x, y), (0xa0, 0x80, 0x30, 255) if y < 14 else (0x70, 0x58, 0x20, 255))  # brass base
    for x in range(6, 10):
        img.putpixel((x, 1), cap + (255,))
        img.putpixel((x, 2), cap + (255,))
    for y in range(5, 10):
        img.putpixel((7, y), mark + (255,))
        img.putpixel((8, y), mark + (255,))
    return img
cartridge((0xd0, 0x30, 0x20), (0xff, 0xd0, 0x40), (0xff, 0xf0, 0xa0)).save(ITEMS / "flare.png")
cartridge((0x90, 0x98, 0xa4), (0xe0, 0xe4, 0xea), (0x40, 0x44, 0x4c)).save(ITEMS / "chaff.png")
print("ammo textures written")


# ---- Anti-gravity core: purple crystal behind a grille on the sides, ring emitter on top/bottom ----
def core_side(active):
    img = panel()
    for y in range(3, 13):
        for x in range(3, 13):
            r = math.hypot(x - cx, y - cy)
            k = max(0.0, 1 - r / 6.5)
            if active:
                c = (int(0x70 + 0x8f * k), int(0x30 + 0x80 * k * k), int(0xb0 + 0x4f * k))
            else:
                c = (int(0x40 + 0x40 * k), int(0x22 + 0x20 * k), int(0x60 + 0x40 * k))
            img.putpixel((x, y), c + (255,))
    for i in range(3, 13):  # grille bars
        for b in (5, 10):
            img.putpixel((b, i), (0x50, 0x54, 0x5c, 255))
            img.putpixel((i, b), (0x50, 0x54, 0x5c, 255))
    return img
def core_top(active):
    img = panel()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - cx, y - cy)
            if abs(r - 5.0) < 0.8 or abs(r - 2.2) < 0.7:
                img.putpixel((x, y), ((0xe0, 0xa0, 0xff) if active else (0x70, 0x48, 0x90)) + (255,))
            elif r < 1.5:
                img.putpixel((x, y), ((0xff, 0xf0, 0xff) if active else (0x50, 0x38, 0x68)) + (255,))
    return img
core_side(False).save(OUT / "antigravity_core_side.png")
core_side(True).save(OUT / "antigravity_core_side_active.png")
core_top(False).save(OUT / "antigravity_core_top.png")
core_top(True).save(OUT / "antigravity_core_top_active.png")
print("antigravity core textures written")
