package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The 0.1.10 bug sweep and balance pass, as far as a player's quests go. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class SweepQuestGameTests {
    private SweepQuestGameTests() {}

    @GameTest(template = "empty")
    public static void anyCatCanBeTreatedAtAnyHour(GameTestHelper h) {
        h.assertFalse(Tasks.keptIn(true, true, Task.Kind.TEND), "a sick cat is seen to at night and in the rain");
        h.assertTrue(Tasks.keptIn(true, false, Task.Kind.HERBS), "gathering still waits for day");
        var catmint = new net.minecraft.world.item.ItemStack(net.snowteb.warriorcats_events.item.ModItems.CATMINT.get(), 4);
        h.assertFalse(Dialog.talksWith(catmint), "a herb in the paw is not talk: the click goes to the cure");
        h.assertTrue(Dialog.talksWith(net.minecraft.world.item.ItemStack.EMPTY), "an empty paw is");
        ServerPlayer healer = BookGameTests.player(h, "sweep_healer"), patient = BookGameTests.player(h, "sweep_patient");
        var sick = (net.snowteb.warriorcats_events.diseases.Diseaseable<?>) (Object) patient;
        var cough = net.snowteb.warriorcats_events.diseases.DiseaseTypes.WHITECOUGH;
        try {
            h.assertFalse(com.ninjacat.greatjourney.clanlands.life.Healing.treat(healer, patient, catmint) || catmint.getCount() != 4,
                    "a well cat takes nothing");
            sick.getList().add(cough.create());
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.Healing.treat(healer, patient, catmint), "a coughing player is given catmint");
            h.assertTrue(catmint.getCount() == 3, "and one is used");
        } finally {
            sick.removeDisease(cough);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void typedCommandsEarnNothing(GameTestHelper h) {
        ServerPlayer p = BookGameTests.player(h, "red_team");
        var cat = net.snowteb.warriorcats_events.entity.ModEntities.WCAT.get().create(h.getLevel());
        try {
            h.assertFalse(Talk.clanmate(p, cat), "a cat of another Clan, or of none, asks you for nothing");
            Talk.accept(p, cat);
            h.assertTrue(QuestState.duties(p).isEmpty(), "typing accept at it takes on no duty");
            CampMoments.tongues(p, cat);
            h.assertFalse(p.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION), "nor does it heal");
            h.assertFalse(MoonDreams.choose(p, "tigerstar"), "the storyteller changes only when the dream offers it");
            var other = BookGameTests.player(h, "red_other");
            cat.getPersistentData().putUUID("greatjourney_for", other.getUUID());
            h.assertFalse(Homestead.cameTo(cat, p), "another founder's wanderer is not yours");
            h.assertTrue(Homestead.cameTo(cat, other), "it is theirs");
            p.setRemainingFireTicks(100);
            h.assertTrue(Starts.inDanger(p), "a burning cat is not whisked away");
        } finally {
            p.clearFire();
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aTrainingStepPaysOnce(GameTestHelper h) {
        ServerPlayer p = BookGameTests.player(h, "sweep_paid");
        try {
            h.assertTrue(QuestState.payStep(p, 1) && QuestState.payStep(p, 2), "each step pays the first time");
            h.assertFalse(QuestState.payStep(p, 1) || QuestState.payStep(p, 2), "not again after changing path");
            h.assertTrue(QuestState.payStep(p, 3), "the steps not yet walked still pay");
        } finally {
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void smallHandInsAreWorthOne(GameTestHelper h) {
        var small = Set.of(Task.Kind.FEED_ELDER, Task.Kind.FEED_QUEEN, Task.Kind.MOSS_BALL, Task.Kind.SHARE_PREY, Task.Kind.PLAY);
        Set<Task.Kind> seen = new java.util.HashSet<>();
        for (Kin.Role you : Kin.Role.values()) {
            for (Kin.Role giver : Kin.Role.values()) {
                for (long day = 0; day < 40; day++) {
                    Task t = Tasks.dutyFor(you, giver, Clan.THUNDER, "Mossheart", UUID.nameUUIDFromBytes(("g" + giver).getBytes()), day);
                    if (t == null || !small.contains(t.kind)) continue;
                    seen.add(t.kind);
                    h.assertTrue(t.standing == 1, t.kind + " gives 1 standing, not " + t.standing);
                }
            }
        }
        h.assertTrue(seen.equals(small), "every small hand-in was looked at: " + seen);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aCatWhoLeftOnTheWayIsAskedAgain(GameTestHelper h) {
        ServerPlayer p = BookGameTests.player(h, "sweep_arriving");
        try {
            Journey.setStage(p, Journey.Stage.PLAYING);
            Starts.loggedIn(p);
            h.assertTrue(Journey.stage(p) == Journey.Stage.PLAYING, "a cat who arrived is left alone");
            Starts.markArriving(p);
            Starts.loggedIn(p);
            h.assertTrue(Journey.stage(p) == Journey.Stage.CHOOSING, "one who never arrived chooses again");
        } finally {
            p.getPersistentData().remove("greatjourney.arriving");
            Journey.setStage(p, Journey.Stage.NEW);
            Dialog.forget(p);
        }
        h.succeed();
    }
}
