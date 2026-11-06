package com.ninjacat.greatjourney.clanwild;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClanwildConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SPEED_STRIKE;
    public static final ModConfigSpec.DoubleValue SPEED_THRESHOLD;
    public static final ModConfigSpec.DoubleValue BONUS_PER_SPEED;
    public static final ModConfigSpec.DoubleValue MAX_BONUS;
    public static final ModConfigSpec.BooleanValue MONSTERS;
    public static final ModConfigSpec.DoubleValue DANGEROUS_SHARE;
    public static final ModConfigSpec.DoubleValue DANGEROUS_SPACING;
    public static final ModConfigSpec.IntValue RAPTOR_CALM;

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
        b.comment("Monsters: zombies, skeletons, creepers and every other monster, outside the Nether and the End.").push("monsters");
        MONSTERS = b.comment("Let monsters spawn on their own. Off, the Clans' world has only Warrior Cats creatures.").define("spawn", false);
        b.pop();
        b.comment("Dangerous animals (the clanwild:dangerous tag: badgers, golden eagles, wolves, foxes, snakes...).").push("dangerous_animals");
        DANGEROUS_SHARE = b.comment("The share of their natural spawns that go ahead (1 = all, as the mods made them; 0 = none).")
                .defineInRange("share", 0.3, 0.0, 1.0);
        DANGEROUS_SPACING = b.comment("No dangerous animal spawns on its own within this many blocks of another (0 = no spacing).")
                .defineInRange("spacing", 48.0, 0.0, 256.0);
        RAPTOR_CALM = b.comment("Seconds a wild golden eagle leaves players alone after it has struck one.")
                .defineInRange("raptorCalmSeconds", 300, 0, 3600);
        b.pop();
        SPEC = b.build();
    }

    private ClanwildConfig() {}
}
