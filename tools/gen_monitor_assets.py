# Generates the HD monitor's blockstate (6 facings x 4 rotations x hologram) and its 16x16 textures.
# usage: python -I tools/gen_monitor_assets.py   (run from the project root)
import json
import os

from PIL import Image

RES = "src/main/resources/assets/ashcc"


def blockstate():
    variants = {}
    horiz = {"north": 0, "east": 90, "south": 180, "west": 270}
    # Model front is north. x=270 turns it up (texture top then points south), x=90 down (top points north)
    up_y = {"south": 0, "west": 90, "north": 180, "east": 270}
    down_y = {"north": 0, "east": 90, "south": 180, "west": 270}
    for facing in ["north", "east", "south", "west", "up", "down"]:
        for rot in ["north", "east", "south", "west"]:
            for holo in ["false", "true"]:
                v = {"model": "ashcc:block/hd_monitor" + ("_hologram" if holo == "true" else "")}
                if facing in horiz:
                    if horiz[facing]:
                        v["y"] = horiz[facing]
                elif facing == "up":
                    v["x"] = 270
                    if up_y[rot]:
                        v["y"] = up_y[rot]
                else:
                    v["x"] = 90
                    if down_y[rot]:
                        v["y"] = down_y[rot]
                variants[f"facing={facing},hologram={holo},rotation={rot}"] = v
    with open(f"{RES}/blockstates/hd_monitor.json", "w") as f:
        json.dump({"variants": variants}, f, indent=2)


def textures():
    front = Image.new("RGBA", (16, 16), (14, 17, 21, 255))
    bezel = (52, 57, 63, 255)
    for i in range(16):
        for p in [(i, 0), (i, 15), (0, i), (15, i)]:
            front.putpixel(p, bezel)
    front.save(f"{RES}/textures/block/hd_monitor_front.png")

    side = Image.new("RGBA", (16, 16), (70, 75, 82, 255))
    for i in range(16):
        side.putpixel((i, 0), (92, 98, 106, 255))
        side.putpixel((i, 15), (48, 52, 58, 255))
    for y in (4, 7, 10):
        for x in range(4, 12):
            side.putpixel((x, y), (40, 44, 50, 255))
    side.save(f"{RES}/textures/block/hd_monitor_side.png")


def models():
    with open(f"{RES}/models/block/hd_monitor.json", "w") as f:
        json.dump({"parent": "minecraft:block/orientable", "textures": {
            "front": "ashcc:block/hd_monitor_front", "side": "ashcc:block/hd_monitor_side",
            "top": "ashcc:block/hd_monitor_side", "particle": "ashcc:block/hd_monitor_side"}}, f, indent=2)
    with open(f"{RES}/models/block/hd_monitor_hologram.json", "w") as f:
        json.dump({"textures": {"particle": "ashcc:block/hd_monitor_side"}, "elements": []}, f, indent=2)
    with open(f"{RES}/models/item/hd_monitor.json", "w") as f:
        json.dump({"parent": "ashcc:block/hd_monitor"}, f, indent=2)


if __name__ == "__main__":
    os.makedirs(f"{RES}/blockstates", exist_ok=True)
    blockstate()
    textures()
    models()
    print("hd_monitor assets written")
