# Warrior Cats Events (WCE) integration notes

Source read: GitHub `Snowo69/WarriorCatsEvents`, branch `1.21.1` (2026-10-11), plus jar
`warriorcats_events-neoforge-1.21.1-1.12.2.jar`. Root package
`net.snowteb.warriorcats_events` (abbreviated `wce.` below). WCE is All Rights Reserved:
call into it, never copy it. Anything marked **(guess)** was not confirmed in code.

Hard deps (neoforge.mods.toml): `walkers` >= 5.8.7 (tocraft Walkers), `craftedcore` >= 5.8,
`geckolib` >= 4.8.4, `patchouli`.

## 1. Playing as a cat

- The morph is a **Walkers shape**: the player's shape is a `WCatEntity` built by
  `wce.attachments.WCEPlayerDataUtils.createShape(Player, Level)` and applied with
  `tocraft.walkers.api.PlayerShape.updateShapes(player, cat)`. The integration code is in `wce.integration.WCatIntegration`,
  `WCatTypeProvider` and `WCatAbility` (night vision).
- **First join** (`wce.event.ModEventsForge1.onPlayerLogin`): if the player's persistent NBT
  lacks `warriorcats_events.starting_items`, WCE makes the player invulnerable and sends
  `OpenClanSetupScreenPacket`, which opens a name screen (prefix/suffix/name, age Kit/Apprentice/Adult, gender).
  After that come `CreateMorphScreen` (genetics) and `SpawnLocationScreen`. `SaveClanDataPacket` stores the profile and
  applies the shape. `CtSTeleportToLocationPacket` then teleports the player to a taiga, plains, savanna or forest biome
  or a village/jungle temple, but only if `allowTeleport` is on. There is also a "Somewhere / don't move" option. This
  step gives Warriors Guide, Claws, Whiskers and a cod, then sets the flag.
- Shape toggle: `CtSSwitchShape` (keybind). `/wce morph create`, `/wce info setup` and `/wce info reset` reopen the screens.
- **Per-player data** is a NeoForge data attachment, `ModAttachments.PLAYER_WCE_DATA` → `wce.attachments.WCEPlayerData`. It holds:
  morphName, prefix, sufix, useSufixes, characterBio, genderData/genderText, `Age morphAge`
  (KIT/APPRENTICE/ADULT), variantData, isOnGeneticalSkin, genetics/variants/chimera genetics
  (`WCGenetics`), `currentClanUUID`, clanName, mateUUID/mateName/mateGenetics, playerKitsCooldown,
  idlePose, chat flags. All fields have public setters (`setCurrentClanUUID`, `setClanName`, `setMorphName`, ...).
  Other attachments: `PLAYER_SKILL` (`PlayerSkill`: skill tree with HP/speed/damage/jump/armor, leap,
  climb, stealth), `PLAYER_STEALTH`, `PLAYER_THIRST`.
- `ClanData.playerMorphNames` / `playerMorphData` also cache every player's morph server-side.

## 2. Clans

**Storage:** `wce.clan.ClanData extends SavedData`, id `wce_clans`. It is always fetched with
`ClanData.get(server.overworld())`, which uses `level.getDataStorage()`, so overworld only.
`Map<UUID, Clan> clans`.

`ClanData.Clan` (public fields unless noted):
`clanUUID, name, clanBioSentence, clanSymbolIndex (0..146, ClanSymbol.SYMBOLS_AMOUNT=147),
color (RGB int), UUID leader (package-private, set only by createClan), leaderName,
normalizedName, Map<UUID,ClanPlayerRank> members, Map<UUID,ClanPermissions> memberPerms,
Map<UUID,ClanCat> clanCats, List<ClanLogEntry> logs (max 300),
Map<ChunkPos,TerritoryChunk> claimedTerritory, ChunkPos coreTerritory`.

- `ClanPlayerRank` (players): LEADER, DEPUTY, MEDICINE, MEDICINEAPP, WARRIOR, ELDER, QUEEN, APPRENTICE, KIT.
- `ClanPermissions`: OWNER, ADMIN, MEMBER, GUEST. `canManage` = LEADER/OWNER/ADMIN;
  `canCommandWarriors` = any perm except GUEST.
- `ClanCat` is only a snapshot of an NPC cat (uuid, name, gender, variant, age text, rank string, parents, genetics),
  built by `ClanCat.buildCat(WCatEntity)`. The live entity is the source of truth.

