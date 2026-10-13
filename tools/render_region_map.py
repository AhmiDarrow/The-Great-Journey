#!/usr/bin/env python3
"""Top-down picture of a generated world from its region files: the top block of every column, hill-shaded.

    python tools/render_region_map.py build/server-harness/world/region out.png [--box x0 z0 x1 z1] [--scale 1]

Reads chunks that finished generating ("full" status) and colours each column by its highest non-air block, so
landmarks, water, paths and trees show as they really generated. Pure Python + Pillow; no game needed.
"""
from __future__ import annotations

import argparse
import struct
import zlib
from pathlib import Path

from PIL import Image

COLOURS = {
    "water": (52, 92, 170), "grass_block": (98, 150, 70), "dirt": (134, 96, 67), "coarse_dirt": (119, 85, 59),
    "podzol": (106, 76, 44), "sand": (219, 207, 163), "gravel": (136, 132, 128), "stone": (125, 125, 125),
    "smooth_stone": (160, 160, 160), "moss_block": (89, 110, 45), "snow": (240, 240, 245), "mud": (60, 56, 60),
    "andesite": (136, 136, 136), "sandstone": (216, 203, 155), "calcite": (225, 225, 220), "cobblestone": (110, 110, 110),
    "stone_bricks": (122, 121, 122), "spruce_planks": (114, 84, 48), "spruce_slab": (114, 84, 48),
    "oak_log": (109, 85, 50), "birch_log": (216, 215, 210), "spruce_log": (58, 37, 16), "iron_bars": (160, 160, 160),
    "oak_fence": (162, 130, 78), "smooth_stone_slab": (165, 165, 165), "black_concrete": (20, 22, 26),
    "sugar_cane": (148, 192, 101), "lily_pad": (32, 128, 48), "clay": (160, 166, 179), "terracotta": (152, 94, 67),
}


def nbt(buf, i, t):
    if t == 1: return struct.unpack_from(">b", buf, i)[0], i + 1
    if t == 2: return struct.unpack_from(">h", buf, i)[0], i + 2
    if t == 3: return struct.unpack_from(">i", buf, i)[0], i + 4
    if t == 4: return struct.unpack_from(">q", buf, i)[0], i + 8
    if t == 5: return None, i + 4
    if t == 6: return None, i + 8
    if t == 7:
        n = struct.unpack_from(">i", buf, i)[0]; return None, i + 4 + n
    if t == 8:
        n = struct.unpack_from(">H", buf, i)[0]; return buf[i + 2:i + 2 + n].decode("utf-8", "replace"), i + 2 + n
    if t == 9:
        et = buf[i]; n = struct.unpack_from(">i", buf, i + 1)[0]; i += 5; out = []
        for _ in range(n):
            v, i = nbt(buf, i, et); out.append(v)
        return out, i
    if t == 10:
        d = {}
        while True:
            tt = buf[i]; i += 1
            if tt == 0: return d, i
            n = struct.unpack_from(">H", buf, i)[0]; name = buf[i + 2:i + 2 + n].decode("utf-8", "replace"); i += 2 + n
            d[name], i = nbt(buf, i, tt)
    if t == 11:
        n = struct.unpack_from(">i", buf, i)[0]; return None, i + 4 + 4 * n
    if t == 12:
        n = struct.unpack_from(">i", buf, i)[0]; return list(struct.unpack_from(f">{n}q", buf, i + 4)), i + 4 + 8 * n
    raise ValueError(t)


def colour(name: str):
    short = name.split(":")[-1]
    if short in COLOURS: return COLOURS[short]
    if "leaves" in short: return (52, 110, 40) if "spruce" not in short else (44, 80, 50)
    if short.endswith("_log") or short.endswith("_wood"): return (96, 72, 44)
    if "planks" in short or "slab" in short or "stairs" in short: return (150, 120, 80)
    if "fence" in short: return (150, 120, 80)
    if short in ("short_grass", "tall_grass", "fern", "large_fern"): return (90, 140, 60)
    if "flower" in short or short in ("poppy", "dandelion", "allium", "azure_bluet", "cornflower"): return (200, 180, 90)
    if "bush" in short or "azalea" in short: return (70, 120, 50)
    if short == "yellow_concrete": return (220, 190, 40)
    if short == "white_concrete": return (230, 230, 230)
    if "concrete" in short: return (60, 60, 64)
    if short.endswith("_wall"): return (130, 130, 130)
    if short in ("mossy_cobblestone", "mossy_stone_bricks", "tuff", "diorite", "granite", "deepslate"): return (110, 115, 105)
    if short in ("seagrass", "tall_seagrass", "kelp", "kelp_plant"): return (52, 92, 170)
    if short in ("vine", "glow_lichen", "moss_carpet", "lily_of_the_valley", "violet", "sweet_berry_bush"): return (80, 130, 60)
    if "mushroom" in short or "petals" in short or "tulip" in short or "orchid" in short: return (190, 150, 110)
    return (170, 60, 170)                          # anything unknown shows magenta, so it stands out


