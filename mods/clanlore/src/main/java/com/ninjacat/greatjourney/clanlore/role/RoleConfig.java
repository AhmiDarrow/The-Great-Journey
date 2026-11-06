package com.ninjacat.greatjourney.clanlore.role;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings for roles and ceremonies (config/clanlore-common.toml, section [roles]). */
public final class RoleConfig {
    private RoleConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_ROLES;
    public static final ModConfigSpec.BooleanValue APPRENTICE_CEREMONY, MENTORS, MENTOR_WALKS, WARRIOR_CEREMONY, KITS_GROW_UP;
    public static final ModConfigSpec.BooleanValue ELDERS, ELDERS_FIGHT_LESS, QUEENS, LEADER_LIVES;
    public static final ModConfigSpec.IntValue RETIRE_MOONS, UNKNOWN_AGE_MAX, RETIRE_GAP_MINUTES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Leaders, elders, queens and mentors for Warrior Cats Events' cats, and the ceremonies between.").push("roles");
        SHOW_ROLES = b.comment("Show a cat's role (\"Leader - 7 lives\", \"Elder\", \"Mentor of Ashpaw\"...) under its name tag.")
                .define("showRoles", true);
        APPRENTICE_CEREMONY = b.comment("When a kit is made an apprentice, the Clan's leader holds the apprentice ceremony.")
                .define("apprenticeCeremony", true);
        MENTORS = b.comment("Each apprentice is given a mentor: a warrior of its Clan with no apprentice, one nearby if there is one.")
                .define("mentors", true);
        MENTOR_WALKS = b.comment("Now and then an apprentice walks beside its mentor for a while (only where the pack does not run the camp).")
                .define("mentorWalks", true);
        WARRIOR_CEREMONY = b.comment("When Warrior Cats Events grows an apprentice into a warrior, the leader gives it its warrior name before the Clan.")
                .define("warriorCeremony", true);
        KITS_GROW_UP = b.comment("Clan kits that Warrior Cats Events did not see born (summoned, or made by another mod) grow into",
                "apprentices like its own kits do, instead of jumping straight to warrior.").define("kitsGrowUp", true);
        ELDERS = b.comment("Old warriors and deputies ask to join the elders (never the leader).").define("elders", true);
        RETIRE_MOONS = b.comment("A warrior this old, in moons, retires to the elders' den. A grown cat ages a moon every",
                "kitGrowthMinutes / 12 real minutes, Warrior Cats Events' own pace (ten minutes by default).").defineInRange("retireMoons", 96, 24, 1000);
        RETIRE_GAP_MINUTES = b.comment("At most one retirement per Clan in this many minutes, and never one that leaves the Clan",
                "fewer than three warriors.").defineInRange("retireGapMinutes", 20, 1, 1440);
        UNKNOWN_AGE_MAX = b.comment("A grown cat whose age nobody knows (one from before Clan Lore, or found wild) is given an age",
                "between 12 moons and this.").defineInRange("unknownAgeMax", 84, 12, 1000);
        ELDERS_FIGHT_LESS = b.comment("Elders leave hunting and fighting to the warriors: they only fight back when attacked.")
                .define("eldersFightLess", true);
        QUEENS = b.comment("A she-cat expecting or nursing kits is a queen until her kits are apprenticed.").define("queens", true);
        LEADER_LIVES = b.comment("A leader has nine lives: a death takes one and StarClan sends them back, until the last; then",
                "the deputy becomes leader. A pack that runs its own Clans (The Great Journey) keeps theirs itself.")
                .define("leaderLives", true);
        b.pop();
        SPEC = b.build();
    }
}
