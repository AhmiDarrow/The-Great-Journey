#!/usr/bin/env python3
"""Write the pack's config overrides: each mod's own default config plus the choices in pack/config-choices.json.

    python tools/apply_config_choices.py            # build/config-defaults + choices -> pack/overrides/config/...
    python tools/apply_config_choices.py --check    # exit 1 if any chosen key is missing from the defaults

The defaults come from a clean server boot with no overrides:
    python tools/full_pack_server.py --stage build/server-defaults --core-jar dist/greatjourney_core-<ver>.jar
    then copy build/server-defaults/config to build/config-defaults.
Only files that carry a choice are written; every other config is left to the mod's own default. TOML edits are
line-level replacements inside the right [section], so comments and layout stay exactly as the mod wrote them.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = ROOT / "build/config-defaults"
OUT = ROOT / "pack/overrides"


def toml_value(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v)
    if isinstance(v, str):
        return json.dumps(v)
    if isinstance(v, list):
        return "[" + ", ".join(toml_value(x) for x in v) + "]"
    raise TypeError(f"unsupported value {v!r}")


def apply_toml(text: str, section: str, key: str, value) -> tuple[str, bool]:
    """Replaces `key = ...` inside [section] (nested sections are their own; [a.b] is not part of [a])."""
    lines = text.splitlines(keepends=True)
    current = None
    for i, line in enumerate(lines):
        m = re.match(r"\s*\[\[?\s*(.+?)\s*\]\]?\s*$", line)
        if m:
            current = m.group(1).strip()
            continue
        if current == section:
            m = re.match(r"(\s*)" + re.escape(key) + r"(\s*=\s*)(.*?)(\r?\n)?$", line)
            if m:
                lines[i] = f"{m.group(1)}{key}{m.group(2)}{toml_value(value)}{m.group(4) or ''}"
                return "".join(lines), True
    return text, False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    choices = json.loads((ROOT / "pack/config-choices.json").read_text(encoding="utf-8"))["files"]
    missing = []
    for rel, items in choices.items():
        src = DEFAULTS / Path(rel).relative_to("config")
        if not src.is_file():
            missing.append(f"{rel}: no generated default at {src}")
            continue
        text = src.read_text(encoding="utf-8", newline="")
        for c in items:
            for key in c.get("keys", [c.get("key")]):
                text, ok = apply_toml(text, c["section"], key, c["value"])
                if not ok:
                    missing.append(f"{rel}: [{c['section']}] {key} not found")
        if not a.check:
            dst = OUT / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(text, encoding="utf-8", newline="")
            print(f"wrote {dst.relative_to(ROOT)}")
    for m in missing:
        print("MISSING:", m)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
