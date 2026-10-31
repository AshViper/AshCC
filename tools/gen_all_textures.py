#!/usr/bin/env python3
"""AshCC texture set, redrawn in one unified style (replaces the scattered gen_* texture scripts' output).

Style: dark gunmetal hulls with a top-left light, beveled panel frames with corner bolts, and a small shared accent
palette (cyan sensors, violet antigravity, red laser optics, amber warnings). Every texture is 16x16; the ones that
had transparency keep it. Run from anywhere:  python3 tools/gen_all_textures.py
"""
from PIL import Image, ImageDraw
import hashlib
import os
import random

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "ashcc", "textures")

# Palette (RGB)
DEEP = (30, 33, 38)
FRAME = (52, 56, 63)
PANEL = (76, 82, 91)
PANEL_D = (62, 67, 75)
LIGHT = (106, 113, 124)
HI = (146, 154, 167)
EDGE = (20, 22, 26)
CYAN = (72, 216, 236)
CYAN_D = (22, 104, 122)
AMBER = (243, 165, 62)
RED = (232, 74, 80)
RED_D = (122, 30, 36)
VIOLET = (166, 110, 238)
VIOLET_D = (88, 50, 146)
GREEN = (92, 219, 122)
ORANGE = (242, 122, 42)
BLUE = (74, 128, 232)


def seed_of(name):
    return int(hashlib.md5(name.encode()).hexdigest()[:8], 16)


def canvas(name, base=PANEL):
    """A noise-dusted opaque 16x16 in the given base colour."""
    im = Image.new("RGBA", (16, 16), base + (255,))
    rnd = random.Random(seed_of(name))
    px = im.load()
    for y in range(16):
        for x in range(16):
            n = rnd.randint(-6, 6)
            r, g, b, a = px[x, y]
            px[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), a)
    return im


