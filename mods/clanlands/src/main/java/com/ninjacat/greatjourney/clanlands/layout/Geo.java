package com.ninjacat.greatjourney.clanlands.layout;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/** Small geometry and seeded-parameter helpers shared by the maps. North is -Z, east is +X. */
public final class Geo {
    private Geo() {}

    public static final double TAU = Math.PI * 2;

    /** Bearing in degrees clockwise from north of the vector (dx, dz), in [0, 360). */
    public static double bearing(double dx, double dz) {
        double b = Math.toDegrees(Math.atan2(dx, -dz));
        return b < 0 ? b + 360 : b;
    }

    /** The point at {@code distance} along a bearing (degrees) from (x, z). */
    public static double[] toward(double x, double z, double bearingDeg, double distance) {
        double r = Math.toRadians(bearingDeg);
        return new double[] {x + Math.sin(r) * distance, z - Math.cos(r) * distance};
    }

    /** Signed smallest difference a - b between two bearings, in (-180, 180]. */
    public static double angleDiff(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    public static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    public static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    /**
     * Seeded parameters: every named value draws from its own stream (seed ^ hash(name)), so the same seed always
     * rebuilds the same map and adding a parameter never reshuffles the others.
     */
    public static final class Rand {
        private final long seed;
        private final String prefix;

        public Rand(long seed, String prefix) {
            this.seed = seed;
            this.prefix = prefix;
        }

        public double range(String name, double lo, double hi) {
            long h = seed ^ ((prefix + "." + name).hashCode() * 0x9E3779B97F4A7C15L);
            return lo + new XoroshiroRandomSource(h).nextDouble() * (hi - lo);
        }

        public double jitter(String name, double centre, double amount) {
            return range(name, centre - amount, centre + amount);
        }

        public long seed(String name) {
            return new XoroshiroRandomSource(seed ^ ((prefix + "." + name).hashCode() * 0xD1B54A32D192ED03L)).nextLong();
        }
    }
}
