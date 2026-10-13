#!/usr/bin/env python3
"""Generate real Clan-lands worlds on the full pack (headless) and inspect every landmark: pictures plus checks.

    python tools/inspect_world.py                       # seeds 4242 777001 + one random, Free and Campaign
    python tools/inspect_world.py --seeds 4242 --no-random --run-id quick
    python tools/inspect_world.py --reuse --run-id r1   # re-analyse the worlds already generated

For each seed: boots the full pack (pack/mods + the current Core, rebuilt first when its sources are newer) on a
dedicated server at BelowNormal priority with no window, as a Free Mode world; forceloads aligned boxes round every
landmark, culvert, tunnel mouth, waterfall and stretch of road the startup log names; waits until they are fully
generated; stops. A second, Campaign-mode boot of the same seed checks only where a Campaign world spawns.

Then, from the region files alone: a labelled top-down tile per landmark (contact sheets per map), side slices
(camps through their way in, every waterfall along its stream, bridges and culverts across the road, tunnel mouths
toward the cavern), tools/scan_region.py on every box, and automatic checks:
  camps        a walking search from the middle finds exactly one way out, at the planned way in
  island       the island is cut off by water, and can be walked to over the tree-bridge
  tunnels      every mouth reaches the cavern through open ground underground
  waterfalls   a real column of water at each fall
  roads        no road far off the land beside it, no buried road, bridges have piers, culverts are open
  spawn        Free Mode by the Sky Oak, Campaign at Fourtrees, on ground a cat can stand on
  others       a signature check per landmark kind (trunks at Fourtrees, farmland at farms, a cave at Mothermouth...)
Writes build/inspect/<run-id>/report.html. Never point this at the hub.
"""
from __future__ import annotations

import argparse
import html
import json
import math
import random
import re
import shutil
import struct
import subprocess
import sys
import threading
import queue
import time
import zlib
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import full_pack_server as fps  # noqa: E402
from render_region_map import colour, nbt  # noqa: E402

CORE = ROOT / "dist/greatjourney_core-0.1.0.jar"
STAGE = ROOT / "build/server-inspect"
PORT: int | None = None                       # --port: a fixed server port instead of any free one
FULL =b"\x06Status\x00\x0eminecraft:full"

# ---------------------------------------------------------------------------------------------------- server


def core_is_stale() -> bool:
    if not CORE.exists():
        return True
    built = CORE.stat().st_mtime
    for module in ("clanlands", "clanwild"):
        for f in (ROOT / "mods" / module / "src/main").rglob("*"):
            if f.is_file() and f.stat().st_mtime > built:
                return True
    return False


def rebuild_core() -> None:
    print("Core sources are newer than the jar: rebuilding", flush=True)
    r = subprocess.run([sys.executable, str(TOOLS / "gradle_locked.py"), ":clanlands:jar", ":clanwild:jar", "-q"], cwd=ROOT)
    if r.returncode:
        raise SystemExit("Gradle build failed")
    r = subprocess.run([sys.executable, str(TOOLS / "build_core_jar.py")], cwd=ROOT)
    if r.returncode:
        raise SystemExit("build_core_jar failed")


class Server:
    """One headless full-pack server: started, fed commands, read line by line, stopped."""

    def __init__(self, stage: Path, args_txt: Path, log_path: Path):
        cmd = ["java", f"@{stage / 'user_jvm_args.txt'}", f"@{args_txt}", "nogui"]
        flags = fps.BELOW_NORMAL_PRIORITY_CLASS | getattr(subprocess, "CREATE_NO_WINDOW", 0) if sys.platform == "win32" else 0
        self.proc = subprocess.Popen(cmd, cwd=stage, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                     bufsize=1, creationflags=flags)
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.log = open(log_path, "a", encoding="utf-8")
        self.all: list[str] = []
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        try:
            for raw in self.proc.stdout:
                line = raw.rstrip("\n")
                self.log.write(line + "\n")
                self.all.append(line)
                self.lines.put(line)
        finally:
            self.lines.put(None)

    def send(self, text: str):
        try:
            self.proc.stdin.write(text + "\n")
            self.proc.stdin.flush()
        except OSError:
            pass

    def wait_for(self, pattern: re.Pattern, timeout: float) -> str | None:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                line = self.lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line is None:
                raise SystemExit(f"server exited {self.proc.poll()}")
            for pat in fps.FAIL_RES:
                if pat.search(line):
                    self.stop()
                    raise SystemExit(f"FAIL: {line.strip()}")
            if pattern.search(line):
                return line
        return None

    def stop(self):
        if self.proc.poll() is None:
            self.send("stop")
            try:
                self.proc.wait(timeout=240)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.log.close()


def stage_for(seed: int, mode: str, level: str, core: Path, fresh: bool) -> Path:
    stage = STAGE
    stage.mkdir(parents=True, exist_ok=True)
    fps.install_neoforge(stage)
    fps.stage_files(stage, core, [], str(seed))
    props = (stage / "server.properties").read_text(encoding="utf-8")
    (stage / "server.properties").write_text(props + f"level-name={level}\n" + (f"server-port={PORT}\n" if PORT else ""),
                                             encoding="utf-8")
    (stage / "config").mkdir(exist_ok=True)
    (stage / "config/clanlands-common.toml").write_text(f'[modes]\n\tnewWorldMode = "{mode}"\n', encoding="utf-8")
    if fresh:
        shutil.rmtree(stage / level, ignore_errors=True)
    shutil.rmtree(stage / "crash-reports", ignore_errors=True)
    return stage


def chunk_status(region: Path, chunks: set[tuple[int, int]], done: set[tuple[int, int]]) -> None:
    """Adds to done every chunk of chunks that is saved fully generated."""
    by_file: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for c in chunks - done:
        by_file.setdefault((c[0] >> 5, c[1] >> 5), []).append(c)
    for (rx, rz), cs in by_file.items():
        f = region / f"r.{rx}.{rz}.mca"
        if not f.exists():
            continue
        try:
            data = f.read_bytes()
        except OSError:
            continue
        for cx, cz in cs:
            i = (cx & 31) + (cz & 31) * 32
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big") * 4096
            if not off or off + 5 > len(data) or data[off + 4] != 2:
                continue
            ln = struct.unpack_from(">i", data, off)[0]
            try:
                raw = zlib.decompress(data[off + 5:off + 4 + ln])
            except zlib.error:
                continue
            if FULL in raw:
                done.add((cx, cz))


def forceload_batches(chunks: set[tuple[int, int]], per_batch: int = 700) -> list[tuple[set, list[str]]]:
    """Aligned 16x16-chunk tiles (each forceloads the rectangle round the chunks it needs, at most 256), gathered
    into batches of about per_batch chunks: forceloading thousands at once runs a 6 GB server out of memory."""
    tiles: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for cx, cz in chunks:
        tiles.setdefault((cx >> 4, cz >> 4), []).append((cx, cz))
    batches, cur, cmds, n = [], set(), [], 0
    for key in sorted(tiles):
        cs = tiles[key]
        x0, x1 = min(c[0] for c in cs), max(c[0] for c in cs)
        z0, z1 = min(c[1] for c in cs), max(c[1] for c in cs)
        cur |= {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}
        cmds.append(f"{x0 * 16} {z0 * 16} {x1 * 16 + 15} {z1 * 16 + 15}")
        if len(cur) >= per_batch:
            batches.append((cur, cmds))
            cur, cmds = set(), []
    if cmds:
        batches.append((cur, cmds))
    return batches


DONE = re.compile(r"Done \([^)]+\)! For help")
SAVED = re.compile(r"Saved the game")


def boot(seed: int, mode: str, level: str, core: Path, out: Path, plan=None, fresh=True, max_wait=5400) -> list[str]:
    """Boots once; plan(log_lines) -> chunk set to generate (None: just boot). Returns the server's lines."""
    stage = stage_for(seed, mode, level, core, fresh)
    args_txt = fps.install_neoforge(stage)
    srv = Server(stage, args_txt, out / f"server-{level}.log")
    try:
        if srv.wait_for(DONE, 1500) is None:
            raise SystemExit("TIMEOUT waiting for Done")
        print(f"[{level}] server up", flush=True)
        if plan:
            need = plan(srv.all)
            region = stage / level / "region"
            done: set[tuple[int, int]] = set()
            start = time.monotonic()
            batches = forceload_batches(need)
            for bi, (batch, cmds) in enumerate(batches):
                for c in cmds:
                    srv.send("forceload add " + c)
                last, last_progress = -1, time.monotonic()
                while time.monotonic() - start < max_wait:
                    time.sleep(20)
                    srv.send("save-all flush")
                    saved = srv.wait_for(SAVED, 600)
                    chunk_status(region, batch, done)
                    got = len(batch & done)
                    print(f"[{level}] batch {bi + 1}/{len(batches)}: {got}/{len(batch)}; all {len(done)}/{len(need)}"
                          + ("" if saved else " (save still queued)"), flush=True)
                    if got >= len(batch):
                        break
                    if got > last:
                        last, last_progress = got, time.monotonic()
                    elif time.monotonic() - last_progress > 600:
                        print(f"[{level}] batch stalled; moving on with {len(batch) - got} missing", flush=True)
                        break
                for c in cmds:
                    srv.send("forceload remove " + c)
        else:
            time.sleep(10)
        srv.send("save-all flush")
        srv.wait_for(SAVED, 180)
    finally:
        srv.stop()
    crashes = list((stage / "crash-reports").glob("*.txt")) if (stage / "crash-reports").exists() else []
    if crashes:
        raise SystemExit(f"crash report {crashes[0]}")
    return srv.all

# ---------------------------------------------------------------------------------------------------- the log


LM = re.compile(r"Clanlands landmark (lake|old forest|journey) (\w+) at (-?\d+) (-?\d+)")
ROAD = re.compile(r"Clanlands road (lake|old forest|journey) (\w+) length (\d+):((?: -?\d+,-?\d+)+)")
CULV = re.compile(r"Clanlands culvert (lake|old forest|journey) at (-?\d+) (-?\d+) floor (-?\d+)")
TUN = re.compile(r"Clanlands tunnel entrance at (-?\d+) (-?\d+) (-?\d+)")
FALL = re.compile(r"Clanlands waterfall at (-?\d+) (-?\d+) from y (-?\d+) to (-?\d+)")
SPAWN = re.compile(r"Clanlands: (\w+) world, spawn set at (-?\d+) (-?\d+) (-?\d+)")
CAMP = re.compile(r"Clanlands camp \(seed -?\d+\) (\w+) at (-?\d+) (-?\d+): way in at (-?\d+) degrees "
                  r"\(outside (-?\d+) (-?\d+)\)(?:, barrier ([\d.]+) to ([\d.]+) round (-?[\d.]+) (-?[\d.]+))?, dens(.*)")
CENTRE = re.compile(r"the lake territories centre on \((-?\d+), (-?\d+)\); the old forest's Fourtrees is at \((-?\d+), (-?\d+)\)")


