package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.quests.Bag;
import com.ninjacat.greatjourney.clanlands.life.quests.Kin;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestLog;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestState;
import com.ninjacat.greatjourney.clanlands.life.quests.Task;
import com.ninjacat.greatjourney.clanlands.life.quests.Tasks;
import com.ninjacat.greatjourney.clanlands.life.quests.Tracking;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Quest bookkeeping: catches and hand-ins count, state survives a save, and the journal shows it all. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class QuestGameTests {
    private QuestGameTests() {}

    /**
     * A fake player that never logs in: a mock player would fire Warrior Cats Events' login sync, whose packets the
     * test connection can't carry. Each test gets its own profile so state doesn't leak between them.
     */
    private static ServerPlayer player(GameTestHelper h, String name) {
        return net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    /** The layout tests' seeds. */
    private static final long[] SEEDS = {20261011L, 3776135913213891029L, -42L, 4242L};

    /**
     * Duties take five to ten minutes of walking, not half an hour: on every test seed, by the lake and in the old
     * forest, each Clan's border patrols, the territory walk (step 1) and the forest's places (step 5) stay within
     * {@link Tasks#ROUTE_CAP}. The Gathering island stays wherever it lies (across the lake from some camps), so by the
     * lake step 5 may only add a short way to the walk there and back.
     */
    @GameTest(template = "empty")
    public static void dutyWalksStayWithinTenMinutes(GameTestHelper h) {
        boolean wasForest = Homeland.forest();
        UUID cat = UUID.randomUUID();
        try {
            for (long seed : SEEDS) {
                ClanWorld w = ClanWorld.layoutOnly(seed);
                for (boolean forest : new boolean[] {false, true}) {
                    Homeland.setForest(forest);
                    for (Clan c : Clan.values()) {
                        Landmark camp = Homeland.camp(w, c);
                        String where = "seed " + seed + (forest ? ", old forest, " : ", lake, ") + c;
                        for (long day = 0; day < 40; day++) {
                            Task t = Tasks.borderPatrol(w, c, "Testpelt", cat, RandomSource.create(day));
                            double len = Tasks.routeLength(camp, t.objectives);
                            h.assertTrue(t.kind == Task.Kind.BORDER_PATROL, where + ": a border patrol on day " + day);
                            h.assertTrue(len <= Tasks.ROUTE_CAP, where + ": the day " + day + " border patrol walks " + (int) len + " blocks");
                        }
                        double[] shore = Kin.shorePoint(w, c), edge = Kin.farEdgePoint(w, c);
                        double walk = Tasks.routeLength(camp, List.of(Task.Objective.visit("shore", shore[0], shore[1], 24),
                                Task.Objective.visit("edge", edge[0], edge[1], 30)));
                        h.assertTrue(walk <= Tasks.ROUTE_CAP, where + ": the territory walk is " + (int) walk + " blocks");
                        Task places = forest ? Tasks.forestPlaces(w, c, "Testpelt", cat) : Tasks.lakePlaces(w, c, "Testpelt", cat);
                        walk = Tasks.routeLength(camp, places.objectives);
                        double gathering = Tasks.routeLength(camp, places.objectives.stream().filter(o -> o.text.contains("Gathering")
                                || o.text.contains("Fourtrees")).toList());
                        h.assertTrue(gathering > 0, where + ": the places step shows the Gathering place");
                        h.assertTrue(walk <= Math.max(Tasks.ROUTE_CAP, gathering + 480), where + ": the places step walks " + (int) walk
                                + " blocks (the Gathering place alone " + (int) gathering + ")");
                        if (forest) h.assertTrue(walk <= Tasks.ROUTE_CAP, where + ": the forest's places walk " + (int) walk + " blocks");
                    }
                }
            }
        } finally {
            Homeland.setForest(wasForest);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerWhoLeavesIsForgottenByTheDialogue(GameTestHelper h) {
        ServerPlayer p = player(h, "dialog_test_a");
        com.ninjacat.greatjourney.clanlands.life.Dialog.play(p, com.ninjacat.greatjourney.clanlands.life.Dialog.Line.narrate("Hello."));
        h.assertTrue(com.ninjacat.greatjourney.clanlands.life.Dialog.busy(p), "queued lines keep the window busy");
        com.ninjacat.greatjourney.clanlands.life.Dialog.forget(p);
        h.assertTrue(!com.ninjacat.greatjourney.clanlands.life.Dialog.busy(p), "logging out drops what was queued");
        com.ninjacat.greatjourney.clanlands.life.Dialog.play(p, com.ninjacat.greatjourney.clanlands.life.Dialog.Line.narrate("Hello again."));
        com.ninjacat.greatjourney.clanlands.life.Dialog.reset();
        h.assertTrue(!com.ninjacat.greatjourney.clanlands.life.Dialog.busy(p), "a stopped server leaves nothing for the next world");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void catchesAndHandInsCount(GameTestHelper h) {
        ServerPlayer p = player(h, "quest_test_a");
        QuestState.reset(p);
        try {
            UUID giver = UUID.randomUUID();
            List<Task.Objective> obj = new ArrayList<>(List.of(
                    Task.Objective.catchPrey("Catch 2 mice", "any", 2),
                    Task.Objective.bring("Bring 4 moss", "minecraft:moss_block|minecraft:moss_carpet", 4)));
            Task duty = new Task(Task.Kind.HUNT, "Test hunt", "test", "Testpelt", giver, false, obj, 3, 0, "", 0);
            QuestState.setDuties(p, new ArrayList<>(List.of(duty)));

            Tracking.caught(p, "warriorcats_events:mouse", 1);
            Tracking.caught(p, "minecraft:zombie", 1);                     // not prey: doesn't count
            Task saved = QuestState.duties(p).get(0);
            h.assertTrue(saved.objectives.get(0).progress == 1, "one mouse counted, got " + saved.objectives.get(0).progress);
            Tracking.caught(p, "warriorcats_events:mouse", 1);
            saved = QuestState.duties(p).get(0);
            h.assertTrue(saved.objectives.get(0).done(), "two mice finish the catch");

            // any prey counts, not only the Clan's favourite: a WindClan hunt takes a squirrel, a Naturalist rat and a rabbit
            Task wind = new Task(Task.Kind.HUNT, "Moor hunt", "test", "Testpelt", UUID.randomUUID(), false,
                    new ArrayList<>(List.of(Task.Objective.catchPrey("Catch 3 prey", "wind", 3))), 3, 0, "", 0);
            QuestState.setDuties(p, new ArrayList<>(List.of(wind)));
            Tracking.caught(p, "warriorcats_events:squirrel", 1);
            Tracking.caught(p, "naturalist:rat", 1);
            Tracking.caught(p, "minecraft:rabbit", 1);
            h.assertTrue(QuestState.duties(p).get(0).objectives.get(0).done(), "a squirrel, a rat and a rabbit all count for WindClan");
            QuestState.setDuties(p, new ArrayList<>(List.of(duty)));

            p.getInventory().add(new ItemStack(Items.MOSS_BLOCK, 3));
            p.getInventory().add(new ItemStack(Items.MOSS_CARPET, 2));
            h.assertTrue(Bag.count(p, "minecraft:moss_block|minecraft:moss_carpet") == 5, "moss blocks and carpets both count");
            h.assertTrue(Bag.take(p, "minecraft:moss_block|minecraft:moss_carpet", 4), "four moss handed over");
            h.assertTrue(Bag.count(p, "minecraft:moss_block|minecraft:moss_carpet") == 1, "one moss left");

            QuestState.addStanding(p, 7);
            QuestState.addPlace(p, "island");
            h.assertTrue(!QuestState.addPlace(p, "island"), "a place is only found once");
            h.assertTrue(QuestState.standing(p) == 7 && QuestState.places(p).contains("island"), "standing and places kept");
            h.succeed();
        } finally {
            QuestState.reset(p);
            p.getInventory().clearContent();
        }
    }

    @GameTest(template = "empty")
    public static void nobodyIsSentOutAtNightOrInTheRain(GameTestHelper h) {
        h.assertFalse(Tasks.night(6000) || Tasks.keptIn(false, false, Task.Kind.BORDER_PATROL), "a dry noon: patrols go out");
        h.assertTrue(Tasks.night(18000) && Tasks.night(24000L * 5 + 13000), "midnight and dusk are night, on any day");
        for (Task.Kind k : Task.Kind.values()) h.assertTrue(Tasks.keptIn(true, false, k), "nothing is asked at night: " + k);
        h.assertFalse(Tasks.night(23500) || Tasks.night(1000), "the dawn patrol's hour is day");
        for (Task.Kind k : List.of(Task.Kind.BORDER_PATROL, Task.Kind.HUNT, Task.Kind.FISH, Task.Kind.STORY_PATROL, Task.Kind.STORY_HUNT,
                Task.Kind.STORY_PLACES, Task.Kind.STORY_ASSESSMENT)) {
            h.assertTrue(Tasks.keptIn(false, true, k), "no " + k + " in the rain");
        }
        for (Task.Kind k : List.of(Task.Kind.MOSS, Task.Kind.FEED_ELDER, Task.Kind.STORY_TERRITORY, Task.Kind.STORY_MED_REMEDIES)) {
            h.assertFalse(Tasks.keptIn(false, true, k), "camp work goes on in the rain: " + k);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyCatHasWorkAndTheJournalShowsIt(GameTestHelper h) {
        ServerPlayer p = player(h, "quest_test_b");
        QuestState.reset(p);
        try {
            UUID cat = UUID.randomUUID();
            for (Kin.Role role : new Kin.Role[] {Kin.Role.MEDICINE, Kin.Role.DEPUTY, Kin.Role.ELDER, Kin.Role.QUEEN, Kin.Role.WARRIOR}) {
                for (long day = 0; day < 4; day++) {
                    Task t = Tasks.dutyFor(role, Clan.THUNDER, "Testpelt", cat, day);
                    h.assertTrue(t != null && !t.objectives.isEmpty(), role + " offers a duty on day " + day);
                }
            }
            h.assertTrue(Tasks.dutyFor(Kin.Role.KIT, Clan.THUNDER, "Testkit", cat, 0) == null, "kits give no duties");
            if (ClanWorld.current() != null) {
                for (int step = 1; step <= 6; step++) {
                    Task t = Tasks.storyStep(step, Clan.RIVER, "Testpelt", cat, 42L);
                    h.assertTrue(t != null && t.story && !t.objectives.isEmpty(), "apprentice step " + step + " has objectives");
                }
                // the medicine cat's path: six steps of its own, with the medicine den, remedies and the herbs test
                for (Clan c : Clan.values()) {
                    for (int step = 1; step <= 6; step++) {
                        Task t = Tasks.storyStep(QuestState.Path.MEDICINE, step, c, "Testleaf", cat, 42L);
                        h.assertTrue(t != null && t.story && !t.objectives.isEmpty(), c + " medicine step " + step + " has objectives");
                        h.assertTrue(Task.load(t.save()) != null, c + " medicine step " + step + " saves and loads");
                    }
                }
                h.assertTrue(Tasks.storyStep(QuestState.Path.MEDICINE, 3, Clan.THUNDER, "Testleaf", cat, 42L).kind == Task.Kind.STORY_MED_REMEDIES,
                        "the medicine path's third step is the remedies");
                h.assertTrue(Tasks.storyStep(QuestState.Path.WARRIOR, 2, Clan.THUNDER, "Testpelt", cat, 42L).kind == Task.Kind.STORY_HUNT,
                        "the warrior's path is as it was");
            }
            QuestState.setDuties(p, new ArrayList<>(List.of(Tasks.dutyFor(Kin.Role.MEDICINE, Clan.THUNDER, "Testleaf", cat, 1))));
            JournalView v = QuestLog.view(p);
            h.assertTrue(v.quests().size() == 1, "the journal lists the duty");
            h.assertTrue(v.quests().get(0).giver().equals("Testleaf"), "with its giver");
            h.assertTrue(!v.rank().isEmpty() && v.nextGoal() != null, "and who the player is");
            if (ClanWorld.current() != null) h.assertTrue(!v.places().isEmpty(), "and the places of the books");
            h.succeed();
        } finally {
            QuestState.reset(p);
            p.getInventory().clearContent();
        }
    }
}
