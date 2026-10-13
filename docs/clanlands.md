# Clanlands: the book worlds

Clanlands builds every new world around the Clan territories from the Warriors books. Canon facts and layout ranges are in [book-geography.md](book-geography.md).

## What a world gets
- **The lake territories at spawn.**
  - An irregular lake with the Gathering island and its fallen-tree bridge.
  - ShadowClan pines (north-west), ThunderClan woods (north-east), RiverClan marsh and streams with the outflow river (south-west), WindClan moor (south-east).
  - The horseplace between RiverClan and WindClan.
  - The Moonpool at the head of the ThunderClan/WindClan border stream, in the hills to the north-east.
  - New players start on the ThunderClan shore by the Sky Oak.
- **The old forest, 8–10 km to the south-east.**
  - Fourtrees at the centre, with four great oaks and the Great Rock.
  - The Thunderpath between ShadowClan and ThunderClan.
  - The river: off WindClan's moor in a thirty-block waterfall into a deep rock gorge, then out across the plains past Sunningrocks and the RiverClan island camp.
  - WindClan's high moor to the west.
  - Further out: Tallpines and the Twolegplace, Barley's farm, and Highstones with Mothermouth and the Moonstone.
- **Everywhere else**, the pack's normal world. No giant mountains.

## Seeds
Every size, angle, border wiggle, shoreline, river bend and landmark spot comes from the world seed, through a named stream per value (`Geo.Rand`). The same seed always rebuilds the same map; two seeds never match. The books' arrangement is fixed: which Clan is where, who holds which landmark, and the lake-to-forest direction.

## How it works
- **Plan:** `layout/LakeLands` and `layout/OldForest` answer, for any column, the zone, the planned ground height, how strongly the map applies (1 inside, fading to 0 over 200 blocks at the edge) and the terrain smoothness.
- **Terrain:** `world/WorldgenPatcher` rewrites the overworld's references to vanilla's surface offset, jaggedness and factor as the data packs load, blending them toward the plan by its weight. Caves, ores, aquifers and surface rules stay vanilla.
- **Biomes:** `mixin/MultiNoiseBiomeSourceMixin` returns each zone's biome above ground inside the maps. Zones use existing pack biomes (first one installed wins), so spawns, colours, seasons and decoration behave normally. Wild Clan cats are enabled there through Warrior Cats Events' `mod_spawn_biomes` tag.
- **Landmarks:** `landmark/Landmarks` is one feature that runs once per chunk at the end of decoration and paints the part of each landmark that falls in that chunk. Each landmark draws its shape from its own seed. It runs only in the Clan lands' own overworld.
- **Streams and falls:** `landmark/Waters` digs each channel above sea level to its planned bed and fills it to the planned surface, never above the banks beside it. At each step down it finds the real brink (the noise moves it a few blocks from the plan) and hangs a curtain of falling water there. A river stretch that starts at the foot of another's fall is marked `continuing()`, so the lower stretch never wears back the lip of the fall above it.
- **Roads:** Thunderpaths are graded to their planned bed. Where the bed runs more than six blocks over the ground (a ravine, a stream's gully) or over water, the road becomes a stone bridge with low walls and a pier every twelve blocks, never an earth dam. Culverts run on as open cuts until the land is low, so both mouths open. Clearing above a road keeps any block that holds a stream's water.
- **Lava:** `landmark/Lava` turns all lava from y 50 up inside the Clan lands to stone, and every bit of lava around the tunnels down to below the cavern floor. Lava in deep ordinary caves is left alone.
- **Safe camps:** `world/CampSanctuary` stops natural and worldgen spawns of monsters and of the `clanlands:camp_predators` tag (foxes, wolves, badgers, eagles and similar) inside every camp. Warrior Cats Events makes foxes attack cats, and taiga foxes spawning on the ShadowClan camp floor killed its cats on the first load. The Clans' own fox and badger events still happen.
- **Tunnels:** a tunnel never drops more than 0.7 blocks a block, so a cat can walk back up. Where the real ground stands over a mouth, a ramp is cut back out of it. The stream-side mouth sits on the bank just outside the stream's ravine, on the cavern's side.
- **Camps** (`landmark/CampPlan` plans them, `landmark/Camps` paints them): every camp has one way in, a natural barrier everywhere else, and dens by what they are for. The Clan's cats are settled into their own dens (nursery for queens and kits, warriors' den for the deputy and warriors...) from the same plan, so nests and dens always line up. Lake ThunderClan's stone hollow: sheer quarry walls, a cut climbing the wall to a thorn tunnel, Highledge with the leader's cave behind it and tumbled rocks up to it, the elders in honeysuckle beneath, the medicine cleft with its pool behind a bramble screen, a bramble nursery. Old-forest ThunderClan: a bramble-and-gorse ring at the foot of a rocky ravine, the gorse tunnel at the bottom of the ravine path, Highrock with the leader's den at its foot, Spottedleaf's fern tunnel to a cleft in a great rock, apprentices by a stump, elders under a fallen tree. WindClan: a hollow ringed by gorse with one gap, Tallrock with climbing stones, every den a burrow dug into the hollow's sides. RiverClan: a wall of bramble and willow lined with reeds where it meets water (the water is the wall where it reaches the camp); the old forest's island is made dry ground with a ford of stones to the nearest bank; willows lean over. ShadowClan: a dense bramble wall with one tunnel, tall pines in the wall keeping it dim, bramble-thicket dens, the elders under a fallen pine's roots, the leader in a hollow under a great rock; the old forest's is marshy with puddles. Gates face easy ground (no stream, ravine or road just outside); old-forest camps are moved clear of the river. `CampGameTests` checks one way in, an open middle and fitting dens on six seeds.
- **Territory names:** the action bar names the territory as a player crosses into it.

