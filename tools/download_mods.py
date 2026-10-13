#!/usr/bin/env python3
"""Download the pack's CurseForge files listed in pack/mods.json and verify their SHA-1.

    python tools/download_mods.py                   # mods (classId 6) -> pack/mods/
    python tools/download_mods.py --resourcepacks   # also resource packs -> pack/resourcepacks-cache/

Files already present with the right SHA-1 are skipped. Jars from older resolves that are no longer listed are
moved to pack/mods-stale/. Files whose author disabled third-party distribution (downloadUrl null) are reported;
they still install through a CurseForge manifest, but a local test needs them fetched by hand.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import urllib.parse
import urllib.request
from pathlib import Path

from cfapi import api

ROOT = Path(__file__).resolve().parents[1]
MOD_CLASS = 6


def sha1(p: Path) -> str:
    return hashlib.sha1(p.read_bytes()).hexdigest()


def cdn_url(file_id: int, name: str) -> str:
    # CurseForge's edge CDN layout; used when the API omits downloadUrl but the file is still public
    return f"https://edge.forgecdn.net/files/{file_id // 1000}/{file_id % 1000}/{urllib.parse.quote(name)}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--resourcepacks", action="store_true")
    args = ap.parse_args()
    entries = json.loads((ROOT / "pack/mods.json").read_text(encoding="utf-8"))
    mods_dir = ROOT / "pack/mods"
    rp_dir = ROOT / "pack/resourcepacks-cache"
    mods_dir.mkdir(parents=True, exist_ok=True)

    wanted = [e for e in entries if e["classId"] == MOD_CLASS or args.resourcepacks]
    info = {f["id"]: f for f in api("/v1/mods/files", {"fileIds": [e["fileId"] for e in wanted]})}
    blocked, fetched, kept = [], 0, 0
    for e in wanted:
        dest_dir = mods_dir if e["classId"] == MOD_CLASS else rp_dir
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / e["fileName"]
        if dest.is_file() and (not e["sha1"] or sha1(dest) == e["sha1"]):
            kept += 1
            continue
        f = info.get(e["fileId"], {})
        url = f.get("downloadUrl")
        if not url:
            blocked.append(e)
            url = cdn_url(e["fileId"], e["fileName"])
        try:
            req = urllib.request.Request(url.replace(" ", "%20"), headers={"User-Agent": "great-journey-tools"})
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
        except Exception as ex:   # noqa: BLE001 - report and continue
            print(f"FAILED {e['name']} ({e['fileName']}): {ex}")
            continue
        if e["sha1"] and hashlib.sha1(data).hexdigest() != e["sha1"]:
            print(f"SHA-1 MISMATCH {e['name']} ({e['fileName']}); not written")
            continue
        dest.write_bytes(data)
        fetched += 1

    listed = {e["fileName"] for e in entries if e["classId"] == MOD_CLASS}
    stale = [p for p in mods_dir.glob("*.jar") if p.name not in listed]
    if stale:
        (ROOT / "pack/mods-stale").mkdir(exist_ok=True)
        for p in stale:
            shutil.move(str(p), ROOT / "pack/mods-stale" / p.name)
            print("stale, moved aside:", p.name)
    for e in blocked:
        print(f"distribution disabled (API downloadUrl null): {e['name']} {e['fileName']}")
    print(f"{fetched} downloaded, {kept} already present, {len(blocked)} with distribution disabled")
    return 0


if __name__ == "__main__":
    sys.exit(main())
