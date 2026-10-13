#!/usr/bin/env python3
"""The Great Journey's CurseForge pack icon, composed from real in-game renders of Warrior Cats Events cats.

    python tools/make_pack_icon.py --cut build/playtest/runs/<run>-portraits   # cut the cats out of a portraits run
    python tools/make_pack_icon.py                                            # compose the icon from docs/public/cats/

The cats are photographed by the playtest harness (`tools/playtest.py --suite portraits`, see mods/qa client/Photo):
each one stands alone in a magenta studio high in the sky, its coat set through Warrior Cats Events' own genetics to
look like a cat from the Warriors books. `--cut` keys the magenta out and writes one transparent PNG per cat and view to
docs/public/cats/<cat>-<view>.png. Composing needs only Pillow: no generated art, only these renders.

Writes docs/public/pack-icon-512.png, pack-icon-400.png (CurseForge) and pack-icon-256.png, plus a 64 px preview in
build/icon/ for checking that the icon still reads small.
"""
from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
CATS = ROOT / "docs/public/cats"
OUT = ROOT / "docs/public"
PREVIEW = ROOT / "build/icon"

# which of the portraits run's pictures to keep for each cat and view (a blink spoils one now and then): the shot
# number within that view, 1-3
PICK: dict[str, dict[str, int]] = {
    "yellowfang": {"head-right": 2},
    "graystripe": {"full": 2},
}

# the icon, drawn at 1024 and scaled down: (cat, view, centre x, top y, bust height). Back cats first, so nearer
# ones overlap them. Cats on the left look in from the left (the "head-left" view), cats on the right from the right.
LAYOUT = [
    ("yellowfang", "head-left", 190, 40, 290),
    ("tigerstar", "head-right", 834, 40, 290),
    ("bluestar", "head-front", 512, 18, 290),
    ("graystripe", "head-left", 150, 285, 320),
    ("sandstorm", "head-right", 874, 285, 320),
    ("ravenpaw", "head-left", 165, 545, 320),
    ("spottedleaf", "head-right", 859, 545, 320),
    ("firestar", "head-front", 512, 370, 500),
]

# the part of each view kept as a bust, as fractions of the cut-out cat: (left, top, right, bottom)
BUST = {"head-front": (0.0, 0.0, 1.0, 0.62), "head-left": (0.36, 0.0, 1.0, 0.72), "head-right": (0.0, 0.0, 0.64, 0.72)}


# ---- cutting the cats out ----------------------------------------------------------------------------------------

def cut(src: Path) -> Image.Image:
    """Keys out the studio's magenta concrete (lit or shaded: hue near 300 degrees, clearly saturated) and keeps only
    the cat: the largest opaque piece and anything nearly as big, so drifting particles and stray specks go."""
    import numpy as np
    from scipy import ndimage

    rgb = np.asarray(Image.open(src).convert("RGB")).astype(np.float32) / 255
    mx, mn = rgb.max(axis=2), rgb.min(axis=2)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    studio = (r > g * 1.35) & (b > g * 1.25) & (sat > 0.38) & (mx > 0.08)
    cat = ndimage.binary_opening(~studio, iterations=1)
    labels, n = ndimage.label(cat)
    if n:
        sizes = ndimage.sum(cat, labels, range(1, n + 1))
        keep = [i + 1 for i, s in enumerate(sizes) if s >= sizes.max() * 0.05]
        cat = np.isin(labels, keep)
    out = Image.open(src).convert("RGBA")
    out.putalpha(Image.fromarray((cat * 255).astype(np.uint8)))
    box = out.getchannel("A").getbbox()
    return out.crop(box) if box else out


def cut_run(run: Path) -> None:
    CATS.mkdir(parents=True, exist_ok=True)
    shots = sorted(run.glob("*/shots/qa-*-p1-portraits-*.png"))
    seen: dict[tuple[str, str], list[Path]] = {}
    for p in shots:
        m = re.match(r"qa-[^-]+-p1-portraits-\d{3}-([a-z]+)-(full|head-left|head-right|head-front)(?:-(\d))?\.png", p.name)
        if m:
            seen.setdefault((m.group(1), m.group(2)), []).append(p)
    for (cat, view), paths in sorted(seen.items()):
        k = PICK.get(cat, {}).get(view, 1)
        src = paths[min(k, len(paths)) - 1]
        img = cut(src)
        dst = CATS / f"{cat}-{view}.png"
        img.save(dst, optimize=True)
        print(f"{dst.relative_to(ROOT)}  <- {src.name}  {img.size}")


