package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The Campaign's bookkeeping: the world's acts survive a save, and late joiners start where the story is. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class CampaignGameTests {
    private CampaignGameTests() {}

    @GameTest(template = "empty")
    public static void theWorldsStorySurvivesASave(GameTestHelper h) {
        var registries = h.getLevel().registryAccess();
        CampaignData d = CampaignData.fromTag(new CompoundTag(), registries);
        h.assertTrue(d.act() == 1 && !d.departed && !d.lakeFounded && d.clansInForest(), "a new Campaign starts in the old forest");
        d.forestFounded = true;
        d.advance(2, 100);
        d.advance(1, 200);                                           // never backwards
        d.felled = 7;
        d.signGiven = true;
        d.midnightMet = true;
        d.departed = true;
        d.forestPopulated.add(Clan.WIND);
        d.roster.put(Clan.RIVER, List.of("Reedwhisker|WARRIOR|0", "Minnowkit|KIT|1"));
        CampaignData back = CampaignData.fromTag(d.save(new CompoundTag(), registries), registries);
        h.assertTrue(back.act() == 2 && back.actStartedAt == 100, "act and its start are kept, got act " + back.act());
        h.assertTrue(back.felled == 7 && back.signGiven && back.midnightMet && back.departed && back.forestFounded, "flags are kept");
        h.assertTrue(back.forestPopulated.contains(Clan.WIND) && !back.forestPopulated.contains(Clan.THUNDER), "filled camps are kept");
        h.assertTrue(back.roster.get(Clan.RIVER).equals(List.of("Reedwhisker|WARRIOR|0", "Minnowkit|KIT|1")), "the travelling roster is kept");
        h.assertTrue(back.clansInForest(), "the Clans live in the forest until they settle at the lake");
        back.lakeFounded = true;
        h.assertTrue(!back.clansInForest(), "after settling, the Clans live at the lake");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aQuietCatIsToldOfTheSignWhereItStands(GameTestHelper h) {
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("quiet_sign".getBytes()), "quiet_sign"));
        com.ninjacat.greatjourney.clanlands.life.Dialog.forget(p);
        h.assertTrue(!com.ninjacat.greatjourney.clanlands.life.quests.QuestState.quiet(p), "off until a cat asks for it");
        com.ninjacat.greatjourney.clanlands.life.quests.Quiet.set(p, true);
        try {
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.quests.QuestState.quiet(p), "kept on the cat");
            var where = p.level();
            Campaign.sign(p);
            h.assertTrue(p.level() == where && Campaign.chapter(p) == Chapter.SUN_DROWN, "the story moves on without carrying the cat off");
            h.assertFalse(com.ninjacat.greatjourney.clanlands.life.Dialog.busy(p), "and no window waits to be answered");
        } finally {
            com.ninjacat.greatjourney.clanlands.life.quests.Quiet.set(p, false);
            com.ninjacat.greatjourney.clanlands.life.Dialog.forget(p);
        }
        h.assertTrue(!com.ninjacat.greatjourney.clanlands.life.quests.QuestState.quiet(p), "and off again");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayersOwnCatIsNotLeftInTheForest(GameTestHelper h) {
        var cat = net.snowteb.warriorcats_events.entity.ModEntities.WCAT.get().create(h.getLevel());
        h.assertFalse(ForestClans.playersOwn(cat, Clan.THUNDER), "a wild cat is the Clan's");
        cat.setTame(true, false);
        cat.setOwnerUUID(com.ninjacat.greatjourney.clanlands.life.BookClans.standInLeader(Clan.THUNDER));
        h.assertFalse(ForestClans.playersOwn(cat, Clan.THUNDER), "one owned by the Clan's stand-in leader travels with the Clan");
        cat.setOwnerUUID(java.util.UUID.randomUUID());
        h.assertTrue(ForestClans.playersOwn(cat, Clan.THUNDER), "a player's cat stays where it is, and theirs");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lateCatsStartWhereTheStoryIs(GameTestHelper h) {
        h.assertTrue(Chapter.startFor(1, false, false, false) == Chapter.APPRENTICE, "act 1: an apprentice of the old forest");
        h.assertTrue(Chapter.startFor(2, false, false, false) == Chapter.DARK_TIMES, "act 2 before the sign: dark times");
        h.assertTrue(Chapter.startFor(2, true, false, false) == Chapter.SUN_DROWN, "act 2 after the sign: go to where the sun drowns");
        h.assertTrue(Chapter.startFor(3, true, true, false) == Chapter.HIGHWAY, "act 3: on the Great Journey");
        h.assertTrue(Chapter.startFor(4, true, true, true) == Chapter.MOONPOOL, "act 4: at the lake");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theWorldCarriesPlayersForwardNeverBack(GameTestHelper h) {
        h.assertTrue(Chapter.catchUp(Chapter.APPRENTICE, 2, false, false, false, false) == Chapter.DARK_TIMES, "the forest's end reaches every apprentice");
        h.assertTrue(Chapter.catchUp(Chapter.SUN_DROWN, 2, true, true, false, false) == Chapter.TELL_CLAN, "someone met Midnight: carry the message");
        h.assertTrue(Chapter.catchUp(Chapter.DARK_TIMES, 3, true, true, true, false) == Chapter.HIGHWAY, "the Clans left: join the journey");
        h.assertTrue(Chapter.catchUp(Chapter.PASS, 4, true, true, true, true) == Chapter.LAKE_CAMP, "the Clans settled: go to the new camp");
        h.assertTrue(Chapter.catchUp(Chapter.MOONPOOL, 4, true, true, true, true) == Chapter.MOONPOOL, "never moved backwards");
        h.assertTrue(Chapter.catchUp(Chapter.CHOOSE, 3, true, true, true, false) == Chapter.CHOOSE, "a cat without a Clan still chooses first");
        h.assertTrue(Chapter.HIGHWAY.next() == Chapter.FARM && Chapter.COMPLETE.next() == Chapter.COMPLETE, "chapters follow in order");
        h.assertTrue(Chapter.parse("nonsense") == Chapter.CHOOSE && Chapter.parse("TRIBE") == Chapter.TRIBE, "chapters read back from saves");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theWayIsToldInCompassWords(GameTestHelper h) {
        h.assertTrue(Campaign.compass(-1, 0).equals("west"), "-x is west");
        h.assertTrue(Campaign.compass(0, -1).equals("north"), "-z is north");
        h.assertTrue(Campaign.compass(1, 1).equals("south-east"), "+x +z is south-east");
        h.assertTrue(Campaign.compass(-1, 0.3).equals("west"), "mostly west is west");
        h.assertTrue(Campaign.compass(-0.7, -0.7).equals("north-west"), "-x -z is north-west");
        h.succeed();
    }

    /** The whole sun-drown beach counts, not only the spot by Midnight's sett (Act 2 never ended otherwise). */
    @GameTest(template = "empty")
    public static void theSunDrownBeachIsBigEnoughToFind(GameTestHelper h) {
        var w = com.ninjacat.greatjourney.clanlands.world.ClanWorld.current();
        if (w == null) {
            h.succeed();                                    // not a Clan world: no journey to check
            return;
        }
        var j = w.journey;
        double[] v = j.seaward(), sd = j.sunDrownPlace();
        // walk along the shore line (across the seaward vector) and count how much of it is sun-drown beach or sea
        int hits = 0;
        for (int t = -300; t <= 300; t += 50) {
            var s = j.sample(sd[0] - v[1] * t, sd[1] + v[0] * t);
            if (s.weight() > 0.5 && (s.zone() == com.ninjacat.greatjourney.clanlands.layout.Zone.SUN_DROWN_SHORE
                    || s.zone() == com.ninjacat.greatjourney.clanlands.layout.Zone.SUN_DROWN_SEA)) hits++;
        }
        h.assertTrue(hits >= 9, "most of 600 blocks of shore counts as the sun-drown-place (" + hits + "/13)");
        h.succeed();
    }
}