def blank():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def bevel(im, inset=0, bolts=True):
    """Panel frame: dark rim, light top/left, dark bottom/right, corner bolts."""
    d = ImageDraw.Draw(im)
    d.rectangle([inset, inset, 15 - inset, 15 - inset], outline=EDGE + (255,))
    d.line([(inset + 1, inset + 1), (14 - inset, inset + 1)], fill=HI + (255,))
    d.line([(inset + 1, inset + 1), (inset + 1, 14 - inset)], fill=LIGHT + (255,))
    d.line([(inset + 1, 14 - inset), (14 - inset, 14 - inset)], fill=DEEP + (255,))
    d.line([(14 - inset, inset + 1), (14 - inset, 14 - inset)], fill=PANEL_D + (255,))
    if bolts:
        for bx, by in [(2, 2), (13, 2), (2, 13), (13, 13)]:
            d.point((bx + inset // 1, by), fill=EDGE + (255,))
            d.point((bx, by), fill=EDGE + (255,))
    return im


def bolts(im, positions=((1, 1), (14, 1), (1, 14), (14, 14))):
    d = ImageDraw.Draw(im)
    for x, y in positions:
        d.point((x, y), fill=EDGE + (255,))
    return im


def disc(im, cx, cy, r, color):
    d = ImageDraw.Draw(im)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(color if len(color) == 4 else color + (255,)))
    return im


def ring(im, cx, cy, r, color, width=1):
    d = ImageDraw.Draw(im)
    for w in range(width):
        d.ellipse([cx - r + w, cy - r + w, cx + r - w, cy + r - w], outline=(color if len(color) == 4 else color + (255,)))
    return im


def glow(im, cx, cy, core, mid, r=3):
    disc(im, cx, cy, r + 1, mid)
    disc(im, cx, cy, r - 1, core)
    d = ImageDraw.Draw(im)
    d.point((cx - 1, cy - 1), fill=(255, 255, 255, 255))
    return im


def slats(im, x0, y0, x1, rows, gap=2, color=DEEP, hi=LIGHT):
    d = ImageDraw.Draw(im)
    for i in range(rows):
        y = y0 + i * gap
        d.line([(x0, y), (x1, y)], fill=color + (255,))
        d.line([(x0, y + 1), (x1, y + 1)], fill=hi + (255,))
    return im


def save(im, path):
    full = os.path.join(OUT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    im.save(full)
    print("wrote", path)


# ---------------------------------------------------------------- hull & sensors

def radar_front():
    im = canvas("radar_front")
    bevel(im)
    d = ImageDraw.Draw(im)
    # Scope screen
    d.rectangle([3, 3, 12, 12], fill=(10, 26, 30, 255))
    d.rectangle([3, 3, 12, 12], outline=CYAN_D + (255,))
    ring(im, 7.5, 7.5, 3, CYAN_D)
    d.line([(4, 8), (11, 8)], fill=CYAN_D + (255,))
    d.line([(8, 4), (8, 11)], fill=CYAN_D + (255,))
    d.point((10, 6), fill=CYAN + (255,))
    d.point((6, 10), fill=CYAN + (255,))
    d.point((8, 8), fill=(220, 255, 255, 255))
    d.point((3, 13), fill=GREEN + (255,))
    d.point((12, 13), fill=AMBER + (255,))
    return im


def radar_side():
    im = canvas("radar_side")
    bevel(im)
    slats(im, 3, 4, 12, 4)
    d = ImageDraw.Draw(im)
    d.rectangle([3, 12, 12, 13], fill=PANEL_D + (255,))
    d.point((8, 12), fill=CYAN + (255,))
    return im


def radar_top():
    im = canvas("radar_top")
    bevel(im)
    ring(im, 7.5, 7.5, 5, FRAME)
    ring(im, 7.5, 7.5, 4, PANEL_D)
    disc(im, 7.5, 7.5, 2, LIGHT)
    d = ImageDraw.Draw(im)
    d.point((8, 8), fill=EDGE + (255,))
    return im


def radar_bottom():
    im = canvas("radar_bottom", PANEL_D)
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([5, 5, 10, 10], fill=DEEP + (255,))
    d.rectangle([5, 5, 10, 10], outline=FRAME + (255,))
    return im


def seeker_front():
    im = canvas("seeker_front")
    bevel(im)
    ring(im, 7.5, 7.5, 5, DEEP)
    ring(im, 7.5, 7.5, 4, FRAME)
    glow(im, 8, 8, (255, 120, 124), RED, 3)
    disc(im, 8, 8, 1, (60, 8, 12, 255))
    return im


def seeker_side():
    im = canvas("seeker_side")
    bevel(im)
    d = ImageDraw.Draw(im)
    d.polygon([(3, 5), (12, 5), (12, 10), (3, 10)], fill=PANEL_D + (255,))
    d.line([(3, 5), (12, 5)], fill=LIGHT + (255,))
    d.point((11, 7), fill=RED + (255,))
    slats(im, 3, 12, 12, 1)
    return im


def transponder_side():
    im = canvas("transponder_side")
    bevel(im)
    d = ImageDraw.Draw(im)
    # Antenna waves
    for r in (2, 4):
        ring(im, 5, 10, r, CYAN_D)
    d.point((5, 10), fill=CYAN + (255,))
    d.rectangle([10, 4, 12, 12], fill=PANEL_D + (255,))
    d.point((11, 6), fill=GREEN + (255,))
    d.point((11, 9), fill=AMBER + (255,))
    return im


def transponder_top():
    im = canvas("transponder_top")
    bevel(im)
    disc(im, 8, 8, 2, FRAME)
    d = ImageDraw.Draw(im)
    d.point((8, 8), fill=CYAN + (255,))
    d.line([(8, 2), (8, 5)], fill=LIGHT + (255,))
    d.point((8, 2), fill=CYAN + (255,))
    return im


def link_hub():
    im = canvas("link_hub")
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([4, 6, 11, 9], fill=DEEP + (255,))
    d.rectangle([4, 6, 11, 9], outline=CYAN_D + (255,))
    for x in (6, 8, 10):
        d.point((x, 8), fill=CYAN + (255,))
    slats(im, 4, 12, 11, 1)
    return im


def link_hub_top():
    im = canvas("link_hub_top")
    bevel(im)
    ring(im, 7.5, 7.5, 4, CYAN_D)
    d = ImageDraw.Draw(im)
    d.point((8, 8), fill=CYAN + (255,))
    for x, y in [(8, 3), (8, 12), (3, 8), (12, 8)]:
        d.point((x, y), fill=CYAN_D + (255,))
    return im


def decoy_launcher_front():
    im = canvas("decoy_launcher_front", PANEL_D)
    bevel(im)
    d = ImageDraw.Draw(im)
    # Two launch tubes
    for cx in (5, 10):
        d.rectangle([cx - 2, 4, cx + 2, 11], fill=DEEP + (255,))
        d.rectangle([cx - 2, 4, cx + 2, 11], outline=FRAME + (255,))
        d.line([(cx - 1, 5), (cx + 1, 5)], fill=AMBER + (255,))
    d.line([(2, 13), (5, 13)], fill=AMBER + (255,))
    d.line([(10, 13), (13, 13)], fill=AMBER + (255,))
    return im


def decoy_launcher_side():
    im = canvas("decoy_launcher_side")
    bevel(im)
    slats(im, 3, 4, 12, 3)
    d = ImageDraw.Draw(im)
    d.rectangle([3, 11, 12, 13], fill=PANEL_D + (255,))
    d.point((4, 12), fill=RED + (255,))
    d.point((11, 12), fill=GREEN + (255,))
    return im


def hd_monitor_front():
    im = canvas("hd_monitor_front", DEEP)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    d.rectangle([1, 1, 14, 14], fill=(8, 20, 26, 255))
    # Faint scan sweep the canvas draws over
    d.line([(2, 12), (13, 12)], fill=CYAN_D + (255,))
    d.line([(2, 12), (2, 3)], fill=(16, 60, 72, 255))
    d.point((13, 2), fill=CYAN_D + (255,))
    return im


def hd_monitor_side():
    im = canvas("hd_monitor_side", FRAME)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    d.rectangle([2, 2, 4, 13], fill=DEEP + (255,))
    slats(im, 7, 3, 13, 5)
    return im


def control_panel_face():
    im = canvas("control_panel_face", PANEL_D)
    bevel(im)
    d = ImageDraw.Draw(im)
    # Screen well top centre, switch rows below (the renderer draws the live controls over it)
    d.rectangle([4, 2, 11, 7], fill=DEEP + (255,))
    d.rectangle([4, 2, 11, 7], outline=FRAME + (255,))
    for y in (10, 13):
        for x in (3, 6, 9, 12):
            d.point((x, y), fill=LIGHT + (255,))
            d.point((x, y + 0), fill=LIGHT + (255,))
    d.point((3, 10), fill=RED + (255,))
    d.point((12, 13), fill=GREEN + (255,))
    return im


def control_panel_side():
    im = canvas("control_panel_side", FRAME)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    d.line([(1, 1), (14, 1)], fill=LIGHT + (255,))
    d.line([(1, 14), (14, 14)], fill=DEEP + (255,))
    slats(im, 3, 5, 12, 4)
    d.point((13, 13), fill=CYAN_D + (255,))
    return im


def copycat_base():
    im = canvas("copycat_base", FRAME)
    bevel(im, bolts=True)
    d = ImageDraw.Draw(im)
    d.rectangle([4, 4, 11, 11], fill=PANEL_D + (255,))
    d.rectangle([4, 4, 11, 11], outline=DEEP + (255,))
    d.point((8, 8), fill=LIGHT + (255,))
    return im


# ---------------------------------------------------------------- weapons & thrusters

def laser_front(active=False):
    im = canvas("laser_front_a" if active else "laser_front")
    bevel(im)
    ring(im, 7.5, 7.5, 5, DEEP)
    ring(im, 7.5, 7.5, 4, FRAME)
    if active:
        glow(im, 8, 8, (255, 200, 200), RED, 3)
    else:
        glow(im, 8, 8, (255, 140, 144), RED_D, 3)
    return im


def laser_cannon_emitter_front():
    im = canvas("laser_cannon_emitter_front")
    bevel(im)
    ring(im, 7.5, 7.5, 5, FRAME)
    glow(im, 8, 8, (200, 240, 255), BLUE, 3)
    return im


def laser_cannon_emitter_back():
    im = canvas("laser_cannon_emitter_back", PANEL_D)
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([5, 5, 10, 10], fill=DEEP + (255,))
    d.rectangle([5, 5, 10, 10], outline=FRAME + (255,))
    d.point((8, 8), fill=AMBER + (255,))
    return im


def laser_cannon_emitter_side():
    im = canvas("laser_cannon_emitter_side")
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([2, 6, 13, 9], fill=PANEL_D + (255,))
    d.line([(2, 6), (13, 6)], fill=LIGHT + (255,))
    d.point((12, 8), fill=BLUE + (255,))
    slats(im, 3, 12, 12, 1)
    return im


def laser_cannon_lens_end():
    im = blank()
    ring(im, 7.5, 7.5, 6, FRAME)
    ring(im, 7.5, 7.5, 5, PANEL_D)
    glow(im, 8, 8, (210, 245, 255), BLUE, 3)
    return im


def laser_cannon_lens_side():
    im = blank()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 5, 15, 10], fill=PANEL + (255,))
    d.line([(0, 5), (15, 5)], fill=HI + (255,))
    d.line([(0, 10), (15, 10)], fill=DEEP + (255,))
    d.rectangle([11, 5, 15, 10], fill=BLUE + (255,))
    d.line([(11, 6), (15, 6)], fill=(160, 210, 255, 255))
    return im


