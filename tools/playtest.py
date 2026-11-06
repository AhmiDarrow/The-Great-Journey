#!/usr/bin/env python3
"""Playtest The Great Journey in real full-pack clients, in the background, and write a report.

    python tools/playtest.py --suite smoke                    # one short client: the focus check
    python tools/playtest.py --suite free-main                # singleplayer Free world: S1 S2 S4 S5 S6 S8 S7
    python tools/playtest.py --suite free-starts              # kittypet, loner, WindClan, RiverClan, ShadowClan
    python tools/playtest.py --suite campaign                 # S3: Fourtrees, the four acts, campaign starts
    python tools/playtest.py --suite mp                       # S9: a dedicated server and two clients
    python tools/playtest.py --report build/playtest/<run>    # rebuild a run's report.html only

Each client is a production NeoForge 21.1.249 install (portablemc, offline name QA1/QA2) with every pack mod, the
freshly built Core and the test-only greatjourney_qa mod, which plays the suite by itself. Clients run hidden, never
focused, never grab the mouse, muted, at BelowNormal priority; the harness watches the foreground window the whole
time and fails the run if one of its own processes ever takes it. Only processes this script started are stopped.
Results: build/playtest/<run-id>/report.html (PASS/FAIL table, screenshots by scenario, exceptions, crash reports).
"""
from __future__ import annotations

import argparse
import ctypes
import html
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from ctypes import wintypes
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "build/playtest"
VENV_PY = BASE / "venv/Scripts/python.exe"
NEO = "21.1.249"
JAVA = Path(os.environ.get("QA_JAVA", r"C:\Users\Administrator\scoop\apps\temurin21-jdk\current\bin\java.exe"))
BELOW_NORMAL = 0x00004000
NO_WINDOW = 0x08000000
# no console windows (a new console is a black box on the owner's screen)
QUIET = (BELOW_NORMAL | NO_WINDOW) if os.name == "nt" else 0
OPTIONS = """onboardAccessibility:false
pauseOnLostFocus:false
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
renderDistance:12
simulationDistance:8
guiScale:2
maxFps:30
enableVsync:false
fullscreen:false
soundCategory_master:0.0
narrator:0
autoJump:false
"""
SUITE_MINUTES = {"smoke": 12, "free-main": 75, "free-starts": 60, "campaign": 60, "tour": 30, "mp": 50}


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


# ---- processes we own -----------------------------------------------------------------------------------------

OURS: list[subprocess.Popen] = []


def our_pids() -> set[int]:
    return {p.pid for p in OURS if p.poll() is None}


def stop_ours() -> None:
    for p in OURS:
        if p.poll() is None:
            try:
                p.kill()
            except OSError:
                pass


# ---- focus watch (read-only Win32 calls) ------------------------------------------------------------------------

user32 = ctypes.windll.user32 if os.name == "nt" else None


def foreground() -> tuple[int, int, str]:
    if not user32:
        return 0, 0, ""
    h = user32.GetForegroundWindow()
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(h, ctypes.byref(pid))
    buf = ctypes.create_unicode_buffer(256)
    user32.GetWindowTextW(h, buf, 256)
    return h, pid.value, buf.value


def visible_windows(pids: set[int]) -> list[tuple[int, str, tuple[int, int, int, int]]]:
    """Visible top-level windows of our processes, with their screen rectangles (read-only Win32 calls)."""
    out = []
    if not user32 or not pids:
        return out
    proto = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)

    def cb(h, _):
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(h, ctypes.byref(pid))
        if pid.value in pids and user32.IsWindowVisible(h):
            r = wintypes.RECT()
            user32.GetWindowRect(h, ctypes.byref(r))
            buf = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(h, buf, 256)
            out.append((pid.value, buf.value, (r.left, r.top, r.right, r.bottom)))
        return True
    user32.EnumWindows(proto(cb), 0)
    return out


DESKTOP = (-10, -1090, 1930, 1090)      # both of the owner's monitors (x 0..1920; y -1080..1080), with a margin


def on_desktop(rect: tuple[int, int, int, int]) -> bool:
    l, t, r, b = rect
    return r > DESKTOP[0] and l < DESKTOP[2] and b > DESKTOP[1] and t < DESKTOP[3] and r - l > 0 and b - t > 0


