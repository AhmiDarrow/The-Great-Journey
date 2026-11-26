package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Free Mode's things from the books that are a player's own: the warrior code, prophecies, and what comes after. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class BookGameTests {
    private BookGameTests() {}

    static ServerPlayer player(GameTestHelper h, String name) {
        ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        QuestState.reset(p);
        Dialog.forget(p);
        var at = h.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    @GameTest(template = "empty")
    public static void aProphecyIsKeptUntilItIsWorkedOut(GameTestHelper h) {
        var data = new com.ninjacat.greatjourney.clanlands.life.story.StoryData();
        var clan = com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.THUNDER;
        h.assertTrue(com.ninjacat.greatjourney.clanlands.life.story.Prophecy.of(data) == null, "none to begin with");
        var given = new com.ninjacat.greatjourney.clanlands.life.story.Prophecy(clan,
                com.ninjacat.greatjourney.clanlands.life.story.Prophecy.Meaning.FIRE, 500, false);
        com.ninjacat.greatjourney.clanlands.life.story.Prophecy.keep(data, given);
        var saved = com.ninjacat.greatjourney.clanlands.life.story.StoryData.fromTag(data.save(new CompoundTag(), h.getLevel().registryAccess()));
        h.assertTrue(given.equals(com.ninjacat.greatjourney.clanlands.life.story.Prophecy.of(saved)), "its words, Clan and time are saved");
        for (var m : com.ninjacat.greatjourney.clanlands.life.story.Prophecy.Meaning.values()) {
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.Kind.valueOf(m.name()) != null, m + " is a thing that can happen");
        }
        ServerPlayer p = player(h, "book_riddle");
        h.assertTrue(Riddles.dream(p, new com.ninjacat.greatjourney.clanlands.life.story.StoryData(), net.minecraft.util.RandomSource.create(1)).isEmpty(),
                "a cat of no Clan is given no prophecy");
        h.assertFalse(Riddles.guess(p, "fire"), "and has nothing to guess at");
        com.ninjacat.greatjourney.clanlands.life.story.Prophecy.keep(data, null);
        h.assertTrue(com.ninjacat.greatjourney.clanlands.life.story.Prophecy.of(data) == null, "once come true, it is gone");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void quietThingsInCamp(GameTestHelper h) {
        ServerPlayer p = player(h, "book_camp");
        try {
            var life = new com.ninjacat.greatjourney.clanlands.life.LifeData();
            h.assertTrue(CampMoments.vigil(p, life, 100) == null, "no vigil for a cat of no Clan");
            var tale = CampMoments.tale(p, "Oldfur");
            h.assertTrue(tale.size() >= 2 && tale.stream().allMatch(l -> !l.text().isEmpty()), "an elder always has something to say");
        } finally {
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aFoundedClanHasALifeOfItsOwn(GameTestHelper h) {
        var bluestar = com.ninjacat.greatjourney.clanlands.life.story.Teller.BLUESTAR;
        h.assertTrue(Homestead.weights(bluestar, 5, true, true).size() == Homestead.Happening.values().length, "all of it can happen to a small Clan");
        var full = Homestead.weights(bluestar, Homestead.FULL, true, false);
        h.assertFalse(full.containsKey(Homestead.Happening.WANDERER) || full.containsKey(Homestead.Happening.KITS)
                || full.containsKey(Homestead.Happening.HUNGER), "a full Clan takes in no more, and a busy founder is asked no more");
        h.assertTrue(Homestead.weights(com.ninjacat.greatjourney.clanlands.life.story.Teller.YELLOWFANG, 5, true, true).get(Homestead.Happening.SICKNESS)
                > Homestead.weights(bluestar, 5, true, true).get(Homestead.Happening.SICKNESS), "the storyteller has a say here too");
        // duties asked by a founded Clan's cats: no book Clan behind them, any role, any day
        for (var role : Kin.Role.values()) {
            for (var you : Kin.Role.values()) {
                for (long day = 0; day < 12; day++) Tasks.dutyFor(you, role, null, "Somecat", UUID.randomUUID(), day);
            }
        }
        ServerPlayer p = player(h, "book_founder");
        UUID clan = UUID.randomUUID();
        var at = h.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
        var cat = net.snowteb.warriorcats_events.entity.ModEntities.WCAT.get().create(h.getLevel());
        var other = net.snowteb.warriorcats_events.entity.ModEntities.WCAT.get().create(h.getLevel());
        try {
            for (var c : new net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity[] {cat, other}) {
                c.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                c.setCustomName(net.minecraft.network.chat.Component.literal("Wander"));
                c.addTag(Homestead.WANDERER_TAG);
            }
            h.assertFalse(Homestead.settle(p, cat, clan, "Testclan", "maybe"), "a wanderer is welcomed or sent on");
            h.getLevel().addFreshEntity(cat);
            cat.removeTag(Homestead.WANDERER_TAG);
            cat.addTag(Homestead.WANDERER_TAG);
            h.assertTrue(Homestead.settle(p, cat, clan, "Testclan", "welcome") && clan.equals(cat.getClanUUID()) && cat.isTame()
                    && !cat.getTags().contains(Homestead.WANDERER_TAG), "welcomed, they are the Clan's");
            h.assertTrue(Homestead.settle(p, other, clan, "Testclan", "turn") && other.isRemoved(), "sent on, they are gone");
            h.assertTrue(Homestead.happen(p, clan, "Testclan", java.util.List.of(cat), Homestead.Happening.HUNGER)
                    && QuestState.duties(p).size() == 1, "a hungry Clan asks its founder to hunt");
        } finally {
            cat.discard();
            other.discard();
            Homestead.reset();
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theDarkForestHasItsPrice(GameTestHelper h) {
        ServerPlayer p = player(h, "book_dark");
        try {
            var bluestar = com.ninjacat.greatjourney.clanlands.life.story.Teller.BLUESTAR;
            h.assertFalse(DarkForest.noticed(p, bluestar), "an honourable cat is left to sleep");
            h.assertTrue(DarkForest.noticed(p, com.ninjacat.greatjourney.clanlands.life.story.Teller.TIGERSTAR), "but not under Tigerstar");
            QuestState.root(p).putInt("honour", DarkForest.NOTICED);
            h.assertTrue(DarkForest.noticed(p, bluestar), "nor a cat who breaks the code");
            QuestState.addStanding(p, 50);
            h.assertFalse(DarkForest.answer(p, "train"), "nothing to accept before he comes");
            for (int i = 0; i < DarkForest.RECKONING; i++) {
                h.assertTrue(DarkForest.reckoning(p).isEmpty(), "StarClan waits");
                DarkForest.offer(p, i);
                h.assertTrue(DarkForest.answer(p, "train"), "his training taken");
            }
            h.assertTrue(QuestState.standing(p) < 50 && Deeds.honour(p) < DarkForest.NOTICED, "at a cost in standing and honour");
            h.assertFalse(DarkForest.reckoning(p).isEmpty(), "then StarClan asks for an answer");
            h.assertTrue(DarkForest.answer(p, "keep") && DarkForest.silenced(p), "keep on, and StarClan falls silent");
            int standing = QuestState.standing(p);
            h.assertTrue(DarkForest.reckoning(p).isEmpty() && !DarkForest.answer(p, "keep") && QuestState.standing(p) == standing,
                    "StarClan does not ask, or charge, a second time");
            h.assertFalse(DarkForest.answer(p, "turn"), "the way back is taken only when it is offered");
            DarkForest.wayBack(p);
            h.assertTrue(DarkForest.answer(p, "turn") && !DarkForest.silenced(p) && Deeds.honour(p) >= 0, "turn away, and the way back opens");
            h.assertTrue(DarkForest.reckoning(p).isEmpty(), "with nothing left to answer for");
        } finally {
            QuestState.reset(p);
            Dialog.forget(p);
            p.removeAllEffects();
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aFriendAcrossTheBorder(GameTestHelper h) {
        ServerPlayer p = player(h, "book_friend");
        var clan = com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.RIVER;
        var at = h.absolutePos(new net.minecraft.core.BlockPos(4, 2, 4));
        Friendship.reset();
        try {
            h.assertFalse(Friendship.answer(p, "stay"), "no friend has spoken");
            h.assertTrue(Friendship.meet(p, clan, at), "a cat waits across the scent line");
            String name = Friendship.name(p);
            for (int i = 1; i <= Friendship.TEST; i++) {
                Friendship.greeting(p, p);
                h.assertFalse(Friendship.answer(p, "hunt"), "the favour is not asked yet");
                Friendship.greeting(p, p);
                h.assertTrue(Friendship.answer(p, "stay") && Friendship.bond(p) == i, "staying to talk draws them closer");
                Friendship.answer(p, "truth");                      // if a Clanmate happened to notice
            }
            h.assertTrue(name.equals(Friendship.name(p)), "it is the same cat each time");
            Friendship.seen(p);
            QuestState.root(p).getCompound("friend").remove("known");
            Friendship.seen(p);
            int honour = Deeds.honour(p);
            h.assertTrue(Friendship.answer(p, "deny") && Deeds.honour(p) < honour, "a lie costs honour");
            Friendship.greeting(p, p);
            h.assertFalse(Friendship.answer(p, "stay"), "a friend met this often asks something harder");
            h.assertTrue(Friendship.answer(p, "refuse") && Friendship.bond(p) == 0 && Friendship.name(p).isEmpty(), "and choosing your Clan ends it");
        } finally {
            Friendship.reset();
            h.getLevel().getEntitiesOfClass(net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.class, p.getBoundingBox().inflate(16),
                    c -> c.getTags().contains(Friendship.TAG)).forEach(net.minecraft.world.entity.Entity::discard);
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theWarriorCodeIsKeptOrBrokenByChoice(GameTestHelper h) {
        ServerPlayer p = player(h, "book_code");
        try {
            h.assertFalse(CodeMoments.answer(p, "first"), "no moment, nothing to answer");
            var m = CodeMoments.MOMENTS.get(0);
            QuestState.addStanding(p, 10);
            CodeMoments.offer(p, m);
            h.assertFalse(CodeMoments.answer(p, "third"), "only the two answers");
            h.assertTrue(CodeMoments.answer(p, "first"), "the moment is answered");
            h.assertTrue(QuestState.standing(p) == 10 + m.first().standing() && Deeds.honour(p) == m.first().honour(), "and moves standing and honour");
            h.assertFalse(CodeMoments.answer(p, "first"), "once only");
            for (int i = 0; i < 30; i++) Deeds.weigh(p, -50, -2);
            h.assertTrue(QuestState.standing(p) == 0 && Deeds.honour(p) == -Deeds.HONOUR_LIMIT, "neither falls without end");
            var seen = new java.util.HashSet<String>();
            var root = new CompoundTag();
            var r = net.minecraft.util.RandomSource.create(7);
            for (int i = 0; i < CodeMoments.MOMENTS.size(); i++) {
                var next = CodeMoments.pick(root, r);
                seen.add(next.id());
                root.putString("codeMet", root.getString("codeMet") + "," + next.id());
            }
            h.assertTrue(seen.size() == CodeMoments.MOMENTS.size(), "every moment is met before any comes again");
            for (var x : CodeMoments.MOMENTS) {
                h.assertTrue(x.first().honour() != x.second().honour() || x.first().standing() != x.second().standing(), x.id() + ": the answers differ");
            }
        } finally {
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }
}
