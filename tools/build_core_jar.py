#!/usr/bin/env python3
"""Package our own modules into one CurseForge-hostable jar: The Great Journey Core.

    python tools/build_core_jar.py --dry-run          # show the inputs it would package, build nothing
    python tools/build_core_jar.py                    # mods/<module>/build/libs/<modid>-<ver>.jar -> dist/greatjourney_core-<ver>.jar
    python tools/build_core_jar.py --check-source     # exit 3 if mods/ changed since the released Core
    python tools/build_core_jar.py --record-release <fileId> --project-id <id>   # after uploading the jar

Only code we wrote goes in here. Third-party mods are never nested: the pack references them by CurseForge
project/file id (their licenses, All Rights Reserved ones included, do not allow re-hosting, and CurseForge rejects
bundled copies). The outer jar has no code (modLoader "lowcodefml"); NeoForge's Jar-in-Jar loader pulls the nested
jars out of META-INF/jarjar/. The zip is deterministic (fixed timestamps, fixed order), so an unchanged input set
always produces the same SHA-1.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE_ID = "greatjourney_core"
CORE_NAME = "The Great Journey Core"
CORE_JAR = "greatjourney_core-{ver}.jar"
GROUP = "com.ninjacat.greatjourney"
MODULES = ("clanlore", "clanlands", "clanwild")      # Gradle subproject name == mod id
EPOCH = (2026, 1, 1, 0, 0, 0)
OUT = ROOT / "dist"
RELEASE = ROOT / "pack/core-release.json"
HASH_EXCLUDE = {"mods/gradle/wrapper/gradle-wrapper.jar"}
SOURCE_CHANGED = "differs from the released Core"


def gradle_version() -> str:
    m = re.search(r"(?m)^mod_version=(\S+)", (ROOT / "mods/gradle.properties").read_text(encoding="utf-8"))
    if not m:
        raise SystemExit("mod_version missing from mods/gradle.properties")
    return m.group(1)


def mods_toml(ver: str) -> str:
    deps = "".join(f'''
[[dependencies.{CORE_ID}]]
    modId="{mid}"
    type="required"
    versionRange="[{ver},)"
    ordering="NONE"
    side="BOTH"
''' for mid in MODULES)
    return f'''modLoader="lowcodefml"
loaderVersion="[4,)"
license="All Rights Reserved"

[[mods]]
modId="{CORE_ID}"
version="{ver}"
displayName="{CORE_NAME}"
authors="AhmiDarrow"
logoFile="{CORE_ID}.png"
description=\'\'\'The companion mods of The Great Journey modpack in one jar: Clanlands (the Clan territories, camps and story from the books) and Clanwild (speed strikes, prey and wildlife glue). Written from scratch for the pack.\'\'\'

[[dependencies.{CORE_ID}]]
    modId="neoforge"
    type="required"
    versionRange="[21.1.0,)"
    ordering="NONE"
    side="BOTH"

[[dependencies.{CORE_ID}]]
    modId="minecraft"
    type="required"
    versionRange="[1.21.1]"
    ordering="NONE"
    side="BOTH"
{deps}'''


def inner_mod(jar: Path) -> tuple[str, str]:
    with zipfile.ZipFile(jar) as z:
        toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
    body = toml.split("[[mods]]", 1)[1]
    return (re.search(r'(?m)^\s*modId\s*=\s*"([^"]+)"', body).group(1),
            re.search(r'(?m)^\s*version\s*=\s*"([^"]+)"', body).group(1))


def module_jar(mid: str, ver: str) -> Path:
    return ROOT / "mods" / mid / "build/libs" / f"{mid}-{ver}.jar"


def put(z: zipfile.ZipFile, name: str, data: bytes, stored: bool = False) -> None:
    info = zipfile.ZipInfo(name, EPOCH)
    info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    z.writestr(info, data)


TEST_ONLY = ("qa", "greatjourney_qa")   # the playtest harness mod: never shipped


def inputs(ver: str) -> list[tuple[str, Path]]:
    found = []
    if any(mid in TEST_ONLY for mid in MODULES):
        raise SystemExit("Refusing: the test-only qa module is in MODULES; it must never ship in the Core")
    for mid in MODULES:
        jar = module_jar(mid, ver)
        if not jar.is_file():
            raise SystemExit(f"missing module jar: {jar.relative_to(ROOT)} (run mods/gradlew buildAll first)")
        got = inner_mod(jar)
        if got[0] in TEST_ONLY:
            raise SystemExit(f"Refusing: {jar.name} is the test-only qa mod")
        if got != (mid, ver):
            raise SystemExit(f"{jar.name} declares {got[0]} {got[1]}, expected {mid} {ver}")
        found.append((mid, jar))
    return found


def build(ver: str, out_dir: Path) -> Path:
    jars = inputs(ver)
    metadata = {"jars": [{
        "identifier": {"group": GROUP, "artifact": mid},
        "version": {"range": f"[{ver},)", "artifactVersion": ver},
        "path": f"META-INF/jarjar/{jar.name}",
        "isObfuscated": False,
    } for mid, jar in jars]}
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / CORE_JAR.format(ver=ver)
    tmp = out.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w") as z:
        put(z, "META-INF/MANIFEST.MF", (f"Manifest-Version: 1.0\r\nImplementation-Title: {CORE_NAME}\r\n"
                                        f"Implementation-Version: {ver}\r\n\r\n").encode())
        put(z, "META-INF/neoforge.mods.toml", mods_toml(ver).encode("utf-8"))
        put(z, "META-INF/jarjar/metadata.json", (json.dumps(metadata, indent=2) + "\n").encode("utf-8"))
        for _, jar in jars:            # nested jars are already compressed; store them
            put(z, f"META-INF/jarjar/{jar.name}", jar.read_bytes(), stored=True)
        logo = ROOT / "docs/public/core-icon-256.png"
        if logo.is_file():
            put(z, f"{CORE_ID}.png", logo.read_bytes(), stored=True)
    tmp.replace(out)
    return out


def source_hash() -> str:
    """SHA-1 over every non-ignored file under mods/ (sorted, CRLF folded to LF). Compiled jars are not byte-for-byte
    reproducible, so "did the module code change since the Core file on CurseForge?" is answered from sources."""
    names = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "mods"], cwd=ROOT,
                           check=True, capture_output=True).stdout.decode("utf-8").split("\0")
    h = hashlib.sha1()
    for name in sorted(n for n in names if n and n not in HASH_EXCLUDE):
        path = ROOT / name
        if path.is_file():
            h.update(name.encode("utf-8") + b"\0" + path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
    return h.hexdigest()


def release_problems(ver: str) -> list[str]:
    if not RELEASE.is_file():
        return [f"{RELEASE.relative_to(ROOT).as_posix()} is missing"]
    rel = json.loads(RELEASE.read_text(encoding="utf-8"))
    if rel.get("sourceHash") != source_hash() or rel.get("version") != ver:
        return [f"mods/ (version {ver}) {SOURCE_CHANGED} {rel.get('filename', '(none)')}"]
    return []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="")
    ap.add_argument("--out-dir", default=str(OUT))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--check-source", action="store_true")
    ap.add_argument("--record-release", type=int, default=0, metavar="FILE_ID")
    ap.add_argument("--project-id", type=int, default=0, help="the Core's CurseForge project id (with --record-release)")
    args = ap.parse_args()
    ver = args.version or gradle_version()

    if args.dry_run:
        print(f"{CORE_JAR.format(ver=ver)} would nest:")
        for mid in MODULES:
            jar = module_jar(mid, ver)
            print(f"  {jar.relative_to(ROOT).as_posix()}  {'present' if jar.is_file() else 'MISSING'}")
        return 0
    if args.record_release:
        if not args.project_id:
            raise SystemExit("--record-release needs --project-id (the Core's CurseForge project)")
        jar = Path(args.out_dir) / CORE_JAR.format(ver=ver)
        if not jar.is_file():
            raise SystemExit(f"{jar} not found; build it first")
        RELEASE.write_text(json.dumps({
            "name": CORE_NAME, "version": ver, "projectId": args.project_id, "fileId": args.record_release,
            "filename": jar.name, "sha1": hashlib.sha1(jar.read_bytes()).hexdigest(), "sourceHash": source_hash(),
        }, indent=2) + "\n", encoding="utf-8")
        print(f"recorded {jar.name} as CurseForge file {args.record_release}")
        return 0
    if args.check_source:
        problems = release_problems(ver)
        if problems:
            print("CHANGED: " + "; ".join(problems))
            return 3
        print("UNCHANGED: mods/ matches the released Core")
        return 0
    out = build(ver, Path(args.out_dir))
    print(f"{out.name}  {out.stat().st_size} bytes  sha1={hashlib.sha1(out.read_bytes()).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