class FocusWatch(threading.Thread):
    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.stolen: list[str] = []
        self.shown: list[str] = []
        self.samples = 0
        self.first = foreground()
        self.running = True

    def run(self) -> None:
        n = 0
        while self.running:
            h, pid, title = foreground()
            self.samples += 1
            n += 1
            wins = visible_windows(our_pids())
            if wins and (n % 30 == 1 or any(on_desktop(w[2]) for w in wins)):
                log(f"our visible windows: {wins}")
            for wpid, wtitle, rect in wins:
                if on_desktop(rect):
                    self.shown.append(f"{time.strftime('%H:%M:%S')} pid {wpid} '{wtitle}' at {rect}")
                    log(f"FAIL window on the owner's screen: pid {wpid} '{wtitle}' {rect}; stopping it")
                    for p in OURS:
                        if p.pid == wpid and p.poll() is None:
                            p.kill()
            if pid in our_pids():
                self.stolen.append(f"{time.strftime('%H:%M:%S')} pid {pid} '{title}'")
                log(f"FOCUS STOLEN by our pid {pid} '{title}'")
            time.sleep(1.0)


# ---- install and stage ------------------------------------------------------------------------------------------

def ensure_venv() -> None:
    if not VENV_PY.exists():
        subprocess.run([sys.executable, "-m", "venv", str(BASE / "venv")], check=True, creationflags=QUIET)
        subprocess.run([str(VENV_PY), "-m", "pip", "install", "-q", "portablemc==4.4.1"], check=True, creationflags=QUIET)


def build_jars(skip: bool) -> tuple[Path, Path]:
    if not skip:
        log("building mods (gradle_locked buildAll) and the Core")
        subprocess.run([sys.executable, str(ROOT / "tools/gradle_locked.py"), "buildAll", "-q"], check=True, creationflags=QUIET)
        subprocess.run([sys.executable, str(ROOT / "tools/build_core_jar.py")], check=True, creationflags=QUIET)
    ver = re.search(r"(?m)^mod_version=(\S+)", (ROOT / "mods/gradle.properties").read_text(encoding="utf-8")).group(1)
    core = ROOT / "dist" / f"greatjourney_core-{ver}.jar"
    qa = ROOT / "mods/qa/build/libs" / f"greatjourney_qa-{ver}.jar"
    if not skip and not qa.exists():
        subprocess.run([sys.executable, str(ROOT / "tools/gradle_locked.py"), ":qa:build", "-q"], check=True, creationflags=QUIET)
    for j in (core, qa):
        if not j.exists():
            raise SystemExit(f"missing {j}")
    return core, qa


def stage_client(n: int, core: Path, qa: Path) -> Path:
    work = BASE / f"client-{n}"
    for sub in ("mods", "config", "saves", "screenshots", "logs", "crash-reports", "defaultconfigs"):
        shutil.rmtree(work / sub, ignore_errors=True)
    (work / "mods").mkdir(parents=True)
    for jar in sorted((ROOT / "pack/mods").glob("*.jar")):
        shutil.copy2(jar, work / "mods" / jar.name)
    shutil.copy2(core, work / "mods" / core.name)
    shutil.copy2(qa, work / "mods" / qa.name)
    if (ROOT / "pack/overrides/config").is_dir():
        shutil.copytree(ROOT / "pack/overrides/config", work / "config")
    (work / "config").mkdir(exist_ok=True)
    # the early loading window is created before any mod can hint it: turn it off, so the game window is made by
    # Minecraft with the QA mod's hidden/unfocused hints
    (work / "config/fml.toml").write_text("earlyWindowControl = false\n", encoding="utf-8")
    (work / "options.txt").write_text(OPTIONS, encoding="utf-8")
    return work


def client_command(work: Path, name: str, props: dict[str, str]) -> list[str]:
    """Resolve the launch command with portablemc (in its venv) as JSON."""
    code = r'''
import json, sys
from pathlib import Path
from portablemc.standard import Context, StandardRunner
from portablemc.forge import _NeoForgeVersion
main, work, name, java = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4])
v = _NeoForgeVersion(sys.argv[5], context=Context(main, work))
v.jvm_path = java
v.set_auth_offline(name, None)
v.resolution = (1280, 720)
env = v.install()
class Capture(StandardRunner):
    def process_create(self, args, work_dir):
        print("ARGS" + json.dumps(args))
        return None
Capture().run(env)
'''
    out = subprocess.run([str(VENV_PY), "-c", code, str(BASE / "mc"), str(work), name, str(JAVA), NEO],
                         capture_output=True, text=True, check=True, creationflags=QUIET).stdout
    args = json.loads(out[out.index("ARGS") + 4:].splitlines()[0])
    # portablemc deletes its natives dir when its runner returns: keep our own copy
    natives = work / "natives"
    nat_arg = next((a for a in args if a.startswith("-Djava.library.path=")), None)
    if nat_arg:
        src = Path(nat_arg.split("=", 1)[1])
        if not natives.exists() or not any(natives.iterdir()):
            extract_natives(natives)
        args = [a.replace(str(src), str(natives)) for a in args]
    jvm_extra = ["-Xmx5G", "-Xms1G"] + [f"-D{k}={v}" for k, v in props.items()]
    i = args.index("cpw.mods.bootstraplauncher.BootstrapLauncher") if "cpw.mods.bootstraplauncher.BootstrapLauncher" in args else 1
    return args[:i] + jvm_extra + args[i:]


