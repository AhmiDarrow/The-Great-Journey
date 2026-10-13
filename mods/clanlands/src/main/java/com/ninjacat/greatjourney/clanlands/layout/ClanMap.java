package com.ninjacat.greatjourney.clanlands.layout;

import java.util.List;

/** One book-world map (the lake territories or the old forest), laid out from the world seed. */
public interface ClanMap {
    String name();

    double centreX();

    double centreZ();

    /** Outer radius of the map including its fade into the normal world. */
    double reach();

    /** Must be cheap to call outside {@link #reach()}: return {@link Sample#OUTSIDE}. */
    Sample sample(double x, double z);

    List<Landmark> landmarks();

    /** The Twoleg roads on this map (Thunderpaths, the old Thunderpath, highways), for painting and traffic. */
    default List<Road> roads() {
        return List.of();
    }

    /** Where a new player should appear on this map, as an x/z pair. */
    double[] spawn();

    default boolean covers(double x, double z) {
        double dx = x - centreX(), dz = z - centreZ();
        return dx * dx + dz * dz < reach() * reach();
    }
}
