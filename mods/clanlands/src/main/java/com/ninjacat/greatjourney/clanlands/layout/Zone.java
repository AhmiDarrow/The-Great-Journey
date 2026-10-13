package com.ninjacat.greatjourney.clanlands.layout;

import java.util.List;

/**
 * The kinds of ground in the Clan lands. Each uses an existing pack biome (first one present wins; see
 * docs/book-geography.md section 3) so mob spawns, colours, seasons and decoration all work as usual, plus a
 * territory name for the "you are entering" message and a terrain smoothness (vanilla factor).
 */
public enum Zone {
    LAKE("The Lake", 7, "minecraft:river"),
    ISLAND("The Island", 6, "minecraft:forest", "biomesoplenty:woodland"),
    THUNDERCLAN_FOREST("ThunderClan territory", 5, "biomesoplenty:woodland", "minecraft:forest"),
    WINDCLAN_MOOR("WindClan territory", 6, "biomesoplenty:moor", "biomesoplenty:highland", "minecraft:meadow"),
    RIVERCLAN_MARSH("RiverClan territory", 8, "biomesoplenty:marsh", "biomesoplenty:wetland", "minecraft:swamp"),
    RIVERCLAN_WETLAND("RiverClan territory", 7, "biomesoplenty:wetland", "minecraft:meadow"),
    RIVERCLAN_PLAINS("RiverClan territory", 7, "biomesoplenty:grassland", "minecraft:plains"),
    SHADOWCLAN_PINES("ShadowClan territory", 6, "minecraft:old_growth_pine_taiga", "biomesoplenty:coniferous_forest"),
    SHADOWCLAN_MARSH("ShadowClan territory", 8, "biomesoplenty:bog", "minecraft:swamp"),
    MOONPOOL_HILLS("The hills to the Moonpool", 4, "biomesoplenty:rocky_shrubland", "biomesoplenty:crag", "minecraft:windswept_hills"),
    TWOLEG_FARMLAND("Twoleg farmland", 7, "biomesoplenty:pasture", "minecraft:plains"),
    RIVER("The river", 8, "minecraft:river"),
    FOURTREES("Fourtrees", 6, "minecraft:forest"),
    HIGHSTONES_APPROACH("The way to Highstones", 5, "biomesoplenty:highland", "biomesoplenty:rocky_shrubland", "minecraft:meadow"),
    HIGHSTONES("Highstones", 3, "minecraft:stony_peaks", "biomesoplenty:crag"),
    TALLPINES("Tallpines", 6, "minecraft:taiga", "biomesoplenty:coniferous_forest"),
    TWOLEGPLACE("Twolegplace", 7, "minecraft:plains"),
    CARRIONPLACE("Carrionplace", 7, "biomesoplenty:wasteland", "minecraft:plains"),
    HIGHWAY_LAND("The great Thunderpath", 8, "minecraft:plains"),
    MOUNTAINS("The mountains", 3.5, "minecraft:windswept_gravelly_hills", "biomesoplenty:rocky_shrubland", "minecraft:windswept_hills"),
    MOUNTAIN_PEAKS("The mountains", 3, "minecraft:stony_peaks", "biomesoplenty:crag"),
    TRIBE_LANDS("The Tribe of Rushing Water", 3.5, "minecraft:windswept_hills", "minecraft:stony_peaks"),
    SUN_DROWN_SEA("The sun-drown-place", 7, "minecraft:ocean"),
    SUN_DROWN_SHORE("The sun-drown-place", 7, "minecraft:beach"),
    JOURNEY_RIDGE("The Great Journey", 6, "minecraft:meadow", "minecraft:plains");

    public final String title;
    public final double factor;
    public final List<String> biomes;

    Zone(String title, double factor, String... biomes) {
        this.title = title;
        this.factor = factor;
        this.biomes = List.of(biomes);
    }
}