def extract_natives(dst: Path) -> None:
    import zipfile
    dst.mkdir(parents=True, exist_ok=True)
    for jar in (BASE / "mc/libraries").rglob("*natives-windows*.jar"):
        if "arm64" in jar.name or "x86" in jar.name.replace("x86_64", ""):
            continue
        with zipfile.ZipFile(jar) as z:
            for info in z.infolist():
                if info.filename.endswith(".dll"):
                    (dst / Path(info.filename).name).write_bytes(z.read(info))


def launch(cmd: list[str], work: Path, logfile: Path) -> subprocess.Popen:
    fh = open(logfile, "w", encoding="utf-8", errors="replace")
    p = subprocess.Popen(cmd, cwd=work, stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                         creationflags=(BELOW_NORMAL | NO_WINDOW) if os.name == "nt" else 0)
    OURS.append(p)
    return p


# ---- dedicated server for the multiplayer suite ----------------------------------------------------------------

def start_server(core: Path, qa: Path, seed: str) -> tuple[subprocess.Popen, int, Path]:
    sys.path.insert(0, str(ROOT / "tools"))
    import full_pack_server as fps
    stage = BASE / "server"
    stage.mkdir(parents=True, exist_ok=True)
    args_txt = fps.install_neoforge(stage)
    fps.stage_files(stage, core, [qa], seed)
    fps.wipe_world(stage)
    props = (stage / "server.properties").read_text(encoding="utf-8")
    port = int(re.search(r"server-port=(\d+)", props).group(1))
    (stage / "server.properties").write_text(props.replace("max-players=2", "max-players=4") + "allow-flight=true\n", encoding="utf-8")
    ops = [{"uuid": offline_uuid(n), "name": n, "level": 4, "bypassesPlayerLimit": True} for n in ("QA1", "QA2")]
    (stage / "ops.json").write_text(json.dumps(ops, indent=2), encoding="utf-8")
    cmd = ["java" if not JAVA.exists() else str(JAVA), f"@{stage / 'user_jvm_args.txt'}", f"@{args_txt}", "nogui"]
    fh = open(stage / "qa-server.log", "w", encoding="utf-8", errors="replace")
    p = subprocess.Popen(cmd, cwd=stage, stdin=subprocess.PIPE, stdout=fh, stderr=subprocess.STDOUT, text=True,
                         creationflags=(BELOW_NORMAL | NO_WINDOW) if os.name == "nt" else 0)
    OURS.append(p)
    deadline = time.time() + 900
    while time.time() < deadline:
        txt = (stage / "qa-server.log").read_text(encoding="utf-8", errors="replace")
        if re.search(r"Done \([^)]+\)! For help", txt):
            return p, port, stage
        if p.poll() is not None:
            raise SystemExit("server exited before Done; see " + str(stage / "qa-server.log"))
        time.sleep(3)
    raise SystemExit("server never reached Done")


def offline_uuid(name: str) -> str:
    import hashlib
    import uuid
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


# ---- run --------------------------------------------------------------------------------------------------------

