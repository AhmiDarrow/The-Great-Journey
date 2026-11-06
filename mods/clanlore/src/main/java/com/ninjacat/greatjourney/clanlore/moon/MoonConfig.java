package com.ninjacat.greatjourney.clanlore.moon;

import java.util.function.Supplier;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code clanlore-moon-common.toml} (COMMON): the truce and the night as the Clans keep them. The night's bounds default to
 * Better Days' own (nightStart 12500, dayStart 23500), so a pack that moves those can move these to match.
 */
final class MoonConfig {
    private MoonConfig() {}

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue TRUCE, TRUCE_EVERYWHERE, STOP_TARGETING, CLOUD_OVER, THUNDER;
    static final ModConfigSpec.IntValue WARNINGS, CLOUD_TICKS, NIGHT_START, DAY_START;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("moon");
        TRUCE = b.comment("On a full-moon night, cats and players of different Clans cannot hurt each other in a truce area",
                "(a Gathering place a pack registers, such as The Great Journey's island).").define("truce", true);
        TRUCE_EVERYWHERE = b.comment("The truce holds everywhere on a full-moon night, not only at the Gathering places.",
                "For worlds without a registered Gathering place.").define("truceEverywhere", false);
        STOP_TARGETING = b.comment("During the truce, Clan cats also stop picking cats of other Clans as targets.").define("stopTargeting", true);
        WARNINGS = b.comment("How many blows a player is warned about before the truce counts as broken (within a minute).")
                .defineInRange("warnings", 1, 0, 10);
        CLOUD_OVER = b.comment("When a player breaks the truce, clouds cover the moon (rain for a little while), as in the books,",
                "and the Gathering ends for that night.").define("cloudOver", true);
        CLOUD_TICKS = b.comment("How long the rain lasts, in ticks.").defineInRange("cloudTicks", 2400, 200, 24000);
        THUNDER = b.comment("A thunderstorm instead of plain rain.").define("thunder", false);
        NIGHT_START = b.comment("When the night begins (time of day in ticks). Better Days' nightStart.").defineInRange("nightStart", 12500, 12000, 14000);
        DAY_START = b.comment("When the day begins (time of day in ticks). Better Days' dayStart.").defineInRange("dayStart", 23500, 22000, 24000);
        b.pop();
        SPEC = b.build();
    }

    /** A value, or its default before the config has loaded (game tests, early calls). */
    static <T> T get(Supplier<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
