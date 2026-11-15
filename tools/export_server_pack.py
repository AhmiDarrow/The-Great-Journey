#!/usr/bin/env python3
"""Build the dedicated-server pack that rides along with each CurseForge release (an additional file of the pack).

    python tools/export_server_pack.py                    # -> dist/TheGreatJourney-Server-<version>.zip
    python tools/export_server_pack.py --unreleased-core  # local test zip before the Core is recorded

No one else's jar is redistributed. The zip carries our own Core jar (the exact file recorded in
pack/core-release.json, checked by SHA-1), the pack's server-side config and datapacks, a manifest of the CurseForge
files the client pack installs (client-only mods left out, by the same rule tools/full_pack_server.py boots with),
and install scripts (bash and PowerShell) that fetch NeoForge from maven.neoforged.net and every mod from
CurseForge's CDN, checking each file's SHA-1. Then start.sh / start.bat.

Adapted from Ninjacat Skies' tools/export_server_pack.py, which has shipped many server packs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_core_jar import CORE_JAR, gradle_version, release_problems  # noqa: E402
from full_pack_server import CLIENT_ONLY_CONFIG, client_only_names, is_client_only  # noqa: E402

PACK_NAME = "The Great Journey"
ZIP_STEM = "TheGreatJourney"
EPOCH = (2026, 1, 1, 0, 0, 0)
# server-side overrides; the client's FancyMenu layouts stay out
OVERRIDE_DIRS = ("config", "moonlight-global-datapacks")
SECRETISH = re.compile(r"(?i)(\.py|\.ps1|\.key|\.log)$|credentials|^\.env")


def toml_value(key: str) -> str:
    m = re.search(rf'(?m)^{key}\s*=\s*"([^"]+)"', (ROOT / "pack/pack.toml").read_text(encoding="utf-8"))
    if not m:
        raise SystemExit(f"{key} missing from pack/pack.toml")
    return m.group(1)


INSTALL_SH = r'''#!/usr/bin/env bash
# The Great Journey dedicated server: installer. Needs java 21, curl, sha1sum (or shasum).
set -euo pipefail
cd "$(dirname "$0")"
NEO="{NEO}"
say() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { say "missing: $1"; exit 1; }; }
need java; need curl
if command -v sha1sum >/dev/null 2>&1; then SHA1="sha1sum"; elif command -v shasum >/dev/null 2>&1; then SHA1="shasum -a 1"; else say "missing: sha1sum"; exit 1; fi
JV=$(java -version 2>&1 | grep -m1 -oE 'version "[0-9]+' | grep -oE '[0-9]+$' || true); [ "${JV:-0}" -ge 21 ] || { say "Java 21 or newer is required (found: $(java -version 2>&1 | grep -m1 version))"; exit 1; }

say "== NeoForge $NEO server"
if [ ! -f "libraries/net/neoforged/neoforge/$NEO/unix_args.txt" ]; then
  curl -fsSL --retry 3 -o neoforge-installer.jar "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO/neoforge-$NEO-installer.jar"
  java -jar neoforge-installer.jar --install-server . >/dev/null
  rm -f neoforge-installer.jar neoforge-installer.jar.log
fi

say "== mods (from CurseForge, checked by SHA-1)"
mkdir -p mods
n=0; total=$(grep -c . server-mods.txt || true)
# server-mods.txt: one line per mod, fileID|filename|sha1 (the same data as server-manifest.json)
while IFS='|' read -r fid fn sha; do
  [ -n "$fid" ] || continue
  n=$((n+1))
  dst="mods/$fn"
  if [ -f "$dst" ] && [ "$($SHA1 "$dst" | cut -d' ' -f1)" = "$sha" ]; then continue; fi
  enc=$(printf '%s' "$fn" | sed 's/%/%25/g; s/ /%20/g; s/+/%2B/g; s/\[/%5B/g; s/\]/%5D/g')
  url="https://edge.forgecdn.net/files/$((fid/1000))/$((fid%1000))/$enc"
  say "  [$n/$total] $fn"
  curl -fsSL --retry 3 -o "$dst.part" "$url" || { say "download failed: $fn ($url)"; rm -f "$dst.part"; exit 1; }
  got=$($SHA1 "$dst.part" | cut -d' ' -f1)
  [ "$got" = "$sha" ] || { say "checksum mismatch for $fn (expected $sha, got $got)"; rm -f "$dst.part"; exit 1; }
  mv -f "$dst.part" "$dst"
done < server-mods.txt

# Updating over an older pack: jars it installed that this version no longer ships would load twice and stop the
# server; they move to mods-removed/. .installed-mods.txt records what the pack put in mods/, so your own mods stay.
record=".installed-mods.txt"
for jar in mods/*.jar; do
  [ -e "$jar" ] || continue
  name=$(basename "$jar")
  grep -qF "|$name|" server-mods.txt && continue
  grep -qxF "$name" bundled-mods.txt && continue
  if [ -f "$record" ] && ! grep -qxF -e "$name" -e "$name"$'\r' "$record"; then continue; fi
  mkdir -p mods-removed; mv -f "$jar" "mods-removed/$name"; say "  retired $name -> mods-removed/"
done
{ cut -d'|' -f2 server-mods.txt | grep .; cat bundled-mods.txt; } > "$record"

[ -f server.properties ] || cp server.properties.default server.properties
# NeoForge's installer writes a user_jvm_args.txt with the memory line commented out: without a -Xmx the server
# gets Java's default heap and can run out. Use the pack's unless the owner set their own -Xmx.
if [ ! -f user_jvm_args.txt ] || ! grep -qE '^[[:space:]]*-Xmx' user_jvm_args.txt; then cp user_jvm_args.default.txt user_jvm_args.txt; fi

if [ ! -f eula.txt ] || ! grep -qi 'eula=true' eula.txt; then
  if [ "${1:-}" = "--accept-eula" ]; then echo "eula=true" > eula.txt
  else
    say ""
    say "Minecraft's EULA (https://aka.ms/MinecraftEULA) must be accepted to run a server."
    read -r -p "Accept it now? [y/N] " a; case "$a" in y|Y|yes|YES) echo "eula=true" > eula.txt;; *) say "Not accepted: edit eula.txt later.";; esac
  fi
fi
say ""
say "Installed. Start with ./start.sh (edit server.properties and config/clanlands-common.toml first if you like)."
'''

INSTALL_PS1 = r'''# The Great Journey dedicated server: installer (Windows PowerShell). Needs Java 21.
$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1: its progress bar slows Invoke-WebRequest to a crawl, and older Windows offers no TLS 1.2
$ProgressPreference = "SilentlyContinue"
if ([Net.ServicePointManager]::SecurityProtocol -ne 0) { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 }
# [brackets] in the folder name are wildcards to PowerShell's path handling, so refuse up front
if ($PSScriptRoot -match '[\[\]]') { Write-Host "Rename this folder without [ or ] and run install again: $PSScriptRoot"; exit 1 }
Set-Location -LiteralPath $PSScriptRoot
$NEO = "{NEO}"
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Write-Host "Java 21 or newer is required (java is not on PATH)"; exit 1 }
# cmd merges java's stderr: in Windows PowerShell 5.1 a native command's stderr under "Stop" is a terminating error
$jvLine = (cmd /c "java -version 2>&1" | Select-String -Pattern 'version "(\d+)' | Select-Object -First 1)
$jv = if ($jvLine) { [int]$jvLine.Matches[0].Groups[1].Value } else { 0 }
if ($jv -lt 21) { Write-Host "Java 21 or newer is required (found: $jvLine)"; exit 1 }

Write-Host "== NeoForge $NEO server"
if (-not (Test-Path "libraries/net/neoforged/neoforge/$NEO/win_args.txt")) {
  Invoke-WebRequest -Uri "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO/neoforge-$NEO-installer.jar" -OutFile neoforge-installer.jar -UseBasicParsing
  & java -jar neoforge-installer.jar --install-server . | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "NeoForge installer failed (exit $LASTEXITCODE)" }
  Remove-Item -Force neoforge-installer.jar, neoforge-installer.jar.log -ErrorAction SilentlyContinue
}

Write-Host "== mods (from CurseForge, checked by SHA-1)"
New-Item -ItemType Directory -Force -Path mods | Out-Null
$manifest = Get-Content server-manifest.json -Raw | ConvertFrom-Json
$i = 0; $total = $manifest.files.Count
foreach ($f in $manifest.files) {
  $i++
  $dst = Join-Path mods $f.filename
  if ((Test-Path -LiteralPath $dst) -and ((Get-FileHash -LiteralPath $dst -Algorithm SHA1).Hash.ToLower() -eq $f.sha1)) { continue }
  $enc = [uri]::EscapeDataString($f.filename)
  $url = "https://edge.forgecdn.net/files/$([math]::Floor($f.fileID / 1000))/$($f.fileID % 1000)/$enc"
  Write-Host "  [$i/$total] $($f.filename)"
  Invoke-WebRequest -Uri $url -OutFile "$dst.part" -UseBasicParsing
  $got = (Get-FileHash -LiteralPath "$dst.part" -Algorithm SHA1).Hash.ToLower()
  if ($got -ne $f.sha1) { Remove-Item -Force -LiteralPath "$dst.part"; throw "checksum mismatch for $($f.filename)" }
  Move-Item -Force -LiteralPath "$dst.part" -Destination $dst
}

# Updating over an older pack: jars it installed that this version no longer ships would load twice and stop the
# server. Those move to mods-removed/. .installed-mods.txt records what the pack put in mods/, so your own mods stay.
$wanted = @{}; foreach ($f in $manifest.files) { $wanted[$f.filename] = $true }
foreach ($b in $manifest.bundled) { $wanted[$b] = $true }
$record = ".installed-mods.txt"
$ours = $null
if (Test-Path $record) { $ours = @{}; foreach ($line in Get-Content $record) { if ($line) { $ours[$line] = $true } } }
$retired = @(Get-ChildItem mods -Filter *.jar | Where-Object { -not $wanted.ContainsKey($_.Name) -and ($null -eq $ours -or $ours.ContainsKey($_.Name)) })
if ($retired.Count -gt 0) {
  New-Item -ItemType Directory -Force -Path mods-removed | Out-Null
  foreach ($j in $retired) { Move-Item -Force -LiteralPath $j.FullName -Destination (Join-Path mods-removed $j.Name); Write-Host "  retired $($j.Name) -> mods-removed/" }
}
Set-Content -Path $record -Value (@($manifest.files | ForEach-Object { $_.filename }) + @($manifest.bundled)) -Encoding ascii

if (-not (Test-Path server.properties)) { Copy-Item server.properties.default server.properties }
# NeoForge's installer writes a user_jvm_args.txt with the memory line commented out: without a -Xmx the server
# gets Java's default heap and can run out. Use the pack's unless the owner set their own -Xmx.
if (-not (Test-Path user_jvm_args.txt) -or -not (Select-String -Path user_jvm_args.txt -Pattern '^\s*-Xmx' -Quiet)) { Copy-Item -Force user_jvm_args.default.txt user_jvm_args.txt }

if (-not (Test-Path eula.txt) -or -not (Select-String -Path eula.txt -Pattern 'eula=true' -Quiet)) {
  if ($args -contains "--accept-eula") { Set-Content eula.txt "eula=true" }
  else {
    Write-Host ""; Write-Host "Minecraft's EULA (https://aka.ms/MinecraftEULA) must be accepted to run a server."
    $a = Read-Host "Accept it now? [y/N]"
    if ($a -match '^(y|yes)$') { Set-Content eula.txt "eula=true" } else { Write-Host "Not accepted: edit eula.txt later." }
  }
}
Write-Host ""; Write-Host "Installed. Start with start.bat (edit server.properties and config/clanlands-common.toml first if you like)."
'''

INSTALL_BAT = r'''@echo off
setlocal
rem Started from a PowerShell 7 prompt, PSModulePath would point Windows PowerShell 5.1 at 7's modules (no Get-FileHash)
set "PSModulePath="
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set "rc=%errorlevel%"
pause
exit /b %rc%
'''

START_SH = r'''#!/usr/bin/env bash
cd "$(dirname "$0")"
[ -f "libraries/net/neoforged/neoforge/{NEO}/unix_args.txt" ] || { echo "Run ./install.sh first."; exit 1; }
exec java @user_jvm_args.txt @libraries/net/neoforged/neoforge/{NEO}/unix_args.txt nogui "$@"
'''

START_BAT = r'''@echo off
cd /d "%~dp0"
if not exist "libraries\net\neoforged\neoforge\{NEO}\win_args.txt" ( echo Run install.bat first. & pause & exit /b 1 )
java @user_jvm_args.txt @libraries/net/neoforged/neoforge/{NEO}/win_args.txt nogui %*
pause
'''

SERVER_PROPERTIES = '''# The Great Journey dedicated server. Everything here is a normal Minecraft server setting.
motd=The Great Journey
gamemode=survival
difficulty=normal
max-players=10
view-distance=10
simulation-distance=8
online-mode=true
spawn-protection=0
allow-flight=true
max-tick-time=-1
sync-chunk-writes=false
enforce-secure-profile=false
'''

USER_JVM_ARGS = '''# Memory for the server. 6 GB is comfortable for a few players; 4 GB is the floor.
-Xmx6G
-Xms2G
'''

# The Clan lands' mode for the server's world. NeoForge fills in anything else the file lacks.
CLANLANDS_COMMON = '''[modes]
\t#How new worlds are played when no title-screen button chose: "free" or "campaign".
\t#Dedicated servers use this. An existing world keeps the mode it was created with.
\tnewWorldMode = "free"
'''

README = '''# The Great Journey: dedicated server

Minecraft {MC} · NeoForge {NEO} · pack {VERSION}

This server pack does not redistribute other people's mods. `install` downloads NeoForge from maven.neoforged.net
and every mod from CurseForge's CDN, checking each file against the SHA-1 in `server-manifest.json`. Our own mods
(The Great Journey Core) are included in `mods/`. Mods that only draw things on a player's screen are left out.

## Install
1. Install Java 21 (Temurin, Microsoft or Oracle builds all work).
2. Linux / macOS: `./install.sh`   Windows: double-click `install.bat`
   (add `--accept-eula` to accept Minecraft's EULA without being asked).
3. Start: `./start.sh` or `start.bat`. The first start builds the Clan lands and takes a minute or two.

## Campaign or Free Mode
Before the FIRST start, choose how the world is played in `config/clanlands-common.toml`:
`newWorldMode = "free"` (the default: live among the Clans by the lake) or `newWorldMode = "campaign"` (the story
from the old forest to the lake). The world keeps the mode it was made with; to change it later, delete `world/`.

## Settings
- `server.properties`: normal Minecraft settings. Memory is in `user_jvm_args.txt` (6 GB default, 4 GB minimum).
- `config/` and `moonlight-global-datapacks/` are the pack's own settings; they match the client pack of the same
  version. Players need the client pack of the same version to join.
- Give yourself operator once: `op <name>` in the console.

## Updating
Unzip the newer server pack over this folder and run `install` again: it fetches only files whose checksum changed,
moves jars this version no longer ships to `mods-removed/` (a mod you added yourself stays in `mods/`), and keeps
`world/`, `server.properties`, `eula.txt` and `user_jvm_args.txt`.
'''

REQUIRED = ("server-manifest.json", "server-mods.txt", "bundled-mods.txt", "install.sh", "install.ps1", "install.bat",
            "start.sh", "start.bat", "server.properties.default", "user_jvm_args.default.txt", "README-SERVER.md",
            "config/clanlands-common.toml")


def verify_server(zip_path: Path) -> None:
    """Fails loudly if the archive is not a complete, clean server pack."""
    with zipfile.ZipFile(zip_path) as z:
        names = set(z.namelist())
        missing = [n for n in REQUIRED if n not in names]
        if missing:
            raise SystemExit(f"server pack is missing {missing}")
        manifest = json.loads(z.read("server-manifest.json"))
        jars = sorted(n for n in names if n.endswith(".jar"))
        if jars != sorted("mods/" + b for b in manifest["bundled"]):
            raise SystemExit(f"server pack jars {jars} differ from its bundled list {manifest['bundled']}")
        if any("qa" in j.lower().split("/")[-1].split("-")[0] for j in jars):
            raise SystemExit("server pack contains the test-only qa mod")
        bad = [n for n in names if SECRETISH.search(n.rsplit("/", 1)[-1]) and n != "install.ps1"]
        if bad:
            raise SystemExit(f"server pack contains tooling or secrets: {bad}")
        if any(n.startswith("config/fancymenu") for n in names):
            raise SystemExit("server pack contains the client-only FancyMenu config")
        lines = z.read("server-mods.txt").decode().split("\n")
        rows = [l.split("|") for l in lines if l]
        files = manifest["files"]
        if [(str(f["fileID"]), f["filename"], f["sha1"]) for f in files] != [tuple(r) for r in rows]:
            raise SystemExit("server-mods.txt and server-manifest.json disagree")
        for f in files:
            if not (type(f["projectID"]) is int and type(f["fileID"]) is int and re.fullmatch(r"[0-9a-f]{40}", f["sha1"])):
                raise SystemExit(f"bad manifest row {f}")
        tagged = client_only_names()
        client = [f["filename"] for f in files if is_client_only(f["filename"], tagged)]
        if client:
            raise SystemExit(f"server pack would install client-only mods: {client}")
        for sh in ("install.sh", "start.sh"):
            if b"\r\n" in z.read(sh):
                raise SystemExit(f"{sh} has Windows line ends; bash would fail on it")
            if (z.getinfo(sh).external_attr >> 16) & 0o111 == 0:
                raise SystemExit(f"{sh} is not executable")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=str(ROOT / "dist"))
    ap.add_argument("--unreleased-core", action="store_true",
                    help="test only: bundle dist/'s Core even though pack/core-release.json does not record it yet")
    args = ap.parse_args()

    mc, neo, version = toml_value("minecraft"), toml_value("neoforge"), toml_value("version")
    core_ver = gradle_version()
    core = ROOT / "dist" / CORE_JAR.format(ver=core_ver)
    if not core.is_file():
        raise SystemExit(f"{core} not found: run tools/build_core_jar.py first")
    release = ROOT / "pack/core-release.json"
    if not args.unreleased_core:
        problems = release_problems(core_ver)
        if problems:
            raise SystemExit("Refusing: " + "; ".join(problems))
        rel = json.loads(release.read_text(encoding="utf-8"))
        if hashlib.sha1(core.read_bytes()).hexdigest() != rel["sha1"]:
            raise SystemExit(f"{core.name} is not the jar recorded as CurseForge file {rel['fileId']} (SHA-1 differs)")

    entries = json.loads((ROOT / "pack/mods.json").read_text(encoding="utf-8"))
    tagged = client_only_names()
    files, skipped = [], []
    for e in entries:
        if e["classId"] != 6:                               # resource packs are for players' screens
            continue
        if is_client_only(e["fileName"], tagged):           # a client renderer on a dedicated server is a boot crash
            skipped.append(e["fileName"])
            continue
        files.append({"projectID": e["projectId"], "fileID": e["fileId"], "filename": e["fileName"], "sha1": e["sha1"]})
    files.sort(key=lambda f: f["filename"].lower())
    if len({f["filename"] for f in files}) != len(files):
        raise SystemExit("two mods share a filename")

    def fill(t: str) -> str:
        return t.replace("{MC}", mc).replace("{NEO}", neo).replace("{VERSION}", version)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"{ZIP_STEM}-Server-{version}{'-test' if args.unreleased_core else ''}.zip"

    def put(z: zipfile.ZipFile, name: str, data: bytes, mode: int = 0o644) -> None:
        info = zipfile.ZipInfo(name, EPOCH)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = mode << 16
        z.writestr(info, data)

    overrides = ROOT / "pack/overrides"
    with zipfile.ZipFile(zip_path, "w") as z:
        put(z, "server-manifest.json", (json.dumps({
            "minecraft": mc, "neoforge": neo, "pack": PACK_NAME, "version": version,
            "bundled": [core.name], "files": files}, indent=2) + "\n").encode())
        put(z, "server-mods.txt", "".join(f"{f['fileID']}|{f['filename']}|{f['sha1']}\n" for f in files).encode())
        put(z, "bundled-mods.txt", f"{core.name}\n".encode())
        put(z, "mods/" + core.name, core.read_bytes())
        for d in OVERRIDE_DIRS:
            for p in sorted((overrides / d).rglob("*")):
                rel = p.relative_to(overrides).as_posix()
                if not p.is_file() or p.name.startswith(".") or SECRETISH.search(p.name):
                    continue
                if any(rel == c or rel.startswith(c + "/") for c in CLIENT_ONLY_CONFIG):
                    continue
                put(z, rel, p.read_bytes())
        put(z, "config/clanlands-common.toml", CLANLANDS_COMMON.encode())
        put(z, "install.sh", fill(INSTALL_SH).encode(), 0o755)
        put(z, "install.ps1", fill(INSTALL_PS1).replace("\n", "\r\n").encode())
        put(z, "install.bat", INSTALL_BAT.replace("\n", "\r\n").encode())
        put(z, "start.sh", fill(START_SH).encode(), 0o755)
        put(z, "start.bat", fill(START_BAT).replace("\n", "\r\n").encode())
        put(z, "server.properties.default", SERVER_PROPERTIES.encode())
        put(z, "user_jvm_args.default.txt", USER_JVM_ARGS.encode())
        put(z, "README-SERVER.md", fill(README).encode())
        put(z, "NOTICE.txt", (overrides / "NOTICE.txt").read_bytes())
    verify_server(zip_path)
    print(f"Wrote {zip_path} ({len(files)} mods from CurseForge, {len(skipped)} client-only left out, Core bundled)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