**Creation:** the `/wce clan register` command opens `CreateClanScreen` → `C2SRegisterClanPacket.handle`.
That handler calls
`ClanData.createClan(String name, int color, UUID leader, String leaderName, String bio, int symbolIndex)`,
which puts `leader` in `members` as LEADER/OWNER. It then sets the player attachment and adopts the player's tamed cats.
If `canAnyoneCreateClans` is false, only ops (level 3) can create clans.

Other public `ClanData` methods: `addMember(ServerPlayer, UUID clan, ClanPlayerRank)` (sets perms
**GUEST**, updates the attachment, syncs, logs), `removeMember(ServerPlayer, UUID)` (**deletes
the clan if members becomes empty**), `removeMember(UUID,UUID)`, `changeMemberRank(...)`,
`changeMemberPermissions(UUID player, UUID clan, ClanPermissions)`, `deleteClan(ServerLevel, UUID)`,
`renameClan`, `getClan(UUID)`, `getClanByName(String)`, `getClanByCat(UUID)`, `getAllClans()`,
`addClanCat(UUID|Clan, WCatEntity)`, `removeClanCatFromClan`, `registerLog(ServerLevel, UUID, Component)`,
`syncTerritoriesToClients(ServerLevel)`, `setDirty()`. `checkForEmptyClans` exists but is never called.

**Territory**:
- Packet `CtSClaimTerritory` (sent from the clan screen) does these checks: the player is in the overworld and
  `canManage`; they stand in air, a flower or grass above a solid block; and the clan has fewer chunks than `maxTerritory`.
  The first claim costs 450 XP and sets `clan.coreTerritory = playerChunk`. Later claims cost 850 XP and must touch existing
  territory along an axis. Each claim calls `ClanData.claimChunk(UUID clan, ChunkPos, String name)`, which **claims the
  chunk plus its 8 neighbours** (any not owned by another clan). It then places a `TREE_STUMP` block (scent marker) at the
  player's feet. Its `TreeStumpBlockEntity` stores ownerClanUUID, territoryPos, name, colour and the renew timer.
- Decay: `ModEventsForge1` server tick → `ClanData.territoryTick`. Each `TerritoryChunk` starts with
  `time = fadingTime/2` (minutes × 1200 ticks) and loses one per tick. The **core chunk and its 8 neighbours never decay**.
  At 0 the chunk is dropped, any stump in it is removed, and `removeTerritoryNotConnected` drops land that no longer
  touches the core along an axis.
- Re-marking: a clan member who isn't a GUEST can shift near a stump whose cooldown has run out (cooldown =
  `treeStumpCD`×max). Alternatively an NPC cat on a **border patrol** can reach the stump. Either way
  `reclaimChunk(UUID, ChunkPos, ServerLevel)` runs and adds max/3 to the chunk and its claimed neighbours.
- Unclaiming: `unclaimChunk(UUID, ChunkPos, ServerLevel)` (packet `CtSUnclaimTerritory`).
- Effects: `wce.clan.ClanTerritoryEvents` cancels block break, place and right-click-block for anyone who is not in the
  clan or is a GUEST. Creative players and op level ≥2 are exempt. Gated by `enforceTerritories`, `placeBreakBlocks` and
  `manageContainers`. `ServerLevelMixin.tickChunk` occasionally spawns prey (squirrel, mouse, pigeon, rabbit) in
  non-core chunks that are more than 36% fresh, with a rare fox, wolf, badger or eagle.
  Client side: `SyncTerritoryToClients` → `ClientTerritoryData`. `ClientTerritoryEvents` draws a HUD showing the clan
  name, territory name and freshness bar while you are inside. `CustomMusicManager` plays an ambient playlist in
  territory (client config `cuteAmbientMusic`). `/wce clan map` opens `TerritoryMapScreen`.

## 3. NPC cats

- One entity type, `warriorcats_events:warrior_cat` → `wce.entity.custom.wcat.WCatEntity extends TamableAnimal`
  (GeckoLib). Wild and clan cats are the same entity; they differ in tame state, rank and clan UUID. They spawn naturally
  via biome modifier `wildcat_vanilla_spawns.json` (`#minecraft:is_overworld`, weight 17, 2-3).
  `isPersistenceRequired() == true` and `removeWhenFarAway == false`, so they never despawn.
- `WCatEntity.Rank` enum (NPCs): NONE, KIT, APPRENTICE, WARRIOR, MEDICINE, DEPUTY. **There is no NPC
  LEADER, ELDER or QUEEN rank.** `CatMode`: SIT, FOLLOW, WANDER (public field `mode`).
