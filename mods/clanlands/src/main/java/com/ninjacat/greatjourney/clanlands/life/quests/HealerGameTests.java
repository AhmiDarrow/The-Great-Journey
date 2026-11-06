package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The medicine cat's side ({@link Patients}, {@link Tasks#healerDutyFor}, {@link StoryTasks}, {@link Errands}): every
 * Clanmate has healing work as well as a warrior's, worth the same; a sick cat is cured the way Warrior Cats Events
 * cures it; the Campaign's errands match between the two paths and finish on their own.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class HealerGameTests {
    private HealerGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        return net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    @GameTest(template = "empty")
    public static void everyClanmateHasHealersWorkWorthTheSame(GameTestHelper h) {
        UUID cat = UUID.randomUUID();
        for (Kin.Role role : new Kin.Role[] {Kin.Role.MEDICINE, Kin.Role.DEPUTY, Kin.Role.WARRIOR, Kin.Role.ELDER, Kin.Role.QUEEN, Kin.Role.APPRENTICE}) {
            for (long day = 0; day < 6; day++) {
                Task heal = Tasks.healerDutyFor(role, Clan.THUNDER, "Testpelt", cat, day);
                h.assertTrue(heal != null && !heal.objectives.isEmpty(), role + " has healer's work on day " + day);
                Task war = Tasks.dutyFor(role, Clan.THUNDER, "Testpelt", cat, day);
                if (war != null) {
                    h.assertTrue(Math.abs(heal.standing - war.standing) <= 1, role + ": a healer's duty (" + heal.standing
                            + ") is worth about a warrior's (" + war.standing + ")");
                }
                h.assertTrue(Task.load(heal.save()) != null, role + "'s healer duty saves and loads");
            }
        }
        h.assertTrue(Tasks.healerDutyFor(Kin.Role.KIT, Clan.THUNDER, "Testkit", cat, 0) == null, "kits give no duties");
        h.assertTrue(Tasks.healerDutyFor(Kin.Role.LEADER, Clan.THUNDER, "Teststar", cat, 0) == null, "nor does the leader");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSickCatIsCuredTheWceWay(GameTestHelper h) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(cat != null, "a Warrior Cats Events cat can be made");
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setCustomName(Component.literal("Sneezepelt"));
        h.getLevel().addFreshEntity(cat);
        long now = h.getLevel().getGameTime();
        List<Task.Objective> cures = Patients.sicken(List.of(cat), DiseaseTypes.FEVER, 1, now, h.getLevel().getRandom());
        h.assertTrue(cures.size() == 1, "one patient, with a fever");
        Task.Objective o = cures.get(0);
        h.assertTrue(o.type == Task.Objective.Type.CURE && o.text.contains("Sneezepelt") && o.text.contains("fever"), "named in the journal: " + o.text);
        h.assertFalse(Patients.cured(h.getLevel(), o, now + 1), "not checked before the disease has taken hold");
        h.runAfterDelay(10, () -> {
            h.assertTrue(cat.hasDisease(DiseaseTypes.FEVER), "Warrior Cats Events has given the cat its fever");
            h.assertFalse(Patients.cured(h.getLevel(), o, now + Patients.SETTLE + 1), "still feverish");
            cat.removeDisease(DiseaseTypes.FEVER);
            h.assertTrue(Patients.cured(h.getLevel(), o, now + Patients.SETTLE + 1), "cured once the fever is gone");
            cat.discard();
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void theStorysErrandsMatchAndFinishOnTheirOwn(GameTestHelper h) {
        ServerPlayer p = player(h, "errand_test_a");
        QuestState.reset(p);
        Dialog.forget(p);
        try {
            QuestState.setPath(p, QuestState.Path.WARRIOR);
            StoryTasks.Def war = StoryTasks.journeyStart(p);
            QuestState.setPath(p, QuestState.Path.MEDICINE);
            StoryTasks.Def heal = StoryTasks.journeyStart(p);
            h.assertTrue(!war.id().equals(heal.id()), "a warrior and a medicine cat get their own errand");
            h.assertTrue(war.task().standing == heal.task().standing && war.task().xp == heal.task().xp, "worth the same");
            StoryTasks.Def pass = StoryTasks.mountains(p, new double[] {0, 0}, new double[] {300, 0}, List.of());
            h.assertTrue(pass.task().objectives.get(0).type == Task.Objective.Type.BRING, "with no Clanmate near, juniper for the cold");

            heal.give(p);
            h.assertTrue(Errands.busy(p) && Errands.given(p, "journey.herbs"), "the errand is under way, and given once");
            h.assertTrue(QuestState.active(p).stream().anyMatch(t -> t.kind == Task.Kind.ERRAND), "and in the journal");
            p.getInventory().clearContent();
            for (String id : List.of("sorrel_leaves", "burnet_leaves", "chamomile_stems", "daisy_leaves")) {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("warriorcats_events", id));
                if (item == Items.AIR) item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("warriorcats_events", id.substring(0, id.indexOf('_'))));
                p.getInventory().add(new ItemStack(item, 2));
            }
            int before = QuestState.standing(p);
            Dialog.forget(p);
            Errands.tick(p);
            h.assertFalse(Errands.busy(p), "done once everything is carried");
            h.assertTrue(QuestState.standing(p) == before + heal.task().standing, "and the Clan's standing grows");
            h.assertTrue(p.getInventory().isEmpty(), "the herbs are shared out");
            h.succeed();
        } finally {
            Dialog.forget(p);
            QuestState.reset(p);
            p.getInventory().clearContent();
        }
    }
}
