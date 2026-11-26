package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The storytellers ({@link Teller}, {@link Director}): what each weighs more and less, the pace of trouble, warnings
 * that wait for their time, and who is spared. The tests that touch the world's own story put it back as it was.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class StoryGameTests {
    private StoryGameTests() {}

    @GameTest(template = "empty")
    public static void eachStorytellerHasTheirOwnTroubles(GameTestHelper h) {
        h.assertTrue(Teller.SPOTTEDLEAF.weight("RAID") < 1 && Teller.SPOTTEDLEAF.weight("KITS") > 1, "Spottedleaf: few attacks, more kits");
        h.assertTrue(Teller.YELLOWFANG.weight("SICKNESS") > 1 && Teller.YELLOWFANG.hunger > 1, "Yellowfang: sickness, and a hard leaf-bare");
        h.assertTrue(Teller.TIGERSTAR.weight("RAID") > 1 && Teller.TIGERSTAR.skirmish > Teller.BLUESTAR.skirmish, "Tigerstar: war");
        for (ClanEvents.Kind k : ClanEvents.Kind.values()) h.assertTrue(Teller.BLUESTAR.weight(k.name()) == 1, "Bluestar is the balance as it was: " + k);
        for (Teller t : Teller.values()) {
            h.assertTrue(Teller.byKey(t.key()) == t && !t.blurb.isEmpty(), t + " can be asked for by name");
            for (Teller.Danger d : Teller.Danger.values()) h.assertTrue(!t.omen(d).isEmpty(), t + " warns of " + d);
            h.assertFalse(t.judge("Testheart", -5, 0).equals(t.judge("Testheart", 5, 0)), t + " speaks of how a cat has lived");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void troubleKeepsItsDistanceAfterALoss(GameTestHelper h) {
        long now = 1_000_000;
        StoryData d = new StoryData();
        d.put("calmSince", now);
        h.assertTrue(Director.pace(Teller.BLUESTAR, d, now, 0) == 1, "an ordinary day");
        d.put("mournUntil", now + 100);
        h.assertTrue(Director.pace(Teller.BLUESTAR, d, now, 0) < 1, "in mourning, trouble keeps away");
        d.put("mournUntil", 0);
        d.put("calmSince", now - Director.LONG_CALM - 1);
        h.assertTrue(Director.pace(Teller.BLUESTAR, d, now, 0) > 1, "after a long calm, something comes");
        d.put("calmSince", now);
        h.assertTrue(Director.pace(Teller.TIGERSTAR, d, now, 200) > Director.pace(Teller.TIGERSTAR, d, now, 0), "Tigerstar sends more to the strong");
        double quiet = Director.pace(Teller.MIDNIGHT, d, now, 0);
        d.put("burst", Director.STORM);
        h.assertTrue(Director.pace(Teller.MIDNIGHT, d, now, 0) > quiet * 4, "Midnight's storm is nothing like her calm");
        h.assertTrue(Teller.TIGERSTAR.mercy == 0 && Teller.SPOTTEDLEAF.mercy > Teller.BLUESTAR.mercy, "and only Tigerstar gives no rest at all");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aDangerIsWarnedOfBeforeItComes(GameTestHelper h) {
        var level = h.getLevel();
        StoryData data = StoryData.get(level);
        CompoundTag before = data.save(new CompoundTag(), level.registryAccess());
        try {
            data.keep("omen", null);
            data.setTeller(Teller.SPOTTEDLEAF);
            h.assertFalse(Director.forewarn(level, Clan.THUNDER, ClanEvents.Kind.KITS), "good news comes without warning");
            h.assertTrue(Director.forewarn(level, Clan.THUNDER, ClanEvents.Kind.BADGER), "a badger is warned of");
            CompoundTag omen = data.part("omen");
            h.assertTrue(omen.getString("kind").equals("BADGER") && omen.getLong("due") == level.getGameTime() + Teller.SPOTTEDLEAF.omenLead,
                    "and waits its time");
            h.assertTrue(Director.forewarn(level, Clan.RIVER, ClanEvents.Kind.RAID) && data.part("omen").getString("kind").equals("BADGER"),
                    "one warning waits at a time, and no other danger strikes unforetold");
            Director.everySecond(level);
            h.assertFalse(data.part("omen").isEmpty(), "it does not come early");
            h.assertTrue(Director.spares(level, "battle") && !Director.spares(level, "old age"), "Spottedleaf lets cats die only of old age");
            data.setTeller(Teller.TIGERSTAR);
            data.put("mournUntil", 0);
            Director.mourn(level);
            h.assertTrue(data.number("mournUntil") == 0, "Tigerstar does not wait on anyone's grief");
            data.setTeller(Teller.BLUESTAR);
            Director.mourn(level);
            h.assertTrue(data.number("mournUntil") > level.getGameTime(), "Bluestar does");
            h.assertFalse(Director.spares(level, "battle"), "and under Bluestar cats can fall in battle");
            h.assertTrue(StoryData.fromTag(data.save(new CompoundTag(), level.registryAccess())).teller() == Teller.BLUESTAR, "the choice is saved");
        } finally {
            restore(data, before);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aBorderSkirmishCanBeSettledThreeWays(GameTestHelper h) {
        var level = h.getLevel();
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("story_border".getBytes()), "story_border"));
        com.ninjacat.greatjourney.clanlands.life.quests.QuestState.reset(p);
        var at = h.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Skirmish.reset();
        try {
            h.assertTrue(Skirmish.begin(level, Clan.THUNDER, Clan.SHADOW, p, at), "a neighbour's patrol is found over the border");
            var first = Skirmish.now.cats.stream().map(level::getEntity).toList();
            h.assertTrue(first.size() >= 2 && first.stream().allMatch(c -> c instanceof net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity w
                    && w.getTags().contains(Skirmish.TAG) && w.getClanUUID() == null || ((net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity) c)
                    .getClanUUID().equals(net.snowteb.warriorcats_events.clan.ClanData.EMPTY_UUID)), "its cats are nobody's members");
            h.assertFalse(Skirmish.begin(level, Clan.WIND, Clan.RIVER, p, at), "one skirmish at a time");
            h.assertTrue(Skirmish.answer(p, "warn") && Skirmish.now != null, "a patrol does not heed a cat of no weight");
            com.ninjacat.greatjourney.clanlands.life.quests.QuestState.addStanding(p, Skirmish.LISTENED_TO);
            h.assertTrue(Skirmish.heeded(level, p, Clan.THUNDER) == com.ninjacat.greatjourney.clanlands.life.quests.Deeds.grown(p),
                    "standing counts only for a named cat");
            h.assertTrue(Skirmish.answer(p, "fight") && Skirmish.now.fighting, "claws can be chosen");
            var one = (net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity) level.getEntity(Skirmish.now.cats.get(0));
            one.hurt(one.damageSources().generic(), 10_000);
            h.assertTrue(one.isAlive(), "a trespasser is never killed");
            int before = com.ninjacat.greatjourney.clanlands.life.quests.QuestState.standing(p);
            for (var id : Skirmish.now.cats) if (level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity c) c.setHealth(1);
            Skirmish.step(level, p);
            h.assertTrue(Skirmish.now == null, "beaten, they all run, and it is over");
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.quests.QuestState.standing(p) > before, "and the border held earns standing");

            h.assertTrue(Skirmish.begin(level, Clan.THUNDER, Clan.WIND, p, at), "another day, another patrol");
            before = com.ninjacat.greatjourney.clanlands.life.quests.QuestState.standing(p);
            StoryData story = StoryData.get(level);
            long owed = story.number("owed.THUNDER");
            h.assertTrue(Skirmish.answer(p, "mercy") && Skirmish.now == null, "they can be let go");
            h.assertTrue(story.number("owed.THUNDER") == owed + 1 || com.ninjacat.greatjourney.clanlands.world.ClanWorld.current() != null,
                    "with the camp's pile out of reach, the prey is owed from it");
            story.put("owed.THUNDER", owed);
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.quests.QuestState.standing(p) < before, "which costs standing");
            h.assertFalse(Skirmish.answer(p, "warn"), "and there is nothing left to answer");
        } finally {
            if (Skirmish.now != null) for (var id : Skirmish.now.cats) {
                var e = level.getEntity(id);
                if (e != null) e.discard();
            }
            Skirmish.reset();
            com.ninjacat.greatjourney.clanlands.life.quests.QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void leafBareHungerIsFedFromThePile(GameTestHelper h) {
        h.assertTrue(Hunger.need(0, 1) == 0 && Hunger.need(4, 1) == 2 && Hunger.need(5, 1) == 3 && Hunger.need(4, Teller.YELLOWFANG.hunger) == 3,
                "half a piece a day for each who eats first, and more in Yellowfang's winter");
        var pile = new net.neoforged.neoforge.items.ItemStackHandler(3);
        pile.setStackInSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COD, 2));
        pile.setStackInSlot(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RABBIT, 3));
        h.assertTrue(Hunger.pieces(pile) == 5, "what the pile really holds is counted");
        h.assertTrue(Hunger.eat(pile, 4) == 4 && Hunger.pieces(pile) == 1, "they eat from it");
        h.assertTrue(Hunger.eat(pile, 4) == 1 && Hunger.pieces(pile) == 0, "and can eat no more than is there");
        h.assertTrue(Hunger.sickens(Teller.BLUESTAR, 1, 3) && !Hunger.sickens(Teller.BLUESTAR, 3, 3), "going short makes a cat ill");
        h.assertTrue(!Hunger.sickens(Teller.SPOTTEDLEAF, 1, 3) && Hunger.sickens(Teller.SPOTTEDLEAF, 0, 3), "under Spottedleaf only an empty pile does");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eachStorytellerKeepsItsOwnPace(GameTestHelper h) {
        var level = h.getLevel();
        StoryData data = StoryData.get(level);
        CompoundTag before = data.save(new CompoundTag(), level.registryAccess());
        long rest = 20L * 600;
        try {
            data.keep("omen", null);
            data.setTeller(Teller.BLUESTAR);
            h.assertTrue(Director.rest(level, rest) == rest, "Bluestar's Clans rest as long as is set");
            data.setTeller(Teller.SPOTTEDLEAF);
            h.assertTrue(Director.rest(level, rest) > rest, "Spottedleaf spaces events out");
            data.setTeller(Teller.TIGERSTAR);
            h.assertTrue(Director.rest(level, rest) < rest, "Tigerstar gives less rest");
            data.setTeller(Teller.MIDNIGHT);
            h.assertTrue(Director.rest(level, rest) > rest, "Midnight's calm is long");
            data.put("burst", Director.STORM);
            h.assertTrue(Director.rest(level, rest) == 0, "and her storm gives no rest at all: events bunch");
            data.put("stormWarned", 0);
            h.assertTrue(Director.forewarn(level, Clan.THUNDER, ClanEvents.Kind.BADGER), "her storm is warned of once");
            h.assertTrue(Director.forewarn(level, Clan.THUNDER, ClanEvents.Kind.RAID) && data.part("omen").getString("kind").equals("BADGER"),
                    "nothing else strikes before what she warned of");
            data.keep("omen", null);
            h.assertFalse(Director.forewarn(level, Clan.THUNDER, ClanEvents.Kind.RAID), "once it has come, the rest of the storm comes at once");
            data.setTeller(Teller.BLUESTAR);
            h.assertTrue(Director.forewarn(level, Clan.THUNDER, ClanEvents.Kind.RAID), "under the others no danger strikes beside a waiting warning");
        } finally {
            restore(data, before);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void noCatDiesInABorderFightAndClawsStartOne(GameTestHelper h) {
        var level = h.getLevel();
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("story_claws".getBytes()), "story_claws"));
        var at = h.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        var mate = net.snowteb.warriorcats_events.entity.ModEntities.WCAT.get().create(level);
        Skirmish.reset();
        try {
            h.assertTrue(Skirmish.begin(level, Clan.THUNDER, Clan.SHADOW, p, at), "trespassers");
            var foe = (net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity) level.getEntity(Skirmish.now.cats.get(0));
            foe.hurt(level.damageSources().playerAttack(p), 1);
            h.assertTrue(Skirmish.now.fighting && foe.getTags().contains(Skirmish.HOSTILE_TAG), "clawing them starts the fight, with no word chosen");
            mate.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + 0.5);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(at), net.minecraft.world.entity.MobSpawnType.EVENT, null);
            level.addFreshEntity(mate);
            mate.invulnerableTime = 0;
            mate.hurt(level.damageSources().mobAttack(foe), 10_000);
            h.assertTrue(mate.isAlive(), "a trespasser's blow kills no cat");
        } finally {
            if (Skirmish.now != null) for (var id : Skirmish.now.cats) {
                var e = level.getEntity(id);
                if (e != null) e.discard();
            }
            Skirmish.reset();
            mate.discard();
            com.ninjacat.greatjourney.clanlands.life.quests.QuestState.reset(p);
        }
        h.succeed();
    }

    /** Puts the world's story back as it was before a test changed it. */
    static void restore(StoryData data, CompoundTag before) {
        for (String k : new String[] {"teller", "burst", "stormWarned", "omen", "mournUntil", "calmSince", "prophecy", "owed.THUNDER"}) {
            if (before.contains(k, net.minecraft.nbt.Tag.TAG_COMPOUND)) data.keep(k, before.getCompound(k));
            else if (before.contains(k, net.minecraft.nbt.Tag.TAG_STRING)) data.setTeller(Teller.byKey(before.getString(k)));
            else if (before.contains(k)) data.put(k, before.getLong(k));
            else data.keep(k, null);
        }
    }
}
