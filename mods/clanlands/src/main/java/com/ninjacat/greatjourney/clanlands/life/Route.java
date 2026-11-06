package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A walking route across the Clan lands, worked out on the world's plan before a cat sets off, so a guide or a mentor
 * takes the way a cat would: round the lake and the rivers, not into them; round a cliff or a gorge, not up it; and
 * along the verge of a Thunderpath where one runs the right way, since its graded bed is the easiest ground there is
 * (beside it, never on it: the monsters drive there). The road itself is only crossed. The plan needs no loaded land,
 * so a long route is known from the first step; {@link Lead} walks it stretch by stretch, finding its feet on the
 * real blocks as it goes. Kiki's guide went straight at the camp and met every ravine and marsh on the way (2026-10-18).
 */
public final class Route {
    private Route() {}

    /** Grid cell, in blocks: fine for a short way, coarser for a long one (each crosses about the same number of cells). */
    static final int CELL = 8, MAX_CELL = 20;
    /** Most cells looked at for one route; past this the straight way is used. */
    static final int MAX_EXPAND = 60000;
    /** Rise or fall between neighbouring cells beyond which the step is a cliff. */
    static final double CLIFF = 5.5;
    /** Cost multipliers: in deep water, on a Thunderpath's bed, and along its verge. */
    static final double WATER = 14, ON_ROAD = 3, VERGE = 0.55;
    /** The verge: from the road's edge out this far. */
    static final double VERGE_WIDTH = 7;
    /** Waypoints are kept at most this far apart, and wherever the way turns. */
    static final double SPACING = 28;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Great Journey routes");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** {@link #plan} off the server thread (the plan reads only the world's layout, which is safe from any thread). */
    public static CompletableFuture<List<double[]>> planAsync(ClanWorld w, double fx, double fz, double tx, double tz) {
        return CompletableFuture.supplyAsync(() -> plan(w, fx, fz, tx, tz), POOL);
    }

    private record Cell(double height, boolean water, double road) {}

