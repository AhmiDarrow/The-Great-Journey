# Handoff: 1.7 update

State of The Great Journey 0.1.7, written 2026-11-26. Read [clanlands.md](clanlands.md) (the "Rising through the Clan"
bullets, which hold the developer notes for everything below) and [wce-integration.md](wce-integration.md) first.

## Where things stand

- Everything in the 0.1.7 section of [changelog.md](changelog.md) is built. The owner asked for it to ship as 0.1.7.
- `python tools/gradle_locked.py :clanlands:runVerification`: the 96 earlier game tests and 11 new ones.
- `python tools/playtest.py --suite smoke` creates a Free Mode world in a real full-pack client, through the new
  storyteller screen. The other playtest suites have not been run on this version (their title-screen steps now pick
  Bluestar).
- Released without the in-play checks below: they need a person at the keyboard.

## Built

| What | Where |
|---|---|
| Deputy and leader orders; a patrol the player leads | `quests/Talk`, `quests/Patrols` |
| Apprentices keep up; kittypets, loners, rogues talk; loner favours | `life/Lead`, `quests/Pupils`, `quests/Strangers` |
| Moonpool dreams | `quests/MoonDreams` |
| Five storytellers: choice screen, weights, pacing, omens, dream judgement, change at the Moonpool | `story/Teller`, `story/StoryData`, `events/Director`, `client/TellerScreen` |
| Border skirmishes and stolen prey | `events/Skirmish` |
| The warrior code (8 choices; standing and honour) | `quests/CodeMoments`, `quests/Deeds` |
| Prophecies | `story/Prophecy`, `quests/Riddles`, `Director.fulfil` |
| Leaf-bare hunger from the real fresh-kill pile | `events/Hunger` |
| The Dark Forest; a friend across the border | `quests/DarkForest`, `quests/Friendship` |
| Camp moments; a founded Clan's own life | `quests/CampMoments`, `quests/Homestead` |
| Long talk menus fit the screen | `client/DialogueScreen.choiceStep` |

Tests: `OrdersGameTests`, `events/StoryGameTests`, `quests/BookGameTests`, `LeadGameTests`.

## Needs playing

Tests cover what these do, not how they happen in the world.

1. "Patrol where I show you" opens Warrior Cats' patrol map from our talk window, and the cat goes.
2. A led patrol attacks what the player fights; how four cats in formation feel; an apprentice outrun at a sprint arrives.
3. Wild cats: an empty-paw right-click now talks instead of opening Warrior Cats' cat menu.
4. A Moonpool dream starts on walking up at night; the storyteller choice and a prophecy read well in one sitting.
5. A Clanmate's menu with seven or eight choices at a large GUI scale (the rows shrink; not seen in a client).
6. A border skirmish out in the territory: where the trespassers appear, whether the fight feels fair, and that they run.
7. Waking from a nest brings the Dark Forest (it relies on Warrior Cats' nests firing the vanilla wake event).
8. A friend waiting at a scent line.
9. Leaf-bare in a live camp: do the Clan's own hunters keep the pile full enough? Tune `Hunger.need` if cats are
   always sick.
10. How often things happen under each storyteller over an hour. All the rates are first guesses: `Teller`, and the
    `ONE_IN` constants in `CodeMoments`, `Friendship` and `Homestead`.
11. A dedicated server with two players (`playtest.py --suite mp`).

## Choices made that the owner may want to change

- One storyteller per world; **any** player dreaming at the Moonpool can change it, and everyone is told.
- Existing worlds get Bluestar, and with it the new omens, skirmishes, hunger and choices. The Campaign is unchanged by
  the storytellers.
- Skirmish trespassers and the friend are story cats with no Clan id: Warrior Cats Events adds every cat with one to
  that Clan's list for good.
- While an omen waits, any other dangerous event is dropped.
- A founded Clan grows by its own events to 16 cats.

## Facts found the hard way (Warrior Cats Events 1.12.2)

- Our talk window cancels the plain right-click that opens WCE's cat menu, the only way to its patrol screen.
- WCE's `PatrolScreen` sends one cat when the player is the deputy; its group relay needs an NPC of rank DEPUTY.
- WCE's Ancient Stick call goes by the cat's owner, and book cats are owned by the stand-in leader.
- Clanless WCE cats may carry `ClanData.EMPTY_UUID` instead of a null Clan id.
- Compare `getOwnerUUID()`, not `isOwnedBy(player)`.
- A she-cat warrior within 20 blocks of a kit of her Clan reads as a QUEEN; tests set the gender.
- An entity that an `EntityJoinLevelEvent` handler may reject as stale must be recorded **before** `addFreshEntity`.
- `Dialog.play` and `Dialog.subtitle` crash on a `FakePlayer`: go through `quests/Ui`, or check first.

## Next ideas

More warrior code moments (add a record to `CodeMoments.MOMENTS`), more prophecy meanings, a title screen and logo.

## Working rules for this repo

- Build: `python tools/gradle_locked.py :clanlands:compileJava`; one heavy job at a time.
- Source files are CRLF. Keep them so.
- Do the work first, then one test run at the end.
- Each behaviour change gets a game test, a changelog line in the players' words, and a note in `clanlands.md`.
