#!/usr/bin/env python3
"""Resolve the original 1.20.1 pack to CurseForge files for NeoForge 1.21.1 and write pack/mods.json.

    python tools/resolve_mods.py            # resolve and write pack/mods.json + docs/port-report.md
    python tools/resolve_mods.py --check    # exit 1 if a required dependency of any chosen file is missing

pack/mods.json is the source of truth for the pack manifest. Re-running picks the newest 1.21.1 release of each
project, so review the diff before committing. Third-party jars are never bundled: the export references them by
CurseForge project/file id.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from cfapi import NEOFORGE, api

ROOT = Path(__file__).resolve().parents[1]
MC = "1.21.1"
FABRIC = 4
MOD_CLASS = 6
REQUIRED_DEP = 3
RELEASE_RANK = {1: 0, 2: 1, 3: 2}   # release, beta, alpha
# Libraries the 1.20.1 instance pulled in as dependencies after the original manifest was written.
EXTRA_FROM_INSTANCE = (669659, 570073)


def files_for(pid: int, loader: int | None) -> list[dict]:
    q = f"/v1/mods/{pid}/files?gameVersion={MC}&pageSize=50"
    if loader:
        q += f"&modLoaderType={loader}"
    return api(q)


def newest(files: list[dict]) -> dict | None:
    files = [f for f in files if f.get("isAvailable", True)]
    if not files:
        return None
    # prefer releases over betas over alphas, then the newest upload
    return min(files, key=lambda f: (RELEASE_RANK.get(f["releaseType"], 9), -_ts(f["fileDate"])))


def _ts(s: str) -> float:
    from datetime import datetime
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def sides(f: dict) -> str:
    gv = set(f.get("gameVersions", []))
    if "Client" in gv and "Server" not in gv:
        return "client"
    if "Server" in gv and "Client" not in gv:
        return "server"
    return "both"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    port = json.loads((ROOT / "pack/port-map.json").read_text(encoding="utf-8"))
    original = json.loads((ROOT / "docs/original-manifest-1.20.1.json").read_text(encoding="utf-8"))
    added = [a["id"] for a in port["add"]]
    pids = [f["projectID"] for f in original["files"]] + list(EXTRA_FROM_INSTANCE) + added
    meta = {m["id"]: m for m in api("/v1/mods", {"modIds": pids + [v["to"] for v in port["swap"].values()]})}
    equivalent = {int(k): v["by"] for k, v in port.get("equivalent_deps", {}).items()}

    out, report, problems = [], [], []
    seen: set[int] = set()
    for old in pids:
        name = meta[old]["name"]
        if str(old) in port["drop"]:
            report.append(f"| {name} | dropped | {port['drop'][str(old)]} |")
            continue
        pid, note = old, ("added: " + next(a["why"] for a in port["add"] if a["id"] == old)) if old in added else "kept"
        if str(old) in port["swap"]:
            pid = port["swap"][str(old)]["to"]
            note = f"swapped to {meta[pid]['name']}: {port['swap'][str(old)]['why']}"
        if pid in seen:
            continue
        seen.add(pid)
        m = meta[pid]
        if m["classId"] == MOD_CLASS:
            f = newest(files_for(pid, NEOFORGE))
            if not f and pid in port["fabric_via_connector"]:
                f = newest(files_for(pid, FABRIC))
                note += " (Fabric build, loaded through Sinytra Connector)"
        else:
            f = newest(files_for(pid, None))
            if not f and pid in port["pin_old_version_ok"]["projects"]:
                f = newest(api(f"/v1/mods/{pid}/files?pageSize=50"))
                note += " (no 1.21.1 file; its newest file is kept, off by default)"
        if not f:
            problems.append(f"{m['name']} ({pid}) has no usable 1.21.1 file")
            report.append(f"| {m['name']} | MISSING | no 1.21.1 file |")
            continue
        sha1 = next((h["value"] for h in f.get("hashes", []) if h["algo"] == 1), None)
        out.append({
            "name": m["name"], "projectId": pid, "fileId": f["id"], "fileName": f["fileName"],
            "classId": m["classId"], "side": sides(f), "releaseType": f["releaseType"], "sha1": sha1,
            "origin": old, "deps": [d["modId"] for d in f.get("dependencies", []) if d["relationType"] == REQUIRED_DEP],
        })
        if note != "kept":
            report.append(f"| {m['name']} | {f['fileName']} | {note} |")

    have = {e["projectId"] for e in out}
    have |= {d for d, by in equivalent.items() if by in have}
    dep_meta_ids = sorted({d for e in out for d in e["deps"] if d not in have})
    if dep_meta_ids:
        dm = {m["id"]: m["name"] for m in api("/v1/mods", {"modIds": dep_meta_ids})}
        for e in out:
            for d in e["deps"]:
                if d not in have:
                    problems.append(f"{e['name']} requires {dm.get(d, d)} ({d}), which is not in the pack")

    out.sort(key=lambda e: e["name"].lower())
    if not args.check:
        (ROOT / "pack/mods.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
        lines = ["# Port report (1.20.1 Forge -> 1.21.1 NeoForge)", "",
                 f"{len(out)} CurseForge files resolved. Only changed or dropped entries are listed.", "",
                 "| Project | 1.21.1 file | Note |", "|---|---|---|", *report, ""]
        if problems:
            lines += ["## Problems", "", *[f"- {p}" for p in problems], ""]
        (ROOT / "docs/port-report.md").write_text("\n".join(lines), encoding="utf-8")
    for p in problems:
        print("PROBLEM:", p)
    print(f"{len(out)} files resolved, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