def run(suite: str, skip_build: bool, slot: int = 1) -> Path:
    ensure_venv()
    core, qa = build_jars(skip_build)
    run_dir = BASE / "runs" / (time.strftime("%Y%m%d-%H%M%S-") + suite)
    run_dir.mkdir(parents=True)
    watch = FocusWatch()
    log(f"foreground before: {watch.first}")
    watch.start()
    server = None
    clients: list[tuple[str, Path, subprocess.Popen]] = []
    started = time.time()
    try:
        if suite == "mp":
            server, port, stage = start_server(core, qa, "20261014")
            log(f"server up on {port}")
            for n, sub in ((1, "mp-a"), (2, "mp-b")):
                work = stage_client(n, core, qa)
                cmd = client_command(work, f"QA{n}", {"greatjourney.qa.suite": sub, "greatjourney.qa.background": "true",
                                                      "greatjourney.qa.server": f"127.0.0.1:{port}"})
                clients.append((f"QA{n}", work, launch(cmd, work, run_dir / f"client-{n}.out.log")))
                time.sleep(20)
        else:
            work = stage_client(slot, core, qa)
            cmd = client_command(work, f"QA{slot}", {"greatjourney.qa.suite": suite, "greatjourney.qa.background": "true"})
            clients.append((f"QA{slot}", work, launch(cmd, work, run_dir / f"client-{slot}.out.log")))
        limit = started + 60 * SUITE_MINUTES.get(suite, 60)
        last_note = 0
        while any(p.poll() is None for _, _, p in clients):
            if time.time() > limit:
                log("time limit reached: stopping our clients")
                break
            if time.time() - last_note > 60:
                last_note = time.time()
                for name, work, p in clients:
                    lines = qa_lines(work / "logs/latest.log")
                    log(f"{name}: {len(lines)} QA lines, last: {lines[-1][:140] if lines else '-'}")
            time.sleep(5)
    finally:
        for _, _, p in clients:
            if p.poll() is None:
                try:
                    p.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    p.kill()
        if server is not None:
            try:
                server.stdin.write("stop\n")
                server.stdin.flush()
                server.wait(timeout=180)
            except Exception:
                server.kill()
        stop_ours()
        watch.running = False
    log(f"foreground after: {foreground()}; samples {watch.samples}; stolen {len(watch.stolen)}")
    for name, work, _ in clients:
        collect(work, run_dir / name)
    if server is not None:
        shutil.copy2(BASE / "server/qa-server.log", run_dir / "server.log")
        for c in (BASE / "server/crash-reports").glob("*.txt") if (BASE / "server/crash-reports").exists() else []:
            shutil.copy2(c, run_dir / ("server-" + c.name))
    (run_dir / "focus.json").write_text(json.dumps({"before": watch.first[2], "samples": watch.samples, "stolen": watch.stolen,
                                                   "shown": watch.shown}, indent=2))
    report(run_dir)
    return run_dir


def collect(work: Path, dst: Path) -> None:
    dst.mkdir(parents=True, exist_ok=True)
    for f in ("logs/latest.log", "logs/debug.log"):
        if (work / f).exists():
            shutil.copy2(work / f, dst / Path(f).name)
    (dst / "shots").mkdir(exist_ok=True)
    for png in (work / "screenshots").glob("qa-*.png") if (work / "screenshots").exists() else []:
        shutil.copy2(png, dst / "shots" / png.name)
    for c in (work / "crash-reports").glob("*.txt") if (work / "crash-reports").exists() else []:
        shutil.copy2(c, dst / c.name)


QA_RE = re.compile(r"\[QA\] (PASS|FAIL|SHOT|NOTE|STEP|SCENARIO|BEGIN|SUITE|END)\s+(.*)")


def qa_lines(path: Path) -> list[str]:
    if not path.exists():
        return []
    return [m.group(0) for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if (m := QA_RE.search(line))]


ERR_RE = re.compile(r"(Exception|Error:|\bERROR\b)")
OURS_RE = re.compile(r"ninjacat|clanlore|clanlands|clanwild|greatjourney|snowteb|warriorcats")


