package com.ninjacat.greatjourney.clanlands.life.events;

import java.lang.reflect.Method;
import net.minecraft.world.level.Level;

/**
 * The season, and whether it is leaf-bare. Asks Serene Seasons when it is installed (through reflection, so Clanlands does not need
 * it); otherwise counts days, with a year of 288 days (as the pack sets Serene Seasons) and its last quarter as leaf-bare.
 */
final class Seasons {
    private Seasons() {}

    private static Method getState, getSeason;
    private static boolean looked;

    /** The four seasons as the Clans name them. */
    enum Season { NEWLEAF, GREENLEAF, LEAF_FALL, LEAF_BARE }

    static boolean isLeafBare(Level level) {
        return season(level) == Season.LEAF_BARE;
    }

    /** The current season: Serene Seasons' when installed, else a 288-day year in four equal quarters. */
    static Season season(Level level) {
        try {
            if (!looked) {
                looked = true;
                Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
                getState = helper.getMethod("getSeasonState", Level.class);
            }
            if (getState != null) {
                Object state = getState.invoke(null, level);
                if (getSeason == null) getSeason = state.getClass().getMethod("getSeason");
                return switch (String.valueOf(getSeason.invoke(state)).toUpperCase(java.util.Locale.ROOT)) {
                    case "SPRING" -> Season.NEWLEAF;
                    case "SUMMER" -> Season.GREENLEAF;
                    case "AUTUMN" -> Season.LEAF_FALL;
                    default -> Season.LEAF_BARE;
                };
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            getState = null;
        }
        long day = level.getDayTime() / 24000L;
        return Season.values()[(int) (day % 288 / 72)];
    }
}
