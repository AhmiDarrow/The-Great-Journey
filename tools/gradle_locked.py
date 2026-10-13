#!/usr/bin/env python3
"""Run ./gradlew in mods/ while holding a lock, so several helpers working in the same tree never build at once.

    python tools/gradle_locked.py :clanlands:compileJava
    python tools/gradle_locked.py :clanlands:runVerification

Waits (up to --wait seconds, default 1800) for whoever holds build/gradle.lock, then runs Gradle at BelowNormal
priority and releases the lock. A lock older than 45 minutes is taken as stale and broken.
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "build/gradle.lock"
STALE = 45 * 60
BELOW_NORMAL_PRIORITY_CLASS = 0x00004000


def acquire(wait: int) -> None:
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    said = False
    while True:
        try:
            LOCK.mkdir()
            (LOCK / "owner.txt").write_text(f"pid {os.getpid()} {' '.join(sys.argv[1:])}\n", encoding="utf-8")
            return
        except FileExistsError:
            try:
                if time.time() - LOCK.stat().st_mtime > STALE:
                    for f in LOCK.iterdir():
                        f.unlink(missing_ok=True)
                    LOCK.rmdir()
                    continue
            except OSError:
                continue
            if not said:
                owner = (LOCK / "owner.txt").read_text(encoding="utf-8", errors="replace").strip() if (LOCK / "owner.txt").exists() else "?"
                print(f"waiting for the Gradle lock ({owner})", flush=True)
                said = True
            if time.monotonic() - start > wait:
                raise SystemExit("gave up waiting for the Gradle lock")
            time.sleep(3)


def release() -> None:
    for f in LOCK.iterdir() if LOCK.exists() else []:
        f.unlink(missing_ok=True)
    try:
        LOCK.rmdir()
    except OSError:
        pass


def main() -> int:
    args = sys.argv[1:]
    wait, normal = 1800, False
    if args[:1] == ["--wait"]:
        wait, args = int(args[1]), args[2:]
    if args[:1] == ["--normal"]:                    # the owner playing a test client: full priority
        normal, args = True, args[1:]
    acquire(wait)
    try:
        gradlew = ROOT / "mods" / ("gradlew.bat" if os.name == "nt" else "gradlew")
        flags = BELOW_NORMAL_PRIORITY_CLASS if os.name == "nt" and not normal else 0
        if os.name == "nt":
            flags |= 0x08000000          # CREATE_NO_WINDOW: no console window pops up on the owner's screen
        # output through pipes: a console-less .bat writes nothing to inherited handles
        proc = subprocess.Popen([str(gradlew), *args], cwd=ROOT / "mods", creationflags=flags,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        for line in proc.stdout:
            print(line, end="", flush=True)
        return proc.wait()
    finally:
        release()


if __name__ == "__main__":
    raise SystemExit(main())