def railgun_breech_front():
    im = canvas("railgun_breech_front")
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([4, 5, 11, 10], fill=DEEP + (255,))
    d.rectangle([4, 5, 11, 10], outline=FRAME + (255,))
    d.line([(6, 8), (9, 8)], fill=CYAN + (255,))
    d.point((5, 6), fill=AMBER + (255,))
    d.point((10, 6), fill=RED + (255,))
    return im


def railgun_breech_back():
    im = canvas("railgun_breech_back", PANEL_D)
    bevel(im)
    d = ImageDraw.Draw(im)
    # Cable sockets
    for cx in (5, 10):
        disc(im, cx, 8, 2, DEEP)
        d.point((cx, 8), fill=FRAME + (255,))
    return im


def railgun_breech_side():
    im = canvas("railgun_breech_side")
    bevel(im)
    d = ImageDraw.Draw(im)
    # Twin rails running the length
    d.rectangle([1, 4, 14, 6], fill=PANEL_D + (255,))
    d.rectangle([1, 9, 14, 11], fill=PANEL_D + (255,))
    d.line([(1, 5), (14, 5)], fill=CYAN_D + (255,))
    d.line([(1, 10), (14, 10)], fill=CYAN_D + (255,))
    return im


def railgun_rail_end():
    im = blank()
    d = ImageDraw.Draw(im)
    d.rectangle([2, 3, 13, 6], fill=PANEL + (255,))
    d.rectangle([2, 9, 13, 12], fill=PANEL + (255,))
    d.line([(2, 3), (13, 3)], fill=HI + (255,))
    d.line([(2, 9), (13, 9)], fill=HI + (255,))
    d.line([(3, 5), (12, 5)], fill=CYAN_D + (255,))
    d.line([(3, 10), (12, 10)], fill=CYAN_D + (255,))
    return im


