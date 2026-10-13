package com.ninjacat.greatjourney.clanlands.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import net.minecraft.util.RandomSource;

/**
 * The tunnels under the ThunderClan/WindClan border: a central cavern deep underground with a dark stream running
 * through it toward the lake, and winding tunnels climbing from it to entrances near the border stream, on the rim
 * above ThunderClan's stone hollow, and on the WindClan moor near its camp. Each world draws its own routes.
 */
public final class Tunnels {
    /** A tunnel as points every block or so: x, y (centre of the bore), z. */
    public record Bore(double[] xs, double[] ys, double[] zs, double radius, double minX, double maxX, double minZ, double maxZ) {}

    public final double cx, cy, cz, cavernRx, cavernRy;
    public final List<Bore> bores = new ArrayList<>();
    public final List<double[]> entrances = new ArrayList<>();
    /** The underground stream: from the cavern toward the lake, a channel in the floor with still dark water. */
    public final Bore stream;

    /**
     * @param cavern   x, z of the cavern
     * @param mouths   x, z of each entrance
     * @param toward   x, z the underground stream heads toward (the lake)
     * @param ground   the planned ground height, to open each entrance at the surface
     */
    Tunnels(double[] cavern, List<double[]> mouths, double[] toward, DoubleBinaryOperator ground, long seed) {
        RandomSource r = RandomSource.create(seed);
        cx = cavern[0];
        cz = cavern[1];
        double surface = ground.applyAsDouble(cx, cz);
        cy = Math.min(48, surface - 26);
        cavernRx = 13 + r.nextDouble() * 5;
        cavernRy = 6 + r.nextDouble() * 2;
        for (double[] m : mouths) {
            double top = ground.applyAsDouble(m[0], m[1]);
            entrances.add(new double[] {m[0], top, m[1]});
            bores.add(bore(m[0], top - 1, m[1], cx, cy, cz, 1.8 + r.nextDouble() * 0.5, r));
        }
        double dx = toward[0] - cx, dz = toward[1] - cz, len = Math.hypot(dx, dz);
        double run = Math.min(70, len * 0.5);
        stream = bore(cx, cy - cavernRy + 2, cz, cx + dx / len * run, cy - cavernRy + 1, cz + dz / len * run, 1.6, r);
    }

    /** A winding bore between two points: wanders sideways and up and down a little on its way. */
    private static Bore bore(double x0, double y0, double z0, double x1, double y1, double z1, double radius, RandomSource r) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        int n = Math.max(2, (int) Math.ceil(len));
        double nx = -(z1 - z0) / Math.max(1, len), nz = (x1 - x0) / Math.max(1, len);
        double phase = r.nextDouble() * Math.PI * 2, amp = 4 + r.nextDouble() * 6, waves = 1.5 + r.nextDouble() * 2;
        double[] xs = new double[n + 1], ys = new double[n + 1], zs = new double[n + 1];
        double lx = Double.MAX_VALUE, hx = -Double.MAX_VALUE, lz = Double.MAX_VALUE, hz = -Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double side = Math.sin(t * Math.PI * waves + phase) * amp * Math.sin(t * Math.PI);   // pinned at both ends
            xs[i] = x0 + (x1 - x0) * t + nx * side;
            zs[i] = z0 + (z1 - z0) * t + nz * side;
            // steeper near the mouth so it soon goes underground, then an easy slope down to the cavern; never
            // dropping more than 0.7 a block, so a cat can walk back up it
            double drop = Math.max((y1 - y0) * Math.sqrt(t), -0.7 * t * len);
            ys[i] = y0 + drop + Math.sin(t * 9 + phase) * 1.2 * Math.sin(t * Math.PI);
            lx = Math.min(lx, xs[i]); hx = Math.max(hx, xs[i]); lz = Math.min(lz, zs[i]); hz = Math.max(hz, zs[i]);
        }
        return new Bore(xs, ys, zs, radius, lx, hx, lz, hz);
    }

    /** How far from the cavern the whole network reaches, for chunk lookups. */
    public double reach() {
        double m = cavernRx + 4;
        for (double[] e : entrances) m = Math.max(m, Math.hypot(e[0] - cx, e[2] - cz) + 16);
        return m;
    }
}