def parse(lines: list[str]) -> dict:
    d = {"landmarks": [], "roads": [], "culverts": [], "tunnels": [], "falls": [], "camps": [], "spawn": None}
    for line in lines:
        if m := LM.search(line):
            d["landmarks"].append({"map": m[1], "kind": m[2], "x": int(m[3]), "z": int(m[4])})
        elif m := ROAD.search(line):
            pts = [tuple(map(int, p.split(","))) for p in m[4].split()]
            d["roads"].append({"map": m[1], "kind": m[2], "length": int(m[3]), "pts": pts})
        elif m := CULV.search(line):
            d["culverts"].append({"map": m[1], "x": int(m[2]), "z": int(m[3]), "floor": int(m[4])})
        elif m := TUN.search(line):
            d["tunnels"].append(tuple(map(int, m.groups())))
        elif m := FALL.search(line):
            d["falls"].append(tuple(map(int, m.groups())))
        elif m := SPAWN.search(line):
            d["spawn"] = {"mode": m[1], "x": int(m[2]), "y": int(m[3]), "z": int(m[4])}
        elif m := CAMP.search(line):
            if not any(c["x"] == int(m[2]) and c["z"] == int(m[3]) for c in d["camps"]):
                d["camps"].append({"style": m[1], "x": int(m[2]), "z": int(m[3]), "gate": int(m[4]),
                                   "ox": int(m[5]), "oz": int(m[6]), "dens": m[11].strip()}
                                  | ({"barrier": (float(m[7]), float(m[8])), "cx": float(m[9]), "cz": float(m[10])} if m[7] else {}))
        elif m := CENTRE.search(line):
            d["lake_centre"] = (int(m[1]), int(m[2]))
            d["fourtrees"] = (int(m[3]), int(m[4]))
    return d


CAMP_KINDS = {"STONE_HOLLOW", "WINDCLAN_HOLLOW", "RIVERCLAN_CAMP", "SHADOWCLAN_CAMP", "THUNDERCLAN_RAVINE"}
ROAD_KINDS = {"ROAD", "CULVERT", "HIGHWAY_STRETCH"}
WATER_KINDS = {"ISLAND_TREE", "TREE_BRIDGE", "HALF_BRIDGE", "STEPPING_STONES", "SNAKEROCKS", "THUNDERCLAN_RAVINE",
               "TUNNELS", "TUNNEL_NETWORK", "WATERFALL"}
RADIUS ={k: 96 for k in CAMP_KINDS} | {
    "ISLAND_TREE": 110, "TREE_BRIDGE": 60, "STEPPING_STONES": 36, "MOONPOOL": 80, "SKY_OAK": 40, "ABANDONED_NEST": 48,
    "HORSEPLACE": 64, "HALF_BRIDGE": 40, "KITTYPET_HOME": 48, "TUNNELS": 40, "FOURTREES": 80, "OWL_TREE": 40,
    "SNAKEROCKS": 64, "GREAT_SYCAMORE": 40, "SUNNINGROCKS": 64, "CARRIONPLACE": 64, "BARLEY_FARM": 80,
    "MOTHERMOUTH": 80, "HIGHWAY_STRETCH": 48, "TWOLEG_FARMS": 80, "TRIBE_CAVE": 80, "LAKE_RIDGE": 80,
    "MIDNIGHT_SETT": 64, "LONER_SPOT": 40,
}


