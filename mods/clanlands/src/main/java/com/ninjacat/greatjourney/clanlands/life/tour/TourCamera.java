package com.ninjacat.greatjourney.clanlands.life.tour;

import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.TourStop;
import java.util.function.DoubleBinaryOperator;

/**
 * The camera's path at one tour stop, worked out the same way on the server (which moves the player to where the
 * camera starts, so the land there loads) and on the client (which flies the camera). The path is a handful of
 * control points for the camera and for where it looks; the client draws a smooth curve through them. Only the
 * heights come from the terrain ({@code ground}: the top block at a column), so x and z agree on both sides.
 */
public final class TourCamera {
    private TourCamera() {}

    /** Control points per stop. */
    public static final int K = 7;

    /** The height the camera looks at: the given height, or the ground there plus the lift. */
    public static double targetY(TourStop s, DoubleBinaryOperator ground) {
        return Double.isNaN(s.y()) ? ground.applyAsDouble(s.x(), s.z()) + s.lift() : s.y();
    }

    /** {cameras, looks}: K points each, {x, y, z}, evenly spread over the stop's time. */
    public static double[][][] controls(TourStop s, DoubleBinaryOperator ground) {
        double[][] cam = new double[K][], look = new double[K][];
        switch (s.style()) {
            case TourStop.PUSH -> push(s, ground, cam, look);
            case TourStop.SWEEP -> sweep(s, ground, cam, look);
            case TourStop.TRACK -> track(s, cam, look);
            case TourStop.VISTA -> vista(s, ground, cam, look);
            case TourStop.DOWN -> down(s, ground, cam, look);
            default -> orbit(s, ground, cam, look);
        }
        return new double[][][] {cam, look};
    }

    /** Where the camera starts, x and z only (no terrain needed): the server moves the player here. */
    public static double[] startXZ(TourStop s) {
        double[][][] c = controls(s, (x, z) -> 64);
        return new double[] {c[0][0][0], c[0][0][2]};
    }

    private static double f(int k) {
        return (double) k / (K - 1);
    }

    /** A slow circle, a little over a quarter of the way round, high enough to see into the place. */
    private static void orbit(TourStop s, DoubleBinaryOperator ground, double[][] cam, double[][] look) {
        double ty = targetY(s, ground);
        // small places (a garden, a sett) are seen from lower down, so walls and trees do not hide them
        double d = Math.max(26, Math.min(96, s.radius() * 1.25 + 18)), h = d * (s.radius() < 20 ? 0.3 : 0.45) + 6;
        double sweep = Math.toRadians(100), eye = 2;
        // a stop can ask for its own distance, height, view height and sweep (degrees): {d, h, eye, sweep}
        double[] o = s.path();
        if (o.length >= 2) {
            d = o[0];
            h = o[1];
        }
        if (o.length >= 3) eye = o[2];
        if (o.length >= 4) sweep = Math.toRadians(o[3]);
        for (int k = 0; k < K; k++) {
            double a = s.heading() + sweep * (f(k) - 0.5);
            cam[k] = new double[] {s.x() + Math.cos(a) * d, ty + h, s.z() + Math.sin(a) * d};
            look[k] = new double[] {s.x(), ty + eye, s.z()};
        }
    }

    /** Above a hollow's rim, close in, looking steeply down into it while circling a little. */
    private static void down(TourStop s, DoubleBinaryOperator ground, double[][] cam, double[][] look) {
        double ty = targetY(s, ground);
        double d = Math.max(12, s.radius() * 1.1 + 8), h = Math.max(22, d * 1.15), sweep = Math.toRadians(70);
        // a stop can ask for its own distance, height and sweep (degrees): {d, h, sweep}
        double[] o = s.path();
        if (o.length >= 2) {
            d = o[0];
            h = o[1];
        }
        if (o.length >= 3) sweep = Math.toRadians(o[2]);
        for (int k = 0; k < K; k++) {
            double t = f(k), a = s.heading() + sweep * (t - 0.5), dd = d * (1.08 - 0.16 * t);
            cam[k] = new double[] {s.x() + Math.cos(a) * dd, ty + h * (1.05 - 0.1 * t), s.z() + Math.sin(a) * dd};
            look[k] = new double[] {s.x(), ty, s.z()};
        }
    }

    /** From far and high to near and low, drifting a little sideways, eyes on the target. */
    private static void push(TourStop s, DoubleBinaryOperator ground, double[][] cam, double[][] look) {
        double ty = targetY(s, ground);
        double d0 = s.radius() * 2.0 + 40, d1 = s.radius() * 0.9 + 16;
        double h0 = d0 * 0.36 + 8, h1 = d1 * 0.18 + 3;
        for (int k = 0; k < K; k++) {
            double t = f(k), a = s.heading() + Math.toRadians(12) * (t - 0.5);
            double d = d0 + (d1 - d0) * t, h = h0 + (h1 - h0) * t;
            cam[k] = new double[] {s.x() + Math.cos(a) * d, ty + h, s.z() + Math.sin(a) * d};
            look[k] = new double[] {s.x(), ty, s.z()};
        }
    }

