#!/usr/bin/env python3
"""Lang gate: every translation key our code uses has an English line, and no English line is left unused.

    python tools/gates/check_lang.py

Keys used: string literals in mods/*/src/main/java that look like our keys (they name clanlands, clanwild or
greatjourney: menu.clanlands.free, key.categories.greatjourney...), item keys for every item a DeferredRegister
registers (item.<mod>.<id>), and death messages for every damage type in data/<mod>/damage_type
(death.attack.<message_id> and its ".player" form). Exits 1 and lists what is missing or orphaned.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OURS = ("clanlands", "clanwild", "greatjourney")
LITERAL = re.compile(r'"((?:[a-z0-9_]+\.)+[a-z0-9_]+)"')
ITEM_REG = re.compile(r'ITEMS\.register(?:SimpleItem|Item)?\("([a-z0-9_]+)"')


def used_keys(mod_dir: Path, mod_id: str) -> set[str]:
    keys: set[str] = set()
    for java in (mod_dir / "src/main/java").rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        for k in LITERAL.findall(text):
            parts = k.split(".")
            if any(p in OURS for p in parts) and parts[0] in (
                    "menu", "key", "screen", "item", "block", "entity", "gui", "tooltip", "death", "subtitles",
                    "effect", "chat", "message", "config", "itemGroup", "advancements"):
                keys.add(k)
        for item in ITEM_REG.findall(text):
            keys.add(f"item.{mod_id}.{item}")
    for dt in (mod_dir / "src/main/resources/data").glob("*/damage_type/*.json"):
        msg = json.loads(dt.read_text(encoding="utf-8")).get("message_id")
        if msg:
            keys.update({f"death.attack.{msg}", f"death.attack.{msg}.player"})
    return keys


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    bad = 0
    for mod_dir in sorted(p for p in (ROOT / "mods").iterdir() if (p / "src/main/java").is_dir()):
        mod_id = mod_dir.name
        if mod_id == "qa":          # the playtest harness: never shipped, and it names clanlands' keys to find buttons
            continue
        lang_file = mod_dir / f"src/main/resources/assets/{mod_id}/lang/en_us.json"
        lang = json.loads(lang_file.read_text(encoding="utf-8")) if lang_file.is_file() else {}
        used = used_keys(mod_dir, mod_id)
        missing = sorted(k for k in used if k not in lang)
        orphaned = sorted(k for k in lang if k not in used)
        for k in missing:
            print(f"MISSING  {mod_id}: {k}")
        for k in orphaned:
            print(f"ORPHANED {mod_id}: {k}")
        bad += len(missing) + len(orphaned)
        print(f"{mod_id}: {len(used)} keys used, {len(lang)} English lines")
    print("PASS lang gate" if bad == 0 else f"FAIL lang gate ({bad} problems)")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
