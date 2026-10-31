"""Generates the jet engine section textures, models, blockstates, loot tables and recipes. Only touches jet_* files."""
import json, math, os, random, zlib
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
A = os.path.join(ROOT, 'assets', 'ashcc')
D = os.path.join(ROOT, 'data', 'ashcc')
PARTS = ['intake', 'core', 'nozzle', 'afterburner_nozzle']


def clamp(v):
    return max(0, min(255, int(v)))


def img(fn):
    im = Image.new('RGBA', (16, 16))
    rnd = random.Random(zlib.crc32(fn.__name__.encode()))
    for y in range(16):
        for x in range(16):
            r, g, b = fn(x, y, rnd)
            im.putpixel((x, y), (clamp(r), clamp(g), clamp(b), 255))
    return im


def metal(base, x, y, rnd, n=10):
    k = rnd.uniform(-n, n)
    return [c + k for c in base]


def shade(c, k):
    return [v * k for v in c]


# Textures are cropped by the model's per-element UVs, so they tile cleanly and keep detail small: no edge bands.
# On side faces one texture axis runs along the engine, the other round it.

def intake_side(x, y, rnd):
    # Painted aluminium cowl: brushed streaks, a panel seam with rivets, a blue stripe
    c = metal((196, 200, 206), x, y, rnd, 4)
    c = shade(c, 1 + 0.04 * math.sin(y * 2.7 + rnd.random()))
    if y == 7:
        c = shade(c, 0.72)
    if y in (6, 8) and x % 4 == 1:
        c = (150, 154, 160)
    if y in (12, 13):
        c = metal((70, 125, 190), x, y, rnd, 5)
    return c


def core_side(x, y, rnd):
    # Steel casing: axial ribs, bolted flange lines and small orange warning marks
    c = metal((128, 128, 126), x, y, rnd, 6)
    if x % 4 == 0:
        c = shade(c, 0.7)
    elif x % 4 == 1:
        c = shade(c, 1.18)
    if y in (3, 11):
        c = shade(c, 0.6)
        if x % 4 == 2:
            c = (190, 190, 186)
    if y == 7 and x % 8 in (5, 6):
        c = (214, 120, 36)
    return c


def heat_tint(x, y, rnd, base):
    # Titanium discoloured by heat: patches of straw, purple and blue
    c = metal(base, x, y, rnd, 5)
    t = 0.5 + 0.5 * math.sin(x * 0.45 + 1.2 * math.sin(y * 0.4))
    tints = [(0, 0, 0), (40, 20, -30), (35, -10, 45), (-20, 0, 50)]
    tc = tints[int(t * 3.999)]
    return [c[i] + tc[i] * 0.35 for i in range(3)]


def nozzle_side(x, y, rnd):
    c = heat_tint(x, y, rnd, (96, 94, 98))
    if x % 8 == 0 or y % 8 == 0:
        c = shade(c, 0.65)
    return c


def afterburner_side(x, y, rnd):
    # Overlapping nozzle petals: dark seams, a lighter overlap edge, soot
    c = heat_tint(x, y, rnd, (78, 74, 82))
    if x % 4 == 0:
        c = shade(c, 0.5)
    elif x % 4 == 1:
        c = shade(c, 1.25)
    if y % 8 == 4:
        c = shade(c, 0.7)
    return c


def section_end(x, y, rnd):
    # Flange face: machined ring with a bolt circle
    dx, dy = x - 7.5, y - 7.5
    r = math.hypot(dx, dy)
    c = metal((140, 142, 148), x, y, rnd, 6)
    c = shade(c, 1 + 0.06 * math.sin(r * 3))
    a = math.atan2(dy, dx)
    if 5.6 < r < 6.6 and math.cos(a * 8) > 0.75:
        c = (70, 72, 78)
    return c


def interior(x, y, rnd):
    c = metal((44, 45, 50), x, y, rnd, 5)
    if (x * 7 + y * 3) % 11 == 0:
        c = shade(c, 0.75)  # soot
    return c