def railgun_rail_side():
    im = blank()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 3, 15, 6], fill=PANEL + (255,))
    d.rectangle([0, 9, 15, 12], fill=PANEL + (255,))
    d.line([(0, 3), (15, 3)], fill=HI + (255,))
    d.line([(0, 9), (15, 9)], fill=HI + (255,))
    for x in range(2, 15, 4):
        d.line([(x, 4), (x, 5)], fill=FRAME + (255,))
        d.line([(x, 10), (x, 11)], fill=FRAME + (255,))
    d.line([(0, 5), (15, 5)], fill=CYAN_D + (255,))
    d.line([(0, 10), (15, 10)], fill=CYAN_D + (255,))
    return im


def rotary_barrel_end():
    im = blank()
    # Six muzzles in a ring
    ring(im, 7.5, 7.5, 6, FRAME)
    for cx, cy in [(8, 4), (11, 6), (11, 10), (8, 12), (5, 10), (5, 6)]:
        disc(im, cx, cy, 1, DEEP)
    disc(im, 8, 8, 1, PANEL_D)
    return im


def rotary_barrel_side():
    im = blank()
    d = ImageDraw.Draw(im)
    for y in (3, 6, 9):
        d.rectangle([0, y, 15, y + 1], fill=PANEL + (255,))
        d.line([(0, y), (15, y)], fill=LIGHT + (255,))
    d.rectangle([0, 2, 3, 11], fill=FRAME + (255,))
    return im


def rotary_breech_end():
    im = canvas("rotary_breech_end")
    bevel(im)
    ring(im, 7.5, 7.5, 4, DEEP)
    disc(im, 8, 8, 1, FRAME)
    d = ImageDraw.Draw(im)
    d.point((8, 3), fill=AMBER + (255,))
    return im


def rotary_breech_side():
    im = canvas("rotary_breech_side")
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([2, 5, 13, 10], fill=PANEL_D + (255,))
    d.line([(2, 5), (13, 5)], fill=LIGHT + (255,))
    for x in range(4, 13, 3):
        d.point((x, 8), fill=DEEP + (255,))
    d.point((12, 6), fill=RED + (255,))
    return im


def thruster_side():
    im = canvas("thruster_side")
    bevel(im)
    d = ImageDraw.Draw(im)
    d.polygon([(3, 4), (9, 4), (12, 7), (12, 8), (9, 11), (3, 11)], fill=PANEL_D + (255,))
    d.line([(3, 4), (9, 4)], fill=LIGHT + (255,))
    d.point((11, 7), fill=ORANGE + (255,))
    slats(im, 3, 13, 8, 1)
    return im


