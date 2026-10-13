package com.ninjacat.greatjourney.clanlands.layout;

/**
 * One landmark placed by a map: its kind, centre, how far its build reaches (for chunk lookups), a facing angle in
 * radians and its own seed, so every world builds it a little differently.
 */
public record Landmark(Kind kind, double x, double z, double radius, double facing, long seed) {
    public enum Kind {
        GREAT_OAK, FOURTREES, GREAT_ROCK, STONE_HOLLOW, ISLAND_TREE, MOONPOOL, MOTHERMOUTH, SUNNINGROCKS,
        SNAKEROCKS, OWL_TREE, GREAT_SYCAMORE, SKY_OAK, ANCIENT_OAK, ABANDONED_NEST, HORSEPLACE, BARLEY_FARM,
        HALF_BRIDGE, TREE_BRIDGE, CARRIONPLACE, TWOLEG_NEST, WINDCLAN_HOLLOW, RIVERCLAN_CAMP, SHADOWCLAN_CAMP,
        THUNDERCLAN_RAVINE, TUNNELS, KITTYPET_HOME, STEPPING_STONES,
        HIGHWAY_STRETCH, TWOLEG_FARMS, TRIBE_CAVE, MIDNIGHT_SETT, LAKE_RIDGE
    }

    public boolean reaches(int chunkMinX, int chunkMinZ) {
        double cx = Math.max(chunkMinX, Math.min(x, chunkMinX + 15));
        double cz = Math.max(chunkMinZ, Math.min(z, chunkMinZ + 15));
        return (cx - x) * (cx - x) + (cz - z) * (cz - z) <= radius * radius;
    }
}