- Data and public API: `setCustomName` (names look like "Firestar ♂"), `setPrefix(Component)`, `setGender(0 tom/1 she)`,
  `setRank(Rank)` (also colours the name), `setClan(Component displayName)`,
  `setClanUUID(UUID)` (also adds or removes the cat in `ClanData.clanCats`), `setAgeInMoons(float)`, vanilla `setAge`
  / `setBaby`, `setAppScale(bool)`, `setHomePosition(BlockPos)`, public fields `wanderCenter`, `returnHomeFlag`, `mode`,
  `setPersonality(Personality)` (NONE, CALM, ... FRIENDLY, SHY, GRUMPY, RECKLESS, INDEPENDENT, HUMBLE, AMBITIOUS,
  CAUTIOUS), `setSpecificMood`, `getGeneticsModule()`
  (`setGenetics`, `setGeneticalVariants`, `initializeGenetics()`), `setOnGeneticalSkin`, `setVariant`,
  mother/father/mate setters, `updateClanCatData()`. Name pools: `WCatEntity.PREFIXES` and `SUFIXES`.
- NBT keys (so `/summon` works): `Rank` (int ordinal), `ClanUUID` (int-array UUID), `Clan` (component JSON),
  `HomePos`, `WCatMode`, `Gender`, `Personality`, `Prefix`, `AgeMoons`, `HasWanderCenter`/`WanderX/Y/Z`,
  `Genetics{...}`, plus vanilla `Owner` and `CustomName`. On load, `readAdditionalSaveData` calls `setClanUUID`, which
  registers the cat into ClanData. `finalizeSpawn` randomises gender, variant, personality and genetics.
- **Recruiting:** an untamed cat right-clicked with `freshkill_and_herbs_bundle`
  (`MobInteractModule.wildCatTaming`) gets a personality-weighted chance to `tame(player)`. If it succeeds, the cat gets a
  random warrior name, rank APPRENTICE (baby) or WARRIOR, and the player's `currentClanUUID`.
  Rank cycling: owner shift-clicks with Whiskers (`changeRank`). Kick: `KickNPCPacket` sets rank NONE.
- **Command handover:** shift-click with an empty hand (`catSetMode`). The cat's owner, or any clan member who is not a
  GUEST, can cycle SIT/FOLLOW/WANDER. In the second case **the cat's owner becomes that player**.
- **Clan without players:** cats only need `ClanUUID`. Ownership is a separate vanilla TamableAnimal owner.
  `ModEventsForge2` (entity join) re-syncs a cat's clan to its owner's clan only when the owner is an online ServerPlayer.
  So a tame cat with a fake or absent owner keeps its clan **(behaviour confirmed by reading; not tested in-game)**.
  `WCatDepositFreshkill` and `WCatFollowOwnerGoal` require `isTame()`.
- **Goals** (`WCatEntity.registerGoals`, classes in `WCGoals`):
  - ReturnHome: `returnHomeFlag` → walk to `homePosition` if it is within 400 blocks.
  - LeaderCalls: Ancient Stick follow/stay calls.
  - SeekShelter: when it rains.
  - MedicineHealsCats: MEDICINE rank makes poultices from herbs in its inventory and heals injured clanmates.
  - PickupItem.
  - DeputySendsPatrols, BorderPatrolGoal, HuntingPatrolGoal: a deputy hands patrol waypoints to listed cats. Border
    patrols re-mark stumps. The cat waits 1200 ticks (a minute) at every waypoint, and the goal's "not reachable"
    counter counts every tick spent walking and is never reset: once a cat has walked 3000 ticks of patrols in all
    (until it reloads) every patrol it is given ends at once. Our quest patrols steer their warriors themselves.
  - DepositFreshkill: prey in the cat's inventory goes to the nearest fresh-kill pile within 18 blocks.
  - BreedGoal, MoveToMate, FollowOwner, RunWithPlayer.
  - BoundedWander: within `wanderRadius` of `wanderCenter`, with a random chance to go back to its nest (home within 20
    blocks) or lavender hangouts.
  - Monster and prey targeting, which only runs in WANDER mode.
- Patrols are player-initiated. `CtSSendPatrol` → `deputy.setDeputyToSetPatrols(Map<Integer,BlockPos>, List<WCatEntity>, int type)`
  (the deputy must have a home). Direct calls: `cat.setOnBorderPatrol(map)` / `setOnHuntingPatrol(map)`.
