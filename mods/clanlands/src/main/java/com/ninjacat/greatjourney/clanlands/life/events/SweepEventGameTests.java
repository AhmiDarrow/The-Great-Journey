package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** The 0.1.10 bug sweep and balance pass, as far as the events go. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class SweepEventGameTests {
    private SweepEventGameTests() {}

    private static WCatEntity cat(GameTestHelper h, WCatEntity.Rank rank) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setRank(rank);
        cat.setNoAi(true);
        return cat;
    }

    @GameTest(template = "empty")
    public static void aRaiderLeftByARestartIsTurnedAway(GameTestHelper h) {
        ClanEvents.reset();
        WCatEntity raider = cat(h, WCatEntity.Rank.WARRIOR);
        raider.addTag("greatjourney_raider");
        h.assertFalse(Happenings.staleRaider(raider, false), "a raider just stepping out stays");
        h.assertTrue(Happenings.staleRaider(raider, true), "one saved with the world, its raid forgotten, does not come back");
        ClanEvents.raiders.put(raider.getUUID(), Clan.WIND);
        h.assertFalse(Happenings.staleRaider(raider, true), "one whose raid is still on reloads");
        ClanEvents.raiders.remove(raider.getUUID());
        h.assertFalse(Happenings.staleRaider(cat(h, WCatEntity.Rank.WARRIOR), true), "an ordinary cat reloads");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aMedicineCatTendsOneCough(GameTestHelper h) {
        WCatEntity healer = cat(h, WCatEntity.Rank.MEDICINE), one = cat(h, WCatEntity.Rank.WARRIOR), two = cat(h, WCatEntity.Rank.WARRIOR);
        one.getList().add(DiseaseTypes.WHITECOUGH.create());
        two.getList().add(DiseaseTypes.WHITECOUGH.create());
        h.assertTrue(one.hasDisease(DiseaseTypes.WHITECOUGH) && two.hasDisease(DiseaseTypes.WHITECOUGH), "two cats cough");
        h.assertFalse(ClanEvents.tendCoughs(h.getLevel(), Clan.THUNDER, List.of(one, two)), "with no medicine cat, nobody is cured");
        h.assertTrue(ClanEvents.tendCoughs(h.getLevel(), Clan.THUNDER, List.of(healer, one, two)), "the medicine cat tends one");
        h.assertTrue(one.hasDisease(DiseaseTypes.WHITECOUGH) != two.hasDisease(DiseaseTypes.WHITECOUGH), "and only one at a time");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tigerstarIsStillTheMostWarlike(GameTestHelper h) {
        for (Teller t : Teller.values()) {
            h.assertTrue(t == Teller.TIGERSTAR || Teller.TIGERSTAR.skirmish > t.skirmish, "more border fights than under " + t.title);
        }
        h.assertTrue(Teller.TIGERSTAR.skirmish <= 0.25, "but not one in every other roll");
        h.succeed();
    }
}