## How a world is played
The title screen's Singleplayer button becomes **Campaign** and **Free Mode** side by side, with **Load World** (the usual world list) under them; Realms is removed. Campaign and Free Mode open the normal create-world screen with the mode remembered, and the world keeps it for good (`life/Mode`). Dedicated servers, and worlds made without a button, use `newWorldMode` in `clanlands-common.toml` (`free` by default). Worlds from before modes existed are Free.

- **Free Mode:** the Clans already live at the lake. A new cat has the StarClan dream, then picks a start: a kittypet at the forest edge, join a Clan, or a loner by the lake.
- **Campaign** (`life/campaign/`): the story in the books' order.
  1. **The dream tutorial** (`life/dream/`): the same short, skippable StarClan lesson as in Free Mode.
  2. **The Gathering at Fourtrees:** the player wakes on the rim of Fourtrees in the old forest under the full moon, with the four leaders on the Great Rock.
  3. **The three starts**, all in the old forest: a kittypet in the Twoleg nest at the forest's edge, join one of the four Clans, or a loner by the farm near Highstones. Kittypets and loners have no Clan yet: `campaign/Outsiders` watches them, and once one has wandered 64 blocks from where it began into a Clan's land, a patrol of three cats steps out and asks it to join. A kittypet's first patrol (ThunderClan, by the Twoleg garden) is the one that finds Rusty. A cat who says "not yet" is asked again after a day, by any Clan whose land it crosses.
  4. **The four acts**, which belong to the world (each step happens once): the old forest, then its end (Twoleg monsters fell it stretch by stretch, StarClan sends a sign, a cat hears Midnight), then the Great Journey (highway, farm, mountain pass, the Tribe of Rushing Water, the lake), then the lake (new camps, the Moonpool, the first Gathering on the island), after which play is free. Chapters belong to each player, and a player who joins late starts at the chapter that fits the world.
  - A cat who arrives while the Clans are away on the journey has one way to begin: walk with them. Once the Clans live at the lake, the same three starts begin there.
  - `/greatjourney campaign` has `status`, `join`, `wake` and (ops) `act` for testing.