# ---- composing ---------------------------------------------------------------------------------------------------

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def background(n: int) -> Image.Image:
    """A forest clearing at dusk: warm gold behind the cats fading into deep green, a full moon, oak trunks, leaves."""
    bg = Image.new("RGB", (n, n))
    px = bg.load()
    cx, cy = n * 0.5, n * 0.42
    gold, amber, green, deep = (255, 214, 130), (226, 140, 62), (46, 92, 52), (12, 34, 22)
    for y in range(n):
        for x in range(n):
            d = math.hypot(x - cx, (y - cy) * 1.1) / (n * 0.72)
            if d < 0.45:
                c = lerp(gold, amber, d / 0.45)
            elif d < 0.8:
                c = lerp(amber, green, (d - 0.45) / 0.35)
            else:
                c = lerp(green, deep, min(1.0, (d - 0.8) / 0.4))
            px[x, y] = c
    d = ImageDraw.Draw(bg, "RGBA")
    # the full moon of a Gathering, with a soft halo
    halo = Image.new("L", (n, n), 0)
    hd = ImageDraw.Draw(halo)
    mr = n * 0.17
    hd.ellipse((cx - mr * 1.9, cy - mr * 1.9 - n * 0.06, cx + mr * 1.9, cy + mr * 1.9 - n * 0.06), fill=150)
    halo = halo.filter(ImageFilter.GaussianBlur(n * 0.06))
    bg.paste((255, 246, 214), mask=halo)
    d.ellipse((cx - mr, cy - mr - n * 0.06, cx + mr, cy + mr - n * 0.06), fill=(255, 250, 232, 255))
    # four great oaks at the edges of the clearing, in silhouette
    trunks = Image.new("L", (n, n), 0)
    td = ImageDraw.Draw(trunks)
    for x0, w in ((-0.02, 0.11), (0.13, 0.05), (0.82, 0.05), (0.93, 0.11)):
        td.rectangle((x0 * n, 0, (x0 + w) * n, n), fill=255)
    for (x, y, r) in ((0.05, 0.02, 0.2), (0.2, -0.04, 0.16), (0.8, -0.04, 0.16), (0.95, 0.02, 0.2), (0.5, -0.12, 0.2),
                      (0.33, -0.08, 0.13), (0.67, -0.08, 0.13)):
        td.ellipse(((x - r) * n, (y - r) * n, (x + r) * n, (y + r) * n), fill=255)
    trunks = trunks.filter(ImageFilter.GaussianBlur(n * 0.004))
    bg.paste((20, 44, 28), mask=trunks.point(lambda v: int(v * 0.85)))
    # a few drifting leaves of light
    import random
    rnd = random.Random(7)
    glow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for _ in range(40):
        x, y, r = rnd.uniform(0.1, 0.9) * n, rnd.uniform(0.05, 0.6) * n, rnd.uniform(0.003, 0.008) * n
        gd.ellipse((x - r, y - r, x + r, y + r), fill=(255, 238, 170, rnd.randint(90, 200)))
    bg = Image.alpha_composite(bg.convert("RGBA"), glow.filter(ImageFilter.GaussianBlur(1.2)))
    return bg