def thruster_back():
    im = canvas("thruster_back", PANEL_D)
    bevel(im)
    ring(im, 7.5, 7.5, 4, FRAME)
    d = ImageDraw.Draw(im)
    for x, y in [(8, 4), (8, 11), (4, 8), (11, 8)]:
        d.point((x, y), fill=LIGHT + (255,))
    d.point((8, 8), fill=DEEP + (255,))
    return im


def thruster_nozzle(active=False):
    im = canvas("thruster_nozzle_a" if active else "thruster_nozzle", DEEP)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    # Bell: trapezoid rings closing in
    ring(im, 7.5, 7.5, 7, FRAME)
    ring(im, 7.5, 7.5, 5, PANEL_D)
    ring(im, 7.5, 7.5, 3, DEEP)
    if active:
        glow(im, 8, 8, (255, 240, 200), ORANGE, 2)
    else:
        disc(im, 8, 8, 1, (12, 12, 14, 255))
    return im


# ---------------------------------------------------------------- jet engine

def jet_section(base_color=PANEL):
    im = canvas("jet_section" + str(base_color), base_color)
    bevel(im)
    return im


def jet_core_side():
    im = jet_section()
    d = ImageDraw.Draw(im)
    d.rectangle([2, 5, 13, 10], fill=PANEL_D + (255,))
    d.line([(2, 5), (13, 5)], fill=LIGHT + (255,))
    disc(im, 8, 8, 2, FRAME)
    d.point((8, 8), fill=ORANGE + (255,))
    d.point((3, 13), fill=GREEN + (255,))
    return im


def jet_intake_side():
    im = jet_section()
    d = ImageDraw.Draw(im)
    d.rectangle([9, 3, 13, 12], fill=DEEP + (255,))
    d.rectangle([9, 3, 13, 12], outline=FRAME + (255,))
    for y in range(4, 12, 2):
        d.line([(10, y), (12, y)], fill=PANEL_D + (255,))
    slats(im, 3, 5, 6, 4)
    return im


def jet_nozzle_side():
    im = jet_section()
    d = ImageDraw.Draw(im)
    d.polygon([(2, 5), (9, 5), (13, 7), (13, 8), (9, 10), (2, 10)], fill=PANEL_D + (255,))
    d.line([(2, 5), (9, 5)], fill=LIGHT + (255,))
    d.point((12, 7), fill=DEEP + (255,))
    return im


def jet_afterburner_nozzle_side():
    im = jet_nozzle_side()
    d = ImageDraw.Draw(im)
    d.point((12, 7), fill=ORANGE + (255,))
    d.point((11, 7), fill=AMBER + (255,))
    d.point((13, 8), fill=RED + (255,))
    return im


def jet_section_end():
    im = canvas("jet_section_end", PANEL_D)
    bevel(im)
    ring(im, 7.5, 7.5, 4, FRAME)
    disc(im, 8, 8, 1, DEEP)
    return im


def jet_interior():
    im = canvas("jet_interior", DEEP)
    d = ImageDraw.Draw(im)
    ring(im, 7.5, 7.5, 7, FRAME)
    ring(im, 7.5, 7.5, 6, PANEL_D)
    for a in range(0, 360, 45):
        import math
        x = int(8 + 4.5 * math.cos(math.radians(a)))
        y = int(8 + 4.5 * math.sin(math.radians(a)))
        d.point((x, y), fill=LIGHT + (255,))
    disc(im, 8, 8, 1, FRAME)
    return im


def jet_fan_blade():
    im = canvas("jet_fan_blade", FRAME)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    # Splayed fan blades around the hub
    import math
    for a in range(0, 360, 30):
        x = int(8 + 6 * math.cos(math.radians(a)))
        y = int(8 + 6 * math.sin(math.radians(a)))
        d.line([(8, 8), (x, y)], fill=LIGHT + (255,))
    disc(im, 8, 8, 2, PANEL_D)
    d.point((8, 8), fill=HI + (255,))
    return im


def jet_turbine_blade():
    im = canvas("jet_turbine_blade", PANEL_D)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    import math
    for a in range(15, 360, 30):
        x = int(8 + 6 * math.cos(math.radians(a)))
        y = int(8 + 6 * math.sin(math.radians(a)))
        d.line([(8, 8), (x, y)], fill=HI + (255,))
    disc(im, 8, 8, 2, FRAME)
    d.point((8, 8), fill=ORANGE + (255,))
    return im


# ---------------------------------------------------------------- antigravity & bearings

