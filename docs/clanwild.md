# Clanwild

Clanwild (`mods/clanwild`, mod id `clanwild`) holds the code and data The Great Journey needs around
Warrior Cats Events (WCE). It replaces two 1.20.1 mods that have no 1.21.1 build, Dynamo and Alex's Mobs - Naturalist
Compat. Both were rewritten from scratch, working only from their CurseForge descriptions; nothing from either mod was
copied.

## Speed strike (replaces Dynamo)

A melee hit from a player gets extra damage based on the player's horizontal speed. Players turned into cats by
Woodwalkers count too.

- The server measures each player's speed from tick to tick, because it can't trust the speed the client reports. A
  hit uses the fastest of the last 4 ticks, so a WCE leap still counts when the swing lands just after it.
- One tick of movement over 3 blocks is treated as a teleport and ignored.
- The bonus only applies to the `player_attack` damage type when the player struck the hit directly. Arrows, thorns and
  pets get no bonus.
- Settings are in `config/clanwild-common.toml`, section `[speed_strike]`:

  | Setting | Default | Meaning |
  |---|---|---|
  | `enabled` | `true` | Turns the speed strike on or off |
  | `threshold` | `0.22` | Speed in blocks per tick below which there's no bonus (walking is about 0.22) |
  | `bonusPerSpeed` | `2.0` | Extra damage, as a fraction of the hit, per block per tick above the threshold |
  | `maxBonus` | `0.5` | The largest bonus (+50%) |

  With the defaults, a sprint (0.28) adds 12%, a sprint-jump or leap (0.40) adds 36%, and anything past 0.47 adds 50%.

## Alex's Mobs and Naturalist overlap (replaces Alex's Mobs - Naturalist Compat)

These are the animals both mods add, comparing Alex's Mobs Unofficial Port 1.22.17 with Naturalist 2.0.5 (NeoForge
1.21.1). Spawns are removed with `neoforge:remove_spawns` biome modifiers that load only when both mods are present
(`data/clanwild/neoforge/biome_modifier/`). The removed animals still exist, so spawn eggs and saved mobs keep working.

| Animal | Kept | Spawns removed | Why |
|---|---|---|---|
| Brown bear | Alex's Mobs grizzly bear | `naturalist:bear` | Same choice as the 1.20.1 pack's compat mod (less change for players). Naturalist's black bear stays and still drops Naturalist fur. |
| Rhino | Alex's Mobs rhinoceros | `naturalist:rhino` | Same choice as the 1.20.1 compat mod |
| Elephant | Alex's Mobs elephant | `naturalist:elephant` | Same choice as the 1.20.1 compat mod |
| Catfish | Alex's Mobs catfish | `naturalist:catfish` | Same choice as the 1.20.1 compat mod. Both raw catfish are tagged prey and `c:foods/raw_fish`. |
| Komodo dragon | Naturalist | `alexsmobs:komodo_dragon` | New overlap on 1.21.1; Naturalist is preferred |
| Tiger | Neither | `alexsmobs:tiger`, `naturalist:tiger` | New overlap on 1.21.1. Naturalist's tiger was kept at first; later all tigers were removed (see below) |
| Blobfish | Naturalist | `alexsmobs:blobfish` | New overlap on 1.21.1; Naturalist is preferred |
| Snakes | Both | none | Naturalist's snake is the forest adder that Warriors lore needs (Snakerocks). Alex's Mobs' rattlesnake lives in deserts, and the 1.20.1 compat's rattlesnake removal targeted an entity Naturalist no longer has. |

For the four animals the 1.20.1 compat mod already handled, we kept its Alex's Mobs choice. This goes against the
general "prefer Naturalist" rule, because the owner asked for the port to change as little as possible. Naturalist
2.0.5's teddy bear no longer uses fur, so no recipe needed redirecting.

Tags: Alex's Mobs still uses the 1.20 tag names `c:raw_meat` and `c:raw_fish`. Clanwild also adds its foods to the 1.21
tags `c:foods/raw_fish`, `c:foods/raw_meat`, `c:foods/cooked_fish` and `c:foods/cooked_meat`. Every entry is
`required: false`, so if a mod is missing the tag still loads.

## Only Warrior Cats creatures

Kiki's playtest (2026-10-17) asked for only Warrior Cats mobs, with no tigers and no zombies or skeletons.

- **Big game:** the entity tag `clanwild:out_of_place` lists Naturalist's tiger, lion, zebra, giraffe, hippo,
  elephant, rhino, mammoth and ostrich, plus (2026-10-18) bear, black bear, alligator, komodo dragon, both scorpions
  and the piranha. `remove_out_of_place_wildlife.json` removes their spawns (it loads whenever
  Naturalist is present), and any already saved in a world is removed when it loads. Alex's Mobs' own big cats and
  safari animals already have their spawn weights set to 0 in `overrides/config/alexsmobs-common.toml`.
