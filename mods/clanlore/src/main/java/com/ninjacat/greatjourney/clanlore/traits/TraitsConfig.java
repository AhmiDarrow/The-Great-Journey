package com.ninjacat.greatjourney.clanlore.traits;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings for scars and old wounds (config/clanlore-traits-common.toml). */
public final class TraitsConfig {
    private TraitsConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue BATTLE_SCARS;
    public static final ModConfigSpec.DoubleValue BADLY_HURT;
    public static final ModConfigSpec.DoubleValue SCAR_CHANCE;
    public static final ModConfigSpec.DoubleValue MISSING_EYE_CHANCE;
    public static final ModConfigSpec.BooleanValue PLAYERS;
    public static final ModConfigSpec.BooleanValue GRIZZLE;
    public static final ModConfigSpec.IntValue GRIZZLE_MOONS;
    public static final ModConfigSpec.BooleanValue LAME_LEGS;
    public static final ModConfigSpec.DoubleValue LAME_CHANCE;
    public static final ModConfigSpec.DoubleValue LAME_CHANCE_WRAPPED;
    public static final ModConfigSpec.DoubleValue LAME_SLOWDOWN;
    public static final ModConfigSpec.BooleanValue LIMP;
    public static final ModConfigSpec.BooleanValue DRAW;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Scars, torn ears, grizzled muzzles and old wounds: marks a cat keeps for life.").push("traits");
        BATTLE_SCARS = b.comment("A cat that comes through a fight badly hurt may keep a scar or a torn ear.").define("battleScars", true);
        BADLY_HURT = b.comment("How much of its health (0 to 1) a cat must lose to enemies in one fight to risk a scar.")
                .defineInRange("badlyHurtFraction", 0.6, 0.1, 1.0);
        SCAR_CHANCE = b.comment("Chance that a cat badly hurt in a fight keeps a mark of it (a scar, a torn or nicked ear).")
                .defineInRange("scarChance", 0.2, 0.0, 1.0);
        MISSING_EYE_CHANCE = b.comment("Chance that a cat badly hurt by an eagle or a badger loses an eye.")
                .defineInRange("missingEyeChance", 0.03, 0.0, 1.0);
        PLAYERS = b.comment("Players (who are cats too) can be scarred and lamed like any cat.").define("players", true);
        GRIZZLE = b.comment("Old cats grow grey about the muzzle.").define("grizzle", true);
        GRIZZLE_MOONS = b.comment("Age in moons at which a cat's muzzle turns grey. Warrior Cats Events' own moon length is used",
                        "(its kit growth time divided by 12); a cat counts on from 12 moons once grown.")
                .defineInRange("grizzleMoons", 96, 13, 1000);
        LAME_LEGS = b.comment("A broken paw sometimes heals badly and leaves the cat lame.").define("lameLegs", true);
        LAME_CHANCE = b.comment("Chance a broken paw left unwrapped heals badly.").defineInRange("lameChance", 0.25, 0.0, 1.0);
        LAME_CHANCE_WRAPPED = b.comment("Chance a broken paw heals badly even though it was wrapped.")
                .defineInRange("lameChanceWrapped", 0.05, 0.0, 1.0);
        LAME_SLOWDOWN = b.comment("How much slower a lame cat moves, as a fraction of its speed.")
                .defineInRange("lameSlowdown", 0.08, 0.0, 0.5);
        LIMP = b.comment("Lame cats walk with Warrior Cats Events' limp (they still run normally).").define("limp", true);
        DRAW = b.comment("Draw traits on cats (client side).").define("draw", true);
        b.pop();
        SPEC = b.build();
    }
}
