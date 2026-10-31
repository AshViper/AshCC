# Converts GNU Unifont .hex files into the compact glyph file the HD monitor loads on both sides.
# usage: python -I tools/gen_font.py <unifont_all.hex> [<patch.hex> ...] <out.bin>
# Later files override earlier ones (the Japanese patch replaces the CJK glyph variants).
# Format (gzip): "AUF1", int count, then per glyph sorted by code point: int cp, byte width (8 or 16), 16 rows
# of width/8 bytes each (MSB = leftmost pixel). Only the BMP is kept.
import gzip
import struct
import sys


def main():
    *inputs, out = sys.argv[1:]
    glyphs = {}
    for path in inputs:
        with open(path, encoding="ascii") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                cp_hex, bits = line.split(":")
                cp = int(cp_hex, 16)
                if cp > 0xFFFF or len(bits) not in (32, 64):
                    continue
                glyphs[cp] = bits
    buf = bytearray(b"AUF1")
    buf += struct.pack(">i", len(glyphs))
    for cp in sorted(glyphs):
        bits = glyphs[cp]
        width = 8 if len(bits) == 32 else 16
        buf += struct.pack(">iB", cp, width)
        buf += bytes.fromhex(bits)
    with gzip.open(out, "wb", compresslevel=9) as f:
        f.write(buf)
    print(f"{len(glyphs)} glyphs -> {out}")


if __name__ == "__main__":
    main()
