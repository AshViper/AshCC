"""Cuts square block-face tiles out of an AI-generated sheet (opaque tiles on a flat magenta background).

usage: python tools/slice_tiles.py SHEET.png name1 name2 ... [--out DIR] [--preview FILE]
Names are given in reading order (left to right, top to bottom); "-" skips a tile.
Each tile becomes a 16x16 texture: one output pixel = median colour of the middle of its cell,
so the generator's uneven "pixel" grid and soft edges do not smear into the result.
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/ashcc/textures/block"


def background(rgb):
    edge = np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]])
    return np.median(edge, axis=0)


def tiles(rgb):
    bg = background(rgb)
    fg = np.abs(rgb - bg).sum(axis=2) > 90
    fg = ndimage.binary_opening(fg, iterations=2)
    fg = ndimage.binary_fill_holes(fg)
    labels, n = ndimage.label(fg)
    boxes = []
    min_area = rgb.shape[0] * rgb.shape[1] * 0.01
    for i, sl in enumerate(ndimage.find_objects(labels), 1):
        if sl is None or (labels[sl] == i).sum() < min_area:
            continue
        boxes.append((sl[0].start, sl[0].stop, sl[1].start, sl[1].stop))
    # rows: tiles whose vertical centres are within half a tile of each other
    boxes.sort(key=lambda b: (b[0] + b[1]) / 2)
    rows, row = [], []
    for b in boxes:
        if row and (b[0] + b[1]) / 2 - (row[0][0] + row[0][1]) / 2 > (row[0][1] - row[0][0]) / 2:
            rows.append(row)
            row = []
        row.append(b)
    if row:
        rows.append(row)
    return [b for r in rows for b in sorted(r, key=lambda b: b[2])]


def sample(rgb, box, size=16, inset=1, detail=0.25):
    y0, y1, x0, x1 = box
    # trim the soft edge the generator leaves against the background
    y0, y1, x0, x1 = y0 + inset, y1 - inset, x0 + inset, x1 - inset
    out = np.zeros((size, size, 3), np.uint8)
    ch, cw = (y1 - y0) / size, (x1 - x0) / size
    for j in range(size):
        for i in range(size):
            ya, yb = y0 + (j + 0.1) * ch, y0 + (j + 0.9) * ch
            xa, xb = x0 + (i + 0.1) * cw, x0 + (i + 0.9) * cw
            px = rgb[int(ya):max(int(yb), int(ya) + 1), int(xa):max(int(xb), int(xa) + 1)].reshape(-1, 3)
            m = np.median(px, axis=0)
            # thin lines and dots are finer than one output pixel: keep them when they cover enough of the cell
            odd = px[np.abs(px - m).sum(axis=1) > 90]
            out[j, i] = np.median(odd, axis=0) if len(odd) >= detail * len(px) else m
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sheet")
    ap.add_argument("names", nargs="+")
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--preview")
    a = ap.parse_args()
    rgb = np.asarray(Image.open(a.sheet).convert("RGB")).astype(np.int32)
    boxes = tiles(rgb)
    if len(boxes) != len(a.names):
        raise SystemExit(f"found {len(boxes)} tiles, expected {len(a.names)}: {boxes}")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    made = []
    for name, box in zip(a.names, boxes):
        if name == "-":
            continue
        img = Image.fromarray(sample(rgb, box)).convert("RGBA")
        img.save(out / f"{name}.png")
        made.append(img)
        print(f"{name}: {box[3] - box[2]}x{box[1] - box[0]} px")
    if a.preview:
        S = 128
        pv = Image.new("RGBA", (len(made) * (S + 8), S), (40, 40, 40, 255))
        for k, img in enumerate(made):
            pv.paste(img.resize((S, S), Image.NEAREST), (k * (S + 8), 0))
        pv.save(a.preview)


if __name__ == "__main__":
    main()
