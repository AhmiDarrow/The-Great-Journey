package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The Campaign's world story, shared by every player: which act the Clans are in, what has happened to the old
 * forest, and who the Clans are bringing with them to the lake. Each world-changing step happens once.
 */
public final class CampaignData extends SavedData {
    private static final String ID = "greatjourney_campaign";

    /** The world's act: 1 the old forest, 2 the forest's end, 3 the Great Journey, 4 the lake, 5 the story is told. */
    int act = 1;
    long actStartedAt = -1;
    boolean forestFounded;
    final Set<Clan> forestPopulated = EnumSet.noneOf(Clan.class);
    /** How many stretches of the old forest the Twolegs have torn down. */
    int felled;
    boolean signGiven;
    boolean midnightMet;
    boolean departed;
    boolean lakeFounded;
    final Set<Clan> lakeArrived = EnumSet.noneOf(Clan.class);
    /** Each Clan's cats when they left the forest: "Name|RANK|gender" lines, to arrive with them at the lake. */
    final Map<Clan, List<String>> roster = new EnumMap<>(Clan.class);
    boolean complete;

    public static CampaignData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(CampaignData::new, CampaignData::load), ID);
    }

    static CampaignData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries);
    }

    private static CampaignData load(CompoundTag tag, HolderLookup.Provider registries) {
        CampaignData d = new CampaignData();
        d.act = Math.max(1, tag.getInt("act"));
        d.actStartedAt = tag.contains("actStartedAt") ? tag.getLong("actStartedAt") : -1;
        d.forestFounded = tag.getBoolean("forestFounded");
        d.felled = tag.getInt("felled");
        d.signGiven = tag.getBoolean("signGiven");
        d.midnightMet = tag.getBoolean("midnightMet");
        d.departed = tag.getBoolean("departed");
        d.lakeFounded = tag.getBoolean("lakeFounded");
        d.complete = tag.getBoolean("complete");
        for (Clan c : Clan.values()) {
            CompoundTag ct = tag.getCompound(c.name());
            if (ct.getBoolean("forestPopulated")) d.forestPopulated.add(c);
            if (ct.getBoolean("lakeArrived")) d.lakeArrived.add(c);
            ListTag l = ct.getList("roster", Tag.TAG_STRING);
            if (!l.isEmpty()) {
                List<String> r = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) r.add(l.getString(i));
                d.roster.put(c, r);
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("act", act);
        tag.putLong("actStartedAt", actStartedAt);
        tag.putBoolean("forestFounded", forestFounded);
        tag.putInt("felled", felled);
        tag.putBoolean("signGiven", signGiven);
        tag.putBoolean("midnightMet", midnightMet);
        tag.putBoolean("departed", departed);
        tag.putBoolean("lakeFounded", lakeFounded);
        tag.putBoolean("complete", complete);
        for (Clan c : Clan.values()) {
            CompoundTag ct = new CompoundTag();
            ct.putBoolean("forestPopulated", forestPopulated.contains(c));
            ct.putBoolean("lakeArrived", lakeArrived.contains(c));
            ListTag l = new ListTag();
            for (String s : roster.getOrDefault(c, List.of())) l.add(net.minecraft.nbt.StringTag.valueOf(s));
            ct.put("roster", l);
            tag.put(c.name(), ct);
        }
        return tag;
    }

    public int act() {
        return act;
    }

    /** Moves the world to a later act (never back). */
    void advance(int to, long gameTime) {
        if (to <= act) return;
        act = to;
        actStartedAt = gameTime;
        setDirty();
    }

    /** Whether the Clans still live in the old forest (before they have settled at the lake). */
    public boolean clansInForest() {
        return !lakeFounded;
    }

    public boolean complete() {
        return complete;
    }
}
