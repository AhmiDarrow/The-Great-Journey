#!/usr/bin/env python3
"""Look through generated chunks for worldgen faults a top-down render hides.

    python tools/scan_region.py <region-dir> [--box X0 Z0 X1 Z1] [--y 40 140]

Reports, each as a short list of places (x y z) grouped so one fault prints once:
  lava    lava open to the air (a lava pool or fall on the surface, in a cave mouth or a tunnel);
  float   still water standing over air, or with an open air face beside it above sea level (it would spill as
          soon as anything updates it);
  seams   chunk borders where the ground jumps by 4+ blocks along most of the edge although the ground inside the
          chunks either side is smooth: one chunk built differently from its neighbour.
Only fully generated chunks (status minecraft:full) are read, and only neighbours inside them are judged.
"""
from __future__ import annotations

import argparse
import struct
import zlib
from pathlib import Path

import numpy as np

from render_region_map import nbt

AIRS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def chunks(region: Path, box):
    for f in sorted(region.glob("r.*.mca")):
        _, rx, rz, _ = f.name.split(".")
        rx, rz = int(rx), int(rz)
        data = f.read_bytes()
        for i in range(1024):
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big") * 4096
            if not off or data[off + 4] != 2:
                continue
            cx, cz = rx * 32 + (i & 31), rz * 32 + (i >> 5)
            if box and not (box[0] >> 4 <= cx <= box[2] >> 4 and box[1] >> 4 <= cz <= box[3] >> 4):
                continue
            ln = struct.unpack_from(">i", data, off)[0]
            b = zlib.decompress(data[off + 5:off + 4 + ln])
            root, _ = nbt(b, 3 + struct.unpack_from(">H", b, 1)[0], 10)
            if root.get("Status") not in ("minecraft:full", "full"):
                continue
            yield cx, cz, root


