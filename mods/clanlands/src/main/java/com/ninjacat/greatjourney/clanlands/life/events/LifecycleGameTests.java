package com.ninjacat.greatjourney.clanlands.life.events;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** Clan events across restarts: nothing is left half done when the server stops mid-event. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class LifecycleGameTests {
    private LifecycleGameTests() {}

    @GameTest(template = "empty")
    public static void aKitLeftLostByARestartGoesHome(GameTestHelper h) {
        WCatEntity kit = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(kit != null, "a Warrior Cats Events cat can be made");
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        BlockPos home = h.absolutePos(new BlockPos(3, 2, 3));
        kit.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        kit.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        kit.setRank(WCatEntity.Rank.KIT);
        kit.setHomePosition(home);
        kit.wanderCenter = at;
        kit.addTag(Happenings.LOST_TAG);
        ClanEvents.reset();                                 // the server stopped mid-search: no event remembers the kit
        Happenings.onJoin(kit);                             // ...and the kit loads again after the restart
        h.assertTrue(!kit.getTags().contains(Happenings.LOST_TAG), "the kit is no longer lost");
        h.assertTrue(home.equals(kit.wanderCenter), "the kit heads for home, got " + kit.wanderCenter);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aStopForgetsEventsInFlight(GameTestHelper h) {
        ClanEvents.activeKind = ClanEvents.Kind.BADGER;
        ClanEvents.activeEntities.add(java.util.UUID.randomUUID());
        ClanEvents.raiders.put(java.util.UUID.randomUUID(), com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.WIND);
        ClanEvents.reset();
        h.assertTrue(ClanEvents.activeKind == null && ClanEvents.activeEntities.isEmpty() && ClanEvents.raiders.isEmpty(),
                "a new world starts with no event running");
        h.succeed();
    }
}
