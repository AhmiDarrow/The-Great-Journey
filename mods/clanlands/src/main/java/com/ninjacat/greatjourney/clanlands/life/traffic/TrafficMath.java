package com.ninjacat.greatjourney.clanlands.life.traffic;

import com.ninjacat.greatjourney.clanlands.layout.Road;

/**
 * The geometry of traffic, kept free of the game so it can be tested: which side of the road a monster keeps to,
 * its facing, whether a point lies inside its footprint, and when it has gone far enough to vanish.
 *
 * <p>Monsters keep to the left of the centre line, as on the books' Thunderpaths. Minecraft coordinates: x east,
 * z south; a facing is a unit vector (fx, fz) and its yaw is the entity rotation that looks along it.
 */
public final class TrafficMath {
    private TrafficMath() {}

    /** The direction to the driver's left of a facing: facing south (0, 1) the left is east (1, 0). */
    public static double[] left(double fx, double fz) {
        return new double[] {fz, -fx};
    }

    /** Entity yaw (degrees) that looks along (fx, fz): 0 faces south, 90 faces west. */
    public static float yaw(double fx, double fz) {
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }

    /**
     * How far left of the centre line a monster drives. Thunderpaths have one lane each way; the highway has two
     * each way, and {@code lane} (0 inner, 1 outer) picks one.
     */
    public static double laneOffset(Road.Kind kind, double half, int lane) {
        return switch (kind) {
            case HIGHWAY -> half * (lane == 0 ? 0.28 : 0.68);
            default -> half * 0.5;
        };
    }

    /**
     * Whether a point (dx, dz from the monster's centre, dy above its wheels) is inside the footprint of a monster
     * {@code length} long and {@code width} wide facing (fx, fz), with a little margin so a cat in the way is hit.
     */
    public static boolean inFootprint(double dx, double dy, double dz, double fx, double fz, double length, double width, double height) {
        double along = dx * fx + dz * fz;
        double[] l = left(fx, fz);
        double side = dx * l[0] + dz * l[1];
        return Math.abs(along) <= length / 2 + 0.3 && Math.abs(side) <= width / 2 + 0.3 && dy > -0.8 && dy < height + 0.4;
    }

    /** Signed distance of a point to the left of the monster's line of travel (negative: to its right). */
    public static double sideOf(double dx, double dz, double fx, double fz) {
        double[] l = left(fx, fz);
        return dx * l[0] + dz * l[1];
    }

    /**
     * A monster has gone far enough to vanish once it is {@code beyond} blocks past the cat it was coming for, or
     * has run off either end of its road.
     */
    public static boolean done(double along, double passAlong, int dir, double roadLength, double beyond) {
        if (along < 0 || along > roadLength) return true;
        return (along - passAlong) * dir > beyond;
    }

    /** Length of the stretches of road that roll for monsters (and cool down after one) on their own. */
    public static final double SEGMENT = 192;

    /** Which stretch of its road a point {@code along} it lies in. */
    public static int segment(double along) {
        return (int) Math.floor(along / SEGMENT);
    }

    /**
     * How far ahead of a monster at {@code along} (driving {@code dir}) another at {@code otherAlong} is; negative
     * when the other is behind it.
     */
    public static double ahead(double along, int dir, double otherAlong) {
        return (otherAlong - along) * dir;
    }

    /**
     * Where a monster's wheels ride: on the highest of the ground under its middle, front and back wheels (a front or
     * back sample more than two blocks off the middle is a tree trunk or a ditch, and is left out), held to the road's
     * planned top where there is one and the ground disagrees with it by more than two blocks. It climbs at once,
     * so it never sinks into a rise, and settles over two ticks when the road drops away. Heights are block tops;
     * {@code planned} and {@code previous} may be NaN.
     */
    public static double rideHeight(double planned, double centre, double front, double back, double previous) {
        double ground = centre;
        if (Math.abs(front - centre) <= 2) ground = Math.max(ground, front);
        if (Math.abs(back - centre) <= 2) ground = Math.max(ground, back);
        if (!Double.isNaN(planned) && Math.abs(ground - planned) > 2) ground = planned;
        if (Double.isNaN(previous) || ground >= previous) return ground;
        return previous + (ground - previous) * 0.5;
    }
}
