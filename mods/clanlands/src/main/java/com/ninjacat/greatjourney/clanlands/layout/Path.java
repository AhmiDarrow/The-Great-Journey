package com.ninjacat.greatjourney.clanlands.layout;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;

/**
 * A meandering line (river, stream, Thunderpath, trail) as a polyline. Built from a list of control points that the
 * layout chooses, then wiggled with seeded noise so each world draws its own bends.
 */
public final class Path {
    private final double[] xs, zs, along;
    private final double minX, maxX, minZ, maxZ;

    private Path(List<double[]> pts) {
        int n = pts.size();
        xs = new double[n];
        zs = new double[n];
        along = new double[n];
        double lx = Double.MAX_VALUE, hx = -Double.MAX_VALUE, lz = Double.MAX_VALUE, hz = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
            if (i > 0) along[i] = along[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            lx = Math.min(lx, xs[i]); hx = Math.max(hx, xs[i]);
            lz = Math.min(lz, zs[i]); hz = Math.max(hz, zs[i]);
        }
        minX = lx; maxX = hx; minZ = lz; maxZ = hz;
    }

    /**
     * Subdivides the control polyline every {@code step} blocks and pushes each point sideways by noise of amplitude
     * {@code wiggle}; the ends stay put so the path still meets what it should (a lake shore, a map edge).
     */
    public static Path meander(double[][] control, double step, double wiggle, long seed, String salt) {
        Noise n = new Noise(seed, salt, 260, 3);
        List<double[]> out = new ArrayList<>();
        double total = 0;
        for (int i = 1; i < control.length; i++) total += Math.hypot(control[i][0] - control[i - 1][0], control[i][1] - control[i - 1][1]);
        double done = 0;
        for (int i = 1; i < control.length; i++) {
            double ax = control[i - 1][0], az = control[i - 1][1], bx = control[i][0], bz = control[i][1];
            double len = Math.hypot(bx - ax, bz - az);
            int k = Math.max(1, (int) Math.ceil(len / step));
            double nx = -(bz - az) / len, nz = (bx - ax) / len;
            for (int j = (i == 1 ? 0 : 1); j <= k; j++) {
                double t = (double) j / k, s = done + t * len;
                double ends = Math.min(1, Math.min(s, total - s) / 120.0);     // fade the wiggle out near both ends
                double off = n.at(s) * wiggle * ends;
                out.add(new double[] {ax + (bx - ax) * t + nx * off, az + (bz - az) * t + nz * off});
            }
            done += len;
        }
        return new Path(out);
    }

    /** The stretch of this path between two distances along it, as its own path. */
    public Path slice(double from, double to) {
        List<double[]> out = new ArrayList<>();
        out.add(pointAt(from));
        for (int i = 0; i < xs.length; i++) {
            if (along[i] > from && along[i] < to) out.add(new double[] {xs[i], zs[i]});
        }
        out.add(pointAt(to));
        return new Path(out);
    }

    public static Path straight(double[][] control) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : control) out.add(p.clone());
        return new Path(out);
    }

    /** Distance from (x, z) to the path, or a large value when far outside its bounds (cheap reject). */
    public double distance(double x, double z, double reach) {
        if (x < minX - reach || x > maxX + reach || z < minZ - reach || z > maxZ + reach) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (int i = 1; i < xs.length; i++) {
            best = Math.min(best, segment(x, z, i));
        }
        return best;
    }

    /**
     * Distance to the path and position along it of the closest point, in one pass: {distance, along}. Returns
     * {MAX_VALUE, 0} when (x, z) is outside the path's bounds by more than {@code reach}.
     */
    public double[] nearest(double x, double z, double reach) {
        if (x < minX - reach || x > maxX + reach || z < minZ - reach || z > maxZ + reach) {
            return new double[] {Double.MAX_VALUE, 0};
        }
        double best = Double.MAX_VALUE, at = 0;
        for (int i = 1; i < xs.length; i++) {
            double ax = xs[i - 1], az = zs[i - 1], dx = xs[i] - ax, dz = zs[i] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
            double d = Math.hypot(x - (ax + dx * t), z - (az + dz * t));
            if (d < best) {
                best = d;
                at = along[i - 1] + t * Math.sqrt(len2);
            }
        }
        return new double[] {best, at};
    }

    /** Position along the path (blocks from its start) of the closest point. */
    public double alongAt(double x, double z) {
        double best = Double.MAX_VALUE, at = 0;
        for (int i = 1; i < xs.length; i++) {
            double ax = xs[i - 1], az = zs[i - 1], dx = xs[i] - ax, dz = zs[i] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
            double d = Math.hypot(x - (ax + dx * t), z - (az + dz * t));
            if (d < best) {
                best = d;
                at = along[i - 1] + t * Math.sqrt(len2);
            }
        }
        return at;
    }

    private double segment(double x, double z, int i) {
        double ax = xs[i - 1], az = zs[i - 1], dx = xs[i] - ax, dz = zs[i] - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
        return Math.hypot(x - (ax + dx * t), z - (az + dz * t));
    }

    public double length() {
        return along[along.length - 1];
    }

    /** The point {@code s} blocks along the path. */
    public double[] pointAt(double s) {
        s = Math.max(0, Math.min(length(), s));
        for (int i = 1; i < xs.length; i++) {
            if (along[i] >= s) {
                double t = (s - along[i - 1]) / Math.max(1e-9, along[i] - along[i - 1]);
                return new double[] {xs[i - 1] + (xs[i] - xs[i - 1]) * t, zs[i - 1] + (zs[i] - zs[i - 1]) * t};
            }
        }
        return new double[] {xs[xs.length - 1], zs[zs.length - 1]};
    }

    static double jitter(RandomSource r, double amount) {
        return (r.nextDouble() * 2 - 1) * amount;
    }
}
