package com.ninjacat.greatjourney.clanlands.life.gathering;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The seasons keep their pace when the clock is wound back for a moon meeting ({@link Gatherings#windClock}). */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class SeasonGameTests {
    private SeasonGameTests() {}

    @GameTest(template = "empty", batch = "season_clock")
    public static void windingTheClockBackDoesNotMoveTheSeasons(GameTestHelper h) {
        ServerLevel ow = h.getLevel().getServer().overworld();
        int[] before = new int[1];
        h.runAfterDelay(3, () -> {
            before[0] = seasonTicks(h, ow);
            Gatherings.windClock(ow, ow.getDayTime() - 100);
        });
        h.runAfterDelay(8, () -> {
            int moved = seasonTicks(h, ow) - before[0];
            Gatherings.windClock(ow, ow.getDayTime() + 100);
            h.assertTrue(Math.abs(moved) < 100, "the season clock moved " + moved + " ticks when the day's clock went back 100");
            h.succeed();
        });
    }

    private static int seasonTicks(GameTestHelper h, ServerLevel ow) {
        try {
            Object state = Class.forName("sereneseasons.api.season.SeasonHelper").getMethod("getSeasonState", Level.class).invoke(null, ow);
            return (int) Class.forName("sereneseasons.api.season.ISeasonState").getMethod("getSeasonCycleTicks").invoke(state);
        } catch (ReflectiveOperationException e) {
            h.fail("Serene Seasons is not in the test run: " + e);
            return 0;
        }
    }
}