def pick_highway(landmarks):
    """Every landmark, but only three stretches of the long highway."""
    hw = [l for l in landmarks if l["kind"] == "HIGHWAY_STRETCH"]
    keep = [hw[i] for i in sorted({0, len(hw) // 2, len(hw) - 1})] if hw else []
    return [l for l in landmarks if l["kind"] != "HIGHWAY_STRETCH"] + keep


def boxes(d: dict) -> list[dict]:
    """Every area to generate and inspect: {name, group, box(x0 z0 x1 z1), ...}."""
    out = []
    landmarks_all = pick_highway(d["landmarks"])
    for l in landmarks_all:
        r = RADIUS.get(l["kind"], 64)
        group = {"lake": "lake", "old forest": "forest", "journey": "journey"}[l["map"]]
        same = sum(1 for o in landmarks_all if o["map"] == l["map"] and o["kind"] == l["kind"])
        label = f"{l['map']} {l['kind']}" + (f" {l['x']} {l['z']}" if same > 1 else "")
        out.append({"name": label, "group": group, "kind": l["kind"], "x": l["x"], "z": l["z"],
                    "box": (l["x"] - r, l["z"] - r, l["x"] + r, l["z"] + r)})
    if d["tunnels"]:
        cav = next((l for l in d["landmarks"] if l["kind"] == "TUNNELS"), None)
        if cav:
            xs = [t[0] for t in d["tunnels"]] + [cav["x"]]
            zs = [t[2] for t in d["tunnels"]] + [cav["z"]]
            out.append({"name": "lake tunnel network", "group": "lake", "kind": "TUNNEL_NETWORK", "x": cav["x"],
                        "z": cav["z"], "box": (min(xs) - 36, min(zs) - 36, max(xs) + 36, max(zs) + 36)})
    for i, f in enumerate(d["falls"]):
        out.append({"name": f"waterfall {f[0]} {f[1]} ({f[2]}->{f[3]})", "group": "falls", "kind": "WATERFALL",
                    "x": f[0], "z": f[1], "top": f[2], "bottom": f[3], "box": (f[0] - 28, f[1] - 28, f[0] + 28, f[1] + 28)})
    for c in d["culverts"]:
        out.append({"name": f"{c['map']} culvert {c['x']} {c['z']}", "group": "roads", "kind": "CULVERT", "x": c["x"],
                    "z": c["z"], "floor": c["floor"], "box": (c["x"] - 32, c["z"] - 32, c["x"] + 32, c["z"] + 32)})
    for rd in d["roads"]:
        if rd["kind"] == "HIGHWAY":
            continue
        for j, (x, z) in enumerate(rd["pts"]):
            out.append({"name": f"{rd['map']} {rd['kind']} @{j * 200}", "group": "roads", "kind": "ROAD", "x": x, "z": z,
                        "road": rd, "box": (x - 28, z - 28, x + 28, z + 28)})
    return out


def chunks_of(areas: list[dict]) -> set[tuple[int, int]]:
    out = set()
    for a in areas:
        x0, z0, x1, z1 = a["box"]
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                out.add((cx, cz))
    return out

# ---------------------------------------------------------------------------------------------------- the blocks


class World:
    """Reads a world's region files: block volumes for boxes, with every chunk decoded once."""

    def __init__(self, region: Path):
        self.region = region
        self.files: dict[tuple[int, int], bytes | None] = {}
        self.sections: dict[tuple[int, int], dict | None] = {}
        self.names: dict[str, int] = {}
        self._cats: dict[str, np.ndarray] = {}

    def _file(self, rx, rz):
        k = (rx, rz)
        if k not in self.files:
            f = self.region / f"r.{rx}.{rz}.mca"
            self.files[k] = f.read_bytes() if f.exists() else None
        return self.files[k]

    def chunk(self, cx, cz):
        """{section y: 4096 ids (y, z, x)} of a fully generated chunk, else None."""
        k = (cx, cz)
        if k in self.sections:
            return self.sections[k]
        out = None
        data = self._file(cx >> 5, cz >> 5)
        if data:
            i = (cx & 31) + (cz & 31) * 32
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big") * 4096
            if off and data[off + 4] == 2:
                ln = struct.unpack_from(">i", data, off)[0]
                b = zlib.decompress(data[off + 5:off + 4 + ln])
                if FULL in b:
                    root, _ = nbt(b, 3 + struct.unpack_from(">H", b, 1)[0], 10)
                    out = {}
                    for s in root.get("sections", []):
                        if "block_states" in s:
                            out[s["Y"]] = self._ids(s)
        self.sections[k] = out
        return out

    def _ids(self, sec):
        bs = sec["block_states"]
        pal = bs.get("palette") or []
        keys = []
        for p in pal:
            props = p.get("Properties", {})
            key = p["Name"] + ("|lvl" + props["level"] if "level" in props else "")
            if key not in self.names:
                self.names[key] = len(self.names)
                self._cats.clear()
            keys.append(self.names[key])
        keys = np.array(keys, dtype=np.int32)
        if len(pal) == 1:
            return np.full((16, 16, 16), keys[0], dtype=np.int32)
        bits = max(4, (len(pal) - 1).bit_length())
        per = 64 // bits
        longs = np.array([v & ((1 << 64) - 1) for v in bs["data"]], dtype=np.uint64)
        idx = np.arange(4096)
        vals = (longs[idx // per] >> ((idx % per) * bits).astype(np.uint64)) & np.uint64((1 << bits) - 1)
        return keys[vals.astype(np.int64)].reshape(16, 16, 16)

    def box(self, x0, z0, x1, z1, ylo, yhi) -> np.ndarray:
        """ids[y - ylo, z - z0, x - x0]; -1 where the chunk is not generated, air id above/below stored sections."""
        H, D, W = yhi - ylo + 1, z1 - z0 + 1, x1 - x0 + 1
        g = np.full((H, D, W), -1, dtype=np.int32)
        air = self.names.setdefault("minecraft:air", len(self.names))
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                ch = self.chunk(cx, cz)
                if ch is None:
                    continue
                bx, bz = cx * 16, cz * 16
                xa, xb = max(bx, x0), min(bx + 15, x1)
                za, zb = max(bz, z0), min(bz + 15, z1)
                g[:, za - z0:zb - z0 + 1, xa - x0:xb - x0 + 1] = air
                for sy in range(ylo >> 4, (yhi >> 4) + 1):
                    sec = ch.get(sy)
                    if sec is None:
                        continue
                    ya, yb = max(sy * 16, ylo), min(sy * 16 + 15, yhi)
                    g[ya - ylo:yb - ylo + 1, za - z0:zb - z0 + 1, xa - x0:xb - x0 + 1] = \
                        sec[ya - sy * 16:yb - sy * 16 + 1, za - bz:zb - bz + 1, xa - bx:xb - bx + 1]
        return g

    def cat(self, name: str) -> np.ndarray:
        """A lookup array over ids (index -1 maps to the extra last slot, 'unknown')."""
        if name not in self._cats:
            n = len(self.names)
            arr = np.zeros(n + 1, dtype=bool)
            for key, i in self.names.items():
                arr[i] = CATEGORY[name](key.split("|")[0].split(":")[-1], key)
            self._cats[name] = arr
        return self._cats[name]

    def colours(self) -> np.ndarray:
        n = len(self.names)
        arr = np.zeros((n + 1, 3), dtype=np.uint8)
        for key, i in self.names.items():
            base = key.split("|")[0]
            if base.endswith(":water"):
                arr[i] = (52, 92, 170) if key.endswith("|lvl0") or "|" not in key else (110, 160, 235)
            elif base.endswith(":lava"):
                arr[i] = (230, 90, 20)
            else:
                arr[i] = more_colour(base)
        arr[n] = (40, 40, 40)
        return arr

    def lookup(self, g: np.ndarray, arr: np.ndarray):
        return arr[np.where(g < 0, len(arr) - 1, g)]


EXTRA = (("barley", (205, 185, 95)), ("wheat", (190, 175, 80)), ("carrots", (120, 160, 60)), ("potatoes", (120, 160, 60)),
         ("salt", (232, 228, 218)), ("willow_vine", (80, 130, 60)), ("watergrass", (64, 112, 120)),
         ("lavender", (150, 120, 200)), ("packed_ice", (160, 190, 235)), ("blue_ice", (140, 180, 235)), ("ice", (170, 200, 240)),
         ("_ore", (125, 125, 125)), ("snow_block", (240, 240, 245)), ("netherrack", (110, 40, 40)), ("toadstool", (190, 150, 110)),
         ("reed", (148, 192, 101)), ("cattail", (148, 192, 101)), ("leaf_litter", (165, 115, 50)), ("pebbles", (140, 140, 140)),
         ("dead_grass", (170, 150, 90)), ("dried_grass", (175, 155, 95)), ("desert_grass", (175, 160, 100)), ("pumpkin", (220, 130, 30)),
         ("mangrove_roots", (100, 80, 60)), ("peony", (200, 150, 180)), ("daisy", (225, 225, 200)), ("lily", (60, 130, 60)),
         ("glowshroom", (120, 200, 200)), ("stems", (90, 140, 60)), ("farmland", (110, 76, 50)), ("hay_block", (200, 170, 60)),
         ("mud", (60, 56, 60)), ("moss", (89, 110, 45)), ("root", (110, 85, 60)), ("bush", (70, 120, 50)), ("clover", (90, 150, 60)))


def more_colour(name: str):
    c = colour(name)
    if c != (170, 60, 170):
        return c
    short = name.split(":")[-1]
    for word, col in EXTRA:
        if word in short:
            return col
    if name.startswith("warriorcats_events:"):
        return (95, 145, 65)                       # its herbs and flowers
    return c


PLANT_WORDS = ("grass", "fern", "flower", "sapling", "vine", "sugar_cane", "torch", "carpet", "petals", "lichen",
               "hanging_roots", "tulip", "orchid", "poppy", "dandelion", "allium", "bluet", "cornflower", "lily_of",
               "daisy", "rose_bush", "sunflower", "lilac", "peony", "pitcher", "seagrass", "kelp", "button", "lever",
               "rail", "sign", "banner", "pressure_plate", "tripwire", "cobweb", "dead_bush", "sweet_berry_bush",
               "spore_blossom", "glow_berries", "cave_vines", "bush", "clover", "reeds", "cattail", "pebble", "leaf_litter")
AIRS = ("air", "cave_air", "void_air")


_CROSS: set[str] | None = None


def cross_plants() -> set[str]:
    """Modded blocks the pack draws as a cross of two planes (flowers, herbs, reeds, toadstools): plants a cat walks
    through, not blocks it stands on. Read once from the blockstates and models in pack/mods; names like
    biomesoplenty:violet or warriorcats_events:yarrow match none of the plant words, and counting them as solid
    let the camp check step up onto a flower and over a hedge it could not really climb."""
    global _CROSS
    if _CROSS is None:
        import zipfile
        _CROSS = set()
        for jar in sorted((ROOT / "pack/mods").glob("*.jar")):
            try:
                z = zipfile.ZipFile(jar)
                files = set(z.namelist())
            except (OSError, zipfile.BadZipFile):
                continue
            for f in files:
                m = re.match(r"assets/([^/]+)/blockstates/([^/]+)\.json$", f)
                if not m:
                    continue
                try:
                    models = re.findall(r'"model"\s*:\s*"([^"]+)"', z.read(f).decode("utf-8", "replace"))
                except (OSError, KeyError):
                    continue
                for mo in models[:2]:
                    ns, path = mo.split(":", 1) if ":" in mo else ("minecraft", mo)
                    mf = f"assets/{ns}/models/{path}.json"
                    if mf in files:
                        try:
                            parent = json.loads(z.read(mf)).get("parent", "")
                        except (ValueError, OSError):
                            continue
                        if "cross" in parent:
                            _CROSS.add(f"{m[1]}:{m[2]}")
                            break
    return _CROSS


def _passable(short, key):
    if short in AIRS:
        return True
    if not key.startswith("minecraft:") and key.split("|")[0] in cross_plants():
        return True
    if short in WETS or short == "lava":
        return False
    if short in ("grass_block", "tall_grass_block", "azalea", "flowering_azalea") or short.endswith("_block") \
            or "leaves" in short or short.endswith("_log") or short.endswith("_wood") or "mushroom_block" in short:
        return False
    if short == "snow":
        return True
    if short.endswith("mushroom"):
        return True
    return any(w in short for w in PLANT_WORDS) and not short.endswith("_slab")


WETS = ("water", "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant")


def _fluid(short, key):
    return short in WETS or short == "lava"


CATEGORY = {
    "air": lambda s, k: s in AIRS,
    "water": lambda s, k: s in WETS,
    "flowing": lambda s, k: s == "water" and "|lvl" in k and not k.endswith("|lvl0"),
    "lava": lambda s, k: s == "lava",
    "passable": _passable,
    "solid": lambda s, k: not _passable(s, k) and not _fluid(s, k),
    "tall": lambda s, k: ("fence" in s or (s.endswith("_wall") and "torch" not in s and "sign" not in s)
                          or s.endswith("_pane") or s == "iron_bars"),
    "log": lambda s, k: s.endswith("_log") or s.endswith("_wood") or s.endswith("_stem"),
    "leaves": lambda s, k: "leaves" in s,
    "ground": lambda s, k: not _passable(s, k) and not _fluid(s, k) and "leaves" not in s
                           and not s.endswith("_log") and not s.endswith("_wood"),
    "road": lambda s, k: s in ("black_concrete", "gray_concrete", "white_concrete", "yellow_concrete",
                               "black_concrete_powder", "gray_concrete_powder"),
    "farmland": lambda s, k: s == "farmland",
    "pebble": lambda s, k: "pebble" in s,
    "built": lambda s, k: "planks" in s or "brick" in s or "fence" in s or s.endswith("_door") or "glass" in s
                          or s.endswith("_stairs") or s.endswith("_slab") or "terracotta" in s or "wool" in s,
    "rock": lambda s, k: s in ("stone", "andesite", "diorite", "granite", "cobblestone", "mossy_cobblestone", "tuff",
                               "calcite", "smooth_stone", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
                               "deepslate", "cobbled_deepslate", "gravel"),
}


def tops(w: World, g: np.ndarray, what: str = "notair") -> np.ndarray:
    """Index (from ylo) of the highest block per column that is not air (or that is in category what); -1 if none."""
    if what == "notair":
        mask = ~w.lookup(g, w.cat("air")) & (g >= 0)
    else:
        mask = w.lookup(g, w.cat(what)) & (g >= 0)
    H = g.shape[0]
    has = mask.any(axis=0)
    idx = H - 1 - np.argmax(mask[::-1], axis=0)
    return np.where(has, idx, -1)


def render_top(w: World, x0, z0, x1, z1, ylo=-10, yhi=220) -> Image.Image:
    g = w.box(x0, z0, x1, z1, ylo, yhi)
    t = tops(w, g)
    cols = w.colours()
    D, W = t.shape
    zz, xx = np.indices((D, W))
    ids = g[np.clip(t, 0, None), zz, xx]
    rgb = cols[np.where((t < 0) | (ids < 0), len(cols) - 1, ids)].astype(np.float32)
    h = t.astype(np.float32)
    west = np.concatenate([h[:, :1], h[:, :-1]], axis=1)
    north = np.concatenate([h[:1, :], h[:-1, :]], axis=0)
    shade = np.clip(1 + ((h - west) + (h - north)) / 8, 0.55, 1.35)[..., None]
    rgb = np.clip(rgb * shade, 0, 255).astype(np.uint8)
    rgb[t < 0] = (24, 24, 24)
    return Image.fromarray(rgb, "RGB")


def render_slice(w: World, x0, z0, x1, z1, ylo, yhi, scale=3, marks=()) -> Image.Image:
    n = max(abs(x1 - x0), abs(z1 - z0)) + 1
    img = np.zeros((yhi - ylo + 1, n, 3), dtype=np.uint8)
    cols = w.colours()
    air = w.cat("air")
    for i in range(n):
        t = i / max(1, n - 1)
        x, z = round(x0 + (x1 - x0) * t), round(z0 + (z1 - z0) * t)
        col = w.box(x, z, x, z, ylo, yhi)[:, 0, 0]
        cols = w.colours() if len(cols) != len(w.names) + 1 else cols
        air = w.cat("air")
        c = cols[np.where(col < 0, len(cols) - 1, col)].copy()
        a = air[np.where(col < 0, len(air) - 1, col)] & (col >= 0)
        c[a] = (200, 225, 245)
        img[:, i] = c[::-1]
    im = Image.fromarray(img, "RGB").resize((n * scale, (yhi - ylo + 1) * scale), Image.NEAREST)
    d = ImageDraw.Draw(im)
    for y in range(ylo - ylo % 10, yhi + 1, 10):         # a height scale every 10 blocks
        py = (yhi - y) * scale
        d.line([(0, py), (6, py)], fill=(0, 0, 0))
        d.text((8, py - 6), str(y), fill=(0, 0, 0))
    for frac, label in marks:
        px = int(frac * n * scale)
        d.line([(px, 0), (px, 12)], fill=(220, 0, 0), width=2)
        d.text((px + 3, 0), label, fill=(220, 0, 0))
    return im

# ---------------------------------------------------------------------------------------------------- checks

YLO, YHI = 30, 200


class Vol:
    """A box of the world with the categories the checks use."""

    def __init__(self, w: World, x0, z0, x1, z1, ylo=YLO, yhi=YHI):
        self.w, self.x0, self.z0, self.ylo = w, x0, z0, ylo
        self.g = w.box(x0, z0, x1, z1, ylo, yhi)
        self.known = self.g >= 0
        L = lambda c: w.lookup(self.g, w.cat(c)) & self.known  # noqa: E731
        self.passable, self.solid, self.water = L("passable"), L("solid"), L("water")
        self.tall, self.log, self.leaves = L("tall"), L("log"), L("leaves")
        self.H, self.D, self.W = self.g.shape

    def idx(self, x, y, z):
        return y - self.ylo, z - self.z0, x - self.x0

    def inside(self, y, z, x):
        return 0 <= y < self.H and 0 <= z < self.D and 0 <= x < self.W

    def cat(self, c):
        return self.w.lookup(self.g, self.w.cat(c)) & self.known

    def top(self, c):
        return tops(self.w, self.g, c)

    def standable(self):
        p, s, H = self.passable, self.solid, self.H
        st = np.zeros_like(p)
        st[1:H - 1] = p[1:H - 1] & p[2:H] & s[0:H - 2] & ~self.tall[0:H - 2]
        return st

    def covered(self):
        """True where some solid block stands above (within the box)."""
        acc = np.logical_or.accumulate(self.solid[::-1], axis=0)[::-1]
        out = np.zeros_like(acc)
        out[:-1] = acc[1:]
        return out


def walk(v: Vol, starts, stand=None, terminal=None, allowed=None):
    """Cells a walking cat can reach from starts: steps up 1, drops up to 3, never swims, never over fences.
    terminal (a (z, x) mask): columns that may be reached but are not walked on from (the edge of the search)."""
    st = v.standable() if stand is None else stand
    p = v.passable
    seen = np.zeros_like(st)
    q = deque()
    for s in starts:
        if v.inside(*s) and st[s] and not seen[s]:
            seen[s] = True
            q.append(s)
    H, D, W = v.H, v.D, v.W
    while q:
        y, z, x = q.popleft()
        if terminal is not None and terminal[z, x]:
            continue
        for dz, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nz, nx = z + dz, x + dx
            if not (0 <= nz < D and 0 <= nx < W) or (allowed is not None and not allowed[nz, nx]):
                continue
            for ny in (y + 1, y, y - 1, y - 2, y - 3):
                if not (1 <= ny < H - 1) or seen[ny, nz, nx] or not st[ny, nz, nx]:
                    continue
                if ny == y + 1 and not (y + 2 < H and p[y + 2, z, x]):
                    continue
                if ny < y and not p[ny + 2:y + 2, nz, nx].all():
                    continue
                seen[ny, nz, nx] = True
                q.append((ny, nz, nx))
    return seen


def clusters(angles_deg, gap=20.0):
    """Groups bearings (degrees) into arcs separated by more than gap; returns [(centre, width)]."""
    if not len(angles_deg):
        return []
    a = sorted(set(round(x) % 360 for x in angles_deg))
    groups, cur = [], [a[0]]
    for x in a[1:]:
        if x - cur[-1] > gap:
            groups.append(cur)
            cur = [x]
        else:
            cur.append(x)
    groups.append(cur)
    if len(groups) > 1 and (groups[0][0] + 360) - groups[-1][-1] <= gap:   # wrap round 0
        groups[0] = [g - 360 for g in groups[-1]] + groups[0]
        groups.pop()
    return [(((g[0] + g[-1]) / 2) % 360, g[-1] - g[0]) for g in groups]


def angdiff(a, b):
    return abs((a - b + 180) % 360 - 180)


def starts_near(v: Vol, cx, cz, r=3, around=None):
    st = v.standable()
    gtop = v.top("ground")
    zi, xi = cz - v.z0, cx - v.x0
    ring = gtop[max(0, zi - 6):zi + 7, max(0, xi - 6):xi + 7]
    gy = int(np.median(ring[ring >= 0])) if (ring >= 0).any() else v.H // 2
    out = []
    for dz in range(-r, r + 1):
        for dx in range(-r, r + 1):
            for y in range(max(1, gy - 2), min(v.H - 1, gy + 5)):
                if v.inside(y, zi + dz, xi + dx) and st[y, zi + dz, xi + dx]:
                    out.append((y, zi + dz, xi + dx))
    return out, st, gy


def check_camp(w: World, a: dict, plan: dict | None) -> dict:
    cx, cz = (plan["x"], plan["z"]) if plan else (a["x"], a["z"])
    # distances from the camp's exact middle to block centres, as the painter measures them, when the log gives it
    fx, fz, off = (plan["cx"], plan["cz"], 0.5) if plan and "cx" in plan else (cx, cz, 0.0)
    if plan:
        # the barrier's outer edge: logged exactly (the quarry's sheer wall runs to cliff + 2.5, the hedge to wallOut);
        # older logs only give a point 4 blocks out past max(wallOut, cliff), truncated, which put the stone hollow's
        # "outside" at the foot of its own wall
        R = plan["barrier"][1] if "barrier" in plan else math.hypot(plan["ox"] - cx, plan["oz"] - cz) - 4
        gate = plan["gate"] % 360
    else:
        R, gate = 40, None
    half = int(R + 26)
    v = Vol(w, cx - half, cz - half, cx + half, cz + half)
    starts, st, gy = starts_near(v, cx, cz)
    res = {"check": "one way in", "radius": round(R, 1), "gate": gate}
    if not starts:
        return res | {"ok": False, "detail": "no open ground to stand on in the middle"}
    zz, xx = np.indices((v.D, v.W))
    dist = np.hypot(xx + v.x0 + off - fx, zz + v.z0 + off - fz)
    bearing = np.degrees(np.arctan2(zz + v.z0 + off - fz, xx + v.x0 + off - fx)) % 360
    # first the camp's own ground, up to a little inside the barrier
    inner = walk(v, starts, st, dist >= R - 6)
    ys, zs, xs = np.nonzero(inner)
    ring = dist[zs, xs] >= R - 9
    if not ring.any():
        return res | {"ok": False, "detail": f"the middle does not reach its own edge (walked {int(dist[zs, xs].max())} blocks)"}
    # then, wedge by wedge, whether the edge of the camp leads out through the barrier there: kept to its wedge, the
    # walk cannot slip out at the way in and come round the outside, so each opening shows only at its own bearing
    # a way out has to come out under the sky: a cave passing under the barrier is not a way through it
    ground3 = v.cat("ground")
    roofed = np.zeros_like(ground3)
    for k in range(1, 13):
        roofed[:-k] |= ground3[k:]
    open_at = []
    for theta in range(0, 360, 4):
        wedge = (np.abs((bearing - theta + 180) % 360 - 180) <= 7) & (dist <= R + 2)
        sel = ring & wedge[zs, xs]
        if not sel.any():
            continue
        got = walk(v, list(zip(ys[sel], zs[sel], xs[sel])), st, None, wedge)
        gy, gz, gx = np.nonzero(got)
        if ((dist[gz, gx] >= R + 1) & ~roofed[gy, gz, gx]).any():
            open_at.append(theta)
    cl = clusters(open_at, gap=12)
    res["ways"] = [round(c[0]) for c in cl]
    res["widths"] = [round(c[1]) for c in cl]
    res["exits"] = []
    for theta in open_at:
        t = math.radians(theta)
        res["exits"].append((int(cx + math.cos(t) * (R + 1)), int(cz + math.sin(t) * (R + 1))))
    if not cl:
        return res | {"ok": False, "detail": "sealed: no way out through the barrier"}
    if gate is None:
        return res | {"ok": len(cl) == 1, "detail": f"{len(cl)} way(s) through the barrier at {res['ways']} (no plan logged)"}
    near = [c for c in cl if angdiff(c[0], gate) <= 30]
    ok = len(cl) == 1 and len(near) == 1
    return res | {"ok": ok, "detail": f"{len(cl)} way(s) through the barrier at {res['ways']} deg (arcs {res['widths']} deg wide); "
                  f"planned way in {gate} deg" + ("" if ok else " (expected exactly one, at the way in)")}


def check_island(w: World, a: dict) -> dict:
    cx, cz, half = a["x"], a["z"], 150
    v = Vol(w, cx - half, cz - half, cx + half, cz + half, 40, 160)
    tg, tw = v.top("ground"), v.top("water")
    land = (tg >= tw) & (tg >= 0)
    zi, xi = cz - v.z0, cx - v.x0
    if not land[zi, xi]:
        # the tree stands near, not on, the middle: take the nearest land
        pts = np.argwhere(land[zi - 20:zi + 21, xi - 20:xi + 21])
        if not len(pts):
            return {"check": "island", "ok": False, "detail": "no land at the island"}
        k = np.argmin(np.hypot(pts[:, 0] - 20, pts[:, 1] - 20))
        zi, xi = zi - 20 + pts[k][0], xi - 20 + pts[k][1]
    seen = np.zeros_like(land)
    seen[zi, xi] = True
    q = deque([(zi, xi)])
    while q:
        z, x = q.popleft()
        for dz, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nz, nx = z + dz, x + dx
            if 0 <= nz < v.D and 0 <= nx < v.W and land[nz, nx] and not seen[nz, nx]:
                seen[nz, nx] = True
                q.append((nz, nx))
    zz, xx = np.nonzero(seen)
    reach = float(np.hypot(xx - (cx - v.x0), zz - (cz - v.z0)).max())
    edge = zz.min() == 0 or xx.min() == 0 or zz.max() == v.D - 1 or xx.max() == v.W - 1
    if edge:
        return {"check": "island", "ok": False, "detail": f"the island's land runs on to the mainland (no water all round)"}
    # from the island's own ground round the middle: the middle column is the great tree's trunk, and starting in it
    # put the cat in a cave under the island or on top of the crown, never on the ground the bridge leads from
    starts = [(int(tg[z, x]) + 1, z, x) for z, x in zip(*np.nonzero(seen))
              if abs(z - zi) <= 12 and abs(x - xi) <= 12 and tg[z, x] >= 0]
    walked = walk(v, starts)
    ys, zs, xs = np.nonzero(walked)
    off = ~seen[zs, xs] & land[zs, xs]
    far = np.hypot(xs[off] - (cx - v.x0), zs[off] - (cz - v.z0))
    crossed = bool((far > reach + 4).any())
    trunk = longest_run(v.log[:, max(0, zi - 25):zi + 26, max(0, xi - 25):xi + 26])
    ok = crossed and trunk >= 12
    return {"check": "island + tree-bridge", "ok": ok,
            "detail": f"water all round (island reaches {reach:.0f} blocks); "
                      + ("walkable to the mainland over the bridge" if crossed else "NOT walkable to the mainland")
                      + f"; tallest trunk {trunk}"}


def longest_run(mask3: np.ndarray) -> int:
    """The longest vertical run of True in any column of a (y, z, x) mask."""
    best = 0
    if mask3.size == 0:
        return 0
    run = np.zeros(mask3.shape[1:], dtype=np.int32)
    for y in range(mask3.shape[0]):
        run = np.where(mask3[y], run + 1, 0)
        best = max(best, int(run.max()))
    return best


def check_tunnels(w: World, a: dict, d: dict) -> dict:
    cav = next(l for l in d["landmarks"] if l["kind"] == "TUNNELS")
    cx, cz = cav["x"], cav["z"]
    res = []
    for ex, ey, ez in d["tunnels"]:
        x0, x1 = min(ex, cx) - 24, max(ex, cx) + 24
        z0, z1 = min(ez, cz) - 24, max(ez, cz) + 24
        v = Vol(w, x0, z0, x1, z1, 0, ey + 8)
        openm = (v.passable | v.water) & v.known
        cov = v.covered()
        # the cavern: the deepest long run of open, covered blocks under its middle
        col_open = openm[:, cz - v.z0, cx - v.x0] & cov[:, cz - v.z0, cx - v.x0]
        ys = np.nonzero(col_open)[0]
        if not len(ys):
            res.append(f"mouth {ex} {ey} {ez}: no cavern found under {cx} {cz}")
            continue
        cy = int(np.median(ys))
        sy, sz, sx = v.idx(ex, ey, ez)
        start = None
        for r in range(0, 5):
            for dy in range(-r, r + 3):
                for dz in range(-r, r + 1):
                    for dx in range(-r, r + 1):
                        c = (sy + dy, sz + dz, sx + dx)
                        if start is None and v.inside(*c) and openm[c]:
                            start = c
        if start is None:
            res.append(f"mouth {ex} {ey} {ez}: mouth is filled in")
            continue
        seen = np.zeros_like(openm)
        seen[start] = True
        q = deque([start])
        reached = False
        best = 1e9
        while q:
            y, z, x = q.popleft()
            dist = math.hypot(x - (cx - v.x0), z - (cz - v.z0))
            best = min(best, dist + abs(y - cy))
            if dist <= 8 and abs(y - cy) <= 6:
                reached = True
                break
            for dy, dz, dx in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                n = (y + dy, z + dz, x + dx)
                if not v.inside(*n) or seen[n] or not openm[n]:
                    continue
                if not cov[n] and math.hypot(n[2] - sx, n[1] - sz) > 60:
                    continue                    # stay underground once past the mouth
                seen[n] = True
                q.append(n)
        res.append(f"mouth {ex} {ey} {ez}: " + ("reaches the cavern" if reached else f"does NOT reach the cavern (closest {best:.0f})"))
    ok = bool(res) and all("reaches" in r for r in res) and len(d["tunnels"]) >= 3
    return {"check": "tunnel mouths reach the cavern", "ok": ok, "detail": "; ".join(res) or "no mouths logged"}


def check_fall(w: World, a: dict) -> dict:
    x, z, top, bot = a["x"], a["z"], a["top"], a["bottom"]
    v = Vol(w, x - 5, z - 5, x + 5, z + 5, bot - 4, top + 4)
    run = longest_run(v.water)
    # Waters hangs the curtain from the real brink, up to 8-11 blocks along the stream from the planned fall, down to
    # wherever the pool really stands: further out only a falling (flowing) column counts, never a deep still pool
    far = Vol(w, x - 12, z - 12, x + 12, z + 12, bot - 20, top + 4)
    run = max(run, longest_run(far.cat("flowing")))
    need = max(2, top - bot - 1) if top - bot >= 3 else 2
    flowing = int((v.cat("flowing")).sum())
    ok = run >= need
    return {"check": "column of water", "ok": ok, "detail": f"drop {top - bot}, longest water column {run} (need {need}), {flowing} flowing blocks"}


def stream_dir(v: Vol, x, z, r=14):
    tw, tg = v.top("water"), v.top("ground")
    zi, xi = z - v.z0, x - v.x0
    pts = [(dx, dz) for dx in range(-r, r + 1) for dz in range(-r, r + 1)
           if dx * dx + dz * dz <= r * r and 0 <= zi + dz < v.D and 0 <= xi + dx < v.W
           and tw[zi + dz, xi + dx] >= 0 and tw[zi + dz, xi + dx] > tg[zi + dz, xi + dx]]
    if len(pts) < 3:
        return None
    P = np.array(pts, dtype=float)
    P -= P.mean(axis=0)
    _, vecs = np.linalg.eigh(P.T @ P)
    return vecs[:, -1]


def road_dir(v: Vol, x, z, r=20):
    rt = road_tops(v)
    zi, xi = z - v.z0, x - v.x0
    zz, xx = np.nonzero(rt[max(0, zi - r):zi + r + 1, max(0, xi - r):xi + r + 1])
    if len(zz) < 4:
        return None
    P = np.stack([xx, zz], axis=1).astype(float)
    P -= P.mean(axis=0)
    _, vecs = np.linalg.eigh(P.T @ P)
    return vecs[:, -1]


def road_tops(v: Vol):
    """Columns whose top block (under leaves and plants, over bridge walls) is road."""
    skip = v.passable | v.leaves | v.tall | ~v.known
    mask = ~skip
    idx = v.H - 1 - np.argmax(mask[::-1], axis=0)
    zz, xx = np.indices(idx.shape)
    road = v.w.lookup(v.g, v.w.cat("road"))
    return road[idx, zz, xx] & mask.any(axis=0)


def check_road(w: World, a: dict) -> dict:
    x0, z0, x1, z1 = a["box"]
    v = Vol(w, x0, z0, x1, z1, 40, 190)
    skip = v.passable | v.leaves | v.tall | ~v.known
    mask = ~skip
    top = np.where(mask.any(axis=0), v.H - 1 - np.argmax(mask[::-1], axis=0), -1)
    rt = road_tops(v)
    n = int(rt.sum())
    if not n:
        return {"check": "road", "ok": None, "detail": "no road in this box"}
    bridge = np.zeros_like(rt)
    for z, x in np.argwhere(rt):
        t = top[z, x]
        below = v.solid[max(0, t - 7):t - 1, z, x]      # under the deck: open (air or water) means a bridge
        if len(below) >= 3 and (~below).sum() >= 3:
            bridge[z, x] = True
    bad = []
    # a cutting or a bank: the land on BOTH sides of the road stands more than 9 off it, the same way. (Measured round
    # the whole column before, a road along the brink of a valley or a river bank, level with the land on its uphill
    # side, counted the valley floor as "the land" and was flagged though nothing was banked up or cut.)
    dvec = road_dir(v, (x0 + x1) // 2, (z0 + z1) // 2, 26)
    bare = ~(skip | v.log)                                           # the land itself, not the trees on it
    land = np.where(bare.any(axis=0), v.H - 1 - np.argmax(bare[::-1], axis=0), -1)
    for z, x in np.argwhere(rt & ~bridge):
        if dvec is None:
            zs, xs = slice(max(0, z - 8), z + 9), slice(max(0, x - 8), x + 9)
            near = top[zs, xs][~rt[zs, xs] & (top[zs, xs] >= 0)]
            if len(near) >= 20 and abs(top[z, x] - float(np.median(near))) > 9:
                bad.append((x + v.x0, z + v.z0, int(top[z, x] + v.ylo), int(np.median(near) + v.ylo)))
            continue
        px, pz = -dvec[1], dvec[0]                                       # across the road
        sides = []
        for sgn in (1, -1):
            hs = []
            for s in range(1, 16):
                cz, cx = round(z + pz * s * sgn), round(x + px * s * sgn)
                if not (0 <= cz < v.D and 0 <= cx < v.W) or rt[cz, cx] or land[cz, cx] < 0:
                    continue
                hs.append(land[cz, cx])
                if len(hs) >= 6:
                    break
            if len(hs) >= 3:
                sides.append(float(np.median(hs)) - top[z, x])
        if not sides:
            continue
        if all(dh > 9 for dh in sides) or all(dh < -9 for dh in sides):
            near = min(sides, key=abs) + top[z, x]
            bad.append((int(x + v.x0), int(z + v.z0), int(top[z, x] + v.ylo), int(near + v.ylo)))
    # buried: road blocks with ground (not leaves or logs) right on top of them
    road3 = v.w.lookup(v.g, v.w.cat("road")) & v.known
    ground3 = v.cat("ground") & ~road3 & ~v.tall                 # a bridge's parapet is not earth on the road
    above = np.zeros_like(ground3)
    above[:-1] = ground3[1:] | (np.concatenate([ground3[2:], np.zeros_like(ground3[:2])])[:-1])
    buried = int((road3 & above).any(axis=0).sum())
    nb = int(bridge.sum())
    trees, pebbles = road_litter(v)
    ok = not bad and buried <= 2 and len(trees) <= 2 and not pebbles
    return {"check": "road on the land", "ok": ok,
            "detail": f"{n} road columns, {nb} on bridges, {len(bad)} off the land by >9"
                      + (f" e.g. {bad[:3]}" if bad else "") + f", {buried} buried" + litter_detail(trees, pebbles)}


def road_litter(v: Vol):
    """Road columns with a tree (log or leaves) in the 30 blocks over the road, and with pebbles lying on it: lists of
    (x, z, y of the road)."""
    road3 = v.cat("road")
    has = road3.any(axis=0)
    ri = v.H - 1 - np.argmax(road3[::-1], axis=0)
    zz, xx = np.indices(has.shape)
    tree = v.log | v.leaves
    over = np.zeros_like(has)
    for dy in range(1, 31):
        over |= tree[np.clip(ri + dy, 0, v.H - 1), zz, xx] & (ri + dy < v.H)
    peb = v.cat("pebble")[np.clip(ri + 1, 0, v.H - 1), zz, xx] & (ri + 1 < v.H)
    pick = lambda m: [(int(x + v.x0), int(z + v.z0), int(ri[z, x] + v.ylo)) for z, x in np.argwhere(m & has)]  # noqa: E731
    return pick(over), pick(peb)


def litter_detail(trees, pebbles) -> str:
    return (f", {len(trees)} under trees" + (f" e.g. {trees[:2]}" if trees else "")
            + f", {len(pebbles)} with pebbles" + (f" e.g. {pebbles[:2]}" if pebbles else ""))


def check_culvert(w: World, a: dict) -> dict:
    x, z, floor = a["x"], a["z"], a["floor"]
    v = Vol(w, x - 24, z - 24, x + 24, z + 24, floor - 6, floor + 30)
    dvec = road_dir(v, x, z)
    if dvec is None:
        return {"check": "culvert open", "ok": False, "detail": "no road found at the culvert"}
    px, pz = -dvec[1], dvec[0]                       # across the road
    open_cells = 0
    for s in range(-14, 15):
        cx, cz = round(x + px * s), round(z + pz * s)
        zi, xi = cz - v.z0, cx - v.x0
        if not (0 <= zi < v.D and 0 <= xi < v.W):
            continue
        y = floor + 1 - v.ylo
        if (v.passable[y, zi, xi] or v.water[y, zi, xi]):
            open_cells += 1
    # and its floor on the ground: no gravel strip hanging in the air over a dip
    floating = 0
    for s in range(-24, 25):
        for w2 in (-1, 0, 1):
            cx, cz = round(x + px * s + dvec[0] * w2), round(z + pz * s + dvec[1] * w2)
            zi, xi = cz - v.z0, cx - v.x0
            if not (0 <= zi < v.D and 0 <= xi < v.W):
                continue
            y = floor - v.ylo
            if v.solid[y, zi, xi] and v.passable[y - 1, zi, xi] and v.passable[y - 2, zi, xi]:
                floating += 1
    out = culvert_walk(w, x, z, floor, dvec)
    ok = open_cells >= 24 and floating <= 2 and all(o["out"] for o in out)
    return {"check": "culvert open", "ok": ok, "detail": f"{open_cells}/29 open blocks along the culvert at y {floor + 1}; "
                                                         f"{floating} floor blocks hanging over air; along its line "
                                                         + "; ".join(o["say"] for o in out),
            "across": (px, pz)}


def culvert_walk(w: World, x, z, floor, dvec) -> list[dict]:
    """Walks out along a culvert's own line from under the middle of the road, each way: a step up of at most one
    block, a drop of at most three, headroom of two, until it stands under open sky at least 16 blocks from the road's
    middle with the land four blocks to either hand no more than a block above it (out of the cut, not at the bottom
    of it). A wall at the end of the cut stops it. Also counts how far the floor climbs on the way (water pools there)."""
    R = 72
    v = Vol(w, x - R, z - R, x + R, z + R, floor - 12, floor + 40)
    px, pz = -dvec[1], dvec[0]
    st = v.standable()
    gtop, ground = v.top("ground"), v.cat("ground")
    cov = np.zeros_like(ground)                                      # earth or rock overhead (a tree's crown is not)
    cov[:-1] = np.logical_or.accumulate(ground[::-1], axis=0)[::-1][1:]
    xi0, zi0 = x - v.x0, z - v.z0
    col = st[:, zi0, xi0]
    fy = floor + 1 - v.ylo
    ys = [y for y in range(max(1, fy - 10), fy + 1) if col[y]]         # under a bridge: the bottom of the dip
    out = []
    if not ys and not ground[max(0, fy - 10):fy + 1, zi0, xi0].any():
        # no floor at all under the middle: the land drops away and the road goes over the dip on a bridge
        return [{"out": True, "say": "the road bridges a dip here, open beneath"}]
    for sgn, name in ((1, "+"), (-1, "-")):
        if not ys:
            out.append({"out": False, "say": f"{name} side: nowhere to stand in the middle"})
            continue
        y, climb, res = ys[-1], 0, None
        lx, lz = xi0, zi0
        fx, fz = float(xi0), float(zi0)                                 # follows the cut where the road curves away
        for s in range(1, R - 1):
            fx, fz = fx + px * sgn, fz + pz * sgn
            cx, cz = round(fx), round(fz)
            if not (0 <= cz < v.D and 0 <= cx < v.W):
                break
            nxt = []
            for k in (0, -1, 1):                                       # anywhere across the culvert's three-wide floor
                kx, kz = round(fx + dvec[0] * k), round(fz + dvec[1] * k)
                if not nxt and 0 <= kz < v.D and 0 <= kx < v.W:
                    nxt = [ny for ny in range(y + 1, y - 4, -1) if 1 <= ny < v.H - 1 and st[ny, kz, kx]
                           and (ny != y + 1 or v.passable[y + 2, lz, lx]) and (ny >= y or v.passable[ny + 2:y + 2, kz, kx].all())]
                    if nxt:
                        cx, cz = kx, kz
                        fx, fz = fx + dvec[0] * k, fz + dvec[1] * k
            lx, lz = cx, cz
            if not nxt:
                res = f"{name} side: blocked {s} blocks out (floor y {y + v.ylo - 1})"
                break
            climb += max(0, nxt[0] - y)
            y = nxt[0]
            if s >= 16 and not cov[y, cz, cx] and not any(
                    0 <= round(cz + dvec[1] * k) < v.D and 0 <= round(cx + dvec[0] * k) < v.W
                    and gtop[round(cz + dvec[1] * k), round(cx + dvec[0] * k)] > y + 1 for k in (-4, 4)):
                out.append({"out": True, "say": f"{name} side: out on the land {s} blocks out, the floor climbing {climb}"})
                break
        else:
            res = f"{name} side: still in the cut {R} blocks out"
        if len(out) < (1 if sgn > 0 else 2):
            out.append({"out": False, "say": res or f"{name} side: runs off the box"})
    return out


def check_spawn(w: World, spawn: dict, d: dict) -> dict:
    x, y, z = spawn["x"], spawn["y"], spawn["z"]
    v = Vol(w, x - 4, z - 4, x + 4, z + 4, max(-60, y - 20), y + 20)
    st = v.standable()
    # players appear within a few blocks of the world spawn: somewhere close by must take a paw
    near = st[:, max(0, z - v.z0 - 3):z - v.z0 + 4, max(0, x - v.x0 - 3):x - v.x0 + 4]
    ys = np.nonzero(near.any(axis=(1, 2)))[0] + v.ylo
    stand = [int(c) for c in ys if abs(c - y) <= 3]
    if spawn["mode"] == "CAMPAIGN":
        tx, tz = d.get("fourtrees", (x, z))
        target, limit = "Fourtrees", 150
    else:
        sky = next((l for l in d["landmarks"] if l["kind"] == "SKY_OAK"), None)
        tx, tz = (sky["x"], sky["z"]) if sky else (x, z)
        target, limit = "the Sky Oak", 160
    dist = math.hypot(x - tx, z - tz)
    ok = bool(stand) and dist <= limit and y > 50
    return {"check": f"{spawn['mode']} spawn", "ok": ok,
            "detail": f"spawn {x} {y} {z}, {dist:.0f} blocks from {target}; "
                      + ("a cat can stand there" if stand else "NO ground to stand on at the spawn height")}


def count_covered_open(v: Vol, cx, cz, r):
    cov = v.covered()
    openm = v.passable & v.known & cov
    zz, xx = np.indices((v.D, v.W))
    near = np.hypot(xx + v.x0 - cx, zz + v.z0 - cz) <= r
    # only count under real ground (ignore the shade under tree canopies): rock or earth above within 12
    ground = v.cat("ground")
    acc = np.zeros_like(ground)
    for k in range(1, 13):
        acc[:-k] |= ground[k:]
    return int((openm & acc & near[None]).sum())


def check_signature(w: World, a: dict) -> dict | None:
    k, x, z = a["kind"], a["x"], a["z"]
    if k in ("FOURTREES",):
        # the oaks stand about 30-35 out, round an open floor, so the box reaches 40
        v = Vol(w, x - 40, z - 40, x + 40, z + 40)
        run = np.zeros((v.D, v.W), dtype=np.int32)
        best = np.zeros((v.D, v.W), dtype=np.int32)
        for y in range(v.H):
            run = np.where(v.log[y], run + 1, 0)
            best = np.maximum(best, run)
        n = blobs(best >= 18)
        zz, xx = np.indices((v.D, v.W))
        dd = np.hypot(xx + v.x0 - x, zz + v.z0 - z)
        near = dd <= 9
        rock = int(v.cat("rock")[:, near].sum())
        roofed = int((v.leaves.any(axis=0) & (dd <= 12)).sum())        # leaves over the Great Rock hide it from above
        wet = int(((v.top("water") > v.top("ground")) & (dd <= 30)).sum())
        return {"check": "four great oaks and the Great Rock", "ok": n >= 4 and rock >= 40 and roofed == 0 and wet == 0,
                "detail": f"{n} trunks of 18+ logs within 40 blocks; {rock} rock blocks at the middle; {roofed} leafy columns "
                          f"over it; {wet} wet columns on the floor"}
    if k in ("SKY_OAK", "OWL_TREE", "GREAT_SYCAMORE"):
        v = Vol(w, x - 20, z - 20, x + 20, z + 20)
        run = longest_run(v.log)
        need = 14 if k == "SKY_OAK" else 10
        return {"check": "a great tree", "ok": run >= need, "detail": f"tallest trunk {run} (need {need})"}
    if k == "MOONPOOL":
        v = Vol(w, x - 40, z - 40, x + 40, z + 40)
        tw, tg = v.top("water"), v.top("ground")
        zz, xx = np.indices((v.D, v.W))
        dd = np.hypot(xx + v.x0 - x, zz + v.z0 - z)
        pool = (tw > tg) & (dd <= 16)
        rim = tg[(dd >= 26) & (dd <= 34) & (tg >= 0)]
        surf = int(np.median(tw[pool])) if pool.any() else -1
        depth = int(np.median(rim)) - surf if pool.any() and len(rim) else 0
        return {"check": "a pool in a hollow", "ok": pool.sum() >= 15 and depth >= 3,
                "detail": f"{int(pool.sum())} water columns in the middle, rim {depth} blocks above the pool"}
    if k == "STEPPING_STONES":
        v = Vol(w, x - 24, z - 24, x + 24, z + 24, 40, 160)
        tw, tg = v.top("water"), v.top("ground")
        wet = tw > tg
        dry = ~wet & (tg >= 0)
        zz, xx = np.indices((v.D, v.W))
        n = 0
        for zi, xi in np.argwhere(dry & (np.hypot(xx + v.x0 - x, zz + v.z0 - z) <= 22)):
            around = sum(1 for dz, dx in ((1, 0), (-1, 0), (0, 1), (0, -1))
                         if 0 <= zi + dz < v.D and 0 <= xi + dx < v.W and wet[zi + dz, xi + dx])
            if around >= 3:
                n += 1
        return {"check": "stones standing in water", "ok": n >= 3, "detail": f"{n} stones with water on 3+ sides"}
    if k in ("TREE_BRIDGE", "HALF_BRIDGE"):
        v = Vol(w, x - 40, z - 40, x + 40, z + 40, 40, 160)
        tw = v.top("water")
        cat = v.log if k == "TREE_BRIDGE" else v.cat("built") | v.log
        zz, xx = np.indices((v.D, v.W))
        over = 0
        for zi, xi in np.argwhere(tw >= 0):
            y = tw[zi, xi]
            if cat[y + 1:y + 6, zi, xi].any():
                over += 1
        need = 8 if k == "TREE_BRIDGE" else 4
        return {"check": "a bridge over the water", "ok": over >= need, "detail": f"{over} columns of {'log' if k == 'TREE_BRIDGE' else 'bridge'} over water"}
    if k == "BARLEY_FARM":
        v = Vol(w, x - 64, z - 64, x + 64, z + 64, 40, 180)
        nb, nf = int(v.cat("built").sum()), int(v.tall.sum())
        return {"check": "a barn and a paddock", "ok": nb >= 40 and nf >= 30, "detail": f"{nb} building blocks, {nf} fence blocks"}
    if k in ("TWOLEG_FARMS",):
        v = Vol(w, x - 80, z - 80, x + 80, z + 80, 40, 180)
        n = int(v.cat("farmland").sum())
        return {"check": "farmland", "ok": n >= 30, "detail": f"{n} farmland blocks"}
    if k == "HORSEPLACE":
        v = Vol(w, x - 64, z - 64, x + 64, z + 64, 40, 180)
        n = int(v.tall.sum())
        return {"check": "fences", "ok": n >= 30, "detail": f"{n} fence/wall blocks"}
    if k in ("ABANDONED_NEST", "KITTYPET_HOME"):
        v = Vol(w, x - 48, z - 48, x + 48, z + 48, 40, 180)
        n = int(v.cat("built").sum())
        return {"check": "a Twoleg building", "ok": n >= 40, "detail": f"{n} building blocks"}
    if k in ("MOTHERMOUTH", "MIDNIGHT_SETT", "TRIBE_CAVE"):
        v = Vol(w, x - 40, z - 40, x + 40, z + 40, 30, 200)
        n = count_covered_open(v, x, z, 30)
        need = {"MOTHERMOUTH": 60, "MIDNIGHT_SETT": 15, "TRIBE_CAVE": 60}[k]
        extra = ""
        ok = n >= need
        if k == "TRIBE_CAVE":
            run = longest_run(v.water)
            extra = f", tallest water column {run}"
            ok = ok and run >= 15
        return {"check": "a cave", "ok": ok, "detail": f"{n} open blocks under rock within 30 blocks{extra}"}
    if k in ("SNAKEROCKS", "SUNNINGROCKS"):
        v = Vol(w, x - 40, z - 40, x + 40, z + 40)
        t = tops(w, v.g)
        zz, xx = np.indices((v.D, v.W))
        rock = w.lookup(v.g, w.cat("rock"))[np.clip(t, 0, None), zz, xx] & (t >= 0)
        n = int(rock.sum())
        return {"check": "bare rock", "ok": n >= 60, "detail": f"{n} columns topped with rock"}
    if k == "HIGHWAY_STRETCH":
        v = Vol(w, x - 48, z - 48, x + 48, z + 48, 40, 190)
        n = int(road_tops(v).sum())
        trees, pebbles = road_litter(v)
        return {"check": "highway", "ok": n >= 150 and len(trees) <= 2 and not pebbles,
                "detail": f"{n} road columns" + litter_detail(trees, pebbles)}
    if k == "TUNNELS":
        v = Vol(w, x - 30, z - 30, x + 30, z + 30, 0, 120)
        n = count_covered_open(v, x, z, 25)
        return {"check": "the cavern", "ok": n >= 500, "detail": f"{n} open blocks under rock round the cavern"}
    return None


def blobs(mask2: np.ndarray) -> int:
    seen = np.zeros_like(mask2)
    n = 0
    for z, x in np.argwhere(mask2):
        if seen[z, x]:
            continue
        n += 1
        q = deque([(z, x)])
        seen[z, x] = True
        while q:
            a, b = q.popleft()
            for dz in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    c, e = a + dz, b + dx
                    if 0 <= c < mask2.shape[0] and 0 <= e < mask2.shape[1] and mask2[c, e] and not seen[c, e]:
                        seen[c, e] = True
                        q.append((c, e))
    return n


def scan(region: Path, box, ylo=40, yhi=160) -> dict:
    r = subprocess.run([sys.executable, str(TOOLS / "scan_region.py"), str(region), "--box", *map(str, box),
                        "--y", str(ylo), str(yhi)], capture_output=True, text=True, cwd=TOOLS)
    out = r.stdout
    def num(pat):
        m = re.search(pat, out)
        return int(m.group(1)) if m else -1
    return {"lava": num(r"lava open to air: (\d+)"), "over_air": num(r"still water over air: (\d+)"),
            "open_side": num(r"still water with an open side above sea level: (\d+)"),
            "seams": num(r"seams: (\d+)"), "text": out.strip()[-1500:]}

# ---------------------------------------------------------------------------------------------------- one seed


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def camp_plan_for(d: dict, a: dict):
    best = None
    for c in d["camps"]:
        dist = math.hypot(c["x"] - a["x"], c["z"] - a["z"])
        if dist < 60 and (best is None or dist < best[0]):
            best = (dist, c)
    return best[1] if best else None


def analyse(seed: int, d: dict, region: Path, camp_region: Path | None, campaign: dict | None, out: Path) -> dict:
    w = World(region)
    sd = out / str(seed)
    shutil.rmtree(sd / "tiles", ignore_errors=True)
    shutil.rmtree(sd / "slices", ignore_errors=True)
    (sd / "tiles").mkdir(parents=True, exist_ok=True)
    (sd / "slices").mkdir(parents=True, exist_ok=True)
    areas = boxes(d)
    results = []
    slices = []
    for a in areas:
        t0 = time.monotonic()
        r = {"name": a["name"], "group": a["group"], "kind": a["kind"], "x": a["x"], "z": a["z"], "checks": []}
        try:
            if a["kind"] in CAMP_KINDS:
                plan = camp_plan_for(d, a)
                c = check_camp(w, a, plan)
                r["checks"].append(c)
                r["plan"] = plan
                if a["kind"] in ("STONE_HOLLOW", "THUNDERCLAN_RAVINE") and plan:
                    R = c["radius"]
                    v = Vol(w, a["x"] - int(R) - 16, a["z"] - int(R) - 16, a["x"] + int(R) + 16, a["z"] + int(R) + 16)
                    tg = v.top("ground")
                    zz, xx = np.indices((v.D, v.W))
                    dd = np.hypot(xx + v.x0 - a["x"], zz + v.z0 - a["z"])
                    floor = np.median(tg[(dd <= R * 0.4) & (tg >= 0)])
                    # the rim from 5 to 12 blocks past the cliff foot, as before: R is now the logged barrier's outer
                    # edge (the quarry's cliff + 2.5; the ravine's hedge, 3 inside its cliff) rather than about the cliff
                    r0 = 5 if "barrier" not in plan else (2.5 if a["kind"] == "STONE_HOLLOW" else 8)
                    rim = np.median(tg[(dd >= R + r0) & (dd <= R + r0 + 7) & (tg >= 0)])
                    need = 15 if a["kind"] == "STONE_HOLLOW" else 8
                    r["checks"].append({"check": "deep quarry" if a["kind"] == "STONE_HOLLOW" else "down in a ravine", "ok": bool(rim - floor >= need),
                                        "detail": f"walls {rim - floor:.0f} blocks high (floor y {floor + YLO:.0f})"})
            elif a["kind"] == "ISLAND_TREE":
                r["checks"].append(check_island(w, a))
            elif a["kind"] == "TUNNEL_NETWORK":
                r["checks"].append(check_tunnels(w, a, d))
            elif a["kind"] == "WATERFALL":
                r["checks"].append(check_fall(w, a))
            elif a["kind"] == "CULVERT":
                r["checks"].append(check_culvert(w, a))
            elif a["kind"] == "ROAD":
                c = check_road(w, a)
                if c["ok"] is not None:
                    r["checks"].append(c)
            sig = check_signature(w, a)
            if sig:
                r["checks"].append(sig)
        except Exception as e:                    # noqa: BLE001 - a broken check is a finding, not a crash
            r["checks"].append({"check": "inspection", "ok": False, "detail": f"check crashed: {e!r}"})
        if a["kind"] != "ROAD":
            ylo = 0 if a["kind"] == "TUNNEL_NETWORK" else 40
            r["scan"] = scan(region, a["box"], ylo, 180 if a["group"] in ("forest", "journey") else 160)
        x0, z0, x1, z1 = a["box"]
        tile = render_top(w, x0, z0, x1, z1)
        draw_overlay(tile, a, r)
        tp = sd / "tiles" / f"{slug(a['name'])}.png"
        tile.save(tp)
        r["tile"] = str(tp.relative_to(out))
        for name, im in slices_for(w, a, r, d):
            p = sd / "slices" / f"{slug(a['name'])}-{slug(name)}.png"
            im.save(p)
            slices.append({"area": a["name"], "name": name, "path": str(p.relative_to(out))})
        r["seconds"] = round(time.monotonic() - t0, 1)
        results.append(r)
        mark = ("PASS" if all(c["ok"] for c in r["checks"]) else "FAIL") if r["checks"] else "LOOK"
        print(f"  {mark} {a['name']} ({r['seconds']}s) " + "; ".join(c["detail"] for c in r["checks"]), flush=True)
    spawn_checks = []
    if d.get("spawn"):
        spawn_checks.append(check_spawn(w, d["spawn"], d))
    if campaign and campaign.get("spawn") and camp_region:
        spawn_checks.append(check_spawn(World(camp_region), campaign["spawn"], campaign))
    for c in spawn_checks:
        print(f"  {'PASS' if c['ok'] else 'FAIL'} {c['check']}: {c['detail']}", flush=True)
    sheets = contact_sheets(results, sd, out)
    return {"seed": seed, "results": results, "slices": slices, "spawn": spawn_checks, "sheets": sheets}


def draw_overlay(tile: Image.Image, a: dict, r: dict):
    d = ImageDraw.Draw(tile)
    x0, z0 = a["box"][0], a["box"][1]
    plan = r.get("plan")
    if plan:
        cx, cz = plan["x"] - x0, plan["z"] - z0
        R = next((c["radius"] for c in r["checks"] if "radius" in c), 40)
        d.ellipse([cx - R, cz - R, cx + R, cz + R], outline=(255, 230, 0))
        g = math.radians(plan["gate"])
        d.line([(cx + math.cos(g) * (R - 6), cz + math.sin(g) * (R - 6)), (cx + math.cos(g) * (R + 14), cz + math.sin(g) * (R + 14))],
               fill=(255, 0, 0), width=2)
        for c in r["checks"]:
            for ex, ez in c.get("exits", []):
                d.point((ex - x0, ez - z0), fill=(255, 0, 255))
            for wdeg in c.get("ways", []):
                t = math.radians(wdeg)
                d.ellipse([cx + math.cos(t) * (R + 10) - 3, cz + math.sin(t) * (R + 10) - 3,
                           cx + math.cos(t) * (R + 10) + 3, cz + math.sin(t) * (R + 10) + 3], fill=(0, 255, 255))


def slices_for(w: World, a: dict, r: dict, d: dict):
    k, x, z = a["kind"], a["x"], a["z"]
    out = []
    try:
        if k in CAMP_KINDS and r.get("plan"):
            p = r["plan"]
            R = next((c["radius"] for c in r["checks"] if "radius" in c), 40)
            g = math.radians(p["gate"])
            ux, uz = math.cos(g), math.sin(g)
            ext = R + 22
            v = Vol(w, p["x"] - 2, p["z"] - 2, p["x"] + 2, p["z"] + 2)
            tg = v.top("ground")
            gy = int(np.median(tg[tg >= 0])) + YLO if (tg >= 0).any() else 70
            out.append(("through the way in", render_slice(w, round(p["x"] - ux * ext), round(p["z"] - uz * ext),
                                                          round(p["x"] + ux * ext), round(p["z"] + uz * ext),
                                                          gy - 30, gy + 30, 3, [(0.5, "centre"), (0.5 + R / (2 * ext), "wall")])))
            # and straight across, at right angles to the way in: the barrier with no gap
            out.append(("across", render_slice(w, round(p["x"] + uz * ext), round(p["z"] - ux * ext),
                                              round(p["x"] - uz * ext), round(p["z"] + ux * ext), gy - 30, gy + 30, 3,
                                              [(0.5, "centre")])))
        elif k == "WATERFALL":
            v = Vol(w, x - 16, z - 16, x + 16, z + 16, a["bottom"] - 10, a["top"] + 10)
            dv = stream_dir(v, x, z)
            if dv is not None:
                ux, uz = float(dv[0]), float(dv[1])
                out.append(("along the stream", render_slice(w, round(x - ux * 40), round(z - uz * 40), round(x + ux * 40),
                                                            round(z + uz * 40), a["bottom"] - 18, a["top"] + 18, 3, [(0.5, "fall")])))
        elif k == "CULVERT":
            c = next((c for c in r["checks"] if "across" in c), None)
            if c:
                px, pz = c["across"]
                f = a["floor"]
                out.append(("across the road", render_slice(w, round(x - px * 24), round(z - pz * 24), round(x + px * 24),
                                                           round(z + pz * 24), f - 10, f + 22, 4, [(0.5, "culvert")])))
        elif k == "ROAD":
            c = next((c for c in r["checks"] if c["check"] == "road on the land"), None)
            if c and " 0 on bridges" not in c["detail"]:
                v = Vol(w, x - 28, z - 28, x + 28, z + 28, 40, 190)
                dv = road_dir(v, x, z, 26)
                if dv is not None:
                    ux, uz = float(dv[0]), float(dv[1])
                    t = road_tops(v)
                    ys = tops(w, v.g)
                    ry = int(np.median(ys[t])) + 40 if t.any() else 80
                    out.append(("along the bridge", render_slice(w, round(x - ux * 30), round(z - uz * 30), round(x + ux * 30),
                                                                round(z + uz * 30), ry - 25, ry + 10, 3)))
        elif k == "TUNNEL_NETWORK":
            cav = next(l for l in d["landmarks"] if l["kind"] == "TUNNELS")
            for i, (ex, ey, ez) in enumerate(d["tunnels"]):
                dx, dz = cav["x"] - ex, cav["z"] - ez
                L = max(1.0, math.hypot(dx, dz))
                n = min(70, L)
                out.append((f"mouth {i + 1} toward the cavern", render_slice(w, ex - round(dx / L * 6), ez - round(dz / L * 6),
                                                                            round(ex + dx / L * n), round(ez + dz / L * n),
                                                                            ey - 40, ey + 14, 3, [(6 / (n + 6), "mouth")])))
        elif k in ("ISLAND_TREE",):
            tb = next((l for l in d["landmarks"] if l["kind"] == "TREE_BRIDGE"), None)
            if tb:
                dx, dz = tb["x"] - x, tb["z"] - z
                L = max(1.0, math.hypot(dx, dz))
                out.append(("island to the tree-bridge", render_slice(w, x, z, round(x + dx / L * (L + 40)), round(z + dz / L * (L + 40)),
                                                                     50, 110, 3)))
        elif k in ("MOONPOOL", "TRIBE_CAVE", "MOTHERMOUTH", "FOURTREES", "MIDNIGHT_SETT"):
            v = Vol(w, x - 2, z - 2, x + 2, z + 2)
            tg = v.top("ground")
            gy = int(np.median(tg[tg >= 0])) + YLO if (tg >= 0).any() else 80
            out.append(("east-west", render_slice(w, x - 60, z, x + 60, z, gy - 40, gy + 40, 3, [(0.5, "mark")])))
            out.append(("north-south", render_slice(w, x, z - 60, x, z + 60, gy - 40, gy + 40, 3, [(0.5, "mark")])))
    except Exception as e:                        # noqa: BLE001
        print(f"    slice for {a['name']} failed: {e!r}", flush=True)
    return out


def font(size=14):
    for f in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    return ImageFont.load_default()


def contact_sheets(results, sd: Path, out: Path) -> list[str]:
    paths = []
    groups = {}
    for r in results:
        if r["kind"] == "ROAD":
            continue
        groups.setdefault(r["group"], []).append(r)
    cell, cols = 300, 4
    f = font(13)
    for g, rs in groups.items():
        rows = (len(rs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cell, rows * (cell + 34)), (255, 255, 255))
        dr = ImageDraw.Draw(sheet)
        for i, r in enumerate(rs):
            im = Image.open(out / r["tile"])
            im.thumbnail((cell - 6, cell - 6), Image.NEAREST if im.width < cell else Image.LANCZOS)
            if im.width < cell - 6:
                k = (cell - 6) // max(im.width, im.height)
                if k > 1:
                    im = im.resize((im.width * k, im.height * k), Image.NEAREST)
            cx, cy = (i % cols) * cell, (i // cols) * (cell + 34)
            sheet.paste(im, (cx + 3, cy + 30))
            ok = all(c["ok"] for c in r["checks"]) if r["checks"] else None
            colr = (0, 140, 0) if ok else ((190, 0, 0) if ok is False else (90, 90, 90))
            dr.text((cx + 4, cy + 2), r["name"], fill=(0, 0, 0), font=f)
            dr.text((cx + 4, cy + 16), "PASS" if ok else ("FAIL" if ok is False else "look"), fill=colr, font=f)
        p = sd / f"sheet-{g}.png"
        sheet.save(p)
        paths.append(str(p.relative_to(out)))
    return paths

# ---------------------------------------------------------------------------------------------------- report


def write_report(out: Path, runs: list[dict], meta: dict) -> Path:
    e = html.escape
    parts = [f"""<!doctype html><html><head><meta charset="utf-8"><title>World Inspection</title>
<style>body{{font:14px system-ui,sans-serif;margin:16px;background:#f6f4ee;color:#222}}
img{{max-width:100%;image-rendering:pixelated;border:1px solid #bbb}} .ok{{color:#070}} .bad{{color:#b00;font-weight:600}}
.look{{color:#666}} table{{border-collapse:collapse}} td,th{{border:1px solid #ccc;padding:3px 6px;vertical-align:top}}
details{{margin:6px 0}} .sl img{{max-width:640px}}</style></head><body>
<h1>The Great Journey: world inspection</h1><p>{e(meta['when'])}; Core {e(meta['core'])}; seeds {e(', '.join(str(r['seed']) for r in runs))}</p>"""]
    for run in runs:
        rs = run["results"]
        checks = [c for r in rs for c in r["checks"]] + run["spawn"]
        bad = [c for c in checks if c["ok"] is False]
        faults = [(r["name"], r["scan"]) for r in rs if r.get("scan") and (r["scan"]["lava"] > 0 or r["scan"]["over_air"] > 0
                                                                           or r["scan"]["open_side"] > 0 or r["scan"]["seams"] > 0)]
        verdict = "PASS" if not bad else "FAIL"
        parts.append(f"<h2>Seed {run['seed']}: <span class='{'ok' if not bad else 'bad'}'>{verdict}</span> "
                     f"({len(checks) - len(bad)}/{len(checks)} checks, {len(faults)} areas with scanner findings)</h2>")
        parts.append("<h3>Spawn</h3><ul>" + "".join(f"<li class='{'ok' if c['ok'] else 'bad'}'>{e(c['check'])}: {e(c['detail'])}</li>"
                                                   for c in run["spawn"]) + "</ul>")
        for s in run["sheets"]:
            parts.append(f"<h3>{e(Path(s).stem)}</h3><img src='{e(s)}'>")
        parts.append("<h3>Checks</h3><table><tr><th>area</th><th>check</th><th>result</th><th>scanner (lava / water over air / open-sided water / seams)</th></tr>")
        for r in rs:
            sc = r.get("scan")
            sct = f"{sc['lava']} / {sc['over_air']} / {sc['open_side']} / {sc['seams']}" if sc else ""
            for i, c in enumerate(r["checks"] or [{"check": "-", "ok": None, "detail": "inspect visually"}]):
                cls = "ok" if c["ok"] else ("bad" if c["ok"] is False else "look")
                parts.append(f"<tr><td>{e(r['name']) if i == 0 else ''}</td><td>{e(c['check'])}</td>"
                             f"<td class='{cls}'>{e(c['detail'])}</td><td>{e(sct) if i == 0 else ''}</td></tr>")
        parts.append("</table>")
        if faults:
            parts.append("<h3>Scanner findings</h3>")
            for name, sc in faults:
                parts.append(f"<details><summary>{e(name)}</summary><pre>{e(sc['text'])}</pre></details>")
        parts.append("<h3>Side slices</h3><div class='sl'>")
        for s in run["slices"]:
            parts.append(f"<p><b>{e(s['area'])}</b>: {e(s['name'])}<br><img src='{e(s['path'])}'></p>")
        parts.append("</div>")
    parts.append("</body></html>")
    p = out / "report.html"
    p.write_text("\n".join(parts), encoding="utf-8")
    return p


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seeds", type=int, nargs="*", default=[4242, 777001])
    ap.add_argument("--no-random", action="store_true", help="do not add a random seed")
    ap.add_argument("--run-id", default=time.strftime("%Y%m%d-%H%M"))
    ap.add_argument("--reuse", action="store_true", help="analyse the worlds already generated, no servers")
    ap.add_argument("--no-campaign", action="store_true", help="skip the Campaign spawn boot")
    ap.add_argument("--keep-world", action="store_true", help="carry on generating the worlds already there")
    ap.add_argument("--only", default="", help="analyse only areas whose name contains this")
    ap.add_argument("--camps-only", action="store_true",
                    help="generate and check only the Clan camps (no roads, falls, culverts or Campaign boot)")
    ap.add_argument("--stage", default="",
                    help="server folder under build/ (default server-inspect), so two inspections never share one")
    ap.add_argument("--roads-only", action="store_true",
                    help="generate and check only the roads, their culverts and the highway stretches (no Campaign boot)")
    ap.add_argument("--port", type=int, default=0, help="a fixed server port (default: any free one)")
    ap.add_argument("--waters-only", action="store_true",
                    help="generate and check only the streams' falls, stepping stones, bridges, island, tunnels, Snakerocks "
                         "and the ravine camp (no Campaign boot)")
    ap.add_argument("--kinds", nargs="+", default=[], metavar="KIND",
                    help="generate and check only these landmark kinds, e.g. FOURTREES LAKE_RIDGE (no Campaign boot)")
    a = ap.parse_args()
    global STAGE, PORT
    if a.stage:
        STAGE = ROOT / "build" / a.stage
    PORT = a.port or None
    kinds = {k.upper() for k in a.kinds}
    if a.camps_only or a.roads_only or a.waters_only or kinds:
        a.no_campaign = True
    seeds = list(a.seeds)
    if not a.no_random and not a.reuse:
        seeds.append(random.randint(-2_000_000_000, 2_000_000_000))
    out = ROOT / "build/inspect" / a.run_id
    out.mkdir(parents=True, exist_ok=True)
    print(f"seeds: {seeds}; output {out}", flush=True)
    if not a.reuse and core_is_stale():
        rebuild_core()
    runs = []
    for seed in seeds:
        free_level, camp_level = f"inspect-{seed}-free", f"inspect-{seed}-campaign"
        meta_p = out / f"{seed}-log.json"
        if a.reuse and meta_p.exists():
            saved = json.loads(meta_p.read_text(encoding="utf-8"))
            d, cd = saved["free"], saved.get("campaign")
        else:
            def plan(lines):
                areas = boxes(parse(lines))
                if a.camps_only:
                    areas = [x for x in areas if x["kind"] in CAMP_KINDS]
                if a.roads_only:
                    areas = [x for x in areas if x["kind"] in ROAD_KINDS]
                if a.waters_only:
                    areas = [x for x in areas if x["kind"] in WATER_KINDS]
                if kinds:
                    areas = [x for x in areas if x["kind"] in kinds]
                need = chunks_of(areas)
                print(f"[{free_level}] {len(areas)} areas, {len(need)} chunks to generate", flush=True)
                return need
            lines = boot(seed, "free", free_level, CORE, out, plan, fresh=not a.keep_world)
            d = parse(lines)
            cd = None
            if not a.no_campaign:
                cd = parse(boot(seed, "campaign", camp_level, CORE, out, None))
            meta_p.write_text(json.dumps({"free": d, "campaign": cd}, default=list), encoding="utf-8")
        if a.only:
            d = dict(d)
            keep = a.only.lower()
            d_landmarks = [l for l in d["landmarks"] if keep in f"{l['map']} {l['kind']}".lower()]
            d = d | {"landmarks": d_landmarks if d_landmarks else d["landmarks"],
                     "falls": [f for f in d["falls"] if keep in "waterfall"], "culverts": [c for c in d["culverts"] if keep in "culvert"],
                     "roads": [r for r in d["roads"] if keep in r["kind"].lower()]}
        if a.camps_only:
            d = d | {"landmarks": [l for l in d["landmarks"] if l["kind"] in CAMP_KINDS], "falls": [], "culverts": [],
                     "roads": [], "tunnels": [], "spawn": None}
        if a.roads_only:
            d = d | {"landmarks": [l for l in d["landmarks"] if l["kind"] in ROAD_KINDS], "falls": [], "tunnels": [],
                     "spawn": None}
        if a.waters_only:
            d = d | {"landmarks": [l for l in d["landmarks"] if l["kind"] in WATER_KINDS], "culverts": [], "roads": [],
                     "spawn": None}
        if kinds:
            d = d | {"landmarks": [l for l in d["landmarks"] if l["kind"] in kinds], "falls": [], "culverts": [],
                     "roads": [], "tunnels": [], "spawn": None}
        print(f"analysing seed {seed}", flush=True)
        region = STAGE / free_level / "region"
        camp_region = STAGE / camp_level / "region" if cd else None
        runs.append(analyse(seed, d, region, camp_region, cd, out))
    rep = write_report(out, runs, {"when": time.strftime("%Y-%m-%d %H:%M"), "core": CORE.name})
    print(f"report: {rep}", flush=True)
    for run in runs:
        bad = [c for r in run["results"] for c in r["checks"] if c["ok"] is False] + [c for c in run["spawn"] if not c["ok"]]
        print(f"seed {run['seed']}: {'PASS' if not bad else 'FAIL (' + str(len(bad)) + ' checks)'}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
