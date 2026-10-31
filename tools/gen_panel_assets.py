"""Writes the control panel's textures, model, blockstate, item model, loot table, recipe and translations."""
import collections
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "ashcc")


def save(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def textures():
    rnd = random.Random(5)
    face = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = (46, 49, 54)
            if x in (0, 15) or y in (0, 15):
                c = (96, 100, 108)
            elif x % 4 in (0, 3) or y % 4 in (0, 3):
                # Cell borders of the 4x4 grid
                c = (34, 36, 40)
            c = tuple(max(0, min(255, v + rnd.randint(-3, 3))) for v in c)
            face.putpixel((x, y), c + (255,))
    face.save(os.path.join(A, "textures", "block", "control_panel_face.png"))
    side = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = (110, 114, 122) if y % 5 else (80, 84, 90)
            side.putpixel((x, y), tuple(max(0, min(255, v + rnd.randint(-4, 4))) for v in c) + (255,))
    side.save(os.path.join(A, "textures", "block", "control_panel_side.png"))


def main():
    textures()
    t = 3
    faces = {f: {"texture": "#side"} for f in ("north", "south", "east", "west", "down")}
    faces["up"] = {"texture": "#face"}
    # The plate and controls are drawn by the block entity renderer (the panel can be tilted); the model only gives the particle
    save(os.path.join(A, "models", "block", "control_panel.json"), {
        "textures": {"particle": "ashcc:block/control_panel_side"},
    })
    # Item: a flat plate
    save(os.path.join(A, "models", "item", "control_panel_plate.json"), {
        "parent": "minecraft:block/block",
        "textures": {"particle": "ashcc:block/control_panel_side", "face": "ashcc:block/control_panel_face",
                     "side": "ashcc:block/control_panel_side"},
        "elements": [{"from": [0, 0, 0], "to": [16, t, 16], "faces": faces}],
    })
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for facing, y in rot.items():
        # Same rotations as vanilla buttons: the plate model lies on the floor
        variants["face=floor,facing=" + facing] = {"model": "ashcc:block/control_panel", "y": y}
        variants["face=wall,facing=" + facing] = {"model": "ashcc:block/control_panel", "x": 90, "y": y}
        variants["face=ceiling,facing=" + facing] = {"model": "ashcc:block/control_panel", "x": 180, "y": (y + 180) % 360}
    for v in variants.values():
        if v.get("y") == 0:
            del v["y"]
    save(os.path.join(A, "blockstates", "control_panel.json"), {"variants": variants})
    save(os.path.join(A, "models", "item", "control_panel.json"), {"parent": "ashcc:item/control_panel_plate"})
    save(os.path.join(ROOT, "data", "ashcc", "loot_table", "blocks", "control_panel.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "ashcc:control_panel"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    save(os.path.join(ROOT, "data", "ashcc", "recipe", "control_panel.json"), {
        "type": "minecraft:crafting_shaped", "category": "redstone",
        "pattern": ["BLB", "III"],
        "key": {"B": {"item": "minecraft:stone_button"}, "L": {"item": "minecraft:lever"}, "I": {"item": "minecraft:iron_ingot"}},
        "result": {"id": "ashcc:control_panel", "count": 1}})
    lang = {
        "ja_jp": {
            "block.ashcc.control_panel": "コントロールパネル",
            "screen.ashcc.panel": "コントロールパネルの設定",
            "screen.ashcc.panel.cell": "マス 列%s・行%s",
            "screen.ashcc.panel.type": "種類: %s",
            "screen.ashcc.panel.width": "幅 %s マス",
            "screen.ashcc.panel.height": "高さ %s マス",
            "screen.ashcc.panel.channel": "チャンネル %s",
            "screen.ashcc.panel.channels": "ch %s〜%s（前後左右・引金）",
            "screen.ashcc.panel.color": "色: %s",
            "panel_control.ashcc.none": "なし（外す）",
            "panel_control.ashcc.button": "ボタン",
            "panel_control.ashcc.toggle": "トグルスイッチ",
            "panel_control.ashcc.knob": "ノブ（16段階）",
            "panel_control.ashcc.slider": "スライダー（スロットル）",
            "panel_control.ashcc.stick": "スティック",
            "hud.ashcc.panel_stick": "スティック 左右 %s  前後 %s",
            "hud.ashcc.panel_value": "値 %s / 15",
        },
        "en_us": {
            "block.ashcc.control_panel": "Control Panel",
            "screen.ashcc.panel": "Control Panel Setup",
            "screen.ashcc.panel.cell": "Cell column %s, row %s",
            "screen.ashcc.panel.type": "Type: %s",
            "screen.ashcc.panel.width": "Width %s cells",
            "screen.ashcc.panel.height": "Height %s cells",
            "screen.ashcc.panel.channel": "Channel %s",
            "screen.ashcc.panel.channels": "Ch %s-%s (F/B/L/R/trigger)",
            "screen.ashcc.panel.color": "Colour: %s",
            "panel_control.ashcc.none": "None (remove)",
            "panel_control.ashcc.button": "Button",
            "panel_control.ashcc.toggle": "Toggle Switch",
            "panel_control.ashcc.knob": "Knob (16 steps)",
            "panel_control.ashcc.slider": "Slider (throttle)",
            "panel_control.ashcc.stick": "Stick",
            "hud.ashcc.panel_stick": "Stick X %s  Y %s",
            "hud.ashcc.panel_value": "Value %s / 15",
        },
    }
    lang["ja_jp"].update({'screen.ashcc.panel.channels.stick': 'ch %s〜%s（前後左右・引金・ハット4・ボタン2）', 'screen.ashcc.panel.channels.throttle': 'ch %s〜%s（スロットル・親指1・2）', 'screen.ashcc.panel.canvas': '表示はパネルの画面（CC で描く）', 'screen.ashcc.panel.grid': 'マス %s×%s', 'screen.ashcc.panel.tilt': '傾き %s°', 'panel_control.ashcc.throttle': 'スロットルレバー', 'panel_control.ashcc.screen': '画面（MFD）', 'panel_control.ashcc.hud': 'HUD ガラス', 'key.categories.ashcc.panel': 'AshCC コントロールパネル', 'key.ashcc.hat_up': 'ハット 上', 'key.ashcc.hat_down': 'ハット 下', 'key.ashcc.hat_left': 'ハット 左', 'key.ashcc.hat_right': 'ハット 右', 'key.ashcc.stick_button': 'スティック ボタン2', 'key.ashcc.throttle_up': 'スロットル 上げ', 'key.ashcc.throttle_down': 'スロットル 下げ', 'key.ashcc.thumb1': 'スロットル 親指ボタン1', 'key.ashcc.thumb2': 'スロットル 親指ボタン2', 'hud.ashcc.panel_help': 'スティック: W/S/A/D・Space 引金・矢印 ハット・X ボタン2・Shift で離す', 'hud.ashcc.panel_help_lever': 'W/S（R/F）で動かす・C/V ボタン・Shift で離す', 'hud.ashcc.panel_help_hotas': 'スティック W/S/A/D・Space・矢印・X ／ スロットル R/F・C/V ／ Shift で離す'})
    lang["en_us"].update({'screen.ashcc.panel.channels.stick': 'Ch %s-%s (axes, trigger, hat, button 2)', 'screen.ashcc.panel.channels.throttle': 'Ch %s-%s (throttle, thumb 1, 2)', 'screen.ashcc.panel.canvas': "Shows the panel's canvas (drawn from CC)", 'screen.ashcc.panel.grid': 'Grid %sx%s', 'screen.ashcc.panel.tilt': 'Tilt %s°', 'panel_control.ashcc.throttle': 'Throttle Lever', 'panel_control.ashcc.screen': 'Screen (MFD)', 'panel_control.ashcc.hud': 'HUD Glass', 'key.categories.ashcc.panel': 'AshCC Control Panel', 'key.ashcc.hat_up': 'Hat Up', 'key.ashcc.hat_down': 'Hat Down', 'key.ashcc.hat_left': 'Hat Left', 'key.ashcc.hat_right': 'Hat Right', 'key.ashcc.stick_button': 'Stick Button 2', 'key.ashcc.throttle_up': 'Throttle Up', 'key.ashcc.throttle_down': 'Throttle Down', 'key.ashcc.thumb1': 'Throttle Thumb 1', 'key.ashcc.thumb2': 'Throttle Thumb 2', 'hud.ashcc.panel_help': 'Stick: W/S/A/D, Space trigger, arrows hat, X button 2, Shift to let go', 'hud.ashcc.panel_help_lever': 'W/S (R/F) to move, C/V buttons, Shift to let go', 'hud.ashcc.panel_help_hotas': 'Stick W/S/A/D, Space, arrows, X / Throttle R/F, C/V / Shift to let go'})
    for code, entries in lang.items():
        path = os.path.join(A, "lang", code + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f, object_pairs_hook=collections.OrderedDict)
        data.update(entries)
        save(path, data)


if __name__ == "__main__":
    main()
