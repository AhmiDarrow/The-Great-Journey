package com.ninjacat.greatjourney.clanlands.life.story;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import net.minecraft.nbt.CompoundTag;

/**
 * A prophecy: a riddle StarClan gives at the Moonpool that comes true days later. A cat who works out what it means
 * before then has the Clan ready for it. One waits at a time in a world, and it is kept with the storyteller's data.
 */
public record Prophecy(Clan clan, Meaning meaning, long due, boolean solved) {
    /** What a prophecy can mean; the event it becomes is {@code ClanEvents.Kind}'s of the same name. */
    public enum Meaning {
        RAID("Enemies at the entrance", "\"When the border sleeps, claws will find the way in.\""),
        BADGER("A great beast", "\"A heavy shadow with a striped face will walk where kits play.\""),
        SICKNESS("A sickness", "\"The song of the dens will turn to coughing, unless leaves are gathered.\""),
        FIRE("Fire", "\"A red flower will open in the branches, and the Clan must not stand beneath it.\"");

        public final String guess;
        public final String riddle;

        Meaning(String guess, String riddle) {
            this.guess = guess;
            this.riddle = riddle;
        }
    }

    private static final String KEY = "prophecy";

    /** The one waiting, or null. */
    public static Prophecy of(StoryData data) {
        CompoundTag t = data.part(KEY);
        if (t.isEmpty()) return null;
        try {
            return new Prophecy(Clan.valueOf(t.getString("clan")), Meaning.valueOf(t.getString("meaning")), t.getLong("due"), t.getBoolean("solved"));
        } catch (IllegalArgumentException ex) {
            data.keep(KEY, null);
            return null;
        }
    }

    public static void keep(StoryData data, Prophecy p) {
        if (p == null) {
            data.keep(KEY, null);
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putString("clan", p.clan.name());
        t.putString("meaning", p.meaning.name());
        t.putLong("due", p.due);
        t.putBoolean("solved", p.solved);
        data.keep(KEY, t);
    }
}