- Dens/beds: many `*_nest` blocks (`NestBlock` with `NestBlockEntity`, which tracks the assigned cat). The home is set by
  `CatHomeActionsPacket.setHome` (searches for a nest within 3 blocks) or by `setHomePosition`.
- Kits: catmint makes a cat `setInLove`, so breeding is player-initiated. After `kittingMinutes` kits are born tame with
  rank KIT and inherit the mother's clan and owner (if the owner is an online player). A kit grows over `kitGrowthMinutes`,
  becomes APPRENTICE (with a "-paw" rename) and later WARRIOR. Players can also have kits with cats (`/wce mate kits ...`).
- Sickness: `wce.diseases.*` covers greencough, whitecough, fever, chills, broken leg, sore pads and deathberries
  poisoning. Players and cats both use the `Diseaseable` interface. Herbs (catmint, juniper, feverfew, yarrow, dock,
  comfrey, burnet, sorrel, chamomile, daisy, poppy) are mixed at the Herb Rock. Toggle: `diseases`.

## 4. Commands (all under `/wce`, `wce.commands.WCECommands`)

Permission level 0 unless noted. Almost every handler calls `getPlayerOrException()`, so **they fail from the console or
a server function**.

| Syntax | Notes |
|---|---|
| `/wce clan` / `clan list` / `clan map` | Opens your clan screen, the clan list, or the territory map |
| `/wce clan register` | Opens the create screen (needs op 3 if `canAnyoneCreateClans=false`) |
| `/wce clan manage` / `leave` / `disband` | |
| `/wce clan invite <player>`, `invite accept`, `invite deny` | Invite lasts 30 s, and joining gives WARRIOR rank with GUEST perms |
| `/wce clan kick <player>` | |
| `/wce clan changerole <player> <role>` | Uses the ClanPlayerRank names except LEADER |
| `/wce clan changeperms <player> <perms>` | |
| `/wce clan setNewLeader <player>` | Leader+OWNER only |
| `/wce clan adminForceDelete <clan>` | Level 3 |
| `/wce clan adminInvite <clan> <player>` | Level 3, needs a player executor |
| `/wce summon` | Level 3 or creative; opens the custom-cat GUI (`SummonCustomCatPacket`) |
| `/wce morph create`, `morph cosmetics` | |
| `/wce info setup` / `profile` / `get [player]` / `reset` | |
| `/wce mate accept/decline/divorce`, `mate kits accept/decline` | |
| `/wce carryRequest accept/deny`, `/wce chatMorphName toggleChatMorphName/toggleFancyFont`, `/wce resync-shapes`, `/wce changelog` | |
| `/wce disease give/remove <entities> <disease>`, `disease clear <entities>` | Level 3 |
| `/gamerule wceThirstSystem / wceSkillSystem / wceAggressiveAnimals / wceLeapSystem / wceChooseSpawnLocation / wceProtectContainersInTerritory / wceProtectBlocksInTerritory / wceDiseases <bool>` | Level 3; these write the server config |

## 5. Third-party hooks (there is no API, events or IMC)

WCE has no API package, no events and no IMC. Everything below means calling public code directly:
compile against the jar as `compileOnly`/`runtimeOnly` and declare `warriorcats_events` as a required dependency.

1. **Clan with no real player:** `ClanData d = ClanData.get(server.overworld());`
   `Clan c = d.createClan("ThunderClan", 0xD4A017, FAKE_UUID, "Bluestar", "\"...\"", sym);`
   Use a stable fake leader UUID, for example a UUID derived from the clan name. The clan survives because nothing prunes
   members who are never online. Never let `removeMember(ServerPlayer,..)` empty it. Then call `d.setDirty()` and
   `d.syncTerritoriesToClients(overworld)`. **(guess)** WCE screens may show the fake UUID as an "Unknown" member.
2. **Core/camp + territory:** call `d.claimChunk(c.clanUUID, campChunk, "Camp")`, then set `c.coreTerritory = campChunk`
   (a public field). Then claim further chunks with `claimChunk`; each call fills a 3×3. Optionally place a
   `ModBlocks.TREE_STUMP` and fill its BE (`setOwnerClanUUID`, `setTerritoryPos`, `setOwnerClanName/Color`,
   `setTerritoryName`, `setTimeUntilRenewScent`), copying what `CtSClaimTerritory` does.
   Non-core land decays, so periodically set `c.claimedTerritory.get(pos).time = d.getMaxTerritoryTime()` (a public
   field) or call `reclaimChunk`. The other option is a very large `fadingTime`. Raise `maxTerritory` above 169 if a book
   territory needs more chunks; `claimChunk` itself does not check the cap.
