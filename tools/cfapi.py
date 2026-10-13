"""Tiny CurseForge Core API client. The key is read from the Ninjacat repo's ignored secrets file (or CF_API_KEY)."""
from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path

SECRET_FILES = (
    Path(__file__).resolve().parent / "secrets/.env",
    Path.home() / "Projects/ninjacat-skies/tools/secrets/.env",
)
NEOFORGE = 6


def secret(name: str) -> str:
    if os.environ.get(name):
        return os.environ[name]
    for f in SECRET_FILES:
        if f.is_file():
            for line in f.read_text(encoding="utf-8").splitlines():
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip().strip('"')
    raise SystemExit(f"{name} not found in {', '.join(map(str, SECRET_FILES))}")


def api(path: str, body=None):
    headers = {"x-api-key": secret("CF_API_KEY"), "Accept": "application/json", "User-Agent": "great-journey-tools"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request("https://api.curseforge.com" + path, headers=headers, data=data)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)["data"]