def antigravity_side(active=False):
    im = canvas("antigravity_side_a" if active else "antigravity_side")
    bevel(im)
    ring(im, 7.5, 7.5, 4, VIOLET_D)
    if active:
        glow(im, 8, 8, (240, 225, 255), VIOLET, 2)
    else:
        disc(im, 8, 8, 2, VIOLET_D)
        d = ImageDraw.Draw(im)
        d.point((7, 7), fill=VIOLET + (255,))
    return im


def antigravity_top(active=False):
    im = canvas("antigravity_top_a" if active else "antigravity_top")
    bevel(im)
    ring(im, 7.5, 7.5, 5, FRAME)
    ring(im, 7.5, 7.5, 3, VIOLET_D)
    if active:
        glow(im, 8, 8, (240, 225, 255), VIOLET, 1)
    else:
        disc(im, 8, 8, 1, VIOLET_D)
    return im


def bearing_side(accent):
    im = canvas("bearing_side" + str(accent))
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([2, 6, 13, 9], fill=PANEL_D + (255,))
    d.line([(2, 6), (13, 6)], fill=LIGHT + (255,))
    disc(im, 8, 8, 1, accent)
    return im


def bearing_back(accent):
    im = canvas("bearing_back" + str(accent), PANEL_D)
    bevel(im)
    ring(im, 7.5, 7.5, 4, FRAME)
    disc(im, 8, 8, 2, DEEP)
    d = ImageDraw.Draw(im)
    d.point((8, 8), fill=accent + (255,))
    for x, y in [(8, 3), (8, 12), (3, 8), (12, 8)]:
        d.point((x, y), fill=LIGHT + (255,))
    return im


def servo_top():
    im = canvas("servo_top")
    bevel(im)
    ring(im, 7.5, 7.5, 5, FRAME)
    ring(im, 7.5, 7.5, 3, PANEL_D)
    d = ImageDraw.Draw(im)
    d.line([(8, 3), (8, 6)], fill=AMBER + (255,))
    d.point((8, 8), fill=EDGE + (255,))
    return im


def servo_body_top():
    im = canvas("servo_body_top", PANEL_D)
    bevel(im)
    d = ImageDraw.Draw(im)
    d.rectangle([4, 4, 11, 11], outline=FRAME + (255,))
    d.point((8, 8), fill=AMBER + (255,))
    return im


def quad_front():
    im = canvas("quad_front")
    bevel(im)
    d = ImageDraw.Draw(im)
    # Four quadrant plates
    d.rectangle([3, 3, 7, 7], fill=PANEL_D + (255,))
    d.rectangle([8, 3, 12, 7], fill=PANEL_D + (255,))
    d.rectangle([3, 8, 7, 12], fill=PANEL_D + (255,))
    d.rectangle([8, 8, 12, 12], fill=PANEL_D + (255,))
    d.point((5, 5), fill=AMBER + (255,))
    d.point((10, 5), fill=CYAN + (255,))
    d.point((5, 10), fill=CYAN + (255,))
    d.point((10, 10), fill=AMBER + (255,))
    return im


def quad_end():
    im = canvas("quad_end", PANEL_D)
    bevel(im)
    disc(im, 8, 8, 2, DEEP)
    d = ImageDraw.Draw(im)
    d.point((8, 8), fill=LIGHT + (255,))
    return im


# ---------------------------------------------------------------- connectors (entity textures)

def hose_tex():
    im = canvas("hose", (44, 44, 48))
    d = ImageDraw.Draw(im)
    for y in range(0, 16, 3):
        d.line([(0, y), (15, y)], fill=DEEP + (255,))
        d.line([(0, y + 1), (15, y + 1)], fill=LIGHT + (255,))
    return im


def fluid_pipe_tex():
    im = canvas("fluid_pipe", FRAME)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    d.line([(0, 4), (15, 4)], fill=HI + (255,))
    d.line([(0, 11), (15, 11)], fill=DEEP + (255,))
    d.line([(0, 7), (15, 7)], fill=CYAN_D + (255,))
    d.line([(0, 8), (15, 8)], fill=(26, 70, 84, 255))
    return im


def offset_connector_tex():
    im = canvas("offset_connector", PANEL_D)
    bevel(im)
    disc(im, 8, 8, 3, FRAME)
    d = ImageDraw.Draw(im)
    d.point((8, 8), fill=CYAN + (255,))
    d.point((8, 5), fill=LIGHT + (255,))
    d.point((8, 10), fill=LIGHT + (255,))
    return im


def offset_connector_side_tex():
    im = canvas("offset_connector_side", FRAME)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=EDGE + (255,))
    d.rectangle([6, 2, 9, 13], fill=PANEL_D + (255,))
    d.point((8, 8), fill=CYAN_D + (255,))
    return im


