package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Den;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Style;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Noise;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Paints the Clan camps from their {@link CampPlan}: each camp's barrier with its single way in, the dens the books
 * describe (bramble nursery, the medicine cat's cleft, burrows on the moor, a cave under Highledge...) and the great
 * rock the leader speaks from. Every shape is worked out in world coordinates from the plan, and each column only
 * reads its own ground, so a camp drawn chunk by chunk joins up whichever chunk paints first.
 */
final class Camps {
    private Camps() {}

    private static BlockState leaves(Block b) {
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    private static BlockState optionalLeaves(String id, Block fallback) {
        BlockState s = Builders.optional(id, fallback.defaultBlockState());
        return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : leaves(fallback);
    }

    static final BlockState BRAMBLE = leaves(Blocks.DARK_OAK_LEAVES);
    static final BlockState GORSE = leaves(Blocks.AZALEA_LEAVES);
    static final BlockState GORSE_FLOWER = leaves(Blocks.FLOWERING_AZALEA_LEAVES);
    // Biomes O' Plenty's willow when the pack has it, else mangrove leaves (a willow-like green)
    static final BlockState WILLOW = optionalLeaves("biomesoplenty:willow_leaves", Blocks.MANGROVE_LEAVES);
    static final BlockState PINE = leaves(Blocks.SPRUCE_LEAVES);
    static final BlockState BUSH = leaves(Blocks.OAK_LEAVES);
    static final BlockState BEECH = leaves(Blocks.BIRCH_LEAVES);
    static final BlockState AIR = Blocks.AIR.defaultBlockState();

    static void build(ChunkPainter p, Landmark l) {
        CampPlan plan = CampPlan.of(l);
        if (plan == null) return;
        switch (plan.style) {
            case STONE_HOLLOW -> stoneHollow(p, plan);
            case RAVINE -> ravine(p, plan);
            case MOOR -> moor(p, plan);
            case RIVER, ISLAND -> river(p, plan);
            case PINES, MARSH_PINES -> pines(p, plan);
        }
        tidy(p, plan);
    }

    private record Cuts(ClanWorld world, java.util.List<Landmark> camps) {}

    private static volatile Cuts cuts;

    /**
     * The ways in that climb out of a hollow run on past the camp's own reach (the stone hollow's cut, the ravine
     * path): chunks out there paint their stretch of it here. And every chunk just outside a camp's reach tidies the
     * camp's edge next door, as the camp's own chunks do (see tidy): its trees and rocks spill in there too.
     */
    static boolean outskirts(ClanWorld w, ChunkPainter p) {
        Cuts c = cuts;
        if (c == null || c.world() != w) {
            java.util.List<Landmark> list = new java.util.ArrayList<>();
            for (var m : w.maps()) {
                for (Landmark l : m.landmarks()) {
                    switch (l.kind()) {
                        case STONE_HOLLOW, THUNDERCLAN_RAVINE, WINDCLAN_HOLLOW, RIVERCLAN_CAMP, SHADOWCLAN_CAMP -> list.add(l);
                        default -> { }
                    }
                }
            }
            cuts = c = new Cuts(w, java.util.List.copyOf(list));
        }
        boolean any = false;
        for (Landmark l : c.camps()) {
            if (l.reaches(p.minX, p.minZ)) continue;                                    // painted with the camp
            boolean climbs = l.kind() == Landmark.Kind.STONE_HOLLOW || l.kind() == Landmark.Kind.THUNDERCLAN_RAVINE;
            boolean edge = new Landmark(l.kind(), l.x(), l.z(), l.radius() + 16, 0, 0).reaches(p.minX, p.minZ);
            if (!edge && !(climbs && new Landmark(l.kind(), l.x(), l.z(), l.radius() + 60, 0, 0).reaches(p.minX, p.minZ))) continue;
            CampPlan plan = CampPlan.of(w, l);
            if (plan == null) continue;
            if (plan.style == Style.STONE_HOLLOW) cut(p, plan, plan.cliff - 3, 0.8, 2.2);
            else if (plan.style == Style.RAVINE) cut(p, plan, plan.wallOut + 0.5, 1.0, 2.0);
            if (edge) tidy(p, plan);
            any = true;
        }
        return any;
    }

    // shared pieces ---------------------------------------------------------------------------------------------

    interface Column {
        void at(int x, int z, double d);
    }

    /** Every column of this chunk within {@code radius} of (x0, z0), with its distance. */
    static void around(ChunkPainter p, double x0, double z0, double radius, Column each) {
        if (x0 + radius < p.minX || x0 - radius > p.minX + 16 || z0 + radius < p.minZ || z0 - radius > p.minZ + 16) return;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double d = Math.hypot(x + 0.5 - x0, z + 0.5 - z0);
                if (d <= radius) each.at(x, z, d);
            }
        }
    }

    /** A steady per-block hash in [0, 1). */
    static double hash(int x, int y, int z) {
        long h = x * 73428767L ^ y * 19349663L ^ z * 912931L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65536.0;
    }

    /**
     * A camp floor sunk well below the plan (a cave mouth or a pit breaking through the hollow) is filled back up to
     * a block under the planned ground; returns the floor's new top.
     */
    static int level(ChunkPainter p, int x, int z, int g) {
        int planned = Places.groundAt(x + 0.5, z + 0.5);
        if (g >= planned - 2) return g;
        for (int y = g + 1; y < planned; y++) p.set(x, y, z, Blocks.DIRT.defaultBlockState());
        return planned - 1;
    }

    /** One of four floor blocks by a patchy noise, dithered so the patches have ragged edges. */
    static BlockState patch(Noise n, int x, int z, Block a, Block b, Block c, Block d) {
        double v = n.at(x, z) + (hash(x, 0, z) - 0.5) / 8;
        return (v < -0.25 ? a : v < 0.15 ? b : v < 0.45 ? c : d).defaultBlockState();
    }

    /** Along/lateral of a point against a bearing from an origin: {along, lateral}. */
    static double[] axis(double x, double z, double ox, double oz, double bearing) {
        double dx = x - ox, dz = z - oz, c = Math.cos(bearing), s = Math.sin(bearing);
        return new double[] {dx * c + dz * s, -dx * s + dz * c};
    }

    /**
     * The barrier ring: a hedge {@code wallIn..wallOut} blocks out, taller in its middle, and everywhere at least two
     * blocks above anything a cat could stand on within a jump of it, inside or out; water already in a RiverClan
     * camp's ring is left as the barrier it is (elsewhere it is filled and hedged). The gate is cut through it: a
     * tunnel roofed with the hedge when {@code roofed}, else a plain gap, bridged with a log where water crosses it.
     */
    static void ring(ChunkPainter p, CampPlan plan, Hedge hedge, boolean roofed, BlockState path) {
        boolean wetWall = plan.style == Style.RIVER || plan.style == Style.ISLAND;
        double[] way = gatePath(p, plan);
        around(p, plan.cx, plan.cz, plan.wallOut + 7, (x, z, d) -> {
            boolean gate = plan.inGate(x + 0.5, z + 0.5);
            int g = p.ground(x, z);
            if (gate) {
                // the way in: a path from inside the camp out past the hedge (in the ravine, its cut takes over at the
                // hedge's outer edge)
                if (d < plan.wallIn - 3 || (plan.style == Style.RAVINE && d > plan.wallOut + 0.5)) return;
                boolean tunnel = roofed && d >= plan.wallIn && d <= plan.wallOut;
                if (!p.get(x, g, z).getFluidState().isEmpty()) {
                    if (!wetWall && d <= plan.wallOut + 1) {
                        g = drain(p, x, z, g);
                    } else {
                        if (plan.style != Style.ISLAND) bridge(p, plan, x, z, g);           // the island has its ford
                        return;
                    }
                }
                // laid at the way's own level: never more than a block up or down from one block to the next (the
                // real ground's bumps left two-block steps, and a floor sunk under the plan a four-block drop)
                int py = wayAt(way, plan, x, z, level(p, x, z, g));
                for (int y = g + 1; y < py; y++) p.set(x, y, z, Blocks.DIRT.defaultBlockState());
                for (int y = py + 1; y <= g; y++) if (p.get(x, y, z).getFluidState().isEmpty()) p.set(x, y, z, AIR);
                g = py;
                p.clearGrowth(x, g, z, tunnel ? 3 : 6);
                p.set(x, g, z, path);
                if (tunnel) {
                    // three blocks of headroom under the roof, so where the floor rises or falls a block a cat (or a
                    // player, who cannot jump in a two-high tunnel) still walks straight through
                    for (int y = 1; y <= 3; y++) p.set(x, g + y, z, AIR);
                    for (int y = 4; y <= Math.max(4, plan.wallHeight + 1); y++) p.soft(x, g + y, z, hedge.at(x, g + y, z, d));
                }
                return;
            }
            if (d < plan.wallIn || d > plan.wallOut) return;
            if (!p.get(x, g, z).getFluidState().isEmpty()) {
                // a lake RiverClan stream is the wall where it runs deep through the ring; a skin of water (over the
                // island's fill, a marsh pool, a cave's water) drains away once the world runs and left gaps in the
                // wall at seed 777001's RiverClan camps, so it is filled and hedged like dry ground
                if (plan.style == Style.RIVER && g - p.solidTop(x, z, g, g - 20) >= 3) return;
                g = drain(p, x, z, g);
            }
            double t = (d - plan.wallIn) / Math.max(0.5, plan.wallOut - plan.wallIn);
            int h = Math.max(2, (int) Math.round(plan.wallHeight * (0.7 + 0.3 * Math.sin(Math.PI * t)) + hash(x, 1, z) * 0.9));
            // and at least two blocks above the highest ground, den, rock or log within a jump of it: where the floor
            // is levelled up, a den or a rock stands near the wall, or the slope beyond rises to the hedge's foot, a
            // hedge two blocks over its own ground was one step up
            int top = Math.min(Math.max(g + h, reach(p, plan, way, x, z, g) + 2), g + 12);
            for (int y = 1; y <= top - g; y++) p.soft(x, g + y, z, hedge.at(x, g + y, z, d));
        });
    }

    /** The barrier of a hedged camp, as its painter lays it (also laid again by the chunks round it, see tidy). */
    static void hedge(ChunkPainter p, CampPlan plan) {
        switch (plan.style) {
            case RAVINE -> ring(p, plan, (x, y, z, d) -> hash(x, y, z) < 0.35 ? GORSE : BRAMBLE, true, Blocks.COARSE_DIRT.defaultBlockState());
            case MOOR -> ring(p, plan, (x, y, z, d) -> hash(x, y, z) < 0.3 ? GORSE_FLOWER : GORSE, false, Blocks.COARSE_DIRT.defaultBlockState());
            case RIVER, ISLAND -> ring(p, plan, (x, y, z, d) -> hash(x, y, z) < 0.45 ? WILLOW : BRAMBLE, false, Blocks.COARSE_DIRT.defaultBlockState());
            case PINES, MARSH_PINES -> ring(p, plan, (x, y, z, d) -> hash(x, y, z) < 0.18 ? PINE : BRAMBLE, true, Blocks.PODZOL.defaultBlockState());
            default -> { }
        }
    }

    /**
     * The level of the way in, block by block along its middle line from {@code wallIn - 3} out: the highest of each
     * block's ground (raised as level() raises the floor) less one per block away, so it never steps more than a block
     * and never digs into a bump. Worked out from the ground as it stands, which every chunk reads alike: a stretch
     * already laid only ever reads at or under this same line. NaN where nothing could be read.
     */
    static double[] gatePath(ChunkPainter p, CampPlan plan) {
        double t0 = plan.wallIn - 3, t1 = plan.style == Style.RAVINE ? plan.wallOut + 0.5 : plan.wallOut + 7;
        int n = (int) Math.ceil(t1 - t0) + 1;
        double ux = Math.cos(plan.gate), uz = Math.sin(plan.gate);
        int[] g = new int[n];
        boolean[] known = new boolean[n];
        for (int i = 0; i < n; i++) {
            int x = (int) Math.floor(plan.cx + ux * (t0 + i)), z = (int) Math.floor(plan.cz + uz * (t0 + i));
            if (!p.readable(x, z)) continue;
            int planned = Places.groundAt(x + 0.5, z + 0.5);
            int top = p.solidTop(x, z, planned + 16, planned - 24);
            if (p.wetTop(x, z, planned + 16, planned - 24) > top) continue;                 // water: bridged, not walked
            g[i] = top < planned - 2 ? planned - 1 : top;
            known[i] = true;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double best = Double.NaN;
            for (int j = 0; j < n; j++) if (known[j]) best = Double.isNaN(best) ? g[j] - Math.abs(i - j) : Math.max(best, g[j] - Math.abs(i - j));
            out[i] = best;
        }
        return out;
    }

    /** The way's level at a column of it (its own ground where the line could not be read). */
    static int wayAt(double[] way, CampPlan plan, int x, int z, int g) {
        double t = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.gate)[0] - (plan.wallIn - 3);
        int i = (int) Math.max(0, Math.min(way.length - 1, Math.round(t)));
        return Double.isNaN(way[i]) ? g : (int) way[i];
    }

    /**
     * The highest a cat could stand within a jump (three blocks) of a hedge column, off the hedge itself: the ground
     * as it is now or as the camp will level it, water to swim up out of, and the dens, rocks and logs the camp builds,
     * worked out from the plan because the chunk beside may not be painted yet (or is painted after the hedge here).
     */
    private static int reach(ChunkPainter p, CampPlan plan, double[] way, int x, int z, int g) {
        int best = g;
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                if (ox * ox + oz * oz > 10) continue;
                int sx = x + ox, sz = z + oz;
                double sd = Math.hypot(sx + 0.5 - plan.cx, sz + 0.5 - plan.cz);
                boolean gate = plan.inGate(sx + 0.5, sz + 0.5);
                if (sd >= plan.wallIn && sd <= plan.wallOut && !gate) continue;                  // the hedge itself
                int top = p.readable(sx, sz) ? p.wetTop(sx, sz, g + 14, g - 14) : g;
                if (sd < plan.wallIn) top = Math.max(top, Places.groundAt(sx + 0.5, sz + 0.5) - 1);   // see level()
                if (gate && sd >= plan.wallIn - 3) top = Math.max(top, wayAt(way, plan, sx, sz, top));   // the way in
                best = Math.max(best, Math.max(top, built(plan, sx, sz, top)));
            }
        }
        return best;
    }

    /** The top of whatever the camp builds on a column standing on {@code floor} (a den, rock, burrow roof or log). */
    private static int built(CampPlan plan, int x, int z, int floor) {
        double px = x + 0.5, pz = z + 0.5;
        int top = floor;
        for (Den den : plan.dens) {
            double dd = Math.hypot(px - den.x(), pz - den.z());
            if (den.shape() == Den.Shape.DOME && dd <= den.radius() + 0.5) {
                double t = Math.min(1, dd / den.radius());
                top = Math.max(top, floor + (int) Math.round(den.height() * Math.sqrt(Math.max(0, 1 - t * t))) + 1);
            } else if (den.shape() == Den.Shape.BURROW) {
                double mid = Math.hypot(den.x() - plan.cx, den.z() - plan.cz), mouth = mid - (den.length() - 2);
                double[] a = axis(px, pz, plan.cx, plan.cz, den.door() + Math.PI);
                boolean chamber = dd <= den.radius();
                if (a[0] >= mouth - 1.5 && a[0] <= mid + 2.6 && (chamber || Math.abs(a[1]) <= 1.05)) {
                    top = Math.max(top, Math.max(floor + 1, den.y() + (chamber ? 2 : 1) + 2));   // earth roof and gorse
                }
            }
        }
        for (double[] rock : new double[][] {plan.rock, plan.medicineRock}) {
            if (rock == null) continue;
            double dd = Math.hypot(px - rock[0], pz - rock[1]);
            if (dd <= rock[2]) {
                int base = Places.groundAt(rock[0], rock[1]);
                top = Math.max(top, base + (int) Math.round(rock[3] * Math.sqrt(Math.max(0, 1 - Math.pow(dd / rock[2], 2.2)))));
            }
        }
        // the fallen trees by the elders' den, and the apprentices' stump
        if (plan.style == Style.RAVINE || plan.style == Style.PINES || plan.style == Style.MARSH_PINES) {
            Den eld = plan.den(DenKind.ELDERS);
            double along = eld.door() + Math.PI * 0.5, ux = Math.cos(along), uz = Math.sin(along);
            boolean ravine = plan.style == Style.RAVINE;
            double sx = ravine ? eld.x() - ux * 4.5 : eld.x() + ux * (eld.radius() + 0.6);
            double sz = ravine ? eld.z() - uz * 4.5 : eld.z() + uz * (eld.radius() + 0.6);
            double[] a = axis(px, pz, sx, sz, along);
            if (a[0] >= -0.8 && a[0] <= 9.8 && Math.abs(a[1]) <= 0.8) {
                top = Math.max(top, Math.max(floor + 1, Places.groundAt(sx, sz) + (ravine ? eld.height() + 1 : 0)));
            }
            if (!ravine) {
                double[] b = axis(px, pz, eld.x(), eld.z(), along);
                if (Math.abs(b[0] - (eld.radius() - 0.2)) <= 1.2 && Math.abs(b[1]) <= 2.8) top = Math.max(top, floor + 4);   // its roots
            }
        }
        if (plan.style == Style.RAVINE) {
            Den app = plan.den(DenKind.APPRENTICES);
            double sa = app.door() + 0.9;
            if (Math.hypot(px - (app.x() + Math.cos(sa) * (app.radius() + 1.0)), pz - (app.z() + Math.sin(sa) * (app.radius() + 1.0))) <= 1.2) {
                top = Math.max(top, floor + 2);
            }
        }
        return top;
    }

    /** Whether mossy cobblestone at a column can only be a wild boulder: on a hedged camp's floor or wall, off its rocks. */
    private static boolean rolledRock(CampPlan plan, int x, int z, double d) {
        if (plan.wallIn <= 0 || plan.style == Style.RIVER || plan.style == Style.ISLAND || d > plan.wallOut + 0.5) return false;
        for (double[] rock : new double[][] {plan.rock, plan.medicineRock}) {
            if (rock != null && Math.hypot(x + 0.5 - rock[0], z + 0.5 - rock[1]) <= rock[2] + 1.5) return false;
        }
        return true;
    }

    /** Water in a camp's floor or wall (a flooded cave or a pond the terrain left there) filled with earth; the top. */
    static int drain(ChunkPainter p, int x, int z, int g) {
        int bed = p.solidTop(x, z, g, g - 40);
        for (int y = bed + 1; y <= g; y++) p.set(x, y, z, Blocks.DIRT.defaultBlockState());
        return g;
    }

    /**
     * Water across the way in, outside the barrier: a log laid over it at the level of the path where it leaves the
     * wall (or on the water itself where the water stands higher), so the way in never ends at a stream's edge.
     */
    private static void bridge(ChunkPainter p, CampPlan plan, int x, int z, int g) {
        double ux = Math.cos(plan.gate), uz = Math.sin(plan.gate);
        int mx = (int) Math.floor(plan.cx + ux * (plan.wallOut - 0.5)), mz = (int) Math.floor(plan.cz + uz * (plan.wallOut - 0.5));
        int bank = p.readable(mx, mz) ? p.wetTop(mx, mz, g + 12, g - 12) : Places.groundAt(mx + 0.5, mz + 0.5);
        int deck = Math.max(g, bank);
        p.set(x, deck, z, Builders.log(Builders.OAK_LOG, Math.abs(ux) > Math.abs(uz) ? Direction.Axis.X : Direction.Axis.Z));
        for (int y = deck + 1; y <= deck + 3; y++) {
            var s = p.get(x, y, z);
            if (!s.isAir() && (ChunkPainter.growth(s) || !s.getFluidState().isEmpty())) p.set(x, y, z, AIR);
        }
    }

    /**
     * Trees grown by a chunk next door after this camp was painted reach into it: their leaves would fill the way
     * in or lie across the barrier as a bridge. Each chunk's pass clears the wild (non-persistent) leaves from the
     * camp's columns in the chunks round it; the camp's own hedges and dens are persistent and stay.
     */
    static void tidy(ChunkPainter p, CampPlan plan) {
        double reach = plan.style == Style.STONE_HOLLOW ? plan.cliff + 9 : plan.wallOut + 7;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                ChunkPainter q = new ChunkPainter(p.level, p.minX + ox * 16, p.minZ + oz * 16);
                around(q, plan.cx, plan.cz, reach, (x, z, d) -> {
                    int g = q.ground(x, z);
                    // a forest's mossy boulder rolled onto the floor or against the hedge is a step up onto it (seed
                    // 777001's forest ShadowClan camp); the camp's own rocks and the ford are left alone
                    if (rolledRock(plan, x, z, d) && q.get(x, g, z).is(Blocks.MOSSY_COBBLESTONE)) {
                        int y = g;
                        while (y > g - 4 && q.get(x, y, z).is(Blocks.MOSSY_COBBLESTONE)) q.set(x, y--, z, AIR);
                        g = y;
                    }
                    for (int y = g + 1; y <= g + 24; y++) {
                        BlockState s = q.get(x, y, z);
                        if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
                            q.set(x, y, z, AIR);
                        }
                    }
                });
                // and the barrier laid again where it stands: whatever the decoration next door took out of it (the
                // hedge at seed 777001's forest RiverClan island was missing over a few blocks, the same in every run)
                // comes back, and a stretch already whole is left as it is
                if (plan.wallIn > 0) hedge(q, plan);
            }
        }
    }

    interface Hedge {
        BlockState at(int x, int y, int z, double d);
    }

    /**
     * A den of leaves: a low dome with a cat-sized hollow inside and an opening toward the camp's middle, moss
     * carpeting the floor round the nests.
     */
    static void dome(ChunkPainter p, CampPlan plan, Den den, BlockState shell, BlockState accent, double accentShare) {
        double[][] nests = nests(plan, den);
        around(p, den.x(), den.z(), den.radius() + 0.5, (x, z, d) -> {
            int g = p.ground(x, z);
            if (!p.get(x, g, z).getFluidState().isEmpty()) return;
            double t = Math.min(1, d / den.radius());
            int roof = (int) Math.round(den.height() * Math.sqrt(Math.max(0, 1 - t * t))) + 1;
            double ang = Math.atan2(z + 0.5 - den.z(), x + 0.5 - den.x());
            boolean door = Math.abs(CampPlan.diff(ang, den.door())) < 0.55 && d > den.radius() - 2.2;
            boolean inside = d < den.radius() - 1.0;
            for (int y = 1; y <= roof; y++) {
                if ((inside && y < roof) || (door && y <= 2)) {
                    var s = p.get(x, g + y, z);
                    if (!s.isAir() && ChunkPainter.growth(s)) p.set(x, g + y, z, AIR);
                } else {
                    p.soft(x, g + y, z, hash(x, g + y, z) < accentShare ? accent : shell);
                }
            }
            if (inside && !nestAt(nests, x, z) && hash(x, 7, z) < 0.55) p.soft(x, g + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
        });
    }

    /** The nest spots of a den, every one of them (left bare for the Clan's moss nests). */
    static double[][] nests(CampPlan plan, Den den) {
        return plan.nests(den.kind()).toArray(double[][]::new);
    }

    static boolean nestAt(double[][] nests, int x, int z) {
        for (double[] n : nests) if ((int) Math.floor(n[0]) == x && (int) Math.floor(n[1]) == z) return true;
        return false;
    }

    /**
     * A burrow dug level into rising ground: a two-high tunnel from its mouth to a wider sleeping chamber, roofed
     * with earth where the ground above runs thin, its mouth hidden under a gorse bush.
     */
    static void burrow(ChunkPainter p, CampPlan plan, Den den, BlockState cover) {
        double out = den.door() + Math.PI;
        double mid = Math.hypot(den.x() - plan.cx, den.z() - plan.cz);
        double mouth = mid - (den.length() - 2);
        int y0 = den.y();
        double[][] nests = nests(plan, den);
        around(p, plan.cx, plan.cz, mid + 3.5, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, out);
            if (a[0] < mouth - 1.5 || a[0] > mid + 2.6) return;
            boolean chamber = Math.hypot(x + 0.5 - den.x(), z + 0.5 - den.z()) <= den.radius();
            if (!chamber && Math.abs(a[1]) > 1.05) return;
            int g = p.ground(x, z);
            int top = chamber ? 2 : 1;
            for (int y = g + 1; y < y0 - 1; y++) p.set(x, y, z, Blocks.DIRT.defaultBlockState());   // a ledge to the mouth
            p.set(x, y0 - 1, z, chamber ? Blocks.ROOTED_DIRT.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
            for (int y = y0; y <= y0 + top; y++) p.set(x, y, z, AIR);
            if (g <= y0 + top) {
                // the slope runs thin above: an earth roof, with gorse growing on it over the mouth
                if (a[0] > mouth + 0.5) p.set(x, y0 + top + 1, z, Blocks.DIRT.defaultBlockState());
                if (a[0] > mouth + 0.5 && a[0] < mouth + 3.5) p.soft(x, y0 + top + 2, z, cover);
            } else if (a[0] < mouth + 3.5 && a[0] > mouth + 0.5) {
                p.soft(x, g + 1, z, cover);
            }
            if (chamber && !nestAt(nests, x, z) && hash(x, 3, z) < 0.5) p.set(x, y0, z, Blocks.MOSS_CARPET.defaultBlockState());
        });
    }

    /**
     * A cleft cut into rock: a narrow slot running {@code den.length()} blocks back from its opening (which faces
     * {@code den.door()}) to a sleeping chamber of {@code den.radius()}, floored at the den's level.
     */
    static void cleft(ChunkPainter p, Den den, int extraHeight, boolean pool) {
        int y0 = den.y();
        around(p, den.x(), den.z(), den.length() + 2, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, den.x(), den.z(), den.door());
            boolean chamber = d <= den.radius();
            boolean slot = a[0] >= 0 && a[0] <= den.length() + 1 && Math.abs(a[1]) <= 0.75;
            if (!chamber && !slot) return;
            int h = chamber ? 2 + extraHeight : 1 + extraHeight;
            for (int y = y0 - 4; y <= y0 - 2; y++) if (p.get(x, y, z).isAir()) p.set(x, y, z, Blocks.STONE.defaultBlockState());   // over any cave
            p.set(x, y0 - 1, z, chamber ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
            for (int y = y0; y <= y0 + h; y++) p.set(x, y, z, AIR);
            if (pool && isPool(den, x, z)) {
                p.set(x, y0 - 1, z, Blocks.WATER.defaultBlockState());                    // the medicine cat's little pool
                p.set(x, y0 - 2, z, Blocks.STONE.defaultBlockState());
            }
        });
        if (!pool) return;
        // the pool's rim: solid all round it, whatever the cleft or a cave beside it left open
        around(p, den.x(), den.z(), den.radius() + 2, (x, z, d) -> {
            if (isPool(den, x, z)) return;
            boolean beside = isPool(den, x + 1, z) || isPool(den, x - 1, z) || isPool(den, x, z + 1) || isPool(den, x, z - 1);
            if (beside && !p.get(x, y0 - 1, z).isSolid()) p.set(x, y0 - 1, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        });
    }

    private static boolean isPool(Den den, int x, int z) {
        double[] a = axis(x + 0.5, z + 0.5, den.x(), den.z(), den.door());
        return Math.hypot(x + 0.5 - den.x(), z + 0.5 - den.z()) <= den.radius() && a[0] < -den.radius() + 1.6 && Math.abs(a[1]) < 1.2;
    }

    /** A rounded boulder of mixed stone, {@code h} high on a planned base, mossy on top. */
    static void boulder(ChunkPainter p, double x0, double z0, double r, double h, int base) {
        around(p, x0, z0, r, (x, z, d) -> {
            int g = Math.min(p.ground(x, z), base);
            int top = base + (int) Math.round(h * Math.sqrt(Math.max(0, 1 - Math.pow(d / r, 2.2))));
            for (int y = g - 2; y <= top; y++) {
                double k = hash(x, y, z);
                Block b = y == top && d < r * 0.65 ? Blocks.MOSSY_COBBLESTONE : k < 0.22 ? Blocks.ANDESITE : k < 0.3 ? Blocks.COBBLESTONE : Blocks.STONE;
                p.set(x, y, z, b.defaultBlockState());
            }
        });
    }

    /** A hollow under a rock or at its foot: the den's space cut out, open toward the camp, mossy inside. */
    static void cave(ChunkPainter p, CampPlan plan, Den den) {
        double[][] nests = nests(plan, den);
        int y0 = den.y();
        around(p, den.x(), den.z(), den.radius() + 2.5, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, den.x(), den.z(), den.door());
            boolean room = d <= den.radius() || (a[0] > 0 && Math.abs(a[1]) <= 1.0);
            if (!room) return;
            for (int y = y0; y <= y0 + 1; y++) p.set(x, y, z, AIR);
            if (d <= den.radius() && !nestAt(nests, x, z)) p.set(x, y0, z, Blocks.MOSS_CARPET.defaultBlockState());
        });
    }

    /** A tall spruce with a high, narrow crown: ShadowClan's pines leaning over the camp. */
    static void pine(ChunkPainter p, double x0, double z0, int height) {
        int bx = (int) Math.floor(x0), bz = (int) Math.floor(z0);
        // on the real ground where it stands higher than the plan: a crown hung from the planned ground came down to
        // two blocks over the real one beside the hedge, a stair over it (seed 777001's forest ShadowClan camp). The
        // pines stand in the hedge band, whose ground the camp never changes, so every chunk reads the same height.
        int planned = Places.groundAt(bx, bz);
        int base = p.readable(bx, bz) ? Math.max(planned, p.solidTop(bx, bz, planned + 24, planned - 24)) : planned;
        around(p, bx + 0.5, bz + 0.5, 4.2, (x, z, d) -> {
            if (x == bx && z == bz) {
                int g = p.ground(x, z);
                for (int y = Math.min(g, base) + 1; y <= base + height; y++) p.set(x, y, z, Blocks.SPRUCE_LOG.defaultBlockState());
            }
            for (int y = base + height - 9; y <= base + height + 1; y++) {
                double rr = 0.6 + (base + height + 1 - y) * 0.36 * ((y & 1) == 0 ? 1 : 0.75);
                if (d <= rr && !(x == bx && z == bz && y <= base + height)) p.soft(x, y, z, PINE);
            }
        });
    }

    /** A willow by the water: a short trunk and a round crown with curtains of leaves hanging to the ground. */
    static void willow(ChunkPainter p, double x0, double z0, int height) {
        int bx = (int) Math.floor(x0), bz = (int) Math.floor(z0);
        int base = Places.groundAt(bx, bz);
        around(p, bx + 0.5, bz + 0.5, 4.6, (x, z, d) -> {
            if (x == bx && z == bz) {
                int g = p.ground(x, z);
                for (int y = Math.min(g, base) + 1; y <= base + height; y++) p.set(x, y, z, Blocks.OAK_LOG.defaultBlockState());
            }
            int top = base + height + 1;
            for (int y = top - 2; y <= top + 1; y++) {
                double rr = 4.2 - Math.abs(y - top + 0.5) * 0.8;
                if (d <= rr && !(x == bx && z == bz && y <= base + height)) p.soft(x, y, z, WILLOW);
            }
            if (d > 2.6 && d <= 3.7 && hash(x, 5, z) < 0.6) {
                int hang = 2 + (int) (hash(x, 6, z) * 3);
                for (int y = top - 3; y >= top - 2 - hang; y--) p.soft(x, y, z, WILLOW);
            }
        });
    }

    /** A trunk lying on the ground from (x0, z0) along a bearing, {@code length} long. */
    static void log(ChunkPainter p, double x0, double z0, double bearing, double length, BlockState log, int lift) {
        double ux = Math.cos(bearing), uz = Math.sin(bearing);
        Direction.Axis ax = Math.abs(ux) > Math.abs(uz) ? Direction.Axis.X : Direction.Axis.Z;
        int base = Places.groundAt(x0, z0) + lift;
        for (double t = 0; t <= length; t += 0.5) {
            int x = (int) Math.floor(x0 + ux * t), z = (int) Math.floor(z0 + uz * t);
            if (!p.inside(x, z)) continue;
            int g = p.ground(x, z);
            p.set(x, Math.max(g + 1, base), z, Builders.log(log, ax));
        }
    }

    static BlockState plant(ChunkPainter p, int x, int g, int z, BlockState plant) {
        BlockState under = p.get(x, g, z);
        if (under.is(Blocks.SAND) || under.is(Blocks.GRAVEL) || under.is(Blocks.STONE)) p.set(x, g, z, Blocks.GRASS_BLOCK.defaultBlockState());
        p.soft(x, g + 1, z, plant);
        return plant;
    }

    // the camps ------------------------------------------------------------------------------------------------

    /**
     * Lake ThunderClan, the stone hollow: sheer quarry walls all round. The one way in is a cut that climbs down
     * through the wall to a thorn tunnel at the hollow's foot. Highledge juts from the wall with the leader's den in
     * the rock behind it, reached by tumbled rocks; the elders sleep in honeysuckle beneath it; the medicine den is a
     * cleft in the rock with a pool, behind a bramble screen; the nursery is a bramble thicket against the wall.
     */
    static void stoneHollow(ChunkPainter p, CampPlan plan) {
        double cliff = plan.cliff;
        Noise floor = new Noise(plan.seed, "hollow.floor", 18, 2);
        int ledgeTop = plan.ledgeTop();
        Den elders = plan.den(DenKind.ELDERS);
        double stairSide = -Math.signum(CampPlan.diff(Math.atan2(elders.z() - plan.cz, elders.x() - plan.cx), plan.ledge));
        // the floor cleared and sanded; the rim dressed in stone, mossy further back
        int wallTop = plan.floorY + 15;
        around(p, plan.cx, plan.cz, cliff + 9, (x, z, d) -> {
            int g = p.ground(x, z);
            double[] ga = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.gate);
            boolean gateWay = ga[0] > 0 && Math.abs(ga[1]) < 3.4;
            // the quarry's walls are sheer all round: where the real land slopes gently down into the hollow (the
            // plan's cliff came out as a ramp in places) the foot is cut back to the floor and the rim built up. And
            // the floor is dug down to the plan everywhere (the real ground came out two or three blocks over it,
            // seed 777001's floor at 69 on a plan of 66): the full depth of the walls, and the floor level with the
            // medicine cleft, the dirtplace tunnel and Highledge, which are all cut from the planned floor. The plan
            // keeps that floor above the water table (LakeLands.hollow), and a flooded column is filled below.
            if ((d < cliff - 3 || !gateWay) && d < cliff - 1 && g > plan.floorY + 1) {
                for (int y = plan.floorY + 2; y <= g; y++) p.set(x, y, z, AIR);
                p.set(x, plan.floorY + 1, z, Blocks.COARSE_DIRT.defaultBlockState());
                g = plan.floorY + 1;
            } else if (!gateWay && d >= cliff - 1 && d <= cliff + 2.5 && g < wallTop) {
                for (int y = g + 1; y <= wallTop; y++) p.set(x, y, z, Builders.STONE);
                g = wallTop;
            }
            if (d < cliff - 1) {
                p.clearGrowth(x, g, z, 34);
                if (!p.get(x, g, z).getFluidState().isEmpty()) g = drain(p, x, z, g);       // a flooded floor
                int top = level(p, x, z, g);
                p.set(x, top, z, patch(floor, x, z, Blocks.SAND, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.SAND));
            } else if (d > cliff + 1) {
                double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.gate);
                if (a[0] > 0 && Math.abs(a[1]) < 3.4) return;                             // the cut
                for (int y = g - 5; y <= g; y++) p.set(x, y, z, y == g && d > cliff + 6 ? Blocks.MOSS_BLOCK.defaultBlockState() : Builders.STONE);
            }
        });
        // Highledge: a shelf of rock against the wall; tumbled rocks step down from one end
        around(p, plan.cx, plan.cz, cliff + 2, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.ledge);
            if (a[0] < cliff - 5) return;
            double lat = a[1] * stairSide;
            int top;
            if (Math.abs(a[1]) <= 4.5) top = ledgeTop;
            else if (lat > 4.5 && lat <= 4.5 + 6 * 1.4 && a[0] > cliff - 4) top = ledgeTop - (int) Math.ceil((lat - 4.5) / 1.4);
            else return;
            int g = p.ground(x, z);
            for (int y = g + 1; y <= top; y++) {
                double k = hash(x, y, z);
                BlockState b = top == ledgeTop ? (y == top ? Blocks.SMOOTH_STONE.defaultBlockState() : Builders.STONE)
                        : (k < 0.35 ? Blocks.MOSSY_COBBLESTONE : k < 0.6 ? Blocks.COBBLESTONE : Blocks.ANDESITE).defaultBlockState();
                p.set(x, y, z, b);
            }
        });
        // the leader's den: a cave in the rock behind Highledge
        Den leader = plan.den(DenKind.LEADER);
        double[][] leaderNests = nests(plan, leader);
        around(p, plan.cx, plan.cz, cliff + 7, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.ledge);
            if (a[0] < cliff - 1.5 || a[0] > cliff + 5.2 || Math.abs(a[1]) > (a[0] < cliff + 1 ? 1.2 : 2.4)) return;
            p.set(x, ledgeTop, z, Builders.STONE);
            for (int y = ledgeTop + 1; y <= ledgeTop + 3; y++) p.set(x, y, z, AIR);
            if (a[0] > cliff + 1.5 && !nestAt(leaderNests, x, z)) p.set(x, ledgeTop + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
        });
        // the medicine den: a cleft in the wall with a pool at the back, screened by bramble
        Den med = plan.den(DenKind.MEDICINE);
        cleft(p, med, 1, true);
        around(p, med.x(), med.z(), med.length() + 3, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, med.x(), med.z(), med.door());
            if (a[0] < med.length() - 2.5 || a[0] > med.length() + 0.5 || Math.abs(a[1]) <= 0.75 || Math.abs(a[1]) > 2.6) return;
            int g = p.ground(x, z);
            for (int y = 1; y <= 3; y++) p.soft(x, g + y, z, BRAMBLE);
        });
        // the way in: a cut climbing the wall, and the thorn tunnel at its foot
        cut(p, plan, cliff - 3, 0.8, 2.2);
        double start = cliff - 3;
        around(p, plan.cx, plan.cz, start + 1, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.gate);
            if (a[0] >= start || a[0] < start - 5 || Math.abs(a[1]) > 3.2) return;
            int g = p.ground(x, z);
            if (Math.abs(a[1]) <= 1.5) {
                p.set(x, g, z, Blocks.COARSE_DIRT.defaultBlockState());
                // three high, like the hedge camps' tunnels: a player cannot jump a step in a two-high tunnel
                for (int y = 1; y <= 3; y++) p.set(x, g + y, z, AIR);
                for (int y = 4; y <= 5; y++) p.soft(x, g + y, z, BRAMBLE);
            } else {
                for (int y = 1; y <= 5; y++) p.soft(x, g + y, z, hash(x, y, z) < 0.25 ? BUSH : BRAMBLE);
            }
        });
        dirtplace(p, plan);
        // the dens round the walls
        dome(p, plan, plan.den(DenKind.NURSERY), BRAMBLE, BUSH, 0.12);
        dome(p, plan, plan.den(DenKind.WARRIORS), BUSH, BEECH, 0.4);
        Den app = plan.den(DenKind.APPRENTICES);
        dome(p, plan, app, BUSH, BRAMBLE, 0.2);
        ferns(p, app);
        dome(p, plan, elders, GORSE_FLOWER, BUSH, 0.3);
    }

    /**
     * The stone hollow's dirtplace: a cat-sized tunnel (one wide, two high) from the foot of the quarry wall into the
     * rock, ending in a small earth-floored hollow roofed with rock. It leads nowhere else: the camp keeps its one way
     * in, and nothing out here stands under the sky to climb out by.
     */
    static void dirtplace(ChunkPainter p, CampPlan plan) {
        if (Double.isNaN(plan.dirtplace)) return;
        double cliff = plan.cliff, end = cliff + 3, room = 2.6;
        double rx = plan.cx + Math.cos(plan.dirtplace) * (cliff + 5.5), rz = plan.cz + Math.sin(plan.dirtplace) * (cliff + 5.5);
        int floor = plan.floorY + 1;
        around(p, plan.cx, plan.cz, cliff + 10, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.dirtplace);
            double dr = Math.hypot(x + 0.5 - rx, z + 0.5 - rz);
            boolean tunnel = a[0] >= cliff - 2 && a[0] <= end && Math.abs(a[1]) <= 0.75;
            boolean chamber = dr <= room;
            boolean shell = !tunnel && !chamber && ((a[0] >= cliff - 1 && a[0] <= end && Math.abs(a[1]) <= 1.9) || dr <= room + 1.1);
            if (!tunnel && !chamber && !shell) return;
            int top = chamber ? floor + 3 : floor + 2;
            if (shell) {
                // solid rock round it, whatever cave or low ground lies there
                for (int y = floor; y <= floor + 6; y++) if (!p.get(x, y, z).isSolid()) p.set(x, y, z, Builders.STONE);
                return;
            }
            for (int y = floor - 2; y < floor; y++) if (!p.get(x, y, z).isSolid()) p.set(x, y, z, Builders.STONE);
            double k = hash(x, 11, z);
            p.set(x, floor, z, (chamber ? (k < 0.4 ? Blocks.PODZOL : k < 0.8 ? Blocks.COARSE_DIRT : Blocks.GRAVEL) : Blocks.COARSE_DIRT).defaultBlockState());
            for (int y = floor + 1; y <= top; y++) p.set(x, y, z, AIR);
            // a rock roof, so the dirtplace is a dead end under cover
            if (a[0] > cliff - 1 || chamber) for (int y = top + 1; y <= top + 2; y++) if (!p.get(x, y, z).isSolid()) p.set(x, y, z, Builders.STONE);
        });
    }

    /** Ferns round the outside of a den. */
    static void ferns(ChunkPainter p, Den den) {
        around(p, den.x(), den.z(), den.radius() + 1.6, (x, z, d) -> {
            if (d < den.radius() + 0.2 || hash(x, 9, z) > 0.55) return;
            double ang = Math.atan2(z + 0.5 - den.z(), x + 0.5 - den.x());
            if (Math.abs(CampPlan.diff(ang, den.door())) < 0.8) return;                    // keep the doorway clear
            int g = p.ground(x, z);
            if (p.get(x, g, z).getFluidState().isEmpty()) plant(p, x, g, z, Blocks.FERN.defaultBlockState());
        });
    }

    /**
     * A cut climbing out of a hollow along the gate's bearing: from {@code start} blocks out at the floor, rising
     * {@code slope} a block, {@code half} blocks either side of its line, cleared of rock and trees above.
     */
    static void cut(ChunkPainter p, CampPlan plan, double start, double slope, double half) {
        int length = 48;
        double[] prof = plan.cutProfile(start, slope, length);
        around(p, plan.cx, plan.cz, start + length + 1, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.gate);
            double t = a[0] - start;
            if (t < 0 || t >= length || Math.abs(a[1]) > half) return;
            int i = (int) Math.floor(t);
            double planned = prof[i] + (prof[i + 1] - prof[i]) * (t - i);
            int g = p.ground(x, z);
            boolean water = !p.get(x, g, z).getFluidState().isEmpty();
            // over the last stretch the path eases onto the real ground, so it meets the land without a step
            double ease = Math.max(0, Math.min(1, (t - (length - 12)) / 10.0));
            int ry = (int) Math.round(planned + ease * (g - planned));
            if (water) {
                // water across the path (a flooded cave or a pond): a log over it at the path's level, or on the water
                int deck = Math.max(ry, g);
                p.set(x, deck, z, Builders.log(Builders.OAK_LOG, Math.abs(Math.cos(plan.gate)) > Math.abs(Math.sin(plan.gate))
                        ? Direction.Axis.X : Direction.Axis.Z));
                for (int y = deck + 1; y <= deck + 3; y++) if (!p.get(x, y, z).isAir()) p.set(x, y, z, AIR);
                return;
            }
            p.clearGrowth(x, Math.min(g, ry), z, 16);
            for (int y = ry + 1; y <= Math.max(g, ry + 3); y++) {
                var s = p.get(x, y, z);
                if (!s.isAir()) p.set(x, y, z, AIR);                                     // headroom
            }
            for (int y = g + 1; y < ry; y++) p.set(x, y, z, Blocks.DIRT.defaultBlockState());   // built up over a dip
            p.set(x, ry, z, hash(x, ry, z) < 0.4 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
        });
    }

    /** Stones jutting from a rock's side, two blocks apart in height, winding up it: a way for cats to climb. */
    static void steps(ChunkPainter p, double[] rock, int base, double start) {
        int n = (int) (rock[3] / 2);
        for (int k = 1; k <= n; k++) {
            double ang = start + k * 0.85;
            int x = (int) Math.floor(rock[0] + Math.cos(ang) * (rock[2] + 0.4)), z = (int) Math.floor(rock[1] + Math.sin(ang) * (rock[2] + 0.4));
            if (!p.inside(x, z)) continue;
            int y = base + 2 * k - 1;
            if (p.get(x, y, z).isAir() || ChunkPainter.growth(p.get(x, y, z))) p.set(x, y, z, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    /**
     * Forest ThunderClan, the ravine camp: at the foot of a steep rocky ravine, ringed by a thick wall of bramble and
     * gorse; the gorse tunnel at the bottom of the ravine path is the only way in. Highrock stands in the clearing
     * with the leader's lichen-hung den at its foot; Spottedleaf's fern tunnel leads to a cleft in a great rock;
     * the nursery is a bramble thicket, the warriors sleep under a bush, the apprentices by a tree stump and the
     * elders under a fallen tree.
     */
    static void ravine(ChunkPainter p, CampPlan plan) {
        double cliff = plan.cliff;
        Noise floor = new Noise(plan.seed, "ravine.floor", 14, 2);
        around(p, plan.cx, plan.cz, cliff + 5, (x, z, d) -> {
            int g = p.ground(x, z);
            if (d < plan.wallIn) {
                p.clearGrowth(x, g, z, 30);
                if (!p.get(x, g, z).getFluidState().isEmpty()) g = drain(p, x, z, g);       // a flooded floor
                int top = level(p, x, z, g);
                p.set(x, top, z, patch(floor, x, z, Blocks.SAND, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL));
            } else if (d > plan.wallOut + 0.5) {
                double[] a = axis(x + 0.5, z + 0.5, plan.cx, plan.cz, plan.gate);
                if (a[0] > 0 && Math.abs(a[1]) < 2.6) return;                             // the ravine path
                if (!p.get(x, g, z).getFluidState().isEmpty()) return;                    // no rock skin on a pond
                // the ravine's steep sides: bare rock with a little earth
                if (d < cliff + 2.5) p.clearGrowth(x, g, z, 30);
                for (int y = g - 2; y <= g; y++) {
                    double k = hash(x, y, z);
                    Block b = k < 0.45 ? Blocks.STONE : k < 0.65 ? Blocks.ANDESITE : k < 0.8 ? Blocks.COBBLESTONE
                            : k < 0.9 ? Blocks.MOSSY_COBBLESTONE : Blocks.COARSE_DIRT;
                    p.set(x, y, z, b.defaultBlockState());
                }
            }
        });
        hedge(p, plan);
        cut(p, plan, plan.wallOut + 0.5, 1.0, 2.0);
        // Highrock, with the leader's den at its foot
        double[] rock = plan.rock;
        int rockBase = Places.groundAt(rock[0], rock[1]);
        boulder(p, rock[0], rock[1], rock[2], rock[3], rockBase);
        steps(p, rock, rockBase, plan.gate + Math.PI * 0.5);
        cave(p, plan, plan.den(DenKind.LEADER));
        // the medicine den: a great rock set into the wall, a cleft in it, and a tunnel of ferns leading there
        double[] mr = plan.medicineRock;
        Den med = plan.den(DenKind.MEDICINE);
        boulder(p, mr[0], mr[1], mr[2], mr[3], Places.groundAt(mr[0], mr[1]));
        cleft(p, med, 0, false);
        around(p, med.x(), med.z(), med.length() + 5, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, med.x(), med.z(), med.door());
            if (a[0] < med.length() + 0.5 || a[0] > med.length() + 4.5 || Math.abs(a[1]) > 1.9) return;
            int g = p.ground(x, z);
            if (Math.abs(a[1]) <= 0.7) {
                p.clearGrowth(x, g, z, 2);
            } else {
                plant(p, x, g, z, Blocks.FERN.defaultBlockState());
                p.soft(x, g + 2, z, BUSH);
            }
            p.soft(x, g + 3, z, BUSH);
        });
        // the dens
        dome(p, plan, plan.den(DenKind.NURSERY), BRAMBLE, BUSH, 0.1);
        dome(p, plan, plan.den(DenKind.WARRIORS), BUSH, BEECH, 0.25);
        Den app = plan.den(DenKind.APPRENTICES);
        dome(p, plan, app, BUSH, BUSH, 0);
        ferns(p, app);
        stump(p, app);
        Den eld = plan.den(DenKind.ELDERS);
        dome(p, plan, eld, BUSH, BRAMBLE, 0.2);
        // a fallen tree across the elders' den
        double along = eld.door() + Math.PI * 0.5;
        log(p, eld.x() - Math.cos(along) * 4.5, eld.z() - Math.sin(along) * 4.5, along, 9, Builders.OAK_LOG, eld.height() + 1);
    }

    /** An old tree stump beside a den's door (Warrior Cats Events' stump if it is installed). */
    static void stump(ChunkPainter p, Den den) {
        double a = den.door() + 0.9;
        int x = (int) Math.floor(den.x() + Math.cos(a) * (den.radius() + 1.0)), z = (int) Math.floor(den.z() + Math.sin(a) * (den.radius() + 1.0));
        if (!p.inside(x, z)) return;
        int g = p.ground(x, z);
        BlockState stump = Builders.optional("warriorcats_events:tree_stump", Builders.OAK_LOG);
        p.set(x, g + 1, z, stump);
        if (stump == Builders.OAK_LOG) p.set(x, g + 2, z, Builders.OAK_LOG);
    }

    /**
     * WindClan: a hollow on the open moor ringed with gorse, one gap in it to come and go by. Tallrock stands at the
     * clearing's edge; the dens are burrows dug into the hollow's sides, the leader's beside Tallrock.
     */
    static void moor(ChunkPainter p, CampPlan plan) {
        Noise floor = new Noise(plan.seed, "moor.floor", 16, 2);
        around(p, plan.cx, plan.cz, plan.wallIn, (x, z, d) -> {
            int g = p.ground(x, z);
            p.clearGrowth(x, g, z, 30);
            if (!p.get(x, g, z).getFluidState().isEmpty()) g = drain(p, x, z, g);       // a flooded floor
            int top = level(p, x, z, g);
            p.set(x, top, z, d < plan.cliff + 2
                    ? patch(floor, x, z, Blocks.SAND, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.SAND)
                    : patch(floor, x, z, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.SAND));
        });
        hedge(p, plan);
        double[] rock = plan.rock;
        int base = Places.groundAt(rock[0], rock[1]);
        boulder(p, rock[0], rock[1], rock[2], rock[3], base);
        steps(p, rock, base, Math.atan2(plan.cz - rock[1], plan.cx - rock[0]));
        for (Den den : plan.dens) burrow(p, plan, den, den.kind() == DenKind.NURSERY ? GORSE : GORSE_FLOWER);
    }

    /**
     * RiverClan: the dry ground between the streams (lake) or an island in the reedbed (old forest). The water is the
     * wall where it reaches the camp; elsewhere a thick wall of bramble and willow, lined with reeds by the water.
     * A path through the reeds is the way in; the island's is a ford of stones to the bank. Willows lean over it.
     */
    static void river(ChunkPainter p, CampPlan plan) {
        boolean island = plan.style == Style.ISLAND;
        Noise floor = new Noise(plan.seed, "river.floor", 12, 2);
        if (island) {
            // the island itself: dry ground a block above the river all the way to the reeds, a muddy bank beyond
            around(p, plan.cx, plan.cz, plan.wallOut + 1.5, (x, z, d) -> {
                int top = p.ground(x, z);
                int solid = p.solidTop(x, z, top, top - 16);
                if (solid >= 64) return;
                for (int y = solid + 1; y <= 64; y++) {
                    p.set(x, y, z, y == 64 ? (d > plan.wallOut ? Blocks.MUD : Blocks.GRASS_BLOCK).defaultBlockState() : Blocks.DIRT.defaultBlockState());
                }
                p.clearAbove(x, 64, z, 2);
            });
        }
        around(p, plan.cx, plan.cz, plan.wallIn, (x, z, d) -> {
            int g = p.ground(x, z);
            p.clearGrowth(x, g, z, 30);
            if (!p.get(x, g, z).getFluidState().isEmpty()) return;
            int top = level(p, x, z, g);
            p.set(x, top, z, d > plan.clearing - 4
                    ? patch(floor, x, z, Blocks.MUD, Blocks.GRASS_BLOCK, Blocks.MOSS_BLOCK, Blocks.MUD)
                    : patch(floor, x, z, Blocks.GRASS_BLOCK, Blocks.MOSS_BLOCK, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK));
        });
        hedge(p, plan);
        // reeds along the water's edge, outside the wall
        around(p, plan.cx, plan.cz, plan.wallOut + 2.5, (x, z, d) -> {
            // (outside the hedge only: a reed set in the hedge's place falls when the water beside it drains, a gap)
            if (d <= plan.wallOut + 0.3 || plan.inGate(x + 0.5, z + 0.5)) return;
            int g = p.ground(x, z);
            BlockState under = p.get(x, g, z);
            if (!under.getFluidState().isEmpty() || !(under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.DIRT) || under.is(Blocks.MUD)
                    || under.is(Blocks.SAND) || under.is(Blocks.COARSE_DIRT) || under.is(Blocks.PODZOL))) return;
            if (!wetBeside(p, x, g, z)) return;
            int h = 2 + (int) (hash(x, 4, z) * 2);
            for (int y = 1; y <= h; y++) p.set(x, g + y, z, Blocks.SUGAR_CANE.defaultBlockState());
        });
        // willows by the water
        ClanWorld w = ClanWorld.current();
        int planted = 0;
        for (int i = 0; i < 24 && planted < 3; i++) {
            double a = plan.gate + Math.PI + (i % 2 == 0 ? 1 : -1) * (0.5 + (i / 2) * 0.45);
            // (on the island's bank, outside the wall: a willow standing in the wall hung its curtains over the camp
            // floor and its crown over the hedge, a ladder out)
            double at = island ? plan.wallOut + 1.2 : plan.wallOut + 2.5;
            double x = plan.cx + Math.cos(a) * at, z = plan.cz + Math.sin(a) * at;
            boolean wet = island || (w != null && w.sample(plan.cx + Math.cos(a) * (plan.wallOut + 6),
                    plan.cz + Math.sin(a) * (plan.wallOut + 6)).height() < 63.2);
            if (!wet || plan.inGate(x, z)) continue;
            willow(p, x, z, 5 + (int) (hash(i, 0, (int) plan.seed) * 2));
            planted++;
            i += 3;                                                                      // spread them out
        }
        if (island) ford(p, plan);
        dome(p, plan, plan.den(DenKind.LEADER), BUSH, WILLOW, 0.2);
        dome(p, plan, plan.den(DenKind.NURSERY), BRAMBLE, BUSH, 0.1);
        dome(p, plan, plan.den(DenKind.MEDICINE), WILLOW, BUSH, 0.3);
        dome(p, plan, plan.den(DenKind.ELDERS), WILLOW, BRAMBLE, 0.25);
        dome(p, plan, plan.den(DenKind.WARRIORS), WILLOW, BRAMBLE, 0.4);
        dome(p, plan, plan.den(DenKind.APPRENTICES), WILLOW, BUSH, 0.2);
    }

    private static boolean wetBeside(ChunkPainter p, int x, int y, int z) {
        for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (!p.readable(x + o[0], z + o[1])) continue;
            if (!p.get(x + o[0], y, z + o[1]).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** The island's ford: stones a stride apart from the reed wall's gap to the nearest dry bank. */
    private static void ford(ChunkPainter p, CampPlan plan) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return;
        double ux = Math.cos(plan.gate), uz = Math.sin(plan.gate);
        for (double t = plan.wallOut + 1; t < 80; t += 2) {
            double sx = plan.cx + ux * t, sz = plan.cz + uz * t;
            var s = w.sample(sx, sz);
            if (s.height() >= 63.2 && s.zone() != com.ninjacat.greatjourney.clanlands.layout.Zone.RIVER) break;
            int x = (int) Math.floor(sx), z = (int) Math.floor(sz);
            for (int[] o : new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                int bx = x + o[0], bz = z + o[1];
                if (!p.inside(bx, bz) || (o[0] + o[1] > 0 && hash(bx, 2, bz) < 0.5)) continue;
                int top = Math.max(63, p.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, bx, bz) - 1);
                if (!p.get(bx, top, bz).getFluidState().isEmpty()) top++;
                for (int y = top - 6; y <= top; y++) {
                    var at = p.get(bx, y, bz);
                    if (at.isAir() || !at.getFluidState().isEmpty() || y == top) {
                        p.set(bx, y, bz, y == top ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Builders.STONE);
                    }
                }
            }
        }
    }

    /**
     * ShadowClan: a dip deep in the pines inside a dense bramble wall, entered by one tunnel through the brambles.
     * Dens are hollowed in bramble thickets; the elders' lies under the roots of a fallen pine; the leader sleeps in a
     * hollow under a great rock. Tall pines lean over the wall and keep the camp dim; the old forest's is marshy.
     */
    static void pines(ChunkPainter p, CampPlan plan) {
        boolean marsh = plan.style == Style.MARSH_PINES;
        Noise floor = new Noise(plan.seed, "pines.floor", 12, 2);
        around(p, plan.cx, plan.cz, plan.wallIn, (x, z, d) -> {
            int g = p.ground(x, z);
            p.clearGrowth(x, g, z, 30);
            if (!p.get(x, g, z).getFluidState().isEmpty()) g = drain(p, x, z, g);       // a flooded floor
            int top = level(p, x, z, g);
            p.set(x, top, z, marsh
                    ? patch(floor, x, z, Blocks.MUD, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MOSS_BLOCK)
                    : patch(floor, x, z, Blocks.PODZOL, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MOSS_BLOCK));
        });
        if (marsh) puddles(p, plan);
        hedge(p, plan);
        // tall pines standing in the wall, two flanking the tunnel
        double[] bearings = {plan.gate + 0.42, plan.gate - 0.42, plan.gate + Math.PI, plan.gate + Math.PI + 1.05, plan.gate + Math.PI - 1.05};
        for (int i = 0; i < bearings.length; i++) {
            double at = plan.wallIn + 1.8;
            pine(p, plan.cx + Math.cos(bearings[i]) * at, plan.cz + Math.sin(bearings[i]) * at, 15 + (int) (hash(i, 1, (int) plan.seed) * 6));
        }
        double[] rock = plan.rock;
        boulder(p, rock[0], rock[1], rock[2], rock[3], Places.groundAt(rock[0], rock[1]));
        cave(p, plan, plan.den(DenKind.LEADER));
        dome(p, plan, plan.den(DenKind.NURSERY), BRAMBLE, BRAMBLE, 0);
        dome(p, plan, plan.den(DenKind.MEDICINE), BRAMBLE, PINE, 0.3);
        dome(p, plan, plan.den(DenKind.WARRIORS), BRAMBLE, PINE, 0.25);
        dome(p, plan, plan.den(DenKind.APPRENTICES), BRAMBLE, BUSH, 0.15);
        Den eld = plan.den(DenKind.ELDERS);
        dome(p, plan, eld, BRAMBLE, PINE, 0.2);
        // the fallen pine: its trunk lies away from the den, its torn-up roots stand over it
        double along = eld.door() + Math.PI * 0.5;
        double ux = Math.cos(along), uz = Math.sin(along);
        log(p, eld.x() + ux * (eld.radius() + 0.6), eld.z() + uz * (eld.radius() + 0.6), along, 9, Blocks.SPRUCE_LOG.defaultBlockState(), 0);
        around(p, eld.x(), eld.z(), eld.radius() + 3.5, (x, z, d) -> {
            double[] a = axis(x + 0.5, z + 0.5, eld.x(), eld.z(), along);
            if (Math.abs(a[0] - (eld.radius() - 0.2)) > 0.7 || Math.abs(a[1]) > 2.8) return;
            int g = p.ground(x, z);
            int h = (int) Math.round(2.8 * Math.sqrt(Math.max(0, 1 - Math.pow(a[1] / 2.8, 2)))) + 1;
            for (int y = 1; y <= h; y++) {
                double k = hash(x, y, z);
                p.set(x, g + y, z, (k < 0.4 ? Blocks.ROOTED_DIRT : k < 0.7 ? Blocks.MANGROVE_ROOTS : Blocks.PODZOL).defaultBlockState());
            }
        });
    }

    /** A few small, shallow puddles on a marshy camp floor, clear of the dens and the middle. */
    private static void puddles(ChunkPainter p, CampPlan plan) {
        for (int i = 0; i < 4; i++) {
            double a = plan.gate + 0.7 + i * 1.55 + hash(i, 2, (int) plan.seed) * 0.4;
            double at = 6 + hash(i, 3, (int) plan.seed) * (plan.clearing * 0.35);
            double px = plan.cx + Math.cos(a) * at, pz = plan.cz + Math.sin(a) * at;
            boolean clear = true;
            for (Den den : plan.dens) if (Math.hypot(den.x() - px, den.z() - pz) < den.radius() + 3) clear = false;
            if (plan.rock != null && Math.hypot(plan.rock[0] - px, plan.rock[1] - pz) < plan.rock[2] + 3) clear = false;
            if (!clear || plan.inGate(px, pz)) continue;
            around(p, px, pz, 1.8, (x, z, d) -> {
                int g = p.ground(x, z);
                // water only where the ground round it is as high, so a puddle never has an open side
                boolean held = true;
                for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (p.wetTop(x + o[0], z + o[1], g + 3, g - 4) < g) held = false;
                }
                if (d < 1.2 && held) {
                    p.set(x, g, z, Blocks.WATER.defaultBlockState());
                    p.set(x, g - 1, z, Blocks.MUD.defaultBlockState());
                } else {
                    p.set(x, g, z, Blocks.MUD.defaultBlockState());
                }
            });
        }
    }
}
