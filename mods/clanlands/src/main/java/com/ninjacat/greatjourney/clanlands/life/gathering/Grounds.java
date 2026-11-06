package com.ninjacat.greatjourney.clanlands.life.gathering;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;

/**
 * Where the Clans hold their Gathering now: the island in the lake, or Fourtrees while the Campaign's Clans still
 * live in the old forest ({@link Homeland#forest()}).
 * <ul>
 *   <li>The island has the Great Oak in the middle of its clearing ({@code ISLAND_TREE}, whose radius is the island's
 *       plus 14) and the fallen tree that bridges the channel from its middle out to the RiverClan shore
 *       ({@code TREE_BRIDGE}, centred halfway along that line). The leaders stand at the oak's roots.</li>
 *   <li>Fourtrees is a steep round hollow ({@code FOURTREES}, whose radius is the hollow's plus 24) with four great
 *       oaks on the diagonals of its facing and the Great Rock beside its middle; the leaders stand on the rock. The
 *       rock is placed by the landmark's own random draws in {@code landmark/Places.fourtrees}, repeated here in the
 *       same order (if that builder changes its draws, change {@link #fourtrees} with it).</li>
 * </ul>
 * The Clans sit in groups at {@code base} plus quarter turns: on the island clear of the bridge, at Fourtrees in the
 * gaps between the oaks (where the Campaign's own Fourtrees Gathering puts them).
 *
 * <p>The medicine cats' places ({@link Shrine}) are the Moonpool at the lake and Mothermouth under the Highstones in
 * the old forest.
 */