# ---------------------------------------------------------------- items

def item_flare():
    im = blank()
    d = ImageDraw.Draw(im)
    # Red flare canister, cap up
    d.rectangle([6, 4, 9, 14], fill=RED + (255,))
    d.rectangle([6, 4, 9, 6], fill=(250, 210, 120, 255))
    d.line([(6, 4), (6, 14)], fill=RED_D + (255,))
    d.line([(9, 7), (9, 14)], fill=RED_D + (255,))
    d.rectangle([5, 2, 10, 3], fill=FRAME + (255,))
    d.point((7, 1), fill=AMBER + (255,))
    d.point((8, 0), fill=(255, 240, 190, 255))
    return im


def item_chaff():
    im = blank()
    d = ImageDraw.Draw(im)
    # Gray canister with foil strips bursting out
    d.rectangle([5, 7, 10, 14], fill=PANEL + (255,))
    d.rectangle([5, 7, 10, 9], fill=FRAME + (255,))
    d.line([(5, 10), (5, 14)], fill=DEEP + (255,))
    for x, y in [(3, 3), (6, 1), (9, 2), (12, 4), (4, 5), (11, 6), (7, 4)]:
        d.point((x, y), fill=HI + (255,))
    for x, y in [(5, 2), (10, 3), (8, 5), (12, 2)]:
        d.point((x, y), fill=LIGHT + (255,))
    return im


def item_link_card():
    im = blank()
    d = ImageDraw.Draw(im)
    d.rectangle([2, 4, 13, 12], fill=(30, 90, 140, 255))
    d.rectangle([2, 4, 13, 12], outline=EDGE + (255,))
    d.line([(2, 5), (13, 5)], fill=(120, 190, 240, 255))
    d.rectangle([4, 7, 7, 10], fill=AMBER + (255,))
    d.line([(9, 8), (12, 8)], fill=(190, 225, 250, 255))
    d.line([(9, 10), (12, 10)], fill=(190, 225, 250, 255))
    return im


def item_head_tracker():
    im = blank()
    d = ImageDraw.Draw(im)
    # Twin camera lenses on a bar
    d.rectangle([1, 6, 14, 9], fill=FRAME + (255,))
    d.line([(1, 6), (14, 6)], fill=LIGHT + (255,))
    for cx in (5, 10):
        disc(im, cx, 8, 2, DEEP)
        d.point((cx, 8), fill=CYAN + (255,))
        d.point((cx - 1, 7), fill=(220, 255, 255, 255))
    d.point((8, 5), fill=RED + (255,))
    return im


def item_hose():
    im = blank()
    d = ImageDraw.Draw(im)
    # Coiled hose
    ring(im, 7.5, 8.5, 5, (58, 58, 64))
    ring(im, 7.5, 8.5, 3, (58, 58, 64))
    d.point((12, 4), fill=LIGHT + (255,))
    d.rectangle([11, 2, 13, 4], fill=FRAME + (255,))
    d.point((3, 13), fill=AMBER + (255,))
    return im


def item_fluid_pipe():
    im = blank()
    d = ImageDraw.Draw(im)
    # Bent pipe segment
    d.rectangle([3, 3, 6, 12], fill=PANEL + (255,))
    d.rectangle([3, 10, 12, 13], fill=PANEL + (255,))
    d.line([(3, 3), (3, 13)], fill=HI + (255,))
    d.line([(3, 10), (12, 10)], fill=HI + (255,))
    d.rectangle([2, 2, 7, 4], fill=FRAME + (255,))
    d.rectangle([10, 9, 13, 14], fill=FRAME + (255,))
    d.point((4, 8), fill=CYAN_D + (255,))
    return im


def item_offset_connector():
    im = blank()
    d = ImageDraw.Draw(im)
    d.rectangle([3, 3, 12, 12], fill=PANEL + (255,))
    d.rectangle([3, 3, 12, 12], outline=EDGE + (255,))
    d.line([(4, 4), (11, 4)], fill=HI + (255,))
    disc(im, 8, 8, 2, FRAME)
    d.point((8, 8), fill=CYAN + (255,))
    return im


