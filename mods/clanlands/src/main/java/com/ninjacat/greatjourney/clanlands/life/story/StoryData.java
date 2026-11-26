package com.ninjacat.greatjourney.clanlands.life.story;

import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What the storyteller keeps for a world: who they are, how the world has fared lately (for the pace of events), a
 * warning still to come true, a prophecy, and what each Clan's pile has been through. One tag, so that each part
 * keeps its own keys.
 */
public final class StoryData extends SavedData {
    private static final String ID = "greatjourney_story";

    private final CompoundTag tag;

    public StoryData() {
        this(new CompoundTag());
    }

    private StoryData(CompoundTag tag) {
        this.tag = tag;
    }

    public static StoryData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new Factory<>(StoryData::new, (t, r) -> fromTag(t)), ID);
    }

    public static StoryData fromTag(CompoundTag t) {
        return new StoryData(t.copy());
    }

    @Override
    public CompoundTag save(CompoundTag out, HolderLookup.Provider registries) {
        out.merge(tag);
        return out;
    }

    /** The world's storyteller; Bluestar where none was ever chosen (worlds from before there were any). */
    public Teller teller() {
        Teller t = Teller.byKey(tag.getString("teller"));
        return t == null ? Teller.BLUESTAR : t;
    }

    public void setTeller(Teller t) {
        tag.putString("teller", t.key());
        tag.remove("burst");
        setDirty();
    }

    /** The storyteller at work in this world, or null in the Campaign, which keeps its own story. */
    public static Teller active(ServerLevel level) {
        return LifeData.get(level.getServer().overworld()).mode() == Mode.CAMPAIGN ? null : get(level).teller();
    }

    public long number(String key) {
        return tag.getLong(key);
    }

    public boolean has(String key) {
        return tag.contains(key);
    }

    public void put(String key, long value) {
        tag.putLong(key, value);
        setDirty();
    }

    /** A named group of keys (empty if not there); call {@link #keep} after changing it. */
    public CompoundTag part(String key) {
        return tag.getCompound(key).copy();
    }

    public void keep(String key, CompoundTag part) {
        if (part == null || part.isEmpty()) tag.remove(key);
        else tag.put(key, part);
        setDirty();
    }
}