TEX = {
    'jet_intake_side': intake_side,
    'jet_core_side': core_side,
    'jet_nozzle_side': nozzle_side,
    'jet_afterburner_nozzle_side': afterburner_side,
    'jet_section_end': section_end,
    'jet_interior': interior,
    # Blades: u along the chord, v along the span (root at 0)
    'jet_fan_blade': lambda x, y, rnd: shade(metal((168, 176, 188), x, y, rnd, 5), (1.2 if x < 3 else 1) * (0.85 + 0.15 * y / 15)),
    'jet_turbine_blade': lambda x, y, rnd: shade(metal((150, 112, 82), x, y, rnd, 6), (1.15 if x < 3 else 1) * (0.8 + 0.4 * y / 15)),
}

os.makedirs(os.path.join(A, 'textures', 'block'), exist_ok=True)
for name, fn in TEX.items():
    fn.__name__ = name
    img(fn).save(os.path.join(A, 'textures', 'block', name + '.png'))


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, indent=2)


# Model shapes: north (-Z, z=0) = exhaust side, south (+Z, z=16) = upstream side. The casing is an octagonal tube; the
# rotating fan (intake) and turbine (nozzle) are drawn by JetEngineRenderer inside the open cavities.
CAV = 5  # half size of the gas path cavity in pixels


def face_tex(direction, plane, tex, cav):
    if direction in ('north', 'south'):
        return tex['end']
    toward_center = (plane - 8) * (1 if direction in ('east', 'up') else -1) < 0
    return tex['inner'] if cav is not None and toward_center and abs(plane - 8) <= cav + 1e-6 else tex['side']


def box(x0, y0, z0, x1, y1, z1, tex, cav=None):
    planes = {'west': x0, 'east': x1, 'down': y0, 'up': y1, 'north': z0, 'south': z1}
    return {'from': [x0, y0, z0], 'to': [x1, y1, z1],
            'faces': {d: {'texture': face_tex(d, p, tex, cav)} for d, p in planes.items()}}


def octagon(z0, z1, o, c, tex):
    """Solid octagonal prism of half size o with corner chamfer c, built without overlapping faces."""
    lo, hi = 8 - o, 8 + o
    return [box(lo, lo + c, z0, hi, hi - c, z1, tex),
            box(lo + c, hi - c, z0, hi - c, hi, z1, tex),
            box(lo + c, lo, z0, hi - c, lo + c, z1, tex)]


def tube(z0, z1, o, c, i, tex):
    """Octagonal tube with a square bore of half size i."""
    lo, hi, a, b = 8 - o, 8 + o, 8 - i, 8 + i
    return [box(lo, lo + c, z0, a, hi - c, z1, tex, i),
            box(b, lo + c, z0, hi, hi - c, z1, tex, i),
            box(lo + c, hi - c, z0, hi - c, hi, z1, tex, i),
            box(lo + c, lo, z0, hi - c, lo + c, z1, tex, i),
            box(a, b, z0, b, hi - c, z1, tex, i),
            box(a, lo + c, z0, b, a, z1, tex, i)]


def tex_of(p):
    s = 'ashcc:block/jet_%s_side' % p
    return {'side': '#side', 'inner': '#inner', 'end': '#end'}, {
        'particle': s, 'side': s, 'inner': 'ashcc:block/jet_interior', 'end': 'ashcc:block/jet_section_end'}


