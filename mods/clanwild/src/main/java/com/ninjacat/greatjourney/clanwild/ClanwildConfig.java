package com.ninjacat.greatjourney.clanwild;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClanwildConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SPEED_STRIKE;
    public static final ModConfigSpec.DoubleValue SPEED_THRESHOLD;
    public static final ModConfigSpec.DoubleValue BONUS_PER_SPEED;
    public static final ModConfigSpec.DoubleValue MAX_BONUS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Speed strike: a melee hit landed while running or leaping does extra damage.").push("speed_strike");
        SPEED_STRIKE = b.comment("Turn the speed strike on or off.").define("enabled", true);
        SPEED_THRESHOLD = b.comment("Horizontal speed (blocks per tick) below which a hit gets no bonus. Walking is about 0.22, sprinting about 0.28.")
                .defineInRange("threshold", 0.22, 0.0, 2.0);
        BONUS_PER_SPEED = b.comment("Extra damage, as a fraction of the hit, for each block per tick above the threshold.")
                .defineInRange("bonusPerSpeed", 2.0, 0.0, 20.0);
        MAX_BONUS = b.comment("The largest bonus, as a fraction of the hit (0.5 = +50%).")
                .defineInRange("maxBonus", 0.5, 0.0, 5.0);
        b.pop();
        SPEC = b.build();
    }

    private ClanwildConfig() {}
}
