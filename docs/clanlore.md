# Clan Lore: the Warrior Cats Events addon

Clan Lore (`mods/clanlore`, mod id `clanlore`, package `com.ninjacat.greatjourney.clanlore`) adds what Warrior Cats Events (WCE) is missing for a Clan to live as it does in the books. It works in any WCE world, and ships inside The Great Journey Core next to Clanlands and Clanwild. Clanlands compiles against it and plugs in through `LoreHooks` (its scenes go through `life/LoreBridge`, so they show in the dialogue window), the role `ClanKeeper` and `ClanNews`. Built 2026-10-18 at the owner's ask: "an addon mod for wce for all the missing traits cats need, events missing etc".

## Roles and ceremonies (`role/`)
WCE's ranks stop at deputy. Clan Lore keeps a role on every WCE cat, as the data attachment `clanlore:role`:
- **Roles:** NONE, LEADER (with 0 to 9 lives), ELDER or QUEEN.
- **Links:** the cat's mentor and its apprentice.

The role shows as a second line under the name tag ("Leader · 7 lives", "Elder", "Queen", "Mentor of Ashpaw"), synced with the payload `clanlore:role_sync`.

Every two seconds each Clan cat is compared with how it was last seen; that state is saved on the cat. Changes drive the ceremonies:
- **Kit to apprentice:** the leader's apprentice ceremony, and a mentor (the nearest warrior in its prime with no apprentice).
- **Apprentice to warrior:** the links are cleared. When WCE itself grew the cat, the leader also gives it its warrior name.
- **Retirement:** a warrior past `retireMoons` (96) asks to join the elders. At most one per Clan per 20 minutes, and never if that leaves fewer than 3 able warriors. A retiring deputy is replaced.
- **Queens:** a she-cat expecting kits, or nursing her own (or the camp's motherless) kits, is a queen until her kits are apprenticed.
- **Leader lives:** for its own Clans Clanlands' `LifeData` is the count and the role mirrors it. Other Clans' leaders lose lives in Clan Lore and pass the leadership on.

Two more behaviours:
- `kitsGrowUp` makes Clan kits WCE newborns, so WCE raises them kit to apprentice to warrior (about 60 loaded minutes as a kit).
- Mentor walks pair mentor and apprentice in plain WCE worlds; in Clanlands' camps the camp routine pairs them instead.

Config: `clanlore-common.toml` `[roles]`.

**Ages:** WCE holds grown cats at 12 moons. `Traits.moons(cat)` counts on from there using WCE's own moon length, and every adult-age check (elders, old-age deaths, Kin) uses it. Cats Clan Lore first meets grown get 12 to 84 moons.

## Traits (`traits/`)
Battle marks WCE can't show:
- scars on the face and flank;
- a torn or nicked ear;
- a grizzled muzzle;
- a missing eye;
- a crooked jaw;
- a lame leg.

They are stored in the attachment `clanlore:traits`, on cats and players, and synced with `clanlore:traits_sync`. How cats get them:
- **Marks from a fight:** surviving a fight that cost 60% of their health gives a 20% chance of a mark. An eagle or a badger can cost a 3% eye.
- **Grizzled muzzle:** at 96 moons.
- **Lame leg:** a broken paw that heals unwrapped makes the leg lame 25% of the time (5% wrapped). Lame is -8% speed, and WCE's limp when walking.

Drawing:
- **Overlays:** `client/TraitLayer` is a GeckoLib layer on WCE's `WCRenderer`, added through `EntityRenderersEvent.AddLayers`. It draws overlay textures from `assets/clanlore/textures/entity/traits/`, painted on `wcat.geo.json`'s 64x64 UVs.
- **Ears:** the ear cubes are too small for a painted notch, so a client mixin on `WCModel.setCustomAnimations` shortens a torn ear.
- **Limp:** a mixin on `WCatEntity.predicate` plays WCE's limp.

**Coat redraw fix:** WCE never redraws a cat's coat once worked out. The mixin `TraitCoatRedrawMixin` clears its texture key when a coat value syncs, so `BookLooks` changes show at once.

Config: `clanlore-traits-common.toml`.

## The moon and the truce (`moon/`)
- **The phase:** `Moon.phase(level)` names it in Clan words (the full moon, the half moon, the dark of the moon...). It also tells Gathering nights and half-moon nights. Better Days only changes clock speed, so vanilla phases hold.
- **The truce:** inside registered areas on a Gathering night, cats and players of different Clans can't hurt or target each other. Clanlands registers the island and Fourtrees.
- **Breaking it:** a first blow gets a warning. A second within a minute breaks the truce: rain, a narration, and the Gathering ends for the night (the protection itself lasts till dawn).

Config: `clanlore-moon-common.toml`.

## Clanlands' side
- **Gatherings** (`life/gathering/`, Free Mode and, since 2026-10-18, Campaign worlds: at Fourtrees with the leaders on the Great Rock while the Clans live in the old forest, none during the Great Journey, and on the island once the Campaign's own first island Gathering is done; the half moon goes to Mothermouth in the forest, the Moonpool at the lake; players in the CHOOSE or GATHERING chapters or near the Campaign's scene cats are skipped): a full-moon night is run step by step.
  1. On the afternoon of a full moon, the leader invites each Clan player (Go / Not tonight).
  2. A clanmate leads them the straightest walkable way, close round the lake's shore through whatever territories lie between (`Starts.wayShore`), and over the tree-bridge. While the Gathering is on, the truce holds on the way there too (`Moon.safePassage`): blows between Clans are stopped anywhere, but only a fight at the Gathering place breaks the truce. Since 2026-11 (owner: "the travel takes so long the gathering is missed").
  2b. The Gathering waits for everyone who said Go: while one of them is still on the way, the night is held just short of the last call (`Gatherings.holdNight`, at most ten minutes of real time), and the news starts once they have arrived and sat down.
  3. Scene cats gather at the Great Oak: the leaders, two warriors, an apprentice and the medicine cat of each Clan.
  4. Each leader tells up to three items of `ClanNews` and this moon's StarClan deaths.
  5. Afterwards the guide offers to walk the player home.

  The half moon sends the medicine cats to the Moonpool; a medicine player can go along, by the same straight way, and the half moon is held up for them in the same way until they reach the pool.

  Commands: `/greatjourney gathering start|stop|status` and `/greatjourney halfmoon` (ops).
- **`ClanNews.record(clan, text)`:** the events record one-line news: kits, ceremonies, raids, sickness, the badger, the lost kit, a new medicine cat, and the new events.
- **New Clan events** (`life/events/BookEvents`, each startable with `/greatjourney event <clan> <kind>`):

  | Kind | What happens |
  |---|---|
  | `dogs` | a collared Twoleg dog |
  | `rogues` | thieves at the fresh-kill pile |
  | `rats` | from Carrionplace, or in by the way in |
  | `adder` | a bite near warm stone, treated by the medicine cat |
  | `kittypet` | a visitor at the way in, scolded home |
  | `shortage` | leaf-bare hunger, and a hunting patrol |
  | `story` | an elder's tale to the kits, while a player is in camp |

  All of them end and leave, using the event-foe leave timer.

## WCE fixes (`mixin/`)
- **Patrols:** the border and hunting patrol goals now reset their "not reachable" counter on each leg. Before, after about 3000 ticks of patrolling every patrol ended at once. The waits at each point are cut: 15 s on border patrols (from 60), 40 s on hunts (from 120).
- **Going home:** the return-home goal clears a stuck `returnHomeFlag` for a cat with no nest within 400 blocks.
- **Safety:** every mixin config is `required: false` with `defaultRequire: 0`, so a WCE update that moves a target logs instead of crashing.

Known WCE bugs left alone:
- `patrolTick` ends a patrol whenever the cat isn't in WANDER mode.
- `checkForEmptyClans` is never called.

## Status
Compiled and boot-checked on the full pack server. Nothing has been played in-game yet: the trait overlays, the name-tag line and the client mixins have not been seen on screen.
