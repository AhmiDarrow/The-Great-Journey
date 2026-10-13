package com.ninjacat.greatjourney.clanlands.life.route;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Which of the Great Journey's inhabitants have been settled in this world (each only once). */
final class RouteData extends SavedData {
    private static final String ID = "greatjourney_route";

    boolean tribe, midnight, purdy, eagles;

    static RouteData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(RouteData::new, RouteData::load), ID);
    }

    private static RouteData load(CompoundTag tag, HolderLookup.Provider registries) {
        RouteData d = new RouteData();
        d.tribe = tag.getBoolean("tribe");
        d.midnight = tag.getBoolean("midnight");
        d.purdy = tag.getBoolean("purdy");
        d.eagles = tag.getBoolean("eagles");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("tribe", tribe);
        tag.putBoolean("midnight", midnight);
        tag.putBoolean("purdy", purdy);
        tag.putBoolean("eagles", eagles);
        return tag;
    }
}