def shadowed(img: Image.Image, radius: int, alpha: int) -> Image.Image:
    a = img.getchannel("A")
    sh = Image.new("RGBA", img.size, (8, 20, 12, 0))
    sh.putalpha(a.point(lambda v: v * alpha // 255).filter(ImageFilter.GaussianBlur(radius)))
    return sh


def tint(mask: Image.Image, colour: tuple[int, int, int], strength: float) -> Image.Image:
    layer = Image.new("RGBA", mask.size, colour + (0,))
    layer.putalpha(mask.point(lambda v: int(v * strength)))
    return layer


def bust(cat: str, view: str, height: int) -> Image.Image | None:
    """The head and shoulders of one cut-out cat, scaled to {height} px, fading out at the bottom."""
    p = CATS / f"{cat}-{view}.png"
    if not p.exists():
        return None
    im = Image.open(p).convert("RGBA")
    l, t, r, b = BUST[view]
    im = im.crop((int(l * im.width), int(t * im.height), int(r * im.width), int(b * im.height)))
    im = im.crop(im.getchannel("A").getbbox())
    s = height / im.height
    im = im.resize((max(1, int(im.width * s)), height), Image.LANCZOS)
    fade = Image.new("L", im.size, 255)
    fd = ImageDraw.Draw(fade)
    for y in range(int(height * 0.72), height):
        fd.line((0, y, im.width, y), fill=int(255 * (1 - (y - height * 0.72) / (height * 0.28)) ** 1.5))
    im.putalpha(ImageChops.multiply(im.getchannel("A"), fade))
    return im


def vignette(img: Image.Image) -> Image.Image:
    n = img.width
    v = Image.new("L", (n, n), 0)
    vd = ImageDraw.Draw(v)
    vd.ellipse((-n * 0.12, -n * 0.12, n * 1.12, n * 1.12), fill=255)
    v = v.filter(ImageFilter.GaussianBlur(n * 0.09))
    dark = Image.new("RGBA", (n, n), (6, 16, 10, 255))
    return Image.composite(img, dark, v.point(lambda x: 70 + x * 185 // 255))


def compose() -> Image.Image:
    n = 1024
    icon = background(n)
    for cat, view, cx, top, h in LAYOUT:
        b = bust(cat, view, h)
        if b is None:
            print(f"missing {cat}-{view}")
            continue
        x, y = int(cx - b.width / 2), int(top)
        a = b.getchannel("A")
        pad = 24
        big = Image.new("L", (b.width + 2 * pad, b.height + 2 * pad), 0)
        big.paste(a, (pad, pad))
        # a warm rim of firelight round each cat, then a crisp dark outline, so dark coats stand off the forest
        fade = Image.new("L", big.size, 255)
        fd = ImageDraw.Draw(fade)
        for yy in range(big.height):
            k = (yy - pad - b.height * 0.55) / (b.height * 0.3)
            fd.line((0, yy, big.width, yy), fill=int(255 * min(1.0, max(0.0, 1 - k))))
        big = ImageChops.multiply(big, fade)
        glow = big.filter(ImageFilter.MaxFilter(13)).filter(ImageFilter.GaussianBlur(9))
        icon.alpha_composite(tint(glow, (255, 214, 140), 0.55), (x - pad, y - pad))
        line = ImageChops.multiply(big.filter(ImageFilter.MaxFilter(9)), fade)
        icon.alpha_composite(tint(line, (14, 22, 14), 0.9), (x - pad, y - pad))
        icon.alpha_composite(b, (x, y))
    # the forest floor: the busts fade into shadow at the bottom
    floor = Image.new("L", (n, n), 0)
    fd = ImageDraw.Draw(floor)
    for y in range(n):
        t = max(0.0, (y - n * 0.80) / (n * 0.20))
        fd.line((0, y, n, y), fill=int(255 * min(1.0, t) ** 1.4 * 0.85))
    icon = Image.composite(Image.new("RGBA", (n, n), (10, 26, 16, 255)), icon, floor)
    return vignette(icon)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cut", help="a portraits run directory: cut its cats out into docs/public/cats/ first")
    a = ap.parse_args()
    if a.cut:
        cut_run(Path(a.cut))
    icon = compose().convert("RGB")
    for size in (512, 400, 256):
        icon.resize((size, size), Image.LANCZOS).save(OUT / f"pack-icon-{size}.png", optimize=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    icon.resize((64, 64), Image.LANCZOS).save(PREVIEW / "pack-icon-64.png")
    print("wrote docs/public/pack-icon-{512,400,256}.png and build/icon/pack-icon-64.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
