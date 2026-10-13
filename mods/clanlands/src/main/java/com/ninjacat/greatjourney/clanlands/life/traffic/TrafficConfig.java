package com.ninjacat.greatjourney.clanlands.life.traffic;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings for the monsters on the Thunderpaths (per world, in serverconfig/clanlands-traffic-server.toml). */
public final class TrafficConfig {
    private TrafficConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.DoubleValue THUNDERPATH_CHANCE;
    public static final ModConfigSpec.DoubleValue HIGHWAY_CHANCE;
    public static final ModConfigSpec.DoubleValue DAMAGE;
    public static final ModConfigSpec.IntValue MAX_MONSTERS;
    public static final ModConfigSpec.IntValue MAX_PER_ROAD;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Twoleg monsters (cars and trucks) that roar along the Thunderpaths when a cat is near the road.",
                "You hear them before you see them: wait for a gap, then cross.").push("traffic");
        ENABLED = b.comment("Whether monsters drive on the Thunderpaths at all.").define("enabled", true);
        THUNDERPATH_CHANCE = b.comment("Chance each second that a monster comes along a Thunderpath while a cat is near it.")
                .defineInRange("thunderpathChance", 1.0 / 12, 0.0, 1.0);
        HIGHWAY_CHANCE = b.comment("Chance each second that a monster comes along the great highway while a cat is near it.")
                .defineInRange("highwayChance", 1.0 / 3, 0.0, 1.0);
        DAMAGE = b.comment("Damage a monster does to a cat (or any creature) it hits. 14 is seven hearts.")
                .defineInRange("damage", 14.0, 0.0, 1000.0);
        MAX_MONSTERS = b.comment("Most monsters on the roads at once, across the whole world.").defineInRange("maxMonsters", 24, 0, 200);
        MAX_PER_ROAD = b.comment("Most monsters near one stretch of road (about 400 blocks of it) at once.").defineInRange("maxPerRoad", 6, 0, 50);
        b.pop();
        SPEC = b.build();
    }
}
