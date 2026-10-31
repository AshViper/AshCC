"""Writes the empty 9x6x9 GameTest structure (data/ashcc/structure/empty_9x6x9.nbt)."""
import gzip
import os
import struct

DATA_VERSION = 3955  # Minecraft 1.21.1
OUT = os.path.join("src", "main", "resources", "data", "ashcc", "structure", "empty_9x6x9.nbt")

TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 3, 8, 9, 10


def name(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def payload(tag, value):
    if tag == TAG_INT:
        return struct.pack(">i", value)
    if tag == TAG_STRING:
        return name(value)
    if tag == TAG_LIST:
        elem_tag, items = value
        return struct.pack(">bi", elem_tag if items else 0, len(items)) + b"".join(payload(elem_tag, i) for i in items)
    if tag == TAG_COMPOUND:
        return b"".join(struct.pack(">b", t) + name(k) + payload(t, v) for k, (t, v) in value.items()) + b"\x00"
    raise ValueError(tag)


def ints(*values):
    return (TAG_LIST, (TAG_INT, list(values)))


blocks = [
    {"pos": ints(x, y, z), "state": (TAG_INT, 0)}
    for x in range(9) for y in range(6) for z in range(9)
]
root = {
    "DataVersion": (TAG_INT, DATA_VERSION),
    "size": ints(9, 6, 9),
    "palette": (TAG_LIST, (TAG_COMPOUND, [{"Name": (TAG_STRING, "minecraft:air")}])),
    "blocks": (TAG_LIST, (TAG_COMPOUND, blocks)),
    "entities": (TAG_LIST, (TAG_COMPOUND, [])),
}

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wb") as f:
    f.write(struct.pack(">b", TAG_COMPOUND) + name("") + payload(TAG_COMPOUND, root))
print("wrote", OUT)