def main():
    b = "block/"
    save(radar_front(), b + "radar_front.png")
    save(radar_side(), b + "radar_side.png")
    save(radar_top(), b + "radar_top.png")
    save(radar_bottom(), b + "radar_bottom.png")
    save(seeker_front(), b + "seeker_front.png")
    save(seeker_side(), b + "seeker_side.png")
    save(transponder_side(), b + "transponder_side.png")
    save(transponder_top(), b + "transponder_top.png")
    save(link_hub(), b + "link_hub.png")
    save(link_hub_top(), b + "link_hub_top.png")
    save(decoy_launcher_front(), b + "decoy_launcher_front.png")
    save(decoy_launcher_side(), b + "decoy_launcher_side.png")
    save(hd_monitor_front(), b + "hd_monitor_front.png")
    save(hd_monitor_side(), b + "hd_monitor_side.png")
    save(control_panel_face(), b + "control_panel_face.png")
    save(control_panel_side(), b + "control_panel_side.png")
    save(copycat_base(), b + "copycat_base.png")
    save(laser_front(False), b + "laser_front.png")
    save(laser_front(True), b + "laser_front_active.png")
    save(laser_cannon_emitter_front(), b + "laser_cannon_emitter_front.png")
    save(laser_cannon_emitter_back(), b + "laser_cannon_emitter_back.png")
    save(laser_cannon_emitter_side(), b + "laser_cannon_emitter_side.png")
    save(laser_cannon_lens_end(), b + "laser_cannon_lens_end.png")
    save(laser_cannon_lens_side(), b + "laser_cannon_lens_side.png")
    save(railgun_breech_front(), b + "railgun_breech_front.png")
    save(railgun_breech_back(), b + "railgun_breech_back.png")
    save(railgun_breech_side(), b + "railgun_breech_side.png")
    save(railgun_rail_end(), b + "railgun_rail_end.png")
    save(railgun_rail_side(), b + "railgun_rail_side.png")
    save(rotary_barrel_end(), b + "rotary_autocannon_barrel_end.png")
    save(rotary_barrel_side(), b + "rotary_autocannon_barrel_side.png")
    save(rotary_breech_end(), b + "rotary_autocannon_breech_end.png")
    save(rotary_breech_side(), b + "rotary_autocannon_breech_side.png")
    save(thruster_side(), b + "thruster_side.png")
    save(thruster_back(), b + "thruster_back.png")
    save(thruster_nozzle(False), b + "thruster_nozzle.png")
    save(thruster_nozzle(True), b + "thruster_nozzle_active.png")
    save(jet_core_side(), b + "jet_core_side.png")
    save(jet_intake_side(), b + "jet_intake_side.png")
    save(jet_nozzle_side(), b + "jet_nozzle_side.png")
    save(jet_afterburner_nozzle_side(), b + "jet_afterburner_nozzle_side.png")
    save(jet_section_end(), b + "jet_section_end.png")
    save(jet_interior(), b + "jet_interior.png")
    save(jet_fan_blade(), b + "jet_fan_blade.png")
    save(jet_turbine_blade(), b + "jet_turbine_blade.png")
    save(antigravity_side(False), b + "antigravity_core_side.png")
    save(antigravity_side(True), b + "antigravity_core_side_active.png")
    save(antigravity_top(False), b + "antigravity_core_top.png")
    save(antigravity_top(True), b + "antigravity_core_top_active.png")
    save(bearing_side(AMBER), b + "flap_bearing_side.png")
    save(bearing_back(AMBER), b + "flap_bearing_back.png")
    save(bearing_side(RED), b + "redstone_flap_bearing_side.png")
    save(bearing_back(RED), b + "redstone_flap_bearing_back.png")
    save(bearing_side(CYAN), b + "servo_bearing_side.png")
    save(bearing_back(CYAN), b + "servo_bearing_back.png")
    save(servo_top(), b + "servo_bearing_top.png")
    save(servo_body_top(), b + "servo_bearing_body_top.png")
    save(quad_front(), b + "quad_flap_bearing_front.png")
    save(bearing_side(AMBER), b + "quad_flap_bearing_side.png")
    save(quad_end(), b + "quad_flap_bearing_end.png")
    e = "entity/"
    save(hose_tex(), e + "hose.png")
    save(fluid_pipe_tex(), e + "fluid_pipe.png")
    save(offset_connector_tex(), e + "offset_connector.png")
    save(offset_connector_side_tex(), e + "offset_connector_side.png")
    i = "item/"
    save(item_flare(), i + "flare.png")
    save(item_chaff(), i + "chaff.png")
    save(item_link_card(), i + "link_card.png")
    save(item_head_tracker(), i + "head_tracker.png")
    save(item_hose(), i + "hose.png")
    save(item_fluid_pipe(), i + "fluid_pipe.png")
    save(item_offset_connector(), i + "offset_connector.png")


if __name__ == "__main__":
    main()
