#!/usr/bin/env python3
"""Upload a Great Journey file through the CurseForge Author Upload API.

    python tools/upload_curseforge.py dist/greatjourney_core-0.1.0.jar                 # the Core -> project 1807909
    python tools/upload_curseforge.py dist/TheGreatJourney-0.1.0.zip                   # the pack -> project 1807904
    python tools/upload_curseforge.py dist/TheGreatJourney-Server-0.1.0.zip --parent-file-id <pack file id>

Reads CF_AUTHOR_TOKEN (uploads) and CF_API_KEY (status checks) from tools/secrets/.env, or from Ninjacat Skies'
ignored secrets file (tools/cfapi.py); never prints them. Refuses:
  - a file meant for the other project (jars go to the Core project, zips to the pack project),
  - the test-only qa mod, a "-nocore" or "-test" build,
  - a pack whose manifest names files CurseForge has not approved yet (the Core first!),
  - a server pack not attached to a client pack file (--parent-file-id), or whose checks fail.
Adapted from Ninjacat Skies' tools/upload_curseforge.py.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from cfapi import secret  # noqa: E402

PACK_PROJECT = 1807904
CORE_PROJECT = 1807909
API = "https://minecraft.curseforge.com/api"
UA = "great-journey-uploader/1.0"
MC_TYPE = 77784          # Minecraft 1.21 version type
LOADER_TYPE = 68441      # mod loader version type
ENVIRONMENT_TYPE = 75208  # Client / Server labels
LIVE_FILE = {4, 10}      # Approved, Released
FILE_STATUS = {1: "Processing", 2: "ChangesRequired", 3: "UnderReview", 4: "Approved", 5: "Rejected",
               6: "MalwareDetected", 7: "Deleted", 8: "Archived", 9: "Testing", 10: "Released",
               11: "ReadyForReview", 18: "UnderManualReview"}


def core_api(path: str, api_key: str):
    sep = "&" if "?" in path else "?"
    req = urllib.request.Request(f"https://api.curseforge.com/v1{path}{sep}t={int(time.time())}",
                                 headers={"x-api-key": api_key, "Accept": "application/json", "User-Agent": UA,
                                          "Cache-Control": "no-cache", "Pragma": "no-cache"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def file_status(project_id: int, file_id: int, api_key: str):
    """The single-file endpoint can stay cached after approval; the project's file list is the live record."""
    try:
        st = (core_api(f"/mods/{project_id}/files/{file_id}", api_key).get("data") or {}).get("fileStatus")
    except urllib.error.HTTPError:
        st = None
    if st in LIVE_FILE:
        return st
    try:
        for row in core_api(f"/mods/{project_id}/files?pageSize=50", api_key).get("data") or []:
            if row.get("id") == file_id:
                return row.get("fileStatus")
    except urllib.error.HTTPError:
        pass
    return st


def require_hosted_files_approved(zip_path: Path, api_key: str) -> None:
    with zipfile.ZipFile(zip_path) as z:
        name = "server-manifest.json" if "server-manifest.json" in z.namelist() else "manifest.json"
        manifest = json.loads(z.read(name))
    bad = []
    for e in manifest.get("files", []):
        pid, fid = e["projectID"], e["fileID"]
        st = file_status(pid, fid, api_key)
        if st not in LIVE_FILE:
            bad.append(f"{e.get('filename', fid)} (project {pid} file {fid}: {FILE_STATUS.get(st, st)})")
    if bad:
        raise SystemExit("Refusing to upload: the manifest names files that are not Approved yet:\n  - " + "\n  - ".join(bad))
    print(f"All {len(manifest.get('files', []))} files the {name} names are live on CurseForge")


def resolve_version_id(versions, name: str, type_id: int) -> int:
    for v in versions:
        if v.get("name") == name and v.get("gameVersionTypeID") == type_id:
            return int(v["id"])
    raise SystemExit(f"Could not resolve game version '{name}' (type {type_id})")