    /**
     * Waypoints {x, z} from near (fx, fz) to (tx, tz), the target last. A short way, or one the plan can't route,
     * is the target alone (walked straight, as before).
     */
    public static List<double[]> plan(ClanWorld w, double fx, double fz, double tx, double tz) {
        double len = Math.hypot(tx - fx, tz - fz);
        if (w == null || len < 2.5 * CELL) return List.of(new double[] {tx, tz});
        int cell = (int) Math.max(CELL, Math.min(MAX_CELL, Math.round(len / 110)));
        List<Road> roads = new ArrayList<>();
        for (ClanMap m : w.maps()) roads.addAll(m.roads());
        int ox = (int) Math.floor(fx), oz = (int) Math.floor(fz);
        int gx = Math.round((float) ((tx - ox) / cell)), gz = Math.round((float) ((tz - oz) / cell));
        int margin = (int) Math.ceil(Math.max(96, len * 0.6) / cell);
        int minX = Math.min(0, gx) - margin, maxX = Math.max(0, gx) + margin, minZ = Math.min(0, gz) - margin, maxZ = Math.max(0, gz) + margin;
        Map<Long, Cell> cells = new HashMap<>();
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        long start = key(0, 0), goal = key(gx, gz);
        cost.put(start, 0.0);
        open.add(new double[] {0, 0, 0});
        int expanded = 0;
        long found = Long.MIN_VALUE, nearest = start;
        double nearestH = Double.MAX_VALUE;
        while (!open.isEmpty() && expanded < MAX_EXPAND) {
            double[] n = open.poll();
            int cx = (int) n[1], cz = (int) n[2];
            long k = key(cx, cz);
            double here = cost.getOrDefault(k, Double.MAX_VALUE);
            if (n[0] - h(cx, cz, gx, gz, cell) > here + 1e-6) continue;    // a stale entry
            if (Math.abs(cx - gx) <= 1 && Math.abs(cz - gz) <= 1) {
                found = k;
                break;
            }
            expanded++;
            double toGoal = Math.hypot(gx - cx, gz - cz);
            if (toGoal < nearestH) {
                nearestH = toGoal;
                nearest = k;
            }
            Cell a = cell(w, roads, cells, ox, oz, cx, cz, cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    Cell b = cell(w, roads, cells, ox, oz, nx, nz, cell);
                    double step = stepCost(a, b, (dx != 0 && dz != 0) ? Math.sqrt(2) * cell : cell, cell);
                    if (step == Double.MAX_VALUE) continue;
                    long nk = key(nx, nz);
                    double c = here + step;
                    if (c < cost.getOrDefault(nk, Double.MAX_VALUE)) {
                        cost.put(nk, c);
                        from.put(nk, k);
                        open.add(new double[] {c + h(nx, nz, gx, gz, cell), nx, nz});
                    }
                }
            }
        }
        // the place itself can't be walked to on the plan (a camp sunk in a hollow, walled by cliffs): the way goes as
        // near as it can, and the cat finds the last stretch on the real ground
        if (found == Long.MIN_VALUE) {
            if (nearest == start || nearestH * cell > Math.max(64, len * 0.25)) return List.of(new double[] {tx, tz});
            found = nearest;
        }
        List<double[]> pts = new ArrayList<>();
        for (long k = found; k != start; k = from.get(k)) pts.add(0, new double[] {ox + kx(k) * cell + 0.5, oz + kz(k) * cell + 0.5});
        return simplify(pts, tx, tz);
    }

    /** The cost of walking from one cell into its neighbour, or MAX_VALUE for a cliff. */
    static double stepCost(Cell a, Cell b, double dist, int cell) {
        // the rise is judged per eight blocks, so a coarse grid does not take a long slope for a cliff
        double rise = Math.abs(b.height() - a.height()) * CELL / cell;
        if (rise > CLIFF && !a.water() && !b.water()) return Double.MAX_VALUE;
        double c = dist * (1 + rise * rise * 0.12);
        if (b.water()) c *= WATER;
        else if (b.road() == 0) c *= ON_ROAD;
        else if (b.road() > 0) c *= VERGE;
        return c;
    }

    /** What a cell holds: its planned ground, whether it is open water, and where it lies to a road (-1 none, 0 on it, 1 its verge). */
    private static Cell cell(ClanWorld w, List<Road> roads, Map<Long, Cell> cells, int ox, int oz, int cx, int cz, int cell) {
        return cells.computeIfAbsent(key(cx, cz), k -> {
            double x = ox + cx * cell + 0.5, z = oz + cz * cell + 0.5;
            Sample s = w.sample(x, z);
            boolean water = s.zone() == Zone.LAKE || s.zone() == Zone.RIVER || s.zone() == Zone.SUN_DROWN_SEA
                    || (s.weight() > 0.5 && s.height() < 62.5);
            double road = -1;
            for (Road r : roads) {
                double d = r.path().distance(x, z, r.half() + VERGE_WIDTH);
                if (d <= r.half()) {
                    road = 0;
                    break;
                }
                if (d <= r.half() + VERGE_WIDTH) road = 1;
            }
            // outside the maps the plan says nothing of the ground: take it as level and dry
            double height = s.weight() > 0.05 ? s.height() : 64;
            return new Cell(height, water, road);
        });
    }

    /**
     * The straight-line estimate. At the plain-ground rate, not the verge's: a little greedy, so a long way is found
     * in a fraction of the search (at the verge's rate the search for a long way round the lake ran out), at the cost
     * of sometimes leaving a verge for open ground.
     */
    private static double h(int x, int z, int gx, int gz, int cell) {
        return Math.hypot(gx - x, gz - z) * cell;
    }

    /** Keeps a waypoint wherever the way turns or every {@link #SPACING} blocks, and ends on the target itself. */
    static List<double[]> simplify(List<double[]> pts, double tx, double tz) {
        List<double[]> out = new ArrayList<>();
        double[] last = null;
        double run = 0;
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            if (last != null) run += Math.hypot(p[0] - last[0], p[1] - last[1]);
            boolean turn = false;
            if (i > 0 && i + 1 < pts.size()) {
                double[] a = pts.get(i - 1), b = pts.get(i + 1);
                double h1 = Math.atan2(p[1] - a[1], p[0] - a[0]), h2 = Math.atan2(b[1] - p[1], b[0] - p[0]);
                double d = Math.abs(Math.atan2(Math.sin(h2 - h1), Math.cos(h2 - h1)));
                turn = d > Math.toRadians(30);
            }
            if (run >= SPACING || turn) {
                out.add(p);
                run = 0;
            }
            last = p;
        }
        if (!out.isEmpty() && Math.hypot(out.get(out.size() - 1)[0] - tx, out.get(out.size() - 1)[1] - tz) < 2 * CELL) out.remove(out.size() - 1);
        out.add(new double[] {tx, tz});
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int kx(long k) {
        return (int) (k >> 32);
    }

    private static int kz(long k) {
        return (int) k;
    }
}
