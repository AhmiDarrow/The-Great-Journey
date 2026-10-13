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
}
