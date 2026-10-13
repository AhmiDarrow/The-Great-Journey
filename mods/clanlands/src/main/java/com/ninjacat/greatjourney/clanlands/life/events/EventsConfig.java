package com.ninjacat.greatjourney.clanlands.life.events;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings for Clan events (per world, in serverconfig/clanlands-events-server.toml). */
public final class EventsConfig {
    private EventsConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue MIN_INTERVAL;
    public static final ModConfigSpec.IntValue MAX_INTERVAL;
    public static final ModConfigSpec.IntValue CLAN_COOLDOWN;
    public static final ModConfigSpec.BooleanValue DEATHS;
    public static final ModConfigSpec.IntValue OLD_AGE_MOONS;
    public static final ModConfigSpec.DoubleValue OLD_AGE_CHANCE;
    public static final ModConfigSpec.IntValue ANNOUNCE_RADIUS;
    public static final ModConfigSpec.BooleanValue FREEZE, FLOOD, DROUGHT, FIRE, FOG;
    public static final ModConfigSpec.IntValue WEATHER_WEIGHT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Random events in the lake Clans' lives: attacks, injuries, sickness, kits, ceremonies, lost kits, deaths.")
                .push("clan_events");
        ENABLED = b.comment("Whether Clan events happen at all.").define("enabled", true);
        MIN_INTERVAL = b.comment("Shortest time between two event rolls, in seconds.").defineInRange("minIntervalSeconds", 180, 30, 7200);
        MAX_INTERVAL = b.comment("Longest time between two event rolls, in seconds.").defineInRange("maxIntervalSeconds", 360, 30, 7200);
        CLAN_COOLDOWN = b.comment("A Clan rests at least this long between its own events, in seconds.")
                .defineInRange("clanCooldownSeconds", 600, 60, 36000);
        DEATHS = b.comment("Clan cats can die (old age, sickness, battle), as in the books, then join StarClan.",
                "When false, a cat that would die is left badly hurt instead.").define("deaths", true);
        OLD_AGE_MOONS = b.comment("Cats at least this old (in moons) may die peacefully of old age.").defineInRange("oldAgeMoons", 120, 24, 1000);
        OLD_AGE_CHANCE = b.comment("Chance per event roll that one old cat in a loaded camp dies peacefully.")
                .defineInRange("oldAgeChance", 0.03, 0.0, 1.0);
        ANNOUNCE_RADIUS = b.comment("Players this close to an event hear about it even if they belong to another Clan.")
                .defineInRange("announceRadius", 48, 0, 512);
        b.comment("Weather from the books: the lake's edge freezing in leaf-bare, newleaf floods in RiverClan's marsh,",
                "greenleaf drought drying the shallows, lightning fires in storms, and morning mist. Everything a weather",
                "event changes is put back when it ends, except a fire's scar.").push("weather");
        FREEZE = b.comment("The lake's shallow edge freezes during leaf-bare, with deeper snow in the camps.").define("freeze", true);
        FLOOD = b.comment("RiverClan's low marsh floods after newleaf rain, for up to 10 minutes.").define("flood", true);
        DROUGHT = b.comment("A greenleaf drought dries the lake's shallowest edge to mud, for up to 15 minutes.").define("drought", true);
        FIRE = b.comment("In a thunderstorm, lightning may set a tree alight in a Clan's territory. The fire is kept small and",
                "goes out after 2 minutes, leaving charred logs.").define("fire", true);
        FOG = b.comment("Morning mist: the hunting goes badly. Nothing else changes.").define("fog", true);
        WEATHER_WEIGHT = b.comment("How likely a weather event is, when its season and sky allow, against the other events (0 = never).")
                .defineInRange("weatherWeight", 3, 0, 100);
        b.pop();
        b.pop();
        SPEC = b.build();
    }
}