def report(run_dir: Path) -> Path:
    rows: dict[tuple[str, str, str], str] = {}
    notes: list[str] = []
    errors: list[str] = []
    shots_by_scen: dict[str, list[Path]] = {}
    logs = list(run_dir.glob("*/latest.log")) + ([run_dir / "server.log"] if (run_dir / "server.log").exists() else [])
    for lg in logs:
        lines = lg.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(lines):
            m = QA_RE.search(line)
            if m:
                kind, rest = m.group(1), m.group(2)
                if kind in ("PASS", "FAIL"):
                    parts = rest.split(" ", 3)
                    if len(parts) >= 3:
                        who, scen, check = parts[0], parts[1], parts[2].rstrip(":")
                        detail = rest.split(": ", 1)[1] if ": " in rest else ""
                        key = (scen, who, check)
                        if kind == "FAIL" or key not in rows:
                            rows[key] = kind + ("  " + detail if detail else "")
                elif kind == "NOTE":
                    notes.append(rest)
                continue
            if ERR_RE.search(line) and OURS_RE.search(" ".join(lines[i:i + 6])):
                errors.append(f"{lg.parent.name}/{lg.name}:{i + 1}: {line.strip()[:300]}")
    for png in sorted(run_dir.glob("*/shots/*.png")):
        m = re.match(r"qa-[^-]+-(.+?)-\d{3}-", png.name)
        shots_by_scen.setdefault(m.group(1) if m else "other", []).append(png)
    focus = json.loads((run_dir / "focus.json").read_text()) if (run_dir / "focus.json").exists() else {}
    crashes = [p.name for p in run_dir.rglob("crash-*.txt")]
    passes = sum(1 for v in rows.values() if v.startswith("PASS"))
    fails = sum(1 for v in rows.values() if v.startswith("FAIL"))
    out = [f"<!doctype html><meta charset=utf-8><title>Playtest {html.escape(run_dir.name)}</title>",
           "<style>body{font:14px system-ui;margin:24px;background:#faf7f0;color:#222}table{border-collapse:collapse}"
           "td,th{border:1px solid #ccc;padding:3px 8px;vertical-align:top}.PASS{background:#e3f4e1}.FAIL{background:#fbe0de}"
           "img{width:420px;margin:4px;border:1px solid #999}h2{margin-top:28px}pre{white-space:pre-wrap;font-size:12px}</style>",
           f"<h1>Playtest {html.escape(run_dir.name)}</h1>",
           f"<p><b>{passes}</b> passed, <b>{fails}</b> failed. Focus: {focus.get('samples', 0)} foreground samples, "
           f"<b>{len(focus.get('stolen', []))}</b> taken by our processes, <b>{len(focus.get('shown', []))}</b> windows of ours seen on screen. Crash reports: {html.escape(', '.join(crashes)) or 'none'}.</p>",
           "<table><tr><th>scenario</th><th>player</th><th>check</th><th>result</th></tr>"]
    for (scen, who, check), v in sorted(rows.items()):
        cls = "PASS" if v.startswith("PASS") else "FAIL"
        out.append(f"<tr class={cls}><td>{html.escape(scen)}</td><td>{html.escape(who)}</td><td>{html.escape(check)}</td><td>{html.escape(v)}</td></tr>")
    out.append("</table>")
    out.append(f"<h2>Errors mentioning our mods ({len(errors)})</h2><pre>{html.escape(chr(10).join(errors[:300]))}</pre>")
    for scen, pngs in shots_by_scen.items():
        out.append(f"<h2>{html.escape(scen)}</h2>")
        for p in pngs:
            rel = p.relative_to(run_dir).as_posix()
            out.append(f"<a href='{rel}'><img src='{rel}' title='{html.escape(p.name)}'></a>")
    out.append(f"<h2>Notes</h2><pre>{html.escape(chr(10).join(notes[-400:]))}</pre>")
    path = run_dir / "report.html"
    path.write_text("\n".join(out), encoding="utf-8")
    sheets(run_dir, shots_by_scen)
    (run_dir / "results.json").write_text(json.dumps({f"{s}|{w}|{c}": v for (s, w, c), v in sorted(rows.items())}, indent=1))
    log(f"report: {path}  ({passes} pass, {fails} fail, {len(errors)} error lines)")
    return path


def sheets(run_dir: Path, shots: dict[str, list[Path]]) -> None:
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return
    for scen, pngs in shots.items():
        for page in range(0, len(pngs), 12):
            chunk = pngs[page:page + 12]
            w, h, cols = 640, 360, 3
            sheet = Image.new("RGB", (w * cols, (h + 18) * ((len(chunk) + cols - 1) // cols)), (20, 20, 20))
            d = ImageDraw.Draw(sheet)
            for i, p in enumerate(chunk):
                try:
                    im = Image.open(p).convert("RGB").resize((w, h))
                except Exception:
                    continue
                x, y = (i % cols) * w, (i // cols) * (h + 18)
                sheet.paste(im, (x, y + 18))
                d.text((x + 4, y + 3), p.name[:90], fill=(255, 230, 150))
            sheet.save(run_dir / f"sheet-{scen}-{page // 12 + 1}.png")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--suite", default="smoke")
    ap.add_argument("--skip-build", action="store_true", help="use the existing Core and qa jars")
    ap.add_argument("--client", type=int, default=1, help="singleplayer suites: which client folder (run two suites side by side)")
    ap.add_argument("--report", help="only rebuild the report of this run directory")
    a = ap.parse_args()
    if a.report:
        report(Path(a.report))
        return 0
    try:
        d = run(a.suite, a.skip_build, a.client)
    finally:
        stop_ours()
    focus = json.loads((d / "focus.json").read_text())
    return 2 if focus["stolen"] or focus.get("shown") else 0


if __name__ == "__main__":
    raise SystemExit(main())
