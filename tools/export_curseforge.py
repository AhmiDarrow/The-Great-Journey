#!/usr/bin/env python3
"""Build the CurseForge modpack zip: manifest.json + modlist.html + overrides/.

    python tools/export_curseforge.py                  # needs pack/core-release.json (our Core on CurseForge)
    python tools/export_curseforge.py --allow-no-core  # local test zip without the Core entry

Every mod and resource pack comes from pack/mods.json and is referenced by CurseForge project/file id; no jar is
ever bundled. Our Core is a CurseForge project too (pack/core-release.json: {"projectId": .., "fileId": ..}).
"""
from __future__ import annotations

import argparse
import html
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK_NAME = "The Great Journey"
ZIP_STEM = "TheGreatJourney"
EPOCH = (2026, 1, 1, 0, 0, 0)


def toml_value(key: str) -> str:
    m = re.search(rf'(?m)^{key}\s*=\s*"([^"]+)"', (ROOT / "pack/pack.toml").read_text(encoding="utf-8"))
    if not m:
        raise SystemExit(f"{key} missing from pack/pack.toml")
    return m.group(1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--allow-no-core", action="store_true")
    ap.add_argument("--out-dir", default=str(ROOT / "dist"))
    args = ap.parse_args()

    overrides = ROOT / "pack/overrides"
    jars = [p for p in overrides.rglob("*") if p.suffix.lower() == ".jar"]
    if jars:
        raise SystemExit(f"Refusing: jars inside pack/overrides ({jars[0]}); reference them in pack/mods.json")
    if not (overrides / "NOTICE.txt").is_file():
        raise SystemExit("Refusing: pack/overrides/NOTICE.txt is missing (pack credits)")

    entries = json.loads((ROOT / "pack/mods.json").read_text(encoding="utf-8"))
    if any("greatjourney_qa" in (e.get("fileName") or "") or "qa" == (e.get("slug") or "") for e in entries) \
            or any(p.name.startswith("greatjourney_qa") for p in (ROOT / "pack/mods").glob("*.jar")):
        raise SystemExit("Refusing: the test-only greatjourney_qa mod is in the pack; it must never ship")
    files = [{"projectID": e["projectId"], "fileID": e["fileId"], "required": True} for e in entries]
    names = [(e["name"], e["projectId"]) for e in entries]
    core_path = ROOT / "pack/core-release.json"
    if core_path.is_file():
        core = json.loads(core_path.read_text(encoding="utf-8"))
        files.append({"projectID": core["projectId"], "fileID": core["fileId"], "required": True})
        names.append((core.get("name", "The Great Journey Core"), core["projectId"]))
    elif not args.allow_no_core:
        raise SystemExit("Refusing: pack/core-release.json is missing (upload the Core first, or pass --allow-no-core)")

    version = toml_value("version")
    manifest = {
        "minecraft": {"version": toml_value("minecraft"),
                      "modLoaders": [{"id": f"neoforge-{toml_value('neoforge')}", "primary": True}]},
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": PACK_NAME,
        "version": version,
        "author": toml_value("author"),
        "files": files,
        "overrides": "overrides",
    }
    rows = "\n".join(
        f'<li><a href="https://www.curseforge.com/projects/{pid}">{html.escape(n)}</a></li>'
        for n, pid in sorted(names, key=lambda t: t[0].lower()))
    modlist = f"<ul>\n{rows}\n</ul>\n"

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"{ZIP_STEM}-{version}{'' if core_path.is_file() else '-nocore'}.zip"

    def put(z: zipfile.ZipFile, name: str, data: bytes) -> None:
        info = zipfile.ZipInfo(name, EPOCH)
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)

    with zipfile.ZipFile(zip_path, "w") as z:
        put(z, "manifest.json", (json.dumps(manifest, indent=2) + "\n").encode())
        put(z, "modlist.html", modlist.encode())
        for p in sorted(overrides.rglob("*")):
            if p.is_file() and not p.name.startswith("."):
                put(z, "overrides/" + p.relative_to(overrides).as_posix(), p.read_bytes())
    print(f"Wrote {zip_path} ({len(files)} files in manifest)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