def section_ids(sec, names: dict):
    """The 4096 block ids of a section (y, z, x order) as indexes into the shared name table."""
    bs = sec.get("block_states") or {}
    pal = bs.get("palette") or []
    keys = []
    for p in pal:
        props = p.get("Properties", {})
        key = p["Name"] + ("|lvl" + props["level"] if "level" in props else "")
        keys.append(names.setdefault(key, len(names)))
    keys = np.array(keys, dtype=np.int32)
    if len(pal) == 1:
        return np.full(4096, keys[0], dtype=np.int32)
    bits = max(4, (len(pal) - 1).bit_length())
    per = 64 // bits
    longs = np.array([v & ((1 << 64) - 1) for v in bs["data"]], dtype=np.uint64)
    idx = np.arange(4096)
    words = longs[idx // per]
    shifts = ((idx % per) * bits).astype(np.uint64)
    vals = (words >> shifts) & np.uint64((1 << bits) - 1)
    return keys[vals.astype(np.int64)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("region")
    ap.add_argument("--box", type=int, nargs=4)
    ap.add_argument("--y", type=int, nargs=2, default=[40, 140])
    a = ap.parse_args()
    ylo, yhi = a.y
    names: dict[str, int] = {}
    vol = {}
    for cx, cz, root in chunks(Path(a.region), a.box):
        secs = {s["Y"]: s for s in root.get("sections", []) if "block_states" in s}
        cube = np.zeros((yhi - ylo + 1, 16, 16), dtype=np.int32)
        air = names.setdefault("minecraft:air", len(names))
        cube[:] = air
        for sy in range(ylo >> 4, (yhi >> 4) + 1):
            if sy not in secs:
                continue
            ids = section_ids(secs[sy], names).reshape(16, 16, 16)
            for ly in range(16):
                y = sy * 16 + ly
                if ylo <= y <= yhi:
                    cube[y - ylo] = ids[ly]
        vol[(cx, cz)] = cube
    if not vol:
        raise SystemExit("no full chunks in range")
    rev = {v: k for k, v in names.items()}
    is_air = np.zeros(len(names) + 1, dtype=bool)
    is_lava = np.zeros(len(names) + 1, dtype=bool)
    is_still = np.zeros(len(names) + 1, dtype=bool)
    is_fluid = np.zeros(len(names) + 1, dtype=bool)
    for k, v in names.items():
        n = k.split("|")[0]
        is_air[v] = n in AIRS
        is_lava[v] = n == "minecraft:lava"
        is_fluid[v] = n in ("minecraft:water", "minecraft:lava") or "waterlogged" in k
        is_still[v] = n == "minecraft:water" and k.endswith("|lvl0")

    xs = [c[0] for c in vol]
    zs = [c[1] for c in vol]
    x0, z0 = min(xs), min(zs)
    W, D = (max(xs) - x0 + 1) * 16, (max(zs) - z0 + 1) * 16
    H = yhi - ylo + 1
    if W * D * H > 120_000_000:
        raise SystemExit(f"{W}x{D} blocks is too wide to scan at once: give a --box round one area")
    world = np.full((H, D, W), -1, dtype=np.int32)
    for (cx, cz), cube in vol.items():
        world[:, (cz - z0) * 16:(cz - z0) * 16 + 16, (cx - x0) * 16:(cx - x0) * 16 + 16] = cube
    known = world >= 0
    w = np.where(known, world, len(names))
    air, lava, still = is_air[w], is_lava[w], is_still[w]

    def neighbours_air(mask):
        out = np.zeros_like(mask)
        for axis, step in ((0, 1), (1, 1), (1, -1), (2, 1), (2, -1)):
            sh = np.roll(air, -step, axis=axis) & np.roll(known, -step, axis=axis)
            if step == 1:
                sl = [slice(None)] * 3
                sl[axis] = slice(-1, None)
            else:
                sl = [slice(None)] * 3
                sl[axis] = slice(0, 1)
            sh[tuple(sl)] = False
            out |= mask & sh
        return out

    def report(title, mask, limit=25):
        pts = np.argwhere(mask)
        print(f"{title}: {len(pts)} blocks")
        seen = set()
        shown = 0
        for y, z, x in pts:
            key = ((x + x0 * 16) // 8, (z + z0 * 16) // 8)
            if key in seen:
                continue
            seen.add(key)
            print(f"  {x + x0 * 16} {y + ylo} {z + z0 * 16}")
            shown += 1
            if shown >= limit:
                print("  ...")
                break

    report("lava open to air", neighbours_air(lava))
    sea = 63 - ylo
    above = np.zeros_like(still)
    above[sea:] = True
    below_air = np.zeros_like(still)
    below_air[1:] = air[:-1] & known[:-1]
    sides = np.zeros_like(still)
    for axis, step in ((1, 1), (1, -1), (2, 1), (2, -1)):
        sh = np.roll(air, -step, axis=axis) & np.roll(known, -step, axis=axis)
        sl = [slice(None)] * 3
        sl[axis] = slice(-1, None) if step == 1 else slice(0, 1)
        sh[tuple(sl)] = False
        sides |= sh
    report("still water over air", still & below_air & above)
    report("still water with an open side above sea level", still & sides & above)

    # ground height map: top non-air block per column
    solid = ~air & known
    top = np.where(solid.any(axis=0), H - 1 - np.argmax(solid[::-1], axis=0), -1) + ylo
    seams = []
    for cx, cz in vol:
        for dx, dz in ((1, 0), (0, 1)):
            if (cx + dx, cz + dz) not in vol:
                continue
            bx, bz = (cx - x0) * 16, (cz - z0) * 16
            if dx:
                a, b = top[bz:bz + 16, bx + 15], top[bz:bz + 16, bx + 16]
                ia, ib = top[bz:bz + 16, bx + 14], top[bz:bz + 16, bx + 17]
            else:
                a, b = top[bz + 15, bx:bx + 16], top[bz + 16, bx:bx + 16]
                ia, ib = top[bz + 14, bx:bx + 16], top[bz + 17, bx:bx + 16]
            jump = np.abs(a - b)
            inner = np.maximum(np.abs(a - ia), np.abs(b - ib))
            bad = (jump >= 4) & (inner <= 1)
            if bad.sum() >= 10:
                seams.append((cx * 16 + (16 if dx else 8), cz * 16 + (8 if dx else 16), int(bad.sum()), int(np.median(jump))))
    print(f"seams: {len(seams)} chunk edges")
    for s in seams[:25]:
        print(f"  near {s[0]} {s[1]}: {s[2]} of 16 columns jump (median {s[3]})")


if __name__ == "__main__":
    main()
