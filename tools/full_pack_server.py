#!/usr/bin/env python3
"""Boot the whole The Great Journey pack on a dedicated NeoForge server, on THIS PC.

    python tools/full_pack_server.py                          # pack/mods (minus client-only) + overrides/config
    python tools/full_pack_server.py --core-jar mods/build/greatjourney_core-0.1.0.jar
    python tools/full_pack_server.py --command "seed" --expect "Seed: \\["

Passes only if the server prints vanilla "Done (...)!", stays up --settle seconds (default 30), then stops cleanly
with no crash report and no failure line in latest.log. The server runs at BelowNormal priority with -Xmx6G so a
game on this PC keeps priority. Never point this at the hub PC.
"""
from __future__ import annotations

import argparse
import json
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NEO = "21.1.249"
INSTALLER_URL = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{NEO}/neoforge-{NEO}-installer.jar"
BELOW_NORMAL_PRIORITY_CLASS = 0x00004000

# Jars a dedicated server must not load. pack/mods.json "side" (CurseForge's Client/Server tags) catches most;
# these are client-only mods CurseForge tags as both sides.
CLIENT_ONLY_PREFIXES = (
    "fancymenu_", "melody_", "visualhealth-", "sound-physics-remastered-", "mcqoy-",
)
CLIENT_ONLY_CONFIG = {"config/fancymenu"}

FAIL_RES = [
    re.compile(r"Exception in server tick loop"),
    re.compile(r"Failed to start the minecraft server", re.I),
    re.compile(r"Preparing crash report"),
    re.compile(r"This crash report has been saved"),
    re.compile(r"Game crashed", re.I),
    re.compile(r"Missing or unsupported mandatory dependencies", re.I),
    re.compile(r"Mod loading has failed|ModLoadingException", re.I),
]
DONE_RE = re.compile(r"Done \([^)]+\)! For help")

SERVER_PROPERTIES = """# The Great Journey full-pack harness
motd=The Great Journey harness
gamemode=survival
difficulty=normal
max-players=2
view-distance=8
simulation-distance=6
online-mode=false
enable-command-block=false
spawn-protection=0
white-list=false
max-tick-time=-1
sync-chunk-writes=false
enforce-secure-profile=false
"""
USER_JVM_ARGS = "-Xmx6G\n-Xms1G\n"


def log(msg: str) -> None:
    print(msg, flush=True)


def client_only_names() -> set[str]:
    entries = json.loads((ROOT / "pack/mods.json").read_text(encoding="utf-8"))
    return {e["fileName"] for e in entries if e["classId"] == 6 and e["side"] == "client"}


def is_client_only(name: str, tagged: set[str]) -> bool:
    return name in tagged or name.startswith(CLIENT_ONLY_PREFIXES)


def install_neoforge(stage: Path) -> Path:
    args_txt = stage / "libraries/net/neoforged/neoforge" / NEO / "win_args.txt"
    if args_txt.is_file():
        return args_txt
    installer = stage / f"neoforge-{NEO}-installer.jar"
    log(f"Downloading NeoForge {NEO} installer")
    urllib.request.urlretrieve(INSTALLER_URL, installer)
    subprocess.run(["java", "-jar", str(installer), "--install-server", str(stage)], check=True, cwd=stage,
                   stdout=subprocess.DEVNULL, creationflags=0x08000000 if sys.platform == "win32" else 0)
    installer.unlink(missing_ok=True)
    (stage / f"neoforge-{NEO}-installer.jar.log").unlink(missing_ok=True)
    if not args_txt.is_file():
        raise SystemExit(f"Installer did not write {args_txt}")
    return args_txt


