# Configs

The Great Journey ships no configuration from any other pack. Every shipped config is the mod's own generated default, plus the few changes listed with reasons in `pack/config-choices.json`. Mods that aren't listed run on their own defaults; client-only mods create theirs on first launch.

## Regenerating
1. Boot a clean server with no overrides, then save its defaults:
   - temporarily move `pack/overrides/config` aside;
   - `python tools/full_pack_server.py --stage build/server-defaults --core-jar dist/greatjourney_core-<ver>.jar --settle 10`;
   - copy `build/server-defaults/config` to `build/config-defaults`.
2. Write the overrides:
   - `python tools/apply_config_choices.py` writes `pack/overrides/config/...`;
   - `--check` only verifies that every chosen key exists.
3. Boot again with the overrides. A correct result logs no "Incorrect key" and leaves every shipped file byte-identical.

## Current choices
| File | Key | Value | Why |
|---|---|---|---|
| dynamicwaters-common.toml | `[Generation] enableMountainRivers` | false | Mountain-river planning held new-world creation for minutes |
| warriorcats_events-server.toml | `[wce_server] allowTeleport` | false | New cats begin where The Great Journey's start puts them |
| warriorcats_events-server.toml | `[wce_server.wce_territories] placeBreakBlocks` | false | The Clans hold the whole lake map; visitors can still build there |
| warriorcats_events-prey_items.toml | `[items] items` | vanilla, Naturalist and Alex's Mobs fish and meats | Wildlife-mod prey counts as prey |
| alexsmobs-common.toml | `[general] giveBookOnStartup` | false | New players started holding the Animal Dictionary, which swallowed right-clicks on Clan cats, so talking did nothing (found by the playtest harness) |
| alexsmobs-common.toml | `[spawning]` 45 `*SpawnWeight` keys | 0 | Exotic animals don't live in the Clans' woods and moors |
| Weather2/Tornado.toml | `[general] Storm_NoTornadosOrCyclones` | true | No tornadoes or hurricanes; rain, thunder and wind stay |
| fancymenu/options.txt (client) | `modpack_mode`, `show_customization_overlay`, `show_welcome_screen` | true, false, false | A new player saw FancyMenu's editor bar and its "Welcome to FancyMenu!" popup over the title menu (found by the playtest harness); not a TOML, so it ships as FancyMenu's own generated file with these three values changed, outside apply_config_choices |
| lmft.json | `disableIngameError`, `showToOnlyPrivileged` | true, true | Load My F***ing Tags posted its (sweary) name and "some tags are a bit cooked" into every player's chat on joining a world, over Warrior Cats Events' own spawn-biome tag naming biomes the pack does not have (found by the playtest harness); it still repairs the tag and logs it. A JSON file, so outside apply_config_choices |

Warrior Cats Events writes `warriorcats_events-server.toml` to `config/`, not to the per-world `serverconfig/`, so it ships in `config/`.
