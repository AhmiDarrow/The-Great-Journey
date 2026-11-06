package com.ninjacat.greatjourney.clanlore.role;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * What Clan Lore keeps on each cat (a data attachment, saved with the cat): its role, a leader's lives, and its mentor
 * or apprentice. (Its age past twelve moons is kept by the traits, {@code Traits.moons}.)
 * <p>
 * The last rank, name and kit size are kept too, saved, so a change that happens on the cat's first tick after
 * loading (a kit grown while the world was saved) is still seen as a change and gets its ceremony.
 */
public final class RoleData implements INBTSerializable<CompoundTag> {
    Role role = Role.NONE;
    int lives;
    UUID mentor, apprentice;
    /** The mentor's and apprentice's names as last seen, for the name tag while the other cat is out of reach. */
    String mentorName = "", apprenticeName = "";
    WCatEntity.Rank lastRank;
    boolean lastBaby;
    String lastName = "";

    // not saved: the last view sent to clients, and how many looks in a row a queen has had no kits
    transient RoleNet.RoleSync sent;
    transient int queenEmpty;

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putString("role", role.name());
        if (lives > 0) t.putInt("lives", lives);
        if (mentor != null) t.putUUID("mentor", mentor);
        if (apprentice != null) t.putUUID("apprentice", apprentice);
        if (!mentorName.isEmpty()) t.putString("mentorName", mentorName);
        if (!apprenticeName.isEmpty()) t.putString("apprenticeName", apprenticeName);
        if (lastRank != null) t.putString("lastRank", lastRank.name());
        t.putBoolean("lastBaby", lastBaby);
        if (!lastName.isEmpty()) t.putString("lastName", lastName);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        try {
            role = Role.valueOf(t.getString("role"));
        } catch (IllegalArgumentException e) {
            role = Role.NONE;
        }
        lives = Math.max(0, Math.min(Roles.MAX_LIVES, t.getInt("lives")));
        mentor = t.hasUUID("mentor") ? t.getUUID("mentor") : null;
        apprentice = t.hasUUID("apprentice") ? t.getUUID("apprentice") : null;
        mentorName = t.getString("mentorName");
        apprenticeName = t.getString("apprenticeName");
        lastRank = null;
        if (t.contains("lastRank")) {
            try {
                lastRank = WCatEntity.Rank.valueOf(t.getString("lastRank"));
            } catch (IllegalArgumentException ignored) {
                // a rank WCE no longer has: seen afresh
            }
        }
        lastBaby = t.getBoolean("lastBaby");
        lastName = t.getString("lastName");
    }
}