def free_port() -> int:
    """A port no other harness (or game) is using, so several stages can run side by side."""
    import socket
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def stage_files(stage: Path, core_jar: Path | None, extra_jars: list[Path], seed: str = "") -> list[str]:
    mods = stage / "mods"
    shutil.rmtree(mods, ignore_errors=True)
    mods.mkdir(parents=True)
    tagged = client_only_names()
    skipped = []
    for jar in sorted((ROOT / "pack/mods").glob("*.jar")):
        if is_client_only(jar.name, tagged):
            skipped.append(jar.name)
            continue
        shutil.copy2(jar, mods / jar.name)
    for jar in ([core_jar] if core_jar else []) + extra_jars:
        shutil.copy2(jar, mods / jar.name)
    src = ROOT / "pack/overrides/config"
    dst = stage / "config"
    shutil.rmtree(dst, ignore_errors=True)
    if src.is_dir():
        shutil.copytree(src, dst, ignore=lambda d, names: [
            n for n in names if f"{Path(d).relative_to(ROOT / 'pack/overrides').as_posix()}/{n}" in CLIENT_ONLY_CONFIG])
    gdp_src = ROOT / "pack/overrides/moonlight-global-datapacks"
    gdp_dst = stage / "moonlight-global-datapacks"
    shutil.rmtree(gdp_dst, ignore_errors=True)
    if gdp_src.is_dir():
        shutil.copytree(gdp_src, gdp_dst)
    (stage / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props = SERVER_PROPERTIES + (f"level-seed={seed}\n" if seed else "") + f"server-port={free_port()}\n"
    (stage / "server.properties").write_text(props, encoding="utf-8")
    (stage / "user_jvm_args.txt").write_text(USER_JVM_ARGS, encoding="utf-8")
    log(f"Staged {len(list(mods.glob('*.jar')))} jars; left out {len(skipped)} client-only")
    return skipped


def wipe_world(stage: Path) -> None:
    for name in ("world", "crash-reports", "logs"):
        shutil.rmtree(stage / name, ignore_errors=True)


def crash_hint(stage: Path) -> str:
    crashes = sorted((stage / "crash-reports").glob("crash-*.txt")) if (stage / "crash-reports").exists() else []
    return str(crashes[-1]) if crashes else "no crash report"


def run_server(stage: Path, args_txt: Path, timeout: int, settle: int, commands: list[str], expects: list[str],
               hold: int = 0) -> None:
    cmd = ["java", f"@{stage / 'user_jvm_args.txt'}", f"@{args_txt}", "nogui"]
    flags = BELOW_NORMAL_PRIORITY_CLASS | 0x08000000 if sys.platform == "win32" else 0   # no console window
    proc = subprocess.Popen(cmd, cwd=stage, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace", bufsize=1, creationflags=flags)
    assert proc.stdin and proc.stdout
    lines: queue.Queue[str | None] = queue.Queue()

    def pump() -> None:
        try:
            for raw in proc.stdout:
                lines.put(raw.rstrip("\n"))
        finally:
            lines.put(None)

    def send(text: str) -> None:
        try:
            proc.stdin.write(text + "\n")
            proc.stdin.flush()
        except OSError:
            pass

    threading.Thread(target=pump, daemon=True).start()
    started = time.monotonic()
    done_at = None
    try:
        while done_at is None or time.monotonic() - done_at < settle:
            if time.monotonic() - started > timeout:
                raise SystemExit(f"TIMEOUT after {timeout}s waiting for Done")
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line is None:
                proc.wait(timeout=30)
                raise SystemExit(f"FAIL: server exited {proc.returncode} ({crash_hint(stage)})")
            print(line, flush=True)
            for pat in FAIL_RES:
                if pat.search(line):
                    send("stop")
                    raise SystemExit(f"FAIL: {line.strip()} ({crash_hint(stage)})")
            if done_at is None and DONE_RE.search(line):
                done_at = time.monotonic()
                log(f"Reached Done after {done_at - started:.0f}s; staying up {settle}s")
        for c in commands:
            send(c)
        pending = [re.compile(p) for p in expects]
        deadline = time.monotonic() + 60
        while pending and time.monotonic() < deadline:
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line is None:
                break
            print(line, flush=True)
            pending = [p for p in pending if not p.search(line)]
        if pending:
            send("stop")
            raise SystemExit(f"FAIL: never saw {[p.pattern for p in pending]}")
        hold_until = time.monotonic() + hold      # e.g. let forceloaded chunks generate before stopping
        while time.monotonic() < hold_until:
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line is None:
                break
            print(line, flush=True)
        send("stop")
        proc.wait(timeout=180)
        if crash_hint(stage) != "no crash report":
            raise SystemExit(f"FAIL: crash report {crash_hint(stage)}")
        log(f"PASS full-pack dedicated server in {time.monotonic() - started:.0f}s")
    finally:
        if proc.poll() is None:
            send("stop")
            try:
                proc.wait(timeout=60)
            except subprocess.TimeoutExpired:
                proc.kill()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--stage", default=str(ROOT / "build/server-harness"))
    ap.add_argument("--core-jar", default="")
    ap.add_argument("--extra-jar", action="append", default=[], help="also load this jar (repeatable)")
    ap.add_argument("--timeout", type=int, default=1200)
    ap.add_argument("--settle", type=int, default=30)
    ap.add_argument("--keep-world", action="store_true")
    ap.add_argument("--command", action="append", default=[])
    ap.add_argument("--expect", action="append", default=[])
    ap.add_argument("--hold", type=int, default=0, help="seconds to keep running after the commands, before stop")
    ap.add_argument("--seed", default="", help="level-seed for the harness world")
    args = ap.parse_args()
    stage = Path(args.stage).resolve()
    stage.mkdir(parents=True, exist_ok=True)
    args_txt = install_neoforge(stage)
    core = Path(args.core_jar).resolve() if args.core_jar else None
    stage_files(stage, core, [Path(p).resolve() for p in args.extra_jar], args.seed)
    if not args.keep_world:
        wipe_world(stage)
    run_server(stage, args_txt, args.timeout, args.settle, args.command, args.expect, args.hold)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