- Planned, not built: recurring Gatherings at the full moon in Free Mode, and Moonpool dreams. In the Campaign the Moonpool and the island Gathering are one-off chapters.

## Guided tour
`/greatjourney tour` (anyone; `tour stop` ends it) plays a self-running tour of the world in the books' order: the old forest, the Great Journey, then the lake. The player is made a spectator with night vision (`life/tour/Tour`); the server carries them from stop to stop so the land loads, and their own client flies the camera and shows the captions (it needs the mod on the client). Their place, game mode and facing are saved first and put back when the tour ends, is stopped, or on the next login after leaving mid-tour. A tour that hears nothing from its client for 75 seconds ends.

Stops come from the running world's own layout (`TourStops`), so a place the world lacks is left out: Fourtrees, the ThunderClan camp, Sunningrocks and the stepping stones, the RiverClan camp, the Thunderpath (a monster drives past), ShadowClan and Carrionplace, the WindClan moor, the gorge, the tunnels, Mothermouth, Barley's farm; then the Great Thunderpath, the Tribe's cave, Midnight's sett, the first sight of the lake; then the island, RiverClan's marsh, the stone hollow, WindClan's hills, ShadowClan's pines, the Moonpool and the Twoleg nest.

## Monsters on the Thunderpaths (Traffic)
`life/traffic/` sends Twoleg "monsters", cars and long trucks, along the roads. They are not mobs: each is a set of block displays (painted body, glass, wheels, lamps) moved together. About once a second, each stretch of road with a cat (player or Clan cat) within 28 blocks of its edge may roll a monster, which starts out of sight, growls closer, roars past and is gone. The great highway is busy, the forest Thunderpaths now and then, the old Thunderpath never. Monsters hurt what they hit (14 damage, seven hearts, by default), scare Clan cats off the road, keep a gap behind slower ones, and are never saved (leftovers from a crash are removed on load). Settings are in the per-world `serverconfig/clanlands-traffic-server.toml`: `enabled`, `thunderpathChance` (1/12), `highwayChance` (1/3), `damage`, `maxMonsters` (24), `maxPerRoad` (6). Ops can use `/greatjourney monster spawn|spawnat|count|clear`.

## Checking a layout
- `./gradlew :clanlands:runVerification` draws each test seed's plans to `mods/clanlands/build/verification/clanlands-maps/`.
- `tools/full_pack_server.py --seed N --command "forceload add ..." --hold 600` generates real chunks.
- `tools/render_region_map.py build/server-harness/world/region out.png` shows what actually generated.
- `tools/render_slice.py <region> out.png --through X Z` cuts a side view along the stream at X Z: the way to check a waterfall.
- `tools/scan_region.py <region> --box X0 Z0 X1 Z1` lists lava open to the air, water standing over air or with an open side, and chunk-border seams.
- `tools/inspect_world.py` boots the full pack headless on a few seeds (Free, plus a Campaign boot that checks only the spawn), forceloads every landmark, then writes `build/inspect/<run-id>/report.html` with a top-down tile per landmark, side slices (camps through their way in, waterfalls, bridges, culverts, tunnel mouths) and automatic checks: one way out of each camp, the island cut off by water but walkable over the tree-bridge, tunnels reaching the cavern, a column of water at each fall, roads and bridges, and a standable spawn. `--reuse --run-id R` re-analyses worlds already generated. Never point it at the hub.
- `tools/playtest.py --suite smoke|free-main|free-starts|campaign|mp` plays real full-pack clients in the background (the `mp` suite uses a dedicated server and two clients) and writes `build/playtest/<run>/report.html`; `--report DIR` only rebuilds a report.
- `tools/gradle_locked.py :clanlands:runVerification` runs Gradle through a lock, so helpers sharing the tree never build at once.