def top_blocks(chunk):
    """{(x, z): (y, name)} for the 256 columns of a chunk."""
    out = {}
    sections = sorted(chunk.get("sections") or [], key=lambda s: -s.get("Y", 0))
    for sec in sections:
        bs = sec.get("block_states") or {}
        pal = bs.get("palette") or []
        if not pal or (len(pal) == 1 and pal[0].get("Name", "").endswith(":air")): continue
        data = bs.get("data")
        bits = max(4, (len(pal) - 1).bit_length())
        per = 64 // bits
        mask = (1 << bits) - 1
        def idx(i):
            if not data: return 0
            L = data[i // per] & ((1 << 64) - 1)
            return (L >> ((i % per) * bits)) & mask
        for z in range(16):
            for x in range(16):
                if (x, z) in out: continue
                for y in range(15, -1, -1):
                    n = pal[idx((y * 16 + z) * 16 + x)].get("Name", "")
                    if n.endswith(":air") or n.endswith("cave_air") or n.endswith(":void_air"): continue
                    out[(x, z)] = (sec["Y"] * 16 + y, n)
                    break
        if len(out) == 256: break
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("region")
    ap.add_argument("out")
    ap.add_argument("--box", type=int, nargs=4, metavar=("X0", "Z0", "X1", "Z1"))
    a = ap.parse_args()
    cols = {}
    for f in Path(a.region).glob("r.*.mca"):
        rx, rz = map(int, f.name.split(".")[1:3])
        if a.box and (rx * 512 > a.box[2] or rx * 512 + 511 < a.box[0] or rz * 512 > a.box[3] or rz * 512 + 511 < a.box[1]):
            continue
        data = f.read_bytes()
        if len(data) < 8192: continue
        for c in range(1024):
            off = int.from_bytes(data[c * 4:c * 4 + 3], "big") * 4096
            if not off: continue
            ln = struct.unpack_from(">i", data, off)[0]
            if data[off + 4] != 2: continue
            b = zlib.decompress(data[off + 5:off + 4 + ln])
            root, _ = nbt(b, 3 + struct.unpack_from(">H", b, 1)[0], 10)
            if root.get("Status") not in ("minecraft:full", "full"): continue
            cx, cz = root["xPos"], root["zPos"]
            for (x, z), v in top_blocks(root).items():
                cols[(cx * 16 + x, cz * 16 + z)] = v
    if not cols:
        raise SystemExit("no generated chunks")
    xs = [k[0] for k in cols]; zs = [k[1] for k in cols]
    x0, z0 = (a.box[0], a.box[1]) if a.box else (min(xs), min(zs))
    x1, z1 = (a.box[2], a.box[3]) if a.box else (max(xs), max(zs))
    img = Image.new("RGB", (x1 - x0 + 1, z1 - z0 + 1), (16, 16, 16))
    px = img.load()
    for (x, z), (y, n) in cols.items():
        if not (x0 <= x <= x1 and z0 <= z <= z1): continue
        w = cols.get((x - 1, z), (y, n))[0]; nn = cols.get((x, z - 1), (y, n))[0]
        shade = max(0.55, min(1.35, 1 + ((y - w) + (y - nn)) / 8))
        c = colour(n)
        px[x - x0, z - z0] = tuple(min(255, int(v * shade)) for v in c)
    img.save(a.out)
    ys = sorted(v[0] for v in cols.values())
    print(f"{len(cols)} columns, x {x0}..{x1}, z {z0}..{z1}, top y p50={ys[len(ys)//2]} max={ys[-1]}")


if __name__ == "__main__":
    main()
