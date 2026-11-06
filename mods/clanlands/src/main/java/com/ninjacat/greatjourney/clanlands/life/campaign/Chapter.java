package com.ninjacat.greatjourney.clanlands.life.campaign;

/**
 * One player's place in the Campaign's story. The world moves through four acts (see {@link CampaignData}); each
 * player walks these chapters at their own pace, and a player who joins late begins at the chapter that fits where
 * the world's story already is.
 */
public enum Chapter {
    CHOOSE(1, "The Gathering at Fourtrees", "Under the full moon the four Clans meet at Fourtrees. Choose how your story begins."),
    OUTSIDER(1, "Beyond the Clans", "You belong to no Clan. Wander into a Clan's land, and perhaps a patrol will find you."),
    APPRENTICE(1, "An apprentice of the old forest", "Learn the ways of your Clan from your mentor and earn your warrior name."),
    WARRIOR_LIFE(1, "A warrior of the old forest", "Serve your Clan: help your Clanmates with their duties, patrol your borders, hunt for the Clan."),
    DARK_TIMES(2, "The forest's end", "Twoleg monsters are tearing down the trees. Prey is scarce. The Clans wait for a sign from StarClan."),
    SUN_DROWN(2, "Where the sun drowns", "StarClan sent a sign: go to where the sun drowns, far to the west, and listen to what you are told there."),
    TELL_CLAN(2, "The message", "Carry Midnight's message home to your Clan's camp: the Clans must leave the forest, together."),
    HIGHWAY(3, "The Great Journey: the Thunderpath", "The four Clans travel together. First the great Thunderpath, roaring with monsters: cross it safely."),
    FARM(3, "The Great Journey: Twoleg farms", "Beyond the Thunderpath lie Twoleg farms. Find shelter there and rest."),
    PASS(3, "The Great Journey: the mountains", "Climb into the mountains. Watch the sky: eagles hunt up here."),
    TRIBE(3, "The Great Journey: the Tribe", "High in the mountains, where water falls, lives the Tribe of Rushing Water. Find their cave."),
    LAKE(3, "The Great Journey: the lake", "Go on, past the mountains, until you can look down on still water."),
    LAKE_CAMP(4, "A new home", "Each Clan has found its own land around the lake. Go to your new camp."),
    MOONPOOL(4, "The Moonpool", "Somewhere in the hills above the lake is a place where StarClan's stars shine in still water. Find it."),
    GATHERING(4, "The first Gathering", "At night, when the moon is up, go to the island in the lake for the first Gathering at your new home."),
    COMPLETE(4, "The story continues", "The Clans have found their new home. Live your life among them, as you like.");

    public final int act;
    public final String title, goal;

    Chapter(int act, String title, String goal) {
        this.act = act;
        this.title = title;
        this.goal = goal;
    }

    /** The chapter's title for a cat on the medicine cat's path ({@code healer}) or the warrior's. */
    String title(boolean healer) {
        return healer && this == WARRIOR_LIFE ? "A medicine cat of the old forest" : title;
    }

    /** What the chapter asks of a cat on the medicine cat's path ({@code healer}) or the warrior's. */
    String goal(boolean healer) {
        if (!healer) return goal;
        return switch (this) {
            case APPRENTICE -> "Learn the ways of a medicine cat from your Clan's medicine cat, and earn your full name.";
            case WARRIOR_LIFE -> "Heal your Clan: cure the sick, keep the herb store full, and share tongues with StarClan at the half moon.";
            case DARK_TIMES -> "Twoleg monsters are tearing down the trees and the herbs with them. The sick are many. The Clans wait for a sign from StarClan.";
            default -> goal;
        };
    }

    /** Where a cat who joins (or chooses a Clan) now begins, given where the world's story stands. */
    static Chapter startFor(int worldAct, boolean signGiven, boolean departed, boolean lakeFounded) {
        if (lakeFounded) return MOONPOOL;
        if (departed) return HIGHWAY;
        if (worldAct >= 2) return signGiven ? SUN_DROWN : DARK_TIMES;
        return APPRENTICE;
    }

    /**
     * The chapter a player should move to because the WORLD moved on without them (another player's step), or the
     * same chapter. Never moves a player backwards.
     */
    static Chapter catchUp(Chapter now, int worldAct, boolean signGiven, boolean midnightMet, boolean departed, boolean lakeFounded) {
        if (now == CHOOSE || now == OUTSIDER || now == COMPLETE) return now;    // no Clan yet: the patrols come first
        Chapter want = now;
        if (worldAct >= 2 && now.ordinal() < DARK_TIMES.ordinal()) want = DARK_TIMES;
        if (midnightMet && want.ordinal() < TELL_CLAN.ordinal()) want = TELL_CLAN;
        if (departed && want.ordinal() < HIGHWAY.ordinal()) want = HIGHWAY;
        if (lakeFounded && want.ordinal() < LAKE_CAMP.ordinal()) want = LAKE_CAMP;
        return want;
    }

    /** The next chapter after this one is finished by the player. */
    Chapter next() {
        return this == COMPLETE ? COMPLETE : values()[ordinal() + 1];
    }

    static Chapter parse(String s) {
        try {
            return s == null || s.isEmpty() ? CHOOSE : valueOf(s);
        } catch (IllegalArgumentException e) {
            return CHOOSE;
        }
    }
}