- **Monsters (`NoMonsters`):** a monster (`MobCategory.MONSTER`, from any mod) never spawns on its own outside the
  Nether and the End. That covers natural spawning, new land, spawners and trial spawners, structures, pillager
  patrols, phantoms, reinforcements and jockeys. Commands, spawn eggs and our own events still make them. Badgers,
  foxes, wolves and eagles are creatures, not monsters, so they still come. To turn monsters back on, set
  `[monsters] spawn = true` in `config/clanwild-common.toml`.
- **Fewer dangerous animals (`Wildlife`, 2026-10-18):** Kiki met too many, and a golden eagle struck at her without
  end. The tag `clanwild:dangerous` (WCE badger and golden eagle, wolf, fox, polar bear, Naturalist snake and boar,
  Alex's Mobs grizzly and rattlesnake) keeps only `[dangerous_animals] share` (0.3) of their natural and new-land
  spawns, and none spawns on its own within `spacing` (48) blocks of another. A wild bird of prey (`clanwild:raptors`:
  WCE's golden eagle) never carries a player off (that only ended in a drop from the sky), and after striking a
  player once it climbs away and leaves players alone for `raptorCalmSeconds` (300). Tamed eagles are left alone.
  Naturalist's bears, black bears, alligators, komodo dragons, scorpions and piranhas joined `clanwild:out_of_place`.
- **More rabbits (2026-10-19):** the playtest found too few rabbits. `more_rabbits_moors.json` adds rabbits (weight 14,
  2 to 4) to the open land in `clanwild:rabbit_moors` (BoP moor, highland, grassland and pasture, meadow, plains: WindClan's
  moor and RiverClan's fields), and `more_rabbits_woods.json` adds a few (weight 6, 2 to 3) to the woods and pines in
  `clanwild:rabbit_woods`. Rabbits are creatures, so they also count toward the creature cap and come back as the land
  is hunted out. Every tag entry is `required: false`.

## Warrior Cats Events tags

WCE reads data tags, so Clanwild adds entries to them (all `required: false`):

- `warriorcats_events:prey` (items: the fresh-kill pile, food bowls, herb crafting): `naturalist:morsel`, `duck`,
  `drumstick`, `venison`, `bushmeat`, `lizard_tail`, `bass`, `catfish`, `alexsmobs:raw_catfish`, `alexsmobs:moose_ribs`.
- `warriorcats_events:additional_prey` (entities: wild cats hunt them, and Sharp Scent shows them as prey):
  `naturalist:bird`, `rat`, `mole`, `duck`, `lizard`, and `alexsmobs:crow`, `blue_jay`, `jerboa`.
- `warriorcats_events:predator_mobs` (entities: Sharp Scent outlines them as danger; nothing else uses this tag):
  `minecraft:fox`, `minecraft:wolf`, `naturalist:bear`, `black_bear`, `snake`, and `alexsmobs:grizzly_bear`,
  `bald_eagle`, `rattlesnake`.

Not done yet: WCE's `worldgen/biome/mod_spawn_biomes` tag (where its wild cats spawn) only lists Wythers biomes. Adding
the BoP and BWG forests would make wild cats spawn there too. That is a design call for the pack, so it isn't included
here.

## Prey items config

For `overrides/config/warriorcats_events-prey_items.toml`, which lists items that feed more than their vanilla value:

```toml
[items]
	#Items that are considered prey and should feed more than usual
	#Use the same format shown below
	items = ["minecraft:salmon", "minecraft:cod", "naturalist:bass", "naturalist:catfish", "alexsmobs:raw_catfish", "naturalist:morsel", "naturalist:lizard_tail"]
```

## Tests

`./gradlew :clanwild:runVerification` runs 6 GameTests against the real Naturalist 2.0.5 and Alex's Mobs port jars the
pack pins. These are pulled from CurseMaven as runtime-only dependencies and are never shipped. The tests check:

- the speed-strike math;
- that a leap makes a real hit do +50% and a teleport is ignored;
- that no removed animal spawns in any biome, while grizzly bears and black bears still do;
- that monsters are turned away from natural, spawner and patrol spawns but not from commands or spawn eggs, that
  foxes, wolves and badgers still spawn, and that the Nether keeps its own;
- that the dangerous-animal tag holds wolves and foxes but not rabbits, that bears and alligators are out of place, and
  that no danger spawns beside another;
- that rabbits spawn often in meadows, plains and forests;
- that the WCE and `c:` tag entries resolve.