    /** Beside a road, a little above the trees on the verge, looking a way down the road. */
    private static void sweep(TourStop s, DoubleBinaryOperator ground, double[][] cam, double[][] look) {
        double[] p = s.path();
        int n = p.length / 2;
        double[] acc = new double[n];
        for (int i = 1; i < n; i++) acc[i] = acc[i - 1] + Math.hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1]);
        double len = acc[n - 1], side = s.radius() + 12;
        for (int k = 0; k < K; k++) {
            double along = len * 0.75 * f(k);
            double[] at = along(p, acc, along), ahead = along(p, acc, Math.min(len, along + 4));
            double ux = ahead[0] - at[0], uz = ahead[1] - at[1], ul = Math.max(1e-6, Math.hypot(ux, uz));
            ux /= ul;
            uz /= ul;
            double cx = at[0] - uz * side, cz = at[1] + ux * side;
            cam[k] = new double[] {cx, ground.applyAsDouble(at[0], at[1]) + 14, cz};
            double[] l = along(p, acc, Math.min(len, along + 30));
            look[k] = new double[] {l[0], ground.applyAsDouble(l[0], l[1]) + 1, l[1]};
        }
    }

    private static double[] along(double[] p, double[] acc, double s) {
        int n = acc.length;
        if (s <= 0) return new double[] {p[0], p[1]};
        for (int i = 1; i < n; i++) {
            if (acc[i] >= s) {
                double t = (s - acc[i - 1]) / Math.max(1e-6, acc[i] - acc[i - 1]);
                return new double[] {p[2 * i - 2] + (p[2 * i] - p[2 * i - 2]) * t, p[2 * i - 1] + (p[2 * i + 1] - p[2 * i - 1]) * t};
            }
        }
        return new double[] {p[2 * n - 2], p[2 * n - 1]};
    }

    /** Exactly along the given points (a tunnel's bore), looking a few blocks ahead. */
    private static void track(TourStop s, double[][] cam, double[][] look) {
        double[] p = s.path();
        int n = p.length / 3;
        for (int k = 0; k < K; k++) {
            int i = (int) Math.round(f(k) * (n - 1));
            int j = Math.min(n - 1, i + Math.max(2, n / 8));
            if (j == i) j = Math.max(0, i - 1);
            cam[k] = new double[] {p[3 * i], p[3 * i + 1], p[3 * i + 2]};
            look[k] = j > i ? new double[] {p[3 * j], p[3 * j + 1], p[3 * j + 2]}
                    : new double[] {2 * p[3 * i] - p[3 * j], p[3 * i + 1], 2 * p[3 * i + 2] - p[3 * j + 2]};
        }
    }

    /**
     * At a lookout: rising and stepping forward while the eyes pan slowly across the view. {@code path} may hold the
     * point the view is about (x, y, z); otherwise the eyes rest 140 blocks out along the heading, a little below.
     */
    private static void vista(TourStop s, DoubleBinaryOperator ground, double[][] cam, double[][] look) {
        double ty = targetY(s, ground);
        double dx = Math.cos(s.heading()), dz = Math.sin(s.heading());
        double reach = 140, fy = ty - 18;
        if (s.path().length >= 3) {
            reach = Math.max(40, Math.hypot(s.path()[0] - s.x(), s.path()[2] - s.z()));
            fy = s.path()[1];
        }
        for (int k = 0; k < K; k++) {
            // from just behind the lookout rock, so it stands in the foreground with the land falling away beyond
            double t = f(k), step = -14 + 30 * t;
            cam[k] = new double[] {s.x() + dx * step, ty + 16 + 10 * t, s.z() + dz * step};
            double a = s.heading() + Math.toRadians(70) * (t - 0.5);
            look[k] = new double[] {s.x() + Math.cos(a) * reach, fy, s.z() + Math.sin(a) * reach};
        }
    }

    /** A point on the uniform Catmull-Rom curve through {@code pts} at u in [0, pts.length - 1]. */
    public static double[] catmull(double[][] pts, double u) {
        int n = pts.length;
        int i = Math.max(0, Math.min(n - 2, (int) Math.floor(u)));
        double t = Math.max(0, Math.min(1, u - i));
        double[] p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(n - 1, i + 2)];
        double t2 = t * t, t3 = t2 * t;
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = 0.5 * (2 * p1[c] + (-p0[c] + p2[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2
                    + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t3);
        }
        return out;
    }
}
