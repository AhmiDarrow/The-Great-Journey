#!/usr/bin/env python3
"""Side view of a generated world: every block on a straight line between two points, from y0 to y1.

    python tools/render_slice.py <region-dir> out.png x0 z0 x1 z1 [--y 40 160] [--scale 3]
    python tools/render_slice.py <region-dir> out.png --through X Z [--half 40]   # along the stream at X Z

--through finds the water around (X, Z), takes the direction it runs in, and slices along it: the way to look at
a waterfall from the side without knowing which way its river flows.

Water shows blue, falling water lighter, air sky-blue, logs brown, leaves green, stone grey. Good for checking
waterfalls, ravines, the gorge, hollows and tunnels from the side.
"""
from __future__ import annotations

import argparse
import struct
import zlib
from pathlib import Path

from PIL import Image

from render_region_map import colour, nbt


def load_chunk(region: Path, cx: int, cz: int, cache: dict):
    key = (cx, cz)
    if key in cache:
        return cache[key]
    f = region / f"r.{cx >> 5}.{cz >> 5}.mca"
    chunk = None
    if f.exists():
        data = f.read_bytes()
        i = (cx & 31) + (cz & 31) * 32
        off = int.from_bytes(data[i * 4:i * 4 + 3], "big") * 4096
        if off and data[off + 4] == 2:
            ln = struct.unpack_from(">i", data, off)[0]
            b = zlib.decompress(data[off + 5:off + 4 + ln])
            root, _ = nbt(b, 3 + struct.unpack_from(">H", b, 1)[0], 10)
            chunk = {s["Y"]: s for s in root.get("sections", []) if "block_states" in s}
    cache[key] = chunk
    return chunk


def block(chunk, x, y, z):
    sec = chunk.get(y >> 4) if chunk else None
    if not sec:
        return "minecraft:air", {}
    bs = sec["block_states"]
    pal = bs["palette"]
    if len(pal) == 1:
        return pal[0]["Name"], pal[0].get("Properties", {})
    data = bs["data"]
    bits = max(4, (len(pal) - 1).bit_length())
    per = 64 // bits
    i = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
    v = (data[i // per] & ((1 << 64) - 1)) >> ((i % per) * bits) & ((1 << bits) - 1)
    return pal[v]["Name"], pal[v].get("Properties", {})


def top_water(region: Path, x: int, z: int, cache: dict, ylo: int, yhi: int) -> bool:
    chunk = load_chunk(region, x >> 4, z >> 4, cache)
    for y in range(yhi, ylo - 1, -1):
        name, _ = block(chunk, x, y, z)
        if name.endswith(":air") or name.endswith("cave_air"):
            continue
        return name.endswith(":water")
    return False


def stream_direction(region: Path, x: int, z: int, r: int, cache: dict, ylo: int, yhi: int):
    """The main direction of the water columns within r of (x, z): the stream's course there."""
    pts = [(dx, dz) for dx in range(-r, r + 1) for dz in range(-r, r + 1)
           if dx * dx + dz * dz <= r * r and top_water(region, x + dx, z + dz, cache, ylo, yhi)]
    if len(pts) < 3:
        raise SystemExit(f"no stream around {x} {z}")
    mx = sum(p[0] for p in pts) / len(pts)
    mz = sum(p[1] for p in pts) / len(pts)
    sxx = sum((p[0] - mx) ** 2 for p in pts)
    szz = sum((p[1] - mz) ** 2 for p in pts)
    sxz = sum((p[0] - mx) * (p[1] - mz) for p in pts)
    import math
    angle = 0.5 * math.atan2(2 * sxz, sxx - szz)
    return math.cos(angle), math.sin(angle)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("region")
    ap.add_argument("out")
    ap.add_argument("coords", type=int, nargs="*")
    ap.add_argument("--through", type=int, nargs=2)
    ap.add_argument("--half", type=int, default=40)
    ap.add_argument("--y", type=int, nargs=2, default=[40, 160])
    ap.add_argument("--scale", type=int, default=3)
    a = ap.parse_args()
    if a.through:
        tx, tz = a.through
        ux, uz = stream_direction(Path(a.region), tx, tz, 14, {}, a.y[0], a.y[1])
        x0, z0 = round(tx - ux * a.half), round(tz - uz * a.half)
        x1, z1 = round(tx + ux * a.half), round(tz + uz * a.half)
        print(f"slicing {x0} {z0} -> {x1} {z1}")
    elif len(a.coords) == 4:
        x0, z0, x1, z1 = a.coords
    else:
        raise SystemExit("give x0 z0 x1 z1 or --through X Z")
    n = max(abs(x1 - x0), abs(z1 - z0)) + 1
    ylo, yhi = a.y
    img = Image.new("RGB", (n, yhi - ylo + 1), (200, 225, 245))
    px = img.load()
    cache = {}
    region = Path(a.region)
    for i in range(n):
        t = i / max(1, n - 1)
        x, z = round(x0 + (x1 - x0) * t), round(z0 + (z1 - z0) * t)
        chunk = load_chunk(region, x >> 4, z >> 4, cache)
        for y in range(ylo, yhi + 1):
            name, props = block(chunk, x, y, z)
            if name.endswith(":air") or name.endswith("cave_air"):
                c = (200, 225, 245) if chunk else (40, 40, 40)
            elif name.endswith(":water"):
                c = (110, 160, 235) if props.get("level", "0") != "0" else (52, 92, 170)
            else:
                c = colour(name)
            px[i, yhi - y] = c
    img = img.resize((img.width * a.scale, img.height * a.scale), Image.NEAREST)
    img.save(a.out)
    print(f"{n} columns, y {ylo}..{yhi}")


if __name__ == "__main__":
    main()
