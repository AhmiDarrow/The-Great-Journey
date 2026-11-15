#!/usr/bin/env python3
"""Install and boot a server pack exactly as a server owner would, on THIS PC.

    python tools/test_server_pack.py dist/TheGreatJourney-Server-0.1.0.zip               # Windows: install.bat path
    python tools/test_server_pack.py dist/TheGreatJourney-Server-0.1.0.zip --installer sh  # bash: install.sh path

Unzips into a fresh folder under build/server-pack-test/, runs the pack's own installer (real downloads from
maven.neoforged.net and CurseForge's CDN, every SHA-1 checked), then the pack's own start script. Passes only if the
server prints "Done (...)!", stays up --settle seconds with no failure line, and stops cleanly on "stop". Then runs
the installer a second time to check an update over an installed server downloads nothing and retires nothing.
"""
from __future__ import annotations

import argparse
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from full_pack_server import DONE_RE, FAIL_RES, free_port  # noqa: E402

BELOW_NORMAL_PRIORITY_CLASS = 0x00004000
GIT_BASH = Path(r"C:\Program Files\Git\bin\bash.exe")


def log(msg: str) -> None:
    print(msg, flush=True)


def installer_cmd(folder: Path, kind: str) -> list[str]:
    if kind == "ps1":
        return ["cmd.exe", "/d", "/c", str(folder / "install.bat"), "--accept-eula"]
    bash = str(GIT_BASH) if GIT_BASH.is_file() else "bash"
    return [bash, "./install.sh", "--accept-eula"]


def start_cmd(folder: Path, kind: str) -> list[str]:
    # start.sh passes unix_args.txt (':' separated), which Java on Windows cannot read: there, a bash-installed
    # server is started with start.bat (the files are the same); start.sh is syntax-checked instead
    if kind == "ps1" or sys.platform == "win32":
        return ["cmd.exe", "/d", "/c", str(folder / "start.bat")]
    return ["bash", "./start.sh"]


def install(folder: Path, kind: str) -> str:
    r = subprocess.run(installer_cmd(folder, kind), cwd=folder, input="\n", capture_output=True, text=True, timeout=3600)
    out = r.stdout + r.stderr
    if r.returncode != 0 or "Installed." not in out:
        raise SystemExit(f"FAIL installer exit {r.returncode}:\n{out[-3000:]}")
    return out


def boot(folder: Path, kind: str, timeout: int, settle: int) -> None:
    props = folder / "server.properties"
    text = props.read_text(encoding="utf-8")
    text = text.replace("online-mode=true", "online-mode=false") + f"server-port={free_port()}\n"
    props.write_text(text, encoding="utf-8")
    proc = subprocess.Popen(start_cmd(folder, kind), cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                            creationflags=BELOW_NORMAL_PRIORITY_CLASS if sys.platform == "win32" else 0)
    lines: queue.Queue[str] = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout], daemon=True).start()
    failures, done_at, start = [], None, time.monotonic()
    try:
        while True:
            now = time.monotonic()
            if done_at is None and now - start > timeout:
                raise SystemExit(f"FAIL no 'Done' within {timeout}s")
            if done_at is not None and now - done_at > settle:
                break
            if proc.poll() is not None and lines.empty():
                raise SystemExit(f"FAIL server exited early (code {proc.returncode})")
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if any(r.search(line) for r in FAIL_RES):
                failures.append(line.rstrip())
            if done_at is None and DONE_RE.search(line):
                done_at = time.monotonic()
                log(f"server up after {done_at - start:.0f}s")
        proc.stdin.write("stop\n")
        proc.stdin.flush()
        # start.bat ends on a pause: keep pressing a key until the window closes (one sent before the server had
        # finished saving was read by the server's console, and the pause then waited for ever)
        end = time.monotonic() + 180
        while proc.poll() is None and time.monotonic() < end:
            time.sleep(3)
            try:
                proc.stdin.write("\n")
                proc.stdin.flush()
            except OSError:
                break
        proc.wait(timeout=10)
        try:
            proc.stdin.close()                               # the window is gone: its input pipe may be too
        except OSError:
            pass
    finally:
        if proc.poll() is None:
            proc.kill()
    if failures:
        raise SystemExit("FAIL failure lines:\n" + "\n".join(failures[:20]))
    if list((folder / "crash-reports").glob("*.txt")) if (folder / "crash-reports").is_dir() else []:
        raise SystemExit("FAIL a crash report was written")
    latest = (folder / "logs/latest.log").read_text(encoding="utf-8", errors="replace")
    if "Stopping server" not in latest:
        raise SystemExit("FAIL the server did not stop cleanly")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("zip")
    ap.add_argument("--installer", choices=["ps1", "sh"], default="ps1")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--settle", type=int, default=30)
    ap.add_argument("--work", default="", help="folder name under build/server-pack-test (default: the installer)")
    args = ap.parse_args()

    folder = ROOT / "build/server-pack-test" / (args.work or args.installer)
    shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir(parents=True)
    with zipfile.ZipFile(args.zip) as z:
        z.extractall(folder)
        for info in z.infolist():                            # keep the scripts' executable bits
            mode = info.external_attr >> 16
            if mode:
                (folder / info.filename).chmod(mode)
    t = time.monotonic()
    install(folder, args.installer)
    mods = sorted(p.name for p in (folder / "mods").glob("*.jar"))
    log(f"installed NeoForge and {len(mods)} mod jars in {time.monotonic() - t:.0f}s")
    jvm = (folder / "user_jvm_args.txt").read_text(encoding="utf-8")
    if not re.search(r"(?m)^\s*-Xmx", jvm):
        raise SystemExit("FAIL user_jvm_args.txt sets no -Xmx: the server would get Java's default heap")
    if args.installer == "sh":
        bash = str(GIT_BASH) if GIT_BASH.is_file() else "bash"
        for script in ("install.sh", "start.sh"):
            r = subprocess.run([bash, "-n", script], cwd=folder, capture_output=True, text=True)
            if r.returncode != 0:
                raise SystemExit(f"FAIL {script} does not parse: {r.stderr}")
    boot(folder, args.installer, args.timeout, args.settle)
    log("server booted and stopped cleanly")
    again = install(folder, args.installer)
    if "[1/" in again or "retired" in again:
        raise SystemExit(f"FAIL a second install downloaded or retired files:\n{again[-2000:]}")
    if sorted(p.name for p in (folder / "mods").glob("*.jar")) != mods:
        raise SystemExit("FAIL a second install changed mods/")
    log(f"PASS server pack ({args.installer}) installs, boots, stops, and re-installs as a no-op")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
