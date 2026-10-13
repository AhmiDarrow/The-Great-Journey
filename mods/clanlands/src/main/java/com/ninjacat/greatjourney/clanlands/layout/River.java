package com.ninjacat.greatjourney.clanlands.layout;

import java.util.function.DoubleBinaryOperator;

/**
 * A stream or river with a planned water surface. The surface follows the land downhill from source to mouth, flat
 * in runs and dropping in steps where the ground falls away: each step is a waterfall. Around the water the river
 * cuts a ravine with steep walls, as deep as the land around it stands above the water.
 *
 * <p>Worldgen fills water only below sea level, so the builders lay the water in ({@code Waters}), using
 * {@link #surfaceAt} and {@link #falls()}.
 */
public final class River {
    public final Path path;
    /** Half the width of the water. */
    public final double half;
    /** Depth of water in the middle of the channel. */
    public final double depth;
    /** How far the ravine's rim reaches beyond the water's edge, and how steep its walls are (blocks up per block out). */
    public final double rim, wallSlope;
    private final double[] surface;          // water surface every STEP blocks along the path
    private static final double STEP = 6;

    private River(Path path, double half, double depth, double rim, double wallSlope, double[] surface) {
        this.path = path;
        this.half = half;
        this.depth = depth;
        this.rim = rim;
        this.wallSlope = wallSlope;
        this.surface = surface;
    }

    /**
     * Plans the water surface along {@code path} from the ground it crosses ({@code ground}, without any river
     * carving): never rising downstream, {@code sink} blocks below the lowest bank met so far, held level in runs
     * and dropped in steps of at least {@code minFall} blocks, so the land's slopes gather into waterfalls.
     * Never below sea level ({@code floor}).
     */
    public static River follow(Path path, DoubleBinaryOperator ground, double half, double depth, double rim,
                               double wallSlope, double sink, double minFall, double floor) {
        int n = (int) Math.ceil(path.length() / STEP) + 1;
        double[] s = new double[n];
        double level = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double[] p = path.pointAt(i * STEP);
            double want = Math.max(floor, ground.applyAsDouble(p[0], p[1]) - sink);
            if (want < level - minFall || level == Double.MAX_VALUE) {
                level = Math.floor(want);                      // a step down: the water falls here
            }
            s[i] = Math.max(floor, level);
        }
        return new River(path, half, depth, rim, wallSlope, s);
    }

    /** A river with a fixed surface profile: {along, surface} knots, each holding until the next. */
    public static River profiled(Path path, double half, double depth, double rim, double wallSlope, double[][] knots) {
        int n = (int) Math.ceil(path.length() / STEP) + 1;
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            double a = i * STEP, level = knots[0][1];
            for (double[] k : knots) if (a >= k[0]) level = k[1];
            s[i] = level;
        }
        return new River(path, half, depth, rim, wallSlope, s);
    }

    /** The water surface height at a distance along the river. */
    public double surfaceAt(double along) {
        int i = (int) Math.round(along / STEP);
        return surface[Math.max(0, Math.min(surface.length - 1, i))];
    }

    private double endFallTo = Double.NaN;
    private volatile double[][] fallsCache;

    /**
     * This river ends in a waterfall down to {@code to} (the next stretch's surface): the gorge's great falls, where
     * one stretch of a river hands over to the next, far below.
     */
    public River fallsAtEnd(double to) {
        this.endFallTo = to;
        this.fallsCache = null;
        return this;
    }

    private boolean continues;

    /**
     * This river carries on from another stretch's falls: its start is the foot of that fall. Columns behind its
     * start then belong to the upper stretch, so the fall's lip stays a clean edge instead of being worn back.
     */
    public River continuing() {
        this.continues = true;
        return this;
    }

    /**
     * Whether a column whose nearest point is {@code along} blocks down this river is this river's to cut and fill:
     * not the columns past the brink of its end falls, nor those behind the start of a continuing stretch.
     */
    public boolean owns(double along) {
        if (continues && along <= 0) return false;
        return Double.isNaN(endFallTo) || along < path.length();
    }

    /** Where the water drops: {along, from, to} for each step of 2 blocks or more. */
    public double[][] falls() {
        double[][] cached = fallsCache;
        if (cached != null) return cached;
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (!Double.isNaN(endFallTo) && surface[surface.length - 1] - endFallTo >= 2) out.add(new double[] {path.length() - 1, surface[surface.length - 1], endFallTo});
        for (int i = 1; i < surface.length; i++) {
            if (surface[i - 1] - surface[i] >= 2) out.add(new double[] {(i - 0.5) * STEP, surface[i - 1], surface[i]});
        }
        fallsCache = out.toArray(double[][]::new);
        return fallsCache;
    }

    /**
     * Where a point {@code along} blocks down the river stands to the nearest fall within {@code margin}: -1 just above
     * it (the upper stretch, where the ground may already have dropped away), +1 just below it (the pool at its foot),
     * 0 away from every fall.
     */
    public int besideFall(double along, double margin) {
        for (double[] f : falls()) {
            if (Math.abs(along - f[0]) <= margin) return along < f[0] ? -1 : 1;
        }
        return 0;
    }

    /**
     * The ground at a column near the river: the water channel, then ravine walls rising steeply to meet the land.
     * {@code d} and {@code along} come from {@link Path#nearest}.
     */
    public double carve(double h, double d, double along) {
        if (d > half + rim || !owns(along)) return h;
        double top = surfaceAt(along);
        if (d < half) {
            double bed = top - 0.6 - depth * (1 - (d / half) * (d / half));
            return Math.min(h, bed);
        }
        double wall = top + 1 + (d - half) * wallSlope;
        return Math.min(h, wall);
    }

    /**
     * Stepping stones across this river, {@code along} blocks from its source: a landmark centred on the river whose
     * facing is the direction across it (radians, atan2 of z over x).
     */
    public Landmark steppingStones(double along, long seed) {
        double[] at = path.pointAt(along), ahead = path.pointAt(along + 2);
        double dx = ahead[0] - at[0], dz = ahead[1] - at[1];
        return new Landmark(Landmark.Kind.STEPPING_STONES, at[0], at[1], half + 6, Math.atan2(dx, -dz), seed);
    }

    /** How far beyond the water this river can change the ground (for cheap rejects). */
    public double reach() {
        return half + rim;
    }
}