def multipart(fields: dict[str, tuple[str, bytes, str]]) -> tuple[bytes, str]:
    boundary = "----gj" + uuid.uuid4().hex
    body = bytearray()
    for name, (filename, data, ctype) in fields.items():
        body += f"--{boundary}\r\n".encode()
        disp = f'Content-Disposition: form-data; name="{name}"'
        if filename:
            disp += f'; filename="{filename}"'
        body += (disp + "\r\n").encode()
        body += f"Content-Type: {ctype}\r\n\r\n".encode()
        body += data + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return bytes(body), boundary


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--release-type", choices=["alpha", "beta", "release"], default="beta")
    ap.add_argument("--display-name", default="")
    ap.add_argument("--changelog-file", default="")
    ap.add_argument("--parent-file-id", type=int, default=0,
                    help="attach as an additional file of this pack file (the server pack)")
    ap.add_argument("--dry-run", action="store_true", help="run every check, upload nothing")
    args = ap.parse_args()

    path = Path(args.file)
    if not path.is_file():
        raise SystemExit(f"Not found: {path}")
    name = path.name
    if "-nocore" in name or "-test" in name or "qa" in name.lower().split("-")[0].split("_"):
        raise SystemExit(f"Refusing: {name} is a local test build")
    is_jar = path.suffix.lower() == ".jar"
    is_server = name.startswith("TheGreatJourney-Server-")
    if is_jar:
        if not name.startswith("greatjourney_core-"):
            raise SystemExit("Only the Core jar is ours to upload")
        with zipfile.ZipFile(path) as z:
            if any("qa" in n.lower() for n in z.namelist() if n.startswith("META-INF/jarjar/")):
                raise SystemExit("Refusing: the Core contains the test-only qa mod")
        project = CORE_PROJECT
    elif path.suffix.lower() == ".zip" and name.startswith("TheGreatJourney-"):
        project = PACK_PROJECT
        if is_server and not args.parent_file_id:
            raise SystemExit("The server pack must ride on a client pack file: pass --parent-file-id <its file id>")
        if not is_server and args.parent_file_id:
            raise SystemExit("--parent-file-id is only for the server pack")
        if is_server:
            from export_server_pack import verify_server
            verify_server(path)
        else:
            with zipfile.ZipFile(path) as z:
                m = json.loads(z.read("manifest.json"))
            if not any(f["projectID"] == CORE_PROJECT for f in m["files"]):
                raise SystemExit("Refusing: the pack's manifest does not install the Core")
    else:
        raise SystemExit(f"Not a Great Journey file: {name}")

    api_key = secret("CF_API_KEY")
    token = secret("CF_AUTHOR_TOKEN")
    if not is_jar:
        require_hosted_files_approved(path, api_key)

    display = args.display_name or path.stem.replace("greatjourney_core-", "The Great Journey Core ").replace(
        "TheGreatJourney-Server-", "The Great Journey Server Pack ").replace("TheGreatJourney-", "The Great Journey ")
    changelog = Path(args.changelog_file).read_text(encoding="utf-8") if args.changelog_file else f"## {display}\n"

    req = urllib.request.Request(API + "/game/versions", headers={"X-Api-Token": token, "Accept": "application/json", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        versions = json.loads(r.read().decode("utf-8"))
    meta = {"changelog": changelog, "changelogType": "markdown", "displayName": display, "releaseType": args.release_type}
    if args.parent_file_id:
        meta["parentFileID"] = args.parent_file_id         # an additional file inherits its parent's game versions
    else:
        gv = [resolve_version_id(versions, "1.21.1", MC_TYPE), resolve_version_id(versions, "NeoForge", LOADER_TYPE)]
        if is_jar:                                          # our mods run on both sides
            gv += [resolve_version_id(versions, "Client", ENVIRONMENT_TYPE), resolve_version_id(versions, "Server", ENVIRONMENT_TYPE)]
        meta["gameVersions"] = gv
    print(f"{'Would upload' if args.dry_run else 'Uploading'} {name} ({path.stat().st_size // 1024} KiB) -> project {project}"
          f" as {args.release_type} \"{display}\"" + (f", attached to file {args.parent_file_id}" if args.parent_file_id else ""))
    if args.dry_run:
        return 0
    body, boundary = multipart({
        "metadata": ("", json.dumps(meta).encode("utf-8"), "application/json"),
        "file": (name, path.read_bytes(), "application/java-archive" if is_jar else "application/zip"),
    })
    req = urllib.request.Request(f"{API}/projects/{project}/upload-file", data=body, method="POST", headers={
        "X-Api-Token": token, "Accept": "application/json", "User-Agent": UA,
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            resp = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Upload failed: HTTP {e.code} {e.read().decode('utf-8', 'ignore')[:500]}")
    file_id = json.loads(resp).get("id")
    if not file_id:
        raise SystemExit(f"Upload response has no file id: {resp[:300]}")
    print(f"OK - CurseForge file id={file_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
