package com.ninjacat.greatjourney.clanlands.layout;

/**
 * What the Clan lands want at one column: the zone (its biome), the ground height, how strongly the map overrides
 * the normal world (1 inside, fading to 0 at the edge) and the terrain smoothness (vanilla "factor"; higher is
 * calmer ground with fewer overhangs).
 */
public record Sample(Zone zone, double height, double weight, double factor) {
    public static final Sample OUTSIDE = new Sample(null, 0, 0, 0);
}