record Grounds(boolean island, double x, double z, double radius, double seatX, double seatZ, double seatR,
               double ring, double base, double[] shore, double[] bridge, double[] edge) {

    /** The Gathering place where the Clans live now, or null. */
    static Grounds current(ClanWorld w) {
        return Homeland.forest() ? fourtrees(w) : island(w);
    }

    /** The island, or null on a map without one. */
    static Grounds island(ClanWorld w) {
        if (w == null) return null;
        Landmark tree = null, bridge = null;
        for (Landmark l : w.lake.landmarks()) {
            if (l.kind() == Landmark.Kind.ISLAND_TREE) tree = l;
            if (l.kind() == Landmark.Kind.TREE_BRIDGE) bridge = l;
        }
        if (tree == null) return null;
        double r = Math.max(20, tree.radius() - 14);
        double sx, sz, bx, bz;
        if (bridge == null) {
            sx = tree.x() + r + 10;
            sz = tree.z();
            bx = tree.x() + r + 5;
            bz = tree.z();
        } else {
            // the bridge's landmark sits halfway from the island's middle to its shore end
            sx = bridge.x() * 2 - tree.x();
            sz = bridge.z() * 2 - tree.z();
            bx = bridge.x();
            bz = bridge.z();
        }
        double a = Math.atan2(sz - tree.z(), sx - tree.x());
        return new Grounds(true, tree.x(), tree.z(), r, tree.x(), tree.z(), 4.5, 9, a + Math.PI / 4,
                new double[] {sx, sz}, new double[] {bx, bz}, new double[] {tree.x() + Math.cos(a) * (r - 3), tree.z() + Math.sin(a) * (r - 3)});
    }

    /** Fourtrees, or null on a map without it. */
    static Grounds fourtrees(ClanWorld w) {
        if (w == null) return null;
        Landmark ft = null;
        for (Landmark l : w.forest.landmarks()) if (l.kind() == Landmark.Kind.FOURTREES) ft = l;
        if (ft == null) return null;
        double hollow = ft.radius() - 24;
        // the Great Rock, from the builder's draws in its order: the oaks' spread, the four oaks' seeds, then the rock
        RandomSource r = RandomSource.create(ft.seed());
        r.nextDouble();
        for (int i = 0; i < 4; i++) r.nextLong();
        double rockAng = r.nextDouble() * Math.PI * 2;
        double rx = ft.x() + Math.cos(rockAng) * 3, rz = ft.z() + Math.sin(rockAng) * 3;
        return new Grounds(false, ft.x(), ft.z(), hollow, rx, rz, 2, Math.max(14, (hollow + 10) * 0.55), ft.facing(), null, null, null);
    }

    /** "the island" or "Fourtrees". */
    String place() {
        return island ? "the island" : "Fourtrees";
    }

    /** "the Great Oak" or "the Great Rock". */
    String seat() {
        return island ? "the Great Oak" : "the Great Rock";
    }

    /** Whether a column is at the Gathering: on the island (or its beach), or down in the hollow. */
    boolean on(double px, double pz) {
        return Math.hypot(px - x, pz - z) <= radius + (island ? 4 : 0);
    }

    /** Players this much beyond {@link #radius} still see and hear it: across the channel, or up on the rim. */
    double watch() {
        return island ? 45 : 28;
    }

    /** The unit direction from the middle toward a point (the player's camp), for Fourtrees' way in and out. */
    private double[] toward(double tx, double tz) {
        double dx = tx - x, dz = tz - z, len = Math.hypot(dx, dz);
        return len < 1 ? new double[] {1, 0} : new double[] {dx / len, dz / len};
    }

    /**
     * The way in, after the walk there: over the fallen tree into the clearing, or to the hollow's rim on the side
     * toward {@code tx, tz} (the player's camp) and down into the hollow. The first point is where the walk across
     * the land ends.
     */
    List<double[]> wayIn(double tx, double tz) {
        List<double[]> out = new ArrayList<>();
        if (island) {
            out.add(shore);
            out.add(bridge);
            out.add(edge);
            double a = base - Math.PI / 4;
            out.add(new double[] {x + Math.cos(a) * 11, z + Math.sin(a) * 11});
        } else {
            double[] d = toward(tx, tz);
            out.add(new double[] {x + d[0] * (radius + 8), z + d[1] * (radius + 8)});
            out.add(new double[] {x + d[0] * (radius - 6), z + d[1] * (radius - 6)});
            out.add(new double[] {x + d[0] * radius * 0.45, z + d[1] * radius * 0.45});
        }
        return out;
    }

    /** The way back out toward tx, tz: off the island over the fallen tree, or up the hollow's wall to its rim. */
    List<double[]> wayOut(double tx, double tz) {
        List<double[]> in = wayIn(tx, tz);
        List<double[]> out = new ArrayList<>();
        for (int i = in.size() - 2; i >= 0; i--) out.add(in.get(i));
        return out;
    }

    /** Where a player is taken if no cat can walk them: the foot of the tree-bridge, or the hollow's rim. */
    double[] arrival(double tx, double tz) {
        return wayIn(tx, tz).get(0);
    }

    /** A medicine cats' meeting place: where they crouch (round x, z at {@code ring}, at the given angles) and what it is called. */
    record Shrine(boolean pool, double x, double z, double ring, double[] angles, double[] approach) {
        String name() {
            return pool ? "the Moonpool" : "Mothermouth";
        }
    }

    /** Where the medicine cats meet now: the Moonpool at the lake, Mothermouth in the old forest; or null. */
    static Shrine shrine(ClanWorld w) {
        if (w == null) return null;
        if (!Homeland.forest()) {
            for (Landmark l : w.lake.landmarks()) {
                if (l.kind() != Landmark.Kind.MOONPOOL) continue;
                // on the stone ledge round the pool (5 to 6.5 across), clear of the waterfall on the facing's side
                double[] a = new double[4];
                for (int i = 0; i < 4; i++) a[i] = l.facing() + Math.PI / 4 + i * Math.PI / 2;
                return new Shrine(true, l.x(), l.z(), 7.5, a, new double[] {l.x() + Math.cos(a[0]) * 9.5, l.z() + Math.sin(a[0]) * 9.5});
            }
            return null;
        }
        for (Landmark l : w.forest.landmarks()) {
            if (l.kind() != Landmark.Kind.MOTHERMOUTH) continue;
            // the tunnel runs in from the opening along minus (cos, sin)(facing - 90 degrees) and down to the Moonstone,
            // fourteen blocks under the hill: the cats crouch in an arc outside the opening, facing it (a liberty: in the
            // books they go down to the stone itself)
            double out = Math.atan2(Math.sin(l.facing() - Math.PI / 2), Math.cos(l.facing() - Math.PI / 2));
            double[] a = {out - 1.2, out - 0.4, out + 0.4, out + 1.2};
            return new Shrine(false, l.x(), l.z(), 5, a, new double[] {l.x() + Math.cos(out) * 9, l.z() + Math.sin(out) * 9});
        }
        return null;
    }
}