def shape(p):
    t, _ = tex_of(p)
    inner = dict(t, side='#inner', end='#inner')
    els = []
    if p == 'intake':
        els += octagon(0, 1.5, 7.5, 2.2, t)                    # flange to the core
        els += [box(8 - CAV, 8 - CAV, 1.5, 8 + CAV, 8 + CAV, 3, inner)]  # bulkhead behind the fan
        els += tube(1.5, 14, 7, 2, CAV, t)
        els += tube(14, 16, 8, 2.5, CAV, dict(t, end='#lip'))  # inlet lip
        # Struts in front of the bulkhead
        els += [box(7.5, 8 - CAV, 4, 8.5, 8 + CAV, 5, inner), box(8 - CAV, 7.5, 4, 7.5, 8.5, 5, inner), box(8.5, 7.5, 4, 8 + CAV, 8.5, 5, inner)]
    elif p == 'core':
        els += octagon(0, 1.5, 7.5, 2.2, t) + octagon(14.5, 16, 7.5, 2.2, t)
        els += octagon(1.5, 14.5, 7, 2, t)
        for z in (5, 10):
            els += octagon(z, z + 1, 7.4, 2.2, dict(t, side='#end'))   # casing bands
        # Fuel manifolds along both sides
        els += [box(15.4, 6.5, 2, 16, 7.5, 14, dict(t, side='#pipe', end='#pipe')),
                box(0, 8.5, 2, 0.6, 9.5, 14, dict(t, side='#pipe', end='#pipe'))]
    else:
        ab = p == 'afterburner_nozzle'
        els += octagon(14.5, 16, 7.5, 2.2, t)
        els += [box(8 - CAV, 8 - CAV, 13, 8 + CAV, 8 + CAV, 14.5, inner)]  # rear bulkhead, the turbine sits in front of it
        if ab:
            els += tube(7, 14.5, 7.2, 2.2, CAV, t)
            els += tube(8, 9, 4.8, 1.5, 3, inner)                 # flame holder ring
            els += tube(0, 7, 6.8, 2, CAV, t)                    # variable nozzle petals
            els += [box(7.6, 15, 0, 8.4, 15.4, 7, dict(t, side='#end')), box(7.6, 0.6, 0, 8.4, 1, 7, dict(t, side='#end'))]
        else:
            els += tube(5, 14.5, 7, 2, CAV, t)
            els += tube(0, 5, 6.4, 2, CAV, t)                    # tapered exit
    return els


for p in PARTS:
    t, textures = tex_of(p)
    textures['lip'] = 'ashcc:block/jet_intake_side'
    textures['pipe'] = 'ashcc:block/jet_core_side'
    write(os.path.join(A, 'models', 'block', 'jet_%s.json' % p), {
        'parent': 'minecraft:block/block', 'ambientocclusion': False, 'textures': textures, 'elements': shape(p)})
for p in PARTS:
    write(os.path.join(A, 'models', 'item', 'jet_%s.json' % p), {'parent': 'ashcc:block/jet_%s' % p})
    rot = {'north': {}, 'east': {'y': 90}, 'south': {'y': 180}, 'west': {'y': 270}, 'up': {'x': 270}, 'down': {'x': 90}}
    write(os.path.join(A, 'blockstates', 'jet_%s.json' % p),
          {'variants': {'facing=' + f: dict({'model': 'ashcc:block/jet_' + p}, **r) for f, r in rot.items()}})
    write(os.path.join(D, 'loot_table', 'blocks', 'jet_%s.json' % p), {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'ashcc:jet_' + p}],
                   'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

RECIPES = {
    'intake': (['III', 'B B', 'III'], {'I': 'minecraft:iron_ingot', 'B': 'minecraft:iron_bars'}),
    'core': (['GBG', 'CFC', 'GDG'], {'G': 'minecraft:gold_ingot', 'B': 'minecraft:iron_bars', 'C': 'minecraft:copper_block', 'F': 'minecraft:blast_furnace', 'D': 'minecraft:diamond'}),
    'nozzle': (['I I', 'NRN', 'I I'], {'I': 'minecraft:iron_ingot', 'N': 'minecraft:netherite_scrap', 'R': 'minecraft:redstone_block'}),
    'afterburner_nozzle': (['FBF', 'BNB', 'FBF'], {'F': 'minecraft:fire_charge', 'B': 'minecraft:blaze_rod', 'N': 'ashcc:jet_nozzle'}),
}
for p, (pat, key) in RECIPES.items():
    write(os.path.join(D, 'recipe', 'jet_%s.json' % p), {
        'type': 'minecraft:crafting_shaped', 'category': 'redstone', 'pattern': pat,
        'key': {k: {'item': v} for k, v in key.items()}, 'result': {'id': 'ashcc:jet_' + p, 'count': 1}})