3. **NPC cats in the clan:** do `WCatEntity cat = ModEntities.WCAT.get().create(level)`, then `cat.finalizeSpawn(...)`.
   Set the position, `setCustomName`, `setGender`, `setRank(Rank.WARRIOR/DEPUTY/MEDICINE/APPRENTICE/KIT)`,
   `setAgeInMoons` and age, `setClan(Component.literal(c.name))`, and `cat.setTame(true,true)` with
   `setOwnerUUID(FAKE_UUID)` **(guess: a fake owner is safe; not tested)**. Then `level.addFreshEntity(cat)`, then
   `cat.setClanUUID(c.clanUUID)`, `cat.setHomePosition(nestPos)`, `cat.wanderCenter = campCentre`. Do the clan calls
   after adding the entity, because `setClanUUID` reads `level()`.
   Book leaders and elders can only be name and lore; WCE has no NPC rank for them. Fallback with no code: `/summon
   warriorcats_events:warrior_cat x y z {CustomName:'"Graystripe ♂"',Rank:3,ClanUUID:[I;...],Gender:0,WCatMode:2,HomePos:[I;x,y,z]}`
   run as server. You need the clan UUID, so this still requires code or reading the SavedData.
4. **Letting the player join:** do not use `adminInvite`, which needs a player executor. Call
   `d.addMember(player, c.clanUUID, ClanPlayerRank.APPRENTICE)` and then
   `d.changeMemberPermissions(player.getUUID(), c.clanUUID, ClanPermissions.MEMBER)`. The second call is required:
   without it the player is a GUEST, cannot command clan cats and is treated as an enemy in clan territory. Joining
   adopts the player's tamed cats into the clan.
5. **Patrols, autonomously:** a server tick in our mod can call `deputy.setDeputyToSetPatrols(stumpMap, warriors, 0/1)`
   or `setOnBorderPatrol(map)` on the cats directly. Border patrols re-mark stumps through WCE's own goal.

## 6. Config

- `serverconfig/warriorcats_events-server.toml` (SERVER, per world; `wce.zconfig.WCEServerConfig`).
  Section `[wce_server]` contains wanderRadius (16, 8..64), kittingMinutes (10), kitGrowthMinutes (120), coloredNames,
  skillCostValue, aggroAnimals, visibleMorphName, serverLeap, serverSkills, rawFoodBonus, **allowTeleport** (true; set
  it to false so first-join does not move players off our lake spawn), canEaglesBeTamed, thirst, diseases,
  **canAnyoneCreateClans** (true).
  The nested `[wce_server.wce_territories]` contains **fadingTime** (240 min), **treeStumpCD** (0.125),
  **maxTerritory** (169), **enforceTerritories**, **placeBreakBlocks** and **manageContainers**. Section `wce_skills` holds
  the multipliers. Nesting is **(guess)**: the code never pops `wce_server` before pushing `wce_territories`.
- `config/warriorcats_events-client.toml` `[wce_client]`: displayTerritory, cuteAmbientMusic, showNametag,
  chat bubbles, panorama, sereneSeasonsOverlay, leap, fishingScreenYOffset.
- `config/warriorcats_events-prey_items.toml` (COMMON): the prey item list.
- To stop random wild cats spawning around the lake, use a pack datapack with `neoforge:remove_spawns` for
  `warriorcats_events:warrior_cat`, or override `wildcat_vanilla_spawns.json`.

## 7. Lore features

Almost nothing is implemented. `Moonstone` is a reskinned enchanting table (`MoonstoneBlock extends EnchantingTableBlock`).
"Moonpool" appears only as a string in `SkillScreen`, "StarClan" only as an accessory texture, and "There is a gathering
tonight" only as a title splash. There are no Gatherings, full-moon truce, StarClan dimension, dreams or prophecies.
The moon phase is never read. Other lore-flavoured systems: warrior-name generation, the "-paw" apprentice rename,
medicine cats and herbs, diseases, fresh-kill pile, patrols, Ancient Stick commands, mates and kits, Stone Cleft, Stickfire,
and the Patchouli Warriors Guide.
