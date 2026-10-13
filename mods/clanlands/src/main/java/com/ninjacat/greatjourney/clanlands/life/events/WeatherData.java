package com.ninjacat.greatjourney.clanlands.life.events;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The weather event under way and every block it changed, saved with the world: a frozen lake edge, a flood or a
 * drought always gets put back, even if the server stops in the middle. Each change remembers what was there and
 * what the weather put there; it is only undone while that block is still the weather's (a player's block is kept).
 */
public final class WeatherData extends SavedData {
    private static final String ID = "greatjourney_weather";

    /** The running weather event's kind name, or "" when none. */
    String kind = "";
    String clan = "";
    long until;
    boolean forced;
    /** Events that ended but still have changes waiting to be undone in chunks not loaded yet. */
    boolean restoring;
    final Map<Long, BlockState[]> changes = new LinkedHashMap<>();   // pos -> {original, placed}
    /** A fire's centre (as a packed BlockPos) and how far it may burn. */
    long fireCentre;
    int fireRadius;
    /** Cats sent away from danger: their own wander centre, to restore. */
    final Map<UUID, Long> moved = new HashMap<>();

    WeatherData() {}

    public static WeatherData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(WeatherData::new, WeatherData::load), ID);
    }

    /** How many changes are still waiting to be put back. */
    public int pending() {
        return changes.size();
    }

    boolean active() {
        return !kind.isEmpty();
    }

    /** Sets a block for the weather, remembering the first original so it can be put back. */
    public void change(ServerLevel level, BlockPos pos, BlockState to) {
        BlockState was = level.getBlockState(pos);
        changes.computeIfAbsent(pos.asLong(), k -> new BlockState[] {was, to})[1] = to;
        level.setBlock(pos, to, 3);
        setDirty();
    }

    /**
     * Puts back every change in a loaded chunk whose block is still what the weather placed (same block type).
     * Changes in unloaded chunks wait for a later call. Returns how many are still waiting.
     */
    public int restoreLoaded(ServerLevel level) {
        Iterator<Map.Entry<Long, BlockState[]>> it = changes.entrySet().iterator();
        boolean any = false;
        while (it.hasNext()) {
            var e = it.next();
            BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) continue;
            BlockState now = level.getBlockState(pos);
            if (now.getBlock() == e.getValue()[1].getBlock()) level.setBlock(pos, e.getValue()[0], 3);
            it.remove();
            any = true;
        }
        if (any) setDirty();
        return changes.size();
    }

    static WeatherData load(CompoundTag tag, HolderLookup.Provider registries) {
        WeatherData d = new WeatherData();
        var blocks = registries.lookupOrThrow(Registries.BLOCK);
        d.kind = tag.getString("kind");
        d.clan = tag.getString("clan");
        d.until = tag.getLong("until");
        d.forced = tag.getBoolean("forced");
        d.restoring = tag.getBoolean("restoring");
        d.fireCentre = tag.getLong("fireCentre");
        d.fireRadius = tag.getInt("fireRadius");
        for (Tag t : tag.getList("changes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.changes.put(c.getLong("pos"), new BlockState[] {
                    NbtUtils.readBlockState(blocks, c.getCompound("was")), NbtUtils.readBlockState(blocks, c.getCompound("to"))});
        }
        for (Tag t : tag.getList("moved", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.moved.put(c.getUUID("cat"), c.getLong("home"));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("kind", kind);
        tag.putString("clan", clan);
        tag.putLong("until", until);
        tag.putBoolean("forced", forced);
        tag.putBoolean("restoring", restoring);
        tag.putLong("fireCentre", fireCentre);
        tag.putInt("fireRadius", fireRadius);
        ListTag list = new ListTag();
        for (var e : changes.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("pos", e.getKey());
            c.put("was", NbtUtils.writeBlockState(e.getValue()[0]));
            c.put("to", NbtUtils.writeBlockState(e.getValue()[1]));
            list.add(c);
        }
        tag.put("changes", list);
        ListTag moved = new ListTag();
        for (var e : this.moved.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("cat", e.getKey());
            c.putLong("home", e.getValue());
            moved.add(c);
        }
        tag.put("moved", moved);
        return tag;
    }

    /** For tests: a fresh copy that never touches the world's saved data. */
    public static WeatherData detached() {
        return new WeatherData();
    }

    public static WeatherData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag, registries);
    }
}
