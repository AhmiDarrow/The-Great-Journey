package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** What The Great Journey has set up in a world: the four Clans' Warrior Cats Events ids, leaders and filled camps. */
public final class LifeData extends SavedData {
    private static final String ID = "greatjourney_clans";

    boolean created;
    final Map<Clan, UUID> clanIds = new EnumMap<>(Clan.class);
    final Map<Clan, String> leaders = new EnumMap<>(Clan.class);
    final Set<Clan> populated = EnumSet.noneOf(Clan.class);
    boolean tunnelsPopulated;
    /** How this world is played (null until the world is created; see {@link Mode}). */
    Mode mode;
    String tunnelKeeper;
    /** Lives each leader has left (nine, as in the books). */
    final Map<Clan, Integer> lives = new EnumMap<>(Clan.class);
    /** Cats who have died and joined StarClan, oldest first, for future StarClan dreams. */
    final List<Fallen> starClan = new ArrayList<>();

    /** A cat who has gone to hunt with StarClan: name, Clan, how they died ("old age", "sickness", "battle"...) and when. */
    public record Fallen(String name, Clan clan, String cause, long gameTime) {}

    public static final int LEADER_LIVES = 9;

    public static LifeData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(LifeData::new, LifeData::load), ID);
    }

    /** Reads saved data back (also used by tests to check a save round trip). */
    public static LifeData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries);
    }

    private static LifeData load(CompoundTag tag, HolderLookup.Provider registries) {
        LifeData d = new LifeData();
        d.created = tag.getBoolean("created");
        d.tunnelsPopulated = tag.getBoolean("tunnelsPopulated");
        if (tag.contains("mode")) d.mode = Mode.parse(tag.getString("mode"));
        if (tag.contains("tunnelKeeper")) d.tunnelKeeper = tag.getString("tunnelKeeper");
        for (Clan c : Clan.values()) {
            CompoundTag ct = tag.getCompound(c.name());
            if (ct.hasUUID("id")) d.clanIds.put(c, ct.getUUID("id"));
            if (ct.contains("leader")) d.leaders.put(c, ct.getString("leader"));
            if (ct.getBoolean("populated")) d.populated.add(c);
            if (ct.contains("lives")) d.lives.put(c, ct.getInt("lives"));
        }
        for (Tag t : tag.getList("starclan", Tag.TAG_COMPOUND)) {
            CompoundTag f = (CompoundTag) t;
            try {
                d.starClan.add(new Fallen(f.getString("name"), Clan.valueOf(f.getString("clan")), f.getString("cause"), f.getLong("time")));
            } catch (IllegalArgumentException ignored) {
                // an unknown Clan name in an old save: skip the entry
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("created", created);
        tag.putBoolean("tunnelsPopulated", tunnelsPopulated);
        if (mode != null) tag.putString("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        if (tunnelKeeper != null) tag.putString("tunnelKeeper", tunnelKeeper);
        for (Clan c : Clan.values()) {
            CompoundTag ct = new CompoundTag();
            if (clanIds.containsKey(c)) ct.putUUID("id", clanIds.get(c));
            if (leaders.containsKey(c)) ct.putString("leader", leaders.get(c));
            ct.putBoolean("populated", populated.contains(c));
            if (lives.containsKey(c)) ct.putInt("lives", lives.get(c));
            tag.put(c.name(), ct);
        }
        ListTag list = new ListTag();
        for (Fallen f : starClan) {
            CompoundTag ft = new CompoundTag();
            ft.putString("name", f.name());
            ft.putString("clan", f.clan().name());
            ft.putString("cause", f.cause());
            ft.putLong("time", f.gameTime());
            list.add(ft);
        }
        tag.put("starclan", list);
        return tag;
    }

    /** How this world is played, or null if it has not been decided yet. */
    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        setDirty();
    }

    public UUID clanId(Clan c) {
        return clanIds.get(c);
    }

    public String leader(Clan c) {
        return leaders.getOrDefault(c, c.name());
    }

    public boolean created() {
        return created;
    }

    /** All four Clans' ids, for matching a cat's Clan. Returns null when the id is not one of the lake Clans. */
    public Clan clanOf(UUID clanId) {
        if (clanId == null) return null;
        for (Map.Entry<Clan, UUID> e : clanIds.entrySet()) if (clanId.equals(e.getValue())) return e.getKey();
        return null;
    }

    public void setLeader(Clan c, String name) {
        leaders.put(c, name);
        lives.put(c, LEADER_LIVES);
        setDirty();
    }

    public int lives(Clan c) {
        return lives.getOrDefault(c, LEADER_LIVES);
    }

    /** The leader loses one life; returns how many are left. */
    public int loseLife(Clan c) {
        int left = Math.max(0, lives(c) - 1);
        lives.put(c, left);
        setDirty();
        return left;
    }

    /** StarClan's memory keeps this many names; the oldest fade first. */
    static final int STARCLAN_KEPT = 500;

    public void rememberInStarClan(String name, Clan clan, String cause, long gameTime) {
        starClan.add(new Fallen(name, clan, cause, gameTime));
        while (starClan.size() > STARCLAN_KEPT) starClan.remove(0);
        setDirty();
    }

    public List<Fallen> starClan() {
        return List.copyOf(starClan);
    }

    /** The Campaign founds the Clans itself (in the old forest first): it records each one here like setup does. */
    public void adopt(Clan c, UUID id, String leader) {
        clanIds.put(c, id);
        leaders.put(c, leader);
        lives.putIfAbsent(c, LEADER_LIVES);
        setDirty();
    }

    /** Marks the Clans as founded (the Campaign's old-forest Clans count). */
    public void markCreated() {
        created = true;
        setDirty();
    }

    /** Keeps the lake camps from being filled with new cats: in the Campaign the Clans bring their own. */
    public void markPopulated(Clan c) {
        populated.add(c);
        setDirty();
    }
}
