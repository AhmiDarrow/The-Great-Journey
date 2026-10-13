package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
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
