package com.ninjacat.greatjourney.clanlands.layout;

import java.util.function.DoubleBinaryOperator;

/**
 * A Twoleg road through the Clan lands. Thunderpaths are hard black roads with a pale centre line where monsters
 * roar past; the old Thunderpath in the lake's ThunderClan woods is a forgotten gravel track the forest is taking
 * back; highways are the wide double roads of the Great Journey. The maps grade the ground under a road to its
 * planned bed (level across, gently following the land along it); {@code Roads} lays its surface, bridges any
 * water it crosses and builds the culverts that run under it.
 */
public final class Road {
    public enum Kind { THUNDERPATH, OLD_THUNDERPATH, HIGHWAY }

    private static final double STEP = 8;

    private final Path path;
    private final double half;
    private final Kind kind;
    /** Planned road surface every STEP blocks along the path, or null for a track that simply follows the ground. */
    private final double[] bed;
    /** Distances along the path where a culvert passes under the road. */
    private final double[] culverts;

    /** A road with no planned bed: it lies on the ground as it is. */
    public Road(Path path, double half, Kind kind) {
        this(path, half, kind, null, new double[0]);
    }

    private Road(Path path, double half, Kind kind, double[] bed, double[] culverts) {
        this.path = path;
        this.half = half;
        this.kind = kind;
        this.bed = bed;
        this.culverts = culverts;
    }

    /**
     * A road whose bed is planned from the ground it crosses: the ground under the centre line, smoothed over about
     * 80 blocks so the road rises and falls gently, never below the shore (64) so it bridges water instead of
     * dipping into it, and lifted {@link #CULVERT_RISE} blocks over each culvert.
     */
    public static Road graded(Path path, double half, Kind kind, DoubleBinaryOperator ground, double... culverts) {
        int n = (int) Math.ceil(path.length() / STEP) + 1;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double[] p = path.pointAt(i * STEP);
            raw[i] = ground.applyAsDouble(p[0], p[1]);
        }
        double[] bed = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int c = 0;
            for (int k = Math.max(0, i - 5); k <= Math.min(n - 1, i + 5); k++) {
                sum += raw[k];
                c++;
            }
            bed[i] = Math.max(64, sum / c);
        }
        for (double a : culverts) {
            for (int i = 0; i < n; i++) {
                double d = Math.abs(i * STEP - a);
                bed[i] += CULVERT_RISE * Geo.smoothstep(48, 10, d);
            }
        }
        return new Road(path, half, kind, bed, culverts.clone());
    }

    /** How far the road is lifted over a culvert: room for a cat-sized tunnel with a roof under the road. */
    public static final double CULVERT_RISE = 5;

    public Path path() {
        return path;
    }

    public double half() {
        return half;
    }

    public Kind kind() {
        return kind;
    }

    /** Whether the road has a planned, graded bed (else it follows the ground). */
    public boolean graded() {
        return bed != null;
    }

    /** The planned surface height at a distance along the road (graded roads only). */
    public double bedAt(double along) {
        double f = Math.max(0, along / STEP);
        int i = (int) Math.floor(f);
        if (i >= bed.length - 1) return bed[bed.length - 1];
        double t = f - i;
        return bed[i] + (bed[i + 1] - bed[i]) * t;
    }

    /** The bed before any culvert rise: the floor height of a culvert at that spot. */
    public double floorAt(double along) {
        double rise = 0;
        for (double a : culverts) rise += CULVERT_RISE * Geo.smoothstep(48, 10, Math.abs(along - a));
        return bedAt(along) - rise;
    }

    public double[] culverts() {
        return culverts;
    }

    /** How far beyond the surface the road reaches (its verge), for cheap rejects. */
    public double reach() {
        return half + 2;
    }

    /**
     * The most earth a road is banked up on over the land (beyond a culvert's own rise); a deeper dip is left as it
     * lies, and {@code Roads} carries the road over it on a bridge.
     */
    public static final double BANK = 6;

    /**
     * Grades planned ground toward this road's bed, from {@link Path#nearest} distance and position along it. Over a
     * dip deeper than {@link #BANK} the land is no longer built up to the road but eased back down to its own level,
     * so the road goes over on a bridge instead of standing on a twenty-block dam (seed 4242's forest Thunderpath
     * at 8006 8999, banked from y 62 to 82 beside its river bridge). The highway keeps its levelled strip: it is
     * built on its own embankment and only bridges water.
     */
    public double grade(double h, double dist, double along) {
        if (bed == null) return h;
        double top = bedAt(along), fill = floorAt(along) - h;
        if (fill > BANK && kind != Kind.HIGHWAY) top = h + Math.max(0, 2 * BANK - fill);
        return grade(h, dist, half, top);
    }

    /** Grades the planned ground toward a smooth road bed: level across, blending back to the land beyond. */
    public static double grade(double h, double dist, double half, double bed) {
        if (dist > half + 8) return h;
        double t = Geo.smoothstep(half + 8, half + 1, dist);
        return Geo.lerp(t, h, bed);
    }
}
